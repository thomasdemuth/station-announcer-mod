package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.nav.ClientNav;
import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.LiftDoorSides;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Platform;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.DashboardScreen;
import org.mtr.mod.screen.LiftCustomizationScreen;
import org.mtr.mod.screen.PlatformScreen;
import org.mtr.mod.screen.SavedRailScreenBase;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Cached accessor for {@code LiftCustomizationScreen.lift}; null after a lookup failure. */
    private static Field liftField;
    private static boolean liftLookupFailed;

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
                        int transferSeconds = buf.readVarInt();
                        int watchedCount = buf.readVarInt();
                        if (watchedCount < 0 || watchedCount > AddonNetworking.MAX_WATCHED) {
                            return;
                        }
                        List<Long> watched = new ArrayList<>(watchedCount);
                        for (int j = 0; j < watchedCount; j++) {
                            watched.add(buf.readLong());
                        }
                        rules.put(platformId, new ClientHoldRules.Rule(watched, seconds, transferSeconds));
                    }
                    client.execute(() -> ClientHoldRules.replace(rules));
                });

        // Server → client hold STATE sync (join + whenever the held set changes).
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.HOLD_STATE_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10_000) {
                        return;
                    }
                    Set<Long> held = new HashSet<>(Math.max(1, count));
                    for (int i = 0; i < count; i++) {
                        held.add(buf.readLong());
                    }
                    client.execute(() -> ClientHoldState.replace(held));
                });

        // Server → client door-obstruction sync (join + whenever the stuck set
        // changes). Data only — the driving HUD renders it.
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.DOOR_OBSTRUCTIONS_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10_000) {
                        return;
                    }
                    Set<Long> obstructed = new HashSet<>(Math.max(1, count));
                    for (int i = 0; i < count; i++) {
                        obstructed.add(buf.readLong());
                    }
                    client.execute(() -> ClientDoorObstructions.replace(obstructed));
                });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHoldRules.clear();
            ClientHoldState.clear();
            ClientDoorObstructions.clear();
            ClientDwellOverrides.clear();
            ClientPlatformSchedule.clear();
            ClientAnnouncementTemplates.clear();
            ClientAccessibility.clear();
            ClientLiftDoors.clear();
            ClientPlatformGroups.clear();
            ClientDispatchInfo.clear();
            ClientDisruptions.clear();
            ClientPosters.clear();
            ClientStopChanges.clear();
            ClientNav.stop();
        });

        // Server → client dispatch-availability sync (join only; one int).
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.DISPATCH_INFO_S2C,
                (client, handler, buf, responseSender) -> {
                    int port = buf.readInt();
                    client.execute(() -> ClientDispatchInfo.setPort(port));
                });

        // Map+ offline copies, kept up to date while connected
        OfflineMapSaver.register();
        registerDwellOverrideSync();
        ClientPlatformSchedule.register();
        registerPlatformScreen();
        registerAnnouncementTemplateSync();
        registerRouteScreen();
        com.stationannouncer.client.gui.RouteTrainScreen.registerClient();
        ClientLineStyles.register();
        ClientLineFolders.register();
        // Custom track geometry: profile sync, client-thread marker, culling-box refresh.
        com.stationannouncer.client.track.ClientTrackNetworking.register();
        registerAccessibilitySync();
        registerStationAccessibilityButton();
        registerLiftDoorSync();
        registerLiftDoorSidesButton();
        registerPlatformGroupSync();
        DashboardRail.register(); // the dashboard's tool rail (replaces the squeezed bottom buttons)
        registerDisruptionSync();
        registerInterlineReplies();
        DrivingHud.register(); // Feature 4 — driving HUD (registers its own disconnect cleanup)
        com.stationannouncer.client.mtraddon.crew.CrewClient.register(); // train crews: roles, buzzer, horn, vigilance
        com.stationannouncer.client.mtraddon.cab.CabHud.register(); // cab HUD (hudStyle "cab"; the classic panel stays as a style)
        com.stationannouncer.client.mtraddon.cab.CabPassengers.register(); // cosmetic platform passengers
        com.stationannouncer.client.mtraddon.cab.CabSeat.register(); // cab seat + lean out (registers CabCamera too)
        // Journey directions: the addon_navigate receiver, the 4 Hz progress
        // tracker, the journey card and the in-world waypoint marker.
        ClientNav.register();

    }

    // -------------------------------------------- Dispatch web UI: dashboard button


    // ------------------------- Feature 6: disruptions + temporary stop changes

    /** Server → client sync of the disruption list and the temporary stop changes. */
    private static void registerDisruptionSync() {
        ClientPlayNetworking.registerGlobalReceiver(DisruptionNetworking.DISRUPTIONS_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > DisruptionNetworking.MAX_SYNC_ENTRIES) {
                        return;
                    }
                    List<ClientDisruptions.Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        long id = buf.readLong();
                        int severity = buf.readVarInt();
                        boolean active = buf.readBoolean();
                        long start = buf.readLong();
                        long end = buf.readLong();
                        String message = buf.readString(DisruptionNetworking.MAX_MESSAGE_LENGTH);
                        int routeCount = buf.readVarInt();
                        if (routeCount < 0 || routeCount > DisruptionNetworking.MAX_ROUTES) {
                            return;
                        }
                        List<Long> routeIds = new ArrayList<>(routeCount);
                        for (int j = 0; j < routeCount; j++) {
                            routeIds.add(buf.readLong());
                        }
                        entries.add(new ClientDisruptions.Entry(id, severity, active, start, end,
                                message, List.copyOf(routeIds)));
                    }
                    client.execute(() -> ClientDisruptions.replace(entries));
                });

        ClientPlayNetworking.registerGlobalReceiver(DisruptionNetworking.POSTERS_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > com.stationannouncer.mtraddon.disruption.ServicePoster.MAX_SYNC_POSTERS) {
                        return;
                    }
                    List<com.stationannouncer.mtraddon.disruption.ServicePoster> posters = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        com.stationannouncer.mtraddon.disruption.ServicePoster poster =
                                com.stationannouncer.mtraddon.disruption.ServicePoster.read(buf);
                        if (poster == null) {
                            return;
                        }
                        posters.add(poster);
                    }
                    client.execute(() -> ClientPosters.replace(posters));
                });

        ClientPlayNetworking.registerGlobalReceiver(DisruptionNetworking.STOP_CHANGES_S2C,
                (client, handler, buf, responseSender) -> {
                    int disabledCount = buf.readVarInt();
                    if (disabledCount < 0 || disabledCount > DisruptionNetworking.MAX_SYNC_ENTRIES) {
                        return;
                    }
                    Map<String, Long> disabled = new HashMap<>(Math.max(1, disabledCount));
                    for (int i = 0; i < disabledCount; i++) {
                        long routeId = buf.readLong();
                        int stopIndex = buf.readVarInt();
                        long expiry = buf.readLong();
                        disabled.put(routeId + ":" + stopIndex, expiry);
                    }
                    int addedCount = buf.readVarInt();
                    if (addedCount < 0 || addedCount > DisruptionNetworking.MAX_SYNC_ENTRIES) {
                        return;
                    }
                    Map<String, long[]> added = new HashMap<>(Math.max(1, addedCount));
                    for (int i = 0; i < addedCount; i++) {
                        long routeId = buf.readLong();
                        int stopIndex = buf.readVarInt();
                        long platformId = buf.readLong();
                        long expiry = buf.readLong();
                        added.put(routeId + ":" + stopIndex, new long[]{platformId, expiry});
                    }
                    client.execute(() -> ClientStopChanges.replace(disabled, added));
                });
    }


    // ------------------------------- Depot groups + line tools (dashboard button)

    /** Server → client interline replies, handed to the open Interlining screen. */
    private static void registerInterlineReplies() {
        ClientPlayNetworking.registerGlobalReceiver(com.stationannouncer.mtraddon.interline.InterlineService.REPLY_S2C,
                (client, handler, buf, responseSender) -> {
                    int requestId = buf.readVarInt();
                    String json = buf.readString(com.stationannouncer.mtraddon.interline.InterlineService.MAX_REPLY_BYTES);
                    client.execute(() -> {
                        if (client.currentScreen instanceof InterlineScreen screen) {
                            screen.accept(requestId, json);
                        }
                    });
                });
    }


    // ------------------------------------------- station accessibility

    /** Server → client step-free map sync (join + after each edit). */
    private static void registerAccessibilitySync() {
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.ACCESSIBILITY_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10_000) {
                        return;
                    }
                    Map<Long, long[]> accessibility = new HashMap<>(Math.max(1, count));
                    for (int i = 0; i < count; i++) {
                        long stationId = buf.readLong();
                        int platformCount = buf.readVarInt();
                        if (platformCount < 0 || platformCount > AddonNetworking.MAX_ACCESSIBLE_PLATFORMS) {
                            return;
                        }
                        long[] platforms = new long[platformCount];
                        for (int j = 0; j < platformCount; j++) {
                            platforms[j] = buf.readLong();
                        }
                        accessibility.put(stationId, platforms);
                    }
                    client.execute(() -> ClientAccessibility.replace(accessibility));
                });
    }

    /**
     * "Accessibility…" button on MTR's Edit Station screen — beside the zone
     * fields, per Thomas's spec. The Station rides the same reflective
     * {@code EditNameColorScreenBase.data} read as the route button.
     */
    private static void registerStationAccessibilityButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof org.mtr.mod.screen.EditStationScreen)) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            org.mtr.core.data.Station station = readStation(screen);
            if (station == null) {
                return;
            }
            long stationId = station.getId();
            String display = station.getName().split("\\|")[0];
            List<StationAccessibilityScreen.PlatformEntry> platforms = new ArrayList<>();
            station.savedRails.forEach(platform -> platforms.add(new StationAccessibilityScreen.PlatformEntry(
                    platform.getId(), platform.getName().split("\\|")[0])));
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.accessibility.button"),
                            button -> client.setScreen(new StationAccessibilityScreen(stationId, display, platforms, screen)))
                    .dimensions(screen.width - 124, screen.height - 24, 120, 20)
                    .build());
        });
    }

    private static org.mtr.core.data.Station readStation(Screen screen) {
        try {
            if (editScreenDataField == null) {
                if (editScreenDataLookupFailed) {
                    return null;
                }
                editScreenDataField = org.mtr.mod.screen.EditNameColorScreenBase.class.getDeclaredField("data");
                editScreenDataField.setAccessible(true);
            }
            Object value = editScreenDataField.get(screen);
            return value instanceof org.mtr.core.data.Station ? (org.mtr.core.data.Station) value : null;
        } catch (Exception e) {
            editScreenDataLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read the station from EditStationScreen", e);
            return null;
        }
    }

    // ---------------------------------------- per-route announcement templates

    /** Server → client template sync (join + after each edit). */
    private static void registerAnnouncementTemplateSync() {
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.ANNOUNCEMENT_TEMPLATES_S2C,
                (client, handler, buf, responseSender) -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10_000) {
                        return;
                    }
                    Map<Long, String> templates = new HashMap<>(Math.max(1, count));
                    for (int i = 0; i < count; i++) {
                        long routeId = buf.readLong();
                        templates.put(routeId, buf.readString(AddonNetworking.MAX_TEMPLATE_LENGTH));
                    }
                    client.execute(() -> ClientAnnouncementTemplates.replace(templates));
                });
    }

    /**
     * MTR's Edit Route screen is replaced by our {@link RouteSettingsScreen} (stops,
     * route fields, line / route style, next-station template, on-board PA) the same way
     * the platform screen is: swapped in {@code AFTER_INIT}, one tick later; "MTR screen"
     * re-opens the original with a one-shot bypass. Config {@code replaceRouteScreen} off =
     * MTR's screen with a single "Route settings…" button. (Replaces the old
     * Announcement… / Train PA… / Line style… corner buttons.)
     */
    private static void registerRouteScreen() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof org.mtr.mod.screen.EditRouteScreen)) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            org.mtr.core.data.Route route = readRoute(screen);
            if (route == null) {
                return;
            }
            if (AddonClientConfig.get().replaceRouteScreen && !RouteSettingsScreen.consumeBypass()) {
                Screen previous = readPreviousScreen(screen);
                client.send(() -> {
                    if (client.currentScreen == screen) {
                        client.setScreen(new RouteSettingsScreen(route, previous, screen));
                    }
                });
                return;
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Route settings…"),
                            button -> client.setScreen(new RouteSettingsScreen(route, screen, null)))
                    .dimensions(screen.width - 124, screen.height - 24, 120, 20)
                    .build());
        });
    }

    private static Field editScreenDataField;
    private static boolean editScreenDataLookupFailed;

    private static org.mtr.core.data.Route readRoute(Screen screen) {
        try {
            if (editScreenDataField == null) {
                if (editScreenDataLookupFailed) {
                    return null;
                }
                editScreenDataField = org.mtr.mod.screen.EditNameColorScreenBase.class.getDeclaredField("data");
                editScreenDataField.setAccessible(true);
            }
            Object value = editScreenDataField.get(screen);
            return value instanceof org.mtr.core.data.Route ? (org.mtr.core.data.Route) value : null;
        } catch (Exception e) {
            editScreenDataLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read the route from EditRouteScreen", e);
            return null;
        }
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
                    Map<Long, Map<Long, Byte>> sources = new HashMap<>(Math.max(1, platformCount));
                    for (int i = 0; i < platformCount; i++) {
                        long platformId = buf.readLong();
                        int routeCount = buf.readVarInt();
                        if (routeCount < 0 || routeCount > AddonNetworking.MAX_ROUTE_OVERRIDES) {
                            return;
                        }
                        Map<Long, Long> byRoute = new HashMap<>(Math.max(1, routeCount));
                        Map<Long, Byte> bySource = new HashMap<>(Math.max(1, routeCount));
                        for (int j = 0; j < routeCount; j++) {
                            long routeId = buf.readLong();
                            long millis = buf.readVarInt();
                            byRoute.put(routeId, millis);
                            bySource.put(routeId, buf.readByte());
                        }
                        overrides.put(platformId, byRoute);
                        sources.put(platformId, bySource);
                    }
                    client.execute(() -> ClientDwellOverrides.replace(overrides, sources));
                });
    }


    // ------------------------------------------ Feature 5: platform groups

    /** Server → client platform-group sync (join + after each edit). */
    private static void registerPlatformGroupSync() {
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.PLATFORM_GROUPS_S2C,
                (client, handler, buf, responseSender) -> {
                    int groupCount = buf.readVarInt();
                    if (groupCount < 0 || groupCount > 10_000) {
                        return;
                    }
                    Map<String, List<Long>> groups = new HashMap<>(Math.max(1, groupCount));
                    for (int i = 0; i < groupCount; i++) {
                        long routeId = buf.readLong();
                        int stopIndex = buf.readVarInt();
                        int memberCount = buf.readVarInt();
                        if (stopIndex < 0 || stopIndex > AddonNetworking.MAX_STOP_INDEX
                                || memberCount < 0 || memberCount > AddonNetworking.MAX_GROUP_SIZE) {
                            return;
                        }
                        List<Long> members = new ArrayList<>(memberCount);
                        for (int j = 0; j < memberCount; j++) {
                            members.add(buf.readLong());
                        }
                        groups.put(routeId + ":" + stopIndex, members);
                    }
                    client.execute(() -> ClientPlatformGroups.replace(groups));
                });
    }


    // -------------------------------------------- Feature 3: lift door sides

    /**
     * Server → client lift door-side sync (join + after each edit). The map is
     * replaced wholesale on the client thread — the same thread the render
     * mixin reads it from.
     */
    private static void registerLiftDoorSync() {
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.LIFT_DOORS_S2C,
                (client, handler, buf, responseSender) -> {
                    int liftCount = buf.readVarInt();
                    if (liftCount < 0 || liftCount > 10_000) {
                        return;
                    }
                    Map<Long, LiftDoorSides> doors = new HashMap<>(Math.max(1, liftCount));
                    for (int i = 0; i < liftCount; i++) {
                        long liftId = buf.readLong();
                        LiftDoorSides sides = LiftDoorSides.fromMask(buf.readByte() & 0x0F);
                        if (sides.any()) {
                            doors.put(liftId, sides);
                        }
                    }
                    client.execute(() -> ClientLiftDoors.replace(doors));
                });
    }

    /**
     * The "Door sides…" button on MTR's {@code LiftCustomizationScreen}
     * (which IS a vanilla screen through the mapping layer, like PlatformScreen).
     * The edited {@link Lift} sits in that screen's private final {@code lift}
     * field (javap-verified against 4.0.1), read by cached reflection.
     */
    private static void registerLiftDoorSidesButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof LiftCustomizationScreen) || !AddonClientConfig.get().showLiftDoorSidesButton) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            Lift lift = readLift(screen);
            if (lift == null) {
                return;
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.lift_doors.button"),
                            button -> client.setScreen(new LiftDoorSidesScreen(lift, screen)))
                    .dimensions(screen.width - 104, screen.height - 24, 100, 20)
                    .build());
        });
    }

    // ------------------------------------------------------ platform screen

    /**
     * MTR's {@code PlatformScreen} (number + one dwell slider) is replaced by our
     * {@link PlatformSettingsScreen}, which also carries per-route dwell, the hold rule,
     * platform groups and the schedule check. MTR opens its screen from three places
     * (brush on a platform, dashboard list, station edit), so the swap happens in
     * {@code AFTER_INIT}, deferred one tick so MTR's own {@code setScreen} finishes first.
     * The replaced screen object is handed over: "MTR screen" in ours re-opens it (with a
     * one-shot bypass), and closing ours returns to MTR's own previous screen.
     * Off in the client config ({@code replacePlatformScreen}) = MTR's screen with one
     * "Platform settings…" button instead.
     */
    private static void registerPlatformScreen() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PlatformScreen)) {
                return;
            }
            // Same visibility condition as MTR's own dashboard edits; the server still
            // validates op level on save.
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            Platform platform = readPlatform(screen);
            if (platform == null) {
                return;
            }
            if (AddonClientConfig.get().replacePlatformScreen && !PlatformSettingsScreen.consumeBypass()) {
                Screen previous = readPreviousScreen(screen);
                client.send(() -> {
                    if (client.currentScreen == screen) {
                        client.setScreen(new PlatformSettingsScreen(platform, previous, screen));
                    }
                });
                return;
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.platform_settings.button"),
                            button -> client.setScreen(new PlatformSettingsScreen(platform, screen, null)))
                    .dimensions(screen.width - 124, screen.height - 24, 120, 20)
                    .build());
        });
    }

    /** MTR's {@code MTRScreenBase.previousScreen} (a mapping holder around a vanilla screen), or null. */
    private static Screen readPreviousScreen(Screen screen) {
        try {
            Field field = org.mtr.mod.screen.MTRScreenBase.class.getDeclaredField("previousScreen");
            field.setAccessible(true);
            Object holder = field.get(screen);
            if (holder == null) {
                return null;
            }
            Object data = holder.getClass().getField("data").get(holder);
            return data instanceof Screen vanilla ? vanilla : null;
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read PlatformScreen's previous screen", e);
            return null;
        }
    }

    private static Lift readLift(Screen screen) {
        try {
            if (liftField == null) {
                if (liftLookupFailed) {
                    return null;
                }
                liftField = LiftCustomizationScreen.class.getDeclaredField("lift");
                liftField.setAccessible(true);
            }
            Object value = liftField.get(screen);
            return value instanceof Lift ? (Lift) value : null;
        } catch (Exception e) {
            liftLookupFailed = true;
            StationAnnouncer.LOGGER.warn("Could not read LiftCustomizationScreen's lift; door-sides button disabled", e);
            return null;
        }
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
