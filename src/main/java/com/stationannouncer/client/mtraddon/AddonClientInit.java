package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.mtr.core.data.Platform;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.PlatformScreen;
import org.mtr.mod.screen.SavedRailScreenBase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client hookup for the MTR dispatch addon. Only classloaded when MTR is present
 * (registered from StationAnnouncerClient behind isModLoaded, like MtrPidsClient).
 *
 * <p>The "Hold rules…" button rides on MTR's own {@code PlatformScreen} via Fabric
 * {@code ScreenEvents.AFTER_INIT} — no mixin needed, because MTR's mapping-layer
 * screens ARE vanilla screens ({@code ScreenExtension} extends
 * {@code ScreenAbstractMapping} extends {@code net.minecraft...Screen}, verified by
 * javap), so a plain {@code instanceof PlatformScreen} on the event's screen works.
 * The edited {@link Platform} sits in {@code SavedRailScreenBase}'s protected
 * {@code savedRailBase} field, read by (cached) reflection.</p>
 */
@Environment(EnvType.CLIENT)
public final class AddonClientInit {
    /** Cached accessor for {@code SavedRailScreenBase.savedRailBase}; null after a lookup failure. */
    private static Field savedRailBaseField;
    private static boolean savedRailBaseLookupFailed;

    private AddonClientInit() {
    }

    public static void register() {
        // Server → client hold-rule sync (join + after each edit).
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.HOLD_RULES_S2C,
                (client, handler, buf, responseSender) -> {
                    int ruleCount = buf.readVarInt();
                    if (ruleCount < 0 || ruleCount > 10_000) {
                        return;
                    }
                    Map<Long, ClientHoldRules.Rule> rules = new HashMap<>(Math.max(1, ruleCount));
                    for (int i = 0; i < ruleCount; i++) {
                        long platformId = buf.readLong();
                        int seconds = buf.readVarInt();
                        int watchedCount = buf.readVarInt();
                        if (watchedCount < 0 || watchedCount > AddonNetworking.MAX_WATCHED) {
                            return;
                        }
                        List<Long> watched = new ArrayList<>(watchedCount);
                        for (int j = 0; j < watchedCount; j++) {
                            watched.add(buf.readLong());
                        }
                        rules.put(platformId, new ClientHoldRules.Rule(watched, seconds));
                    }
                    client.execute(() -> ClientHoldRules.replace(rules));
                });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHoldRules.clear();
            ClientDwellOverrides.clear();
        });

        registerDwellOverrideSync();
        registerRouteDwellButton();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PlatformScreen) || !AddonClientConfig.get().showHoldRulesButton) {
                return;
            }
            // Same visibility condition as MTR's own dashboard edits; the server
            // still validates op level on save.
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            Platform platform = readPlatform(screen);
            if (platform == null) {
                return;
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.hold_rules.button"),
                            button -> client.setScreen(new HoldRuleScreen(platform, screen)))
                    .dimensions(screen.width - 104, screen.height - 24, 100, 20)
                    .build());
        });
    }

    // -------------------------------------------- Feature 2: per-route dwell

    /** Server → client dwell-override sync (join + after each edit). */
    private static void registerDwellOverrideSync() {
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.DWELL_OVERRIDES_S2C,
                (client, handler, buf, responseSender) -> {
                    int platformCount = buf.readVarInt();
                    if (platformCount < 0 || platformCount > 10_000) {
                        return;
                    }
                    Map<Long, Map<Long, Long>> overrides = new HashMap<>(Math.max(1, platformCount));
                    for (int i = 0; i < platformCount; i++) {
                        long platformId = buf.readLong();
                        int routeCount = buf.readVarInt();
                        if (routeCount < 0 || routeCount > AddonNetworking.MAX_ROUTE_OVERRIDES) {
                            return;
                        }
                        Map<Long, Long> byRoute = new HashMap<>(Math.max(1, routeCount));
                        for (int j = 0; j < routeCount; j++) {
                            long routeId = buf.readLong();
                            long millis = buf.readVarInt();
                            byRoute.put(routeId, millis);
                        }
                        overrides.put(platformId, byRoute);
                    }
                    client.execute(() -> ClientDwellOverrides.replace(overrides));
                });
    }

    /**
     * The "Per-route dwell…" button on MTR's {@code PlatformScreen}, one slot
     * above Feature 1's "Hold rules…" button. Registered as its own
     * {@code AFTER_INIT} callback so Feature 1's stays untouched.
     */
    private static void registerRouteDwellButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PlatformScreen) || !AddonClientConfig.get().showRouteDwellButton) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            Platform platform = readPlatform(screen);
            if (platform == null) {
                return;
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.route_dwell.button"),
                            button -> client.setScreen(new RouteDwellScreen(platform, screen)))
                    .dimensions(screen.width - 104, screen.height - 48, 100, 20)
                    .build());
        });
    }

    private static Platform readPlatform(Screen screen) {
        try {
            if (savedRailBaseField == null) {
                if (savedRailBaseLookupFailed) {
                    return null;
                }
                savedRailBaseField = SavedRailScreenBase.class.getDeclaredField("savedRailBase");
                savedRailBaseField.setAccessible(true);
            }
            Object value = savedRailBaseField.get(screen);
            return value instanceof Platform ? (Platform) value : null;
        } catch (Exception e) {
            savedRailBaseLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read PlatformScreen's platform; hold-rule button disabled", e);
            return null;
        }
    }
}
