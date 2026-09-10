package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server side of the Bridge Creator: turns rails into {@link BridgeBuilder}
 * paths, finds the parallel tracks of a line, runs the build against the
 * real world while recording what it replaced, and keeps one undo per
 * player.
 *
 * <p><b>Threading:</b> MTR's rails live in the per-dimension
 * {@code Simulator}, which may tick on its own thread. Every read of its
 * rail map happens inside {@code simulator.run(...)}; only immutable
 * {@code RailMath} objects cross back, and the build itself always runs on
 * the server thread via {@code server.execute}.
 */
public final class BridgeService {
    /** Manually selected tracks (rail hex ids) on the creator item. */
    public static final String TRACKS_TAG = "Tracks";
    public static final int MAX_MANUAL_TRACKS = 8;
    /** Blocks one undo may hold; larger builds keep only their first part undoable. */
    private static final int MAX_UNDO = 400_000;

    private record Change(BlockPos pos, BlockState before) {
    }

    private record Undo(ServerWorld world, List<Change> changes, boolean complete) {
    }

    private static final Map<UUID, Undo> LAST_BUILD = new HashMap<>();

    private BridgeService() {
    }

    // ------------------------------------------------------------ paths

    /** A rail as a path: MTR's own curve, sampled by distance along it. */
    public record RailPath(RailMath railMath) implements BridgeBuilder.Path {
        @Override
        public double length() {
            return railMath.getLength();
        }

        @Override
        public Vector at(double distance) {
            return railMath.getPosition(Math.max(0, Math.min(length(), distance)), false);
        }
    }

    /** The world as a sink that remembers what it overwrote. */
    private static final class WorldSink implements BridgeBuilder.Sink {
        final ServerWorld world;
        final List<Change> changes = new ArrayList<>();
        boolean complete = true;

        WorldSink(ServerWorld world) {
            this.world = world;
        }

        @Override
        public boolean replaceable(BlockPos pos) {
            return world.getBlockState(pos).isReplaceable();
        }

        @Override
        public void set(BlockPos pos, BlockState state) {
            if (changes.size() < MAX_UNDO) {
                changes.add(new Change(pos.toImmutable(), world.getBlockState(pos)));
            } else {
                complete = false;
            }
            world.setBlockState(pos, state, 3);
        }

        @Override
        public int bottomY() {
            return world.getBottomY();
        }
    }

    // ------------------------------------------------------- manual tracks

    public static List<String> manualTracks(ItemStack stack) {
        List<String> ids = new ArrayList<>();
        NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(TRACKS_TAG, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(TRACKS_TAG, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) {
                ids.add(list.getString(i));
            }
        }
        return ids;
    }

    public static int manualTrackCount(ItemStack stack) {
        return manualTracks(stack).size();
    }

    private static void writeManualTracks(ItemStack stack, List<String> ids) {
        NbtList list = new NbtList();
        for (String id : ids) {
            list.add(NbtString.of(id));
        }
        stack.getOrCreateNbt().put(TRACKS_TAG, list);
    }

    public static void clearManualTracks(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt != null) {
            nbt.remove(TRACKS_TAG);
        }
    }

    /** A completed two-node click in manual mode: remember the rail, build later from the screen. */
    public static void addManualTrack(ServerPlayerEntity player, ItemStack stack, Rail rail) {
        List<String> ids = manualTracks(stack);
        String id = rail.getHexId();
        if (ids.contains(id)) {
            ids.remove(id);
            writeManualTracks(stack, ids);
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.track_removed", ids.size()), true);
            return;
        }
        if (ids.size() >= MAX_MANUAL_TRACKS) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.track_limit", MAX_MANUAL_TRACKS), true);
            return;
        }
        ids.add(id);
        writeManualTracks(stack, ids);
        player.sendMessage(Text.translatable("msg.station_announcer.bridge.track_added", ids.size()), true);
    }

    // ------------------------------------------------------------ building

    /**
     * Two nodes clicked (auto or single mode): find the line's other tracks
     * if asked, then build under all of them with this rail as the reference.
     */
    public static void buildFromRail(ServerPlayerEntity player, ItemStack stack, Rail rail) {
        BridgeSpec spec = BridgeSpec.read(stack);
        ServerWorld world = player.getServerWorld();
        if (spec.trackMode != BridgeSpec.TrackMode.AUTO) {
            build(player, world, spec, rail.railMath, List.of());
            return;
        }
        Simulator simulator = simulatorFor(world);
        if (simulator == null) {
            build(player, world, spec, rail.railMath, List.of());
            return;
        }
        String selfId = rail.getHexId();
        RailMath reference = rail.railMath;
        int reach = spec.trackReach;
        MinecraftServer server = world.getServer();
        simulator.run(() -> {
            List<RailMath> nearby = new ArrayList<>();
            try {
                for (Rail other : simulator.railIdMap.values()) {
                    if (other.getHexId().equals(selfId) || !boxesOverlap(reference, other.railMath, reach)) {
                        continue;
                    }
                    nearby.add(other.railMath);
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Bridge creator: rail scan failed", t);
            }
            server.execute(() -> {
                List<BridgeBuilder.Path> candidates = new ArrayList<>();
                for (RailMath rm : nearby) {
                    candidates.add(new RailPath(rm));
                }
                List<BridgeBuilder.Path> companions = BridgeBuilder.detectCompanions(new RailPath(reference), candidates, reach);
                build(player, world, spec, reference, companions);
            });
        });
    }

    /** Build from the manually selected tracks (the first selected is the reference). */
    public static void buildManual(ServerPlayerEntity player, ItemStack stack) {
        List<String> ids = manualTracks(stack);
        if (ids.isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.no_tracks"), true);
            return;
        }
        BridgeSpec spec = BridgeSpec.read(stack);
        ServerWorld world = player.getServerWorld();
        Simulator simulator = simulatorFor(world);
        if (simulator == null) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.no_simulator"), true);
            return;
        }
        MinecraftServer server = world.getServer();
        simulator.run(() -> {
            Map<String, RailMath> found = new HashMap<>();
            try {
                for (Rail rail : simulator.railIdMap.values()) {
                    String id = rail.getHexId();
                    if (ids.contains(id)) {
                        found.put(id, rail.railMath);
                    }
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Bridge creator: rail lookup failed", t);
            }
            server.execute(() -> {
                RailMath reference = null;
                List<BridgeBuilder.Path> companions = new ArrayList<>();
                for (String id : ids) {
                    RailMath rm = found.get(id);
                    if (rm == null) {
                        continue;
                    }
                    if (reference == null) {
                        reference = rm;
                    } else {
                        companions.add(new RailPath(rm));
                    }
                }
                if (reference == null) {
                    player.sendMessage(Text.translatable("msg.station_announcer.bridge.tracks_gone"), true);
                    clearManualTracks(stack);
                    return;
                }
                build(player, world, spec, reference, companions);
            });
        });
    }

    private static void build(ServerPlayerEntity player, ServerWorld world, BridgeSpec spec, RailMath reference,
                              List<BridgeBuilder.Path> companions) {
        WorldSink sink = new WorldSink(world);
        BridgeBuilder.Result result;
        try {
            result = BridgeBuilder.build(sink, new RailPath(reference), companions, spec);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.error("Bridge creator: build failed", t);
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.failed"), true);
            return;
        }
        if (!sink.changes.isEmpty()) {
            LAST_BUILD.put(player.getUuid(), new Undo(world, sink.changes, sink.complete));
        }
        StationAnnouncer.LOGGER.info("Bridge creator: {} built {} blocks under {} track(s), {} pier(s)",
                player.getName().getString(), result.blocks(), result.tracks(), result.piers());
        player.sendMessage(Text.translatable("msg.station_announcer.bridge.built",
                result.blocks(), result.tracks(), result.piers()), true);
        if (!result.badMaterials().isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (BridgeSpec.Slot slot : result.badMaterials()) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(slot.name().toLowerCase(java.util.Locale.ROOT));
            }
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.bad_materials", names.toString()), false);
        }
    }

    /** Puts back every block the player's last build replaced. */
    public static void undo(ServerPlayerEntity player) {
        Undo undo = LAST_BUILD.remove(player.getUuid());
        if (undo == null) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.nothing_to_undo"), true);
            return;
        }
        if (player.getServerWorld() != undo.world) {
            player.sendMessage(Text.translatable("msg.station_announcer.bridge.undo_other_world"), true);
            LAST_BUILD.put(player.getUuid(), undo);
            return;
        }
        List<Change> changes = undo.changes;
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change c = changes.get(i);
            undo.world.setBlockState(c.pos, c.before, 3);
        }
        player.sendMessage(Text.translatable(undo.complete
                ? "msg.station_announcer.bridge.undone" : "msg.station_announcer.bridge.undone_partial", changes.size()), true);
    }

    public static boolean hasUndo(ServerPlayerEntity player) {
        return LAST_BUILD.containsKey(player.getUuid());
    }

    public static void clearAll() {
        LAST_BUILD.clear();
    }

    // ------------------------------------------------------------- helpers

    private static Simulator simulatorFor(ServerWorld world) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null) {
            return null;
        }
        String id;
        try {
            id = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return null;
        }
        for (Simulator simulator : simulators) {
            if (simulator.dimension.equals(id)) {
                return simulator;
            }
        }
        return null;
    }

    private static boolean boxesOverlap(RailMath a, RailMath b, int reach) {
        return b.maxX >= a.minX - reach && b.minX <= a.maxX + reach
                && b.maxZ >= a.minZ - reach && b.minZ <= a.maxZ + reach
                && b.maxY >= a.minY - 16 && b.minY <= a.maxY + 16;
    }

    /** For the client screen: summarises what the item holds. */
    public static void forEachManualTrack(ItemStack stack, Consumer<String> consumer) {
        manualTracks(stack).forEach(consumer);
    }
}
