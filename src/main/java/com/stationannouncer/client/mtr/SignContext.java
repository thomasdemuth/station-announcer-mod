package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.AddonUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;
import java.util.ArrayList;
import java.util.List;

/**
 * What the live tiles of a sign need from the world: the station here, its
 * exits, the lines calling, and where a route ends. One instance per block
 * position; every answer is cached for a moment because a sign asks every
 * frame and the answer only changes when somebody edits the railway.
 *
 * <p>Everything is resolved by NAME at draw time — station, exit and route
 * names — so a rename or repaint in MTR's dashboard follows through to every
 * sign without touching the sign.</p>
 */
@Environment(EnvType.CLIENT)
public class SignContext {
    private static final long TTL_MS = 1_000;

    /** One MTR exit: its name (as typed in the station's exit list) and its street destinations. */
    public record Exit(String name, List<String> destinations) {
    }

    /** A route as the editor lists it: full MTR name (line || direction), display name, colour. */
    public record RouteOption(String routeName, String line, String direction, int color) {
    }

    private final BlockPos pos;
    private long expiry;
    private String stationName = "";
    private int stationColor = 0xFF1F4D3A;
    private List<Exit> exits = List.of();
    private List<String> stationLines = List.of();

    public SignContext(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now < expiry) {
            return;
        }
        expiry = now + TTL_MS;
        try {
            Station station = MtrDataCache.station(pos);
            if (station == null) {
                stationName = "";
                exits = List.of();
                stationLines = List.of();
                return;
            }
            stationName = AddonUi.firstLang(station.getName());
            stationColor = 0xFF000000 | station.getColor();
            List<Exit> found = new ArrayList<>();
            for (StationExit exit : station.getExits()) {
                List<String> destinations = new ArrayList<>();
                for (String destination : exit.getDestinations()) {
                    if (destination != null && !destination.isBlank()) {
                        destinations.add(AddonUi.firstLang(destination));
                    }
                }
                found.add(new Exit(exit.getName() == null ? "" : exit.getName(), List.copyOf(destinations)));
            }
            exits = List.copyOf(found);
            List<String> lines = new ArrayList<>();
            for (RouteBullets.Option option : RouteBullets.atStation(pos)) {
                if (RouteBullets.isNoEntry(option.routeName())) {
                    continue;
                }
                String line = AddonUi.splitLineAndDirection(option.routeName())[0];
                if (!line.isEmpty() && !lines.contains(line)) {
                    lines.add(line);
                }
            }
            stationLines = List.copyOf(lines);
        } catch (Exception e) {
            // MTR data mid-sync; keep the last answer
        }
    }

    /** The MTR station this block stands in, first language, or "" outside any station. */
    public String stationName() {
        refresh();
        return stationName;
    }

    public int stationColor() {
        refresh();
        return stationColor;
    }

    /** The station's exits in MTR's order. */
    public List<Exit> exits() {
        refresh();
        return exits;
    }

    /** The exit called {@code name}; empty name = the first exit; null when there is none. */
    public Exit exit(String name) {
        refresh();
        if (name == null || name.isEmpty()) {
            return exits.isEmpty() ? null : exits.get(0);
        }
        for (Exit exit : exits) {
            if (exit.name().equalsIgnoreCase(name)) {
                return exit;
            }
        }
        return null;
    }

    /** Line names (the part before MTR's {@code ||}) of every route calling at this station. */
    public List<String> stationLines() {
        refresh();
        return stationLines;
    }

    // ------------------------------------------------------------- routes

    /**
     * The terminus of the route called {@code routeName}: the route's own
     * destination override where MTR has one, else the last stop's station
     * name; "" when the route is unknown on this client.
     */
    public static String destination(String routeName) {
        SimplifiedRoute route = findRoute(routeName);
        if (route == null) {
            return "";
        }
        try {
            List<SimplifiedRoutePlatform> platforms = route.getPlatforms();
            if (platforms.isEmpty()) {
                return "";
            }
            for (SimplifiedRoutePlatform platform : platforms) {
                String custom = platform.getDestination();
                if (custom != null && !custom.isBlank()) {
                    return AddonUi.firstLang(custom);
                }
            }
            return AddonUi.firstLang(platforms.get(platforms.size() - 1).getStationName());
        } catch (Exception e) {
            return "";
        }
    }

    /** The direction wording after MTR's {@code ||} in the route name ("" when there is none). */
    public static String direction(String routeName) {
        return AddonUi.splitLineAndDirection(routeName)[1];
    }

    /** The route's colour, or MTR's grey when unknown. */
    public static int routeColor(String routeName) {
        SimplifiedRoute route = findRoute(routeName);
        return route != null ? 0xFF000000 | route.getColor() : RouteBullets.bulletFor(routeName).color();
    }

    private static SimplifiedRoute findRoute(String routeName) {
        if (routeName == null || routeName.isEmpty()) {
            return null;
        }
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                String raw = route.getName();
                if (raw != null && (raw.equals(routeName) || routeKey(raw).equals(routeKey(routeName)))) {
                    return route;
                }
            }
        } catch (Exception ignored) {
            // MTR data mid-sync
        }
        return null;
    }

    /** "line||direction" reduced to first languages, for lenient matching. */
    private static String routeKey(String raw) {
        String[] parts = AddonUi.splitLineAndDirection(raw);
        return parts[0] + "||" + parts[1];
    }

    /** Every route with a generated path, in MTR's order, for the editor's picker. */
    public static List<RouteOption> routes() {
        List<RouteOption> options = new ArrayList<>();
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                String raw = route.getName();
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                String[] parts = AddonUi.splitLineAndDirection(raw);
                options.add(new RouteOption(raw, parts[0], parts[1], 0xFF000000 | route.getColor()));
            }
        } catch (Exception ignored) {
            // MTR data mid-sync
        }
        return options;
    }
}
