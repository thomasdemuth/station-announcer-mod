package com.stationannouncer.atmosphere;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FareMachineBlock;
import com.stationannouncer.mtr.TurnstileBlock;
import com.stationannouncer.mtr.TurnstileHeetBlock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/**
 * Fare equipment that is out of service: a MetroCard machine bagged with an
 * "OUT OF ORDER" sign (refuses every use), a turnstile or HEET taped off
 * (the lane stays locked and refuses every rider). Toggled with the
 * Maintenance Tool; kept per dimension by the equipment's DATA cell (lower
 * half / lane_lower), synced to clients for the tape and bag overlays.
 */
public final class OutOfService {
    /** S2C: bool full, varint n, n longs (data cells out of service); a delta packet lists toggled cells, bool each. */
    public static final Identifier SYNC_S2C = StationAnnouncer.id("out_of_service");

    private OutOfService() {
    }

    // ------------------------------------------------------------ state

    public static final class State extends PersistentState {
        public static final String ID = "station_announcer_out_of_service";
        public static final Type<State> TYPE = new Type<>(State::new, State::fromNbt,
                net.minecraft.datafixer.DataFixTypes.LEVEL);
        final LongOpenHashSet cells = new LongOpenHashSet();

        private static State fromNbt(NbtCompound nbt) {
            State s = new State();
            for (long l : nbt.getLongArray("Cells")) {
                s.cells.add(l);
            }
            return s;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            nbt.putLongArray("Cells", cells.toLongArray());
            return nbt;
        }
    }

    public static State state(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(State.TYPE, State.ID);
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendFull(handler.player, handler.player.getServerWorld()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                sendFull(player, destination));
        ServerTickEvents.END_SERVER_TICK.register(OutOfService::sweep);
    }

    // ------------------------------------------------------------ queries

    public static boolean toggleable(BlockState state) {
        return state.getBlock() instanceof FareMachineBlock
                || state.getBlock() instanceof TurnstileBlock
                || state.getBlock() instanceof TurnstileHeetBlock;
    }

    /** The data cell of a piece of fare equipment. */
    public static BlockPos dataCell(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof FareMachineBlock) {
            return state.get(FareMachineBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos.toImmutable();
        }
        if (state.getBlock() instanceof TurnstileBlock) {
            return TurnstileBlock.lanePos(state, pos).toImmutable();
        }
        if (state.getBlock() instanceof TurnstileHeetBlock) {
            return TurnstileHeetBlock.lanePos(state, pos).toImmutable();
        }
        return pos.toImmutable();
    }

    /** Server check (fare lanes, the fare machine). */
    public static boolean isOut(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld server)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (!toggleable(state)) {
            return false;
        }
        LongSet cells = state(server).cells;
        return !cells.isEmpty() && cells.contains(dataCell(state, pos).asLong());
    }

    /** Sets it directly (command); returns false when the block is not fare equipment. */
    public static boolean set(ServerWorld world, BlockPos pos, boolean out) {
        BlockState state = world.getBlockState(pos);
        if (!toggleable(state)) {
            return false;
        }
        long cell = dataCell(state, pos).asLong();
        State s = state(world);
        boolean changed = out ? s.cells.add(cell) : s.cells.remove(cell);
        if (changed) {
            s.markDirty();
            for (ServerPlayerEntity p : world.getPlayers()) {
                sendDelta(p, cell, out);
            }
        }
        return true;
    }

    public static void toggle(ServerWorld world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!player.canModifyBlocks()) {
            return;
        }
        long cell = dataCell(state, pos).asLong();
        boolean out = !state(world).cells.contains(cell);
        set(world, pos, out);
        world.playSound(null, pos, out ? SoundEvents.ITEM_BUNDLE_INSERT : SoundEvents.ITEM_BUNDLE_REMOVE_ONE,
                SoundCategory.BLOCKS, 0.8f, out ? 0.8f : 1.1f);
        player.sendMessage(Text.translatable(out ? "msg.station_announcer.maintenance.out_of_service"
                : "msg.station_announcer.maintenance.in_service",
                Text.translatable(state.getBlock().getTranslationKey())), true);
    }

    /** What a rider / customer is told. */
    public static void refuse(World world, BlockPos pos, PlayerEntity player) {
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.BLOCKS, 0.6f, 0.6f);
        player.sendMessage(Text.translatable("msg.station_announcer.maintenance.refused"), true);
    }

    // ------------------------------------------------------------ sync

    private static void sendFull(ServerPlayerEntity player, ServerWorld world) {
        LongSet cells = state(world).cells;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true);
        buf.writeVarInt(cells.size());
        for (long l : cells) {
            buf.writeLong(l);
            buf.writeBoolean(true);
        }
        ServerPlayNetworking.send(player, SYNC_S2C, buf);
    }

    private static void sendDelta(ServerPlayerEntity player, long cell, boolean out) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false);
        buf.writeVarInt(1);
        buf.writeLong(cell);
        buf.writeBoolean(out);
        ServerPlayNetworking.send(player, SYNC_S2C, buf);
    }

    /** Forgets equipment that was broken (loaded chunks only). */
    private static void sweep(MinecraftServer server) {
        if (server.getTicks() % 600 != 300) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            State s = state(world);
            if (s.cells.isEmpty()) {
                continue;
            }
            LongOpenHashSet gone = new LongOpenHashSet();
            for (long cell : s.cells) {
                BlockPos p = BlockPos.fromLong(cell);
                if (world.isChunkLoaded(ChunkSectionPos.getSectionCoord(p.getX()), ChunkSectionPos.getSectionCoord(p.getZ()))
                        && !toggleable(world.getBlockState(p))) {
                    gone.add(cell);
                }
            }
            if (!gone.isEmpty()) {
                s.cells.removeAll(gone);
                s.markDirty();
                for (ServerPlayerEntity p : world.getPlayers()) {
                    for (long cell : gone) {
                        sendDelta(p, cell, false);
                    }
                }
            }
        }
    }
}
