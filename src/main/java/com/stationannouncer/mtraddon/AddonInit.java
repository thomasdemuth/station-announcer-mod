package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.dispatch.DispatchRegistry;
import com.stationannouncer.mtraddon.dispatch.DispatchStreamer;
import com.stationannouncer.mtraddon.dispatch.DispatchWebSetup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
    private static int holdStateCountdown;

    private AddonInit() {
    }

    public static void register() {
        AddonNetworking.registerServerReceivers();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AddonServerConfig.get(); // load (and cache) the config before any simulator asks for it
            AddonStore.load(server);
            // MTR (our dependency, so it initialized and registered first) has already
            // constructed Main by now; explain in the log when the dispatch UI is absent.
            DispatchWebSetup.logAvailability();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AddonStore.flush();
            // Dispatch teardown before MTR's Main.stop(). Clear the registry FIRST so
            // late servlet requests answer 503 and no new SSE client can register (which
            // would restart the streamer thread we are about to stop), then drop the
            // connected clients and the streamer thread itself.
            DispatchRegistry.clear();
            DispatchStreamer.shutdown();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HoldRuleEngine.clearRuntimeState();
            PlatformGroupEngine.clearRuntimeState();
            lastHoldState = Set.of();
            holdStateCountdown = 0;
        });

        // Which platforms are holding a train right now, for the yellow holding
        // lights. Twice a second, and only sent when the answer changes — which,
        // with no train being held, is never: the whole tick is one isEmpty check.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--holdStateCountdown > 0) {
                return;
            }
            holdStateCountdown = HOLD_STATE_POLL_TICKS;
            Set<Long> held = HoldRuleEngine.heldPlatforms();
            if (held.equals(lastHoldState)) {
                return;
            }
            lastHoldState = held;
            AddonNetworking.broadcastHoldState(server, held);
        });

        // Late joiners need the current rule/override maps for the GUIs (and future HUDs).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AddonNetworking.syncHoldRulesTo(sender);
            AddonNetworking.syncHoldStateTo(sender, lastHoldState);
            AddonNetworking.syncDwellOverridesTo(sender);
            AddonNetworking.syncLiftDoorsTo(sender);
            AddonNetworking.syncPlatformGroupsTo(sender);
            AddonNetworking.syncDispatchInfoTo(sender);
        });

        StationAnnouncer.LOGGER.info("MTR dispatch addon initialized");
    }
}
