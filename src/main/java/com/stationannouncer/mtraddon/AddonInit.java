package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.dispatch.DispatchRegistry;
import com.stationannouncer.mtraddon.dispatch.DispatchStreamer;
import com.stationannouncer.mtraddon.dispatch.DispatchWebSetup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Entry point for the MTR dispatch addon (hold rules today; per-line dwell, lift
 * door sides, driving HUD and platform groups to follow). Registered from
 * {@link com.stationannouncer.StationAnnouncer#onInitialize()} behind
 * {@code isModLoaded("mtr")} like the other MTR modules, so this class — and every
 * {@code org.mtr.*} type the addon touches — is only classloaded when MTR exists.
 */
public final class AddonInit {
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
        });

        // Late joiners need the current rule/override maps for the GUIs (and future HUDs).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AddonNetworking.syncHoldRulesTo(sender);
            AddonNetworking.syncDwellOverridesTo(sender);
            AddonNetworking.syncLiftDoorsTo(sender);
            AddonNetworking.syncPlatformGroupsTo(sender);
        });

        StationAnnouncer.LOGGER.info("MTR dispatch addon initialized");
    }
}
