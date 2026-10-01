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
import java.util.HashMap;
import java.util.Map;

/**
 * Client mirror of {@link LineStyles}: each line's bullet shape, plus the
 * "Bullet" button on MTR's Edit Route screen that sets it.
 *
 * <p>{@link #version()} ticks on every sync so the bullet caches
 * ({@code RouteBullets}, {@code PosterLayout}) rebuild at once instead of
 * waiting out their TTL.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientLineStyles {
    private static volatile Map<String, LineStyles.Shape> shapes = Map.of();
    private static volatile int version;

    private ClientLineStyles() {
    }

    /** The bullet shape for a route or line name. */
    public static LineStyles.Shape shape(String routeOrLineName) {
        Map<String, LineStyles.Shape> current = shapes;
        if (current.isEmpty() || routeOrLineName == null) {
            return LineStyles.Shape.CIRCLE;
        }
        return current.getOrDefault(LineStyles.lineKey(routeOrLineName), LineStyles.Shape.CIRCLE);
    }

    public static int version() {
        return version;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LineStyles.LINE_STYLES_S2C, (client, handler, buf, responseSender) -> {
            int count = buf.readVarInt();
            if (count < 0 || count > 10_000) {
                return;
            }
            Map<String, LineStyles.Shape> next = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String line = buf.readString(LineStyles.MAX_LINE_LENGTH);
                next.put(line, LineStyles.Shape.byOrdinal(buf.readByte()));
            }
            client.execute(() -> {
                shapes = Map.copyOf(next);
                version++;
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shapes = Map.of();
            version++;
        });

        // "Bullet: ● Circle" on MTR's Edit Route screen, one row above the
        // addon's Announcement button; each click cycles circle → diamond → square
        // for the whole LINE (both directions), saved on the server at once.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof org.mtr.mod.screen.EditRouteScreen)) {
                return;
            }
            if (!org.mtr.mod.client.MinecraftClientData.hasPermission()) {
                return;
            }
            org.mtr.core.data.Route route = readRoute(screen);
            if (route == null) {
                return;
            }
            String line = LineStyles.lineKey(route.getName());
            if (line.isEmpty()) {
                return;
            }
            LineStyles.Shape[] current = {shape(line)};
            ButtonWidget button = ButtonWidget.builder(label(current[0]), b -> {
                        current[0] = current[0].next();
                        b.setMessage(label(current[0]));
                        PacketByteBuf buf = PacketByteBufs.create();
                        buf.writeString(line, LineStyles.MAX_LINE_LENGTH);
                        buf.writeByte(current[0].ordinal());
                        ClientPlayNetworking.send(LineStyles.UPDATE_LINE_STYLE_C2S, buf);
                    })
                    .dimensions(screen.width - 124, screen.height - 46, 120, 20)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.translatable("gui.station_announcer.line_style.tooltip", line)))
                    .build();
            Screens.getButtons(screen).add(button);
        });
    }

    private static Text label(LineStyles.Shape shape) {
        return Text.translatable("gui.station_announcer.line_style.button",
                Text.translatable("gui.station_announcer.line_style." + shape.id));
    }

    private static Field dataField;
    private static boolean lookupFailed;

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
}
