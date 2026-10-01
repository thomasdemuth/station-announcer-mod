import com.stationannouncer.wayfinding.layout.CellInfo;
import com.stationannouncer.wayfinding.layout.LayoutInput;
import com.stationannouncer.wayfinding.layout.LayoutResult;
import com.stationannouncer.wayfinding.layout.LayoutSolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Synthetic stations for the station-layout solver (pure Java, no world).
 * Run from the repo root after compiling the layout package:
 *   javac -d /tmp/lt -cp gson.jar src/main/java/com/stationannouncer/wayfinding/layout/{CellInfo,LayoutInput,LayoutResult,LayoutSolver}.java
 *   java -cp /tmp/lt:gson.jar tools/layout_test/LayoutSolverTest.java
 */
public class LayoutSolverTest {
    static int fails;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "   " + detail));
        if (!ok) {
            fails++;
        }
    }

    // ------------------------------------------------------------------ world

    static final CellInfo SOLID = CellInfo.FULL;
    static final CellInfo AIR = CellInfo.AIR;
    /** A vanilla stair ascending toward +x: low half at x 0..0.5, high half at 0.5..1. */
    static final CellInfo STAIR_EAST = CellInfo.fromBoxes(new double[][]{{0, 0, 0, 1, 0.5, 1}, {0.5, 0.5, 0, 1, 1, 1}},
            CellInfo.TAG_NONE, false);
    static final CellInfo PANE_X = CellInfo.fromBoxes(new double[][]{{0.4375, 0, 0, 0.5625, 1, 1}}, CellInfo.TAG_NONE, false);
    static final CellInfo TURNSTILE = CellInfo.passable(CellInfo.TAG_FARE, false);

    static final class World {
        final LayoutInput in = new LayoutInput();

        World(int minX, int minY, int minZ, int sx, int sy, int sz) {
            in.minX = minX;
            in.minY = minY;
            in.minZ = minZ;
            in.sizeX = sx;
            in.sizeY = sy;
            in.sizeZ = sz;
            in.cells = new CellInfo[sx * sy * sz];
            java.util.Arrays.fill(in.cells, AIR);
            in.surface = new int[sx * sz];
            java.util.Arrays.fill(in.surface, Integer.MIN_VALUE);
            in.areaMinX = Long.MIN_VALUE / 4;
            in.areaMaxX = Long.MAX_VALUE / 4;
            in.areaMinZ = Long.MIN_VALUE / 4;
            in.areaMaxZ = Long.MAX_VALUE / 4;
        }

        void set(int x, int y, int z, CellInfo cell) {
            int lx = x - in.minX, ly = y - in.minY, lz = z - in.minZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= in.sizeX || ly >= in.sizeY || lz >= in.sizeZ) {
                return;
            }
            in.cells[((ly * in.sizeZ) + lz) * in.sizeX + lx] = cell;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, CellInfo cell) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, cell);
                    }
                }
            }
        }

        /** Surface = one above the highest solid (floor-bearing) cell in each column. */
        void computeSurface() {
            for (int lx = 0; lx < in.sizeX; lx++) {
                for (int lz = 0; lz < in.sizeZ; lz++) {
                    int top = Integer.MIN_VALUE;
                    for (int ly = in.sizeY - 1; ly >= 0; ly--) {
                        if (in.cells[((ly * in.sizeZ) + lz) * in.sizeX + lx] != AIR) {
                            top = in.minY + ly + 1;
                            break;
                        }
                    }
                    in.surface[lz * in.sizeX + lx] = top;
                }
            }
        }

        LayoutResult solve() {
            return new LayoutSolver(in).solve();
        }
    }

    static List<double[]> line(double x0, double x1, double y, double z) {
        List<double[]> out = new ArrayList<>();
        for (double x = x0; x <= x1; x += 0.5) {
            out.add(new double[]{x, y, z});
        }
        return out;
    }

    static LayoutResult.Link link(LayoutResult r, String from, String to) {
        for (LayoutResult.Link l : r.links) {
            if (l.from.equals(from) && l.to.equals(to)) {
                return l;
            }
        }
        return null;
    }

    static String legs(LayoutResult.Link l) {
        if (l == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder();
        for (LayoutResult.Leg leg : l.legs) {
            b.append(leg.kind()).append('(').append(Math.round(leg.meters())).append("m,").append(Math.round(leg.dy())).append(") ");
        }
        return b.toString().trim();
    }

    static boolean has(LayoutResult.Link l, String kind) {
        return l != null && l.legs.stream().anyMatch(g -> g.kind().equals(kind));
    }

    // -------------------------------------------------------------- stations

    /**
     * An underground station: street at y 70 (ground top), a hall + platform at
     * floor 60, a 10-step staircase up to the street at the west end, a turnstile
     * line across the hall, one track down the middle in a trench.
     *
     *   x 0..44, z 0..19. Station box (air) x 5..34, z 3..16, y 60..65.
     *   Track trench z 9..11 (floor 58), rail at z 10.5.
     *   Stairs: x 20+i, y 60+i, z 3..4 (i = 0..9) rising east, out at x 30 onto the street.
     *   Fare wall at x 16 (full height), turnstile lanes at z 3..4 … wait: the stairs land
     *   on the street; the hall side of the stairs is x < 20. Fare wall at x 16, lanes z 5..6.
     */
    static World subway(boolean withLift) {
        World w = new World(0, 50, 0, 45, 30, 20);
        w.fill(0, 50, 0, 44, 69, 19, SOLID);            // ground, top at 70
        w.fill(5, 60, 3, 34, 65, 16, AIR);              // the station box
        w.fill(5, 58, 9, 34, 59, 11, AIR);              // track trench, floor top 58
        // staircase: stair blocks rising east; everything above each step is open to the sky
        for (int i = 0; i < 10; i++) {
            int x = 20 + i;
            int y = 60 + i;
            w.fill(x, y + 1, 1, x, 69, 2, AIR);
            w.set(x, y, 1, STAIR_EAST);
            w.set(x, y, 2, STAIR_EAST);
            w.fill(x, 60, 1, x, y - 1, 2, SOLID);
            w.fill(x, y + 1, 1, x, 69, 2, AIR);
        }
        // the stair well opens into the hall along z 3 (a doorway x 18..19 at floor 60)
        w.fill(18, 60, 1, 19, 62, 2, AIR);
        w.fill(18, 59, 1, 19, 59, 2, SOLID);
        // the stairwell is walled off from the hall except the doorway at x 18..19
        w.fill(20, 60, 3, 34, 65, 3, SOLID);
        // fare wall at x 22 across the hall (incl. the trench), lanes at z 5..6: the
        // unpaid side is x < 22, the platform x 23..33 is behind the gates
        w.fill(22, 58, 4, 22, 65, 16, SOLID);
        w.set(22, 60, 5, TURNSTILE);
        w.set(22, 61, 5, TURNSTILE);
        w.set(22, 60, 6, TURNSTILE);
        w.set(22, 61, 6, TURNSTILE);
        if (withLift) {
            // a lift shaft from the street down to the hall on the paid side, x 32..33 z 14..15
            w.fill(32, 60, 17, 33, 69, 18, AIR);         // the shaft (a pit to floor 60)
            w.fill(32, 59, 17, 33, 59, 18, SOLID);
            w.in.lifts.add(new LayoutInput.Lift(900, List.of(new int[]{32, 60, 17}, new int[]{32, 70, 17}), 3.5));
        }
        w.computeSurface();
        w.in.areaMinX = 5;
        w.in.areaMaxX = 34;
        w.in.areaMinZ = 3;
        w.in.areaMaxZ = 18;
        w.in.track.addAll(line(0, 44, 58, 10.5));
        // the platform: both sides of the track are standing area (a hall with the track in a trench)
        w.in.platforms.add(new LayoutInput.Platform(1, "1", line(24.5, 33.5, 58, 10.5)));
        w.in.stationId = 7;
        w.in.stationName = "Test Sq";
        return w;
    }

    public static void main(String[] args) {
        // ---- 1. stairs + fare control, exit pinned at the top of the stairs
        World w = subway(false);
        w.in.exits.add(new LayoutInput.Exit("A", 31, 70, 1));
        LayoutResult r = w.solve();
        LayoutResult.Link a = link(r, "exit:A", "platform:1");
        System.out.println("   A -> platform: " + legs(a) + "  meters " + (a == null ? "-" : Math.round(a.meters))
                + "  nodes " + r.nodes + "  " + r.millis + " ms  warnings " + r.warnings);
        check("exit A reaches the platform", a != null, r.warnings);
        check("...down the stairs (about 10 blocks)", has(a, "stairs")
                && a.legs.stream().filter(g -> g.kind().equals("stairs")).mapToDouble(LayoutResult.Leg::dy).sum() < -9.0, legs(a));
        check("...through fare control", has(a, "fare"), legs(a));
        check("...and it is not step-free (no lift here)", a != null && !a.stepFree && a.stepFreeAlt == null, a == null ? "" : a.stepFreeAlt);
        check("the platform is not step-free from the street", Boolean.FALSE.equals(r.platformStepFree.get(1L)), r.platformStepFree);
        check("no path ever stands on the track",
                a != null && a.path.stream().noneMatch(p -> Math.abs(p[2] - 10.5) < 1.2 && p[1] < 59.5), "");
        check("a sensible walk (15..80 m)", a != null && a.meters > 15 && a.meters < 80, a == null ? "" : a.meters);
        check("the path starts at the marker and ends beside the track",
                a != null && Math.abs(a.path.get(0)[0] - 31.5) < 2 && Math.abs(a.path.get(a.path.size() - 1)[1] - 60) < 1,
                a == null ? "" : java.util.Arrays.toString(a.path.get(a.path.size() - 1)));

        // ---- 2. no exit markers: the scan suggests the stair top as a street opening
        World w2 = subway(false);
        LayoutResult r2 = w2.solve();
        List<LayoutResult.Anchor> openings = r2.anchors.stream().filter(x -> x.kind().equals("opening")).toList();
        check("without markers the stair top is found as a street opening", !openings.isEmpty()
                && openings.stream().anyMatch(o -> o.x() > 28 && o.x() < 33 && Math.abs(o.y() - 70) < 0.6),
                openings.stream().map(o -> o.x() + "," + o.y() + "," + o.z()).toList());
        check("...and it links to the platform", link(r2, openings.isEmpty() ? "" : openings.get(0).id(), "platform:1") != null, r2.links.size());

        // ---- 3. add a lift: step-free alternative and a step-free platform
        World w3 = subway(true);
        w3.in.exits.add(new LayoutInput.Exit("A", 31, 70, 1));
        LayoutResult r3 = w3.solve();
        LayoutResult.Link a3 = link(r3, "exit:A", "platform:1");
        System.out.println("   A -> platform (lift built): " + legs(a3) + " | step-free alt: "
                + (a3 == null ? "-" : legs(a3.stepFreeAlt)) + "  warnings " + r3.warnings);
        check("with a lift the platform becomes step-free", Boolean.TRUE.equals(r3.platformStepFree.get(1L)), r3.platformStepFree);
        check("...the stairs stay the fastest walk", a3 != null && has(a3, "stairs"), legs(a3));
        check("...and the step-free alternative rides the lift", a3 != null && a3.stepFreeAlt != null
                && has(a3.stepFreeAlt, "lift") && !has(a3.stepFreeAlt, "stairs") && a3.stepFreeAlt.stepFree, a3 == null ? "" : legs(a3.stepFreeAlt));

        // ---- 4. a glass-pane wall is not walked through; a 1-block ledge is not climbed
        World f = new World(0, 60, 0, 20, 6, 12);
        f.fill(0, 60, 0, 19, 60, 11, SOLID);                   // floor top 61
        f.fill(10, 61, 0, 10, 63, 9, PANE_X);                   // a pane wall with a gap at z 10..11
        f.computeSurface();
        f.in.areaMinX = 0; f.in.areaMaxX = 19; f.in.areaMinZ = 0; f.in.areaMaxZ = 11;
        f.in.track.addAll(line(0, 19, 60, -3));                 // (no track near)
        f.in.platforms.add(new LayoutInput.Platform(2, "P", line(15, 18, 60.5, 1.0)));  // platform east of the pane
        f.in.exits.add(new LayoutInput.Exit("W", 3, 61, 2));    // exit west of the pane
        LayoutResult rf = f.solve();
        LayoutResult.Link lf = link(rf, "exit:W", "platform:2");
        check("panes block: the walk goes round through the gap (> 14 m, straight is ~8)", lf != null && lf.meters > 14,
                lf == null ? rf.warnings : lf.meters);
        World g = new World(0, 60, 0, 20, 6, 8);
        g.fill(0, 60, 0, 19, 60, 7, SOLID);                      // floor top 61
        g.fill(10, 61, 0, 19, 61, 7, SOLID);                      // a raised half at top 62: a 1-block ledge
        g.computeSurface();
        g.in.areaMinX = 0; g.in.areaMaxX = 19; g.in.areaMinZ = 0; g.in.areaMaxZ = 7;
        g.in.platforms.add(new LayoutInput.Platform(3, "Q", line(14, 18, 61.5, -0.5)));
        g.in.exits.add(new LayoutInput.Exit("L", 3, 61, 3));
        LayoutResult rg = g.solve();
        check("a 1-block ledge is not climbed (no jumps)", link(rg, "exit:L", "platform:3") == null, legs(link(rg, "exit:L", "platform:3")));

        // ---- 5. two side platforms with ballast + posts between the tracks: never joined
        //         (Cherry Bridge on world_baker), and a LOW platform beside a wall still boards
        //         (Kalamazoo B: platform floor level with the track bed)
        World c = new World(0, 58, 0, 24, 8, 18);
        c.fill(0, 58, 0, 23, 60, 17, SOLID);                      // track bed + ballast, top 61
        c.fill(0, 61, 0, 23, 61, 3, SOLID);                       // side platform A (top 62), z 0..3
        c.fill(0, 61, 12, 23, 61, 15, SOLID);                     // side platform B (top 62), z 12..15
        CellInfo post = CellInfo.fromBoxes(new double[][]{{0.25, 0, 0.25, 0.75, 1.5, 0.75}}, CellInfo.TAG_NONE, false);
        for (int x = 2; x < 22; x += 4) {
            c.set(x, 61, 7, post);                                // posts on the ballast between the tracks
        }
        c.computeSurface();
        c.in.areaMinX = 0; c.in.areaMaxX = 23; c.in.areaMinZ = 0; c.in.areaMaxZ = 17;
        c.in.track.addAll(line(0, 23, 61, 5.5));
        c.in.track.addAll(line(0, 23, 61, 9.5));
        c.in.platforms.add(new LayoutInput.Platform(10, "A", line(2, 21, 61, 5.5)));
        c.in.platforms.add(new LayoutInput.Platform(11, "B", line(2, 21, 61, 9.5)));
        LayoutResult rc = c.solve();
        check("side platforms across two tracks are NOT joined through the ballast or post tops",
                link(rc, "platform:10", "platform:11") == null, legs(link(rc, "platform:10", "platform:11")));
        c.fill(0, 61, 7, 23, 61, 8, SOLID);                       // now a continuous wall between the tracks
        LayoutResult rw = c.solve();
        check("...nor through the top of a wall between the tracks (it leads nowhere)",
                link(rw, "platform:10", "platform:11") == null, legs(link(rw, "platform:10", "platform:11")));
        World lo = new World(0, 58, 0, 20, 8, 12);
        lo.fill(0, 58, 0, 19, 60, 11, SOLID);                     // floor top 61 everywhere: level boarding
        lo.fill(0, 61, 8, 19, 64, 11, SOLID);                     // a wall behind the track
        lo.computeSurface();
        lo.in.areaMinX = 0; lo.in.areaMaxX = 19; lo.in.areaMinZ = 0; lo.in.areaMaxZ = 11;
        lo.in.track.addAll(line(0, 19, 61, 5.5));
        lo.in.platforms.add(new LayoutInput.Platform(12, "L", line(2, 17, 61, 5.5)));
        lo.in.exits.add(new LayoutInput.Exit("E", 10, 61, 0));
        LayoutResult rlo = lo.solve();
        check("a low (level-boarding) platform beside a wall still has standing room",
                link(rlo, "exit:E", "platform:12") != null && rlo.warnings.isEmpty(), rlo.warnings);

        // ---- 7. fare control: the line of gates is one anchor, and the walk names it
        List<LayoutResult.Anchor> fares = r.anchors.stream().filter(x -> x.kind().equals("fare")).toList();
        check("the turnstile line is ONE fare-control anchor", fares.size() == 1 && fares.get(0).id().equals("fare:0")
                && Math.abs(fares.get(0).x() - 22.5) < 1, fares.stream().map(x -> x.id() + "@" + x.x() + "," + x.z()).toList());
        check("...the walk in from Exit A passes it by name", a != null && a.legs.stream()
                .anyMatch(leg -> leg.kind().equals("fare") && "fare:0".equals(leg.at())), legs(a));
        check("...and it knows Exit A walks through it", r.access.get("fare:0") != null
                && r.access.get("fare:0").usedBy.contains("exit:A"), r.access.get("fare:0") == null ? "-" : r.access.get("fare:0").usedBy);
        check("...a properly gated station has no warnings", r.warnings.isEmpty(), r.warnings);
        check("exit A (stairs only) reads step-free: none", r.access.get("exit:A") != null
                && "none".equals(r.access.get("exit:A").stepFree) && !r.access.get("exit:A").lift, r.access.get("exit:A"));

        // ---- 8. two exits, only one with a lift: per-exit access
        World two = subway(false);
        two.fill(15, 70, 0, 15, 72, 19, SOLID);                   // a wall on the street between the two exits
        two.fill(8, 66, 5, 9, 69, 6, AIR);                        // a lift shaft from the street into the UNPAID hall
        two.in.lifts.add(new LayoutInput.Lift(901, List.of(new int[]{8, 60, 5}, new int[]{8, 70, 5}), 3.5));
        two.computeSurface();
        two.in.exits.add(new LayoutInput.Exit("A", 31, 70, 1));
        two.in.exits.add(new LayoutInput.Exit("B", 11, 70, 5));
        LayoutResult rt = two.solve();
        LayoutResult.Access accA = rt.access.get("exit:A");
        LayoutResult.Access accB = rt.access.get("exit:B");
        System.out.println("   B -> platform: " + legs(link(rt, "exit:B", "platform:1")) + "  warnings " + rt.warnings);
        check("Exit A (stairs) is not step-free, Exit B (lift) is — by lift",
                accA != null && "none".equals(accA.stepFree) && accB != null && "all".equals(accB.stepFree) && accB.lift,
                (accA == null ? "-" : accA.stepFree) + " / " + (accB == null ? "-" : accB.stepFree + " lift " + accB.lift));
        check("...the JSON carries it on the exit anchors", rt.toJson(false).toString()
                .contains("\"id\":\"exit:B\",\"kind\":\"exit\",\"name\":\"B\",\"pos\":[11.5,70.0,5.5],\"stepFree\":\"all\",\"lift\":true"),
                rt.toJson(false).getAsJsonArray("anchors"));
        check("...and a lift into the unpaid hall raises no fare warning", rt.warnings.isEmpty(), rt.warnings);

        // ---- 9. no exit, no street opening (surface unknown): fare control is the way in
        World indoor = subway(false);
        java.util.Arrays.fill(indoor.in.surface, Integer.MIN_VALUE);
        LayoutResult ri = indoor.solve();
        LayoutResult.Access fi = ri.access.get("fare:0");
        check("with nothing else, the fare line stands in as the entrance", fi != null && fi.entrance
                && link(ri, "fare:0", "platform:1") != null && "all".equals(fi.stepFree), fi == null ? ri.anchors : fi.stepFree);
        check("...and the platform counts as step-free from it", Boolean.TRUE.equals(ri.platformStepFree.get(1L)), ri.platformStepFree);

        // ---- 10. a gap in the fare wall: riders skip the gates
        World leak = subway(false);
        leak.fill(22, 60, 7, 22, 62, 8, AIR);                     // the trench splits the hall: gap on the gates' side
        leak.in.exits.add(new LayoutInput.Exit("A", 31, 70, 1));
        LayoutResult rl = leak.solve();
        check("a gap round the gates is reported", rl.warnings.stream()
                .anyMatch(x -> x.startsWith("Platform 1: riders can also reach it from the street without passing fare control")), rl.warnings);
        check("...the scanned box and margin are reported", rl.region != null && rl.region[0] == 0 && rl.region[3] == 44
                && rl.toJson(false).has("region"), rl.region == null ? "-" : java.util.Arrays.toString(rl.region));

        // ---- 11. an at-grade station: an open-sky PAID plaza fenced off from the street,
        //          turnstiles in the fence (Atlantic / Morgan) — the plaza is not the street
        World grade = new World(0, 56, 0, 35, 10, 12);
        grade.fill(0, 56, 0, 34, 60, 11, SOLID);                   // ground, top 61
        grade.fill(0, 59, 1, 34, 60, 3, AIR);                      // track trench, floor 59
        grade.fill(0, 61, 0, 34, 63, 0, SOLID);                    // nothing to stand on beyond the track
        grade.fill(21, 61, 4, 21, 62, 11, SOLID);                  // the fence between plaza and street
        grade.fill(21, 59, 1, 21, 63, 3, SOLID);                   // ...across the trench too
        grade.set(21, 61, 7, TURNSTILE);
        grade.set(21, 62, 7, TURNSTILE);
        grade.set(21, 61, 8, TURNSTILE);
        grade.set(21, 62, 8, TURNSTILE);
        grade.computeSurface();
        grade.in.areaMinX = 0; grade.in.areaMaxX = 20; grade.in.areaMinZ = 0; grade.in.areaMaxZ = 11;
        grade.in.track.addAll(line(0, 34, 59, 2.5));
        grade.in.platforms.add(new LayoutInput.Platform(20, "G", line(1, 8, 59, 2.5)));
        LayoutResult rgr = grade.solve();
        List<LayoutResult.Anchor> gOpen = rgr.anchors.stream().filter(x -> x.kind().equals("opening")).toList();
        System.out.println("   at-grade openings: " + gOpen.stream().map(o -> Math.round(o.x()) + "," + Math.round(o.z())).toList()
                + "  warnings " + rgr.warnings);
        check("the open-air paid plaza is not taken for the street: entrances are found past the gates",
                !gOpen.isEmpty() && gOpen.stream().allMatch(o -> o.x() > 21), gOpen.stream().map(LayoutResult.Anchor::x).toList());
        check("...their walks pass the fare control, which knows it", has(link(rgr, gOpen.isEmpty() ? "" : gOpen.get(0).id(), "platform:20"), "fare")
                && rgr.access.get("fare:0") != null && !rgr.access.get("fare:0").usedBy.isEmpty(), rgr.access.get("fare:0"));
        check("...and nothing is reported as skipping the gates", rgr.warnings.isEmpty(), rgr.warnings);
        grade.set(21, 61, 10, AIR);                                // now a gap in the fence
        grade.set(21, 62, 10, AIR);
        LayoutResult rgap = grade.solve();
        check("a gap in the fence is reported once for the station", rgap.warnings.stream()
                .filter(x -> x.contains("without passing fare control")).count() == 1, rgap.warnings);

        // ---- 13. gates ACROSS the platform: the track runs on past them, the street stairs
        //          land on the unpaid end (Atlantic) — that end is not the platform
        World across = new World(0, 56, 0, 35, 10, 12);
        across.fill(0, 56, 0, 34, 60, 11, SOLID);                  // ground, top 61
        across.fill(0, 59, 1, 34, 60, 3, AIR);                     // track trench, floor 59
        across.fill(0, 61, 0, 34, 63, 0, SOLID);
        across.fill(21, 61, 4, 21, 62, 11, SOLID);                 // the fence crosses the platform
        across.fill(21, 59, 1, 21, 63, 3, SOLID);
        across.set(21, 61, 7, TURNSTILE);
        across.set(21, 62, 7, TURNSTILE);
        across.computeSurface();
        across.in.areaMinX = 0; across.in.areaMaxX = 34; across.in.areaMinZ = 0; across.in.areaMaxZ = 11;
        across.in.track.addAll(line(0, 34, 59, 2.5));
        across.in.platforms.add(new LayoutInput.Platform(40, "X", line(1, 30, 59, 2.5)));   // runs past the gates
        across.in.exits.add(new LayoutInput.Exit("S", 31, 61, 9));                         // on the unpaid end
        LayoutResult rac = across.solve();
        LayoutResult.Link sx = link(rac, "exit:S", "platform:40");
        check("gates across a platform: the unpaid end is not the platform (the walk passes the gates)",
                has(sx, "fare") && rac.warnings.isEmpty(), legs(sx) + " " + rac.warnings);

        // ---- 12. a lift against a wall: without landing doors it reaches both sides (the old
        //          rule), with an MTR lift door it is entered only through that door (187 St)
        for (boolean door : new boolean[]{false, true}) {
            World lw = new World(0, 58, 0, 21, 9, 11);
            lw.fill(0, 58, 0, 20, 60, 10, SOLID);                 // floor top 61
            lw.fill(10, 61, 0, 10, 65, 10, SOLID);                // a wall the whole way across
            if (door) {
                lw.set(9, 61, 5, CellInfo.passable(CellInfo.TAG_LIFT_DOOR, false));
                lw.set(9, 62, 5, CellInfo.passable(CellInfo.TAG_LIFT_DOOR, false));
            }
            lw.computeSurface();
            lw.in.areaMinX = 0; lw.in.areaMaxX = 20; lw.in.areaMinZ = 0; lw.in.areaMaxZ = 10;
            lw.in.track.addAll(line(12, 19, 61, 9.5));
            lw.in.platforms.add(new LayoutInput.Platform(30, "E", line(12, 19, 61, 9.5)));
            lw.in.lifts.add(new LayoutInput.Lift(902, List.of(new int[]{10, 61, 5}, new int[]{10, 71, 5}), 3.5));
            lw.in.exits.add(new LayoutInput.Exit("W", 3, 61, 5));
            LayoutResult rlw = lw.solve();
            LayoutResult.Link lk = link(rlw, "exit:W", "platform:30");
            check(door ? "...with a landing door the lift no longer reaches through the wall"
                            : "a doorless lift landing reaches round its shaft (old rule kept)",
                    door == (lk == null), legs(lk));
        }

        // ---- 6. JSON round trip shape
        String json = r3.toJson(true).toString();
        check("the result serialises with anchors, links, paths and step-free flags",
                json.contains("\"anchors\"") && json.contains("\"path\"") && json.contains("\"platformStepFree\""), json.length());

        System.out.println(fails == 0 ? "\nall layout checks passed" : "\n" + fails + " FAILURE(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
