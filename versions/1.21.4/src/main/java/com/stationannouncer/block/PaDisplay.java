package com.stationannouncer.block;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A block entity that can be linked to a PA Control Box as a visual display
 * (the NYC PIDS screens). Implemented in the optional MTR module; this
 * interface keeps the common code (Speaker Link item, control box, link line
 * renderer) free of any MTR class references.
 *
 * <p>Implementations only mutate their own state and {@code markDirty()};
 * callers are responsible for pushing the sync packet (markForUpdate).
 */
public interface PaDisplay {
    /** The linked control box position, or null when unlinked. Persisted by the display. */
    @Nullable
    BlockPos getPaControlBoxPos();

    void setPaControlBoxPos(@Nullable BlockPos pos);

    /** Server-side: the control box just fired this message — show it live. */
    void showPaAnnouncement(String message);

    /** Removes a broken display from its control box's list (call server-side on block break). */
    static void handleBroken(World world, BlockPos displayPos) {
        if (world.getBlockEntity(displayPos) instanceof PaDisplay display) {
            BlockPos boxPos = display.getPaControlBoxPos();
            if (boxPos != null && world.isChunkLoaded(boxPos)
                    && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box
                    && box.removeDisplay(displayPos)) {
                box.sync();
            }
        }
    }
}
