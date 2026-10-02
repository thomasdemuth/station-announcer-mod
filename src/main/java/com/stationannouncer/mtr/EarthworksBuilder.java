package com.stationannouncer.mtr;

import com.stationannouncer.mtr.EarthworksSpec.Layer;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.GlowLichenBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.AttachedStemBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The earthworks engine behind the Embankment Creator, Trench Creator and
 * ROW Clearer. Like {@link BridgeBuilder} it walks the reference track in
 * quarter-block steps (sharing its sampler, so curves, grades and parallel
 * tracks behave the same) and lays out each cross-section in lateral cells;
 * unlike the bridge it has to READ the terrain, so it runs against a
 * {@link View} and produces a {@link Plan} — every changed cell and its new
 * state — instead of writing directly. The server applies a plan over
 * several ticks with an undo record; the settings screen draws one over
 * generated terrain. Later passes read earlier ones through the plan.
 *
 * <p><b>Embankment:</b> the track bed (tracks ± shoulder, one below the
 * rail) is filled down to the ground — top layer, then core — then each
 * side steps down the slope profile until it meets the ground. <b>Trench:</b>
 * everything from rail height to the surface is cut over the bed and the
 * ditches, then each side steps UP the profile until it reaches the surface,
 * the face under the cut clad in the face mix (which also seals water).
 * Both: a slope that would be wider than the wall trigger becomes a
 * retaining wall; benches insert flat steps every N blocks of height; blend
 * adds a little value noise so long earthworks don't look ruled.
 * <b>Clearer:</b> trees (whole, from any log in the corridor), plants, snow
 * and optionally terrain come out of a corridor along the tracks.</p>
 *
 * <p>Only natural things are ever removed or overwritten when "protect
 * builds" is on: terrain, fluids, plants and trees — a tree counts only when
 * its logs carry naturally grown (non-persistent) leaves or it is a huge
 * mushroom, so log cabins and hedges survive. Blocks with block entities and
 * MTR / station blocks are never touched.</p>
 */
public final class EarthworksBuilder {
    /** Read-only access to the world (or the preview's generated terrain). */
    public interface View {
        BlockState get(BlockPos pos);

        int bottomY();

        int topY();

        /** Top solid-or-fluid cell of a column, ignoring leaves (MOTION_BLOCKING_NO_LEAVES − 1). */
        int surface(int x, int z);
    }

    public static final int MAX_CHANGES = 1_000_000;
    private static final int CLEARANCE = 4;
    private static final int MAX_REACH = 160;
    private static final int TREE_LIMIT = 4096;
    private static final int NO_GROUND = Integer.MIN_VALUE;

    /** Planned changes in order, plus what they replace (read through the plan). */
    public static final class Plan {
        public final LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
        final Map<BlockPos, Layer> layers = new HashMap<>();
        final View view;

        Plan(View view) {
            this.view = view;
        }

        public BlockState state(BlockPos pos) {
            BlockState s = changes.get(pos);
            return s != null ? s : view.get(pos);
        }

        public int size() {
            return changes.size();
        }
    }

    public record Result(Plan plan, int skipped, int keptBuilds, int trees, List<String> badMaterials, boolean tooBig) {
    }

    private EarthworksBuilder() {
    }

    // ================================================================= entry

    public static Result build(View view, BridgeBuilder.Path reference, List<BridgeBuilder.Path> companions,
                               EarthworksSpec spec) {
        spec.clamp();
        Job job = new Job(view, spec, BridgeBuilder.sample(reference, companions));
        try {
            switch (spec.kind) {
                case EMBANKMENT -> job.embankment();
                case TRENCH -> job.trench();
                case CLEARER -> job.clear();
            }
            job.settleFallingBlocks();
        } catch (TooBig e) {
            return new Result(job.plan, job.skipped, job.keptBuilds, job.trees, job.bad, true);
        }
        return new Result(job.plan, job.skipped, job.keptBuilds, job.trees, job.bad, false);
    }

    private static final class TooBig extends RuntimeException {
        TooBig() {
            super(null, null, false, false);
        }
    }

    // ============================================================ predicates

    /** Never touched: block entities, MTR / station / MSD blocks, bedrock. */
    static boolean untouchable(BlockState s) {
        if (s.hasBlockEntity() || s.isOf(Blocks.BEDROCK) || s.isOf(Blocks.BARRIER)) {
            return true;
        }
        String ns = Registries.BLOCK.getId(s.getBlock()).getNamespace();
        return ns.equals("mtr") || ns.equals("station_announcer") || ns.equals("msd");
    }

    /** Natural ground: what world generation lays down (the carvers' own list, ores, a few extras). */
    static boolean naturalTerrain(BlockState s) {
        return s.isIn(BlockTags.OVERWORLD_CARVER_REPLACEABLES) || s.isIn(BlockTags.NETHER_CARVER_REPLACEABLES)
                || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND)
                || s.isIn(BlockTags.TERRACOTTA) || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES)
                || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.GOLD_ORES) || s.isIn(BlockTags.REDSTONE_ORES)
                || s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES)
                || s.isIn(BlockTags.ICE) || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.CLAY) || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.DRIPSTONE_BLOCK)
                || s.isOf(Blocks.POINTED_DRIPSTONE) || s.isOf(Blocks.SMOOTH_BASALT) || s.isOf(Blocks.MAGMA_BLOCK)
                || s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.SEAGRASS) || s.isOf(Blocks.TALL_SEAGRASS)
                || s.isOf(Blocks.KELP) || s.isOf(Blocks.KELP_PLANT) || s.isOf(Blocks.SCULK)
                || s.getBlock() instanceof FluidBlock;
    }

    static boolean fluid(BlockState s) {
        return s.getBlock() instanceof FluidBlock;
    }

    /** Wood and mushroom parts that make up a tree's frame. */
    static boolean trunk(BlockState s) {
        return s.isIn(BlockTags.LOGS) || s.isOf(Blocks.MUSHROOM_STEM) || s.isOf(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.isOf(Blocks.RED_MUSHROOM_BLOCK) || s.isOf(Blocks.MANGROVE_ROOTS);
    }

    static boolean naturalLeaves(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && s.contains(Properties.PERSISTENT) && !s.get(Properties.PERSISTENT);
    }

    /** Things that hang off a tree and go with it. */
    static boolean treeDecor(BlockState s) {
        return s.getBlock() instanceof VineBlock || s.getBlock() instanceof CocoaBlock || s.isOf(Blocks.BEE_NEST)
                || s.isOf(Blocks.MANGROVE_PROPAGULE) || s.isOf(Blocks.SNOW);
    }

    /** Ground plants (not crops — those are someone's farm). */
    static boolean plant(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock || b instanceof StemBlock || b instanceof AttachedStemBlock || b instanceof NetherWartBlock) {
            return false;
        }
        return b instanceof PlantBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock
                || b instanceof BambooBlock || b instanceof BambooShootBlock || b instanceof VineBlock
                || b instanceof GlowLichenBlock;
    }

    static boolean snowOrIce(BlockState s) {
        return s.isOf(Blocks.SNOW) || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.ICE) || s.isOf(Blocks.FROSTED_ICE);
    }

    /** Free for a fill: air, fluids, plants, snow layers — anything vanilla lets you place into. */
    static boolean fillable(BlockState s) {
        return s.isAir() || s.isReplaceable();
    }

    // ================================================================== job

    private static final class Job {
        final View view;
        final EarthworksSpec spec;
        final List<BridgeBuilder.Sample> samples;
        final Plan plan;
        final Map<Layer, EarthworksPalette> palettes = new EnumMap<>(Layer.class);
        final Set<BlockPos> protectedCells = new HashSet<>();
        final List<String> bad = new ArrayList<>();
        final int[] profile;
        int skipped, keptBuilds, trees;

        Job(View view, EarthworksSpec spec, List<BridgeBuilder.Sample> samples) {
            this.view = view;
            this.spec = spec;
            this.samples = samples;
            this.plan = new Plan(view);
            for (Layer layer : Layer.values()) {
                if (layer.usedBy(spec.kind)) {
                    EarthworksPalette p = EarthworksPalette.of(spec.palette(layer));
                    palettes.put(layer, p);
                    bad.addAll(p.bad());
                }
            }
            for (BridgeBuilder.Sample s : samples) {
                for (BridgeBuilder.Track t : s.tracks) {
                    for (int y = t.railY(); y <= t.railY() + CLEARANCE; y++) {
                        protectedCells.add(s.pos(t.cell(), y));
                    }
                }
            }
            this.profile = profile(spec, MAX_REACH + 16);
        }

        // --------------------------------------------------------- writing

        BlockState state(BlockPos pos) {
            return plan.state(pos);
        }

        boolean inWorld(BlockPos pos) {
            return pos.getY() >= view.bottomY() && pos.getY() < view.topY();
        }

        void put(BlockPos pos, BlockState state, Layer layer) {
            BlockPos key = pos.toImmutable();
            plan.changes.put(key, state);
            if (layer != null) {
                plan.layers.put(key, layer);
            } else {
                plan.layers.remove(key);
            }
            if (plan.changes.size() > MAX_CHANGES) {
                throw new TooBig();
            }
        }

        /** Place a layer block into a free cell (fills). */
        boolean fill(BlockPos pos, Layer layer) {
            EarthworksPalette p = palettes.get(layer);
            if (p == null || p.isEmpty() || !inWorld(pos) || protectedCells.contains(pos) || !fillable(state(pos))) {
                return false;
            }
            put(pos, p.pick(pos, layer.ordinal()), layer);
            return true;
        }

        /** Overwrite a cell with a layer block (cladding, walls, beds): natural or free cells only. */
        boolean clad(BlockPos pos, Layer layer) {
            EarthworksPalette p = palettes.get(layer);
            if (p == null || p.isEmpty() || !inWorld(pos) || protectedCells.contains(pos)) {
                return false;
            }
            BlockState s = state(pos);
            if (untouchable(s)) {
                return false;
            }
            if (!fillable(s) && !naturalTerrain(s) && !(trunk(s) || s.getBlock() instanceof LeavesBlock)) {
                if (spec.protectBuilds) {
                    keptBuilds++;
                    return false;
                }
            }
            put(pos, p.pick(pos, layer.ordinal()), layer);
            return true;
        }

        /** Take a cell out (cuts, clearing). Trees met on the way come out whole. */
        void remove(BlockPos pos, boolean felling) {
            if (!inWorld(pos)) {
                return;
            }
            BlockState s = state(pos);
            if (s.isAir() || untouchable(s)) {
                return;
            }
            if (felling && trunk(s)) {
                tree(pos);
                if (state(pos).isAir()) {
                    return;
                }
            }
            if (spec.protectBuilds && !naturalTerrain(s) && !plant(s) && !snowOrIce(s)
                    && !trunk(s) && !(s.getBlock() instanceof LeavesBlock) && !treeDecor(s)) {
                keptBuilds++;
                return;
            }
            air(pos, s);
        }

        void air(BlockPos pos, BlockState s) {
            put(pos, Blocks.AIR.getDefaultState(), null);
            // a double plant comes out as a pair
            if (s.getBlock() instanceof TallPlantBlock && s.contains(Properties.DOUBLE_BLOCK_HALF)) {
                BlockPos other = s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.up() : pos.down();
                if (state(other).isOf(s.getBlock())) {
                    put(other, Blocks.AIR.getDefaultState(), null);
                }
            }
        }

        // ------------------------------------------------------- columns

        /**
         * Highest non-fillable y at or below {@code fromY}, at most {@code limit}
         * down; {@link #NO_GROUND} over a void (a cliff, a ravine, the edge of a
         * floating build) — never fill down into thin air to the limit.
         */
        int groundBelow(int x, int z, int fromY, int limit) {
            BlockPos.Mutable m = new BlockPos.Mutable(x, fromY, z);
            int floor = Math.max(view.bottomY(), fromY - limit);
            for (int y = fromY; y >= floor; y--) {
                m.setY(y);
                if (!fillable(state(m))) {
                    return y;
                }
            }
            return NO_GROUND;
        }

        /** The ground surface of a column: the heightmap, stepped down past trunks, plants and snow. */
        int surfaceTop(int x, int z, int floorY) {
            int y = view.surface(x, z);
            BlockPos.Mutable m = new BlockPos.Mutable(x, y, z);
            while (y > floorY) {
                m.setY(y);
                BlockState s = state(m);
                if (s.isAir() || trunk(s) || plant(s) || treeDecor(s) || s.getBlock() instanceof LeavesBlock) {
                    y--;
                } else {
                    break;
                }
            }
            return y;
        }

        int noise(BlockPos column, int d) {
            if (spec.blend <= 0 || d < 2) {
                return 0;
            }
            double ramp = Math.min(1.0, (d - 1) / 3.0);
            return (int) Math.round(valueNoise(column.getX(), column.getZ()) * spec.blend * ramp);
        }

        int widthFor(int height) {
            for (int d = 1; d < profile.length; d++) {
                if (profile[d] >= height) {
                    return d;
                }
            }
            return profile.length;
        }

        int rise(int d) {
            return profile[Math.min(profile.length - 1, Math.max(0, d))];
        }

        // ------------------------------------------------------ embankment

        void embankment() {
            Set<Long> done = new HashSet<>();
            List<BridgeBuilder.Sample> active = new ArrayList<>();
            for (BridgeBuilder.Sample s : samples) {
                int[] span = span(s, spec.shoulder);
                int refTop = s.railYNear(0) - 1;
                BlockPos c0 = s.pos(0, refTop);
                int g0 = groundBelow(c0.getX(), c0.getZ(), refTop, spec.maxHeight + 2);
                if (g0 == NO_GROUND || refTop - g0 > spec.maxHeight) {
                    skipped++;
                    continue;
                }
                active.add(s);
                for (int k = span[0]; k <= span[1]; k++) {
                    int top = s.railYNear(k) - 1;
                    BlockPos col = s.pos(k, top);
                    if (!done.add(column(col))) {
                        continue;
                    }
                    int ground = groundBelow(col.getX(), col.getZ(), top, spec.maxHeight + 2);
                    if (ground == NO_GROUND) {
                        continue;
                    }
                    for (int y = top; y > ground; y--) {
                        fill(new BlockPos(col.getX(), y, col.getZ()), top - y < spec.topThickness ? Layer.TOP : Layer.CORE);
                    }
                }
            }
            for (BridgeBuilder.Sample s : active) {
                int[] span = span(s, spec.shoulder);
                for (int side : new int[]{-1, 1}) {
                    int edge = side < 0 ? span[0] : span[1];
                    int edgeTop = s.railYNear(edge) - 1;
                    BlockPos first = s.pos(edge + side, edgeTop);
                    if (done.contains(column(first))) {
                        continue;
                    }
                    int firstGround = groundBelow(first.getX(), first.getZ(), edgeTop, spec.maxHeight + 2);
                    if (firstGround == NO_GROUND) {
                        continue;
                    }
                    int height = edgeTop - firstGround;
                    if (height <= 0) {
                        continue;
                    }
                    if (wallFor(height)) {
                        for (int t = 1; t <= spec.wallThickness; t++) {
                            BlockPos col = s.pos(edge + side * t, edgeTop);
                            if (!done.add(column(col))) {
                                continue;
                            }
                            int ground = groundBelow(col.getX(), col.getZ(), edgeTop, spec.maxHeight + 2);
                            if (ground == NO_GROUND) {
                                continue;
                            }
                            for (int y = edgeTop + spec.wallParapet; y > ground; y--) {
                                fill(new BlockPos(col.getX(), y, col.getZ()), Layer.WALL);
                            }
                        }
                        continue;
                    }
                    for (int d = 1; d < MAX_REACH; d++) {
                        BlockPos col = s.pos(edge + side * d, edgeTop);
                        int colTop = Math.min(edgeTop, edgeTop - rise(d) + noise(col, d));
                        int ground = groundBelow(col.getX(), col.getZ(), colTop, spec.maxHeight + 2);
                        if (ground == NO_GROUND || ground >= colTop) {
                            break;
                        }
                        if (!done.add(column(col))) {
                            continue;
                        }
                        int exposedBelow = edgeTop - rise(d + spec.surfaceThickness);
                        boolean toe = !palettes.get(Layer.TOE).isEmpty();
                        for (int y = colTop; y > ground; y--) {
                            Layer layer;
                            if (toe && y == ground + 1) {
                                layer = Layer.TOE;
                            } else if (colTop - y < spec.surfaceThickness || y > exposedBelow) {
                                layer = Layer.SLOPE;
                            } else {
                                layer = Layer.CORE;
                            }
                            fill(new BlockPos(col.getX(), y, col.getZ()), layer);
                        }
                    }
                }
            }
        }

        boolean wallFor(int height) {
            return spec.wallMode == EarthworksSpec.WallMode.ALWAYS
                    || (spec.wallMode == EarthworksSpec.WallMode.AUTO && widthFor(height) > spec.wallTrigger);
        }

        // ---------------------------------------------------------- trench

        void trench() {
            Set<Long> done = new HashSet<>();
            List<BridgeBuilder.Sample> active = new ArrayList<>();
            int dw = spec.ditchWidth;
            for (BridgeBuilder.Sample s : samples) {
                int[] span = span(s, spec.shoulder);
                int railY = s.railYNear(0);
                BlockPos c0 = s.pos(0, railY);
                if (surfaceTop(c0.getX(), c0.getZ(), railY) - railY >= spec.maxDepth) {
                    skipped++;
                    continue;
                }
                active.add(s);
                for (int k = span[0] - dw; k <= span[1] + dw; k++) {
                    int rail = s.railYNear(k);
                    BlockPos col = s.pos(k, rail);
                    if (!done.add(column(col))) {
                        continue;
                    }
                    boolean ditch = k < span[0] || k > span[1];
                    int bottom = ditch ? rail - spec.ditchDepth : rail;
                    cutColumn(col.getX(), col.getZ(), bottom, rail);
                    // re-surface the bed only where there is ground (a stretch through a dip stays as it is)
                    BlockPos bed = new BlockPos(col.getX(), ditch ? rail - 1 - spec.ditchDepth : rail - 1, col.getZ());
                    if (!fillable(state(bed))) {
                        clad(bed, ditch ? Layer.DITCH : Layer.FLOOR);
                    }
                }
            }
            for (BridgeBuilder.Sample s : active) {
                int[] span = span(s, spec.shoulder);
                for (int side : new int[]{-1, 1}) {
                    int edge = (side < 0 ? span[0] : span[1]) + side * dw;
                    int base = s.railYNear(edge);
                    BlockPos first = s.pos(edge + side, base);
                    if (done.contains(column(first))) {
                        continue;
                    }
                    int height = surfaceTop(first.getX(), first.getZ(), base - 1) - base + 1;
                    if (height <= 0) {
                        continue;
                    }
                    int prevBottom = dw > 0 ? base - spec.ditchDepth : base;
                    if (wallFor(height)) {
                        for (int t = 1; t <= spec.wallThickness; t++) {
                            BlockPos col = s.pos(edge + side * t, base);
                            if (!done.add(column(col))) {
                                continue;
                            }
                            int top = Math.min(surfaceTop(col.getX(), col.getZ(), base - 1), base + spec.maxDepth);
                            for (int y = prevBottom - 1; y <= top; y++) {
                                clad(new BlockPos(col.getX(), y, col.getZ()), Layer.WALL);
                            }
                            for (int y = top + 1; y <= top + spec.wallParapet; y++) {
                                fill(new BlockPos(col.getX(), y, col.getZ()), Layer.WALL);
                            }
                            clearAbove(col.getX(), top, col.getZ());
                        }
                        continue;
                    }
                    for (int d = 1; d < MAX_REACH; d++) {
                        BlockPos col = s.pos(edge + side * d, base);
                        int top = Math.min(surfaceTop(col.getX(), col.getZ(), base - 1), base + spec.maxDepth);
                        int cutBottom = Math.max(base, base + rise(d) + noise(col, d));
                        boolean fresh = done.add(column(col));
                        if (cutBottom > top) {
                            // the slope reached the surface: clad the step this column shows the cut
                            if (fresh) {
                                for (int y = prevBottom; y <= top; y++) {
                                    clad(new BlockPos(col.getX(), y, col.getZ()), Layer.FACE);
                                }
                            }
                            break;
                        }
                        if (!fresh) {
                            prevBottom = cutBottom;
                            continue;
                        }
                        cutColumn(col.getX(), col.getZ(), cutBottom, base);
                        int cladFrom = Math.min(prevBottom, cutBottom - spec.surfaceThickness);
                        for (int y = cladFrom; y < cutBottom; y++) {
                            clad(new BlockPos(col.getX(), y, col.getZ()), Layer.FACE);
                        }
                        prevBottom = cutBottom;
                    }
                }
            }
        }

        /** Cut a column from {@code bottom} to the surface (capped at max depth over the rail). */
        void cutColumn(int x, int z, int bottom, int railY) {
            int top = Math.min(surfaceTop(x, z, bottom - 1), railY + spec.maxDepth);
            for (int y = bottom; y <= top; y++) {
                remove(new BlockPos(x, y, z), spec.fellTrees);
            }
            clearAbove(x, top, z);
        }

        /** Whatever stood on a removed surface: plants and snow go, trees go whole. */
        void clearAbove(int x, int top, int z) {
            for (int y = top + 1; y <= top + 2; y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = state(p);
                if (spec.fellTrees && trunk(s)) {
                    tree(p);
                } else if (plant(s) || s.isOf(Blocks.SNOW)) {
                    air(p, s);
                }
            }
        }

        // ---------------------------------------------------------- clearer

        void clear() {
            Set<Long> done = new HashSet<>();
            for (BridgeBuilder.Sample s : samples) {
                int[] span = span(s, spec.corridor);
                int[] tracks = span(s, 0);
                for (int k = span[0]; k <= span[1]; k++) {
                    int rail = s.railYNear(k);
                    BlockPos col = s.pos(k, rail);
                    if (!done.add(column(col))) {
                        continue;
                    }
                    boolean terrainColumn = spec.terrain && k >= tracks[0] - spec.terrainWidth
                            && k <= tracks[1] + spec.terrainWidth;
                    for (int y = rail - spec.clearBelow; y <= rail + spec.clearHeight; y++) {
                        BlockPos p = new BlockPos(col.getX(), y, col.getZ());
                        if (!inWorld(p)) {
                            continue;
                        }
                        BlockState st = state(p);
                        if (st.isAir() || untouchable(st)) {
                            continue;
                        }
                        if (spec.trees && trunk(st)) {
                            tree(p);
                        } else if (spec.trees && naturalLeaves(st)) {
                            air(p, st);
                        } else if (spec.plants && plant(st)) {
                            air(p, st);
                        } else if (spec.snow && snowOrIce(st)) {
                            air(p, st);
                        } else if (terrainColumn && y >= rail && y < rail + spec.terrainHeight && naturalTerrain(st)) {
                            air(p, st);
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ trees

        /**
         * A whole tree from one of its logs: the trunk and branches (logs,
         * 26-connected), then the leaves those logs feed — a leaf goes only
         * if its own distance-to-nearest-log is no shorter than its distance
         * to THIS tree, so a neighbouring tree keeps its crown — then vines,
         * cocoa, nests and propagules hanging off them. Logs without any
         * natural leaves are a building, not a tree, and stay.
         */
        void tree(BlockPos seed) {
            Set<BlockPos> logs = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed.toImmutable());
            logs.add(seed.toImmutable());
            boolean natural = false;
            while (!queue.isEmpty() && logs.size() < TREE_LIMIT) {
                BlockPos p = queue.poll();
                BlockState ps = state(p);
                if (ps.isOf(Blocks.MUSHROOM_STEM) || ps.isOf(Blocks.RED_MUSHROOM_BLOCK) || ps.isOf(Blocks.BROWN_MUSHROOM_BLOCK)) {
                    natural = true;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            BlockPos q = p.add(dx, dy, dz);
                            if (!inWorld(q)) {
                                continue;
                            }
                            BlockState qs = state(q);
                            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1 && naturalLeaves(qs)) {
                                natural = true;
                            }
                            if (trunk(qs) && !untouchable(qs) && logs.add(q)) {
                                queue.add(q);
                            }
                        }
                    }
                }
            }
            if (!natural) {
                return;
            }
            trees++;
            Set<BlockPos> removed = new HashSet<>(logs);
            for (BlockPos p : logs) {
                put(p, Blocks.AIR.getDefaultState(), null);
            }
            // leaves this tree feeds
            Map<BlockPos, Integer> depth = new HashMap<>();
            ArrayDeque<BlockPos> leafQueue = new ArrayDeque<>();
            for (BlockPos p : logs) {
                for (Direction d : Direction.values()) {
                    BlockPos q = p.offset(d);
                    if (!depth.containsKey(q) && naturalLeaves(view.get(q))) {
                        depth.put(q, 1);
                        leafQueue.add(q);
                    }
                }
            }
            while (!leafQueue.isEmpty()) {
                BlockPos p = leafQueue.poll();
                int dd = depth.get(p);
                BlockState ls = view.get(p);
                int own = ls.contains(LeavesBlock.DISTANCE) ? ls.get(LeavesBlock.DISTANCE) : 1;
                if (own < dd) {
                    continue; // nearer to another tree: its leaf
                }
                if (!state(p).isAir()) {
                    put(p, Blocks.AIR.getDefaultState(), null);
                    removed.add(p);
                }
                if (dd >= 7) {
                    continue;
                }
                for (Direction d : Direction.values()) {
                    BlockPos q = p.offset(d);
                    if (!depth.containsKey(q) && naturalLeaves(view.get(q))) {
                        depth.put(q, dd + 1);
                        leafQueue.add(q);
                    }
                }
            }
            // hangers-on
            ArrayDeque<BlockPos> decor = new ArrayDeque<>();
            for (BlockPos p : removed) {
                for (Direction d : Direction.values()) {
                    decor.add(p.offset(d));
                }
            }
            int guard = 0;
            while (!decor.isEmpty() && guard++ < TREE_LIMIT) {
                BlockPos p = decor.poll();
                BlockState ds = state(p);
                if (treeDecor(ds) && !untouchable(ds)) {
                    put(p, Blocks.AIR.getDefaultState(), null);
                    if (ds.getBlock() instanceof VineBlock) {
                        decor.add(p.down());
                    }
                }
            }
        }

        // ------------------------------------------------------- finishing

        /** Sand / gravel over a hole would fall at the first update: use the layer's solid block there. */
        void settleFallingBlocks() {
            List<Map.Entry<BlockPos, BlockState>> fix = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> e : plan.changes.entrySet()) {
                if (!(e.getValue().getBlock() instanceof FallingBlock)) {
                    continue;
                }
                BlockState below = state(e.getKey().down());
                if (below.isAir() || FallingBlock.canFallThrough(below)) {
                    Layer layer = plan.layers.get(e.getKey());
                    EarthworksPalette p = layer == null ? null : palettes.get(layer);
                    BlockState solid = p == null ? null : p.solid();
                    fix.add(Map.entry(e.getKey(), solid != null ? solid : Blocks.COBBLESTONE.getDefaultState()));
                }
            }
            for (Map.Entry<BlockPos, BlockState> e : fix) {
                plan.changes.put(e.getKey(), e.getValue());
            }
        }

        // ---------------------------------------------------------- helpers

        /** Lateral cells covered by the tracks at a sample, widened by {@code margin} each side. */
        int[] span(BridgeBuilder.Sample s, int margin) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (BridgeBuilder.Track t : s.tracks) {
                min = Math.min(min, t.cell());
                max = Math.max(max, t.cell());
            }
            return new int[]{min - margin, max + margin};
        }

        long column(BlockPos pos) {
            return BlockPos.asLong(pos.getX(), 0, pos.getZ());
        }
    }

    // ============================================================= profile

    /**
     * Height reached (blocks) at each lateral step d off the bed edge: the
     * slope ratio, with a flat bench of {@code benchWidth} every
     * {@code benchEvery} blocks of height when benches are on.
     */
    public static int[] profile(EarthworksSpec spec, int length) {
        int[] out = new int[length + 1];
        double run = spec.run();
        double u = 0;
        int rise = 0;
        int benchLeft = 0;
        int nextBench = spec.benchEvery;
        for (int d = 1; d <= length; d++) {
            if (benchLeft > 0) {
                benchLeft--;
                out[d] = rise;
                continue;
            }
            u += 1;
            rise = (int) Math.floor(u / run + 1e-9);
            if (spec.benches && rise >= nextBench) {
                rise = nextBench;
                nextBench += spec.benchEvery;
                benchLeft = spec.benchWidth;
                u = rise * run;
            }
            out[d] = rise;
        }
        return out;
    }

    /** Smooth value noise in [-1, 1], features ~6 blocks across. */
    static double valueNoise(int x, int z) {
        double fx = x / 6.0, fz = z / 6.0;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private static double lattice(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) / (double) (1L << 53)) * 2 - 1;
    }
}
