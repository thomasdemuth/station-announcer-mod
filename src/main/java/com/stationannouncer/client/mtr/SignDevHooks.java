package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import com.stationannouncer.mtr.sign.SignFaces;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/**
 * Dev-rig only (reached from the dev client's commands.txt hook): opens the
 * sign editor for a block, or loads a sign JSON file into a block through the
 * normal update packet — the headless rig cannot right-click, and chat
 * commands are capped at 256 characters so {@code /data merge} cannot carry a
 * sign. Never called in production.
 */
@Environment(EnvType.CLIENT)
public final class SignDevHooks {
    private SignDevHooks() {
    }

    public static void open(MinecraftClient client, String command) {
        String[] parts = command.trim().split("\\s+");
        try {
            switch (parts[0]) {
                case "#sign-editor" -> {
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    if (client.world != null && client.world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                        SignEditScreen screen = new SignEditScreen(decor);
                        client.setScreen(screen);
                        if (parts.length > 4) {
                            screen.selectForDev(parts[4]);
                        }
                    }
                }
                case "#sign-load" -> {
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    String json = java.nio.file.Files.readString(new java.io.File(client.runDirectory, parts[4]).toPath());
                    SignFaces faces = SignFaces.parse(json);
                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeBlockPos(pos);
                    faces.write(buf);
                    ClientPlayNetworking.send(MtrStationDecor.UPDATE_SIGN_C2S, buf);
                }
                case "#sign-close" -> client.setScreen(null);
                default -> {
                }
            }
        } catch (Exception e) {
            com.stationannouncer.StationAnnouncer.LOGGER.warn("sign dev hook failed: {}", command, e);
        }
    }
}
