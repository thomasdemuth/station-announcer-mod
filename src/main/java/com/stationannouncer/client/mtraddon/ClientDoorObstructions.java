package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Set;

/**
 * Which vehicles currently have something stuck in their doors, mirrored from
 * the server's {@code addon_door_obstructions} packet (join + whenever the set
 * changes) and cleared on disconnect. Deliberately minimal — the driving-HUD
 * work renders it; this is just the data.
 *
 * <p>Read on the client thread (HUD/render); one immutable set replaced
 * wholesale, so the common case costs a single isEmpty check.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientDoorObstructions {
    private static Set<Long> obstructed = Set.of();

    private ClientDoorObstructions() {
    }

    /** True while something is stuck in this vehicle's doors. */
    public static boolean isObstructed(long vehicleId) {
        return !obstructed.isEmpty() && obstructed.contains(vehicleId);
    }

    static void replace(Set<Long> newObstructed) {
        obstructed = newObstructed;
    }

    static void clear() {
        obstructed = Set.of();
    }
}
