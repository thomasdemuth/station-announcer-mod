package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.core.servlet.Webserver;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.org.eclipse.jetty.servlet.ServletHolder;
import org.mtr.mod.Init;

/**
 * Registers the dispatch servlets on MTR's own embedded Jetty webserver. Invoked by
 * {@link com.stationannouncer.mixin.MainMixin} from inside the {@code org.mtr.core.Main}
 * constructor, immediately before MTR calls {@code Webserver.start()} — Jetty servlet
 * registration must happen before start, and at that point MTR's own servlets (static
 * site, SystemMapServlet, OBAServlet) plus any {@code additionalWebserverSetup} are
 * already in place, so we only ever ADD paths and cannot disturb the system map.
 *
 * <p>Paths (longest-prefix / exact matching, Jetty servlet-spec rules):</p>
 * <ul>
 *   <li>{@code /dispatch/*} — static frontend files from classpath
 *       {@code assets/station_announcer/dispatch/}</li>
 *   <li>{@code /dispatch/api/*} — data endpoints (ServletBase-derived, simulator-thread hop)</li>
 *   <li>{@code /dispatch/api/ping} — synchronous bootstrap info (no simulator hop)</li>
 *   <li>{@code /dispatch/api/stream} — the SSE live stream</li>
 * </ul>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — when false this returns after one
 * volatile-cached config read and nothing is registered.</p>
 */
public final class DispatchWebSetup {
    private DispatchWebSetup() {
    }

    /**
     * Called from the Main-constructor mixin. Any throwable is contained: a failure here
     * must never break MTR's webserver construction.
     */
    public static void install(Webserver webserver, ObjectImmutableList<Simulator> simulators) {
        if (!AddonServerConfig.get().dispatch.enabled) {
            return;
        }
        try {
            webserver.addServlet(new ServletHolder(new DispatchStaticServlet()), "/dispatch/*");
            webserver.addServlet(new ServletHolder(new DispatchApiServlet(simulators)), "/dispatch/api/*");
            webserver.addServlet(new ServletHolder(new DispatchPingServlet()), "/dispatch/api/ping");
            webserver.addServlet(new ServletHolder(new DispatchStreamServlet()), "/dispatch/api/stream");
            // Init.serverPort is assigned before Main is constructed (Init.registerServerStarted),
            // and Main only creates a Webserver when the port is > 0, so this is the real port.
            int port = Init.getServerPort();
            DispatchRegistry.set(simulators, port);
            StationAnnouncer.LOGGER.info("Dispatch web UI available at http://localhost:{}/dispatch/", port);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.error("Failed to register dispatch servlets on the MTR webserver", t);
        }
    }

    /**
     * Called from AddonInit's SERVER_STARTED hook (which runs after MTR's own
     * server-started handler constructed Main, because MTR initializes first as our
     * dependency). Logs why the dispatch UI is unavailable when it is.
     */
    public static void logAvailability() {
        if (!AddonServerConfig.get().dispatch.enabled) {
            StationAnnouncer.LOGGER.info("Dispatch web UI disabled by config (dispatch.enabled=false)");
            return;
        }
        if (!DispatchRegistry.isActive()) {
            int port = Init.getServerPort();
            if (port <= 0) {
                StationAnnouncer.LOGGER.info("MTR webserver is disabled (port {}); dispatch web UI unavailable", port);
            } else {
                // Shouldn't normally happen: webserver up but our mixin never fired.
                StationAnnouncer.LOGGER.warn("Dispatch servlets not registered although the MTR webserver port is {}", port);
            }
        }
    }
}
