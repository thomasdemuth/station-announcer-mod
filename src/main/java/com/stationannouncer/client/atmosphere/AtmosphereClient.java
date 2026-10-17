package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.Atmosphere;
import com.stationannouncer.atmosphere.LightConditions;
import com.stationannouncer.atmosphere.LightLuminance;
import com.stationannouncer.atmosphere.OutOfService;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Client side of the atmosphere layer: the synced light conditions (also
 * handed to the client light engine) and out-of-service equipment, the
 * renderer, the maintenance screen, and each family's render layers.
 */
@Environment(EnvType.CLIENT)
public final class AtmosphereClient {
    /** pos → packed condition, for the world this client shows. */
    static final Long2IntOpenHashMap LIGHTS = new Long2IntOpenHashMap();
    static final LongOpenHashSet OUT_OF_SERVICE = new LongOpenHashSet();

    private AtmosphereClient() {
    }

    public static void register() {
        LIGHTS.defaultReturnValue(0);
        ClientPlayNetworking.registerGlobalReceiver(LightConditions.SYNC_S2C, (client, handler, buf, sender) -> {
            boolean full = buf.readBoolean();
            int n = buf.readVarInt();
            long[] pos = new long[n];
            int[] val = new int[n];
            for (int i = 0; i < n; i++) {
                pos[i] = buf.readLong();
                val[i] = buf.readVarInt();
            }
            client.execute(() -> applyLights(client, full, pos, val));
        });
        ClientPlayNetworking.registerGlobalReceiver(OutOfService.SYNC_S2C, (client, handler, buf, sender) -> {
            boolean full = buf.readBoolean();
            int n = buf.readVarInt();
            long[] pos = new long[n];
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) {
                pos[i] = buf.readLong();
                out[i] = buf.readBoolean();
            }
            client.execute(() -> {
                if (full) {
                    OUT_OF_SERVICE.clear();
                }
                for (int i = 0; i < n; i++) {
                    if (out[i]) {
                        OUT_OF_SERVICE.add(pos[i]);
                    } else {
                        OUT_OF_SERVICE.remove(pos[i]);
                    }
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            LIGHTS.clear();
            OUT_OF_SERVICE.clear();
            LightLuminance.publishClient(LIGHTS);
        }));
        Atmosphere.MAINTENANCE_SCREEN = hand -> MinecraftClient.getInstance().setScreen(new MaintenanceScreen(hand));
        Atmosphere.WEATHERING_SCREEN = hand -> MinecraftClient.getInstance().setScreen(new WeatheringScreen(hand));
        AtmosphereRenderer.register();
        AtmosphereFamiliesClient.register();
    }

    /** Dev-only headless hooks (run/commands.txt). */
    public static void devHook(MinecraftClient client, String cmd) {
        switch (cmd.trim()) {
            case "#atmo-maint" -> client.setScreen(new MaintenanceScreen(net.minecraft.util.Hand.MAIN_HAND));
            case "#atmo-weather" -> client.setScreen(new WeatheringScreen(net.minecraft.util.Hand.MAIN_HAND));
            case "#atmo-close" -> client.setScreen(null);
            default -> {
            }
        }
    }

    private static void applyLights(MinecraftClient client, boolean full, long[] pos, int[] val) {
        if (full) {
            LIGHTS.clear();
        }
        for (int i = 0; i < pos.length; i++) {
            if (val[i] == 0) {
                LIGHTS.remove(pos[i]);
            } else {
                LIGHTS.put(pos[i], val[i]);
            }
        }
        LightLuminance.publishClient(LIGHTS);
        ClientWorld world = client.world;
        if (world != null && !full) {
            // The server relights too and sends the result; checking here keeps
            // the client engine consistent if the client recomputes on its own.
            for (long p : pos) {
                world.getChunkManager().getLightingProvider().checkBlock(BlockPos.fromLong(p));
            }
        }
    }
}
