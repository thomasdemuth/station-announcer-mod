package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.LiftDoorSides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Map;

/**
 * Client-side mirror of the server's lift door-side configs (Feature 3),
 * replaced wholesale by the {@code addon_lift_doors} S2C packet (join + after
 * every change) and cleared on disconnect. Read every frame by the lift render
 * mixin/{@link AddonRenderLifts}, which runs on the render thread — the same
 * thread {@code client.execute} delivers the replacement on, so a plain
 * immutable-map swap is safe with no locking.
 *
 * <p>{@link #isEmpty()} is the render mixin's O(1) bail: while no lift is
 * configured (or the server feature is disabled, which syncs an empty map),
 * MTR's stock {@code RenderLifts.render} runs untouched.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientLiftDoors {
    private static Map<Long, LiftDoorSides> doors = Map.of();

    private ClientLiftDoors() {
    }

    /** The configured sides for a lift, or {@code null} for stock MTR behavior. */
    public static LiftDoorSides get(long liftId) {
        return doors.get(liftId);
    }

    public static boolean isEmpty() {
        return doors.isEmpty();
    }

    static void replace(Map<Long, LiftDoorSides> newDoors) {
        doors = newDoors;
    }

    static void clear() {
        doors = Map.of();
    }
}
