package com.stationannouncer.client.mtr;

import com.stationannouncer.client.material.MaterialClient;
import com.stationannouncer.mtr.CurvedPlatforms;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/**
 * Curved platform edges, client side: every state drawn by one
 * {@link com.stationannouncer.client.material.ShapeModel} running
 * {@link CurvedEdgeGeometry}. One call from {@link MtrPidsClient}.
 */
public final class CurvedPlatformsClient {
    private CurvedPlatformsClient() {
    }

    public static void register() {
        ModelLoadingPlugin.register(context -> {
            MaterialClient.registerStates(context, CurvedPlatforms.CURVED_PLATFORM_EDGE,
                    new CurvedEdgeGeometry(), CurvedEdgeGeometry.TEXTURES, "top");
            // Curved gap fillers: the same edge with the plate's slot (floor 12 px Union
            // Square, 10 px South Ferry — its plate drops as it rolls out); the plate
            // itself is GapFillerRenderer's.
            MaterialClient.registerStates(context, com.stationannouncer.mtr.GapFillers.CURVED_GAP_FILLER,
                    new CurvedEdgeGeometry(12f), CurvedEdgeGeometry.fillerTextures(false), "top");
            MaterialClient.registerStates(context, com.stationannouncer.mtr.GapFillers.CURVED_GAP_FILLER_LOOP,
                    new CurvedEdgeGeometry(10f), CurvedEdgeGeometry.fillerTextures(true), "top");
        });
    }
}
