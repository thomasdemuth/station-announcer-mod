package com.stationannouncer.mtraddon.dispatch;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.CachedResponse;
import org.mtr.core.servlet.ServletBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The JSON data endpoints at {@code /dispatch/api/*}, built on MTR's own
 * {@link ServletBase} so we inherit its request flow verbatim (javap-verified against
 * 4.0.1): async context with no timeout, {@code dimension} int query param routing
 * (default 0, {@code dimensions=all} fan-out), then {@code simulator.run(...)} hops
 * onto the target dimension's SIMULATOR thread where {@link #getContent} runs and
 * replies through the async consumer. Responses are wrapped in MTR's standard envelope
 * {@code {"code":200,"currentTime":...,"text":"...","version":1,"data":{...}}}.
 *
 * <p>Endpoints (first path segment after the servlet path):</p>
 * <ul>
 *   <li>{@code network} — the static network payload ({@link DispatchNetwork}), served
 *       behind a per-dimension 30 s {@link CachedResponse}, the same throttle
 *       SystemMapServlet uses so busy servers never rebuild per request.</li>
 * </ul>
 *
 * <p>{@code /dispatch/api/ping} and {@code /dispatch/api/stream} are separate exact-path
 * servlets and never reach this class (Jetty exact-over-prefix matching).</p>
 *
 * <p><b>Threading:</b> {@link #doGet} runs on Jetty workers; {@link #getContent} runs on
 * the simulator thread of the requested dimension. The cache map is a ConcurrentHashMap
 * because different dimensions resolve their entries from different simulator threads;
 * each {@link CachedResponse} itself is only ever used from its own dimension's thread.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — never registered when off; requests after
 * SERVER_STOPPING (registry cleared) get HTTP 503.</p>
 */
public final class DispatchApiServlet extends ServletBase {
    private final ConcurrentHashMap<String, CachedResponse> networkResponses = new ConcurrentHashMap<>();

    public DispatchApiServlet(ObjectImmutableList<Simulator> simulators) {
        super(simulators);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        if (unavailable(response)) {
            return;
        }
        super.doGet(request, response);
    }

    /**
     * ServletBase routes GET through doPost, but a direct POST would otherwise skip the
     * session guard entirely — so both entry points check it.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        if (unavailable(response)) {
            return;
        }
        super.doPost(request, response);
    }

    /** Plain 503 (no MTR envelope) while no webserver session is registered. */
    private static boolean unavailable(HttpServletResponse response) {
        if (DispatchRegistry.isActive()) {
            return false;
        }
        try {
            response.sendError(503, "Dispatch not available");
        } catch (IOException | RuntimeException ignored) {
            // Client already gone; nothing to clean up for a synchronous error reply.
        }
        return true;
    }

    /** Runs on the simulator thread for the requested dimension (ServletBase contract). */
    @Override
    protected void getContent(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters,
                              JsonReader jsonReader, Simulator simulator, Consumer<JsonObject> sendResponse) {
        if ("network".equals(endpoint)) {
            sendResponse.accept(networkResponses
                    .computeIfAbsent(simulator.dimension, key -> new CachedResponse(DispatchNetwork::build, 30_000))
                    .get(simulator));
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("error", "unknown endpoint: " + endpoint);
            sendResponse.accept(error);
        }
    }
}
