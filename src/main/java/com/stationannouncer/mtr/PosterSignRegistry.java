package com.stationannouncer.mtr;

import com.stationannouncer.mtraddon.disruption.ServicePoster;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-side weak set of every loaded {@link ServicePosterBlockEntity}, so a
 * poster edit can refresh the snapshot on every frame currently hanging it
 * (the {@code AnnouncerRegistry} pattern). Unloaded frames catch up the next
 * time the poster is re-assigned — or never, which is the point of the
 * snapshot: a hung poster stays readable regardless.
 *
 * <p>Server thread only.</p>
 */
public final class PosterSignRegistry {
    private static final Set<ServicePosterBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private PosterSignRegistry() {
    }

    public static void add(ServicePosterBlockEntity sign) {
        LOADED.add(sign);
    }

    public static void remove(ServicePosterBlockEntity sign) {
        LOADED.remove(sign);
    }

    /** Push an edited poster into every loaded frame that hangs it. */
    public static void refresh(ServicePoster poster) {
        List<ServicePosterBlockEntity> hanging = new ArrayList<>();
        for (ServicePosterBlockEntity sign : LOADED) {
            if (sign != null && !sign.isRemoved() && sign.getPosterId() == poster.id()) {
                hanging.add(sign);
            }
        }
        for (ServicePosterBlockEntity sign : hanging) {
            sign.setPoster(poster.id(), poster);
            sign.sync();
        }
    }
}
