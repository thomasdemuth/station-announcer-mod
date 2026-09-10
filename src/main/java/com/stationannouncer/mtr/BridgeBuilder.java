package com.stationannouncer.mtr;

import com.stationannouncer.block.ElDeckBlock;
import com.stationannouncer.block.ElGirderBlock;
import com.stationannouncer.block.ElRun;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Bridge Creator's build engine, written against two small interfaces so
 * the very same code runs on the server (a real world, recording an undo
 * list) and in the settings screen (a scratch map that becomes the 3D
 * preview): a {@link Path} is anything with a length and a position along
 * it, a {@link Sink} is anything that can be asked whether a cell is free
 * and told to fill it.
 *
 * <p>How a cross-section follows curves and hills: the reference track is
 * walked in quarter-block steps; at each step the local horizontal tangent
 * and perpendicular are taken, every companion track is located sideways
 * (its nearest point, projected onto that perpendicular), and the section
 * is laid out in <em>lateral cells</em> — cell 0 under the reference rail,
 * negative to one side, positive to the other — each mapped to the world
 * block containing {@code centre + perpendicular × k}. Heights are taken
 * from the rail itself (the nearest track's rail height per cell), so a
 * grade simply steps the deck block by block. Because the deck extends a
 * fixed overhang beyond the OUTERMOST tracks found at each step, a two- or
 * three-track line gets a correspondingly wider bridge with no extra
 * settings.
 *
 * <p>Passes: (1) sample the reference and protect the rail cells above
 * every track, (2) deck + girders + railing, (3) piers (cap, legs that pass
 * straight through anything this build placed and stop at real ground,
 * footings), (4) arches between piers. Every cell is claimed by its first
 * writer, so nothing built here is overwritten by a later pass.
 */
public final class BridgeBuilder {
    /** Something the section can follow: a rail, or a straight line in the preview. */
    public interface Path {
        double length();

        Vector at(double distance);
    }

    /** Where the blocks go. */
    public interface Sink {
        boolean replaceable(BlockPos pos);

        void set(BlockPos pos, BlockState state);

        int bottomY();
    }

    public record Result(int blocks, int piers, int tracks, List<BridgeSpec.Slot> badMaterials) {
    }

    /** A straight path between two points — the preview's rails. */
    public record LinePath(Vector a, Vector b) implements Path {
        @Override
        public double length() {
            return a.distanceTo(b);
        }

        @Override
        public Vector at(double distance) {
            double t = length() <= 0 ? 0 : Math.max(0, Math.min(1, distance / length()));
            return new Vector(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
        }
    }

    static final double STEP = 0.25;
    private static final double LONGITUDINAL_TOLERANCE = 1.5;
    private static final double HEIGHT_TOLERANCE = 6;
    private static final double PARALLEL_COS = 0.8;
    private static final double SAME_TRACK_OFFSET = 0.75;
    /** Rows above the rail kept free over every track. */
    private static final int CLEARANCE = 4;

    private BridgeBuilder() {
    }

    // ------------------------------------------------------------ companions

    /**
     * Which of the candidate paths run beside the reference: parallel
     * (tangents within ~37°), within {@code reach} blocks sideways, roughly
     * level with it, along at least half of the shorter of the two — and not
     * simply on top of it (an overlapping rail is the same track).
     */
    public static List<Path> detectCompanions(Path reference, List<Path> candidates, double reach) {
        Polyline ref = new Polyline(reference, 1.0);
        List<Path> found = new ArrayList<>();
        for (Path candidate : candidates) {
            Polyline line = new Polyline(candidate, 1.0);
            if (line.n < 2) {
                continue;
            }
            int covered = 0;
            int beside = 0;
            for (int i = 0; i < ref.n; i++) {
                double tx = ref.tx[i], tz = ref.tz[i];
                double px = -tz, pz = tx;
                int j = line.nearest(ref.x[i], ref.z[i]);
                double dx = line.x[j] - ref.x[i];
                double dz = line.z[j] - ref.z[i];
                double lon = dx * tx + dz * tz;
                double lat = dx * px + dz * pz;
                double cos = tx * line.tx[j] + tz * line.tz[j];
                if (Math.abs(lon) <= LONGITUDINAL_TOLERANCE && Math.abs(lat) <= reach
                        && Math.abs(line.y[j] - ref.y[i]) <= HEIGHT_TOLERANCE && Math.abs(cos) >= PARALLEL_COS) {
                    covered++;
                    if (Math.abs(lat) >= SAME_TRACK_OFFSET) {
                        beside++;
                    }
                }
            }
            int need = Math.max(3, Math.min(ref.n, line.n) / 2);
            if (covered >= need && beside >= covered * 0.8) {
                found.add(candidate);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ build

    public static Result build(Sink sink, Path reference, List<Path> companions, BridgeSpec spec) {
        spec.clamp();
        Materials materials = new Materials(spec);
        List<Sample> samples = sample(reference, companions);
        Build b = new Build(sink, spec, materials, samples);
        b.protectTracks();
        b.deckGirdersRailing();
        int piers = b.piers();
        b.arches();
        int tracks = 1;
        boolean[] present = new boolean[companions.size()];
        for (Sample s : samples) {
            for (Track t : s.tracks) {
                if (t.index > 0) {
                    present[t.index - 1] = true;
                }
            }
        }
        for (boolean p : present) {
            if (p) {
                tracks++;
            }
        }
        return new Result(b.placed, piers, tracks, materials.missing());
    }

    // --------------------------------------------------------------- sampling

    /** One track's presence at a sample: its lateral cell and rail height. */
    private record Track(int index, int cell, int railY) {
    }

    private static final class Sample {
        final double distance;
        final Vector centre;
        final double tx, tz;   // unit tangent (horizontal)
        final double px, pz;   // unit perpendicular (horizontal)
        final List<Track> tracks = new ArrayList<>(3);
        int left, right;       // deck extent in cells (tracks ± overhang), set by Build

        Sample(double distance, Vector centre, double tx, double tz) {
            this.distance = distance;
            this.centre = centre;
            this.tx = tx;
            this.tz = tz;
            this.px = -tz;
            this.pz = tx;
        }

        BlockPos pos(int cell, int y) {
            return new BlockPos((int) Math.floor(centre.x + px * cell), y, (int) Math.floor(centre.z + pz * cell));
        }

        boolean isTrackCell(int cell) {
            for (Track t : tracks) {
                if (t.cell == cell) {
                    return true;
                }
            }
            return false;
        }

        /** Rail height of the track nearest to a lateral cell. */
        int railYNear(int cell) {
            int best = tracks.get(0).railY;
            int bestDistance = Integer.MAX_VALUE;
            for (Track t : tracks) {
                int d = Math.abs(t.cell - cell);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = t.railY;
                }
            }
            return best;
        }
    }

    private static List<Sample> sample(Path reference, List<Path> companions) {
        List<Polyline> lines = new ArrayList<>();
        for (Path c : companions) {
            lines.add(new Polyline(c, 1.0));
        }
        List<Sample> samples = new ArrayList<>();
        double length = reference.length();
        for (double d = 0; d < length; d += STEP) {
            Vector c = reference.at(d);
            double t2 = Math.min(d + 0.5, length);
            Vector a = reference.at(Math.max(0, t2 - 0.5));
            Vector b = reference.at(t2);
            double tx = b.x - a.x, tz = b.z - a.z;
            double len = Math.sqrt(tx * tx + tz * tz);
            if (len < 1e-6) {
                continue;
            }
            Sample s = new Sample(d, c, tx / len, tz / len);
            s.tracks.add(new Track(0, 0, (int) Math.floor(c.y)));
            for (int i = 0; i < lines.size(); i++) {
                Polyline line = lines.get(i);
                if (line.n == 0) {
                    continue;
                }
                int j = line.nearest(c.x, c.z);
                double dx = line.x[j] - c.x;
                double dz = line.z[j] - c.z;
                double lon = dx * s.tx + dz * s.tz;
                double lat = dx * s.px + dz * s.pz;
                if (Math.abs(lon) <= LONGITUDINAL_TOLERANCE && Math.abs(line.y[j] - c.y) <= HEIGHT_TOLERANCE) {
                    s.tracks.add(new Track(i + 1, (int) Math.round(lat), (int) Math.floor(line.y[j])));
                }
            }
            samples.add(s);
        }
        return samples;
    }

    /** A path sampled into arrays: positions and unit tangents every {@code step}. */
    private static final class Polyline {
        final double[] x, y, z, tx, tz;
        final int n;

        Polyline(Path path, double step) {
            double length = path.length();
            int count = Math.max(2, (int) Math.floor(length / step) + 1);
            x = new double[count];
            y = new double[count];
            z = new double[count];
            tx = new double[count];
            tz = new double[count];
            for (int i = 0; i < count; i++) {
                double d = Math.min(length, i * step);
                Vector v = path.at(d);
                x[i] = v.x;
                y[i] = v.y;
                z[i] = v.z;
            }
            for (int i = 0; i < count; i++) {
                int a = Math.max(0, i - 1), b = Math.min(count - 1, i + 1);
                double dx = x[b] - x[a], dz = z[b] - z[a];
                double len = Math.sqrt(dx * dx + dz * dz);
                tx[i] = len < 1e-6 ? 1 : dx / len;
                tz[i] = len < 1e-6 ? 0 : dz / len;
            }
            n = count;
        }

        int nearest(double px, double pz) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double dx = x[i] - px, dz = z[i] - pz;
                double d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }
    }

    // -------------------------------------------------------------- materials

    /** Parsed block states per slot, remembering which enabled slots failed to parse. */
    private static final class Materials {
        private final Map<BridgeSpec.Slot, BlockState> states = new EnumMap<>(BridgeSpec.Slot.class);
        private final List<BridgeSpec.Slot> missing = new ArrayList<>();

        Materials(BridgeSpec spec) {
            for (BridgeSpec.Slot slot : BridgeSpec.Slot.values()) {
                BlockState state = BridgeSpec.parseMaterial(spec.material(slot));
                if (state != null) {
                    states.put(slot, state);
                } else if (enabled(spec, slot)) {
                    missing.add(slot);
                }
            }
            if (!states.containsKey(BridgeSpec.Slot.EDGE) && spec.edgeMaterial.isBlank()) {
                missing.remove(BridgeSpec.Slot.EDGE); // blank edge = same as deck, not an error
            }
        }

        private static boolean enabled(BridgeSpec spec, BridgeSpec.Slot slot) {
            return switch (slot) {
                case DECK -> spec.deck;
                case EDGE -> spec.deck && !spec.edgeMaterial.isBlank();
                case GIRDER -> spec.girderStyle != BridgeSpec.GirderStyle.NONE;
                case RAILING -> spec.railing;
                case PIER -> spec.pierStyle != BridgeSpec.PierStyle.NONE;
                case CAP -> spec.pierStyle != BridgeSpec.PierStyle.NONE && spec.pierCap;
                case FOOTING -> spec.pierStyle != BridgeSpec.PierStyle.NONE && spec.footing;
                case ARCH -> spec.archStyle != BridgeSpec.ArchStyle.NONE;
            };
        }

        BlockState get(BridgeSpec.Slot slot) {
            return states.get(slot);
        }

        List<BridgeSpec.Slot> missing() {
            return missing;
        }
    }

    /**
     * Turns axis-aware blocks to follow the track: logs, pillars, our el
     * decks and girders. {@code along} is true for parts running with the
     * rail (deck), false for parts running across it (girders, caps).
     */
    static BlockState orient(BlockState state, Sample s, boolean along) {
        double dx = along ? s.tx : s.px;
        double dz = along ? s.tz : s.pz;
        if (state.contains(ElDeckBlock.AXIS)) {
            return state.with(ElDeckBlock.AXIS, ElRun.fromDirection(dx, dz));
        }
        if (state.contains(ElGirderBlock.AXIS)) {
            return state.with(ElGirderBlock.AXIS, ElRun.fromDirection(dx, dz));
        }
        Direction.Axis axis = Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
        if (state.contains(Properties.AXIS)) {
            return state.with(Properties.AXIS, axis);
        }
        if (state.contains(Properties.HORIZONTAL_AXIS)) {
            return state.with(Properties.HORIZONTAL_AXIS, axis);
        }
        return state;
    }

    // ------------------------------------------------------------------ passes

    private static final class Build {
        final Sink sink;
        final BridgeSpec spec;
        final Materials materials;
        final List<Sample> samples;
        final Set<BlockPos> claimed = new HashSet<>();
        final Set<BlockPos> protectedCells = new HashSet<>();
        int placed;

        Build(Sink sink, BridgeSpec spec, Materials materials, List<Sample> samples) {
            this.sink = sink;
            this.spec = spec;
            this.materials = materials;
            this.samples = samples;
            for (Sample s : samples) {
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                for (Track t : s.tracks) {
                    min = Math.min(min, t.cell);
                    max = Math.max(max, t.cell);
                }
                s.left = min - spec.overhang;
                s.right = max + spec.overhang;
            }
        }

        boolean place(BlockPos pos, BlockState state) {
            if (state == null || pos.getY() < sink.bottomY() || protectedCells.contains(pos) || claimed.contains(pos)) {
                return false;
            }
            if (!sink.replaceable(pos)) {
                return false;
            }
            sink.set(pos, state);
            claimed.add(pos);
            placed++;
            return true;
        }

        /** Top deck row at a lateral cell: one below the nearest track's rail. */
        int deckY(Sample s, int cell) {
            return s.railYNear(cell) - 1;
        }

        /** Lowest deck row (the deck may be several blocks thick). */
        int deckBottom(Sample s, int cell) {
            return deckY(s, cell) - (spec.deck ? spec.deckThickness - 1 : 0);
        }

        int girderDepth() {
            return spec.girderStyle == BridgeSpec.GirderStyle.NONE ? 0 : spec.girderDepth;
        }

        /** Nothing may be built in the rail cell and the clearance rows above it, on any track. */
        void protectTracks() {
            for (Sample s : samples) {
                for (Track t : s.tracks) {
                    for (int y = t.railY; y <= t.railY + CLEARANCE; y++) {
                        protectedCells.add(s.pos(t.cell, y));
                    }
                }
            }
        }

        boolean girderAt(Sample s, int cell) {
            return switch (spec.girderStyle) {
                case NONE -> false;
                case EDGES -> cell == s.left || cell == s.right;
                case TRACKS -> s.isTrackCell(cell);
                case BOTH -> cell == s.left || cell == s.right || s.isTrackCell(cell);
                case FULL -> true;
            };
        }

        void deckGirdersRailing() {
            BlockState deck = materials.get(BridgeSpec.Slot.DECK);
            BlockState edge = materials.get(BridgeSpec.Slot.EDGE);
            BlockState girder = materials.get(BridgeSpec.Slot.GIRDER);
            BlockState railing = materials.get(BridgeSpec.Slot.RAILING);
            for (Sample s : samples) {
                for (int k = s.left; k <= s.right; k++) {
                    int top = deckY(s, k);
                    if (spec.deck && deck != null) {
                        boolean isEdge = edge != null && (k - s.left < spec.edgeWidth || s.right - k < spec.edgeWidth);
                        BlockState state = orient(isEdge ? edge : deck, s, true);
                        for (int t = 0; t < spec.deckThickness; t++) {
                            place(s.pos(k, top - t), state);
                        }
                    }
                    if (girder != null && girderAt(s, k)) {
                        int bottom = deckBottom(s, k);
                        BlockState state = orient(girder, s, spec.girderStyle == BridgeSpec.GirderStyle.FULL);
                        for (int d = 1; d <= spec.girderDepth; d++) {
                            place(s.pos(k, bottom - d), state);
                        }
                    }
                }
                if (spec.railing && railing != null) {
                    int[] cells = {s.left + spec.railingInset, s.right - spec.railingInset};
                    for (int k : cells) {
                        if (k < s.left || k > s.right || s.isTrackCell(k)) {
                            continue;
                        }
                        int top = deckY(s, k);
                        for (int h = 1; h <= spec.railingHeight; h++) {
                            place(s.pos(k, top + h), orient(railing, s, true));
                        }
                    }
                }
            }
        }

        /** Lateral cells the pier legs stand in at a sample. */
        List<Integer> legCells(Sample s) {
            List<Integer> cells = new ArrayList<>();
            int inner = s.left + spec.pierInset;
            int outer = s.right - spec.pierInset;
            switch (spec.pierStyle) {
                case NONE -> {
                }
                case EDGES -> {
                    if (inner >= outer) {
                        cells.add((s.left + s.right) / 2);
                    } else {
                        cells.add(inner);
                        cells.add(outer);
                    }
                }
                case CENTRE -> {
                    int sum = s.left + s.right;
                    cells.add(Math.floorDiv(sum, 2));
                    if (Math.floorMod(sum, 2) != 0) {
                        cells.add(Math.floorDiv(sum, 2) + 1);
                    }
                }
                case TRACKS -> {
                    for (Track t : s.tracks) {
                        cells.add(t.cell);
                    }
                }
                case TWIN -> {
                    for (Track t : s.tracks) {
                        cells.add(t.cell - 1);
                        cells.add(t.cell + 1);
                    }
                }
                case WALL -> {
                    for (int k = Math.min(inner, outer); k <= Math.max(inner, outer); k++) {
                        cells.add(k);
                    }
                }
            }
            return cells;
        }

        /** Distances along the reference at which pier sets stand. */
        List<Double> pierDistances() {
            List<Double> out = new ArrayList<>();
            double length = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).distance + STEP;
            for (double d = spec.pierSpacing / 2.0; d < length; d += spec.pierSpacing) {
                out.add(d);
            }
            return out;
        }

        Sample sampleAt(double distance) {
            int idx = (int) Math.round(distance / STEP);
            if (idx < 0 || idx >= samples.size()) {
                return null;
            }
            return samples.get(idx);
        }

        int piers() {
            if (spec.pierStyle == BridgeSpec.PierStyle.NONE) {
                return 0;
            }
            BlockState pier = materials.get(BridgeSpec.Slot.PIER);
            BlockState cap = spec.pierCap ? materials.get(BridgeSpec.Slot.CAP) : null;
            BlockState footing = spec.footing ? materials.get(BridgeSpec.Slot.FOOTING) : null;
            if (pier == null) {
                return 0;
            }
            int built = 0;
            for (double d : pierDistances()) {
                boolean any = false;
                for (int m = 0; m < spec.pierThickness; m++) {
                    Sample s = sampleAt(d + m);
                    if (s == null) {
                        continue;
                    }
                    if (cap != null) {
                        for (int k = s.left; k <= s.right; k++) {
                            int capY = deckBottom(s, k) - girderDepth() - 1;
                            place(s.pos(k, capY), orient(cap, s, false));
                        }
                    }
                    for (int k : legCells(s)) {
                        int legs = leg(s, k, pier, footing);
                        any |= legs > 0;
                    }
                }
                if (any) {
                    built++;
                }
            }
            return built;
        }

        /**
         * One leg: from just under the deck straight down, passing through
         * whatever this build already placed (girders, cap, arch), filling
         * every free block, stopping at the first real block — the ground,
         * the riverbed, an existing structure.
         */
        int leg(Sample s, int cell, BlockState pier, BlockState footing) {
            int start = deckBottom(s, cell) - 1;
            int count = 0;
            int lowest = Integer.MAX_VALUE;
            BlockPos lastPos = null;
            for (int y = start; y >= sink.bottomY(); y--) {
                BlockPos pos = s.pos(cell, y);
                if (claimed.contains(pos)) {
                    continue;
                }
                if (protectedCells.contains(pos) || !sink.replaceable(pos)) {
                    break;
                }
                place(pos, pier);
                count++;
                lowest = y;
                lastPos = pos;
            }
            if (footing != null && lastPos != null && count > 0) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dz != 0) {
                            place(lastPos.add(dx, 0, dz), footing);
                        }
                    }
                }
            }
            return count;
        }

        void arches() {
            if (spec.archStyle == BridgeSpec.ArchStyle.NONE) {
                return;
            }
            BlockState arch = materials.get(BridgeSpec.Slot.ARCH);
            if (arch == null) {
                return;
            }
            List<Double> piers = pierDistances();
            if (piers.size() < 2) {
                return;
            }
            double first = piers.get(0);
            double last = piers.get(piers.size() - 1);
            double half = spec.pierSpacing / 2.0;
            for (Sample s : samples) {
                double d = s.distance;
                if (d < first || d > last) {
                    continue;
                }
                int span = (int) Math.floor((d - first) / spec.pierSpacing);
                double mid = first + span * spec.pierSpacing + half;
                double u = Math.max(-1, Math.min(1, (d - mid) / half));
                int depth = (int) Math.round(spec.archRise * (1 - Math.sqrt(Math.max(0, 1 - u * u))));
                List<Integer> cells = new ArrayList<>();
                if (spec.archStyle == BridgeSpec.ArchStyle.FILLED) {
                    for (int k = s.left; k <= s.right; k++) {
                        cells.add(k);
                    }
                } else {
                    cells.add(s.left + spec.pierInset);
                    if (s.right - spec.pierInset != s.left + spec.pierInset) {
                        cells.add(s.right - spec.pierInset);
                    }
                }
                for (int k : cells) {
                    int top = deckBottom(s, k) - 1;
                    for (int y = top; y >= top - depth; y--) {
                        place(s.pos(k, y), orient(arch, s, true));
                    }
                }
            }
        }
    }
}
