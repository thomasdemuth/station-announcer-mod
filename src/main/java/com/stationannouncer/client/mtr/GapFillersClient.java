package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.GapFillerBlockEntity;
import com.stationannouncer.mtr.GapFillers;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

import java.util.function.Consumer;

/**
 * Gap fillers, client side: the moving plate's renderer and the brush screen.
 * One call from {@link MtrPidsClient}, like {@link SubwayWallsClient}.
 */
public final class GapFillersClient {
    private GapFillersClient() {
    }

    public static void register() {
        BlockEntityRendererFactories.register(GapFillers.GAP_FILLER_BLOCK_ENTITY, GapFillerRenderer::new);

        Consumer<BlockEntity> previous = StationAnnouncer.GUI_OPENER;
        StationAnnouncer.GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof GapFillerBlockEntity filler) {
                MinecraftClient.getInstance().setScreen(new GapFillerScreen(filler));
            } else {
                previous.accept(blockEntity);
            }
        };
    }
}
