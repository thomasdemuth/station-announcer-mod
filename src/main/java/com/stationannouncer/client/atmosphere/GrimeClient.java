package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.GrimeBlocks;
import com.stationannouncer.atmosphere.GrimeDecalBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/** Render layers of the grime decals: soft alpha = translucent, the cobweb = cutout. */
@Environment(EnvType.CLIENT)
public final class GrimeClient {
    private GrimeClient() {
    }

    public static void register() {
        for (GrimeDecalBlock block : GrimeBlocks.TRANSLUCENT) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getTranslucent());
        }
        BlockRenderLayerMap.INSTANCE.putBlock(GrimeBlocks.COBWEB_CORNER, RenderLayer.getCutout());
    }
}
