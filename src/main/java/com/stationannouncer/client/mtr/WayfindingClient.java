package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.ExitMarkerBlockEntity;
import com.stationannouncer.mtr.PlaceMarkerBlockEntity;
import com.stationannouncer.mtr.Wayfinding;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

import java.util.function.Consumer;

/**
 * Wayfinding, client side: the marker renderer (pins shown only while a
 * revealing tool is held) and the two editors. One call from
 * {@link MtrPidsClient}, like {@link GapFillersClient}.
 */
public final class WayfindingClient {
    private WayfindingClient() {
    }

    public static void register() {
        BlockEntityRendererFactories.register(Wayfinding.EXIT_MARKER_BLOCK_ENTITY, MarkerRenderer::new);
        BlockEntityRendererFactories.register(Wayfinding.PLACE_MARKER_BLOCK_ENTITY, MarkerRenderer::new);
        // station layouts: the Layout tab's data and the in-world path overlay
        ClientLayouts.register();

        Consumer<BlockEntity> previous = StationAnnouncer.GUI_OPENER;
        StationAnnouncer.GUI_OPENER = blockEntity -> {
            if (blockEntity instanceof ExitMarkerBlockEntity exit) {
                MinecraftClient.getInstance().setScreen(new ExitMarkerScreen(exit));
            } else if (blockEntity instanceof PlaceMarkerBlockEntity place) {
                MinecraftClient.getInstance().setScreen(new PlaceMarkerScreen(place));
            } else {
                previous.accept(blockEntity);
            }
        };
    }

    /**
     * Dev-only rig hooks (run/commands.txt), so a headless client can open and
     * drive the editors: {@code #marker-editor x y z} opens one;
     * {@code #marker-exit x y z NAME destination…} adds (or reuses) exit NAME on
     * the marker's station with that destination, pins the marker to it and
     * saves through the real save path; {@code #marker-place x y z category name…}
     * saves a place the same way.
     */
    public static void devHook(MinecraftClient client, String cmd) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment() || client.world == null) {
            return;
        }
        String[] a = cmd.split("\\s+");
        if (a.length < 4) {
            return;
        }
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(
                Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        BlockEntity be = client.world.getBlockEntity(pos);
        String rest = a.length > 5 ? String.join(" ", java.util.Arrays.copyOfRange(a, 5, a.length)) : "";
        switch (a[0]) {
            case "#marker-editor" -> {
                if (be != null) {
                    StationAnnouncer.GUI_OPENER.accept(be);
                }
            }
            case "#marker-layout" -> {
                if (be instanceof ExitMarkerBlockEntity exit) {
                    ExitMarkerScreen screen = new ExitMarkerScreen(exit);
                    client.setScreen(screen);
                    screen.devShowLayout(a.length > 4 ? Integer.parseInt(a[4]) : 0);
                }
            }
            case "#marker-exit" -> {
                if (be instanceof ExitMarkerBlockEntity exit && a.length > 4) {
                    ExitMarkerScreen screen = new ExitMarkerScreen(exit);
                    client.setScreen(screen);
                    screen.devAddAndPin(a[4], rest);
                }
            }
            case "#marker-place" -> {
                if (be instanceof PlaceMarkerBlockEntity place && a.length > 4) {
                    PlaceMarkerScreen screen = new PlaceMarkerScreen(place);
                    client.setScreen(screen);
                    screen.devSave(a[4], rest);
                }
            }
            default -> {
            }
        }
    }
}
