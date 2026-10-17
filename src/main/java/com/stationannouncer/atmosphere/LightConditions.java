package com.stationannouncer.atmosphere;

import com.stationannouncer.StationAnnouncer;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Server side of the light conditions: the per-dimension store, the light
 * engine's snapshot, relighting when a condition changes the light level,
 * syncing to clients (full on join / dimension change, deltas on edits), and
 * a slow sweep that forgets lights whose block is gone.
 */
public final class LightConditions {
    /** S2C: bool full, int count, then (long pos, int packed) per entry; packed 0 = healthy again. */
    public static final Identifier SYNC_S2C = StationAnnouncer.id("light_conditions");
    private static final int ENTRIES_PER_PACKET = 8_000;
    private static final int SWEEP_TICKS = 600;

    /** Blocks that can never emit light in any state (cached). */
    private static final Map<Block, Boolean> CAN_GLOW = new HashMap<>();

    private LightConditions() {
    }

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> publish(world));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LightLuminance.clearServer());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendFull(handler.player, handler.player.getServerWorld()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                sendFull(player, destination));
        ServerTickEvents.END_SERVER_TICK.register(LightConditions::sweep);
    }

    public static LightConditionState state(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(LightConditionState.TYPE, LightConditionState.ID);
    }

    /** True for a block that gives off light in at least one of its states. */
    public static boolean canGlow(BlockState state) {
        if (state.getLuminance() > 0) {
            return true;
        }
        return CAN_GLOW.computeIfAbsent(state.getBlock(),
                b -> b.getStateManager().getStates().stream().anyMatch(s -> s.getLuminance() > 0));
    }

    public static LightCondition get(ServerWorld world, BlockPos pos) {
        return LightConditionState.condition(state(world).get(pos.asLong()));
    }

    /** Sets one light; returns false when the block cannot glow. */
    public static boolean set(ServerWorld world, BlockPos pos, LightCondition condition, int salt) {
        if (condition != LightCondition.HEALTHY && !canGlow(world.getBlockState(pos))) {
            return false;
        }
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        int packed = LightConditionState.pack(condition, salt);
        if (state(world).set(pos.asLong(), packed)) {
            changed.put(pos.asLong(), packed);
        }
        commit(world, changed);
        return true;
    }

    /**
     * Applies many at once (area tools): one snapshot, one broadcast.
     * {@code changes} maps pos → packed (0 = healthy).
     */
    public static int setAll(ServerWorld world, Long2IntMap changes) {
        LightConditionState state = state(world);
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry e : changes.long2IntEntrySet()) {
            long pos = e.getLongKey();
            int packed = e.getIntValue();
            if (packed != 0 && !canGlow(world.getBlockState(BlockPos.fromLong(pos)))) {
                continue;
            }
            if (state.set(pos, packed)) {
                changed.put(pos, packed);
            }
        }
        commit(world, changed);
        return changed.size();
    }

    /** Lights inside a box that currently have a condition (for undo). */
    public static Long2IntOpenHashMap inBox(ServerWorld world, BlockPos min, BlockPos max) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry e : state(world).entries().long2IntEntrySet()) {
            BlockPos p = BlockPos.fromLong(e.getLongKey());
            if (p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ()) {
                out.put(e.getLongKey(), e.getIntValue());
            }
        }
        return out;
    }

    private static void commit(ServerWorld world, Long2IntMap changed) {
        if (changed.isEmpty()) {
            return;
        }
        publish(world);
        // The light level only differs for DIM / WARM / DEAD, but a change
        // FROM one of those needs a recheck as well; checking all is cheap.
        for (long pos : changed.keySet()) {
            world.getChunkManager().getLightingProvider().checkBlock(BlockPos.fromLong(pos));
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            send(player, changed, false);
        }
    }

    private static void publish(ServerWorld world) {
        LightLuminance.publishServer(world.getRegistryKey(), state(world).entries());
    }

    private static void sendFull(ServerPlayerEntity player, ServerWorld world) {
        send(player, state(world).entries(), true);
    }

    private static void send(ServerPlayerEntity player, Long2IntMap entries, boolean full) {
        LongArrayList keys = new LongArrayList(entries.keySet());
        int i = 0;
        boolean first = true;
        do {
            int n = Math.min(ENTRIES_PER_PACKET, keys.size() - i);
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(full && first);
            buf.writeVarInt(n);
            for (int k = 0; k < n; k++) {
                long pos = keys.getLong(i + k);
                buf.writeLong(pos);
                buf.writeVarInt(entries.get(pos));
            }
            ServerPlayNetworking.send(player, SYNC_S2C, buf);
            i += n;
            first = false;
        } while (i < keys.size());
    }

    /** Drops entries whose block (in a loaded chunk) can no longer glow. */
    private static void sweep(MinecraftServer server) {
        if (server.getTicks() % SWEEP_TICKS != 0) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            LightConditionState state = state(world);
            if (state.entries().isEmpty()) {
                continue;
            }
            Long2IntOpenHashMap gone = new Long2IntOpenHashMap();
            for (long pos : state.entries().keySet()) {
                int x = BlockPos.unpackLongX(pos);
                int z = BlockPos.unpackLongZ(pos);
                if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z))) {
                    continue;
                }
                if (!canGlow(world.getBlockState(BlockPos.fromLong(pos)))) {
                    gone.put(pos, 0);
                }
            }
            if (!gone.isEmpty()) {
                setAll(world, gone);
            }
        }
    }
}
