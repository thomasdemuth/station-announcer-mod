package com.stationannouncer.mtraddon.analytics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The durable half of the analytics feature: an <b>append-only JSONL log</b> at
 * {@code <save>/station-announcer-addon/analytics/YYYY-MM-DD.jsonl}, one event per line,
 * rotated by local calendar day and pruned to {@code analytics.retentionDays}.
 *
 * <p><b>Why JSONL and not SQLite</b> (decision recorded in PROGRESS.md): SQLite would
 * need a JDBC driver shaded into the mod — a new dependency on a mod that currently has
 * none — for a workload that is 99 % sequential appends and one bounded tail-read at
 * startup. JSONL needs no dependency, matches the existing {@code AddonStore} pattern,
 * is greppable/importable by Thomas without tooling, makes retention a file delete
 * instead of a DELETE + VACUUM, and cannot corrupt the world save if the process dies
 * mid-write (at worst the last line is truncated and skipped on replay). The derived
 * metrics are served from an in-memory window, so the log is never queried at runtime.</p>
 *
 * <p><b>Thread:</b> {@link #append} and {@link #replayRecent} run on the analytics writer
 * thread, except for one synchronous {@code append} on the server thread during
 * SERVER_STOPPING (after the writer has been shut down, so there is never more than one
 * writer). {@link #open}/{@link #close} run on the server thread with no writer alive.
 * Nothing here ever runs on a simulator or server tick.</p>
 */
public final class AnalyticsStore {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SUFFIX = ".jsonl";
    /** Safety valve for the startup replay so a huge log can never stall server start. */
    private static final int MAX_REPLAY_LINES = 200_000;

    private static Path directory;
    private static int retentionDays;
    /** The day currently being written to; a change triggers rotation + a retention pass. */
    private static String currentDay = "";

    private AnalyticsStore() {
    }

    /** Server thread, SERVER_STARTED. */
    static void open(Path analyticsDirectory, int retention) {
        directory = analyticsDirectory;
        retentionDays = retention;
        currentDay = "";
        try {
            Files.createDirectories(analyticsDirectory);
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not create analytics directory {}", analyticsDirectory, e);
            directory = null;
            return;
        }
        prune();
    }

    /** Server thread, SERVER_STOPPING (after the final flush). */
    static void close() {
        directory = null;
        currentDay = "";
    }

    /**
     * Appends a batch, splitting it across day files when a batch straddles midnight.
     * One open/close per file per flush (every {@code analytics.flushSeconds}); the
     * events are already serialized in memory, so the file is held for microseconds.
     */
    static void append(List<AnalyticsEvent> events) {
        Path dir = directory;
        if (dir == null || events.isEmpty()) {
            return;
        }
        String day = null;
        StringBuilder buffer = new StringBuilder(events.size() * 200);
        for (AnalyticsEvent event : events) {
            String eventDay = dayOf(event.atMillis());
            if (day == null) {
                day = eventDay;
            } else if (!day.equals(eventDay)) {
                writeChunk(dir, day, buffer);
                buffer.setLength(0);
                day = eventDay;
            }
            buffer.append(event.toJson()).append('\n');
        }
        if (day != null && buffer.length() > 0) {
            writeChunk(dir, day, buffer);
        }
    }

    private static void writeChunk(Path dir, String day, StringBuilder buffer) {
        if (!day.equals(currentDay)) {
            // Rotation: a new calendar day started while the server was up. Prune here
            // too so a long-running server does not keep files forever.
            boolean first = currentDay.isEmpty();
            currentDay = day;
            if (!first) {
                prune();
            }
        }
        Path file = dir.resolve(day + SUFFIX);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writer.write(buffer.toString());
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not append to analytics log {}", file, e);
        }
    }

    /**
     * Reads back the tail of the log so a restart does not blank the dashboard: today's
     * file plus (near midnight) yesterday's, keeping only events inside the metric
     * window. Bounded by {@value #MAX_REPLAY_LINES} lines per file.
     */
    static List<AnalyticsEvent> replayRecent(AddonServerConfig.Analytics config) {
        List<AnalyticsEvent> events = new ArrayList<>();
        Path dir = directory;
        if (dir == null) {
            return events;
        }
        long cutoff = System.currentTimeMillis() - config.windowMinutes * 60_000L;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (LocalDate date : new LocalDate[]{today.minusDays(1), today}) {
            Path file = dir.resolve(DAY.format(date) + SUFFIX);
            if (!Files.isReadable(file)) {
                continue;
            }
            int lines = 0;
            try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
                for (String line : (Iterable<String>) stream::iterator) {
                    if (++lines > MAX_REPLAY_LINES) {
                        break;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    AnalyticsEvent event = parse(line);
                    if (event != null && event.atMillis() >= cutoff) {
                        events.add(event);
                    }
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not replay analytics log {}", file, e);
            }
        }
        if (!events.isEmpty()) {
            StationAnnouncer.LOGGER.info("Replayed {} analytics events from the last {} minutes",
                    events.size(), config.windowMinutes);
        }
        return events;
    }

    /** A truncated final line (process killed mid-write) simply yields null and is skipped. */
    private static AnalyticsEvent parse(String line) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            return AnalyticsEvent.fromJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes day files older than the retention window. Called on open and on rotation. */
    private static void prune() {
        Path dir = directory;
        if (dir == null) {
            return;
        }
        LocalDate oldest = LocalDate.now(ZoneId.systemDefault()).minusDays(Math.max(0, retentionDays - 1));
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).forEach(path -> {
                String name = path.getFileName().toString();
                String day = name.substring(0, name.length() - SUFFIX.length());
                try {
                    if (LocalDate.parse(day, DAY).isBefore(oldest)) {
                        Files.deleteIfExists(path);
                        StationAnnouncer.LOGGER.info("Pruned expired analytics log {}", name);
                    }
                } catch (Exception ignored) {
                    // Not one of ours (or an unparseable name) — leave it alone.
                }
            });
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not prune analytics logs in {}", dir, e);
        }
    }

    private static String dayOf(long millis) {
        return DAY.format(LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }
}
