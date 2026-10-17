import com.stationannouncer.mtraddon.PlatformOccupancy;
import com.stationannouncer.mtraddon.PlatformOccupancy.Plan;
import com.stationannouncer.mtraddon.PlatformOccupancy.Siding;
import com.stationannouncer.mtraddon.PlatformOccupancy.Stop;

import java.util.List;

/**
 * Pure-Java check of PlatformOccupancy (no Minecraft). Run:
 * javac -d out src/main/java/com/stationannouncer/mtraddon/PlatformOccupancy.java tools/platform_test/PlatformOccupancyTest.java
 * java -cp out PlatformOccupancyTest
 */
public class PlatformOccupancyTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    static Plan plan(long dwellA, long dwellB, long hold) {
        return new Plan() {
            public long dwell(long routeId, long baked) {
                return routeId == 1 ? (dwellA < 0 ? baked : dwellA) : (dwellB < 0 ? baked : dwellB);
            }

            public long holdExtra() {
                return hold;
            }
        };
    }

    public static void main(String[] args) {
        long day = 1_200_000; // 20 min
        // Route 1: every 200 s, arrives 60 s after departure, dwells 40 s.
        Siding a = new Siding(10, 100, "Depot A", day, new long[]{0, 200_000, 400_000, 600_000, 800_000, 1_000_000},
                new Stop[]{new Stop(1, 3, 60_000, 100_000)});
        // Route 2: every 200 s, arrives 150 s after its departure (phase 0) → 150 s, dwell 30 s.
        Siding b = new Siding(20, 200, "Depot B", day, new long[]{0, 200_000, 400_000, 600_000, 800_000, 1_000_000},
                new Stop[]{new Stop(2, 5, 150_000, 180_000)});

        var clean = PlatformOccupancy.compute(List.of(a, b), day, plan(-1, -1, 0));
        check(clean.visits().size() == 12, "12 visits a day (" + clean.visits().size() + ")");
        check(clean.conflicts().isEmpty(), "baked schedule has no conflicts (" + clean.conflicts().size() + ")");
        check(clean.headway(1) == 200_000, "route 1 headway 200 s (" + clean.headway(1) + ")");

        // Route 1 dwell 100 s → leaves at 160 s, route 2 arrives at 150 s: blocked 10 s, 6 times.
        var long1 = PlatformOccupancy.compute(List.of(a, b), day, plan(100_000, -1, 0));
        long hard = long1.conflicts().stream().filter(c -> !c.maybe()).count();
        check(hard == 6, "dwell 100 s blocks route 2 six times (" + hard + ")");
        check(long1.conflicts().get(0).waitMillis() == 10_000, "wait is 10 s (" + long1.conflicts().get(0).waitMillis() + ")");
        check(long1.conflicts().get(0).blocker().routeId() == 1 && !long1.conflicts().get(0).sameRoute(), "blocker is route 1, other route");

        // Route 1 dwell 210 s ≥ its 200 s headway → same-route conflicts too.
        var huge = PlatformOccupancy.compute(List.of(a), day, plan(210_000, -1, 0));
        check(huge.conflicts().size() == 6 && huge.conflicts().stream().allMatch(PlatformOccupancy.Conflict::sameRoute),
                "dwell ≥ headway → 6 same-route conflicts, incl. across midnight (" + huge.conflicts().size() + ")");

        // Hold rule worst case 60 s: 40 s dwell + 60 = 100 s → route 2 at 150 s is a MAYBE (leave 100 → maybe 160).
        var maybe = PlatformOccupancy.compute(List.of(a, b), day, plan(-1, -1, 60_000));
        check(maybe.conflicts().size() == 6 && maybe.conflicts().stream().allMatch(PlatformOccupancy.Conflict::maybe),
                "hold window gives 6 maybe conflicts (" + maybe.conflicts().size() + ")");

        // Merged route boundary: one train, two stops at the same platform back to back → one visit, no self-conflict.
        Siding loop = new Siding(30, 300, "Loop", day, new long[]{0, 600_000},
                new Stop[]{new Stop(1, 9, 300_000, 340_000), new Stop(2, 0, 340_000, 360_000)});
        var merged = PlatformOccupancy.compute(List.of(loop), day, plan(-1, -1, 0));
        check(merged.visits().size() == 2 && merged.conflicts().isEmpty(), "merged boundary = one visit per train ("
                + merged.visits().size() + ")");

        // Lengthening an earlier stop shifts the later stop of the same train cycle.
        Siding twice = new Siding(40, 400, "Twice", day, new long[]{0},
                new Stop[]{new Stop(1, 2, 100_000, 120_000), new Stop(2, 6, 500_000, 520_000)});
        var shifted = PlatformOccupancy.compute(List.of(twice), day, plan(80_000, -1, 0));
        check(shifted.visits().get(1).arrive() == 560_000, "later stop moves by the extra 60 s ("
                + shifted.visits().get(1).arrive() + ")");

        // A siding repeating every 60 min on a 20-min day: its own cycle, never folded onto itself.
        Siding hour = new Siding(50, 500, "Hour", 3_600_000, new long[]{0, 1_200_000, 2_400_000},
                new Stop[]{new Stop(1, 1, 100_000, 115_000)});
        var hourly = PlatformOccupancy.compute(List.of(hour, a), day, plan(-1, -1, 0));
        check(hourly.day() == 3_600_000, "cycle = longest repeat (" + hourly.day() + ")");
        check(hourly.visits().size() == 3 + 18, "20-min siding repeated 3x to fill the hour (" + hourly.visits().size() + ")");
        check(PlatformOccupancy.compute(List.of(hour), day, plan(-1, -1, 0)).conflicts().isEmpty(),
                "no self-overlap from folding");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
