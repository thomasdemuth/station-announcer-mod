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

    // ------------------------------------------------- next-train tuning
    /** Arrow override values for {@link #arrowFor}: follow the platform's real bearing, or force a direction. */
    public static final int ARROW_AUTO = 0;
    public static final int ARROW_LEFT = 1;
    public static final int ARROW_RIGHT = 2;
    public static final int ARROW_DOWN = 3;

    public static final int DEFAULT_ON_SECONDS = 60;
    public static final int DEFAULT_OFF_SECONDS = 5;
    public static final int MIN_ON_SECONDS = 5;
    public static final int MAX_ON_SECONDS = 300;
    public static final int MAX_OFF_SECONDS = 120;
    public static final int MAX_ARROW_OVERRIDES = 8;

    /** Mini only: seconds before arrival the indicator lights / after arrival it goes dark. */
    private int nextTrainOnSeconds = DEFAULT_ON_SECONDS;
    private int nextTrainOffSeconds = DEFAULT_OFF_SECONDS;

    /**
     * Mini only: per-platform arrow overrides ({@code ARROW_*}), LEFT/RIGHT as
     * seen from the block's FRONT face — the renderer mirrors them on the back
     * face so both sides of the double-sided sign point at the same physical
     * track. Insertion-ordered so the GUI lists tracks stably.
     */
    private final java.util.LinkedHashMap<Long, Integer> nextTrainArrows = new java.util.LinkedHashMap<>();

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
        // Absent on minis placed before the timing was adjustable: the old constants.
        setNextTrainOnSeconds(compoundTag.contains("next_train_on")
                ? compoundTag.getInt("next_train_on") : DEFAULT_ON_SECONDS);
        setNextTrainOffSeconds(compoundTag.contains("next_train_off")
                ? compoundTag.getInt("next_train_off") : DEFAULT_OFF_SECONDS);
        // "id:dir,id:dir" — a string survives every NBT holder the mapping layer has.
        nextTrainArrows.clear();
        for (String pair : compoundTag.getString("next_train_arrows").split(",")) {
            int split = pair.indexOf(':');
            if (split <= 0) {
                continue;
            }
            try {
                putNextTrainArrow(Long.parseLong(pair.substring(0, split)),
                        Integer.parseInt(pair.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                // malformed entry (hand-edited NBT) — skip it, keep the rest
            }
        }
        controlBoxPos = compoundTag.contains("control_box")
                ? BlockPos.fromLong(compoundTag.getLong("control_box")) : null;
        liveMessage = compoundTag.getString("live_message");
        liveStart = compoundTag.getLong("live_start");
    }

    @Override
    public void writeCompoundTag(org.mtr.mapping.holder.CompoundTag compoundTag) {
        super.writeCompoundTag(compoundTag);
        compoundTag.putBoolean("next_train_mode", nextTrainMode);
        compoundTag.putInt("next_train_on", nextTrainOnSeconds);
        compoundTag.putInt("next_train_off", nextTrainOffSeconds);
        StringBuilder arrows = new StringBuilder();
        nextTrainArrows.forEach((platformId, dir) -> {
            if (arrows.length() > 0) {
                arrows.append(',');
            }
            arrows.append(platformId).append(':').append(dir);
        });
        compoundTag.putString("next_train_arrows", arrows.toString());
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

    /** Seconds before arrival the indicator lights up. */
    public int getNextTrainOnSeconds() {
        return nextTrainOnSeconds;
    }

    public void setNextTrainOnSeconds(int seconds) {
        this.nextTrainOnSeconds = Math.max(MIN_ON_SECONDS, Math.min(MAX_ON_SECONDS, seconds));
    }

    /** Seconds after arrival the indicator goes dark. */
    public int getNextTrainOffSeconds() {
        return nextTrainOffSeconds;
    }

    public void setNextTrainOffSeconds(int seconds) {
        this.nextTrainOffSeconds = Math.max(0, Math.min(MAX_OFF_SECONDS, seconds));
    }

    /** The arrow override for a platform ({@code ARROW_AUTO} when none is stored). */
    public int arrowFor(long platformId) {
        Integer dir = nextTrainArrows.get(platformId);
        return dir == null ? ARROW_AUTO : dir;
    }

    /** Every stored override, insertion-ordered (the GUI edits this map wholesale). */
    public java.util.Map<Long, Integer> getNextTrainArrows() {
        return java.util.Collections.unmodifiableMap(nextTrainArrows);
    }

    public void clearNextTrainArrows() {
        nextTrainArrows.clear();
    }

    /** Stores one override; AUTO entries are dropped rather than stored (absent = auto). */
    public void putNextTrainArrow(long platformId, int direction) {
        if (direction <= ARROW_AUTO || direction > ARROW_DOWN) {
            nextTrainArrows.remove(platformId);
        } else if (nextTrainArrows.containsKey(platformId)
                || nextTrainArrows.size() < MAX_ARROW_OVERRIDES) {
            nextTrainArrows.put(platformId, direction);
        }
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
