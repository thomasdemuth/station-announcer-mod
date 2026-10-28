import com.stationannouncer.mtr.SweepGeometry;
import com.stationannouncer.mtr.SweepGeometry.Column;
import com.stationannouncer.mtr.SweepGeometry.Mode;

import java.util.Map;

/**
 * Checks the Structure Sweeper's pure geometry (no Minecraft needed):
 *   javac -d OUT src/main/java/com/stationannouncer/mtr/SweepGeometry.java tools/sweep_test/SweepGeometryTest.java
 *   java -cp OUT SweepGeometryTest
 */
public class SweepGeometryTest {
    static int failures;
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    /** A circular arc around (cx, cz), radius r, from angle a0 to a1 (radians), climbing dy over its length. */
    record Arc(double cx, double cz, double r, double a0, double a1, double y0, double dy) implements SweepGeometry.PathFn {
        public double length() {
            return Math.abs(a1 - a0) * r;
        }

        public double[] at(double d) {
            double t = Math.max(0, Math.min(1, d / length()));
            double a = a0 + (a1 - a0) * t;
            return new double[]{cx + r * Math.cos(a), y0 + dy * t, cz + r * Math.sin(a)};
        }
    }

    public static void main(String[] args) {
        straight();
        arc(false);
        arc(true);
        templateIndices();
        quarters();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void straight() {
        SweepGeometry.Line line = new SweepGeometry.Line(0.5, 64, 0.5, 20.5, 64, 0.5);
        Map<Long, Column> cols = SweepGeometry.columns(line, -2, 2);
        check(cols.size() == 21 * 5, "straight: 21x5 columns, got " + cols.size());
        for (Column c : cols.values()) {
            check(Math.abs(c.u - Math.round(c.u)) < 1e-9, "straight: integer u " + c.u);
            check(Math.abs(c.s - c.x) < 1e-9, "straight: s = x at x " + c.x + " s " + c.s);
            check(c.u == c.z, "straight: u = z (right of east is south) at z " + c.z + " u " + c.u);
        }
        // a diagonal line: every column within 2 sideways, none twice
        SweepGeometry.Line diag = new SweepGeometry.Line(0, 64, 0, 30, 64, 30);
        Map<Long, Column> d = SweepGeometry.columns(diag, -2, 2);
        int expected = 0;
        for (int x = -5; x < 36; x++) {
            for (int z = -5; z < 36; z++) {
                double px = x + 0.5, pz = z + 0.5;
                double s = (px + pz) / Math.sqrt(2), u = (pz - px) / Math.sqrt(2);
                if (s >= 0 && s <= diag.length() && u >= -2 && u <= 2) {
                    expected++;
                    Column c = d.get(SweepGeometry.key(x, z));
                    check(c != null, "diagonal: missing column " + x + "," + z);
                    if (c != null) {
                        check(Math.abs(c.s - s) < 1e-6 && Math.abs(c.u - u) < 1e-6, "diagonal: exact s/u at " + x + "," + z);
                    }
                }
            }
        }
        check(d.size() == expected, "diagonal: " + expected + " columns, got " + d.size());
    }

    /** Gap-free and exact on a 90° curve, both turning directions, with a grade. */
    static void arc(boolean left) {
        double r = 18;
        Arc arc = left
                ? new Arc(0.3, 0.7, r, Math.PI / 2, 0, 70, 6)     // travelling clockwise → centre on the left
                : new Arc(0.3, 0.7, r, 0, Math.PI / 2, 70, 6);    // counter-clockwise (in x,z with z down) → centre on the right
        double half = 4;
        Map<Long, Column> cols = SweepGeometry.columns(arc, -half, half);
        int expected = 0, missing = 0, badU = 0, badS = 0;
        for (int x = -5; x < 30; x++) {
            for (int z = -5; z < 30; z++) {
                double px = x + 0.5 - arc.cx(), pz = z + 0.5 - arc.cz();
                double rr = Math.sqrt(px * px + pz * pz);
                double ang = Math.atan2(pz, px);
                if (ang < -1e-9 || ang > Math.PI / 2 + 1e-9) {
                    continue;
                }
                double s = left ? (Math.PI / 2 - ang) * r : ang * r;
                // atan2 with z "down": increasing angle turns from east to south = clockwise on screen.
                // Travelling with increasing angle the centre is on the RIGHT, so u = r − rr; the other way u = rr − r.
                double u = left ? rr - r : r - rr;
                if (Math.abs(u) > half || s < 0 || s > arc.length()) {
                    continue;
                }
                expected++;
                Column c = cols.get(SweepGeometry.key(x, z));
                if (c == null) {
                    missing++;
                    continue;
                }
                if (Math.abs(c.u - u) > 0.15) {
                    badU++;
                }
                if (Math.abs(c.s - s) > 0.3) {
                    badS++;
                }
            }
        }
        String tag = left ? "arc (centre left): " : "arc (centre right): ";
        check(missing == 0, tag + missing + " of " + expected + " columns missing (holes)");
        check(badU == 0, tag + badU + " columns with a wrong sideways offset");
        check(badS == 0, tag + badS + " columns with a wrong distance along");
        check(Math.abs(cols.size() - expected) <= expected / 50, tag + "no stray columns (" + cols.size() + " vs " + expected + ")");
    }

    static void templateIndices() {
        // STRETCH: 37 blocks of an 8-long bay -> 5 copies, each 7.4 long, all 8 cells in every copy, ends exact
        int[] seen = new int[8 * 5];
        for (double s = 0; s < 37; s += 0.05) {
            int i = SweepGeometry.templateS(s, 37, 8, Mode.STRETCH, 0);
            int copy = (int) Math.floor(s / 7.4);
            seen[Math.min(4, copy) * 8 + i]++;
        }
        for (int k = 0; k < seen.length; k++) {
            check(seen[k] > 0, "stretch: copy " + (k / 8) + " cell " + (k % 8) + " present");
        }
        check(SweepGeometry.templateS(36.999, 37, 8, Mode.STRETCH, 0) == 7, "stretch: last cell at the far node");
        check(SweepGeometry.templateS(0, 37, 8, Mode.STRETCH, 0) == 0, "stretch: first cell at the near node");
        check(Math.abs(SweepGeometry.stretchFactor(37, 8) - 0.925) < 1e-9, "stretch factor 37/40");
        // ONCE: one 30-long arch over 90 blocks
        check(SweepGeometry.templateS(0, 90, 30, Mode.ONCE, 0) == 0, "once: start");
        check(SweepGeometry.templateS(89.99, 90, 30, Mode.ONCE, 0) == 29, "once: end");
        check(SweepGeometry.templateS(45, 90, 30, Mode.ONCE, 0) == 15, "once: middle");
        // REPEAT with phase
        check(SweepGeometry.templateS(9.5, 100, 8, Mode.REPEAT, 0) == 1, "repeat: 9.5 -> 1");
        check(SweepGeometry.templateS(0.5, 100, 8, Mode.REPEAT, -2) == 6, "repeat: phase -2 wraps");
        check(SweepGeometry.templateU(-0.49) == 0 && SweepGeometry.templateU(0.51) == 1 && SweepGeometry.templateU(-0.51) == -1,
                "templateU rounds to the nearest cell");
    }

    static void quarters() {
        check(SweepGeometry.quarter(1, 0) == 0, "east");
        check(SweepGeometry.quarter(0, 1) == 1, "south");
        check(SweepGeometry.quarter(-1, 0) == 2, "west");
        check(SweepGeometry.quarter(0, -1) == 3, "north");
        check(SweepGeometry.quarter(0.8, 0.6) == 0, "mostly east");
        check(SweepGeometry.quarter(-0.6, -0.8) == 3, "mostly north");
    }
}
