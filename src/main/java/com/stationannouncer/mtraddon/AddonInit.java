package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
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
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AddonStore.flush());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HoldRuleEngine.clearRuntimeState());

        // Late joiners need the current rule map for the GUIs (and future HUDs).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                AddonNetworking.syncHoldRulesTo(sender));

        StationAnnouncer.LOGGER.info("MTR dispatch addon initialized");
    }
}
