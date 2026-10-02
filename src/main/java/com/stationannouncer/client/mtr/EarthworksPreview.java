package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.BridgeBuilder;
import com.stationannouncer.mtr.EarthworksBuilder;
import com.stationannouncer.mtr.EarthworksSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The earthworks settings preview: a small patch of generated terrain the
 * tool would actually face — a valley with a stream for the Embankment
 * Creator, a wooded hill for the Trench Creator, a meadow with trees, flowers
 * and snow for the ROW Clearer — run through the real
 * {@link EarthworksBuilder}, so the miniature is exactly what a build does
 * there. Only cells with an open face are drawn (plus the cut-away edges).
 */
@Environment(EnvType.CLIENT)
public final class EarthworksPreview {
    private static final int LENGTH = 18;

    private EarthworksPreview() {
    }

    /** Terrain generator: ground height per column, a stream, and a few trees. */
    private static final class Terrain implements EarthworksBuilder.View {
        final EarthworksSpec.Kind kind;
        final int railY;
        final int water;
        final Map<BlockPos, BlockState> extra = new HashMap<>();
        final Map<Long, Integer> treeTop = new HashMap<>();

        Terrain(EarthworksSpec.Kind kind, int railY) {
            this.kind = kind;
            this.railY = railY;
            this.water = kind == EarthworksSpec.Kind.EMBANKMENT ? railY - 7 : Integer.MIN_VALUE;
            switch (kind) {
                case EMBANKMENT -> {
                    tree(-1, 9);
                    tree(LENGTH - 1, -10);
                }
                case TRENCH -> {
                    tree(7, -6);
                    tree(11, 7);
                    tree(5, 11);
                }
                case CLEARER -> {
                    int[][] spots = {{2, 3}, {6, -4}, {9, 2}, {13, -2}, {16, 5}, {4, -9}, {11, 9}, {15, -8}, {1, -1}};
                    for (int[] s : spots) {
                        tree(s[0], s[1]);
                    }
                    for (int x = 0; x < LENGTH; x++) {
                        for (int z = -8; z <= 8; z++) {
                            long h = hash(x, z);
                            int g = ground(x, z);
                            BlockPos p = new BlockPos(x, g + 1, z);
                            if (extra.containsKey(p)) {
                                continue;
                            }
                            if (x > 11 && z < 0) {
                                extra.put(p, Blocks.SNOW.getDefaultState());
                            } else if (h % 5 == 0) {
                                extra.put(p, Blocks.SHORT_GRASS.getDefaultState());
                            } else if (h % 11 == 1) {
                                extra.put(p, Blocks.POPPY.getDefaultState());
                            } else if (h % 13 == 2) {
                                extra.put(p, Blocks.DANDELION.getDefaultState());
                            }
                        }
                    }
                }
            }
        }

        int ground(int x, int z) {
            double t = Math.max(0, Math.min(1, x / (double) (LENGTH - 1)));
            double bump = Math.sin(Math.PI * t);
            int az = Math.abs(z);
            return switch (kind) {
                case EMBANKMENT -> railY - 1 - (int) Math.round(7 * Math.pow(bump, 0.7)) + (az > 11 ? 1 : 0);
                case TRENCH -> railY - 1 + (int) Math.round(10 * Math.pow(bump, 1.4)) + az / 6;
                case CLEARER -> railY - 1 + (az > 5 ? 1 : 0);
            };
        }

        void tree(int tx, int tz) {
            int g = ground(tx, tz);
            int top = g + 5;
            BlockState log = Blocks.OAK_LOG.getDefaultState();
            for (int y = g + 1; y <= top; y++) {
                extra.put(new BlockPos(tx, y, tz), log);
            }
            treeTop.put(BlockPos.asLong(tx, 0, tz), top);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -2; dy <= 1; dy++) {
                        int r = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy);
                        if (r > 3 || (dx == 0 && dz == 0 && dy <= 0)) {
                            continue;
                        }
                        BlockPos p = new BlockPos(tx + dx, top + dy, tz + dz);
                        if (!extra.containsKey(p)) {
                            extra.put(p, Blocks.OAK_LEAVES.getDefaultState()
                                    .with(LeavesBlock.DISTANCE, Math.max(1, Math.min(7, Math.abs(dx) + Math.abs(dz) + Math.max(0, dy))))
                                    .with(Properties.PERSISTENT, false));
                        }
                    }
                }
            }
        }

        @Override
        public BlockState get(BlockPos pos) {
            BlockState e = extra.get(pos);
            if (e != null) {
                return e;
            }
            int g = ground(pos.getX(), pos.getZ());
            int y = pos.getY();
            if (y < 0) {
                return Blocks.BEDROCK.getDefaultState();
            }
            if (y > g) {
                return y <= water ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
            }
            if (y == g) {
                return g < water ? Blocks.SAND.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
            }
            return y >= g - 2 ? Blocks.DIRT.getDefaultState() : Blocks.STONE.getDefaultState();
        }

        @Override
        public int bottomY() {
            return 0;
        }

        @Override
        public int topY() {
            return 96;
        }

        @Override
        public int surface(int x, int z) {
            int g = Math.max(ground(x, z), water);
            Integer top = treeTop.get(BlockPos.asLong(x, 0, z));
            return top != null ? Math.max(g, top) : g;
        }
    }

    public static int railY(EarthworksSpec.Kind kind) {
        return kind == EarthworksSpec.Kind.EMBANKMENT ? 14 : 6;
    }

    /** The scene: terrain after (or before) the build, open faces only, rails on the tracks. */
    public static List<CreatorPreview.Cell> scene(EarthworksSpec spec, boolean after) {
        int railY = railY(spec.kind);
        Terrain terrain = new Terrain(spec.kind, railY);
        List<BridgeBuilder.Path> companions = new ArrayList<>();
        for (int j = 1; j < spec.previewTracks; j++) {
            companions.add(new BridgeBuilder.LinePath(new Vector(0, railY, 0.5 + 3 * j), new Vector(LENGTH, railY, 0.5 + 3 * j)));
        }
        EarthworksBuilder.Plan plan = null;
        if (after) {
            try {
                plan = EarthworksBuilder.build(terrain,
                        new BridgeBuilder.LinePath(new Vector(0, railY, 0.5), new Vector(LENGTH, railY, 0.5)),
                        companions, spec.copy()).plan();
            } catch (Exception e) {
                com.stationannouncer.StationAnnouncer.LOGGER.warn("Earthworks preview failed", e);
            }
        }
        final EarthworksBuilder.Plan finalPlan = plan;
        java.util.function.Function<BlockPos, BlockState> at = p -> finalPlan != null ? finalPlan.state(p) : terrain.get(p);

        int width = 3 * (spec.previewTracks - 1);
        int half = Math.min(15, 6 + width / 2 + (spec.kind == EarthworksSpec.Kind.CLEARER ? spec.corridor / 2 : 6));
        int minX = 0, maxX = LENGTH - 1, minZ = -half, maxZ = half + width;
        int minY = Math.max(1, railY - 12), maxY = railY + 16;
        BlockState rail = Blocks.RAIL.getDefaultState().with(net.minecraft.block.RailBlock.SHAPE, RailShape.EAST_WEST);
        List<CreatorPreview.Cell> out = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // a skin of ground, not the whole block of rock under it: the earthwork is the subject
                int floor = Math.max(minY, Math.min(terrain.ground(x, z), railY - 3) - 2);
                for (int y = floor; y <= maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = at.apply(p);
                    boolean trackCell = y == railY && z >= 0 && z <= width && z % 3 == 0;
                    if (trackCell && s.isAir()) {
                        out.add(new CreatorPreview.Cell(p, rail));
                        continue;
                    }
                    if (s.isAir()) {
                        continue;
                    }
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ || y == floor;
                    if (!edge && !open(at, p)) {
                        continue;
                    }
                    if (s.isOf(Blocks.WATER)) {
                        s = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
                    }
                    out.add(new CreatorPreview.Cell(p, s));
                }
            }
        }
        return out;
    }

    private static boolean open(java.util.function.Function<BlockPos, BlockState> at, BlockPos p) {
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            BlockState n = at.apply(p.offset(d));
            if (n.isAir() || !n.isOpaqueFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                return true;
            }
        }
        return false;
    }

    private static long hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        return Math.floorMod(h, 1_000_003L);
    }
}
