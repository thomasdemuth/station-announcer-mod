package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtr.ServicePosterBlockEntity;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Dev-rig only (reached from the dev client's commands.txt hook): opens the
 * poster screens from a text command so the headless rig can screenshot GUIs
 * that otherwise need a right-click. Never called in production.
 */
@Environment(EnvType.CLIENT)
public final class PosterDevHooks {
    private PosterDevHooks() {
    }

    public static void open(MinecraftClient client, String command) {
        String[] parts = command.trim().split("\\s+");
        try {
            switch (parts[0]) {
                case "#poster-list" -> client.setScreen(new PosterListScreen(Long.parseLong(parts[1]), null));
                case "#poster-editor" -> {
                    ServicePoster poster = ClientPosters.byId(Long.parseLong(parts[1]));
                    if (poster != null) {
                        PosterEditScreen screen = new PosterEditScreen(new ServicePoster.Builder(poster), null);
                        if (parts.length > 2) {
                            screen.selectForDev(switch (parts[2]) {
                                case "header" -> -2;
                                case "footer" -> -1;
                                default -> Integer.parseInt(parts[2]);
                            });
                        }
                        client.setScreen(screen);
                    }
                }
                case "#poster-picker" -> {
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    if (client.world != null && client.world.getBlockEntity(pos) instanceof ServicePosterBlockEntity sign) {
                        client.setScreen(new PosterSignPickerScreen(sign));
                    }
                }
                case "#poster-close" -> client.setScreen(null);
                default -> {
                }
            }
        } catch (Exception ignored) {
            // malformed dev command — nothing to do
        }
    }
}
