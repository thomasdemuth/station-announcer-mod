package com.stationannouncer.mtr.track;

/**
 * Which side (client or server) a {@code RailMath} belongs to, decided ONCE, on the thread that
 * constructs it.
 *
 * <h2>Why thread identity works (verified against the MTR 4.0.1 sources)</h2>
 * Single-player runs the server simulator and the client in one JVM, and {@code Rail}/
 * {@code RailMath} objects are built in many places. Every client-side construction runs on the
 * Minecraft CLIENT (render) thread:
 * <ul>
 *   <li>client data sync: MTR's packets ({@code PacketUpdateData}, {@code PacketRequestData},
 *       {@code PacketUpdateVehiclesLifts}, …) are handed by {@code RegistryClient} to
 *       {@code MinecraftClient::execute}, so {@code runClient → UpdateDataResponse/DataResponse}
 *       deserialises rails on the client thread; so does {@code PathData.writePathCache}'s
 *       {@code defaultRail()} for client-side vehicle paths;</li>
 *   <li>rail previews while placing ({@code RenderRails.render → ItemRailModifier.createRail}),
 *       brush style previews ({@code PacketUpdateLastRailStyles.CLIENT_CACHE}) and
 *       {@code RailModifierScreen}'s {@code Rail.copy}: render thread.</li>
 * </ul>
 * Every server-side construction runs on some OTHER thread: the simulator's {@code FileLoader}
 * worker threads and the TSC simulator thread(s) (load, {@code OperationProcessor} re-deserialises
 * every {@code update_data} request there), or — with threaded simulation off — the server thread;
 * {@code PacketUpdateData.runServerOutbound} builds a throw-away request copy on the server thread.
 * None of those is the client thread (the integrated server has its own "Server thread").
 *
 * <p>So: client ⇔ the constructing thread is the client thread. The client thread is recorded by
 * {@code ClientTrackNetworking.register()} (client init runs on it) and re-confirmed every client
 * tick. On a dedicated server it stays null → everything is server-side.</p>
 *
 * <p>Weak spot: a client-side rail built off the client thread (none in MTR 4.0.1; another mod
 * could) would read the SERVER registry — in multiplayer that is empty on a client JVM, so it would
 * simply show MTR's shape.</p>
 */
public final class TrackSide {
    private static volatile Thread clientThread;

    private TrackSide() {
    }

    /** Client init / client tick: the current thread is the Minecraft client thread. */
    public static void markClientThread() {
        final Thread current = Thread.currentThread();
        if (clientThread != current) {
            clientThread = current;
        }
    }

    public static boolean isClientThread() {
        final Thread thread = clientThread;
        return thread != null && thread == Thread.currentThread();
    }
}
