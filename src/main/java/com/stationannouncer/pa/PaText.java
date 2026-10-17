package com.stationannouncer.pa;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PA's small text language, shared by server and client. Everything rides
 * inside the existing announcement strings, so storage, packets and older
 * pools are unchanged:
 * <ul>
 *   <li>{@code {station}}: the MTR station the PA source stands in. Filled in
 *       on the SERVER at fire time from the source's own position (the box may
 *       be far from the listener), falling back to "this station".</li>
 *   <li>{@code {time}}: the wall clock, HH:MM. Filled in on each CLIENT so it
 *       matches that player's PIDS clocks.</li>
 *   <li>{@code {shown|spoken}}: shown in chat / on screens one way, spoken
 *       another ("{Bklyn|Brooklyn}"). Either side may be empty.</li>
 *   <li>{@code {off}} at the start of a pool entry: the message is kept but
 *       never picked (the control box's per-message switch).</li>
 *   <li>{@code {access}}: where the platform's step-free access is ("The elevator
 *       is at the rear of the platform."), from the station's layout scan; "" when
 *       the station has not been scanned. SERVER-filled.</li>
 *   <li>Train tokens {@code {route}}, {@code {dest}}, {@code {next}},
 *       {@code {nextstop}} (a whole sentence: "The next stop is X." / "This is the
 *       last stop on this train.") and {@code {platform}}: the train a
 *       train-timed announcement is about; in an ordinary message the next train
 *       due at the source's platform when known, else "". SERVER-filled.</li>
 * </ul>
 * Anything else in braces is left alone, so ordinary text never breaks.
 */
public final class PaText {
    public static final String STATION = "{station}";
    public static final String TIME = "{time}";
    public static final String DISABLED_PREFIX = "{off}";
    public static final String FALLBACK_STATION = "this station";
    public static final String ACCESS = "{access}";
    public static final String ROUTE = "{route}";
    public static final String DEST = "{dest}";
    public static final String NEXT = "{next}";
    public static final String NEXT_STOP = "{nextstop}";
    public static final String PLATFORM = "{platform}";
    /** Every server-filled token besides {station}, for cheap "anything to do?" tests. */
    private static final String[] CONTEXT_TOKENS = {ACCESS, ROUTE, DEST, NEXT, NEXT_STOP, PLATFORM};
    private static final String[] TRAIN_TOKENS = {ROUTE, DEST, NEXT, NEXT_STOP, PLATFORM};

    /**
     * What a train-timed announcement knows about its train (or, for an ordinary
     * message, the next train due at the source's platform). Plain strings, so the
     * common code never sees an MTR type; any field may be "".
     */
    public record Train(long platformId, String route, String destination, String next, boolean terminating,
                        String platform, String access, String station) {
        public static final Train NONE = new Train(0, "", "", "", false, "", "");

        /** Without a station: {station} then stays the PA source's own (filled from its position). */
        public Train(long platformId, String route, String destination, String next, boolean terminating,
                     String platform, String access) {
            this(platformId, route, destination, next, terminating, platform, access, "");
        }

        public Train withAccess(String value) {
            return new Train(platformId, route, destination, next, terminating, platform, value, station);
        }

        /** On a train, {station} is the stop being served — set here. */
        public Train withStation(String value) {
            return new Train(platformId, route, destination, next, terminating, platform, access, value);
        }
    }

    /**
     * Server-side context for a source with no particular train: the next train at
     * its nearest platform and that platform's access phrase. Installed by the MTR
     * module; {@code null} result = nothing known.
     */
    public static volatile BiFunction<World, BlockPos, Train> serverContext;

    /** {shown|spoken}: no braces inside, exactly one bar. */
    private static final Pattern SAY_AS = Pattern.compile("\\{([^{}|]*)\\|([^{}|]*)\\}");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    /** A space an empty fill left before punctuation ("Next stop is ." → "Next stop is."). */
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile(" +([.,!?;:])");

    /**
     * Server-side station name lookup ({@code null} = not in a station / no MTR),
     * installed by the MTR module. Kept apart from {@link #clientStation} because
     * an integrated server runs both in one JVM.
     */
    public static volatile BiFunction<World, BlockPos, String> serverStation;
    /** Client-side station name lookup for previews and PIDS screens. */
    public static volatile Function<BlockPos, String> clientStation;

    private PaText() {
    }

    // ------------------------------------------------------------- pool entries

    /** One message of a pool, as the editor sees it. */
    public record Entry(String text, boolean enabled) {
    }

    public static boolean isDisabled(String message) {
        return message.startsWith(DISABLED_PREFIX);
    }

    public static String stripDisabled(String message) {
        return isDisabled(message) ? message.substring(DISABLED_PREFIX.length()).trim() : message;
    }

    /** Every entry of a "||"-separated pool, switched-off ones included. */
    public static List<Entry> entries(String pool, Function<String, String[]> splitter) {
        List<Entry> out = new ArrayList<>();
        for (String raw : splitter.apply(pool)) {
            out.add(new Entry(stripDisabled(raw), !isDisabled(raw)));
        }
        return out;
    }

    /** The inverse of {@link #entries}; blank messages are dropped. */
    public static String join(List<Entry> entries, String separator) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            String text = clean(entry.text());
            if (text.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ').append(separator).append(' ');
            }
            if (!entry.enabled()) {
                out.append(DISABLED_PREFIX);
            }
            out.append(text);
        }
        return out.toString();
    }

    /** One line, no stray separators: what a single message may contain. */
    public static String clean(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        while (flat.contains("||")) {
            flat = flat.replace("||", "|");
        }
        return flat.trim();
    }

    // ------------------------------------------------------------------ tokens

    /**
     * Replaces {@code {station}} and/or {@code {time}} where a value is given;
     * a {@code null} value leaves that token for a later stage.
     */
    public static String fill(String text, @Nullable String station, @Nullable String time) {
        String out = text;
        if (station != null && out.contains(STATION)) {
            out = out.replace(STATION, station.isBlank() ? FALLBACK_STATION : station);
        }
        if (time != null && out.contains(TIME)) {
            out = out.replace(TIME, time);
        }
        return out;
    }

    /** True when the text uses any train token ({route}, {dest}, …). */
    public static boolean usesTrainTokens(String text) {
        return containsAny(text, TRAIN_TOKENS);
    }

    private static boolean containsAny(String text, String[] tokens) {
        if (text.indexOf('{') < 0) {
            return false;
        }
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills {access} and the train tokens from {@code train}; a null train leaves
     * them alone. Empty values collapse the surrounding spaces.
     */
    public static String fillTrain(String text, @Nullable Train train) {
        boolean station = train != null && train.station() != null && !train.station().isBlank() && text.contains(STATION);
        if (train == null || !station && !containsAny(text, CONTEXT_TOKENS)) {
            return text;
        }
        String next = train.next() == null ? "" : train.next();
        String nextStop = train.terminating() || next.isBlank()
                ? (train.terminating() ? "This is the last stop on this train." : "")
                : "The next stop is " + next + ".";
        String withStation = train.station() == null || train.station().isBlank() ? text
                : text.replace(STATION, train.station());
        String filled = tidy(withStation.replace(ACCESS, train.access())
                .replace(ROUTE, train.route())
                .replace(DEST, train.destination())
                .replace(NEXT_STOP, nextStop)
                .replace(NEXT, next)
                .replace(PLATFORM, train.platform()));
        return SPACE_BEFORE_PUNCT.matcher(filled).replaceAll("$1");
    }

    /** The server's station fill for a source at {@code pos}. */
    public static String fillServer(World world, BlockPos pos, String text) {
        return fillServer(world, pos, text, null);
    }

    /**
     * The server's fills for a source at {@code pos}: {station}, then {access} and
     * the train tokens from {@code train} or, when that is null, from the source's
     * own surroundings ({@link #serverContext}).
     */
    public static String fillServer(World world, BlockPos pos, String text, @Nullable Train train) {
        String out = text;
        if (containsAny(out, CONTEXT_TOKENS)) {
            Train context = train;
            if (context == null) {
                BiFunction<World, BlockPos, Train> lookup = serverContext;
                if (lookup != null) {
                    try {
                        context = lookup.apply(world, pos);
                    } catch (RuntimeException ignored) {
                        // a lookup failure must never stop an announcement
                    }
                }
            }
            out = fillTrain(out, context == null ? Train.NONE : context);
        }
        return fillStation(world, pos, out);
    }

    private static String fillStation(World world, BlockPos pos, String text) {
        if (!text.contains(STATION)) {
            return text;
        }
        BiFunction<World, BlockPos, String> lookup = serverStation;
        String name = null;
        if (lookup != null) {
            try {
                name = lookup.apply(world, pos);
            } catch (RuntimeException ignored) {
                // a lookup failure must never stop an announcement
            }
        }
        return fill(text, name == null ? "" : name, null);
    }

    /** The client's station name at {@code pos}, or "" when unknown. */
    public static String clientStationAt(@Nullable BlockPos pos) {
        Function<BlockPos, String> lookup = clientStation;
        if (pos == null || lookup == null) {
            return "";
        }
        try {
            String name = lookup.apply(pos);
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Stand-in train for editor previews (the client cannot see the server's trains). */
    public static final Train SAMPLE = new Train(0, "2", "Flatbush Avenue", "Park Place", false, "Platform 1",
            "The elevator is at the center of the platform.");

    public static String clock() {
        LocalTime now = LocalTime.now();
        return String.format(java.util.Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute());
    }

    /** What chat and screens show: the shown half of every {shown|spoken}. */
    public static String shown(String text) {
        return tidy(sayAs(text, 1));
    }

    /** What the voice says: the spoken half of every {shown|spoken}. */
    public static String spoken(String text) {
        return tidy(sayAs(text, 2));
    }

    /**
     * Shown text for a display/preview: switch prefix dropped, station looked up
     * client-side at {@code stationPos}, clock filled, pronunciations resolved.
     */
    public static String forDisplay(String raw, @Nullable BlockPos stationPos) {
        String text = stripDisabled(raw);
        if (containsAny(text, CONTEXT_TOKENS)) {
            text = fillTrain(text, Train.NONE); // the train/access fills are the server's to make
        }
        if (text.contains(STATION)) {
            text = fill(text, clientStationAt(stationPos), null);
        }
        if (text.contains(TIME)) {
            text = fill(text, null, clock());
        }
        return text.indexOf('{') < 0 ? text : shown(text);
    }

    private static String sayAs(String text, int group) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        Matcher matcher = SAY_AS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(group)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String tidy(String text) {
        return SPACES.matcher(text).replaceAll(" ").trim();
    }
}
