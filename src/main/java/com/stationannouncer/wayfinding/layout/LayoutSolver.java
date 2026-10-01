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
 * <h2>Fare control</h2>
 * Fare lanes (ours, MTR's ticket barriers, other mods' gates — see
 * {@code LayoutScanner.classify}) within {@value #FARE_GROUP_RADIUS} blocks of each
 * other form one FARE CONTROL group (an emergency exit door beside them is its
 * service gate). The PAID AREA is everything a rider reaches from the platforms
 * without passing a gate. Each group is an anchor ("fare:0"); walks name the group
 * they pass. A station with no exit and no street opening is entered through its
 * fare control instead (inside a building, under a mall). Warnings: an exit or
 * opening inside the paid area (riders skip the gates), a group with no platform
 * behind it, a group with nothing unpaid in front of it.
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
    /** Fare lanes this close together (blocks, same floor) are one line of gates. */
    static final double FARE_GROUP_RADIUS = 3.0;

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
    private final List<Long> liftIdOf = new ArrayList<>();        // lift node -> MTR lift id
    private final List<List<int[]>> liftLandings = new ArrayList<>(); // lift node -> {realNode, costMilli}
    private final Map<Integer, List<int[]>> liftsAtNode = new HashMap<>(); // real node -> {liftNode, costMilli}

    // fare control: node -> index into fareGroups, -1 = not a gate
    private int[] fareGroup;
    private final List<FareGroup> fareGroups = new ArrayList<>();
    /** Reached from the platforms without passing a gate (real + lift nodes). */
    private BitSet paid;
    /** The station has a working line of gates (platforms behind it, something unpaid in front). */
    private boolean gated;

    /** One line of fare gates. */
    private static final class FareGroup {
        final int[] members;
        /** The anchor id, or null when the group is not part of this station (outside its area, no platform behind it). */
        String anchorId;
        boolean paidSide;
        int[] freeSide = new int[0];

        FareGroup(int[] members) {
            this.members = members;
        }
    }

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

        result.region = new int[]{in.minX, in.minY, in.minZ,
                in.minX + in.sizeX - 1, in.minY + in.sizeY - 1, in.minZ + in.sizeZ - 1};
        result.margin = in.margin;
        result.area = new long[]{in.areaMinX, in.areaMinZ, in.areaMaxX, in.areaMaxZ};

        // ---- anchors (fare gates first: they cut platforms that run through fare control)
        findFareGroups();
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

        // ---- fare control: lines of gates, and the paid area behind them
        paid = paidArea(zones);
        int fareAnchors = 0;
        for (FareGroup group : fareGroups) {
            sides(group);
            boolean inside = false;
            for (int node : group.members) {
                inside |= (flags[node] & F_INSIDE) != 0;
            }
            if (!group.paidSide && !inside) {
                continue; // another station's gates inside the scan margin
            }
            gated |= group.paidSide;
            group.anchorId = "fare:" + fareAnchors++;
            int at = centreNode(group.members);
            result.anchors.add(new LayoutResult.Anchor(group.anchorId, "fare", "", nx(at), h[at], nz(at)));
            result.access.put(group.anchorId, new LayoutResult.Access());
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
        // street openings the scan found (suggested entrances without a marker)
        List<int[]> openings = findOpenings(zones);

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
        // ---- opening -> platform
        for (int i = 0; i < openings.size(); i++) {
            int node = openings.get(i)[0];
            String id = "opening:" + i;
            result.anchors.add(new LayoutResult.Anchor(id, "opening", "", nx(node), h[node], nz(node)));
            linksFrom(result, id, new int[]{node}, new float[1], zones, platformIds, 0, false);
        }
        // ---- no exit and no opening: the station is entered through its fare control
        List<FareGroup> entrances = new ArrayList<>();
        if (in.exits.isEmpty() && openings.isEmpty()) {
            for (FareGroup group : fareGroups) {
                if (group.anchorId != null && group.paidSide && group.freeSide.length > 0) {
                    entrances.add(group);
                    result.access.get(group.anchorId).entrance = true;
                    linksFrom(result, group.anchorId, group.freeSide, new float[group.freeSide.length], zones,
                            platformIds, 0, false);
                }
            }
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
        for (FareGroup group : entrances) {
            for (int s : group.freeSide) {
                streetSources.add(s);
            }
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

        fareWarnings(result, exitIds, exitSources, openings, zones);
        summariseAccess(result, zones);
        result.geometry = geometry(result, zones, platformIds);

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
                    } else if (feetCell.tag == CellInfo.TAG_LIFT_DOOR || headCell.tag == CellInfo.TAG_LIFT_DOOR) {
                        nodeTag = CellInfo.TAG_LIFT_DOOR;
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
        Map<Integer, Integer> sampleOf = new HashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            final int sampleIndex = i;
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
                    sampleOf.putIfAbsent(node, sampleIndex);
                }
            });
        }
        dropSmallPatches(zone);
        cutAtFareControl(zone, sampleOf);
        return zone;
    }

    /**
     * A platform whose track runs on past a line of gates (Atlantic: gates across the
     * platform, the street stairs landing on its far end): only the side of fare control
     * with most of the standing room is the platform. Zone parts that reach each other
     * without passing a gate are one part; a smaller part that meets the main one only
     * at the same fare control AND lies further along the track (end to end, not across
     * the track from it) — or is a scrap a tenth its size — is the unpaid side, and is dropped.
     */
    private void cutAtFareControl(BitSet zone, Map<Integer, Integer> sampleOf) {
        if (fareGroups.isEmpty() || zone.isEmpty()) {
            return;
        }
        int[] regionOf = new int[n + liftCount];
        java.util.Arrays.fill(regionOf, -1);
        List<Integer> sizes = new ArrayList<>();
        List<java.util.Set<Integer>> touched = new ArrayList<>();
        IntList stack = new IntList();
        for (int start = zone.nextSetBit(0); start >= 0; start = zone.nextSetBit(start + 1)) {
            if (regionOf[start] >= 0) {
                continue;
            }
            int region = sizes.size();
            java.util.Set<Integer> gates = new java.util.HashSet<>();
            final int[] size = {0};
            stack.size = 0;
            stack.add(start);
            regionOf[start] = region;
            while (stack.size > 0) {
                int node = stack.data[--stack.size];
                if (node < n && zone.get(node)) {
                    size[0]++;
                }
                neighbours(node, false, (to, cost, kind) -> {
                    if (isGate(to)) {
                        if (fareGroup[to] >= 0) {
                            gates.add(fareGroup[to]);
                        }
                    } else if (regionOf[to] < 0) {
                        regionOf[to] = region;
                        stack.add(to);
                    }
                });
            }
            sizes.add(size[0]);
            touched.add(gates);
        }
        if (sizes.size() < 2) {
            return;
        }
        int main = 0;
        for (int r = 1; r < sizes.size(); r++) {
            if (sizes.get(r) > sizes.get(main)) {
                main = r;
            }
        }
        // each part's stretch along the track (sample indices)
        int[] lo = new int[sizes.size()];
        int[] hi = new int[sizes.size()];
        java.util.Arrays.fill(lo, Integer.MAX_VALUE);
        java.util.Arrays.fill(hi, Integer.MIN_VALUE);
        for (int node = zone.nextSetBit(0); node >= 0; node = zone.nextSetBit(node + 1)) {
            int r = regionOf[node];
            int i = sampleOf.getOrDefault(node, 0);
            lo[r] = Math.min(lo[r], i);
            hi[r] = Math.max(hi[r], i);
        }
        for (int node = zone.nextSetBit(0); node >= 0; node = zone.nextSetBit(node + 1)) {
            int r = regionOf[node];
            boolean endToEnd = hi[r] <= lo[main] + 2 || lo[r] >= hi[main] - 2;
            // or a scrap in front of the gates (Atlantic: barriers between the stairwell pillars,
            // the main platform running on past them along both track edges)
            boolean scrap = sizes.get(r) * 10 <= sizes.get(main);
            if (r != main && (endToEnd || scrap) && !java.util.Collections.disjoint(touched.get(r), touched.get(main))) {
                zone.clear(node);
            }
        }
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
                // a landing with MTR lift doors is entered THROUGH them — never from whatever
                // stands within reach behind the shaft wall (187 St: a lift beside the gate line
                // joined the street to the paid side)
                boolean doors = false;
                for (int node : nodes) {
                    doors |= tag[node] == CellInfo.TAG_LIFT_DOOR;
                }
                if (doors) {
                    for (int i = nodes.size() - 1; i >= 0; i--) {
                        if (tag[nodes.get(i)] != CellInfo.TAG_LIFT_DOOR) {
                            nodes.remove(i);
                            costs.remove(i);
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    continue;
                }
                liftCount++;
                liftPos.add(new double[]{floor[0] + 0.5, floor[1], floor[2] + 0.5});
                liftIdOf.add(lift.id());
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

    // ========================================================== fare control

    private boolean isGate(int node) {
        return node < n && (tag[node] == CellInfo.TAG_FARE || tag[node] == CellInfo.TAG_EMERGENCY);
    }

    /**
     * Groups the gate nodes into lines of fare control: gates within
     * {@value #FARE_GROUP_RADIUS} blocks on the same floor. A group of emergency doors
     * with no fare lane among them is not fare control (it stays a last-resort door).
     */
    private void findFareGroups() {
        fareGroup = new int[n];
        java.util.Arrays.fill(fareGroup, -1);
        IntList queue = new IntList();
        for (int start = 0; start < n; start++) {
            if (fareGroup[start] != -1 || !isGate(start)) {
                continue;
            }
            final int id = fareGroups.size();
            queue.size = 0;
            queue.add(start);
            fareGroup[start] = id;
            boolean anyFare = false;
            for (int head = 0; head < queue.size; head++) {
                int node = queue.data[head];
                anyFare |= tag[node] == CellInfo.TAG_FARE;
                float y = h[node];
                columnsAround(nx(node), nz(node), FARE_GROUP_RADIUS, (col, d) -> {
                    for (int other = colStart[col]; other < colStart[col + 1]; other++) {
                        if (fareGroup[other] == -1 && isGate(other) && Math.abs(h[other] - y) <= 1.5f) {
                            fareGroup[other] = id;
                            queue.add(other);
                        }
                    }
                });
            }
            int[] members = queue.toArray();
            if (!anyFare) {
                for (int m : members) {
                    fareGroup[m] = -2; // a lone emergency door: seen, but no group
                }
                continue;
            }
            fareGroups.add(new FareGroup(members));
        }
        for (int node = 0; node < n; node++) {
            if (fareGroup[node] == -2) {
                fareGroup[node] = -1;
            }
        }
    }

    /** Everything a rider reaches from the platforms without passing a gate. */
    private BitSet paidArea(List<BitSet> zones) {
        BitSet out = new BitSet(n + liftCount);
        IntList stack = new IntList();
        for (BitSet zone : zones) {
            for (int node = zone.nextSetBit(0); node >= 0; node = zone.nextSetBit(node + 1)) {
                if (!out.get(node)) {
                    out.set(node);
                    stack.add(node);
                }
            }
        }
        while (stack.size > 0) {
            int node = stack.data[--stack.size];
            neighbours(node, false, (to, cost, kind) -> {
                if (!out.get(to) && !isGate(to)) {
                    out.set(to);
                    stack.add(to);
                }
            });
        }
        return out;
    }

    /** The standing places right in front of / behind a group: paid side yes/no, and the unpaid ones. */
    private void sides(FareGroup group) {
        BitSet free = new BitSet(n);
        final boolean[] paidSide = {false};
        for (int node : group.members) {
            neighbours(node, false, (to, cost, kind) -> {
                if (isGate(to)) {
                    return;
                }
                if (paid.get(to)) {
                    paidSide[0] = true;
                } else if (to < n) {
                    free.set(to);
                }
            });
        }
        group.paidSide = paidSide[0];
        group.freeSide = free.stream().toArray();
    }

    /** The member nearest the group's centroid (where its anchor stands). */
    private int centreNode(int[] members) {
        double cx = 0, cz = 0;
        for (int node : members) {
            cx += nx(node);
            cz += nz(node);
        }
        cx /= members.length;
        cz /= members.length;
        int best = members[0];
        double bestD = Double.MAX_VALUE;
        for (int node : members) {
            double d = Math.hypot(nx(node) - cx, nz(node) - cz);
            if (d < bestD) {
                bestD = d;
                best = node;
            }
        }
        return best;
    }

    private void fareWarnings(LayoutResult result, List<String> exitIds, List<int[]> exitSources, List<int[]> openings,
                              List<BitSet> zones) {
        // platforms BEHIND fare control (their nearest gate is about as close as the nearest
        // way in from the street, or closer) that riders can still reach from the street
        // without passing a gate. A platform the street reaches long before any gate is
        // simply a free platform (a tram stop beside a gated subway: Morgan) — not reported.
        boolean leaks = false;
        if (gated) {
            List<String> from = new ArrayList<>();
            IntList seeds = new IntList();
            for (int e = 0; e < exitIds.size(); e++) {
                boolean leaking = false;
                for (int node : exitSources.get(e)) {
                    seeds.add(node);
                    leaking |= paid.get(node);
                }
                if (leaking) {
                    from.add(exitIds.get(e).replace("exit:", "Exit "));
                }
            }
            for (int[] opening : openings) {
                int node = opening[0];
                seeds.add(node);
                if (paid.get(node)) {
                    from.add(String.format(Locale.ROOT, "%d, %d, %d",
                            (int) Math.floor(nx(node)), (int) Math.floor(h[node]), (int) Math.floor(nz(node))));
                }
            }
            BitSet street = new BitSet(n);
            for (int i = 0; i < seeds.size; i++) {
                street.set(seeds.data[i]);
            }
            List<String> bypassed = new ArrayList<>();
            for (int p = 0; p < zones.size(); p++) {
                float[] d = gateVersusStreet(zones.get(p), street);
                if (d[1] < MAX_COST && d[0] <= d[1] * 1.5f + 5f) {
                    bypassed.add(label(in.platforms.get(p).name(), in.platforms.get(p).id()));
                }
            }
            if (!bypassed.isEmpty()) {
                leaks = true;
                String which = bypassed.size() <= 4 ? String.join(", ", bypassed)
                        : String.join(", ", bypassed.subList(0, 3)) + " and " + (bypassed.size() - 3) + " more";
                result.warnings.add((bypassed.size() == 1 ? "Platform " : "Platforms ") + which
                        + ": riders can also reach " + (bypassed.size() == 1 ? "it" : "them")
                        + " from the street without passing fare control"
                        + (from.isEmpty() ? "" : " (from " + String.join("; ", from.subList(0, Math.min(3, from.size())))
                        + (from.size() > 3 ? "; …" : "") + ")"));
            }
        }
        for (FareGroup group : fareGroups) {
            if (group.anchorId == null) {
                continue;
            }
            int at = centreNode(group.members);
            String where = String.format(Locale.ROOT, "Fare control at %d, %d, %d",
                    (int) Math.floor(nx(at)), (int) Math.floor(h[at]), (int) Math.floor(nz(at)));
            if (!group.paidSide) {
                result.warnings.add(where + ": no platform behind it");
            } else if (group.freeSide.length == 0 && !leaks) {
                result.warnings.add(where + ": nothing in front of it to walk in from (walled off?)");
            }
        }
    }

    /**
     * Gate-free walking metres from a platform to its nearest fare gate and to the nearest
     * street source ({@code MAX_COST} when there is none): {gate, street}.
     */
    private float[] gateVersusStreet(BitSet zone, BitSet street) {
        final float[] best = {MAX_COST, MAX_COST};
        if (zone.isEmpty()) {
            return best;
        }
        generation++;
        heapSize = 0;
        for (int node = zone.nextSetBit(0); node >= 0; node = zone.nextSetBit(node + 1)) {
            relax(node, 0f, -1);
        }
        while (heapSize > 0) {
            float cost = heapKey[0];
            int node = heapNode[0];
            pop();
            if (cost > dist[node]) {
                continue;
            }
            if (cost > MAX_COST || cost > Math.max(best[0], best[1]) && best[0] < MAX_COST && best[1] < MAX_COST) {
                break;
            }
            if (node < n && street.get(node)) {
                best[1] = Math.min(best[1], cost);
            }
            neighbours(node, false, (to, edgeCost, kind) -> {
                if (isGate(to)) {
                    best[0] = Math.min(best[0], cost + edgeCost);
                } else {
                    relax(to, cost + edgeCost, node);
                }
            });
        }
        return best;
    }

    /**
     * Per street-side anchor: which platforms it reaches, which without steps, and
     * whether that takes a lift — so a station can say "Exit B has a lift, Exit A is
     * stairs only". Per fare-control anchor: the anchors whose walks pass it.
     */
    private void summariseAccess(LayoutResult result, List<BitSet> zones) {
        int boardable = 0;
        for (BitSet zone : zones) {
            boardable += zone.isEmpty() ? 0 : 1;
        }
        for (LayoutResult.Anchor anchor : result.anchors) {
            String kind = anchor.kind();
            LayoutResult.Access acc = result.access.get(anchor.id());
            if (kind.equals("fare") && (acc == null || !acc.entrance)) {
                continue;
            }
            if (!kind.equals("exit") && !kind.equals("opening") && !kind.equals("fare")) {
                continue;
            }
            if (acc == null) {
                acc = new LayoutResult.Access();
                result.access.put(anchor.id(), acc);
            }
            for (LayoutResult.Link link : result.links) {
                if (!link.from.equals(anchor.id()) || !link.to.startsWith("platform:")) {
                    continue;
                }
                long platform = Long.parseLong(link.to.substring("platform:".length()));
                acc.reaches.add(platform);
                LayoutResult.Link way = link.stepFree ? link : link.stepFreeAlt;
                if (way != null) {
                    acc.stepFreeTo.add(platform);
                    acc.lift |= way.legs.stream().anyMatch(g -> g.kind().equals("lift"));
                }
            }
            if (!acc.reaches.isEmpty()) {
                acc.stepFree = acc.stepFreeTo.size() >= boardable ? "all" : acc.stepFreeTo.isEmpty() ? "none" : "some";
            }
        }
        for (LayoutResult.Link link : result.links) {
            noteGates(result, link.from, link);
            if (link.stepFreeAlt != null) {
                noteGates(result, link.from, link.stepFreeAlt);
            }
        }
    }

    private static void noteGates(LayoutResult result, String from, LayoutResult.Link link) {
        for (LayoutResult.Leg leg : link.legs) {
            if (leg.at() == null || leg.at().equals(from)) {
                continue;
            }
            LayoutResult.Access acc = result.access.get(leg.at());
            if (acc != null && !acc.usedBy.contains(from)) {
                acc.usedBy.add(from);
            }
        }
    }

    // ============================================================= geometry

    /** Within this many blocks of a scanned walk, ground outside the MTR area still counts as the station. */
    static final double GEOMETRY_NEAR_WALK = 6;

    /**
     * The station drawn as simple shapes for the web dispatch station page (a clean schematic,
     * Thomas 2026-10-01): every standing place that belongs to the station, merged into flat
     * rectangles per kind and height — platform (per platform), paid, free, stairs, escalator,
     * fare (gate lanes) — plus lift shafts, the track and street level. Absolute world
     * coordinates. "Belongs to the station": reachable from a platform, and not the street
     * (open to the sky at street level), and either inside the MTR area or near a scanned walk.
     * Kept OUT of the main layout JSON (Map+ embeds that for every station).
     */
    private com.google.gson.JsonObject geometry(LayoutResult result, List<BitSet> zones, List<String> platformIds) {
        // reachable from the platforms (any way, gates included)
        List<Integer> sources = new ArrayList<>();
        for (BitSet zone : zones) {
            zone.stream().forEach(sources::add);
        }
        BitSet reach = new BitSet(n);
        if (!sources.isEmpty()) {
            int[] src = toIntArray(sources);
            search(src, new float[src.length], false, null);
            for (int node = 0; node < n; node++) {
                if (stamp[node] == generation && dist[node] < MAX_COST) {
                    reach.set(node);
                }
            }
        }
        // near a scanned walk (exits and walkways outside the MTR area)
        BitSet nearWalk = new BitSet(qxN * qzN);
        for (LayoutResult.Link link : result.links) {
            markNear(nearWalk, link.path);
            if (link.stepFreeAlt != null) {
                markNear(nearWalk, link.stepFreeAlt.path);
            }
        }
        // kind per node
        int[] platformOf = new int[n];
        java.util.Arrays.fill(platformOf, -1);
        for (int p = 0; p < zones.size(); p++) {
            final int index = p;
            zones.get(p).stream().forEach(node -> platformOf[node] = index);
        }
        Map<String, Map<Float, BitSet>> layers = new java.util.LinkedHashMap<>();
        for (int node = 0; node < n; node++) {
            if (!reach.get(node) || (flags[node] & F_TRACK) != 0) {
                continue;
            }
            byte f = flags[node];
            boolean inside = (f & F_INSIDE) != 0;
            boolean near = nearWalk.get(colOf[node]);
            boolean streetLike = (f & F_OUTDOOR) != 0 && !Double.isNaN(streetLevel)
                    && Math.abs(h[node] - streetLevel) <= STREET_BAND;
            String kind;
            if (platformOf[node] >= 0) {
                kind = platformIds.get(platformOf[node]);
            } else if (isGate(node)) {
                String anchor = gateAnchor(node);
                kind = anchor != null ? anchor : "gate";
            } else if (streetLike || !(inside || near)) {
                continue;
            } else if (tag[node] == CellInfo.TAG_ESCALATOR) {
                kind = "escalator";
            } else if (isStairNode(node)) {
                kind = "stairs";
            } else {
                kind = paid != null && paid.get(node) ? "paid" : "free";
            }
            layers.computeIfAbsent(kind, k -> new java.util.TreeMap<>())
                    .computeIfAbsent(h[node], k -> new BitSet(qxN * qzN)).set(colOf[node]);
        }
        com.google.gson.JsonObject geo = new com.google.gson.JsonObject();
        if (!Double.isNaN(streetLevel)) {
            geo.addProperty("street", round2(streetLevel));
        }
        com.google.gson.JsonArray floors = new com.google.gson.JsonArray();
        int rects = 0;
        for (Map.Entry<String, Map<Float, BitSet>> layer : layers.entrySet()) {
            for (Map.Entry<Float, BitSet> level : layer.getValue().entrySet()) {
                for (int[] r : mergeRects(level.getValue())) {
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray(6);
                    a.add(layer.getKey());
                    a.add(round2(in.minX + r[0] * 0.5));
                    a.add(round2(in.minZ + r[1] * 0.5));
                    a.add(round2(in.minX + r[2] * 0.5));
                    a.add(round2(in.minZ + r[3] * 0.5));
                    a.add(round2(level.getKey()));
                    floors.add(a);
                    rects++;
                }
            }
        }
        geo.add("floors", floors);
        // lift shafts: one per MTR lift, with the landing heights it serves here
        Map<Long, com.google.gson.JsonObject> lifts = new java.util.LinkedHashMap<>();
        for (int k = 0; k < liftCount; k++) {
            double[] pos = liftPos.get(k);
            com.google.gson.JsonObject lift = lifts.computeIfAbsent(liftIdOf.get(k), id -> {
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("id", Long.toString(id));
                o.addProperty("x", round2(pos[0]));
                o.addProperty("z", round2(pos[2]));
                o.add("floors", new com.google.gson.JsonArray());
                return o;
            });
            lift.getAsJsonArray("floors").add(round2(pos[1]));
        }
        com.google.gson.JsonArray liftsJson = new com.google.gson.JsonArray();
        lifts.values().forEach(liftsJson::add);
        geo.add("lifts", liftsJson);
        // the track: rail samples near the station's platforms, as polylines
        com.google.gson.JsonArray track = new com.google.gson.JsonArray();
        List<double[]> line = new ArrayList<>();
        double[] prev = null;
        for (double[] t : in.track) {
            if (!nearAnyPlatform(t)) {
                prev = null;
                flushLine(track, line);
                continue;
            }
            if (prev != null && Math.hypot(t[0] - prev[0], t[2] - prev[2]) > 1.0) {
                flushLine(track, line);
            }
            line.add(t);
            prev = t;
        }
        flushLine(track, line);
        geo.add("track", track);
        geo.addProperty("rects", rects);
        return geo;
    }

    /** A stair node: one of its 4 neighbours is a step (rise above a ramp) away. */
    private boolean isStairNode(int node) {
        int col = colOf[node];
        int qx = col % qxN;
        int qz = col / qxN;
        for (int d = 0; d < 4; d++) {
            int other = floorNear(qx + DIRS[d][0], qz + DIRS[d][1], h[node]);
            if (other >= 0 && Math.abs(h[other] - h[node]) > RAMP) {
                return true;
            }
        }
        return false;
    }

    private void markNear(BitSet near, List<double[]> path) {
        if (path == null) {
            return;
        }
        for (double[] p : path) {
            columnsAround(p[0], p[2], GEOMETRY_NEAR_WALK, (col, d) -> near.set(col));
        }
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
            for (double t = 2; t < len; t += 2) {
                double x = a[0] + (b[0] - a[0]) * t / len;
                double z = a[2] + (b[2] - a[2]) * t / len;
                columnsAround(x, z, GEOMETRY_NEAR_WALK, (col, d) -> near.set(col));
            }
        }
    }

    private boolean nearAnyPlatform(double[] t) {
        for (LayoutInput.Platform platform : in.platforms) {
            for (double[] s : platform.samples()) {
                if (Math.abs(s[0] - t[0]) < 40 && Math.abs(s[2] - t[2]) < 40 && Math.abs(s[1] - t[1]) < 12) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void flushLine(com.google.gson.JsonArray track, List<double[]> line) {
        if (line.size() >= 2) {
            com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
            for (double[] p : simplify(line, 0.2)) {
                com.google.gson.JsonArray v = new com.google.gson.JsonArray(3);
                v.add(round2(p[0]));
                v.add(round2(p[1]));
                v.add(round2(p[2]));
                pts.add(v);
            }
            track.add(pts);
        }
        line.clear();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Greedy rectangles over the quadrant grid: {qx0, qz0, qx1, qz1} with x1/z1 exclusive edges. */
    private List<int[]> mergeRects(BitSet cells) {
        List<int[]> out = new ArrayList<>();
        BitSet left = (BitSet) cells.clone();
        for (int i = left.nextSetBit(0); i >= 0; i = left.nextSetBit(i + 1)) {
            int qz = i / qxN;
            int qx = i % qxN;
            int x1 = qx;
            while (x1 + 1 < qxN && left.get(qz * qxN + x1 + 1)) {
                x1++;
            }
            int z1 = qz;
            grow:
            while (z1 + 1 < qzN) {
                for (int x = qx; x <= x1; x++) {
                    if (!left.get((z1 + 1) * qxN + x)) {
                        break grow;
                    }
                }
                z1++;
            }
            for (int z = qz; z <= z1; z++) {
                left.clear(z * qxN + qx, z * qxN + x1 + 1);
            }
            out.add(new int[]{qx, qz, x1 + 1, z1 + 1});
        }
        return out;
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
            // inside the station area, open ground BEHIND the gates is a paid plaza, not the street:
            // walk on through the gates (Atlantic, Morgan: at-grade stations with open-air fare control)
            boolean inside = node < n && (flags[node] & F_INSIDE) != 0;
            if (node < n && isStreet(node) && (!inside || (cost >= OPENING_MIN_WALK && !(gated && paid.get(node))))) {
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
                addLeg(legs, "fare", 0, 0, gateAnchor(b));
            } else if (b < n && tag[b] == CellInfo.TAG_EMERGENCY && (a >= n || tag[a] != CellInfo.TAG_EMERGENCY)) {
                addLeg(legs, "emergency", 0, 0, gateAnchor(b));
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
            link.legs.add(new LayoutResult.Leg(kind, m, dy, seconds, (String) leg[3]));
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

    /** The fare-control anchor a gate node belongs to, or null. */
    private String gateAnchor(int node) {
        int group = fareGroup == null || node >= n ? -1 : fareGroup[node];
        return group < 0 ? null : fareGroups.get(group).anchorId;
    }

    private static void addLeg(List<Object[]> legs, String kind, double meters, double dy) {
        addLeg(legs, kind, meters, dy, null);
    }

    private static void addLeg(List<Object[]> legs, String kind, double meters, double dy, String at) {
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
        legs.add(new Object[]{kind, meters, dy, at});
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
