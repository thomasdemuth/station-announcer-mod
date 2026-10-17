package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.mtraddon.crew.CabFeed;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jetbrains.annotations.Nullable;

/**
 * The latest server cab feed ({@link CabFeed}: the line ahead + the claimed slot). The client
 * cannot see far down a manual train's path itself — MTR only syncs it up to the stopping point —
 * so {@link CabData} prefers this when it is fresh. Client thread.
 */
@Environment(EnvType.CLIENT)
public final class CabFeedClient {
    private static final long FRESH_MILLIS = 3_000;
    @Nullable
    private static CabFeed latest;
    private static long receivedAt;

    private CabFeedClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.FEED_S2C, (client, handler, buf, sender) -> {
            CabFeed feed = CabFeed.read(buf);
            client.execute(() -> {
                latest = feed;
                receivedAt = System.currentTimeMillis();
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> latest = null);
    }

    /** The feed for this vehicle if one arrived in the last few seconds, else null. */
    @Nullable
    static CabFeed forVehicle(long vehicleId, long wallNow) {
        CabFeed feed = latest;
        return feed != null && feed.vehicleId() == vehicleId && wallNow - receivedAt < FRESH_MILLIS ? feed : null;
    }

    /** Age of the latest feed (distances in it were measured that long ago). */
    static long ageMillis(long wallNow) {
        return wallNow - receivedAt;
    }
}
