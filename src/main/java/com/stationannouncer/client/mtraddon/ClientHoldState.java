package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Set;

/**
 * Which platforms are holding a train right now, mirrored from the server's
 * {@code addon_hold_state} packet (join + whenever the set changes) and cleared
 * on disconnect.
 *
 * <p>Read from the block-entity renderer, i.e. per frame per holding light, so
 * the whole thing is one immutable set replaced wholesale on the client thread —
 * a {@code contains} on an empty set is what the common case costs.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientHoldState {
    private static Set<Long> held = Set.of();

    private ClientHoldState() {
    }

    /** True while a hold rule is keeping a train at this platform. */
    public static boolean isHeld(long platformId) {
        return !held.isEmpty() && held.contains(platformId);
    }

    static void replace(Set<Long> newHeld) {
        held = newHeld;
    }

    static void clear() {
        held = Set.of();
    }
}
