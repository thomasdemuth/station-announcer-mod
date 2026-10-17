package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.LitterBlocks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/** Render layers for the litter family: alpha-cut ash/dust decals and the can liner's ragged hem. */
@Environment(EnvType.CLIENT)
public final class LitterClient {
    private LitterClient() {
    }

    public static void register() {
        BlockRenderLayerMap.INSTANCE.putBlock(LitterBlocks.LITTER, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(LitterBlocks.SWEPT_PILE, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(LitterBlocks.TRASH_CAN, RenderLayer.getCutout());
    }
}
