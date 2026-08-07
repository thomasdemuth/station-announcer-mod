package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.List;
import java.util.Map;

/**
 * Client-side mirror of the server's hold rules, replaced wholesale by the
 * {@code addon_hold_rules} S2C packet (join + after every change) and cleared on
 * disconnect. Read on the client thread only (GUIs, future HUD elements).
 */
@Environment(EnvType.CLIENT)
public final class ClientHoldRules {
    public record Rule(List<Long> watched, int seconds) {
    }

    private static Map<Long, Rule> rules = Map.of();

    private ClientHoldRules() {
    }

    public static Rule get(long platformId) {
        return rules.get(platformId);
    }

    static void replace(Map<Long, Rule> newRules) {
        rules = newRules;
    }

    static void clear() {
        rules = Map.of();
    }
}
