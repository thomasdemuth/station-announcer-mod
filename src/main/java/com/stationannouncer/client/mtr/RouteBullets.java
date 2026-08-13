package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The NYC-style route bullets: a coloured disc carrying the line's number or
 * letter, the way an entrance sign lists the services that stop there.
 *
 * <p>Signs store the plain MTR route NAME, not a colour — a repainted or
 * renamed line then follows along on its own, and a bullet still draws (grey,
 * labelled from the name) while route data is missing or the line has been
 * deleted.</p>
 */
@Environment(EnvType.CLIENT)
public final class RouteBullets {
    /** How far to sweep for platforms when the sign is not inside a station area. */
    private static final int SEARCH_RADIUS = 16;

    /**
     * The no-entry roundel, stored as a reserved "route name".
     *
     * <p>Signs persist plain route NAMES, so a sentinel rides the existing
     * storage and the existing packets untouched — no schema change, and an
     * old sign that never had one is unaffected. The '!' prefix is what keeps
     * it from ever colliding with a real MTR route: route names come from the
     * railway's own data and cannot begin with one.</p>
     */
    public static final String NO_ENTRY = "!no_entry";

    /** The MTA's prohibition red, and the white bar across it. */
    public static final int NO_ENTRY_COLOR = 0xFFCE1B22;

    public static boolean isNoEntry(String routeName) {
        return NO_ENTRY.equals(routeName);
    }

    /** Colour for a stored route the client cannot find in MTR's route data. */
    private static final int UNKNOWN_COLOR = 0xFF6E6E78;

    /** Route colours change about as often as anybody rebuilds a line. */
    private static final long COLORS_TTL_MS = 3000;

    /** One drawable bullet: the short label on the disc and the disc's colour. */
    public record Bullet(String label, int color) {
    }

    /** A route that could be put on a sign: its stored name plus how it will look. */
    public record Option(String routeName, String displayName, Bullet bullet) {
    }

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static long colorsExpiry;

    /**
     * Finished bullets by route name. {@link #bulletFor} is called per bullet,
     * per face, per frame, and it used to build a fresh record and a fresh
     * label string every time — the label alone costs a scan, a substring and
     * an upper-case. The bullets only change when route data does, so they are
     * memoized alongside the colours and dropped with them.
     */
    private static final Map<String, Bullet> BULLETS = new HashMap<>();

    private RouteBullets() {
    }

    /** Drops cached route colours (called when the client leaves a world). */
    public static void clearCache() {
        COLORS.clear();
        BULLETS.clear();
        colorsExpiry = 0;
    }

    /** How a stored route name draws right now. */
    public static Bullet bulletFor(String routeName) {
        if (isNoEntry(routeName)) {
            // No label: the white bar IS the symbol, and a letter on top of it
            // would read as a route bullet in the wrong colour.
            return new Bullet("", NO_ENTRY_COLOR);
        }
        Map<String, Integer> colors = colors();   // may drop BULLETS as it expires
        Bullet cached = BULLETS.get(routeName);
        if (cached != null) {
            return cached;
        }
        Integer color = colors.get(routeName);
        Bullet bullet = new Bullet(label(routeName), color != null ? color : UNKNOWN_COLOR);
        BULLETS.put(routeName, bullet);
        return bullet;
    }

    /**
     * The routes calling anywhere in the station this sign stands in, in the
     * order MTR lists them. Built from {@code simplifiedRoutes} — clients never
     * receive the full route graph, and each simplified route already lists the
     * platforms it stops at.
     */
    public static List<Option> atStation(BlockPos here) {
        Set<Long> platformIds = stationPlatforms(here);
        Map<String, Option> options = new LinkedHashMap<>();
        // Offered first, and offered everywhere: a "no entry" sign is most
        // useful exactly where the sign is NOT inside a station area.
        options.put(NO_ENTRY, new Option(NO_ENTRY, "No Entry",
                new Bullet("", NO_ENTRY_COLOR)));
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                String routeName = route.getName();
                if (routeName == null || routeName.isEmpty() || options.containsKey(routeName)) {
                    continue;
                }
                if (!platformIds.isEmpty() && !callsAt(route, platformIds)) {
                    continue;
                }
                options.put(routeName, new Option(routeName, RailroadRouteData.firstLang(routeName),
                        new Bullet(label(routeName), 0xFF000000 | route.getColor())));
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — the list just comes back short
        }
        return new ArrayList<>(options.values());
    }

    private static boolean callsAt(SimplifiedRoute route, Set<Long> platformIds) {
        for (SimplifiedRoutePlatform stop : route.getPlatforms()) {
            if (platformIds.contains(stop.getPlatformId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every platform of the station the sign is in — an entrance sign lists the
     * whole station's services, not just the track nearest the street. An empty
     * set means "no station here": the picker then offers every known route
     * rather than nothing at all.
     */
    private static Set<Long> stationPlatforms(BlockPos here) {
        Set<Long> ids = new HashSet<>();
        org.mtr.mapping.holder.BlockPos pos = new org.mtr.mapping.holder.BlockPos(here);
        try {
            Station station = org.mtr.mod.InitClient.findStation(pos);
            if (station != null) {
                for (Platform platform : station.savedRails) {
                    ids.add(platform.getId());
                }
            }
            if (ids.isEmpty()) {
                org.mtr.mod.InitClient.findClosePlatform(pos, SEARCH_RADIUS, platform -> ids.add(platform.getId()));
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — fall through to "every route"
        }
        return ids;
    }

    /** Route name → colour, rebuilt every {@value #COLORS_TTL_MS} ms. */
    private static Map<String, Integer> colors() {
        long now = System.currentTimeMillis();
        if (now < colorsExpiry) {
            return COLORS;
        }
        colorsExpiry = now + COLORS_TTL_MS;
        COLORS.clear();
        BULLETS.clear();   // a bullet is only as fresh as the colour behind it
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                String routeName = route.getName();
                if (routeName != null && !routeName.isEmpty()) {
                    COLORS.putIfAbsent(routeName, 0xFF000000 | route.getColor());
                }
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — bullets fall back to grey for a frame or two
        }
        return COLORS;
    }

    /**
     * What goes on the disc: the line's number if the name has one, otherwise
     * its first letter — the same heuristic the PIDS route map uses, since MTR
     * routes have no separate "number" field.
     */
    public static String label(String routeName) {
        String name = RailroadRouteData.firstLang(routeName);
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end))) {
                    end++;
                }
                return name.substring(i, Math.min(end, i + 2));
            }
        }
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    /** Black or white lettering, whichever reads on a bullet of this colour. */
    public static boolean needsDarkText(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140;
    }
}
