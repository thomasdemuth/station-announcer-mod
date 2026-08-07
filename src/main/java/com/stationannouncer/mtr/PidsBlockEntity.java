package com.stationannouncer.mtr;

import com.stationannouncer.block.PaDisplay;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.mtr.mod.block.BlockPIDSBase;

/**
 * Shared block entity for all NYC PIDS variants. All configuration state
 * (platform ids, per-row messages) lives in MTR's {@link BlockEntityBase} and
 * is edited through MTR's own PIDS config screen via the brush. Implements
 * {@link PaDisplay} so a Speaker Link can attach the screen to a PA Control
 * Box: fired announcements scroll across the hanging clock's bottom row, and
 * the box's message pool feeds the wall/standing "Happening now" section.
 */
public class PidsBlockEntity extends BlockPIDSBase.BlockEntityBase implements PaDisplay {
    public final PidsStyle style;

    /** Mini only: display a "Next train" indicator instead of the countdown row. */
    private boolean nextTrainMode = false;

    /** Linked PA Control Box, or null (persisted, synced for the renderer). */
    @Nullable
    private BlockPos controlBoxPos;

    /** Last announcement fired by the linked box, with its start time (epoch ms). */
    private String liveMessage = "";
    private long liveStart;

    public PidsBlockEntity(PidsStyle style, org.mtr.mapping.holder.BlockPos pos, org.mtr.mapping.holder.BlockState state) {
        super(style.maxArrivals, (world, blockPos) -> BlockPidsNyc.isPrimary(world, blockPos),
                BlockPidsNyc::primaryPos,
                new org.mtr.mapping.holder.BlockEntityType<>(MtrPids.PIDS_BLOCK_ENTITY), pos, state);
        this.style = style;
    }

    @Override
    public void readCompoundTag(org.mtr.mapping.holder.CompoundTag compoundTag) {
        super.readCompoundTag(compoundTag);
        nextTrainMode = compoundTag.getBoolean("next_train_mode");
        controlBoxPos = compoundTag.contains("control_box")
                ? BlockPos.fromLong(compoundTag.getLong("control_box")) : null;
        liveMessage = compoundTag.getString("live_message");
        liveStart = compoundTag.getLong("live_start");
    }

    @Override
    public void writeCompoundTag(org.mtr.mapping.holder.CompoundTag compoundTag) {
        super.writeCompoundTag(compoundTag);
        compoundTag.putBoolean("next_train_mode", nextTrainMode);
        if (controlBoxPos != null) {
            compoundTag.putLong("control_box", controlBoxPos.asLong());
        }
        compoundTag.putString("live_message", liveMessage);
        compoundTag.putLong("live_start", liveStart);
    }

    // ------------------------------------------------------ PaDisplay (link)

    @Nullable
    @Override
    public BlockPos getPaControlBoxPos() {
        return controlBoxPos;
    }

    @Override
    public void setPaControlBoxPos(@Nullable BlockPos pos) {
        this.controlBoxPos = pos == null ? null : pos.toImmutable();
        markDirty();
    }

    @Override
    public void showPaAnnouncement(String message) {
        this.liveMessage = message == null ? "" : message;
        this.liveStart = System.currentTimeMillis();
        markDirty();
    }

    /** Client-side: the announcement to scroll ("" = none). */
    public String getLiveMessage() {
        return liveMessage;
    }

    /** Client-side: when the live announcement started (epoch millis, server clock). */
    public long getLiveStart() {
        return liveStart;
    }

    public boolean isNextTrainMode() {
        return nextTrainMode;
    }

    public void setNextTrainMode(boolean nextTrainMode) {
        this.nextTrainMode = nextTrainMode;
    }

    /** Joins all non-empty config message rows into the "Happening now" text. */
    public String getCustomMessage() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < maxArrivals; i++) {
            String message = getMessage(i);
            if (message != null && !message.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" • ");
                }
                builder.append(message.trim());
            }
        }
        return builder.toString();
    }

    @Override
    public boolean showArrivalNumber() {
        return true;
    }

    @Override
    public boolean alternateLines() {
        return false;
    }

    @Override
    public int textColorArrived() {
        return 0xFFFFFF;
    }

    @Override
    public int textColor() {
        return 0xFFFFFF;
    }
}
