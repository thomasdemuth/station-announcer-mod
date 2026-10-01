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
 * </ul>
 * Anything else in braces is left alone, so ordinary text never breaks.
 */
public final class PaText {
    public static final String STATION = "{station}";
    public static final String TIME = "{time}";
    public static final String DISABLED_PREFIX = "{off}";
    public static final String FALLBACK_STATION = "this station";

    /** {shown|spoken}: no braces inside, exactly one bar. */
    private static final Pattern SAY_AS = Pattern.compile("\\{([^{}|]*)\\|([^{}|]*)\\}");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

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

    /** The server's station fill for a source at {@code pos}. */
    public static String fillServer(World world, BlockPos pos, String text) {
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
