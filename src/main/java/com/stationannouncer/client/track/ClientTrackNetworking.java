package com.stationannouncer.client.track;

import com.stationannouncer.mtr.track.TrackJson;
import com.stationannouncer.mtr.track.TrackRailMathAccess;
import com.stationannouncer.mtr.track.TrackRailMathState;
import com.stationannouncer.mtr.track.TrackService;
import com.stationannouncer.mtr.track.TrackSide;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import org.mtr.core.data.RailMath;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side of custom track geometry: the editor's save/reset calls and the
 * {@code station_announcer:track_sync} receiver (format in {@link TrackService}).
 *
 * <p>Applying a sync only touches {@link TrackRegistry}'s CLIENT map: every client {@code RailMath}
 * re-resolves lazily when the version moves, so no rail is rebuilt. The one client-side cache of
 * rail geometry MTR keeps is {@code MinecraftClientData.RailWrapper}'s culling box
 * ({@code startVector}/{@code endVector}, copied from the RailMath bounds when the wrapper is first
 * created and NEVER refreshed by MTR, not even when {@code sync()} swaps in a re-sent rail); the
 * client tick re-copies them whenever the registry version changed, so a reshaped rail is culled
 * by its new bounds.</p>
 */
public final class ClientTrackNetworking {
    private static long lastBoundsVersion = Long.MIN_VALUE;
    /** key → wall-clock deadline: previews kept after Save until the server's sync lands. Client thread only. */
    private static final Map<String, Long> HELD_PREVIEWS = new HashMap<>();

    private ClientTrackNetworking() {
    }

    /** True when {@link #sendSave} would send this profile (small enough for one packet). */
    public static boolean canSave(TrackProfile profile) {
        return TrackJson.toJson(profile).length() <= TrackService.MAX_SAVE_JSON;
    }

    /**
     * Keeps the editor's preview of {@code key} on screen until the server's sync for it arrives (or
     * ~5 s pass), so a saved rail does not flash back to its old shape for a few ticks.
     */
    public static void holdPreviewUntilSync(String key) {
        HELD_PREVIEWS.put(key, System.currentTimeMillis() + 5000);
    }

    public static boolean isPreviewHeld(String key) {
        return HELD_PREVIEWS.containsKey(key);
    }

    private static void releaseHeld(java.util.Collection<String> keys) {
        for (final String key : keys) {
            if (HELD_PREVIEWS.remove(key) != null) {
                TrackRegistry.clearPreview(key);
            }
        }
    }

    /** Save (create or replace) a custom profile. The server re-validates it (must close). Returns false if nothing was sent. */
    public static boolean sendSave(TrackProfile profile) {
        final String json = TrackJson.toJson(profile);
        if (json.length() > TrackService.MAX_SAVE_JSON) {
            return false;
        }
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(json, TrackService.MAX_SAVE_JSON);
        ClientPlayNetworking.send(TrackService.SAVE_C2S, buf);
        return true;
    }

    /** Drop the custom profile: the rail goes back to MTR's own shape. */
    public static void sendReset(String key) {
        if (key == null || key.length() > TrackService.MAX_KEY) {
            return;
        }
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(key, TrackService.MAX_KEY);
        ClientPlayNetworking.send(TrackService.RESET_C2S, buf);
    }

    /** Registers the S2C receivers (called once from the client init — on the client thread). */
    public static void register() {
        TrackSide.markClientThread();
        ClientPlayNetworking.registerGlobalReceiver(TrackService.SYNC_S2C, (client, handler, buf, responseSender) -> {
            final byte op = buf.readByte();
            final int count = buf.readVarInt();
            if (count < 0 || count > 100_000) {
                return;
            }
            final Map<String, TrackProfile> profiles = new HashMap<>();
            final List<String> removed = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final String key = buf.readString(TrackService.MAX_KEY);
                if (op == TrackService.OP_REMOVE) {
                    removed.add(key);
                } else {
                    final TrackProfile profile = TrackJson.parse(buf.readString(TrackService.MAX_SYNC_JSON));
                    if (profile != null && profile.key().equals(key)) {
                        profiles.put(key, profile);
                    }
                }
            }
            client.execute(() -> {
                switch (op) {
                    case TrackService.OP_SET -> TrackRegistry.setClient(profiles);
                    case TrackService.OP_PUT -> profiles.values().forEach(TrackRegistry::putClient);
                    case TrackService.OP_REMOVE -> removed.forEach(TrackRegistry::removeClient);
                    default -> {
                    }
                }
                releaseHeld(op == TrackService.OP_REMOVE ? removed : profiles.keySet());
            });
        });
        // fired on the client thread while the connection is torn down — clear right away (a deferred
        // clear could land after a quick reconnect's first sync)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HELD_PREVIEWS.clear();
            TrackRegistry.clearClient();
            TrackRailMathState.clearCache(true);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            TrackSide.markClientThread();
            if (!HELD_PREVIEWS.isEmpty()) {
                final long now = System.currentTimeMillis();
                final List<String> expired = new ArrayList<>();
                HELD_PREVIEWS.forEach((key, deadline) -> {
                    if (now > deadline) {
                        expired.add(key);
                    }
                });
                releaseHeld(expired);
            }
            final long version = TrackRegistry.version();
            if (version != lastBoundsVersion) {
                lastBoundsVersion = version;
                refreshCullingBoxes();
            }
        });
    }

    /** Copies every rail's (possibly custom) bounds into MTR's culling wrappers. Client thread. */
    private static void refreshCullingBoxes() {
        try {
            for (final MinecraftClientData.RailWrapper wrapper : MinecraftClientData.getInstance().railWrapperList.values()) {
                final RailMath math = wrapper.getRail().railMath;
                ((TrackRailMathAccess) math).stationannouncer$trackPath(); // resolve → bounds up to date
                wrapper.startVector.set(math.minX, math.minY, math.minZ);
                wrapper.endVector.set(math.maxX, math.maxY, math.maxZ);
            }
        } catch (RuntimeException | LinkageError e) {
            // MTR internals changed: culling falls back to MTR's own (possibly stale) boxes
        }
    }
}
