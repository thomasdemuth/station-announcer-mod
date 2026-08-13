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
    static final int FALLBACK_STATION_COLOR = 0x1F4D3A;

    /**
     * Station tint per column position. The block color provider is called
     * once per tinted quad while a chunk is being meshed — a whole station's
     * worth of columns meant hundreds of full station scans per rebuild.
     *
     * <p>Concurrent because chunk meshing runs on worker threads; entries are
     * immutable so a racing reader either sees the old one or the new one.
     * The tint only changes when the station is edited, and MTR needs a chunk
     * rebuild to show that anyway.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, ColorEntry> STATION_COLORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COLOR_TTL_MS = 5000;
    private static final int MAX_COLOR_ENTRIES = 8192;

    private record ColorEntry(long expiry, int color) {
    }

    static int stationColor(net.minecraft.util.math.BlockPos pos) {
        long key = pos.asLong();
        long now = System.currentTimeMillis();
        ColorEntry entry = STATION_COLORS.get(key);
        if (entry != null && now < entry.expiry()) {
            return entry.color();
        }
        int color = FALLBACK_STATION_COLOR;
        try {
            // Guarded: this runs on chunk-meshing threads while MTR data may
            // be updating underneath us.
            org.mtr.core.data.Station station = org.mtr.mod.InitClient.findStation(
                    new org.mtr.mapping.holder.BlockPos(pos));
            if (station != null) {
                color = station.getColor() & 0xFFFFFF;
            }
        } catch (Exception ignored) {
            // fall through to the fallback color
        }
        if (STATION_COLORS.size() > MAX_COLOR_ENTRIES) {
            STATION_COLORS.clear();
        }
        STATION_COLORS.put(key, new ColorEntry(now + COLOR_TTL_MS, color));
        return color;
    }

    public static void register() {
        // The exit door's upper half is a wire-mesh window (alpha holes).
        net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
                com.stationannouncer.mtr.MtrStationDecor.EMERGENCY_EXIT_DOOR,
                net.minecraft.client.render.RenderLayer.getCutoutMipped());

        BlockEntityRendererFactories.register(MtrPids.PIDS_BLOCK_ENTITY, context -> new PidsNycRenderer());
        BlockEntityRendererFactories.register(MtrStationDecor.DECOR_BLOCK_ENTITY, context -> new StationDecorRenderer());
        BlockEntityRendererFactories.register(MtrStationDecor.STOP_MARKER_BLOCK_ENTITY, context -> new StopMarkerRenderer());
        BlockEntityRendererFactories.register(MtrPids.RAILROAD_PIDS_BLOCK_ENTITY, context -> new RailroadPidsRenderer());
        BlockEntityRendererFactories.register(MtrPids.RAILROAD_HANGING_BLOCK_ENTITY, context -> new RailroadHangingRenderer());
        BlockEntityRendererFactories.register(MtrPids.RAILROAD_DEPARTURE_BLOCK_ENTITY, context -> new RailroadDepartureRenderer());
        BlockEntityRendererFactories.register(MtrPids.DEPARTURE_BOARD_BLOCK_ENTITY, context -> new DepartureBoardRenderer());
        BlockEntityRendererFactories.register(MtrPids.DEPARTURE_BOARD_HANGING_BLOCK_ENTITY, context -> new DepartureBoardRenderer());

        // The renderers keep small per-position state maps (holding-light
        // departure windows, next-train lit state); drop them when leaving a
        // world so nothing leaks or carries over between servers.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    PidsNycRenderer.clearWorldState();
                    StationDecorRenderer.clearWorldState();
                    RailroadRouteData.clear();
                    MtrDataCache.clear();
                    STATION_COLORS.clear();
                });

        // Glyph widths are memoized for the marquee loops — drop them when the
        // font itself may have changed.
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public net.minecraft.util.Identifier getFabricId() {
                        return StationAnnouncer.id("font_metrics");
                    }

                    @Override
                    public void reload(net.minecraft.resource.ResourceManager manager) {
                        CanvasPainter.clearFontCache();
                    }
                });

        // Station-colored columns: MULTIPLY the station's line color over the
        // riveted texture (same mechanism as vanilla grass tinting), so the
        // rivets and shading stay visible. Guarded because the provider runs
        // on chunk-meshing worker threads while MTR data may be updating.
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.BLOCK.register(
                (state, world, pos, tintIndex) -> pos == null ? FALLBACK_STATION_COLOR : stationColor(pos),
                MtrStationDecor.COLUMN_IRON_STATION, MtrStationDecor.COLUMN_IRON_NAMED_STATION);
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> FALLBACK_STATION_COLOR,
                MtrStationDecor.COLUMN_IRON_STATION, MtrStationDecor.COLUMN_IRON_NAMED_STATION);

        // Tile wall: the band's station tint and the name tablet's renderer.
        SubwayWallsClient.register();

        // The brush's full PIDS settings screen (the bare-click mini toggle
        // stays on the shared GUI opener below).
        MtrPids.CONFIG_GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof PidsBlockEntity pids) {
                MinecraftClient.getInstance().setScreen(new NycPidsScreen(pids));
            }
        };

        // Chain onto the shared GUI opener: PIDS block entities get the mini's
        // "Next train" screen, decor blocks get the name screen; everything
        // else falls through to the base mod.
        Consumer<BlockEntity> previous = StationAnnouncer.GUI_OPENER;
        StationAnnouncer.GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof com.stationannouncer.mtr.DepartureBoardBlockEntity board) {
                net.minecraft.client.MinecraftClient.getInstance().setScreen(new DepartureBoardScreen(board));
            } else if (blockEntity instanceof com.stationannouncer.mtr.RailroadPidsBlockEntity railroad) {
                MinecraftClient.getInstance().setScreen(new RailroadPidsScreen(railroad));
            } else if (blockEntity instanceof com.stationannouncer.mtr.StopMarkerBlockEntity marker) {
                MinecraftClient.getInstance().setScreen(new StopMarkerScreen(marker));
            } else if (blockEntity instanceof PidsBlockEntity pids) {
                MinecraftClient.getInstance().setScreen(new MiniPidsScreen(pids));
            } else if (blockEntity instanceof StationDecorBlockEntity decor) {
                // Same block entity, different screens: holding lights pick a
                // platform, everything else edits its name text.
                if (decor.getCachedState().getBlock() instanceof com.stationannouncer.mtr.HoldingLightBlock) {
                    MinecraftClient.getInstance().setScreen(new HoldingLightScreen(decor));
                } else if (decor.getCachedState().getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock) {
                    // Entrance signs have two independently configurable faces
                    // and carry route bullets, so they get their own screen.
                    MinecraftClient.getInstance().setScreen(new RailingSignScreen(decor));
                } else {
                    MinecraftClient.getInstance().setScreen(new StationSignScreen(decor));
                }
            } else {
                previous.accept(blockEntity);
            }
        };
    }
}
