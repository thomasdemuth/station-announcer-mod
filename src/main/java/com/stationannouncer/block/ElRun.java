package com.stationannouncer.block;

import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;

/**
 * Direction of a run of el structure (girders, decks): the two grid axes
 * plus the two diagonals, so curved track can be carried at 45 degrees.
 * The property is still called "axis" and keeps the values x / z, so cells
 * placed before the diagonals existed load unchanged.
 */
public enum ElRun implements StringIdentifiable {
    X("x", new Vec3i(1, 0, 0)),
    Z("z", new Vec3i(0, 0, 1)),
    /** South-east: the run steps +x +z. */
    XZ("xz", new Vec3i(1, 0, 1)),
    /** North-east: the run steps +x -z. */
    ZX("zx", new Vec3i(1, 0, -1));

    private final String name;
    private final Vec3i step;

    ElRun(String name, Vec3i step) {
        this.name = name;
        this.step = step;
    }

    @Override
    public String asString() {
        return name;
    }

    /** One step along the run toward its positive end. */
    public Vec3i step() {
        return step;
    }

    /** One step toward the negative end. */
    public Vec3i back() {
        return new Vec3i(-step.getX(), 0, -step.getZ());
    }

    public boolean diagonal() {
        return this == XZ || this == ZX;
    }

    /** The run at right angles to this one. */
    public ElRun across() {
        switch (this) {
            case X: return Z;
            case Z: return X;
            case XZ: return ZX;
            default: return XZ;
        }
    }

    /** The run a player is looking along (eight sectors of yaw). */
    public static ElRun fromYaw(float yaw) {
        // yaw 0 = south (+z), 90 = west (-x), -90 = east (+x)
        int sector = MathHelper.floor((MathHelper.wrapDegrees(yaw) + 22.5f + 360f) / 45f) & 7;
        switch (sector) {
            case 0: case 4: return Z;      // south / north
            case 2: case 6: return X;      // west / east
            case 1: case 5: return ZX;     // south-west / north-east: steps (-1,+1) = ZX
            default: return XZ;            // north-west / south-east
        }
    }

    /** The run closest to a horizontal direction (dx, dz). */
    public static ElRun fromDirection(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx)); // 0 = +x, 90 = +z
        int sector = MathHelper.floor((angle + 22.5 + 360) / 45) & 7;
        switch (sector) {
            case 0: case 4: return X;
            case 2: case 6: return Z;
            case 1: case 5: return XZ;     // +x +z
            default: return ZX;            // +x -z
        }
    }
}
