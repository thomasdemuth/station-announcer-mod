package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Map;

/**
 * Client-side mirror of the server's per-route announcement templates, replaced
 * wholesale by the {@code addon_announcement_templates} S2C packet (join + after
 * every change) and cleared on disconnect. Read on the client thread only — by
 * the on-board announcement mixin at announce time and by the template editor.
 */
@Environment(EnvType.CLIENT)
public final class ClientAnnouncementTemplates {
    /** route id → template; immutable snapshot. */
    private static Map<Long, String> templates = Map.of();

    private ClientAnnouncementTemplates() {
    }

    /** The route's template, or null when it uses MTR's stock announcement. */
    public static String get(long routeId) {
        return templates.get(routeId);
    }

    static void replace(Map<Long, String> newTemplates) {
        templates = newTemplates;
    }

    static void clear() {
        templates = Map.of();
    }
}
