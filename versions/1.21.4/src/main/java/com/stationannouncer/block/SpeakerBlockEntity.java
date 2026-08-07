package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A wall speaker in a PA network. Holds only playback properties (volume,
 * radius) and the position of the PA Control Box it is linked to; the control
 * box does all triggering. The link position is plain NBT so structure
 * blocks/Litematica/Create schematics preserve it — on the first ticks after
 * being placed from NBT the speaker re-registers itself with the control box
 * at the stored position (once that chunk is loaded), and if no control box
 * is there the GUI shows "link broken" instead of crashing.
 */
public class SpeakerBlockEntity extends BlockEntity {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 128;

    /** Link state as shown in the GUI (synced, computed server-side). */
    public static final byte LINK_NONE = 0;
    public static final byte LINK_OK = 1;
    public static final byte LINK_BROKEN = 2;
    public static final byte LINK_NOT_LOADED = 3;

    private int volume = 100; // 0..100 (%)
    private int radius = 16;  // 1..128 blocks
    @Nullable
    private BlockPos controlBoxPos;

    /** Whether the stored link still needs re-registration with its control box. Not persisted. */
    private boolean validated = false;

    /** Last link state received from the server (client side only). */
    private byte clientLinkState = LINK_NONE;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.SPEAKER_BLOCK_ENTITY, pos, state);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("Volume", volume);
        nbt.putInt("Radius", radius);
        if (controlBoxPos != null) {
            nbt.putLong("ControlBox", controlBoxPos.asLong());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setVolume(nbt.getInt("Volume"));
        setRadius(nbt.getInt("Radius"));
        controlBoxPos = nbt.contains("ControlBox") ? BlockPos.fromLong(nbt.getLong("ControlBox")) : null;
        // NBT may arrive on a live block entity too (/data merge, structure tools):
        // re-arm the one-shot validation so the control box learns about us.
        validated = false;
        if (nbt.contains("LinkState")) {
            clientLinkState = nbt.getByte("LinkState"); // present only in sync packets
        }
    }

    // ----------------------------------------------------------- client sync

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        NbtCompound nbt = createNbt();
        nbt.putByte("LinkState", computeLinkState());
        return nbt;
    }

    public void sync() {
        markDirty();
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(pos);
        }
    }

    /** Server-side view of the link's health (what the GUI displays). */
    private byte computeLinkState() {
        if (controlBoxPos == null) {
            return LINK_NONE;
        }
        if (world == null || !world.isChunkLoaded(controlBoxPos)) {
            return LINK_NOT_LOADED;
        }
        return world.getBlockEntity(controlBoxPos) instanceof ControlBoxBlockEntity ? LINK_OK : LINK_BROKEN;
    }

    // -------------------------------------------------------------- ticking

    /**
     * Validates the stored link once after load/placement-from-NBT: when the
     * control box's chunk is available, re-register with it (structure-paste
     * support). Cheap no-op afterwards.
     */
    public static void serverTick(World world, BlockPos pos, BlockState state, SpeakerBlockEntity be) {
        if (be.validated) {
            return;
        }
        if (be.controlBoxPos == null) {
            be.validated = true;
            return;
        }
        if (!world.isChunkLoaded(be.controlBoxPos)) {
            return; // try again when the box's chunk is around; never force-load
        }
        be.validated = true;
        if (world.getBlockEntity(be.controlBoxPos) instanceof ControlBoxBlockEntity box) {
            if (box.addSpeaker(pos)) {
                box.sync();
            }
        }
        // No control box there: keep the stored position, the GUI shows "link broken".
    }

    // ------------------------------------------------------------ accessors

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = MathHelper.clamp(volume, 0, 100);
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = MathHelper.clamp(radius, MIN_RADIUS, MAX_RADIUS);
    }

    @Nullable
    public BlockPos getControlBoxPos() {
        return controlBoxPos;
    }

    public void setControlBoxPos(@Nullable BlockPos controlBoxPos) {
        this.controlBoxPos = controlBoxPos == null ? null : controlBoxPos.toImmutable();
        this.validated = true; // explicit links need no re-validation
        sync();
    }

    public void clearControlBox() {
        setControlBoxPos(null);
    }

    /** Client-side: link state as last synced from the server. */
    public byte getClientLinkState() {
        return clientLinkState;
    }

    /** Applies a settings update (from the GUI packet), persists and syncs. */
    public void applySettings(int volume, int radius) {
        setVolume(volume);
        setRadius(radius);
        sync();
    }
}
