import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stationannouncer.mtraddon.interline.InterlineModel;
import com.stationannouncer.mtraddon.interline.InterlineSolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthetic sections for the interline solver's hold suggestions (pure Java, no world).
 * Run from the repo root:
 *   javac -d OUT -cp gson.jar src/main/java/com/stationannouncer/mtraddon/interline/{InterlineModel,Timetable,InterlineSolver}.java
 *   java -cp OUT:gson.jar tools/interline_test/InterlineSolverTest.java
 *
 * Thomas, 2026-10-01: "a section has a combined frequency of every 1 minute but a 3 minute hold
 * is suggested — that will clog the line". A hold may keep a train at its platform for at most
 * half the gap between trains calling there, leaving at least 30 s before the next one.
 */
public class InterlineSolverTest {
    static int fails;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "   " + detail));
        if (!ok) {
            fails++;
        }
    }

    static final long DAY = 86_400_000L;   // a real-time day: slider value f = one train every 14 400 000 / f ms

    /**
     * Two lines (depots 1 and 2) of {@code frequency} each, stopping at their own platform
     * (10, 20) and then sharing the section 31 → 32. Both arrive at the entry together, so
     * the section is trains in pairs until one line is moved. {@code shared} = both lines
     * use platform 10 before the section (a busy shared stop).
     */
    static InterlineModel.Analysis line(long frequency, long dwellMs, boolean shared) {
        long[] f = new long[24];
        java.util.Arrays.fill(f, frequency);
        Map<Long, InterlineModel.Depot> depots = new LinkedHashMap<>();
        depots.put(1L, new InterlineModel.Depot(1, "Red depot", 0xff0000, new long[]{101}, f, f, 0, 0, 0, true, "", 4, new long[0]));
        depots.put(2L, new InterlineModel.Depot(2, "Blue depot", 0x0000ff, new long[]{102}, f, f, 0, 0, 0, true, "", 4, new long[0]));
        long before2 = shared ? 10 : 20;
        Map<Long, InterlineModel.Route> routes = new LinkedHashMap<>();
        routes.put(101L, new InterlineModel.Route(101, "Red", "1", 0xff0000, new long[]{10, 31, 32},
                new long[]{1, 3, 4}, new String[]{"Alpha", "Trunk A", "Trunk B"}));
        routes.put(102L, new InterlineModel.Route(102, "Blue", "2", 0x0000ff, new long[]{before2, 31, 32},
                new long[]{shared ? 1 : 2, 3, 4}, new String[]{shared ? "Alpha" : "Beta", "Trunk A", "Trunk B"}));
        List<InterlineModel.Feed> feeds = List.of(
                new InterlineModel.Feed(1, 101, 60_000, 0, 0, 1),
                new InterlineModel.Feed(2, 102, 60_000, 0, 0, 1));
        List<InterlineModel.PrevStop> prev = List.of(
                new InterlineModel.PrevStop(101, 10, "Alpha", dwellMs, false, 0),
                new InterlineModel.PrevStop(102, before2, shared ? "Alpha" : "Beta", dwellMs, false, 0));
        InterlineModel.Section section = new InterlineModel.Section("s", "Trunk", "", new long[]{101, 102},
                new long[]{31, 32}, new long[]{3, 4}, new String[]{"Trunk A", "Trunk B"}, feeds, prev,
                InterlineModel.Stats.EMPTY, InterlineModel.Measured.NONE);
        return new InterlineModel.Analysis("test", DAY, 0, true, 1000, 300_000, depots, routes, List.of(),
                List.of(section), Map.of("s", section));
    }

    static InterlineSolver.Request holdsOnly() {
        return new InterlineSolver.Request("s", "", 0, "forward", "holds", Set.of(), 0, Map.of());
    }

    static long maxPad(JsonObject out) {
        long max = 0;
        if (out.has("pads")) {
            for (JsonElement pad : out.getAsJsonArray("pads")) {
                max = Math.max(max, pad.getAsJsonObject().get("extraMs").getAsLong());
            }
        }
        return max;
    }

    static String warnings(JsonObject out) {
        return out.has("warnings") ? out.getAsJsonArray("warnings").toString() : "[]";
    }

    public static void main(String[] args) {
        // 1. Each line every 2 min (section every 1 min), 10 s dwell. The even answer moves one
        //    line by 60 s; at a platform seeing a train every 2 min a held train may stay 60 s
        //    (half the gap) — 10 s dwell + at most 50 s hold.
        JsonObject a = InterlineSolver.solve(line(120, 10_000, false), holdsOnly());
        System.out.println("   2-min lines: pad " + maxPad(a) + " ms  " + warnings(a));
        check("a hold is still suggested on a 2-min line", maxPad(a) > 0, a);
        check("...but never more than half the 2-min gap minus the dwell (50 s)", maxPad(a) <= 50_000, maxPad(a));
        check("...and the cap is explained, for the stop actually used", warnings(a).contains("Holds kept short")
                && !(warnings(a).contains("Alpha") && warnings(a).contains("Beta")), warnings(a));

        // 2. Each line every minute (section every 30 s): at most 20 s of hold.
        JsonObject b = InterlineSolver.solve(line(240, 10_000, false), holdsOnly());
        System.out.println("   1-min lines: pad " + maxPad(b) + " ms  " + warnings(b));
        check("1-min lines: hold capped at 20 s (30 s occupancy - 10 s dwell)", maxPad(b) <= 20_000, maxPad(b));

        // 3. Both lines stop at the same platform before the section: it sees a train every
        //    minute although each line runs every 2 min — the busy platform sets the limit.
        JsonObject c = InterlineSolver.solve(line(120, 10_000, true), holdsOnly());
        System.out.println("   shared stop: pad " + maxPad(c) + " ms  " + warnings(c));
        check("a shared stop counts every line's trains (cap 20 s, not 50 s)", maxPad(c) <= 20_000, maxPad(c));

        // 4. A train every 40 s at the stop and a 15 s dwell: no room at all — no hold, said so.
        JsonObject d = InterlineSolver.solve(line(360, 15_000, false), holdsOnly());
        System.out.println("   40-s lines: pad " + maxPad(d) + " ms  " + warnings(d));
        check("no hold where the platform has no room", maxPad(d) == 0, maxPad(d));
        check("...and the warning says why", warnings(d).contains("No hold at"), warnings(d));

        // 5. A quiet line (every 10 min): the old configured maximum still applies, nothing capped.
        JsonObject e = InterlineSolver.solve(line(24, 10_000, false), holdsOnly());
        System.out.println("   10-min lines: pad " + maxPad(e) + " ms  " + warnings(e));
        check("a quiet line keeps long holds (up to half its 10-min gap)", maxPad(e) >= 250_000 && maxPad(e) <= 290_000,
                maxPad(e) + " " + warnings(e));

        System.out.println(fails == 0 ? "\nall interline checks passed" : "\n" + fails + " FAILURE(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
