package com.stationannouncer.wayfinding.layout;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out how a rider walks around one station — PURE JAVA (see
 * {@link LayoutInput}); no world access, no threads, deterministic.
 *
 * <h2>The walk graph</h2>
 * A node is a place a rider can STAND: a quadrant column (half a block square)
 * at one floor height. A floor is the top of the collision under the feet with
 * {@value #HEADROOM} blocks of nothing in the way above it (see
 * {@link CellInfo}: obstruction counts every box touching the quadrant, so panes
 * and railings block even though they are no floor). Moves go to the 8
 * neighbouring quadrants and may climb or drop at most {@value #STEP} — vanilla's
 * step height: stairs, slabs and ramps, but NO jumps, NO drops and NO ladders
 * (Thomas, 2026-09-29: rider routes, not parkour).
 * <ul>
 *   <li>A rise of more than {@value #RAMP} per half block is a STAIR step; less
 *       is a ramp (step-free). Escalator blocks make their moves ESCALATOR.
 *       Neither stairs nor escalators are step-free.</li>
 *   <li>Turnstile / HEET lanes are walk-through and cost a little (FARE);
 *       emergency exit doors are walk-through only as a last resort.</li>
 *   <li>Track: floors within {@value #TRACK_RADIUS} of a rail centreline at rail
 *       height are removed, so no path ever crosses the tracks.</li>
 *   <li>MTR lifts are virtual nodes, one per floor, joined to the standing places
 *       around that landing and to every other floor of the same lift.</li>
 * </ul>
 *
 * <h2>Anchors</h2>
 * Exit Markers (the standing places around the pin), platforms (the standing
 * places beside their rail, {@value #ZONE_MIN}..{@value #ZONE_MAX} blocks out)
 * and street OPENINGS: where a flood from the platforms first reaches the
 * street — open to the sky AND at street level (within {@value #STREET_BAND}
 * of the median ground height around the edge of the scanned region; an open
 * stairwell's lower steps are open to the sky too, but they are not the
 * street) — suggested entrances that have no Exit Marker yet.
 *
 * <h2>Searches</h2>
 * Dijkstra over metres (plus effort: climbing costs more than walking, a gate
 * a few metres, a lift ride {@value #LIFT_METERS}), run twice per source:
 * normally and step-free (stairs and escalators forbidden, lifts and ramps
 * allowed). The winning paths are compressed into legs.
 */
public final class LayoutSolver {
    static final double STEP = 0.6;
    static final double RAMP = 0.26;
    static final double HEADROOM = 1.8;
    static final double TRACK_RADIUS = 1.25;
    static final double TRACK_HEIGHT = 1.5;
    static final double TRACKSIDE_RADIUS = 4.0;
    static final double ZONE_MIN = 1.25;
    static final double ZONE_MAX = 4.0;
    static final double ZONE_BELOW = 0.6;
    /**
     * A boarding place lower than this above the rail's centreline is at TRACK level:
     * a low platform (level boarding is real — Kalamazoo B) or the ballast between two
     * tracks. It only counts when no OTHER track runs alongside on its far side.
     */
    static final double ZONE_LOW = 0.4;
    /** Standing patches beside a platform smaller than this (in half-block nodes) are post tops, not platform. */
    static final int ZONE_MIN_PATCH = 8;
    static final double ZONE_ABOVE = 2.6;
    static final double EXIT_RADIUS = 1.6;
    static final double LIFT_RADIUS = 3.5;
    static final double LIFT_METERS = 20;
    static final float FARE_METERS = 4f;
    static final float EMERGENCY_METERS = 300f;
    static final float MAX_COST = 3000f;
    static final int MAX_OPENINGS = 8;
    static final double OPENING_CLUSTER = 10;
    static final double OPENING_NEAR_EXIT = 8;
    static final int MAX_PATH_POINTS = 160;
    static final double STREET_BAND = 2.0;
    /** Inside the MTR area, a street opening is at least this much walking from the platforms (at-grade stations). */
    static final float OPENING_MIN_WALK = 15f;

    static final byte K_WALK = 0;
    static final byte K_STAIRS = 1;
    static final byte K_ESCALATOR = 2;
    static final byte K_LIFT = 3;

    static final byte F_TRACK = 1;
    static final byte F_TRACKSIDE = 2;
    static final byte F_OUTDOOR = 4;
    static final byte F_INSIDE = 8;

    private final LayoutInput in;
    private final int qxN;
    private final int qzN;
    /** Median ground height around the region's edge (absolute Y), or NaN when unknown. */
    private double streetLevel = Double.NaN;

    // real nodes (CSR by quadrant column)
    private int[] colStart;
    private float[] h;
    private byte[] tag;
    private byte[] flags;
    private int[] colOf;
    private int n;

    // lift nodes: index n + k
    private int liftCount;
    private final List<double[]> liftPos = new ArrayList<>();   // {x, y, z}
    private final List<List<int[]>> liftLandings = new ArrayList<>(); // lift node -> {realNode, costMilli}
    private final Map<Integer, List<int[]>> liftsAtNode = new HashMap<>(); // real node -> {liftNode, costMilli}

    // search state
    private float[] dist;
    private int[] parent;
    private int[] stamp;
    private int generation;
    private float[] heapKey = new float[1024];
    private int[] heapNode = new int[1024];
    private int heapSize;

    public LayoutSolver(LayoutInput in) {
        this.in = in;
        this.qxN = in.sizeX * 2;
        this.qzN = in.sizeZ * 2;
    }

    // =================================================================== solve

    public LayoutResult solve() {
        long start = System.nanoTime();
        LayoutResult result = new LayoutResult();
        result.stationId = in.stationId;
        result.stationName = in.stationName;
        result.scannedAt = System.currentTimeMillis();
        result.cells = in.sizeX * in.sizeY * in.sizeZ;
        result.warnings.addAll(in.warnings);

        buildNodes();
        streetLevel = borderStreetLevel();
        markTrack();
        buildLifts();
        int total = n + liftCount;
        result.nodes = n;
        dist = new float[total];
        parent = new int[total];
        stamp = new int[total];

        // ---- anchors
        List<BitSet> zones = new ArrayList<>();
        List<String> platformIds = new ArrayList<>();
        for (LayoutInput.Platform platform : in.platforms) {
            BitSet zone = platformZone(platform);
            zones.add(zone);
            String id = "platform:" + platform.id();
            platformIds.add(id);
            double[] mid = platform.samples().isEmpty() ? new double[]{0, 0, 0}
                    : platform.samples().get(platform.samples().size() / 2);
            result.anchors.add(new LayoutResult.Anchor(id, "platform", platform.name(), mid[0], mid[1], mid[2]));
            if (zone.isEmpty()) {
                result.warnings.add(String.format(Locale.ROOT,
                        "Platform %s: no standing area found beside its track", label(platform.name(), platform.id())));
            }
        }
        List<int[]> exitSources = new ArrayList<>();
        List<float[]> exitSourceCosts = new ArrayList<>();
        List<String> exitIds = new ArrayList<>();
        Map<String, Integer> nameCount = new HashMap<>();
        for (LayoutInput.Exit exit : in.exits) {
            int k = nameCount.merge(exit.name(), 1, Integer::sum);
            String id = "exit:" + exit.name() + (k > 1 ? "#" + k : "");
            List<Integer> nodes = new ArrayList<>();
            List<Float> costs = new ArrayList<>();
            gatherNear(exit.x() + 0.5, exit.y(), exit.z() + 0.5, EXIT_RADIUS, -0.7, 1.3, nodes, costs);
            result.anchors.add(new LayoutResult.Anchor(id, "exit", exit.name(), exit.x() + 0.5, exit.y(), exit.z() + 0.5));
            if (nodes.isEmpty()) {
                result.warnings.add("Exit " + exit.name() + ": no floor to stand on at its marker (is it floating or inside a wall?)");
            }
            exitSources.add(toIntArray(nodes));
            exitSourceCosts.add(toFloatArray(costs));
            exitIds.add(id);
        }

        // ---- exit -> platform
        for (int e = 0; e < exitIds.size(); e++) {
            linksFrom(result, exitIds.get(e), exitSources.get(e), exitSourceCosts.get(e), zones, platformIds, 0, true);
        }
        // ---- platform -> platform (transfers), lower index first
        for (int p = 0; p < zones.size(); p++) {
            BitSet zone = zones.get(p);
            if (zone.isEmpty() || p == zones.size() - 1) {
                continue;
            }
            int[] sources = zone.stream().toArray();
            linksFrom(result, platformIds.get(p), sources, new float[sources.length], zones, platformIds, p + 1, false);
        }

        // ---- street openings the scan found (suggested entrances without a marker)
        List<int[]> openings = findOpenings(zones);
        for (int i = 0; i < openings.size(); i++) {
            int node = openings.get(i)[0];
            String id = "opening:" + i;
            result.anchors.add(new LayoutResult.Anchor(id, "opening", "", nx(node), h[node], nz(node)));
            linksFrom(result, id, new int[]{node}, new float[1], zones, platformIds, 0, false);
        }

        // ---- which platforms can be reached from the street without steps
        List<Integer> streetSources = new ArrayList<>();
        for (int[] sources : exitSources) {
            for (int s : sources) {
                streetSources.add(s);
            }
        }
        for (int[] opening : openings) {
            streetSources.add(opening[0]);
        }
        if (!streetSources.isEmpty()) {
            int[] sources = toIntArray(streetSources);
            search(sources, new float[sources.length], true, null);
            for (int p = 0; p < zones.size(); p++) {
                boolean reached = false;
                BitSet zone = zones.get(p);
                for (int node = zone.nextSetBit(0); node >= 0 && !reached; node = zone.nextSetBit(node + 1)) {
                    reached = stamp[node] == generation && dist[node] < MAX_COST;
                }
                result.platformStepFree.put(in.platforms.get(p).id(), reached);
            }
        }

        result.millis = (System.nanoTime() - start) / 1_000_000L;
        return result;
    }

    private static String label(String name, long id) {
        return name == null || name.isBlank() ? Long.toString(id) : name;
    }

    /**
     * Links from one source anchor to every platform from index {@code firstTarget}
     * on: the normal walk, and when that needs stairs, the step-free alternative.
     */
    private void linksFrom(LayoutResult result, String fromId, int[] sources, float[] sourceCosts, List<BitSet> zones,
                           List<String> platformIds, int firstTarget, boolean warnUnreachable) {
        if (sources.length == 0) {
            return;
        }
        int[] best = settleTargets(sources, sourceCosts, false, zones, firstTarget);
        List<LayoutResult.Link> normal = new ArrayList<>();
        for (int p = firstTarget; p < zones.size(); p++) {
            int node = best[p];
            if (node < 0) {
                normal.add(null);
                if (warnUnreachable && !zones.get(p).isEmpty()) {
                    result.warnings.add(fromId.replace("exit:", "Exit ") + ": no walking route to platform "
                            + label(in.platforms.get(p).name(), in.platforms.get(p).id()));
                }
                continue;
            }
            normal.add(buildLink(fromId, platformIds.get(p), node));
        }
        boolean anyStairs = false;
        for (LayoutResult.Link link : normal) {
            anyStairs |= link != null && !link.stepFree;
        }
        int[] bestStepFree = anyStairs ? settleTargets(sources, sourceCosts, true, zones, firstTarget) : null;
        for (int p = firstTarget; p < zones.size(); p++) {
            LayoutResult.Link link = normal.get(p - firstTarget);
            if (link == null) {
                continue;
            }
            if (!link.stepFree && bestStepFree != null && bestStepFree[p] >= 0) {
                link.stepFreeAlt = buildLink(fromId, platformIds.get(p), bestStepFree[p]);
            }
            result.links.add(link);
        }
    }

    // ================================================================== nodes

    private void buildNodes() {
        int columns = qxN * qzN;
        colStart = new int[columns + 1];
        FloatList heights = new FloatList();
        ByteList tags = new ByteList();
        IntList cols = new IntList();
        float[] loLoose = new float[in.sizeY + 2];
        float[] hiLoose = new float[in.sizeY + 2];
        float[] loFloor = new float[in.sizeY + 2];
        float[] hiFloor = new float[in.sizeY + 2];
        for (int qz = 0; qz < qzN; qz++) {
            for (int qx = 0; qx < qxN; qx++) {
                int col = qz * qxN + qx;
                colStart[col] = heights.size;
                int bx = qx >> 1;
                int bz = qz >> 1;
                int q = (qx & 1) + 2 * (qz & 1);
                int looseCount = merge(bx, bz, q, false, loLoose, hiLoose);
                int floorCount = merge(bx, bz, q, true, loFloor, hiFloor);
                for (int i = 0; i < floorCount; i++) {
                    float top = hiFloor[i];
                    if (top > in.minY + in.sizeY - 1) {
                        continue;
                    }
                    if (!clear(loLoose, hiLoose, looseCount, top, (float) (top + HEADROOM))) {
                        continue;
                    }
                    int feet = (int) Math.floor(top + 1e-3) - in.minY;
                    CellInfo feetCell = in.cell(bx, feet, bz);
                    CellInfo headCell = in.cell(bx, feet + 1, bz);
                    if (feetCell.liquid || headCell.liquid) {
                        continue;
                    }
                    CellInfo under = in.cell(bx, (int) Math.floor(top - 1e-3) - in.minY, bz);
                    byte nodeTag = CellInfo.TAG_NONE;
                    if (feetCell.tag == CellInfo.TAG_FARE || headCell.tag == CellInfo.TAG_FARE) {
                        nodeTag = CellInfo.TAG_FARE;
                    } else if (feetCell.tag == CellInfo.TAG_EMERGENCY || headCell.tag == CellInfo.TAG_EMERGENCY) {
                        nodeTag = CellInfo.TAG_EMERGENCY;
                    } else if (under.tag == CellInfo.TAG_ESCALATOR || feetCell.tag == CellInfo.TAG_ESCALATOR) {
                        nodeTag = CellInfo.TAG_ESCALATOR;
                    }
                    heights.add(top);
                    tags.add(nodeTag);
                    cols.add(col);
                }
            }
        }
        colStart[columns] = heights.size;
        n = heights.size;
        h = heights.toArray();
        tag = tags.toArray();
        colOf = cols.toArray();
        flags = new byte[n];
        for (int node = 0; node < n; node++) {
            int col = colOf[node];
            int bx = (col % qxN) >> 1;
            int bz = (col / qxN) >> 1;
            int surface = in.surfaceAt(bx, bz);
            if (surface != Integer.MIN_VALUE && h[node] >= surface - 0.01f) {
                flags[node] |= F_OUTDOOR;
            }
            long wx = in.minX + bx;
            long wz = in.minZ + bz;
            if (wx >= in.areaMinX && wx <= in.areaMaxX && wz >= in.areaMinZ && wz <= in.areaMaxZ) {
                flags[node] |= F_INSIDE;
            }
        }
    }

    /** Merged collision intervals (absolute Y) of one quadrant column, bottom up. */
    private int merge(int bx, int bz, int q, boolean floor, float[] lo, float[] hi) {
        int count = 0;
        for (int y = 0; y < in.sizeY; y++) {
            CellInfo cell = in.cell(bx, y, bz);
            boolean has = floor ? cell.floor[q] : cell.block[q];
            if (!has) {
                continue;
            }
            float b = in.minY + y + (floor ? cell.floorBottom[q] : cell.blockBottom[q]);
            float t = in.minY + y + (floor ? cell.floorTop[q] : cell.blockTop[q]);
            if (count > 0 && b <= hi[count - 1] + 0.02f) {
                hi[count - 1] = Math.max(hi[count - 1], t);
            } else {
                if (count == lo.length) {
                    return count;
                }
                lo[count] = b;
                hi[count] = t;
                count++;
            }
        }
        return count;
    }

    /** Is (from, to) free of every obstruction interval? */
    private static boolean clear(float[] lo, float[] hi, int count, float from, float to) {
        for (int i = 0; i < count; i++) {
            if (lo[i] < to - 0.01f && hi[i] > from + 0.01f) {
                return false;
            }
        }
        return true;
    }

    private double nx(int node) {
        if (node >= n) {
            return liftPos.get(node - n)[0];
        }
        return in.minX + (colOf[node] % qxN) * 0.5 + 0.25;
    }

    private double nz(int node) {
        if (node >= n) {
            return liftPos.get(node - n)[2];
        }
        return in.minZ + (colOf[node] / qxN) * 0.5 + 0.25;
    }

    private double ny(int node) {
        return node >= n ? liftPos.get(node - n)[1] : h[node];
    }

    /** Quadrant columns whose centre is within {@code radius} of (x, z), visited with their distance. */
    private interface ColumnVisitor {
        void visit(int col, double horizontal);
    }

    private void columnsAround(double x, double z, double radius, ColumnVisitor visitor) {
        int qx0 = (int) Math.floor((x - radius - in.minX) * 2);
        int qx1 = (int) Math.ceil((x + radius - in.minX) * 2);
        int qz0 = (int) Math.floor((z - radius - in.minZ) * 2);
        int qz1 = (int) Math.ceil((z + radius - in.minZ) * 2);
        for (int qz = Math.max(0, qz0); qz <= Math.min(qzN - 1, qz1); qz++) {
            for (int qx = Math.max(0, qx0); qx <= Math.min(qxN - 1, qx1); qx++) {
                double cx = in.minX + qx * 0.5 + 0.25;
                double cz = in.minZ + qz * 0.5 + 0.25;
                double d = Math.hypot(cx - x, cz - z);
                if (d <= radius) {
                    visitor.visit(qz * qxN + qx, d);
                }
            }
        }
    }

    private void markTrack() {
        for (double[] sample : in.track) {
            double sy = sample[1];
            columnsAround(sample[0], sample[2], TRACKSIDE_RADIUS, (col, d) -> {
                for (int node = colStart[col]; node < colStart[col + 1]; node++) {
                    double dy = h[node] - sy;
                    if (d <= TRACK_RADIUS && Math.abs(dy) <= TRACK_HEIGHT) {
                        flags[node] |= F_TRACK;
                    }
                    if (dy >= -TRACK_HEIGHT && dy <= 3.0) {
                        flags[node] |= F_TRACKSIDE;
                    }
                }
            });
        }
    }

    /**
     * The standing places beside a platform's rail: a band {@value #ZONE_MIN}..{@value #ZONE_MAX}
     * out to either side, only ALONGSIDE the rail — each sample claims a thin strip
     * across the track, so the band never wraps round the platform's ends into the hall.
     */
    private BitSet platformZone(LayoutInput.Platform platform) {
        BitSet zone = new BitSet(n);
        List<double[]> samples = platform.samples();
        List<double[]> foreign = foreignTrack(samples);
        for (int i = 0; i < samples.size(); i++) {
            double[] sample = samples.get(i);
            double[] a = samples.get(Math.max(0, i - 1));
            double[] b = samples.get(Math.min(samples.size() - 1, i + 1));
            double tx = b[0] - a[0];
            double tz = b[2] - a[2];
            double len = Math.hypot(tx, tz);
            double ux = len < 1e-6 ? 0 : tx / len;
            double uz = len < 1e-6 ? 0 : tz / len;
            double sy = sample[1];
            columnsAround(sample[0], sample[2], ZONE_MAX, (col, d) -> {
                double cx = in.minX + (col % qxN) * 0.5 + 0.25;
                double cz = in.minZ + (col / qxN) * 0.5 + 0.25;
                double along = (cx - sample[0]) * ux + (cz - sample[2]) * uz;
                if (d < ZONE_MIN || (len > 1e-6 && Math.abs(along) > 0.3)) {
                    return;
                }
                for (int node = colStart[col]; node < colStart[col + 1]; node++) {
                    double dy = h[node] - sy;
                    if ((flags[node] & F_TRACK) != 0 || dy < -ZONE_BELOW || dy > ZONE_ABOVE) {
                        continue;
                    }
                    if (dy < ZONE_LOW && betweenTracks(cx, cz, sample, foreign)) {
                        continue; // ballast between this track and another one
                    }
                    zone.set(node);
                }
            });
        }
        dropSmallPatches(zone);
        return zone;
    }

    /** Rail samples near this platform that belong to OTHER tracks (more than a block from its rail). */
    private List<double[]> foreignTrack(List<double[]> samples) {
        List<double[]> out = new ArrayList<>();
        if (samples.isEmpty()) {
            return out;
        }
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (double[] s : samples) {
            x0 = Math.min(x0, s[0]);
            x1 = Math.max(x1, s[0]);
            z0 = Math.min(z0, s[2]);
            z1 = Math.max(z1, s[2]);
        }
        double pad = ZONE_MAX + 2;
        for (double[] t : in.track) {
            if (t[0] < x0 - pad || t[0] > x1 + pad || t[2] < z0 - pad || t[2] > z1 + pad) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (double[] s : samples) {
                best = Math.min(best, Math.hypot(t[0] - s[0], t[2] - s[2]));
                if (best <= 1.0) {
                    break;
                }
            }
            if (best > 1.0) {
                out.add(t);
            }
        }
        return out;
    }

    /** Is (x, z) between the platform's rail (at {@code own}) and another track at about the same height? */
    private static boolean betweenTracks(double x, double z, double[] own, List<double[]> foreign) {
        double ox = own[0] - x;
        double oz = own[2] - z;
        for (double[] f : foreign) {
            double fx = f[0] - x;
            double fz = f[2] - z;
            if (Math.abs(f[1] - own[1]) <= 1.5 && Math.hypot(fx, fz) <= ZONE_MAX && ox * fx + oz * fz < 0) {
                return true;
            }
        }
        return false;
    }

    /** Removes zone patches (connected within the zone) too small to be a platform: post and wall tops. */
    private void dropSmallPatches(BitSet zone) {
        BitSet seen = new BitSet(n);
        IntList stack = new IntList();
        IntList patch = new IntList();
        for (int start = zone.nextSetBit(0); start >= 0; start = zone.nextSetBit(start + 1)) {
            if (seen.get(start)) {
                continue;
            }
            patch.size = 0;
            stack.size = 0;
            stack.add(start);
            seen.set(start);
            while (stack.size > 0) {
                int node = stack.data[--stack.size];
                patch.add(node);
                neighbours(node, false, (to, cost, kind) -> {
                    if (to < n && zone.get(to) && !seen.get(to)) {
                        seen.set(to);
                        stack.add(to);
                    }
                });
            }
            if (patch.size < ZONE_MIN_PATCH || !leadsAway(patch.data[0])) {
                for (int i = 0; i < patch.size; i++) {
                    zone.clear(patch.data[i]);
                }
            }
        }
    }

    private void gatherNear(double x, double y, double z, double radius, double below, double above,
                            List<Integer> nodes, List<Float> costs) {
        columnsAround(x, z, radius, (col, d) -> {
            for (int node = colStart[col]; node < colStart[col + 1]; node++) {
                double dy = h[node] - y;
                if ((flags[node] & F_TRACK) == 0 && dy >= below && dy <= above) {
                    nodes.add(node);
                    costs.add((float) d);
                }
            }
        });
    }

    private void buildLifts() {
        for (LayoutInput.Lift lift : in.lifts) {
            List<Integer> floorNodes = new ArrayList<>();
            for (int[] floor : lift.floors()) {
                int liftNode = n + liftCount;
                List<Integer> nodes = new ArrayList<>();
                List<Float> costs = new ArrayList<>();
                gatherNear(floor[0] + 0.5, floor[1], floor[2] + 0.5, lift.radius() > 0 ? lift.radius() : LIFT_RADIUS,
                        -1.2, 1.2, nodes, costs);
                if (nodes.isEmpty()) {
                    continue;
                }
                liftCount++;
                liftPos.add(new double[]{floor[0] + 0.5, floor[1], floor[2] + 0.5});
                List<int[]> landings = new ArrayList<>();
                for (int i = 0; i < nodes.size(); i++) {
                    int real = nodes.get(i);
                    int cost = Math.round(costs.get(i) * 1000);
                    landings.add(new int[]{real, cost});
                    liftsAtNode.computeIfAbsent(real, k -> new ArrayList<>()).add(new int[]{liftNode, cost});
                }
                liftLandings.add(landings);
                floorNodes.add(liftNode);
            }
            // every floor of this lift reaches every other floor
            for (int a : floorNodes) {
                for (int b : floorNodes) {
                    if (a != b) {
                        int cost = (int) Math.round((LIFT_METERS + Math.abs(ny(a) - ny(b))) * 1000);
                        liftLandings.get(a - n).add(new int[]{b, cost});
                    }
                }
            }
        }
    }

    /**
     * Does the standing area around {@code start} lead anywhere away from the tracks
     * (stairs, a concourse, the street, a lift)? The top of a wall or a row of posts
     * between two tracks does not — it is beside BOTH tracks' platforms and would
     * join them with a 0 m "transfer" nobody can make (Cherry Bridge).
     */
    private boolean leadsAway(int start) {
        java.util.BitSet seen = new java.util.BitSet(n + liftCount);
        IntList queue = new IntList();
        queue.add(start);
        seen.set(start);
        final boolean[] found = {false};
        for (int head = 0; head < queue.size && head < 40_000 && !found[0]; head++) {
            int node = queue.data[head];
            if (node >= n || (flags[node] & F_TRACKSIDE) == 0) {
                found[0] = true;
                break;
            }
            neighbours(node, false, (to, cost, kind) -> {
                if (!seen.get(to)) {
                    seen.set(to);
                    queue.add(to);
                }
            });
        }
        return found[0];
    }

    // ============================================================ neighbours

    private interface EdgeVisitor {
        void edge(int to, float cost, byte kind);
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** The standing node in quadrant column (qx, qz) nearest to height {@code at}, within a step; -1 if none. */
    private int floorNear(int qx, int qz, float at) {
        if (qx < 0 || qz < 0 || qx >= qxN || qz >= qzN) {
            return -1;
        }
        int col = qz * qxN + qx;
        int best = -1;
        float bestDy = Float.MAX_VALUE;
        for (int node = colStart[col]; node < colStart[col + 1]; node++) {
            if ((flags[node] & F_TRACK) != 0) {
                continue;
            }
            float dy = Math.abs(h[node] - at);
            if (dy <= STEP && dy < bestDy) {
                best = node;
                bestDy = dy;
            }
        }
        return best;
    }

    private void neighbours(int node, boolean stepFree, EdgeVisitor visitor) {
        if (node >= n) {
            for (int[] edge : liftLandings.get(node - n)) {
                visitor.edge(edge[0], edge[1] / 1000f, K_LIFT);
            }
            return;
        }
        int col = colOf[node];
        int qx = col % qxN;
        int qz = col / qxN;
        float here = h[node];
        for (int[] d : DIRS) {
            int to = floorNear(qx + d[0], qz + d[1], here);
            if (to < 0) {
                continue;
            }
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (floorNear(qx + d[0], qz, here) < 0 || floorNear(qx, qz + d[1], here) < 0)) {
                continue; // never cut a corner past a wall
            }
            float dh = h[to] - here;
            float adh = Math.abs(dh);
            byte kind;
            if ((tag[node] == CellInfo.TAG_ESCALATOR || tag[to] == CellInfo.TAG_ESCALATOR) && adh > RAMP) {
                kind = K_ESCALATOR;
            } else if (adh > RAMP) {
                kind = K_STAIRS;
            } else {
                kind = K_WALK;
            }
            if (stepFree && kind != K_WALK) {
                continue;
            }
            float cost = diagonal ? 0.7071f : 0.5f;
            if (kind == K_STAIRS) {
                cost += adh * (dh > 0 ? 1.6f : 0.8f);
            } else if (kind == K_ESCALATOR) {
                cost = cost * 0.6f + adh * 0.3f;
            } else {
                cost += adh * 0.5f;
            }
            if (tag[to] == CellInfo.TAG_FARE && tag[node] != CellInfo.TAG_FARE) {
                cost += FARE_METERS;
            } else if (tag[to] == CellInfo.TAG_EMERGENCY && tag[node] != CellInfo.TAG_EMERGENCY) {
                cost += EMERGENCY_METERS;
            }
            visitor.edge(to, cost, kind);
        }
        List<int[]> lifts = liftsAtNode.get(node);
        if (lifts != null) {
            for (int[] edge : lifts) {
                visitor.edge(edge[0], edge[1] / 1000f, K_LIFT);
            }
        }
    }

    // ================================================================ search

    private interface Settle {
        /** Return false to stop the search. */
        boolean settled(int node, float cost);
    }

    /** Dijkstra from the sources; {@code settle} may stop it early. Results stay in dist/parent for this generation. */
    private void search(int[] sources, float[] sourceCosts, boolean stepFree, Settle settle) {
        generation++;
        heapSize = 0;
        for (int i = 0; i < sources.length; i++) {
            relax(sources[i], sourceCosts[i], -1);
        }
        final boolean[] stop = {false};
        while (heapSize > 0 && !stop[0]) {
            float cost = heapKey[0];
            int node = heapNode[0];
            pop();
            if (cost > dist[node] || cost > MAX_COST) {
                if (cost > MAX_COST) {
                    break;
                }
                continue;
            }
            if (settle != null && !settle.settled(node, cost)) {
                break;
            }
            neighbours(node, stepFree, (to, edgeCost, kind) -> relax(to, cost + edgeCost, node));
        }
    }

    private void relax(int node, float cost, int from) {
        if (stamp[node] == generation && dist[node] <= cost) {
            return;
        }
        stamp[node] = generation;
        dist[node] = cost;
        parent[node] = from;
        push(cost, node);
    }

    /** Settles until every platform zone from {@code firstTarget} on has been reached; returns the node reached per zone. */
    private int[] settleTargets(int[] sources, float[] sourceCosts, boolean stepFree, List<BitSet> zones, int firstTarget) {
        int[] best = new int[zones.size()];
        java.util.Arrays.fill(best, -1);
        final int[] remaining = {0};
        for (int p = firstTarget; p < zones.size(); p++) {
            if (!zones.get(p).isEmpty()) {
                remaining[0]++;
            }
        }
        if (remaining[0] == 0) {
            return best;
        }
        search(sources, sourceCosts, stepFree, (node, cost) -> {
            for (int p = firstTarget; p < zones.size(); p++) {
                if (best[p] < 0 && zones.get(p).get(node)) {
                    best[p] = node;
                    remaining[0]--;
                }
            }
            return remaining[0] > 0;
        });
        return best;
    }

    private void push(float key, int node) {
        if (heapSize == heapKey.length) {
            heapKey = java.util.Arrays.copyOf(heapKey, heapSize * 2);
            heapNode = java.util.Arrays.copyOf(heapNode, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (heapKey[p] <= key) {
                break;
            }
            heapKey[i] = heapKey[p];
            heapNode[i] = heapNode[p];
            i = p;
        }
        heapKey[i] = key;
        heapNode[i] = node;
    }

    private void pop() {
        float key = heapKey[--heapSize];
        int node = heapNode[heapSize];
        int i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= heapSize) {
                break;
            }
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) {
                c++;
            }
            if (heapKey[c] >= key) {
                break;
            }
            heapKey[i] = heapKey[c];
            heapNode[i] = heapNode[c];
            i = c;
        }
        heapKey[i] = key;
        heapNode[i] = node;
    }

    // ============================================================== openings

    /**
     * Where a rider coming up from the platforms first reaches the street: a flood
     * from every platform that stops at the first street node on each way out.
     * Street = out in the open, or outside the MTR station area — never beside a
     * track. Clustered, and dropped when an Exit Marker already stands there.
     *
     * @return {node, cost-milli} per opening, nearest first
     */
    private List<int[]> findOpenings(List<BitSet> zones) {
        List<Integer> sources = new ArrayList<>();
        for (BitSet zone : zones) {
            zone.stream().forEach(sources::add);
        }
        if (sources.isEmpty()) {
            return List.of();
        }
        List<int[]> candidates = new ArrayList<>();
        int[] src = toIntArray(sources);
        generation++;
        heapSize = 0;
        for (int s : src) {
            relax(s, 0f, -1);
        }
        while (heapSize > 0) {
            float cost = heapKey[0];
            int node = heapNode[0];
            pop();
            if (cost > dist[node]) {
                continue;
            }
            if (cost > MAX_COST) {
                break;
            }
            if (node < n && isStreet(node) && ((flags[node] & F_INSIDE) == 0 || cost >= OPENING_MIN_WALK)) {
                candidates.add(new int[]{node, Math.round(cost * 1000)});
                continue; // the street is where this way out ends
            }
            neighbours(node, false, (to, edgeCost, kind) -> relax(to, cost + edgeCost, node));
        }
        List<int[]> chosen = new ArrayList<>();
        for (int[] candidate : candidates) {
            int node = candidate[0];
            double x = nx(node);
            double z = nz(node);
            boolean near = false;
            for (int[] other : chosen) {
                near |= Math.hypot(nx(other[0]) - x, nz(other[0]) - z) < OPENING_CLUSTER;
            }
            for (LayoutInput.Exit exit : in.exits) {
                near |= Math.hypot(exit.x() + 0.5 - x, exit.z() + 0.5 - z) < OPENING_NEAR_EXIT;
            }
            if (!near) {
                chosen.add(candidate);
                if (chosen.size() >= MAX_OPENINGS) {
                    break;
                }
            }
        }
        return chosen;
    }

    /**
     * The street: open to the sky, near street level, and FLAT (at least three of the
     * four sides continue at the same height) — the top step of an open stairwell is
     * the street, the steps below it are not.
     */
    private boolean isStreet(int node) {
        byte f = flags[node];
        if ((f & F_TRACKSIDE) != 0 || (f & F_OUTDOOR) == 0 || Double.isNaN(streetLevel)
                || Math.abs(h[node] - streetLevel) > STREET_BAND) {
            return false;
        }
        int col = colOf[node];
        int qx = col % qxN;
        int qz = col / qxN;
        int flat = 0;
        for (int d = 0; d < 4; d++) {
            int other = floorNear(qx + DIRS[d][0], qz + DIRS[d][1], h[node]);
            if (other >= 0 && Math.abs(h[other] - h[node]) <= 0.1f) {
                flat++;
            }
        }
        return flat >= 3;
    }

    /** The ground height around the scanned region's edge: the neighbourhood's street level. */
    private double borderStreetLevel() {
        List<Integer> values = new ArrayList<>();
        for (int x = 0; x < in.sizeX; x++) {
            addSurface(values, x, 0);
            addSurface(values, x, in.sizeZ - 1);
        }
        for (int z = 1; z < in.sizeZ - 1; z++) {
            addSurface(values, 0, z);
            addSurface(values, in.sizeX - 1, z);
        }
        if (values.isEmpty()) {
            return Double.NaN;
        }
        values.sort(Integer::compare);
        return values.get(values.size() / 2);
    }

    private void addSurface(List<Integer> values, int x, int z) {
        int s = in.surfaceAt(x, z);
        if (s != Integer.MIN_VALUE) {
            values.add(s);
        }
    }

    // ================================================================= links

    /** The path the last search found to {@code target}, as a link with legs. */
    private LayoutResult.Link buildLink(String from, String to, int target) {
        IntList nodes = new IntList();
        for (int node = target; node >= 0; node = parent[node]) {
            nodes.add(node);
            if (nodes.size > 200_000) {
                break; // cannot happen on a real parent chain; never loop forever
            }
        }
        int[] path = nodes.toArray();
        reverse(path);

        LayoutResult.Link link = new LayoutResult.Link();
        link.from = from;
        link.to = to;
        List<Object[]> legs = new ArrayList<>(); // {kind String, meters double, dy double}
        for (int i = 1; i < path.length; i++) {
            int a = path[i - 1];
            int b = path[i];
            String kind;
            double meters;
            double dy = ny(b) - ny(a);
            if (a >= n || b >= n) {
                boolean ride = a >= n && b >= n;
                kind = ride ? "lift" : "walk";
                meters = ride ? 0 : Math.hypot(nx(b) - nx(a), nz(b) - nz(a));
                if (!ride) {
                    dy = 0;
                }
            } else {
                meters = Math.hypot(nx(b) - nx(a), nz(b) - nz(a));
                double adh = Math.abs(dy);
                if ((tag[a] == CellInfo.TAG_ESCALATOR || tag[b] == CellInfo.TAG_ESCALATOR) && adh > RAMP) {
                    kind = "escalator";
                } else if (adh > RAMP) {
                    kind = "stairs";
                } else {
                    kind = "walk";
                }
            }
            addLeg(legs, kind, meters, dy);
            if (b < n && tag[b] == CellInfo.TAG_FARE && (a >= n || tag[a] != CellInfo.TAG_FARE)) {
                addLeg(legs, "fare", 0, 0);
            } else if (b < n && tag[b] == CellInfo.TAG_EMERGENCY && (a >= n || tag[a] != CellInfo.TAG_EMERGENCY)) {
                addLeg(legs, "emergency", 0, 0);
            }
        }
        // a short landing between two flights is part of the stairs
        for (int i = legs.size() - 2; i >= 1; i--) {
            if ("walk".equals(legs.get(i)[0]) && (double) legs.get(i)[1] < 1.6
                    && "stairs".equals(legs.get(i - 1)[0]) && "stairs".equals(legs.get(i + 1)[0])) {
                Object[] merged = legs.get(i - 1);
                merged[1] = (double) merged[1] + (double) legs.get(i)[1] + (double) legs.get(i + 1)[1];
                merged[2] = (double) merged[2] + (double) legs.get(i)[2] + (double) legs.get(i + 1)[2];
                legs.remove(i + 1);
                legs.remove(i);
            }
        }
        compact(legs);
        boolean stepFree = true;
        double meters = 0;
        double extra = 0;
        for (Object[] leg : legs) {
            String kind = (String) leg[0];
            double m = (double) leg[1];
            double dy = (double) leg[2];
            if ("walk".equals(kind) && m < 0.3) {
                continue;
            }
            double seconds = switch (kind) {
                case "lift" -> 12 + Math.abs(dy) / 2.0;
                case "fare" -> 3;
                default -> 0;
            };
            if ("stairs".equals(kind) || "escalator".equals(kind)) {
                stepFree = false;
            }
            meters += m;
            extra += seconds;
            link.legs.add(new LayoutResult.Leg(kind, m, dy, seconds));
        }
        link.meters = meters;
        link.extraSeconds = extra;
        link.stepFree = stepFree;

        List<double[]> points = new ArrayList<>(path.length);
        for (int node : path) {
            points.add(new double[]{nx(node), ny(node) + 0.05, nz(node)});
        }
        link.path.addAll(simplify(points, 0.35));
        return link;
    }

    /**
     * Folds crumbs into their neighbours: a walk of under a metre between two legs of
     * the same kind (a landing between flights, the flat top plate of an escalator)
     * joins them; any other walk under half a metre is dropped (its distance goes to
     * the leg before). Repeats until nothing changes.
     */
    private static void compact(List<Object[]> legs) {
        for (Object[] leg : legs) {
            if ("escalator".equals(leg[0]) && Math.abs((double) leg[2]) < 1.5) {
                leg[0] = "walk"; // the flat plates at an escalator's ends
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < legs.size(); i++) {
                Object[] a = legs.get(i - 1);
                Object[] b = legs.get(i);
                if (a[0].equals(b[0]) && !"fare".equals(a[0]) && !"emergency".equals(a[0]) && !"lift".equals(a[0])
                        && ("walk".equals(a[0]) || Math.signum((double) a[2]) * Math.signum((double) b[2]) >= 0)) {
                    a[1] = (double) a[1] + (double) b[1];
                    a[2] = (double) a[2] + (double) b[2];
                    legs.remove(i);
                    changed = true;
                    break;
                }
            }
            if (changed) {
                continue;
            }
            for (int i = 0; i < legs.size(); i++) {
                Object[] leg = legs.get(i);
                if (!"walk".equals(leg[0]) || legs.size() == 1) {
                    continue;
                }
                double m = (double) leg[1];
                Object[] before = i > 0 ? legs.get(i - 1) : null;
                Object[] after = i + 1 < legs.size() ? legs.get(i + 1) : null;
                boolean sameAround = before != null && after != null && before[0].equals(after[0])
                        && !"fare".equals(before[0]) && !"emergency".equals(before[0]) && !"lift".equals(before[0])
                        && Math.signum((double) before[2]) * Math.signum((double) after[2]) >= 0;
                if (sameAround && m < 1.0) {
                    before[1] = (double) before[1] + m + (double) after[1];
                    before[2] = (double) before[2] + (double) leg[2] + (double) after[2];
                    legs.remove(i + 1);
                    legs.remove(i);
                    changed = true;
                    break;
                }
                if (m < 0.8) {
                    Object[] into = before != null ? before : after;
                    if (into != null && !"fare".equals(into[0]) && !"emergency".equals(into[0])) {
                        into[1] = (double) into[1] + m;
                        into[2] = (double) into[2] + (double) leg[2];
                    }
                    legs.remove(i);
                    changed = true;
                    break;
                }
            }
        }
    }

    private static void addLeg(List<Object[]> legs, String kind, double meters, double dy) {
        if (!legs.isEmpty()) {
            Object[] last = legs.get(legs.size() - 1);
            if (last[0].equals(kind) && !"fare".equals(kind) && !"emergency".equals(kind)
                    && ("walk".equals(kind) || Math.signum(dy) == 0 || Math.signum((double) last[2]) == 0
                    || Math.signum(dy) == Math.signum((double) last[2]))) {
                last[1] = (double) last[1] + meters;
                last[2] = (double) last[2] + dy;
                return;
            }
        }
        legs.add(new Object[]{kind, meters, dy});
    }

    /** Ramer–Douglas–Peucker in 3D, then thinned to {@value #MAX_PATH_POINTS}. */
    static List<double[]> simplify(List<double[]> points, double tolerance) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, points.size() - 1});
        while (!stack.isEmpty()) {
            int[] span = stack.pop();
            double[] a = points.get(span[0]);
            double[] b = points.get(span[1]);
            double worst = -1;
            int at = -1;
            for (int i = span[0] + 1; i < span[1]; i++) {
                double d = segmentDistance(points.get(i), a, b);
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
            if (worst > tolerance && at > 0) {
                keep[at] = true;
                stack.push(new int[]{span[0], at});
                stack.push(new int[]{at, span[1]});
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        if (out.size() > MAX_PATH_POINTS) {
            List<double[]> thinned = new ArrayList<>();
            double step = (out.size() - 1) / (double) (MAX_PATH_POINTS - 1);
            for (int i = 0; i < MAX_PATH_POINTS; i++) {
                thinned.add(out.get((int) Math.round(i * step)));
            }
            return thinned;
        }
        return out;
    }

    private static double segmentDistance(double[] p, double[] a, double[] b) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] ap = {p[0] - a[0], p[1] - a[1], p[2] - a[2]};
        double len = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
        double t = len < 1e-9 ? 0 : Math.max(0, Math.min(1, (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / len));
        double dx = a[0] + ab[0] * t - p[0];
        double dy = a[1] + ab[1] * t - p[1];
        double dz = a[2] + ab[2] * t - p[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ============================================================= plumbing

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static void reverse(int[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private static final class FloatList {
        float[] data = new float[4096];
        int size;

        void add(float v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static final class ByteList {
        byte[] data = new byte[4096];
        int size;

        void add(byte v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        byte[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static final class IntList {
        int[] data = new int[4096];
        int size;

        void add(int v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
