package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.ConstructionBlocks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.block.Block;
import net.minecraft.client.render.RenderLayer;

/**
 * Render layers of the construction props: everything with see-through
 * texels (tape, scaffold ends, the sign's handle slot, lamp cages, the
 * barrier's forklift pockets) is cutout. All are opaque-or-clear, never
 * translucent.
 */
@Environment(EnvType.CLIENT)
public final class ConstructionClient {
    private ConstructionClient() {
    }

    public static void register() {
        for (Block block : new Block[]{
                ConstructionBlocks.TRAFFIC_CONE,
                ConstructionBlocks.WET_FLOOR_SIGN,
                ConstructionBlocks.BARRICADE,
                ConstructionBlocks.POLICE_BARRICADE,
                ConstructionBlocks.PLASTIC_BARRIER,
                ConstructionBlocks.CAUTION_TAPE,
                ConstructionBlocks.SCAFFOLD,
                ConstructionBlocks.SIDEWALK_SHED,
                ConstructionBlocks.SIDEWALK_SHED_LIGHT,
                ConstructionBlocks.WORK_LIGHT,
                ConstructionBlocks.CAGED_WORK_LAMP}) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
        }
    }
}
