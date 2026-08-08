package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.javax.servlet.http.HttpServlet;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

/**
 * GET {@code /dispatch/api/ping} — the frontend's bootstrap: schema version, the
 * dimension list (index-aligned with the {@code dimension} query param used by the
 * other endpoints, same convention as MTR's own servlets) and the streaming cadence.
 *
 * <p>Answered synchronously on the Jetty worker thread — it reads only the volatile
 * {@link DispatchRegistry} state and each simulator's {@code public final String
 * dimension} field (immutable after construction), so no simulator-thread hop and no
 * MTR envelope. Plain JSON body, HTTP 503 while no session is active.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — never registered when off.</p>
 */
public final class DispatchPingServlet extends HttpServlet {
    public static final int SCHEMA_VERSION = DispatchStreamer.SCHEMA_VERSION;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        DispatchRegistry.State state = DispatchRegistry.get();
        if (state == null) {
            DispatchStaticServlet.sendText(response, 503, "application/json;charset=utf-8",
                    "{\"error\":\"dispatch not available\"}");
            return;
        }
        AddonServerConfig.Dispatch config = AddonServerConfig.get().dispatch;
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("serverTime", System.currentTimeMillis());
        json.addProperty("updateMillis", DispatchStreamer.clampedUpdateMillis(config));
        json.addProperty("maxClients", config.maxClients);
        JsonArray dimensions = new JsonArray();
        state.simulators().forEach(simulator -> dimensions.add(simulator.dimension));
        json.add("dimensions", dimensions);
        DispatchStaticServlet.sendText(response, 200, "application/json;charset=utf-8", json.toString());
    }
}
