package com.stationannouncer.mtraddon.analytics;

import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the rolling window of DEPARTURE events into the per-line and per-station metrics
 * behind {@code /dispatch/api/analytics} and {@code /dispatch stats}.
 *
 * <p><b>Metric definitions</b> (all over the last {@code analytics.windowMinutes}):</p>
 * <ul>
 *   <li><b>On time</b> — a departure whose schedule deviation satisfies
 *       {@code |deviation| <= analytics.onTimeToleranceSeconds}. MTR only refreshes a
 *       vehicle's deviation when it stops, so the value logged at a departure is exactly
 *       "how late this train was at this stop".</li>
 *   <li><b>Dwell overrun</b> — {@code measured dwell − the dwell baked into the path}.
 *       The baked value already carries Feature 2's per-route override, so an express
 *       with a 10 s override is not scored against the platform's 25 s default.</li>
 *   <li><b>Headway</b> — the gap between consecutive departures of the SAME line at the
 *       SAME platform (a headway is a per-point quantity; pooling platforms would mix
 *       directions). Per-line figures pool those gaps across the line's platforms.</li>
 *   <li><b>Scheduled headway</b> — derived from depot frequencies at record time
 *       ({@link AnalyticsRecorder}); when unavailable the median observed gap is used
 *       instead and the payload says {@code headwaySource: "observed"}.</li>
 *   <li><b>Bunching</b> — a gap shorter than {@code analytics.bunchingFraction} of the
 *       reference headway.</li>
 *   <li><b>Headway irregularity</b> (stations, for the map heat view) — the coefficient
 *       of variation (standard deviation ÷ mean) of every gap observed at that station's
 *       platforms. 0 = metronomic, ≥1 = wildly uneven.</li>
 * </ul>
 *
 * <p><b>Thread:</b> {@link #ingest} and {@link #recompute} run only on the analytics
 * writer thread; {@link #reset} runs on the server thread while no writer exists.
 * {@link #get} is read from Jetty workers and the server thread (command) — it returns a
 * volatile immutable snapshot, so readers never touch the window itself. Recomputation
 * is throttled to {@code analytics.aggregateSeconds}.</p>
 */
public final class AnalyticsAggregator {
    /** How many headway samples the strip chart carries per line (newest kept). */
    private static final int MAX_SERIES_POINTS = 240;
    /** How many bunching alerts are reported per line (newest kept). */
    private static final int MAX_BUNCHING_ALERTS = 20;
    /** A gap longer than this is a service break, not a headway; kept out of the averages. */
    private static final long MAX_SENSIBLE_GAP_MILLIS = 6 * 60 * 60_000L;

    /** dimension → departures in the window, in ingest order. Writer thread only. */
    private static final Map<String, List<AnalyticsEvent>> WINDOW = new ConcurrentHashMap<>();
    /** dimension → the last published aggregate. */
    private static volatile Map<String, Aggregate> published = Map.of();
    private static volatile long lastComputedAt;

    private AnalyticsAggregator() {
    }

    // ------------------------------------------------------------------- results ----

    /** One bunching alert: two trains of a line closer together than they should be. */
    public record Bunching(long atMillis, long platformId, String platformName,
                           long stationId, String stationName, long gapMillis) {
    }

    /** Per-line scorecard. All millis; {@code -1} means "not enough data". */
    public static final class LineStats {
        public long routeId;
        public String name = "";
        public String number = "";
        public int color;
        public int departures;
        public int onTime;
        public double onTimePercent;
        public long averageDeviationMillis;
        public long worstLateMillis;
        public long worstEarlyMillis;
        public long averageDwellMillis;
        public long averageDwellOverrunMillis;
        public long maxDwellOverrunMillis;
        public int headwaySamples;
        public long averageHeadwayMillis = -1;
        public long medianHeadwayMillis = -1;
        public long minHeadwayMillis = -1;
        public long maxHeadwayMillis = -1;
        public long scheduledHeadwayMillis = -1;
        /** {@code scheduled} (depot frequencies), {@code observed} (median gap) or {@code none}. */
        public String headwaySource = "none";
        public List<Bunching> bunching = List.of();
        /** {@code {atMillis, gapMillis}} pairs, oldest first, for the strip chart. */
        public List<long[]> series = List.of();
    }

    /** Per-station scores for the map heat view. */
    public static final class StationStats {
        public long stationId;
        public String name = "";
        public int departures;
        public double onTimePercent;
        public long averageDwellOverrunMillis;
        public long maxDwellOverrunMillis;
        public int headwaySamples;
        public double headwayIrregularity;
    }

    /** An immutable published snapshot for one dimension, with its payload pre-rendered. */
    public static final class Aggregate {
        public final String dimension;
        public final long computedAt;
        public final int departures;
        public final List<LineStats> lines;
        public final List<StationStats> stations;
        /** The {@code data} object served by {@code /dispatch/api/analytics}; never mutated after publish. */
        public final JsonObject json;

        Aggregate(String dimension, long computedAt, int departures,
                  List<LineStats> lines, List<StationStats> stations, JsonObject json) {
            this.dimension = dimension;
            this.computedAt = computedAt;
            this.departures = departures;
            this.lines = lines;
            this.stations = stations;
            this.json = json;
        }
    }

    // ------------------------------------------------------------------ lifecycle ----

    /** Server thread, SERVER_STARTED (no writer running). */
    static void reset() {
        WINDOW.clear();
        published = Map.of();
        lastComputedAt = 0;
    }

    /** Writer thread: fold a flushed batch into the window. Arrival events carry no metrics. */
    static void ingest(List<AnalyticsEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        for (AnalyticsEvent event : events) {
            if (event.arrival()) {
                continue;
            }
            WINDOW.computeIfAbsent(event.dimension(), key -> new ArrayList<>()).add(event);
        }
        trim(config);
    }

    /** Drops events that fell out of the window, then enforces the hard per-dimension cap. */
    private static void trim(AddonServerConfig.Analytics config) {
        long cutoff = System.currentTimeMillis() - config.windowMinutes * 60_000L;
        WINDOW.values().forEach(events -> {
            events.removeIf(event -> event.atMillis() < cutoff);
            int excess = events.size() - config.maxWindowEvents;
            if (excess > 0) {
                events.subList(0, excess).clear();
            }
        });
    }

    /** Writer thread. Recomputes at most every {@code analytics.aggregateSeconds}. */
    static void recompute(boolean force) {
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        long now = System.currentTimeMillis();
        if (!force && now - lastComputedAt < config.aggregateSeconds * 1_000L) {
            return;
        }
        lastComputedAt = now;
        trim(config);
        Map<String, Aggregate> result = new LinkedHashMap<>();
        WINDOW.forEach((dimension, events) -> result.put(dimension, build(dimension, events, config, now)));
        published = Map.copyOf(result);
    }

    /** Any thread: the last published aggregate for a dimension, or null. */
    public static Aggregate get(String dimension) {
        return published.get(dimension);
    }

    /** Any thread: every dimension that has data (for the command's "no data here" hint). */
    public static List<String> dimensions() {
        return new ArrayList<>(published.keySet());
    }

    // ----------------------------------------------------------------- computation ----

    private static Aggregate build(String dimension, List<AnalyticsEvent> windowEvents,
                                   AddonServerConfig.Analytics config, long now) {
        List<AnalyticsEvent> events = new ArrayList<>(windowEvents);
        events.sort(Comparator.comparingLong(AnalyticsEvent::atMillis));

        // --- gaps: consecutive departures of one line at one platform.
        Map<String, List<AnalyticsEvent>> byPoint = new LinkedHashMap<>();
        for (AnalyticsEvent event : events) {
            byPoint.computeIfAbsent(event.routeId() + "@" + event.platformId(), key -> new ArrayList<>()).add(event);
        }
        Map<Long, List<Gap>> gapsByRoute = new HashMap<>();
        Map<Long, List<Gap>> gapsByStation = new HashMap<>();
        for (List<AnalyticsEvent> point : byPoint.values()) {
            for (int i = 1; i < point.size(); i++) {
                AnalyticsEvent event = point.get(i);
                long gap = event.atMillis() - point.get(i - 1).atMillis();
                if (gap <= 0 || gap > MAX_SENSIBLE_GAP_MILLIS) {
                    continue;
                }
                Gap entry = new Gap(event, gap);
                gapsByRoute.computeIfAbsent(event.routeId(), key -> new ArrayList<>()).add(entry);
                if (event.stationId() != 0) {
                    gapsByStation.computeIfAbsent(event.stationId(), key -> new ArrayList<>()).add(entry);
                }
            }
        }

        // --- per line
        Map<Long, List<AnalyticsEvent>> byRoute = new LinkedHashMap<>();
        for (AnalyticsEvent event : events) {
            byRoute.computeIfAbsent(event.routeId(), key -> new ArrayList<>()).add(event);
        }
        List<LineStats> lines = new ArrayList<>();
        for (Map.Entry<Long, List<AnalyticsEvent>> entry : byRoute.entrySet()) {
            lines.add(buildLine(entry.getKey(), entry.getValue(),
                    gapsByRoute.getOrDefault(entry.getKey(), List.of()), config));
        }
        lines.sort(Comparator.comparing((LineStats line) -> displayName(line.name)));

        // --- per station
        Map<Long, List<AnalyticsEvent>> byStation = new LinkedHashMap<>();
        for (AnalyticsEvent event : events) {
            if (event.stationId() != 0) {
                byStation.computeIfAbsent(event.stationId(), key -> new ArrayList<>()).add(event);
            }
        }
        List<StationStats> stations = new ArrayList<>();
        for (Map.Entry<Long, List<AnalyticsEvent>> entry : byStation.entrySet()) {
            stations.add(buildStation(entry.getKey(), entry.getValue(),
                    gapsByStation.getOrDefault(entry.getKey(), List.of()), config));
        }
        stations.sort(Comparator.comparing((StationStats station) -> displayName(station.name)));

        return new Aggregate(dimension, now, events.size(), List.copyOf(lines), List.copyOf(stations),
                toJson(dimension, now, events.size(), lines, stations, config));
    }

    private record Gap(AnalyticsEvent event, long millis) {
    }

    private static LineStats buildLine(long routeId, List<AnalyticsEvent> events, List<Gap> gaps,
                                       AddonServerConfig.Analytics config) {
        LineStats line = new LineStats();
        line.routeId = routeId;
        AnalyticsEvent newest = events.get(events.size() - 1);
        line.name = newest.routeName();
        line.number = newest.routeNumber();
        line.color = newest.routeColor();
        line.departures = events.size();

        long toleranceMillis = config.onTimeToleranceSeconds * 1_000L;
        long deviationSum = 0;
        long dwellSum = 0;
        long overrunSum = 0;
        int overrunSamples = 0;
        for (AnalyticsEvent event : events) {
            long deviation = event.deviationMs();
            deviationSum += deviation;
            if (Math.abs(deviation) <= toleranceMillis) {
                line.onTime++;
            }
            line.worstLateMillis = Math.max(line.worstLateMillis, deviation);
            line.worstEarlyMillis = Math.min(line.worstEarlyMillis, deviation);
            dwellSum += event.dwellMs();
            if (event.scheduledDwellMs() > 0) {
                long overrun = event.dwellMs() - event.scheduledDwellMs();
                overrunSum += overrun;
                overrunSamples++;
                line.maxDwellOverrunMillis = Math.max(line.maxDwellOverrunMillis, overrun);
            }
        }
        line.onTimePercent = round1(100.0 * line.onTime / line.departures);
        line.averageDeviationMillis = deviationSum / line.departures;
        line.averageDwellMillis = dwellSum / line.departures;
        line.averageDwellOverrunMillis = overrunSamples == 0 ? 0 : overrunSum / overrunSamples;

        // Headway. The scheduled value is whatever the most recent departure derived from
        // the depot frequencies; 0 there means "not derivable", so we observe it instead.
        long scheduled = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).schedHeadwayMs() > 0) {
                scheduled = events.get(i).schedHeadwayMs();
                break;
            }
        }
        line.headwaySamples = gaps.size();
        if (!gaps.isEmpty()) {
            long[] values = gaps.stream().mapToLong(Gap::millis).toArray();
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = 0;
            for (long value : values) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            long[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            line.averageHeadwayMillis = sum / values.length;
            line.medianHeadwayMillis = sorted[sorted.length / 2];
            line.minHeadwayMillis = min;
            line.maxHeadwayMillis = max;
        }
        if (scheduled > 0) {
            line.scheduledHeadwayMillis = scheduled;
            line.headwaySource = "scheduled";
        } else if (line.medianHeadwayMillis > 0) {
            line.scheduledHeadwayMillis = line.medianHeadwayMillis;
            line.headwaySource = "observed";
        }

        if (line.scheduledHeadwayMillis > 0) {
            long threshold = (long) (line.scheduledHeadwayMillis * config.bunchingFraction);
            List<Bunching> alerts = new ArrayList<>();
            for (Gap gap : gaps) {
                if (gap.millis() < threshold) {
                    AnalyticsEvent event = gap.event();
                    alerts.add(new Bunching(event.atMillis(), event.platformId(), event.platformName(),
                            event.stationId(), event.stationName(), gap.millis()));
                }
            }
            alerts.sort(Comparator.<Bunching>comparingLong(Bunching::atMillis).reversed());
            line.bunching = List.copyOf(alerts.subList(0, Math.min(alerts.size(), MAX_BUNCHING_ALERTS)));
        }

        List<long[]> series = new ArrayList<>(gaps.size());
        List<Gap> ordered = new ArrayList<>(gaps);
        ordered.sort(Comparator.comparingLong((Gap gap) -> gap.event().atMillis()));
        for (Gap gap : ordered) {
            series.add(new long[]{gap.event().atMillis(), gap.millis()});
        }
        if (series.size() > MAX_SERIES_POINTS) {
            series = new ArrayList<>(series.subList(series.size() - MAX_SERIES_POINTS, series.size()));
        }
        line.series = List.copyOf(series);
        return line;
    }

    private static StationStats buildStation(long stationId, List<AnalyticsEvent> events, List<Gap> gaps,
                                             AddonServerConfig.Analytics config) {
        StationStats station = new StationStats();
        station.stationId = stationId;
        station.name = events.get(events.size() - 1).stationName();
        station.departures = events.size();

        long toleranceMillis = config.onTimeToleranceSeconds * 1_000L;
        long overrunSum = 0;
        int overrunSamples = 0;
        int onTime = 0;
        for (AnalyticsEvent event : events) {
            if (Math.abs(event.deviationMs()) <= toleranceMillis) {
                onTime++;
            }
            if (event.scheduledDwellMs() > 0) {
                long overrun = event.dwellMs() - event.scheduledDwellMs();
                overrunSum += overrun;
                overrunSamples++;
                station.maxDwellOverrunMillis = Math.max(station.maxDwellOverrunMillis, overrun);
            }
        }
        station.onTimePercent = round1(100.0 * onTime / station.departures);
        station.averageDwellOverrunMillis = overrunSamples == 0 ? 0 : overrunSum / overrunSamples;

        station.headwaySamples = gaps.size();
        if (gaps.size() >= 2) {
            double mean = 0;
            for (Gap gap : gaps) {
                mean += gap.millis();
            }
            mean /= gaps.size();
            if (mean > 0) {
                double variance = 0;
                for (Gap gap : gaps) {
                    double delta = gap.millis() - mean;
                    variance += delta * delta;
                }
                variance /= gaps.size();
                station.headwayIrregularity = round3(Math.sqrt(variance) / mean);
            }
        }
        return station;
    }

    // --------------------------------------------------------------------- json ----

    private static JsonObject toJson(String dimension, long computedAt, int departures,
                                     List<LineStats> lines, List<StationStats> stations,
                                     AddonServerConfig.Analytics config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("enabled", true);
        root.addProperty("dimension", dimension);
        root.addProperty("computedAt", computedAt);
        root.addProperty("windowMinutes", config.windowMinutes);
        root.addProperty("aggregateSeconds", config.aggregateSeconds);
        root.addProperty("onTimeToleranceSeconds", config.onTimeToleranceSeconds);
        root.addProperty("bunchingFraction", config.bunchingFraction);
        root.addProperty("departures", departures);
        long[] stats = AnalyticsRecorder.stats();
        root.addProperty("recorded", stats[0]);
        root.addProperty("dropped", stats[1]);

        JsonArray lineArray = new JsonArray();
        for (LineStats line : lines) {
            JsonObject json = new JsonObject();
            json.addProperty("id", Long.toString(line.routeId));
            json.addProperty("name", line.name);
            json.addProperty("number", line.number);
            json.addProperty("color", line.color);
            json.addProperty("departures", line.departures);
            json.addProperty("onTime", line.onTime);
            json.addProperty("onTimePct", line.onTimePercent);
            json.addProperty("avgDeviationMs", line.averageDeviationMillis);
            json.addProperty("worstLateMs", line.worstLateMillis);
            json.addProperty("worstEarlyMs", line.worstEarlyMillis);
            json.addProperty("avgDwellMs", line.averageDwellMillis);
            json.addProperty("avgDwellOverrunMs", line.averageDwellOverrunMillis);
            json.addProperty("maxDwellOverrunMs", line.maxDwellOverrunMillis);
            json.addProperty("headwaySamples", line.headwaySamples);
            json.addProperty("avgHeadwayMs", line.averageHeadwayMillis);
            json.addProperty("medianHeadwayMs", line.medianHeadwayMillis);
            json.addProperty("minHeadwayMs", line.minHeadwayMillis);
            json.addProperty("maxHeadwayMs", line.maxHeadwayMillis);
            json.addProperty("refHeadwayMs", line.scheduledHeadwayMillis);
            json.addProperty("headwaySource", line.headwaySource);
            JsonArray bunching = new JsonArray();
            for (Bunching alert : line.bunching) {
                JsonObject alertJson = new JsonObject();
                alertJson.addProperty("atMs", alert.atMillis());
                alertJson.addProperty("platformId", Long.toString(alert.platformId()));
                alertJson.addProperty("platform", alert.platformName());
                alertJson.addProperty("stationId", Long.toString(alert.stationId()));
                alertJson.addProperty("station", alert.stationName());
                alertJson.addProperty("gapMs", alert.gapMillis());
                bunching.add(alertJson);
            }
            json.add("bunching", bunching);
            JsonArray series = new JsonArray();
            for (long[] point : line.series) {
                JsonArray pair = new JsonArray();
                pair.add(point[0]);
                pair.add(point[1]);
                series.add(pair);
            }
            json.add("series", series);
            lineArray.add(json);
        }
        root.add("lines", lineArray);

        JsonArray stationArray = new JsonArray();
        for (StationStats station : stations) {
            JsonObject json = new JsonObject();
            json.addProperty("id", Long.toString(station.stationId));
            json.addProperty("name", station.name);
            json.addProperty("departures", station.departures);
            json.addProperty("onTimePct", station.onTimePercent);
            json.addProperty("avgDwellOverrunMs", station.averageDwellOverrunMillis);
            json.addProperty("maxDwellOverrunMs", station.maxDwellOverrunMillis);
            json.addProperty("headwaySamples", station.headwaySamples);
            json.addProperty("headwayIrregularity", station.headwayIrregularity);
            stationArray.add(json);
        }
        root.add("stations", stationArray);
        return root;
    }

    /** The payload served when the feature is off or a dimension has no data yet. */
    public static JsonObject emptyJson(String dimension, boolean enabled) {
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("enabled", enabled);
        root.addProperty("dimension", dimension);
        root.addProperty("computedAt", System.currentTimeMillis());
        root.addProperty("windowMinutes", config.windowMinutes);
        root.addProperty("aggregateSeconds", config.aggregateSeconds);
        root.addProperty("onTimeToleranceSeconds", config.onTimeToleranceSeconds);
        root.addProperty("bunchingFraction", config.bunchingFraction);
        root.addProperty("departures", 0);
        long[] stats = AnalyticsRecorder.stats();
        root.addProperty("recorded", stats[0]);
        root.addProperty("dropped", stats[1]);
        root.add("lines", new JsonArray());
        root.add("stations", new JsonArray());
        return root;
    }

    // ------------------------------------------------------------------- helpers ----

    /** MTR names are {@code "English|Other"}; everything user-facing uses the first segment. */
    public static String displayName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int bar = name.indexOf('|');
        return bar < 0 ? name : name.substring(0, bar);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
