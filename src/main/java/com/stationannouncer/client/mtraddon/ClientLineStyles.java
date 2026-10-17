package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.LineStyles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Client mirror of {@link LineStyles}: each line's bullet style, plus the
 * "Line style…" button on MTR's Edit Route screen that opens
 * {@link LineStyleScreen}.
 *
 * <p>{@link #version()} ticks on every sync so the bullet caches
 * ({@code RouteBullets}, {@code PosterLayout}) rebuild at once instead of
 * waiting out their TTL.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientLineStyles {
    private static volatile Map<String, LineStyles.Style> styles = Map.of();
    private static volatile Map<String, LineStyles.RouteStyle> routeStyles = Map.of();
    private static volatile int version;

    private ClientLineStyles() {
    }

    /** The bullet style for a route or line name. */
    public static LineStyles.Style style(String routeOrLineName) {
        Map<String, LineStyles.Style> current = styles;
        if (current.isEmpty() || routeOrLineName == null) {
            return LineStyles.Style.DEFAULT;
        }
        LineStyles.Style line = current.getOrDefault(LineStyles.lineKey(routeOrLineName), LineStyles.Style.DEFAULT);
        Map<String, LineStyles.RouteStyle> routes = routeStyles;
        return routes.isEmpty() ? line
                : routes.getOrDefault(LineStyles.routeKey(routeOrLineName), LineStyles.RouteStyle.NONE).over(line);
    }

    /** The line's own style, ignoring any route override (the route screen's "Same as line"). */
    public static LineStyles.Style lineStyle(String routeOrLineName) {
        return routeOrLineName == null ? LineStyles.Style.DEFAULT
                : styles.getOrDefault(LineStyles.lineKey(routeOrLineName), LineStyles.Style.DEFAULT);
    }

    /** A route's override of its line's style ({@link LineStyles.RouteStyle#NONE} = none). */
    public static LineStyles.RouteStyle routeStyle(String routeName) {
        return routeName == null ? LineStyles.RouteStyle.NONE
                : routeStyles.getOrDefault(LineStyles.routeKey(routeName), LineStyles.RouteStyle.NONE);
    }

    /** Sends a route's override; the server answers with a full resync. */
    public static void sendRoute(String routeName, LineStyles.RouteStyle style) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(LineStyles.routeKey(routeName), LineStyles.MAX_LINE_LENGTH);
        LineStyles.writeRouteStyle(buf, style);
        ClientPlayNetworking.send(LineStyles.UPDATE_ROUTE_STYLE_C2S, buf);
    }

    /** The bullet shape for a route or line name. */
    public static LineStyles.Shape shape(String routeOrLineName) {
        return style(routeOrLineName).shape();
    }

    public static int version() {
        return version;
    }

    /** Sends a line's new style; the server answers with a full resync. */
    public static void send(String line, LineStyles.Style style) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(LineStyles.lineKey(line), LineStyles.MAX_LINE_LENGTH);
        LineStyles.writeStyle(buf, style);
        ClientPlayNetworking.send(LineStyles.UPDATE_LINE_STYLE_C2S, buf);
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LineStyles.LINE_STYLES_S2C, (client, handler, buf, responseSender) -> {
            int count = buf.readVarInt();
            if (count < 0 || count > 10_000) {
                return;
            }
            Map<String, LineStyles.Style> next = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String line = buf.readString(LineStyles.MAX_LINE_LENGTH);
                next.put(line, LineStyles.readStyle(buf));
            }
            Map<String, LineStyles.RouteStyle> nextRoutes = new HashMap<>();
            int routeCount = buf.isReadable() ? buf.readVarInt() : 0;
            if (routeCount < 0 || routeCount > 10_000) {
                return;
            }
            for (int i = 0; i < routeCount; i++) {
                String key = buf.readString(LineStyles.MAX_LINE_LENGTH);
                nextRoutes.put(key, LineStyles.readRouteStyle(buf));
            }
            client.execute(() -> {
                styles = Map.copyOf(next);
                routeStyles = Map.copyOf(nextRoutes);
                version++;
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            styles = Map.of();
            routeStyles = Map.of();
            version++;
        });
    }

    /**
     * Dev-only (run/commands.txt), after {@code #use} opened MTR's dashboard with the
     * dashboard item: {@code #line-style edit <route name part>} opens MTR's real Edit
     * Route screen on the matching route, {@code #line-style open} presses its
     * "Line style…" button, anything else goes to {@link LineStyleScreen#devCommand}.
     */
    public static void devHook(net.minecraft.client.MinecraftClient client, String cmd) {
        String args = cmd.substring("#line-style".length()).trim();
        if (args.startsWith("edit")) {
            String part = args.substring(4).trim().toLowerCase(java.util.Locale.ROOT);
            org.mtr.mapping.mapper.ScreenExtension parent =
                    client.currentScreen instanceof org.mtr.mapping.mapper.ScreenExtension extension ? extension : null;
            for (org.mtr.core.data.Route route : org.mtr.mod.client.MinecraftClientData.getDashboardInstance().routes) {
                if (route.getName().toLowerCase(java.util.Locale.ROOT).contains(part)) {
                    client.setScreen(new org.mtr.mod.screen.EditRouteScreen(route, parent));
                    return;
                }
            }
            StationAnnouncer.LOGGER.info("[line-style dev] no route matching '{}' ({} routes)", part,
                    org.mtr.mod.client.MinecraftClientData.getDashboardInstance().routes.size());
        } else if (args.equals("open")) {
            org.mtr.core.data.Route route = client.currentScreen == null ? null : readRoute(client.currentScreen);
            if (route != null) {
                client.setScreen(new LineStyleScreen(route, client.currentScreen));
                return;
            }
            StationAnnouncer.LOGGER.info("[line-style dev] not on MTR's Edit Route screen ({})", client.currentScreen);
        } else if (client.currentScreen instanceof LineStyleScreen screen) {
            screen.devCommand(args);
        }
    }

    private static Field dataField;
    private static boolean lookupFailed;
    private static Method saveDataMethod;
    private static boolean saveLookupFailed;

    /** The Route rides EditNameColorScreenBase's protected {@code data} field (as AddonClientInit reads it). */
    private static org.mtr.core.data.Route readRoute(Screen screen) {
        try {
            if (dataField == null) {
                if (lookupFailed) {
                    return null;
                }
                dataField = org.mtr.mod.screen.EditNameColorScreenBase.class.getDeclaredField("data");
                dataField.setAccessible(true);
            }
            Object value = dataField.get(screen);
            return value instanceof org.mtr.core.data.Route route ? route : null;
        } catch (Exception e) {
            lookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read the route from EditRouteScreen", e);
            return null;
        }
    }

    /**
     * MTR's own {@code saveData()} (EditRouteScreen overrides the base's): copies
     * the screen's fields into the route and sends it, exactly what closing the
     * screen does.
     */
    private static void commitEdits(Screen screen) {
        try {
            if (saveDataMethod == null) {
                if (saveLookupFailed) {
                    return;
                }
                saveDataMethod = org.mtr.mod.screen.EditNameColorScreenBase.class.getDeclaredMethod("saveData");
                saveDataMethod.setAccessible(true);
            }
            saveDataMethod.invoke(screen);
        } catch (Exception e) {
            saveLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not commit the Edit Route screen before opening Line style", e);
        }
    }
}
