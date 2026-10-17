package com.stationannouncer.atmosphere;

import com.stationannouncer.StationAnnouncer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MultifaceGrowthBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans a weathering pass over a box: where grime collects, where litter
 * ends up, which lights have failed. Pure planning — reads the world, returns
 * the block changes and light conditions; {@link Weathering} applies them.
 *
 * <p>Placement follows where dirt really goes: corners and wall bases get
 * dust (open floor much less), the trackbed and platform edge get oil and
 * litter, open concourse floor gets gum, litter gathers near benches,
 * turnstiles and stairs, cobwebs sit in ceiling corners, rust bleeds from
 * metal, mineral bloom grows on tile and masonry, a ceiling leak gets a
 * puddle under it. Everything is clustered by a value noise so it comes in
 * patches, not salt.
 *
 * <p>Family blocks are looked up by id so this does not depend on the
 * family classes; a missing block just skips its layer.
 */
final class WeatheringPlanner {
    static final int MAX_CELLS = 1_500_000;

    record Result(Map<BlockPos, BlockState> blocks, Long2IntOpenHashMap lights, int decals, int litter,
                  int removed, int cans) {
    }

    // decal kinds and the faces each may sit on ("down" = on the floor)
    private enum Kind {
        DUST("grime_dust", ALL),
        SOOT("grime_soot", ALL),
        STAIN("grime_water_stain", ALL),
        RUST("grime_rust_streak", WALLS_AND_CEILING),
        EFFLORESCENCE("grime_efflorescence", WALLS_AND_CEILING),
        PUDDLE("grime_puddle", FLOOR),
        GUM("grime_gum", FLOOR),
        OIL("grime_oil", FLOOR),
        COBWEB("cobweb_corner", ALL);

        final String id;
        final Set<Direction> faces;

        Kind(String id, Set<Direction> faces) {
            this.id = id;
            this.faces = faces;
        }
    }

    private static final Set<Direction> ALL = EnumSet.allOf(Direction.class);
    private static final Set<Direction> FLOOR = EnumSet.of(Direction.DOWN);
    private static final Set<Direction> WALLS_AND_CEILING = EnumSet.of(Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST);

    /** Blocks the clean pass removes (and the planner treats as air). */
    static final String[] REMOVABLE = {"grime_dust", "grime_soot", "grime_water_stain", "grime_rust_streak",
            "grime_efflorescence", "grime_puddle", "grime_gum", "grime_oil", "cobweb_corner",
            "litter", "trash_bags", "cardboard_pile", "swept_pile", "newspaper_stack"};

    private final ServerWorld world;
    private final BlockPos min;
    private final BlockPos max;
    private final WeatherSpec spec;
    private final long seed;
    private final Map<Kind, Block> kinds = new java.util.EnumMap<>(Kind.class);
    private final Set<Block> removable = new java.util.HashSet<>();
    private final LongOpenHashSet busyColumns = new LongOpenHashSet();
    private final LongOpenHashSet leakColumns = new LongOpenHashSet();

    private WeatheringPlanner(ServerWorld world, BlockPos min, BlockPos max, WeatherSpec spec, long seed) {
        this.world = world;
        this.min = min;
        this.max = max;
        this.spec = spec;
        this.seed = seed;
        for (Kind k : Kind.values()) {
            Block b = block(k.id);
            if (b instanceof MultifaceGrowthBlock) {
                kinds.put(k, b);
            }
        }
        for (String id : REMOVABLE) {
            Block b = block(id);
            if (b != null) {
                removable.add(b);
            }
        }
    }

    static Block block(String path) {
        Block b = Registries.BLOCK.get(new Identifier(StationAnnouncer.MOD_ID, path));
        return b == Blocks.AIR ? null : b;
    }

    static boolean isRemovable(BlockState state) {
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (!id.getNamespace().equals(StationAnnouncer.MOD_ID)) {
            return false;
        }
        for (String r : REMOVABLE) {
            if (r.equals(id.getPath())) {
                return true;
            }
        }
        return false;
    }

    static Result plan(ServerWorld world, BlockPos min, BlockPos max, WeatherSpec spec, long seed) {
        return new WeatheringPlanner(world, min, max, spec, seed).run();
    }

    // ------------------------------------------------------------------ run

    private Result run() {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        Long2IntOpenHashMap lights = new Long2IntOpenHashMap();
        int removed = 0;
        int cans = 0;
        List<BlockPos> glowing = new ArrayList<>();
        List<BlockPos> trashCans = new ArrayList<>();
        Block trashCan = block("trash_can");

        // pass 1: clean, find lights / cans / busy spots
        for (BlockPos p : BlockPos.iterate(min, max)) {
            BlockState s = world.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            if (spec.clean && removable.contains(s.getBlock())) {
                out.put(p.toImmutable(), Blocks.AIR.getDefaultState());
                removed++;
                continue;
            }
            if (LightConditions.canGlow(s)) {
                glowing.add(p.toImmutable());
            }
            if (trashCan != null && s.isOf(trashCan)) {
                trashCans.add(p.toImmutable());
            }
            String path = Registries.BLOCK.getId(s.getBlock()).getPath();
            if (path.contains("bench") || path.contains("turnstile") || path.contains("stair")
                    || path.contains("fare") || path.contains("trash_can") || path.contains("pids")
                    || path.contains("ticket") || path.contains("escalator")) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        busyColumns.add(column(p.getX() + dx, p.getZ() + dz));
                    }
                }
            }
        }

        // pass 2: ceilings first (leaks decide where puddles go)
        int decals = 0;
        int litter = 0;
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(min, max)) {
            BlockPos c = p.toImmutable();
            if (free(c, out)) {
                cells.add(c);
            }
        }
        for (BlockPos c : cells) {
            if (solidFace(c, Direction.UP) && !solidFace(c, Direction.DOWN)) {
                BlockState placed = ceilingCell(c);
                if (placed != null) {
                    out.put(c, placed);
                    decals++;
                }
            }
        }
        // pass 3: floors and walls
        for (BlockPos c : cells) {
            if (out.containsKey(c) && !out.get(c).isAir()) {
                continue;
            }
            BlockState placed;
            if (solidFace(c, Direction.DOWN)) {
                placed = floorCell(c);
            } else if (!solidFace(c, Direction.UP)) {
                placed = wallCell(c);
            } else {
                continue;
            }
            if (placed != null) {
                out.put(c, placed);
                if (placed.getBlock() instanceof MultifaceGrowthBlock) {
                    decals++;
                } else {
                    litter++;
                }
            }
        }

        // lights
        int faults = spec.get(WeatherSpec.Layer.LIGHTS);
        for (BlockPos p : glowing) {
            double r = rand(p, 91);
            int packed = 0;
            if (r < faults / 100.0) {
                packed = LightConditionState.pack(pickCondition(p), (int) (rand(p, 92) * 256));
            } else if (!spec.clean) {
                continue; // leave lights we did not pick alone
            }
            lights.put(p.asLong(), packed);
        }

        // trash cans fill up with neglect
        int trash = spec.get(WeatherSpec.Layer.TRASH);
        if (trash > 0) {
            int sev = spec.get(WeatherSpec.Layer.SEVERITY);
            for (BlockPos p : trashCans) {
                BlockState s = world.getBlockState(p);
                int fill = (int) Math.round(sev / 100.0 * 3 + (rand(p, 101) - 0.5) * 1.4);
                BlockState t = withInt(s, "fill", fill);
                if (t != s) {
                    out.put(p, t);
                    cans++;
                }
            }
        }
        return new Result(out, lights, decals, litter, removed, cans);
    }

    // --------------------------------------------------------------- cells

    private BlockState ceilingCell(BlockPos c) {
        int walls = walls(c).size();
        double cl = cluster(c, 0);
        boolean metal = metal(world.getBlockState(c.up()));
        int cob = spec.get(WeatherSpec.Layer.COBWEBS);
        if (walls >= 1 && roll(c, 1, cob / 100.0 * (walls >= 2 ? 0.8 : 0.45))) {
            return decal(c, Kind.COBWEB, Direction.UP, 0.6);
        }
        if (roll(c, 2, spec.get(WeatherSpec.Layer.STAINS) / 100.0 * 0.12 * cluster(c, 1))) {
            leakColumns.add(column(c.getX(), c.getZ()));
            return decal(c, Kind.STAIN, Direction.UP, 0.5);
        }
        if (metal && roll(c, 3, spec.get(WeatherSpec.Layer.RUST) / 100.0 * 0.3 * cl)) {
            return decal(c, Kind.RUST, Direction.UP, 0.3);
        }
        if (roll(c, 4, spec.get(WeatherSpec.Layer.SOOT) / 100.0 * 0.35 * cl)) {
            return decal(c, Kind.SOOT, Direction.UP, 0.25);
        }
        return null;
    }

    private BlockState floorCell(BlockPos c) {
        List<Direction> walls = walls(c);
        int w = Math.min(2, walls.size());
        boolean trackbed = trackbed(c);
        boolean edge = !trackbed && platformEdge(c);
        boolean busy = busyColumns.contains(column(c.getX(), c.getZ()));
        double cl = cluster(c, 0);

        // objects first
        double litterP = spec.get(WeatherSpec.Layer.LITTER) / 100.0 * 0.10
                * (0.6 + 0.8 * w + (trackbed ? 2.5 : 0) + (busy ? 1.2 : 0)) * cl;
        int trash = spec.get(WeatherSpec.Layer.TRASH);
        double trashP = trash / 100.0 * (walls.size() >= 2 ? 0.22 : walls.size() == 1 ? 0.035 : 0);
        if (!trackbed && roll(c, 10, trashP)) {
            BlockState s = rand(c, 11) < 0.7 ? object(c, "trash_bags", "bags", 1 + (int) (rand(c, 12) * (1 + 2 * trash / 100.0)))
                    : object(c, "cardboard_pile", "layers", 1 + (int) (rand(c, 12) * 2.5));
            if (s != null) {
                return s;
            }
        }
        if (roll(c, 13, litterP)) {
            int sev = spec.get(WeatherSpec.Layer.SEVERITY);
            int count = 1 + (int) (rand(c, 14) * (1.3 + 2.7 * sev / 100.0));
            BlockState s = object(c, "litter", "count", count);
            if (s != null) {
                return s;
            }
        }
        if (!trackbed && trash < 20 && spec.get(WeatherSpec.Layer.LITTER) > 0 && walls.size() >= 1 && roll(c, 15, 0.004)) {
            BlockState s = object(c, "swept_pile", null, 0);
            if (s != null) {
                return withBool(s, "broom", rand(c, 16) < 0.5);
            }
        }

        // decals: pick one kind for the cell
        boolean leak = leakColumns.contains(column(c.getX(), c.getZ()));
        if (roll(c, 20, leak ? 0.6 * spec.get(WeatherSpec.Layer.PUDDLES) / 100.0 + 0.2
                : spec.get(WeatherSpec.Layer.PUDDLES) / 100.0 * 0.03 * cluster(c, 1))) {
            BlockState s = decal(c, Kind.PUDDLE, Direction.DOWN, 0);
            if (s != null) {
                return s;
            }
        }
        if (roll(c, 21, spec.get(WeatherSpec.Layer.OIL) / 100.0 * (trackbed || edge ? 0.45 : 0.015) * cl)) {
            BlockState s = decal(c, Kind.OIL, Direction.DOWN, 0);
            if (s != null) {
                return s;
            }
        }
        if (walls.isEmpty() && !trackbed && roll(c, 22, spec.get(WeatherSpec.Layer.GUM) / 100.0 * 0.10 * cl * (busy ? 1.6 : 1))) {
            BlockState s = decal(c, Kind.GUM, Direction.DOWN, 0);
            if (s != null) {
                return s;
            }
        }
        double cornerWeight = walls.isEmpty() ? 0.25 : walls.size() == 1 ? 0.8 : 1.2;
        if (trackbed) {
            cornerWeight += 1.0;
        }
        if (roll(c, 23, spec.get(WeatherSpec.Layer.DUST) / 100.0 * 0.55 * cornerWeight * cl)) {
            return decal(c, Kind.DUST, Direction.DOWN, 0.75); // floor + the wall bases beside it
        }
        // tile / masonry wall bases can bloom white even without dust
        return wallDecals(c, walls);
    }

    private BlockState wallCell(BlockPos c) {
        List<Direction> walls = walls(c);
        if (walls.isEmpty()) {
            return null;
        }
        return wallDecals(c, walls);
    }

    /**
     * Wall grime only where it really collects: the lower two blocks (splash,
     * mineral bloom rising from the floor) and the band under the ceiling
     * (leaks and soot coming down). Mid-wall gets nothing but rust weeping
     * from metal — scattered blotches at eye height read as noise.
     */
    private BlockState wallDecals(BlockPos c, List<Direction> walls) {
        if (walls.isEmpty()) {
            return null;
        }
        int up = distance(c, Direction.UP);
        int down = distance(c, Direction.DOWN);
        boolean top = up <= 1;
        boolean base = down <= 2;
        Direction d = walls.get((int) (rand(c, 30) * walls.size()));
        BlockState wall = world.getBlockState(c.offset(d));
        if (metal(wall) && roll(c, 31, spec.get(WeatherSpec.Layer.RUST) / 100.0 * (top ? 0.5 : 0.15) * cluster(c, 2))) {
            return decal(c, Kind.RUST, d, 0.2);
        }
        if (!top && !base) {
            return null;
        }
        if (masonry(wall) && roll(c, 32, spec.get(WeatherSpec.Layer.EFFLORESCENCE) / 100.0 * 0.25 * cluster(c, 3))) {
            return decal(c, Kind.EFFLORESCENCE, d, 0.3);
        }
        if (top && roll(c, 33, spec.get(WeatherSpec.Layer.STAINS) / 100.0 * 0.12 * cluster(c, 1))) {
            return decal(c, Kind.STAIN, d, 0.2);
        }
        if (top && roll(c, 34, spec.get(WeatherSpec.Layer.SOOT) / 100.0 * 0.12 * cluster(c, 0))) {
            return decal(c, Kind.SOOT, d, 0.2);
        }
        return null;
    }

    /** Free cells from {@code c} to the first solid face in direction {@code d} (capped at 6). */
    private int distance(BlockPos c, Direction d) {
        BlockPos p = c;
        for (int i = 0; i < 6; i++) {
            if (solidFace(p, d)) {
                return i;
            }
            p = p.offset(d);
        }
        return 6;
    }

    // ------------------------------------------------------------- helpers

    /**
     * A decal of {@code kind} on {@code main} (which must be a supporting
     * solid face), plus each other allowed supporting face with {@code extra}
     * chance (dust spreading up the wall base, a cobweb across two walls…).
     */
    private BlockState decal(BlockPos c, Kind kind, Direction main, double extra) {
        Block b = kinds.get(kind);
        if (b == null || !kind.faces.contains(main) || !supports(c, main)
                || !b.getDefaultState().contains(MultifaceGrowthBlock.getProperty(main))) {
            return null;
        }
        BlockState s = b.getDefaultState().with(MultifaceGrowthBlock.getProperty(main), true);
        int salt = 40;
        for (Direction d : Direction.values()) {
            if (d == main || !kind.faces.contains(d) || !s.contains(MultifaceGrowthBlock.getProperty(d)) || !supports(c, d)) {
                continue;
            }
            if (rand(c, salt++) < extra) {
                s = s.with(MultifaceGrowthBlock.getProperty(d), true);
            }
        }
        return s;
    }

    private BlockState object(BlockPos c, String id, String property, int value) {
        Block b = block(id);
        if (b == null) {
            return null;
        }
        BlockState s = b.getDefaultState();
        if (s.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
            s = s.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,
                    Direction.fromHorizontal((int) (rand(c, 17) * 4) & 3));
        }
        return property == null ? s : withInt(s, property, value);
    }

    static BlockState withInt(BlockState s, String name, int value) {
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals(name) && p instanceof IntProperty ip) {
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                for (int v : ip.getValues()) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                return s.with(ip, Math.max(lo, Math.min(hi, value)));
            }
        }
        return s;
    }

    private static BlockState withBool(BlockState s, String name, boolean value) {
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals(name) && p instanceof net.minecraft.state.property.BooleanProperty bp) {
                return s.with(bp, value);
            }
        }
        return s;
    }

    private boolean free(BlockPos c, Map<BlockPos, BlockState> planned) {
        BlockState s = planned.containsKey(c) ? planned.get(c) : world.getBlockState(c);
        return s.isAir() && world.getFluidState(c).isEmpty();
    }

    /** The neighbour in {@code d} offers a full face toward this cell. */
    private boolean solidFace(BlockPos c, Direction d) {
        BlockPos n = c.offset(d);
        BlockState s = world.getBlockState(n);
        return !s.isAir() && !removable.contains(s.getBlock()) && s.isSideSolidFullSquare(world, n, d.getOpposite());
    }

    private boolean supports(BlockPos c, Direction d) {
        BlockPos n = c.offset(d);
        return solidFace(c, d) && MultifaceGrowthBlock.canGrowOn(world, d, n, world.getBlockState(n));
    }

    private List<Direction> walls(BlockPos c) {
        List<Direction> list = new ArrayList<>(4);
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (solidFace(c, d)) {
                list.add(d);
            }
        }
        return list;
    }

    /** In a pit beside a platform: some side is a wall whose top is a floor you could stand on. */
    private boolean trackbed(BlockPos c) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = c.offset(d);
            if (solidFace(c, d) && world.getBlockState(n.up()).isAir()
                    && (world.getBlockState(n.up(2)).isAir())) {
                return true;
            }
        }
        // also the cell next to a rail-ish block (MTR rails sit on ballast as blocks: gravel, ballast…)
        String below = Registries.BLOCK.getId(world.getBlockState(c.down()).getBlock()).getPath();
        return below.contains("ballast") || below.contains("gravel") || below.contains("track");
    }

    /** On a floor whose neighbour drops away: the platform edge. */
    private boolean platformEdge(BlockPos c) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = c.offset(d);
            if (world.getBlockState(n).isAir() && world.getBlockState(n.down()).isAir()) {
                return true;
            }
        }
        String below = Registries.BLOCK.getId(world.getBlockState(c.down()).getBlock()).getPath();
        return below.contains("platform_edge");
    }

    private static boolean metal(BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        return path.contains("iron") || path.contains("column") || path.contains("girder") || path.contains("steel")
                || path.contains("el_") || path.contains("copper") || path.contains("anvil") || path.contains("deck");
    }

    private static boolean masonry(BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        return path.contains("tile") || path.contains("brick") || path.contains("stone") || path.contains("concrete")
                || path.contains("terracotta") || path.contains("deepslate") || path.contains("wall")
                || path.contains("mosaic") || path.contains("quartz") || path.contains("calcite");
    }

    private LightCondition pickCondition(BlockPos p) {
        double sev = spec.get(WeatherSpec.Layer.SEVERITY) / 100.0;
        // mild: old tubes, stutters, pulses, dim · severe: dead, failing ballast, restrikes
        double[] w = {
                0.30 * (1 - sev) + 0.04 * sev, // WARM
                0.30 * (1 - sev) + 0.12 * sev, // STUTTER
                0.18 * (1 - sev) + 0.04 * sev, // PULSE
                0.20 * (1 - sev) + 0.12 * sev, // DIM
                0.02 * (1 - sev) + 0.22 * sev, // BALLAST
                0.00 * (1 - sev) + 0.12 * sev, // RESTRIKE
                0.00 * (1 - sev) + 0.34 * sev, // DEAD
        };
        LightCondition[] c = {LightCondition.WARM, LightCondition.STUTTER, LightCondition.PULSE, LightCondition.DIM,
                LightCondition.BALLAST, LightCondition.RESTRIKE, LightCondition.DEAD};
        double total = 0;
        for (double x : w) {
            total += x;
        }
        double r = rand(p, 93) * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) {
                return c[i];
            }
        }
        return LightCondition.STUTTER;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private boolean roll(BlockPos c, int salt, double p) {
        return p > 0 && rand(c, salt) < p;
    }

    private double rand(BlockPos c, int salt) {
        long h = c.asLong() * 0x9E3779B97F4A7C15L ^ (seed + salt * 0xD1B54A32D192ED03L);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Patchiness multiplier 0.15 .. 1.85 from a 3D value noise (5-block cells). */
    private double cluster(BlockPos c, int layer) {
        double n = noise(c.getX() / 5.0, c.getY() / 3.0, c.getZ() / 5.0, layer) * 0.7
                + noise(c.getX() / 2.0, c.getY() / 2.0, c.getZ() / 2.0, layer + 17) * 0.3;
        double t = Math.max(0, Math.min(1, (n - 0.3) / 0.45));
        return 0.15 + 1.7 * t * t * (3 - 2 * t);
    }

    private double noise(double x, double y, double z, int layer) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0, fy = y - y0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double v = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1;
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += w * rand(new BlockPos(x0 + dx, y0 + dy, z0 + dz), 500 + layer);
        }
        return v;
    }
}
