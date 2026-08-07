package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.MtrPids;
import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.PidsBlockEntity;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import java.util.function.Consumer;

/** Client hookup for the NYC PIDS renderers. Only classloaded when MTR is present. */
@Environment(EnvType.CLIENT)
public final class MtrPidsClient {
    private MtrPidsClient() {
    }

    /** Fallback tint when a station-colored block sits outside any MTR station area. */
    private static final int FALLBACK_COLUMN_COLOR = 0x1F4D3A;

    public static void register() {
        BlockEntityRendererFactories.register(MtrPids.PIDS_BLOCK_ENTITY, context -> new PidsNycRenderer());
        BlockEntityRendererFactories.register(MtrStationDecor.DECOR_BLOCK_ENTITY, context -> new StationDecorRenderer());

        // Station-colored columns: MULTIPLY the station's line color over the
        // riveted texture (same mechanism as vanilla grass tinting), so the
        // rivets and shading stay visible. Guarded because the provider runs
        // on chunk-meshing worker threads while MTR data may be updating.
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.BLOCK.register(
                (state, world, pos, tintIndex) -> {
                    if (pos != null) {
                        try {
                            org.mtr.core.data.Station station = org.mtr.mod.InitClient.findStation(
                                    new org.mtr.mapping.holder.BlockPos(pos));
                            if (station != null) {
                                return station.getColor() & 0xFFFFFF;
                            }
                        } catch (Exception ignored) {
                            // fall through to the fallback color
                        }
                    }
                    return FALLBACK_COLUMN_COLOR;
                },
                MtrStationDecor.COLUMN_IRON_STATION, MtrStationDecor.COLUMN_IRON_NAMED_STATION);
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> FALLBACK_COLUMN_COLOR,
                MtrStationDecor.COLUMN_IRON_STATION, MtrStationDecor.COLUMN_IRON_NAMED_STATION);

        // Conduit pipes: the paint color is a block state property multiplied
        // over a grayscale texture (state-based, no world lookup needed).
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.BLOCK.register(
                (state, world, pos, tintIndex) ->
                        state.get(com.stationannouncer.mtr.PipeBlock.COLOR).rgb,
                MtrStationDecor.PIPE);
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> com.stationannouncer.mtr.PipeBlock.PipeColor.OFF_WHITE.rgb,
                MtrStationDecor.PIPE);

        // Chain onto the shared GUI opener: PIDS block entities get the mini's
        // "Next train" screen, decor blocks get the name screen; everything
        // else falls through to the base mod.
        Consumer<BlockEntity> previous = StationAnnouncer.GUI_OPENER;
        StationAnnouncer.GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof PidsBlockEntity pids) {
                MinecraftClient.getInstance().setScreen(new MiniPidsScreen(pids));
            } else if (blockEntity instanceof StationDecorBlockEntity decor) {
                // Same block entity, different screens: holding lights pick a
                // platform, everything else edits its name text.
                if (decor.getCachedState().getBlock() instanceof com.stationannouncer.mtr.HoldingLightBlock) {
                    MinecraftClient.getInstance().setScreen(new HoldingLightScreen(decor));
                } else {
                    MinecraftClient.getInstance().setScreen(new StationSignScreen(decor));
                }
            } else {
                previous.accept(blockEntity);
            }
        };
    }
}
