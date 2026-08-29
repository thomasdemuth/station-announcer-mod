package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.analytics.AnalyticsCommand;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import com.stationannouncer.mtraddon.dispatch.DispatchRegistry;
import com.stationannouncer.mtraddon.dispatch.DispatchStreamer;
import com.stationannouncer.mtraddon.dispatch.DispatchWebSetup;
import com.stationannouncer.mtraddon.dispatch.PlayerPositions;
import com.stationannouncer.mtraddon.dispatch.SatelliteScanner;
import com.stationannouncer.mtraddon.dispatch.TerrainScanner;
import com.stationannouncer.mtraddon.disruption.DisruptionBroadcaster;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.mtraddon.disruption.StopOverlayEngine;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.util.WorldSavePath;
import java.util.Set;

/**
 * Entry point for the MTR dispatch addon (hold rules today; per-line dwell, lift
 * door sides, driving HUD and platform groups to follow). Registered from
 * {@link com.stationannouncer.StationAnnouncer#onInitialize()} behind
 * {@code isModLoaded("mtr")} like the other MTR modules, so this class — and every
 * {@code org.mtr.*} type the addon touches — is only classloaded when MTR exists.
 */
public final class AddonInit {
    /** How often the held-platform set is compared against what clients were last told. */
    private static final int HOLD_STATE_POLL_TICKS = 10;

    /** What the clients currently believe; the ticker only sends when this changes. */
    private static Set<Long> lastHoldState = Set.of();
    private static Set<Long> lastDoorObstructions = Set.of();
    private static int holdStateCountdown;

    private AddonInit() {
    }

    /** Feature 6: the expiry check + disruption broadcast run once a second. */
    private static final int DISRUPTION_POLL_TICKS = 20;

    private static int disruptionCountdown;

    /** Dispatch map player layer: four captures a second, matching the stream's cadence. */
    private static final int PLAYER_POLL_TICKS = 5;

    private static int playerCountdown;

    public static void register() {
        AddonNetworking.registerServerReceivers();
        DisruptionNetworking.registerServerReceivers();
        DepotGroupNetworking.registerServerReceivers();
        AnalyticsCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AddonServerConfig.get(); // load (and cache) the config before any simulator asks for it
            AddonStore.load(server);
            // Dispatch-map terrain: retains the server (nothing else in the addon does)
            // and reads whatever water polygons a previous /dispatch terrain scan cached.
            TerrainScanner.onServerStarted(server);
            // Satellite basemap: the same lifecycle, its own state machine. Reads only the
            // per-dimension tile INDEXES here — the PNGs are streamed from disk per request.
            SatelliteScanner.onServerStarted(server);
            // Timetable analytics: opens <save>/station-announcer-addon/analytics/, prunes
            // expired day files, replays the recent tail into the metric window and starts
            // the writer thread. A no-op when analytics.enabled is false.
            AnalyticsRecorder.start(server.getSavePath(WorldSavePath.ROOT)
                    .resolve("station-announcer-addon").resolve("analytics").normalize());
            // MTR (our dependency, so it initialized and registered first) has already
            // constructed Main by now; explain in the log when the dispatch UI is absent.
            DispatchWebSetup.logAvailability();
            // …which also means MTR's depots already wrote today's departures BEFORE the
            // store above was read, so the depot-group stagger would be missing until
            // something regenerated them. Re-write the grouped depots' timetables once,
            // on their own simulator threads. No-op when the feature is off or nothing is
            // grouped.
            DepotGroupEngine.refreshOffsets(server, true);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AddonStore.flush();
            // Flush the queued analytics events and stop the writer before the simulators go.
            AnalyticsRecorder.stop();
            // Join any in-flight terrain.json write and drop the retained server/world refs.
            TerrainScanner.onServerStopping();
            // Join any in-flight tile encode and drop the retained server/world refs.
            SatelliteScanner.onServerStopping();
            // The player layer is live-only; nothing to flush, just forget everybody.
            PlayerPositions.clear();
            // Dispatch teardown before MTR's Main.stop(). Clear the registry FIRST so
            // late servlet requests answer 503 and no new SSE client can register (which
            // would restart the streamer thread we are about to stop), then drop the
            // connected clients and the streamer thread itself.
            DispatchRegistry.clear();
            DispatchStreamer.shutdown();
            com.stationannouncer.mtraddon.dispatch.DispatchEvents.clear();
            // Feature 6: drop the captured Simulator references before MTR stops them.
            MtrSimulators.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HoldRuleEngine.clearRuntimeState();
            PlatformGroupEngine.clearRuntimeState();
            DoorObstructionEngine.clearRuntimeState();
            StopOverlayEngine.clearRuntimeState();
            DisruptionBroadcaster.clearRuntimeState();
            DepotGroupEngine.clearRuntimeState();
            lastHoldState = Set.of();
            lastDoorObstructions = Set.of();
            holdStateCountdown = 0;
            disruptionCountdown = 0;
            playerCountdown = 0;
        });

        // Which platforms are holding a train right now (yellow holding lights)
        // and which vehicles have something stuck in their doors (HUD). Twice a
        // second, and only sent when an answer changes — which, with nothing held
        // or stuck, is never: the whole tick is two isEmpty checks.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Terrain scanning gets its time-budgeted slice EVERY tick, so it sits ahead
            // of the countdown early-return below. Two field reads when no scan is running.
            TerrainScanner.tick();
            // …and so does the satellite basemap's, from its own separate scan state:
            // both can be mid-scan in the same session without touching each other.
            SatelliteScanner.tick();
            // The dispatch map's player layer, four times a second. Ahead of the
            // countdown early-return below for the same reason: it is one list walk,
            // and with nobody online it is an isEmpty check.
            if (--playerCountdown <= 0) {
                playerCountdown = PLAYER_POLL_TICKS;
                PlayerPositions.capture(server);
            }
            // Feature 6 shares this ticker (no new per-tick loop): once a second it
            // retires whatever has expired and lets the disruption broadcaster do
            // its (heavily throttled) work. Both calls return after one or two
            // field reads when nothing is configured.
            if (--disruptionCountdown <= 0) {
                disruptionCountdown = DISRUPTION_POLL_TICKS;
                if (AddonStore.expireDue(System.currentTimeMillis())) {
                    DisruptionNetworking.broadcastStopChanges(server);
                    DisruptionNetworking.broadcastDisruptions(server);
                }
                DisruptionBroadcaster.tick(server);
            }
            if (--holdStateCountdown > 0) {
                return;
            }
            holdStateCountdown = HOLD_STATE_POLL_TICKS;
            Set<Long> held = HoldRuleEngine.heldPlatforms();
            if (!held.equals(lastHoldState)) {
                lastHoldState = held;
                AddonNetworking.broadcastHoldState(server, held);
            }
            Set<Long> obstructed = DoorObstructionEngine.obstructedVehicleIds();
            if (!obstructed.equals(lastDoorObstructions)) {
                lastDoorObstructions = obstructed;
                AddonNetworking.broadcastDoorObstructions(server, obstructed);
            }
        });

        // Late joiners need the current rule/override maps for the GUIs (and future HUDs).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AddonNetworking.syncHoldRulesTo(sender);
            AddonNetworking.syncHoldStateTo(sender, lastHoldState);
            AddonNetworking.syncDoorObstructionsTo(sender, lastDoorObstructions);
            AddonNetworking.syncDwellOverridesTo(sender);
            AddonNetworking.syncAnnouncementTemplatesTo(sender);
            AddonNetworking.syncAccessibilityTo(sender);
            AddonNetworking.syncLiftDoorsTo(sender);
            AddonNetworking.syncPlatformGroupsTo(sender);
            AddonNetworking.syncDispatchInfoTo(sender);
            DisruptionNetworking.syncStopChangesTo(sender);
            DisruptionNetworking.syncDisruptionsTo(sender);
            DepotGroupNetworking.syncDepotGroupsTo(sender);
        });

        StationAnnouncer.LOGGER.info("MTR dispatch addon initialized");
    }
}
