package com.stationannouncer.mtr;

import net.minecraft.util.StringIdentifiable;

/**
 * Where a gap filler is in its cycle. Shared by the simulator-side sequencing
 * ({@link com.stationannouncer.mtraddon.GapFillerEngine}), the blockstate (so
 * the plate's collision follows it) and the renderer (so the plate slides).
 *
 * <p>One stop at a gap-filler platform runs RETRACTED → EXTENDING → EXTENDED
 * (doors open, riders cross, doors close) → RETRACTING → RETRACTED, and only
 * then may the train leave — the Union Square / South Ferry interlock.</p>
 */
public enum GapFillerPhase implements StringIdentifiable {
    RETRACTED("retracted"),
    EXTENDING("extending"),
    EXTENDED("extended"),
    RETRACTING("retracting");

    private final String id;

    GapFillerPhase(String id) {
        this.id = id;
    }

    @Override
    public String asString() {
        return id;
    }

    /** The plate is out (or on its way back) — it carries a rider. */
    public boolean plateSolid() {
        return this == EXTENDED || this == RETRACTING;
    }

    /** Where the plate is heading: true = out. */
    public boolean outward() {
        return this == EXTENDING || this == EXTENDED;
    }
}
