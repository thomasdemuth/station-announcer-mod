package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Curved platforms: the {@link CurvedPlatformEdgeBlock} and the Curved Platform
 * Creator that lays it along a platform track.
 *
 * <p><b>Workflow:</b> build the platform floor roughly (stair-stepped, a bit
 * too far out is fine), stand on it, click the platform track's two nodes with
 * the creator. Along the rail it computes where the edge must be — the car's
 * half width ({@link GapFillers#CAR_HALF_WIDTH}) plus, on a curve, the amount a
 * straight car body swings off the arc (≈ 25/R blocks for a 20-block car, both
 * the middle bulging in and the ends swinging out) so no car clips the
 * platform — and, for every block that line passes through, places a curved
 * edge cut exactly along it; blocks of floor sticking out into the gap are
 * trimmed away. Cells just behind the cut carry the rest of the tactile strip.
 * Right-click the air with the creator to undo the last build.</p>
 *
 * <p>Runs on the server thread with the rail geometry MTR hands the creator.
 * Never touches MTR's own blocks (rail nodes, platforms) or block entities.</p>
 */
public final class CurvedPlatforms {
    public static final CurvedPlatformEdgeBlock CURVED_PLATFORM_EDGE = new CurvedPlatformEdgeBlock(ModContent.floorSettings());
    public static ItemCurvedPlatformCreator CREATOR;

    private static final double STEP = 0.25;
    private static final int SEARCH_WINDOW = 16;
    private static final Map<UUID, List<Change>> UNDO = new HashMap<>();

    private record Change(BlockPos pos, BlockState before) {
    }

    private CurvedPlatforms() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, StationAnnouncer.id("curved_platform_edge"), CURVED_PLATFORM_EDGE);
        BlockItem item = new BlockItem(CURVED_PLATFORM_EDGE, new Item.Settings());
        Registry.register(Registries.ITEM, StationAnnouncer.id("curved_platform_edge"), item);
        ModContent.DECORATION_ENTRIES.add(item);
        CREATOR = new ItemCurvedPlatformCreator();
        Registry.register(Registries.ITEM, StationAnnouncer.id("curved_platform_creator"), CREATOR);
        ModContent.OPERATIONS_ENTRIES.add(CREATOR);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> UNDO.clear());
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
            CurvedPlatformDevCommand.register();
        }
    }

    // ------------------------------------------------------------------ build

    /** Creator modes (item NBT {@link #MODE_KEY}): edges only, or edges + curved gap fillers of a style. */
    public static final String MODE_KEY = "FillerMode";
    public static final int MODE_EDGES = 0;
    public static final int MODE_UNION_FILLERS = 1;
    public static final int MODE_LOOP_FILLERS = 2;

    public static void build(ServerWorld world, RailMath rail, ServerPlayerEntity player) {
        build(world, rail, player, MODE_EDGES);
    }

    /**
     * {@code fillerMode} {@link #MODE_UNION_FILLERS} / {@link #MODE_LOOP_FILLERS}: every edge cell whose cut
     * runs the full width of the block (depth 0..16 at both ends) becomes a curved gap filler of that style
     * instead of a plain curved edge — a whole curved platform fitted with fillers in one pass. They link to
     * the platform and size their reach from the rail on their own.
     */
    public static void build(ServerWorld world, RailMath rail, ServerPlayerEntity player, int fillerMode) {
        double length = rail.getLength();
        int n = Math.max(2, (int) Math.ceil(length / STEP));
        double[] px = new double[n + 1], py = new double[n + 1], pz = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            Vector v = rail.getPosition(length * i / n, false);
            px[i] = v.x;
            py[i] = v.y;
            pz[i] = v.z;
        }
        double[] tx = new double[n + 1], tz = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n, i + 1);
            double dx = px[b] - px[a], dz = pz[b] - pz[a];
            double len = Math.hypot(dx, dz);
            tx[i] = len < 1e-9 ? 1 : dx / len;
            tz[i] = len < 1e-9 ? 0 : dz / len;
        }

        // Which side: where the player stands relative to the nearest point of the rail.
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double d = Math.hypot(player.getX() - px[i], player.getZ() - pz[i]);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        double cross = tx[nearest] * (player.getZ() - pz[nearest]) - tz[nearest] * (player.getX() - px[nearest]);
        if (Math.abs(cross) < 0.3) {
            player.sendMessage(Text.translatable("msg.station_announcer.curved_platform.side"), true);
            return;
        }
        double side = Math.signum(cross);

        // The edge line: rail centre + normal × (half width + curve allowance).
        double[] ex = new double[n + 1], ez = new double[n + 1];
        double[] nx = new double[n + 1], nz = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            nx[i] = -tz[i] * side;
            nz[i] = tx[i] * side;
            int a = Math.max(0, i - 8), b = Math.min(n, i + 8);
            double arc = (b - a) * length / n;
            double turn = Math.abs(Math.asin(Math.max(-1, Math.min(1, tx[a] * tz[b] - tz[a] * tx[b]))));
            double curvature = arc > 1e-6 ? turn / arc : 0;
            double distance = GapFillers.CAR_HALF_WIDTH + Math.min(1.5, 25.0 * curvature);
            ex[i] = px[i] + nx[i] * distance;
            ez[i] = pz[i] + nz[i] * distance;
        }

        // Every cell the edge passes near, with the sample it is nearest to.
        Map<BlockPos, Integer> cells = new LinkedHashMap<>();
        Map<BlockPos, Double> cellDistance = new HashMap<>();
        for (int i = 0; i <= n; i++) {
            int y = (int) Math.floor(py[i] + 1e-3);
            int cx = (int) Math.floor(ex[i]), cz = (int) Math.floor(ez[i]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cell = new BlockPos(cx + dx, y, cz + dz);
                    double d = Math.hypot(cell.getX() + 0.5 - ex[i], cell.getZ() + 0.5 - ez[i]);
                    Double previous = cellDistance.get(cell);
                    if (previous == null || d < previous) {
                        cellDistance.put(cell, d);
                        cells.put(cell, i);
                    }
                }
            }
        }

        List<Change> changes = new ArrayList<>();
        int placed = 0, trimmed = 0;
        for (Map.Entry<BlockPos, Integer> entry : cells.entrySet()) {
            BlockPos cell = entry.getKey();
            int i = entry.getValue();
            Direction facing = Direction.getFacing(-nx[i], 0, -nz[i]); // toward the track
            if (!facing.getAxis().isHorizontal()) {
                continue;
            }
            float[] left = GapFillerBlock.rotateXZ(facing, 0, 0);
            float[] right = GapFillerBlock.rotateXZ(facing, 16, 0);
            double wx = -facing.getOffsetX(), wz = -facing.getOffsetZ(); // into the block
            Double depthA = depth(cell.getX() + left[0] / 16.0, cell.getZ() + left[1] / 16.0, wx, wz, ex, ez, i, n);
            Double depthB = depth(cell.getX() + right[0] / 16.0, cell.getZ() + right[1] / 16.0, wx, wz, ex, ez, i, n);
            if (depthA == null || depthB == null) {
                continue;
            }
            double a = depthA * 16, b = depthB * 16;
            BlockState current = world.getBlockState(cell);
            if (a >= 15.5 && b >= 15.5) {
                // Floor sticking out into the gap: trim it (only plain solid blocks, well off the rail).
                double fromRail = Math.hypot(cell.getX() + 0.5 - px[i], cell.getZ() + 0.5 - pz[i]);
                if (fromRail >= 1.0 && trimmable(current)) {
                    changes.add(new Change(cell, current));
                    world.setBlockState(cell, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    trimmed++;
                }
                continue;
            }
            if (a <= -8 && b <= -8) {
                continue; // plain platform behind the edge: the player's own floor stays
            }
            if (!replaceable(current)) {
                continue;
            }
            boolean fullWidthCut = a >= 0 && b >= 0 && a <= 16 && b <= 16;
            Block block = CURVED_PLATFORM_EDGE;
            if (fullWidthCut && fillerMode == MODE_UNION_FILLERS) {
                block = GapFillers.CURVED_GAP_FILLER;
            } else if (fullWidthCut && fillerMode == MODE_LOOP_FILLERS) {
                block = GapFillers.CURVED_GAP_FILLER_LOOP;
            }
            BlockState edge = block.getDefaultState()
                    .with(CurvedPlatformEdgeBlock.TRACK_SIDE, facing)
                    .with(CurvedPlatformEdgeBlock.CUT_A, CurvedPlatformEdgeBlock.cutFor((float) a))
                    .with(CurvedPlatformEdgeBlock.CUT_B, CurvedPlatformEdgeBlock.cutFor((float) b));
            if (edge != current) {
                changes.add(new Change(cell, current));
                world.setBlockState(cell, edge, Block.NOTIFY_ALL);
                placed++;
            }
        }
        UNDO.put(player.getUuid(), changes);
        player.sendMessage(Text.translatable("msg.station_announcer.curved_platform.built", placed, trimmed), true);
    }

    /**
     * Distance (blocks) along the ray from (cx, cz) in direction (wx, wz) to the
     * edge polyline near sample {@code i}; negative = behind the ray's start.
     * Null when the edge does not cross that line nearby (past a rail end).
     */
    private static Double depth(double cx, double cz, double wx, double wz, double[] ex, double[] ez, int i, int n) {
        Double best = null;
        for (int j = Math.max(0, i - SEARCH_WINDOW); j < Math.min(n, i + SEARCH_WINDOW); j++) {
            double sx = ex[j + 1] - ex[j], sz = ez[j + 1] - ez[j];
            double denom = wx * sz - wz * sx;
            if (Math.abs(denom) < 1e-9) {
                continue;
            }
            double qx = ex[j] - cx, qz = ez[j] - cz;
            double t = (qx * sz - qz * sx) / denom;   // along the ray
            double u = (qx * wz - qz * wx) / denom;   // along the segment
            if (u < -1e-6 || u > 1 + 1e-6 || Math.abs(t) > 3) {
                continue;
            }
            if (best == null || Math.abs(t) < Math.abs(best)) {
                best = t;
            }
        }
        return best;
    }

    private static boolean isOurs(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("mtr");
    }

    /** Edge cells: anything but MTR's blocks and block-entity blocks may become a curved edge. */
    private static boolean replaceable(BlockState state) {
        if (state.isOf(CURVED_PLATFORM_EDGE) || state.getBlock() instanceof CurvedGapFillerBlock) {
            return true; // re-running the creator (e.g. to add or drop fillers) replaces its own work
        }
        return !isOurs(state) && !(state.getBlock() instanceof BlockEntityProvider);
    }

    /** Gap cells: only plain solid floor (full cubes, platform edges) is trimmed. */
    private static boolean trimmable(BlockState state) {
        if (state.isAir() || isOurs(state) || state.getBlock() instanceof BlockEntityProvider) {
            return false;
        }
        return state.isOf(CURVED_PLATFORM_EDGE) || state.getBlock() instanceof PlatformEdgeBlock
                || state.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }

    // ------------------------------------------------------------------- undo

    public static void undo(ServerWorld world, ServerPlayerEntity player) {
        List<Change> changes = UNDO.remove(player.getUuid());
        if (changes == null || changes.isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.curved_platform.nothing_to_undo"), true);
            return;
        }
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            world.setBlockState(change.pos(), change.before(), Block.NOTIFY_ALL);
        }
        player.sendMessage(Text.translatable("msg.station_announcer.curved_platform.undone", changes.size()), true);
    }
}
