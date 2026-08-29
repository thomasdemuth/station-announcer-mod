package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import org.mtr.core.integration.Response;
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
 *   <li>{@code analytics} — the timetable/headway aggregate
 *       ({@link AnalyticsAggregator}). Handled before ServletBase's simulator hop and
 *       answered straight from the off-thread cache, so polling it costs the simulation
 *       nothing at all.</li>
 *   <li>{@code mapdata} — the System Map+ payload ({@link DispatchMapData}): per-route
 *       platform order, leg rail chains and durations, station platform clustering,
 *       scheduled headways. Per-dimension 30 s {@link CachedResponse} like network.</li>
 *   <li>{@code terrain} — cached water polygons from the last {@code /dispatch terrain
 *       scan} ({@link TerrainScanner}); empty until a scan has run.</li>
 *   <li>{@code satmeta} — the satellite basemap's tile index ({@link SatelliteScanner}):
 *       origin, scale, tile list and bbox; {@code available:false} until a scan has run.</li>
 *   <li>{@code sattile} — one basemap tile as {@code image/png}. Handled before
 *       ServletBase's simulator hop (like {@code analytics}) and read straight off the
 *       disk, so painting a screenful of tiles never touches the simulation.</li>
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
    private final ConcurrentHashMap<String, CachedResponse> stringlineAxisResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedResponse> mapDataResponses = new ConcurrentHashMap<>();
    /**
     * ServletBase keeps its own copy privately, so {@code sattile} — which answers before
     * the simulator hop and therefore never receives a {@link Simulator} — keeps a second
     * reference to resolve the {@code dimension} index exactly the way the base class does.
     */
    private final ObjectImmutableList<Simulator> simulators;

    public DispatchApiServlet(ObjectImmutableList<Simulator> simulators) {
        super(simulators);
        this.simulators = simulators;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        if (unavailable(response) || handleAnalytics(request, response) || handleAlerts(request, response)
                || handleSatTile(request, response)) {
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
        if (unavailable(response) || handleAnalytics(request, response) || handleAlerts(request, response)
                || handleSatTile(request, response)) {
            return;
        }
        super.doPost(request, response);
    }

    /**
     * {@code sattile} — one satellite basemap tile as a PNG. Answered SYNCHRONOUSLY on
     * the Jetty worker like {@code analytics}: the tiles are immutable files on disk
     * (written once, moved into place atomically, and only then listed in the index the
     * lookup validates against), so a blocking read here needs no simulator at all and
     * a browser fetching a screenful of tiles costs the simulation nothing.
     *
     * <p>Query: {@code dimension} (int index, ServletBase's convention — default 0),
     * {@code tx}, {@code tz}. Unknown tiles answer 404 in plain text rather than MTR's
     * JSON envelope: the caller is an {@code <img>}/fetch expecting image bytes.</p>
     *
     * @return true when the request was handled here
     */
    private boolean handleSatTile(HttpServletRequest request, HttpServletResponse response) {
        if (!"sattile".equals(firstSegment(request))) {
            return false;
        }
        int dimensionIndex = intParameter(request, "dimension", 0);
        if (dimensionIndex < 0 || dimensionIndex >= simulators.size()) {
            DispatchStaticServlet.sendText(response, 400, "text/plain;charset=utf-8", "invalid dimension");
            return true;
        }
        // Tile indices are relative to the dimension's permanent origin, so a network that
        // has grown toward -x/-z legitimately has NEGATIVE ones — they are passed through
        // untouched. Integer.MIN_VALUE is only the "absent or unparseable" sentinel (no
        // tile can sit 2^31 tiles from the origin; the scanner refuses long before that),
        // and the index lookup is what actually decides whether a tile exists.
        int tx = intParameter(request, "tx", Integer.MIN_VALUE);
        int tz = intParameter(request, "tz", Integer.MIN_VALUE);
        byte[] png = tx == Integer.MIN_VALUE || tz == Integer.MIN_VALUE
                ? null
                : SatelliteScanner.tile(simulators.get(dimensionIndex).dimension, tx, tz);
        if (png == null) {
            DispatchStaticServlet.sendText(response, 404, "text/plain;charset=utf-8", "no such tile");
            return true;
        }
        try {
            response.setStatus(200);
            response.setHeader("Content-Type", "image/png");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setContentLength(png.length);
            response.getOutputStream().write(png);
        } catch (IOException | RuntimeException ignored) {
            // Client went away mid-reply; nothing to clean up for a sync response.
        }
        return true;
    }

    /** A tolerant int query parameter, matching ServletBase's parse-or-default behaviour. */
    private static int intParameter(HttpServletRequest request, String name, int fallback) {
        try {
            String parameter = request.getParameter(name);
            if (parameter != null && !parameter.isEmpty()) {
                return Integer.parseInt(parameter.trim());
            }
        } catch (RuntimeException ignored) {
            // NumberFormatException, or a container that dislikes the query string;
            // fall through to the default, matching ServletBase's tolerant handling.
        }
        return fallback;
    }

    /**
     * {@code alerts} — the retained alert backlog, answered synchronously on the Jetty
     * worker like {@code analytics}: the ring is its own monitor-guarded snapshot and
     * has no reason to queue work onto a simulator.
     */
    private static boolean handleAlerts(HttpServletRequest request, HttpServletResponse response) {
        if (!"alerts".equals(firstSegment(request))) {
            return false;
        }
        JsonObject data = new JsonObject();
        data.addProperty("schemaVersion", DispatchStreamer.SCHEMA_VERSION);
        data.add("alerts", DispatchEvents.backlogJson());
        DispatchStaticServlet.sendText(response, 200, "application/json;charset=utf-8",
                new Response(200, "Success", data).getJson().toString());
        return true;
    }

    /**
     * {@code analytics} is answered SYNCHRONOUSLY on the Jetty worker, before
     * ServletBase's {@code simulator.run(...)} hop, because the aggregate is computed
     * entirely on the analytics writer thread and published as a volatile immutable
     * snapshot — the request has no reason to queue work onto a simulator. The reply
     * still uses MTR's own {@link Response} envelope, so it is byte-shaped exactly like
     * the {@code network} endpoint's.
     *
     * @return true when the request was handled here
     */
    private static boolean handleAnalytics(HttpServletRequest request, HttpServletResponse response) {
        if (!"analytics".equals(firstSegment(request))) {
            return false;
        }
        int dimensionIndex = 0;
        try {
            String parameter = request.getParameter("dimension");
            if (parameter != null && !parameter.isEmpty()) {
                dimensionIndex = Integer.parseInt(parameter);
            }
        } catch (NumberFormatException ignored) {
            // Fall through with dimension 0, matching ServletBase's tolerant default.
        }
        Simulator simulator = DispatchRegistry.simulator(dimensionIndex);
        if (simulator == null) {
            DispatchStaticServlet.sendText(response, 400, "application/json;charset=utf-8",
                    "{\"error\":\"invalid dimension\"}");
            return true;
        }
        boolean enabled = AddonServerConfig.get().analytics.enabled;
        AnalyticsAggregator.Aggregate aggregate = enabled ? AnalyticsAggregator.get(simulator.dimension) : null;
        JsonObject data = aggregate == null
                ? AnalyticsAggregator.emptyJson(simulator.dimension, enabled)
                : aggregate.json;
        DispatchStaticServlet.sendText(response, 200, "application/json;charset=utf-8",
                new Response(200, "Success", data).getJson().toString());
        return true;
    }

    /** First path segment after the servlet mapping, e.g. {@code /analytics} → {@code analytics}. */
    private static String firstSegment(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getRequestURI();
        }
        if (path == null) {
            return "";
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        int slash = path.indexOf('/', start);
        int end = slash < 0 ? path.length() : slash;
        // getRequestURI() fallback keeps the whole "/dispatch/api/analytics" path, so take
        // the LAST segment there; getPathInfo() (the normal case) yields the first.
        String segment = path.substring(start, end);
        if ("dispatch".equals(segment)) {
            int lastSlash = path.lastIndexOf('/');
            segment = lastSlash < 0 ? segment : path.substring(lastSlash + 1);
        }
        return segment;
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
        } else if ("stringline".equals(endpoint)) {
            // Axis (route/station geometry) is cached like the network payload; the
            // departure rows are filtered fresh per request from the published
            // analytics snapshot (immutable — safe to read here on the simulator
            // thread or anywhere else). Nesting the cached axis object into a new
            // parent is serialization-only reuse; nothing ever mutates it.
            JsonObject payload = new JsonObject();
            payload.add("axis", stringlineAxisResponses
                    .computeIfAbsent(simulator.dimension, key -> new CachedResponse(DispatchStringline::buildAxis, 30_000))
                    .get(simulator));
            payload.add("deps", DispatchStringline.depsJson(simulator.dimension, parameters.get("routes")));
            payload.addProperty("windowMinutes", AddonServerConfig.get().analytics.windowMinutes);
            payload.addProperty("analyticsEnabled", AddonServerConfig.get().analytics.enabled);
            payload.addProperty("now", System.currentTimeMillis());
            sendResponse.accept(payload);
        } else if ("mapdata".equals(endpoint)) {
            // System Map+ payload: route geometry chains + leg durations + station
            // platform clustering + headways. Same 30 s per-dimension cache as network.
            sendResponse.accept(mapDataResponses
                    .computeIfAbsent(simulator.dimension, key -> new CachedResponse(DispatchMapData::build, 30_000))
                    .get(simulator));
        } else if ("terrain".equals(endpoint)) {
            // Water polygons from the last /dispatch terrain scan. The scanner's
            // snapshot is volatile-immutable, so reading it here is thread-safe and
            // cheap; empty polygons until a scan has run in this dimension.
            sendResponse.accept(TerrainScanner.terrainJson(simulator.dimension));
        } else if ("satmeta".equals(endpoint)) {
            // Where the satellite basemap's tiles are and how they map onto the world.
            // Same volatile-immutable snapshot rules as terrain; the tiles themselves go
            // out through the synchronous /sattile branch above, never from here.
            sendResponse.accept(SatelliteScanner.satelliteJson(simulator.dimension));
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("error", "unknown endpoint: " + endpoint);
            sendResponse.accept(error);
        }
    }
}
