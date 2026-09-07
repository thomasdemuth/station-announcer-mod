package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import com.stationannouncer.mtraddon.nav.NavNetworking;
import com.stationannouncer.mtraddon.nav.NavPlanner;
import com.stationannouncer.mtraddon.nav.NavStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.mtr.core.integration.Response;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.CachedResponse;
import org.mtr.core.servlet.ServletBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 *   <li>{@code satmeta} — the basemap's tile index ({@link BasemapScanner}):
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
                || handleSatTile(request, response) || handleNav(request, response)) {
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
                || handleSatTile(request, response) || handleNav(request, response)) {
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
        // kind=mask serves the class-mask tile (water/forest/snow classes in the red
        // channel) the schematic basemap is styled from; anything else is the colour tile.
        boolean mask = "mask".equals(request.getParameter("kind"));
        byte[] png = tx == Integer.MIN_VALUE || tz == Integer.MIN_VALUE
                ? null
                : BasemapScanner.tile(simulators.get(dimensionIndex).dimension, tx, tz, mask);
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

    // ------------------------------------------------------------ nav endpoints

    /**
     * Bodies bigger than this are refused unread. Raised from 16 KB with the v2 wire
     * format: a journey now carries a display name and a position for every endpoint
     * plus a per-ride stop list (up to 24 legs × 48 stops × ~100 bytes), so the old cap
     * would have rejected a long itinerary outright.
     */
    private static final int MAX_NAV_BODY_BYTES = 64 * 1024;
    /**
     * How long a nav request will wait for its hop onto the server thread. Bounded, and
     * only reached when that thread is already badly behind — the reply then says so
     * instead of pinning a Jetty worker.
     */
    private static final long SERVER_HOP_TIMEOUT_MILLIS = 2_000;

    /**
     * The three journey-directions endpoints, answered SYNCHRONOUSLY on the Jetty worker
     * beside {@code sattile}: they touch {@link NavStore} (its own monitor-guarded state)
     * and then hop onto the SERVER thread for anything that reads or writes Minecraft
     * state. No simulator is involved at all, so they work whether or not one exists.
     *
     * <ul>
     *   <li>{@code POST pair} — {@code {"code":"ABC123","label":"Chrome on Mac"}} →
     *       {@code {"ok":true,"token":"<32 hex>","player":"Thomas"}}. The code is consumed
     *       on success; a bad or expired one answers
     *       {@code {"ok":false,"error":"invalid or expired code"}}.</li>
     *   <li>{@code POST navigate} — {@code {"token":"…","journey":{…}}}, the journey
     *       mirroring {@link NavNetworking}'s packet with every id as a DECIMAL STRING
     *       (JavaScript cannot hold MTR's random longs exactly). Rate limited to one
     *       accepted send per {@link NavStore#SEND_COOLDOWN_MILLIS} ms per token AND per
     *       target player.
     *       <p>The browser has NO simulator here, so it also supplies what it resolved
     *       from its own map payload, all OPTIONAL: {@code "name"} and
     *       {@code "pos":[x,y,z]} on walk endpoints; {@code "routeName"},
     *       {@code "routeLabel"}, {@code "routeColor"}, {@code "headsign"},
     *       {@code "boardName"}/{@code "boardPos"}, {@code "alightName"}/
     *       {@code "alightPos"} and {@code "stopList":[{"name":…,"pos":[…]}]} on ride
     *       legs; {@code "fromName"}/{@code "fromPos"} and {@code "toName"}/
     *       {@code "toPos"} on transfers. Each is capped and stripped of control
     *       characters; a missing one is never a reason to reject the journey.</p></li>
     *   <li>{@code GET navstatus?token=…} → {@code {"ok":true,"player":"…","online":true}}.</li>
     * </ul>
     *
     * @return true when the request was handled here
     */
    private static boolean handleNav(HttpServletRequest request, HttpServletResponse response) {
        String segment = firstSegment(request);
        boolean pair = "pair".equals(segment);
        boolean navigate = "navigate".equals(segment);
        boolean status = "navstatus".equals(segment);
        if (!pair && !navigate && !status) {
            return false;
        }
        try {
            if (pair) {
                handlePair(request, response);
            } else if (navigate) {
                handleNavigate(request, response);
            } else {
                handleNavStatus(request, response);
            }
        } catch (Throwable throwable) {
            // Never let a browser take a Jetty worker (or the endpoint) down with it.
            StationAnnouncer.LOGGER.warn("Nav endpoint /{} failed ({})", segment, throwable.toString());
            sendNavError(response, "internal error");
        }
        return true;
    }

    private static void handlePair(HttpServletRequest request, HttpServletResponse response) {
        JsonObject body = readJsonBody(request);
        if (body == null) {
            sendNavError(response, "malformed request body");
            return;
        }
        NavStore.Redemption redemption = NavStore.redeem(string(body, "code", 64), string(body, "label", 128));
        if (!redemption.ok()) {
            sendNavError(response, redemption.error());
            return;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", true);
        reply.addProperty("token", redemption.token());
        reply.addProperty("player", redemption.playerName());
        sendNavJson(response, reply);
    }

    private static void handleNavStatus(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = request.getParameter("token");
        NavStore.Token token = NavStore.peek(rawToken);
        if (token == null) {
            sendNavError(response, "unknown token");
            return;
        }
        MinecraftServer server = NavStore.server();
        // The player list belongs to the server thread, so ask it rather than reading the
        // PlayerManager's maps from a Jetty worker.
        Boolean online = server == null ? null : awaitOnServer(server,
                () -> server.getPlayerManager().getPlayer(token.playerId()) != null, null);
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", true);
        reply.addProperty("player", token.playerName());
        reply.addProperty("online", online != null && online);
        if (online == null) {
            // Distinguish "definitely offline" from "could not ask in time".
            reply.addProperty("stale", true);
        }
        sendNavJson(response, reply);
    }

    private static void handleNavigate(HttpServletRequest request, HttpServletResponse response) {
        JsonObject body = readJsonBody(request);
        if (body == null) {
            sendNavError(response, "malformed request body");
            return;
        }
        NavStore.Token token = NavStore.use(string(body, "token", 64));
        if (token == null) {
            sendNavError(response, "unknown or expired token");
            return;
        }
        JsonElement journeyElement = body.get("journey");
        if (journeyElement == null || !journeyElement.isJsonObject()) {
            sendNavError(response, "malformed journey");
            return;
        }
        NavPlanner.Journey journey;
        try {
            journey = parseJourney(journeyElement.getAsJsonObject());
        } catch (IllegalArgumentException e) {
            sendNavError(response, "malformed journey: " + e.getMessage());
            return;
        }
        if (!NavStore.allowSend(token.token(), token.playerId())) {
            sendNavError(response, "rate limited — one send every "
                    + (NavStore.SEND_COOLDOWN_MILLIS / 1000) + " s");
            return;
        }
        MinecraftServer server = NavStore.server();
        if (server == null) {
            sendNavError(response, "server unavailable");
            return;
        }
        Boolean sent = awaitOnServer(server, () -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(token.playerId());
            if (player == null) {
                return Boolean.FALSE;
            }
            if (journey.legs().isEmpty()) {
                NavNetworking.clear(player);
            } else {
                NavNetworking.send(player, journey);
            }
            return Boolean.TRUE;
        }, null);
        if (sent == null) {
            sendNavError(response, "server busy, try again");
            return;
        }
        if (!sent) {
            sendNavError(response, "player offline");
            return;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", true);
        sendNavJson(response, reply);
    }

    /**
     * The JSON mirror of {@link NavNetworking}'s packet. Ids arrive as DECIMAL STRINGS
     * and anything unparseable is rejected outright rather than silently becoming 0 — a
     * journey pointing at platform 0 would draw nonsense on the HUD.
     *
     * @throws IllegalArgumentException with a browser-readable reason
     */
    private static NavPlanner.Journey parseJourney(JsonObject journeyJson) {
        String destination = string(journeyJson, "destination", NavNetworking.MAX_DESTINATION_LENGTH);
        long plannedArriveMs = 0;
        JsonElement arrive = journeyJson.get("plannedArriveMs");
        if (arrive != null && arrive.isJsonPrimitive()) {
            try {
                plannedArriveMs = Math.max(0, arrive.getAsLong());
            } catch (RuntimeException ignored) {
                plannedArriveMs = 0;
            }
        }
        JsonElement legsElement = journeyJson.get("legs");
        if (legsElement == null || !legsElement.isJsonArray()) {
            throw new IllegalArgumentException("legs must be an array");
        }
        JsonArray legsJson = legsElement.getAsJsonArray();
        if (legsJson.size() > NavNetworking.MAX_LEGS) {
            throw new IllegalArgumentException("at most " + NavNetworking.MAX_LEGS + " legs");
        }
        List<NavPlanner.Leg> legs = new ArrayList<>(legsJson.size());
        for (int i = 0; i < legsJson.size(); i++) {
            JsonElement element = legsJson.get(i);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("leg " + i + " is not an object");
            }
            legs.add(parseLeg(element.getAsJsonObject(), i));
        }
        return new NavPlanner.Journey(destination, List.copyOf(legs), plannedArriveMs, 0);
    }

    private static NavPlanner.Leg parseLeg(JsonObject legJson, int index) {
        String type = string(legJson, "type", 16).toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "walk" -> new NavPlanner.WalkLeg(parsePoint(legJson.get("from"), index, "from"),
                    parsePoint(legJson.get("to"), index, "to"), metres(legJson));
            case "ride" -> {
                List<NavPlanner.Via> via = new ArrayList<>();
                JsonElement viaElement = legJson.get("via");
                if (viaElement != null && viaElement.isJsonArray()) {
                    JsonArray viaJson = viaElement.getAsJsonArray();
                    if (viaJson.size() > NavNetworking.MAX_VIA) {
                        throw new IllegalArgumentException("leg " + index + ": at most "
                                + NavNetworking.MAX_VIA + " via entries");
                    }
                    for (int v = 0; v < viaJson.size(); v++) {
                        if (!viaJson.get(v).isJsonObject()) {
                            throw new IllegalArgumentException("leg " + index + ": via " + v + " is not an object");
                        }
                        JsonObject entry = viaJson.get(v).getAsJsonObject();
                        via.add(new NavPlanner.Via(id(entry, "route", index), id(entry, "at", index),
                                string(entry, "routeName", NavNetworking.MAX_ROUTE_NAME_LENGTH),
                                string(entry, "routeLabel", NavNetworking.MAX_ROUTE_LABEL_LENGTH),
                                routeColor(entry),
                                string(entry, "headsign", NavNetworking.MAX_HEADSIGN_LENGTH)));
                    }
                }
                int stops = 0;
                JsonElement stopsElement = legJson.get("stops");
                if (stopsElement != null && stopsElement.isJsonPrimitive()) {
                    try {
                        stops = Math.max(0, Math.min(1024, stopsElement.getAsInt()));
                    } catch (RuntimeException ignored) {
                        stops = 0;
                    }
                }
                yield new NavPlanner.RideLeg(id(legJson, "route", index),
                        string(legJson, "routeName", NavNetworking.MAX_ROUTE_NAME_LENGTH),
                        string(legJson, "routeLabel", NavNetworking.MAX_ROUTE_LABEL_LENGTH),
                        routeColor(legJson),
                        string(legJson, "headsign", NavNetworking.MAX_HEADSIGN_LENGTH),
                        namedPlatform(legJson, "board", index),
                        namedPlatform(legJson, "alight", index),
                        stops, List.copyOf(via), parseStopList(legJson));
            }
            case "transfer" -> new NavPlanner.TransferLeg(namedPlatform(legJson, "from", index),
                    namedPlatform(legJson, "to", index), metres(legJson));
            default -> throw new IllegalArgumentException("leg " + index + " has unknown type \"" + type + "\"");
        };
    }

    /**
     * {@code {"x":…,"y":…,"z":…}} or {@code {"platform":"<decimal id>"}}, either of which
     * may also carry {@code "name"} and {@code "pos":[x,y,z]}. Names and positions are
     * OPTIONAL and never a reason to reject a journey — an absent one simply means the
     * HUD falls back to whatever MTR has synced to that player.
     */
    private static NavPlanner.Point parsePoint(JsonElement element, int index, String field) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("leg " + index + ": " + field + " is not an object");
        }
        JsonObject point = element.getAsJsonObject();
        String name = string(point, "name", NavNetworking.MAX_NAME_LENGTH);
        if (point.has("platform")) {
            double[] pos = position(point, "pos");
            return NavPlanner.Point.ofPlatform(id(point, "platform", index), pos[0], pos[1], pos[2], name);
        }
        if (point.has("x") && point.has("y") && point.has("z")) {
            try {
                return new NavPlanner.Point(false, 0, finite(point.get("x").getAsDouble()),
                        finite(point.get("y").getAsDouble()), finite(point.get("z").getAsDouble()), name);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("leg " + index + ": " + field + " needs x/y/z or platform");
            }
        }
        if (point.has("pos")) {
            // Loose coordinates may also arrive in the same "pos":[x,y,z] shape the
            // platform endpoints use, so a caller need only learn one spelling.
            double[] pos = position(point, "pos");
            return new NavPlanner.Point(false, 0, pos[0], pos[1], pos[2], name);
        }
        throw new IllegalArgumentException("leg " + index + ": " + field + " needs x/y/z, pos or platform");
    }

    /**
     * A ride/transfer endpoint, which is always a platform: {@code "<field>"} is the
     * required decimal id, {@code "<field>Name"} and {@code "<field>Pos"} the optional
     * resolved name and position (e.g. {@code board} / {@code boardName} /
     * {@code boardPos}).
     */
    private static NavPlanner.Point namedPlatform(JsonObject legJson, String field, int index) {
        double[] pos = position(legJson, field + "Pos");
        return NavPlanner.Point.ofPlatform(id(legJson, field, index), pos[0], pos[1], pos[2],
                string(legJson, field + "Name", NavNetworking.MAX_NAME_LENGTH));
    }

    /**
     * {@code "stopList":[{"name":…,"pos":[x,y,z]}]} — the ride's stops in order, so the
     * HUD can count them down outside MTR's sync range. Over the cap it is TRUNCATED
     * rather than rejected (the brief's rule: never refuse a journey over its names).
     */
    private static List<NavPlanner.Stop> parseStopList(JsonObject legJson) {
        JsonElement element = legJson.get("stopList");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        int count = Math.min(array.size(), NavNetworking.MAX_STOP_LIST);
        List<NavPlanner.Stop> stops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JsonElement entry = array.get(i);
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject stop = entry.getAsJsonObject();
            double[] pos = position(stop, "pos");
            stops.add(new NavPlanner.Stop(string(stop, "name", NavNetworking.MAX_NAME_LENGTH),
                    pos[0], pos[1], pos[2]));
        }
        return List.copyOf(stops);
    }

    /**
     * {@code "pos":[x,y,z]} (or {@code {"x":…,"y":…,"z":…}} under that key) as three
     * finite doubles. Anything missing or unparseable is {@code (0,0,0)}, which the wire
     * contract defines as "position unknown".
     */
    private static double[] position(JsonObject object, String key) {
        double[] none = {0, 0, 0};
        JsonElement element = object.get(key);
        if (element == null) {
            return none;
        }
        try {
            if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                if (array.size() < 3) {
                    return none;
                }
                return new double[]{finite(array.get(0).getAsDouble()), finite(array.get(1).getAsDouble()),
                        finite(array.get(2).getAsDouble())};
            }
            if (element.isJsonObject()) {
                JsonObject point = element.getAsJsonObject();
                return new double[]{finite(point.get("x").getAsDouble()), finite(point.get("y").getAsDouble()),
                        finite(point.get("z").getAsDouble())};
            }
        } catch (RuntimeException ignored) {
            // A malformed position is simply an absent one.
        }
        return none;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    /**
     * {@code "routeColor"} as a number or a {@code "#rrggbb"} / {@code "0xrrggbb"} string.
     * −1 (the wire's "unknown") for anything absent or unparseable.
     */
    private static int routeColor(JsonObject object) {
        JsonElement element = object.get("routeColor");
        if (element == null || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            String raw = element.getAsString().trim();
            if (raw.isEmpty()) {
                return -1;
            }
            if (raw.startsWith("#")) {
                raw = raw.substring(1);
            } else if (raw.startsWith("0x") || raw.startsWith("0X")) {
                raw = raw.substring(2);
            } else {
                // A plain decimal number, which is how JSON normally carries it.
                return (int) (Long.parseLong(raw) & 0xFFFFFF);
            }
            return (int) (Long.parseLong(raw, 16) & 0xFFFFFF);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static int metres(JsonObject legJson) {
        JsonElement element = legJson.get("meters");
        if (element == null) {
            element = legJson.get("metres");
        }
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(1_000_000, (int) Math.round(element.getAsDouble())));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** A decimal-string (or numeric) MTR id. Rejects anything that is not a long. */
    private static long id(JsonObject object, String key, int index) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("leg " + index + ": missing \"" + key + "\"");
        }
        try {
            return Long.parseLong(element.getAsString().trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("leg " + index + ": \"" + key + "\" is not a decimal id");
        }
    }

    /**
     * A capped, never-null string field with every control character stripped. The
     * browser is an untrusted source and these strings are drawn straight onto the HUD
     * and into chat, so newlines, section signs and the like must never survive: a
     * {@code §} would let a journey recolour chat, and a newline would forge lines.
     */
    private static String string(JsonObject object, String key, int maxLength) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            String value = element.getAsString();
            StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
            for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
                char c = value.charAt(i);
                if (c >= ' ' && c != 0x7F && c != '§') {
                    builder.append(c);
                }
            }
            return builder.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * Reads at most {@value #MAX_NAV_BODY_BYTES} bytes of request body and parses it as a
     * JSON object; null on anything else (oversize, unreadable, not an object). Bounded
     * by construction — one byte past the cap aborts rather than buffering.
     */
    private static JsonObject readJsonBody(HttpServletRequest request) {
        try {
            long declared = request.getContentLengthLong();
            if (declared > MAX_NAV_BODY_BYTES) {
                return null;
            }
            byte[] buffer = new byte[MAX_NAV_BODY_BYTES + 1];
            int total = 0;
            try (InputStream stream = request.getInputStream()) {
                while (total < buffer.length) {
                    int read = stream.read(buffer, total, buffer.length - total);
                    if (read < 0) {
                        break;
                    }
                    total += read;
                }
            }
            if (total > MAX_NAV_BODY_BYTES || total == 0) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(new String(buffer, 0, total, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Runs {@code supplier} on the SERVER thread and waits at most
     * {@value #SERVER_HOP_TIMEOUT_MILLIS} ms for it. Returns {@code fallback} on timeout,
     * interruption or failure — the caller turns that into an honest "server busy".
     */
    private static <T> T awaitOnServer(MinecraftServer server, Supplier<T> supplier, T fallback) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future.get(SERVER_HOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Throwable throwable) {
            return fallback;
        }
    }

    private static void sendNavJson(HttpServletResponse response, JsonObject payload) {
        DispatchStaticServlet.sendText(response, 200, "application/json;charset=utf-8", payload.toString());
    }

    private static void sendNavError(HttpServletResponse response, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("error", error == null ? "unknown error" : error);
        // 200 with ok:false, like the rest of this surface: the browser reads the body.
        DispatchStaticServlet.sendText(response, 200, "application/json;charset=utf-8", payload.toString());
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
        } else if ("satmeta".equals(endpoint)) {
            // Where the basemap's tiles are and how they map onto the world. The
            // scanner's snapshot is volatile-immutable, so reading it here is thread-safe
            // and cheap; the tiles themselves go out through the synchronous /sattile
            // branch above, never from here.
            sendResponse.accept(BasemapScanner.satelliteJson(simulator.dimension));
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("error", "unknown endpoint: " + endpoint);
            sendResponse.accept(error);
        }
    }
}
