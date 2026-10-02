package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the earthworks tools: finds the tracks (the bridge
 * creator's way — the clicked rail plus, in Auto, every parallel track of
 * the line within reach), plans the work with {@link EarthworksBuilder}, and
 * APPLIES it a slice per tick ({@link #BUDGET} blocks) so a cutting through
 * a hill does not freeze the server, with an action-bar progress readout.
 * Each player keeps one undo (the states the job replaced, put back the
 * same sliced way). Removals go top-down first, placements bottom-up after,
 * so nothing is placed over a hole that is still to be dug; blocks are set
 * without neighbour reactions so water and leaves stay where they are.
 */
public final class EarthworksService {
    private static final int BUDGET = 8000;

    private static final class Job {
        final UUID player;
        final ServerWorld world;
        final BlockPos[] pos;
        final BlockState[] target;
        final BlockState[] before;
        final boolean undo;
        final String summary;
        int cursor;

        Job(UUID player, ServerWorld world, BlockPos[] pos, BlockState[] target, boolean undo, String summary) {
            this.player = player;
            this.world = world;
            this.pos = pos;
            this.target = target;
            this.before = new BlockState[pos.length];
            this.undo = undo;
            this.summary = summary;
        }
    }

    private record Undo(ServerWorld world, BlockPos[] pos, BlockState[] before) {
    }

    private static final Map<UUID, Job> RUNNING = new HashMap<>();
    private static final Map<UUID, Undo> LAST = new HashMap<>();

    private EarthworksService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(EarthworksService::tick);
        // never leave a half-built cutting behind a shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Job job : RUNNING.values()) {
                while (job.cursor < job.pos.length) {
                    step(job);
                }
            }
            RUNNING.clear();
            LAST.clear();
        });
    }

    /** The world as the builder's view. */
    record WorldView(ServerWorld world) implements EarthworksBuilder.View {
        @Override
        public BlockState get(BlockPos pos) {
            return world.getBlockState(pos);
        }

        @Override
        public int bottomY() {
            return world.getBottomY();
        }

        @Override
        public int topY() {
            return world.getTopY();
        }

        @Override
        public int surface(int x, int z) {
            return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        }
    }

    // ------------------------------------------------------------ building

    /** Two nodes clicked: this rail, plus (Auto) its parallel tracks. */
    public static void buildFromRail(ServerPlayerEntity player, ItemStack stack, EarthworksSpec.Kind kind, Rail rail) {
        EarthworksSpec spec = EarthworksSpec.read(kind, stack);
        ServerWorld world = player.getServerWorld();
        RailMath reference = rail.railMath;
        Simulator simulator = spec.trackMode == EarthworksSpec.TrackMode.AUTO ? BridgeService.simulatorFor(world) : null;
        if (simulator == null) {
            build(player, world, spec, new BridgeService.RailPath(reference), List.of());
            return;
        }
        String selfId = rail.getHexId();
        int reach = spec.trackReach;
        MinecraftServer server = world.getServer();
        simulator.run(() -> {
            List<RailMath> nearby = new ArrayList<>();
            try {
                for (Rail other : simulator.railIdMap.values()) {
                    if (!other.getHexId().equals(selfId) && BridgeService.boxesOverlap(reference, other.railMath, reach)) {
                        nearby.add(other.railMath);
                    }
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Earthworks: rail scan failed", t);
            }
            server.execute(() -> {
                List<BridgeBuilder.Path> candidates = new ArrayList<>();
                for (RailMath rm : nearby) {
                    candidates.add(new BridgeService.RailPath(rm));
                }
                BridgeBuilder.Path ref = new BridgeService.RailPath(reference);
                build(player, world, spec, ref, BridgeBuilder.detectCompanions(ref, candidates, reach));
            });
        });
    }

    /** A straight line between two points (the /earthworks line command, for testing and freehand work). */
    public static void buildLine(ServerPlayerEntity player, ItemStack stack, EarthworksSpec.Kind kind, BlockPos from, BlockPos to) {
        EarthworksSpec spec = EarthworksSpec.read(kind, stack);
        BridgeBuilder.Path path = new BridgeBuilder.LinePath(
                new Vector(from.getX() + 0.5, from.getY(), from.getZ() + 0.5),
                new Vector(to.getX() + 0.5, to.getY(), to.getZ() + 0.5));
        build(player, player.getServerWorld(), spec, path, List.of());
    }

    private static void build(ServerPlayerEntity player, ServerWorld world, EarthworksSpec spec,
                              BridgeBuilder.Path reference, List<BridgeBuilder.Path> companions) {
        if (RUNNING.containsKey(player.getUuid())) {
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.busy"), true);
            return;
        }
        EarthworksBuilder.Result result;
        long started = System.nanoTime();
        try {
            result = EarthworksBuilder.build(new WorldView(world), reference, companions, spec);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.error("Earthworks: planning failed", t);
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.failed"), true);
            return;
        }
        String what = Text.translatable("item.station_announcer." + itemId(spec.kind)).getString();
        if (result.tooBig()) {
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.too_big",
                    EarthworksBuilder.MAX_CHANGES), false);
            return;
        }
        if (!result.badMaterials().isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.bad_materials",
                    String.join(", ", result.badMaterials())), false);
        }
        Map<BlockPos, BlockState> changes = result.plan().changes;
        if (changes.isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.nothing", what), true);
            return;
        }
        // removals top-down, then placements bottom-up
        List<Map.Entry<BlockPos, BlockState>> removals = new ArrayList<>();
        List<Map.Entry<BlockPos, BlockState>> placements = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : changes.entrySet()) {
            (e.getValue().isAir() ? removals : placements).add(e);
        }
        removals.sort((a, b) -> Integer.compare(b.getKey().getY(), a.getKey().getY()));
        placements.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));
        int n = changes.size();
        BlockPos[] pos = new BlockPos[n];
        BlockState[] target = new BlockState[n];
        int i = 0;
        for (List<Map.Entry<BlockPos, BlockState>> list : List.of(removals, placements)) {
            for (Map.Entry<BlockPos, BlockState> e : list) {
                pos[i] = e.getKey();
                target[i] = e.getValue();
                i++;
            }
        }
        StringBuilder summary = new StringBuilder(Text.translatable("msg.station_announcer.earthworks.done",
                what, removals.size(), placements.size()).getString());
        if (result.trees() > 0) {
            summary.append(" · ").append(Text.translatable("msg.station_announcer.earthworks.trees", result.trees()).getString());
        }
        if (result.skipped() > 0) {
            summary.append(" · ").append(Text.translatable(spec.kind == EarthworksSpec.Kind.TRENCH
                    ? "msg.station_announcer.earthworks.skipped_tunnel" : "msg.station_announcer.earthworks.skipped_bridge",
                    (result.skipped() + 3) / 4).getString());
        }
        if (result.keptBuilds() > 0) {
            summary.append(" · ").append(Text.translatable("msg.station_announcer.earthworks.kept", result.keptBuilds()).getString());
        }
        StationAnnouncer.LOGGER.info("Earthworks: {} planned {} for {} ({} removals, {} placements, {} trees, {} samples skipped) in {} ms",
                player.getName().getString(), spec.kind, what, removals.size(), placements.size(), result.trees(),
                result.skipped(), (System.nanoTime() - started) / 1_000_000);
        RUNNING.put(player.getUuid(), new Job(player.getUuid(), world, pos, target, false, summary.toString()));
    }

    // ------------------------------------------------------------- applying

    private static void tick(MinecraftServer server) {
        if (RUNNING.isEmpty()) {
            return;
        }
        Iterator<Job> it = RUNNING.values().iterator();
        while (it.hasNext()) {
            Job job = it.next();
            int end = Math.min(job.pos.length, job.cursor + BUDGET);
            while (job.cursor < end) {
                step(job);
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.player);
            if (job.cursor >= job.pos.length) {
                it.remove();
                if (job.undo) {
                    if (player != null) {
                        player.sendMessage(Text.translatable("msg.station_announcer.earthworks.undone", job.pos.length), true);
                    }
                } else {
                    LAST.put(job.player, new Undo(job.world, job.pos, job.before));
                    if (player != null) {
                        player.sendMessage(Text.literal(job.summary), false);
                    }
                }
            } else if (player != null && server.getTicks() % 10 == 0) {
                player.sendMessage(Text.translatable("msg.station_announcer.earthworks.progress",
                        job.cursor * 100 / job.pos.length, job.cursor, job.pos.length), true);
            }
        }
    }

    private static void step(Job job) {
        int i = job.cursor++;
        job.before[i] = job.world.getBlockState(job.pos[i]);
        job.world.setBlockState(job.pos[i], job.target[i], Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }

    public static void undo(ServerPlayerEntity player) {
        if (RUNNING.containsKey(player.getUuid())) {
            player.sendMessage(Text.translatable("msg.station_announcer.earthworks.busy"), true);
            return;
        }
        Undo undo = LAST.remove(player.getUuid());
        if (undo == null) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.nothing_to_undo"), true);
            return;
        }
        if (player.getServerWorld() != undo.world) {
            LAST.put(player.getUuid(), undo);
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.undo_other_world"), true);
            return;
        }
        int n = undo.pos.length;
        BlockPos[] pos = new BlockPos[n];
        BlockState[] target = new BlockState[n];
        for (int i = 0; i < n; i++) {
            pos[i] = undo.pos[n - 1 - i];
            target[i] = undo.before[n - 1 - i];
        }
        RUNNING.put(player.getUuid(), new Job(player.getUuid(), undo.world, pos, target, true, ""));
    }

    public static String itemId(EarthworksSpec.Kind kind) {
        return switch (kind) {
            case EMBANKMENT -> "embankment_creator";
            case TRENCH -> "trench_creator";
            case CLEARER -> "row_clearer";
        };
    }
}
