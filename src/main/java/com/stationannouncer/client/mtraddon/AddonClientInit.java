package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.DepotGroupNetworking;
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
            ClientLiftDoors.clear();
            ClientPlatformGroups.clear();
            ClientDispatchInfo.clear();
            ClientDisruptions.clear();
            ClientStopChanges.clear();
            ClientDepotGroups.clear();
        });

        // Server → client dispatch-availability sync (join only; one int).
        ClientPlayNetworking.registerGlobalReceiver(AddonNetworking.DISPATCH_INFO_S2C,
                (client, handler, buf, responseSender) -> {
                    int port = buf.readInt();
                    client.execute(() -> ClientDispatchInfo.setPort(port));
                });

        registerDwellOverrideSync();
        registerRouteDwellButton();
        registerLiftDoorSync();
        registerLiftDoorSidesButton();
        registerPlatformGroupSync();
        registerPlatformGroupButton();
        registerDispatchDashboardButton();
        registerDisruptionSync();
        registerDisruptionsDashboardButton();
        registerDepotGroupSync();
        registerToolsDashboardButton();
        DrivingHud.register(); // Feature 4 — driving HUD (registers its own disconnect cleanup)

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

    // -------------------------------------------- Dispatch web UI: dashboard button

    /**
     * Adds a "Dispatch" button beside MTR's "Transport System Map" button on every
     * MTR dashboard (all transport modes share {@code DashboardScreen}). MTR's own
     * button opens the CLIENT-side proxy webserver in multiplayer, which does not
     * host the dispatch servlets, so this one targets the game server's synced
     * port instead ({@link ClientDispatchInfo}). Hidden when the server reports no
     * dispatch UI (feature off, webserver off, or server without the addon).
     */
    private static void registerDispatchDashboardButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DashboardScreen) || !ClientDispatchInfo.isAvailable()) {
                return;
            }
            // Find MTR's map button by its localized label, shrink it, and slot ours
            // into the freed space so the bottom row keeps its layout.
            String mapLabel = Text.translatable("gui.mtr.transport_system_map").getString();
            ClickableWidget mapButton = null;
            for (ClickableWidget widget : Screens.getButtons(screen)) {
                if (widget instanceof ButtonWidget && mapLabel.equals(widget.getMessage().getString())) {
                    mapButton = widget;
                    break;
                }
            }
            if (mapButton == null) {
                return; // MTR layout changed — skip rather than overlap
            }
            int dispatchWidth = Math.min(80, mapButton.getWidth() / 2);
            if (dispatchWidth < 40) {
                return; // row too cramped to split
            }
            mapButton.setWidth(mapButton.getWidth() - dispatchWidth - 2);
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.dispatch.button"),
                            button -> Util.getOperatingSystem().open(URI.create(ClientDispatchInfo.url())))
                    .dimensions(mapButton.getX() + mapButton.getWidth() + 2, mapButton.getY(), dispatchWidth, mapButton.getHeight())
                    .build());
        });
    }

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

    /**
     * Adds a "Disruptions" button to MTR's dashboard, one row below the addon's
     * "Dispatch" button: it splits MTR's "Resource Pack Creator" button the same
     * way the dispatch button splits the "Transport System Map" button above it,
     * so the two addon buttons line up on the right of the bottom two rows and
     * MTR's own layout keeps working. {@code DashboardScreen} itself is never
     * restructured — this is a plain {@code ScreenEvents.AFTER_INIT} injection.
     */
    private static void registerDisruptionsDashboardButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DashboardScreen) || !AddonClientConfig.get().showDisruptionsButton) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            String creatorLabel = Text.translatable("gui.mtr.resource_pack_creator").getString();
            ClickableWidget hostButton = null;
            for (ClickableWidget widget : Screens.getButtons(screen)) {
                if (widget instanceof ButtonWidget && creatorLabel.equals(widget.getMessage().getString())) {
                    hostButton = widget;
                    break;
                }
            }
            if (hostButton == null) {
                return; // MTR layout changed — skip rather than overlap
            }
            int buttonWidth = Math.min(80, hostButton.getWidth() / 2);
            if (buttonWidth < 40) {
                return; // row too cramped to split
            }
            hostButton.setWidth(hostButton.getWidth() - buttonWidth - 2);
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.disruptions.button"),
                            button -> client.setScreen(new DisruptionsScreen(screen)))
                    .dimensions(hostButton.getX() + hostButton.getWidth() + 2, hostButton.getY(),
                            buttonWidth, hostButton.getHeight())
                    .build());
        });
    }

    // ------------------------------- Depot groups + line tools (dashboard button)

    /** Server → client depot-group sync (join + after every edit and offset refresh). */
    private static void registerDepotGroupSync() {
        ClientPlayNetworking.registerGlobalReceiver(DepotGroupNetworking.DEPOT_GROUPS_S2C,
                (client, handler, buf, responseSender) -> {
                    int groupCount = buf.readVarInt();
                    if (groupCount < 0 || groupCount > DepotGroupNetworking.MAX_GROUPS) {
                        return;
                    }
                    List<ClientDepotGroups.Group> groups = new ArrayList<>(groupCount);
                    for (int i = 0; i < groupCount; i++) {
                        long id = buf.readLong();
                        String name = buf.readString(DepotGroupNetworking.MAX_NAME_LENGTH);
                        int memberCount = buf.readVarInt();
                        if (memberCount < 0 || memberCount > DepotGroupNetworking.MAX_DEPOTS) {
                            return;
                        }
                        List<ClientDepotGroups.Member> members = new ArrayList<>(memberCount);
                        for (int j = 0; j < memberCount; j++) {
                            long depotId = buf.readLong();
                            int offsetMillis = buf.readVarInt(); // -1 = not computed yet
                            members.add(new ClientDepotGroups.Member(depotId, offsetMillis));
                        }
                        groups.add(new ClientDepotGroups.Group(id, name, List.copyOf(members)));
                    }
                    client.execute(() -> ClientDepotGroups.replace(groups));
                });
    }

    /**
     * Adds a "Tools…" button to MTR's dashboard for the addon's line/depot tools
     * (duplicate a line, depot groups). It splits MTR's own Options button the same way
     * the addon's "Dispatch" and "Disruptions" buttons split the two map rows beside it,
     * so MTR's layout keeps working and {@code DashboardScreen} is never restructured.
     * One button, not two, because those two rows are the only splittable space left.
     */
    private static void registerToolsDashboardButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DashboardScreen) || !AddonClientConfig.get().showToolsButton) {
                return;
            }
            if (!MinecraftClientData.hasPermission()) {
                return;
            }
            // "Options..." is a vanilla key, so the label is always resolvable.
            String optionsLabel = Text.translatable("menu.options").getString();
            ClickableWidget hostButton = null;
            for (ClickableWidget widget : Screens.getButtons(screen)) {
                if (widget instanceof ButtonWidget && optionsLabel.equals(widget.getMessage().getString())) {
                    hostButton = widget;
                    break;
                }
            }
            if (hostButton == null) {
                return; // MTR layout changed — skip rather than overlap
            }
            int buttonWidth = Math.min(52, hostButton.getWidth() / 2);
            if (buttonWidth < 34) {
                return; // row too cramped to split
            }
            hostButton.setWidth(hostButton.getWidth() - buttonWidth - 2);
            Screens.getButtons(screen).add(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.tools.button"),
                            button -> client.setScreen(new DispatchToolsScreen(screen)))
                    .dimensions(hostButton.getX() + hostButton.getWidth() + 2, hostButton.getY(),
                            buttonWidth, hostButton.getHeight())
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

    /**
     * The "Platform group…" button on MTR's {@code PlatformScreen}, one slot
     * above Feature 2's "Per-route dwell…" button. The platform screen (not
     * {@code EditRouteScreen}) is the entry point — see
     * {@link PlatformGroupScreen}'s javadoc for the documented decision.
     */
    private static void registerPlatformGroupButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PlatformScreen) || !AddonClientConfig.get().showPlatformGroupButton) {
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
                            Text.translatable("gui.station_announcer.platform_groups.button"),
                            button -> client.setScreen(new PlatformGroupScreen(platform, screen)))
                    .dimensions(screen.width - 104, screen.height - 72, 100, 20)
                    .build());
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
