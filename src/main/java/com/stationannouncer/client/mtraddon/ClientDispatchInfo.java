package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Client mirror of the dispatch web UI's location, synced on join
 * ({@code addon_dispatch_info}). Holds the GAME SERVER's MTR webserver port —
 * not the client-side proxy port MTR's own map button uses, because the
 * dispatch servlets only exist on the server's Jetty instance.
 */
@Environment(EnvType.CLIENT)
public final class ClientDispatchInfo {

    private static volatile int port = -1;

    private ClientDispatchInfo() {
    }

    public static void setPort(int newPort) {
        port = newPort;
    }

    public static void clear() {
        port = -1;
    }

    public static boolean isAvailable() {
        return port > 0;
    }

    /**
     * The dispatch UI's URL for the server this client is connected to:
     * the connected address's host (localhost in singleplayer) + the synced port.
     * IPv6 literal addresses are passed through untouched apart from a port strip.
     */
    public static String url() {
        String host = "localhost";
        ServerInfo serverInfo = MinecraftClient.getInstance().getCurrentServerEntry();
        if (serverInfo != null && serverInfo.address != null && !serverInfo.address.isEmpty()) {
            host = serverInfo.address;
            int bracket = host.lastIndexOf(']');
            int colon = host.lastIndexOf(':');
            if (colon > bracket) {
                host = host.substring(0, colon);
            }
        }
        return "http://" + host + ":" + port + "/dispatch/";
    }
}
