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
    private static volatile int version;

    private ClientLineStyles() {
    }

    /** The bullet style for a route or line name. */
    public static LineStyles.Style style(String routeOrLineName) {
        Map<String, LineStyles.Style> current = styles;
        if (current.isEmpty() || routeOrLineName == null) {
            return LineStyles.Style.DEFAULT;
        }
        return current.getOrDefault(LineStyles.lineKey(routeOrLineName), LineStyles.Style.DEFAULT);
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
            client.execute(() -> {
                styles = Map.copyOf(next);
                version++;
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            styles = Map.of();
            version++;
        });

        // "Line style…" on MTR's Edit Route screen, one row above the addon's
        // Announcement button: bullet text, shape, colour and ink for the whole
        // LINE (both directions).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof org.mtr.mod.screen.EditRouteScreen)) {
                return;
            }
            if (!org.mtr.mod.client.MinecraftClientData.hasPermission()) {
                return;
            }
            org.mtr.core.data.Route route = readRoute(screen);
            if (route == null || LineStyles.lineKey(route.getName()).isEmpty()) {
                return;
            }
            ButtonWidget button = ButtonWidget.builder(Text.translatable("gui.station_announcer.line_style.open"), b -> {
                        // Commit what is typed in MTR's own fields first (name, colour,
                        // route number): coming back re-runs MTR's init, which reloads
                        // every field from the route and would otherwise drop them.
                        commitEdits(screen);
                        client.setScreen(new LineStyleScreen(route, screen));
                    })
                    .dimensions(screen.width - 124, screen.height - 46, 120, 20)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.translatable("gui.station_announcer.line_style.tooltip",
                                    LineStyles.lineKey(route.getName()))))
                    .build();
            Screens.getButtons(screen).add(button);
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
            if (client.currentScreen != null) {
                for (net.minecraft.client.gui.widget.ClickableWidget widget : Screens.getButtons(client.currentScreen)) {
                    if (widget instanceof ButtonWidget button
                            && button.getMessage().getString().startsWith("Line style")) {
                        button.onPress();
                        return;
                    }
                }
            }
            StationAnnouncer.LOGGER.info("[line-style dev] no Line style button on {}", client.currentScreen);
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
