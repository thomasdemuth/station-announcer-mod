package com.stationannouncer.mtr;

import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Which service change poster hangs in this frame, plus a SNAPSHOT of it.
 *
 * <p>The id links the sign to the live poster: while that poster exists the
 * server refreshes the snapshot on every edit ({@link PosterSignRegistry}) and
 * the client renderer prefers its synced mirror, so edits show up at once. The
 * snapshot is what keeps the sign readable after the disruption (and its
 * posters) has been deleted or has expired — a hung poster stays hung until
 * somebody takes it down.</p>
 */
public class ServicePosterBlockEntity extends BlockEntity {
    private long posterId;
    @Nullable
    private ServicePoster snapshot;

    public ServicePosterBlockEntity(BlockPos pos, BlockState state) {
        super(MtrStationDecor.SERVICE_POSTER_BLOCK_ENTITY, pos, state);
    }

    public long getPosterId() {
        return posterId;
    }

    @Nullable
    public ServicePoster getSnapshot() {
        return snapshot;
    }

    /** Server thread. {@code null} poster with id 0 clears the frame. */
    public void setPoster(long id, @Nullable ServicePoster poster) {
        this.posterId = id;
        this.snapshot = poster;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("PosterId", posterId);
        if (snapshot != null) {
            nbt.putString("Poster", snapshot.toJson().toString());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        posterId = nbt.getLong("PosterId");
        snapshot = null;
        if (nbt.contains("Poster")) {
            try {
                snapshot = ServicePoster.fromJson(JsonParser.parseString(nbt.getString("Poster")).getAsJsonObject());
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Service poster at {} has an unreadable snapshot", pos, e);
            }
        }
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    public void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void setWorld(World world) {
        super.setWorld(world);
        if (world instanceof ServerWorld) {
            PosterSignRegistry.add(this);
        }
    }

    @Override
    public void markRemoved() {
        PosterSignRegistry.remove(this);
        super.markRemoved();
    }
}
