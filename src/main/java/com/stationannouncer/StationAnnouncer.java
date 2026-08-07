package com.stationannouncer;

import com.stationannouncer.command.AnnounceCommand;
import net.minecraft.block.entity.BlockEntity;
import com.stationannouncer.net.AnnouncerNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Consumer;

public class StationAnnouncer implements ModInitializer {
    public static final String MOD_ID = "station_announcer";
    public static final Logger LOGGER = LoggerFactory.getLogger("Station Announcer");

    /**
     * Installed by the client entrypoint to open the matching config screen
     * (announcer, control box, or speaker); a no-op on dedicated servers so no
     * client class is ever touched there.
     */
    public static Consumer<BlockEntity> GUI_OPENER = be -> {
    };

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModContent.register();
        AnnouncerNetworking.registerServerReceivers();
        AnnounceCommand.register();

        // NYC PIDS blocks need MTR's data and config screens; the module class
        // is only touched when MTR is actually present.
        if (FabricLoader.getInstance().isModLoaded("mtr")) {
            com.stationannouncer.mtr.MtrPids.register();
            com.stationannouncer.mtr.MtrPillars.register();
            com.stationannouncer.mtr.MtrStationDecor.register();
            LOGGER.info("MTR detected — NYC PIDS, Pillar Creators and station decor enabled");
        }

        // Drop all tracked announcers when a server stops (mainly relevant for
        // singleplayer, where the JVM outlives the integrated server).
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AnnouncerRegistry.clear());

        LOGGER.info("Station Announcer initialized");
    }
}
