package com.stationannouncer.mtraddon;

/**
 * Feature 3 — which sides of a lift cab have doors, in CAB space (before the
 * lift's world rotation is applied): {@code front} is MTR's {@code -Z} (the side
 * the stock single door faces), {@code back} is {@code +Z} (MTR's
 * {@code isDoubleSided} door), {@code left}/{@code right} are {@code -X}/{@code +X}.
 *
 * <p>Shared by the server store, the sync packets (as a 4-bit mask) and the
 * client mirror. Immutable, so instances are safe to hand across threads.</p>
 */
public record LiftDoorSides(boolean front, boolean back, boolean left, boolean right) {
    public static final int MASK_FRONT = 1;
    public static final int MASK_BACK = 2;
    public static final int MASK_LEFT = 4;
    public static final int MASK_RIGHT = 8;

    /** Packs into the low 4 bits for the wire format and the JSON-independent paths. */
    public int mask() {
        return (front ? MASK_FRONT : 0) | (back ? MASK_BACK : 0) | (left ? MASK_LEFT : 0) | (right ? MASK_RIGHT : 0);
    }

    public static LiftDoorSides fromMask(int mask) {
        return new LiftDoorSides((mask & MASK_FRONT) != 0, (mask & MASK_BACK) != 0, (mask & MASK_LEFT) != 0, (mask & MASK_RIGHT) != 0);
    }

    /** An all-off config is meaningless (an unenterable box); callers treat it as "unconfigured". */
    public boolean any() {
        return front || back || left || right;
    }
}
