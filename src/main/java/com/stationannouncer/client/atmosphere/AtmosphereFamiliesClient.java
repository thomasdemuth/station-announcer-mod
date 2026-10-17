package com.stationannouncer.client.atmosphere;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Render layers etc. of the block families (grime, litter, construction). */
@Environment(EnvType.CLIENT)
final class AtmosphereFamiliesClient {
    private AtmosphereFamiliesClient() {
    }

    static void register() {
        LitterClient.register();
        GrimeClient.register();
        ConstructionClient.register();
    }
}
