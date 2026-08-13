package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.SubwayWalls;
import com.stationannouncer.mtr.TileTabletBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import java.util.function.Consumer;

/**
 * Client hookup for the subway tile wall: the band's station tint, the name
 * tablet's renderer, and the brush screen.
 *
 * <p>The band is a greyscale texture multiplied by the station's colour — the
 * same mechanism as the station columns, and it reuses their cache: the colour
 * provider is called once per tinted quad while a chunk meshes, so a platform's
 * worth of band blocks would otherwise be thousands of station lookups per
 * rebuild.</p>
 */
@Environment(EnvType.CLIENT)
public final class SubwayWallsClient {
    private SubwayWallsClient() {
    }

    public static void register() {
        BlockEntityRendererFactories.register(SubwayWalls.TILE_TABLET_BLOCK_ENTITY,
                context -> new TileTabletRenderer());

        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.BLOCK.register(
                (state, world, pos, tintIndex) -> pos == null
                        ? MtrPidsClient.FALLBACK_STATION_COLOR : MtrPidsClient.stationColor(pos),
                SubwayWalls.TILE_BAND);
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> MtrPidsClient.FALLBACK_STATION_COLOR, SubwayWalls.TILE_BAND);

        // Chain onto the shared opener rather than editing it: the brush on a
        // tablet opens its editor, anything else falls through untouched.
        Consumer<BlockEntity> previous = StationAnnouncer.GUI_OPENER;
        StationAnnouncer.GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof TileTabletBlockEntity tablet) {
                MinecraftClient.getInstance().setScreen(new TileTabletScreen(tablet));
            } else {
                previous.accept(blockEntity);
            }
        };
    }
}
