package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.PlatformOccupancy;
import com.stationannouncer.mtraddon.PlatformSchedule;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The last {@code addon_platform_schedule} reply per platform: the sidings that stop at one platform,
 * with their departures and baked stop times ({@link PlatformSchedule}). Requested by the
 * platform screen when it opens (and every few seconds while it stays open); read on the
 * client thread only.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatformSchedule {
    public record Data(long platformId, long simMillis, long receivedAt, long day, String note,
                       List<PlatformOccupancy.Siding> sidings) {
        /** Simulation millis now, extrapolated from the reply (the simulation runs in real time). */
        public long simNow() {
            return simMillis + (System.currentTimeMillis() - receivedAt);
        }
    }

    private static final java.util.Map<Long, Data> LATEST = new java.util.HashMap<>();
    private static int version;

    private ClientPlatformSchedule() {
    }

    public static void request(long platformId) {
        request(List.of(platformId));
    }

    /** One request for several platforms (the server answers one packet each). */
    public static void request(List<Long> platformIds) {
        if (platformIds.isEmpty()) {
            return;
        }
        int count = Math.min(PlatformSchedule.MAX_REQUEST, platformIds.size());
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(platformIds.get(i));
        }
        ClientPlayNetworking.send(PlatformSchedule.REQUEST_C2S, buf);
    }

    /** The latest reply for this platform, or null. */
    public static Data get(long platformId) {
        return LATEST.get(platformId);
    }

    /** Bumped on every reply, so a screen can recompute only when new data came in. */
    public static int version() {
        return version;
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(PlatformSchedule.REPLY_S2C, (client, handler, buf, sender) -> {
            long platformId = buf.readLong();
            long simMillis = buf.readLong();
            long day = buf.readVarLong();
            String note = buf.readString(PlatformSchedule.MAX_NOTE);
            int count = buf.readVarInt();
            if (count < 0 || count > PlatformSchedule.MAX_SIDINGS) {
                return;
            }
            List<PlatformOccupancy.Siding> sidings = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long depotId = buf.readLong();
                long sidingId = buf.readLong();
                String depotName = buf.readString(64);
                long repeat = buf.readVarLong();
                int phaseCount = buf.readVarInt();
                if (phaseCount < 0 || phaseCount > PlatformSchedule.MAX_DEPARTURES) {
                    return;
                }
                long[] phases = new long[phaseCount];
                long previous = 0;
                for (int j = 0; j < phaseCount; j++) {
                    previous += buf.readVarLong();
                    phases[j] = previous;
                }
                int stopCount = buf.readVarInt();
                if (stopCount < 0 || stopCount > PlatformSchedule.MAX_STOPS) {
                    return;
                }
                PlatformOccupancy.Stop[] stops = new PlatformOccupancy.Stop[stopCount];
                for (int j = 0; j < stopCount; j++) {
                    stops[j] = new PlatformOccupancy.Stop(buf.readLong(), buf.readVarInt(), buf.readVarLong(), buf.readVarLong());
                }
                sidings.add(new PlatformOccupancy.Siding(depotId, sidingId, depotName, repeat, phases, stops));
            }
            Data data = new Data(platformId, simMillis, System.currentTimeMillis(), day, note, List.copyOf(sidings));
            client.execute(() -> {
                LATEST.put(platformId, data);
                version++;
            });
        });
    }

    static void clear() {
        LATEST.clear();
        version++;
    }
}
