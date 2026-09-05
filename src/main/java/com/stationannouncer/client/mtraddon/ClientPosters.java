package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.ArrayList;
import java.util.List;

/**
 * Client mirror of the server's service change posters, replaced wholesale by
 * the {@code addon_posters} sync on join and after every edit, cleared on
 * disconnect. Read on the client thread by the poster screens and by the
 * poster-frame renderer (which prefers this live copy over its block entity's
 * snapshot, so an edit shows on every hung frame immediately).
 */
@Environment(EnvType.CLIENT)
public final class ClientPosters {
    private static List<ServicePoster> posters = List.of();

    private ClientPosters() {
    }

    public static void replace(List<ServicePoster> newPosters) {
        List<ServicePoster> sorted = new ArrayList<>(newPosters);
        sorted.sort((a, b) -> Long.compare(a.id(), b.id()));
        posters = List.copyOf(sorted);
    }

    public static void clear() {
        posters = List.of();
    }

    public static List<ServicePoster> all() {
        return posters;
    }

    public static ServicePoster byId(long id) {
        if (id == 0) {
            return null;
        }
        for (ServicePoster poster : posters) {
            if (poster.id() == id) {
                return poster;
            }
        }
        return null;
    }

    public static List<ServicePoster> forDisruption(long disruptionId) {
        List<ServicePoster> result = new ArrayList<>();
        for (ServicePoster poster : posters) {
            if (poster.disruptionId() == disruptionId) {
                result.add(poster);
            }
        }
        return result;
    }
}
