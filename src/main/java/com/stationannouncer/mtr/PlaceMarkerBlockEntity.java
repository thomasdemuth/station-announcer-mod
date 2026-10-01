package com.stationannouncer.mtr;

import com.stationannouncer.wayfinding.Place;
import com.stationannouncer.wayfinding.PlaceCategory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A place marker's copy of its {@link Place}: what the client draws on the
 * pin and what a structure paste carries along. The world's truth is
 * {@link com.stationannouncer.wayfinding.WayfindingStore}; on its first tick
 * the block adopts the store's record for its position (see
 * {@link Wayfinding#reconcilePlace}).
 */
public class PlaceMarkerBlockEntity extends BlockEntity {
    private String placeId = "";
    private String name = "";
    private PlaceCategory category = PlaceCategory.LANDMARK;
    private String description = "";
    private int radius;
    private boolean hidden;

    /** Server-only, never saved. */
    boolean reconciled;
    /** Server-only: who placed the block (becomes the place's author). */
    String createdBy = "";

    public PlaceMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(Wayfinding.PLACE_MARKER_BLOCK_ENTITY, pos, state);
    }

    public String getPlaceId() {
        return placeId;
    }

    public String getName() {
        return name;
    }

    public PlaceCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public int getRadius() {
        return radius;
    }

    public boolean isHidden() {
        return hidden;
    }

    /** Server thread: mirror the store's record. */
    void adopt(Place place) {
        if (place.id().equals(placeId) && place.name().equals(name) && place.category() == category
                && place.description().equals(description) && place.radius() == radius && place.hidden() == hidden) {
            return;
        }
        placeId = place.id();
        name = place.name();
        category = place.category();
        description = place.description();
        radius = place.radius();
        hidden = place.hidden();
        sync();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("PlaceId", placeId);
        nbt.putString("Name", name);
        nbt.putString("Category", category.id());
        nbt.putString("Description", description);
        nbt.putInt("Radius", radius);
        nbt.putBoolean("Hidden", hidden);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        placeId = nbt.getString("PlaceId");
        name = nbt.getString("Name");
        category = nbt.contains("Category") ? PlaceCategory.byId(nbt.getString("Category")) : PlaceCategory.LANDMARK;
        description = nbt.getString("Description");
        radius = nbt.getInt("Radius");
        hidden = nbt.getBoolean("Hidden");
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

    void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }
}
