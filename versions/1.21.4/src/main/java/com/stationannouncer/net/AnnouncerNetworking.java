package com.stationannouncer.net;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.config.ServerConfig;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Channels:
 * <ul>
 *   <li>{@code station_announcer:announce} (S2C) — text, per-player volume, source pos,
 *       presentation flags. The server decides who is in radius (deduplicated across
 *       speakers); the client does chat display, chime and TTS.</li>
 *   <li>{@code station_announcer:update_announcer} (C2S) — announcer GUI "Done".</li>
 *   <li>{@code station_announcer:update_control_box} (C2S) — control box GUI "Done".</li>
 *   <li>{@code station_announcer:update_speaker} (C2S) — speaker GUI "Done".</li>
 *   <li>{@code station_announcer:unlink_all} (C2S) — control box GUI "Unlink all".</li>
 * </ul>
 * Every C2S packet is validated server-side (distance, permission, length caps).
 */
public final class AnnouncerNetworking {
    public static final Identifier ANNOUNCE_S2C = StationAnnouncer.id("announce");
    public static final Identifier UPDATE_ANNOUNCER_C2S = StationAnnouncer.id("update_announcer");
    public static final Identifier UPDATE_CONTROL_BOX_C2S = StationAnnouncer.id("update_control_box");
    public static final Identifier UPDATE_SPEAKER_C2S = StationAnnouncer.id("update_speaker");
    public static final Identifier UNLINK_ALL_C2S = StationAnnouncer.id("unlink_all");
    public static final Identifier UPDATE_AMBIENCE_C2S = StationAnnouncer.id("update_ambience");

    /** Players farther than this from a block cannot edit it (anti-cheat sanity bound). */
    private static final double MAX_EDIT_DISTANCE_SQ = 64.0 * 64.0;

    private AnnouncerNetworking() {
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_ANNOUNCER_C2S, (server, player, handler, buf, responseSender) -> {
            // Read everything on the netty thread; the buffer is released after this handler returns.
            BlockPos pos = buf.readBlockPos();
            String text = buf.readString(AbstractPaBlockEntity.MAX_TEXT_LENGTH);
            int volume = buf.readVarInt();
            int delaySeconds = buf.readVarInt();
            int radius = buf.readVarInt();
            String tag = buf.readString(AbstractPaBlockEntity.MAX_TAG_LENGTH);
            boolean showChat = buf.readBoolean();
            boolean playChime = buf.readBoolean();
            String chimeSound = buf.readString(AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof AnnouncerBlockEntity announcer) {
                    announcer.applySettings(capText(text), volume, delaySeconds, radius, tag,
                            showChat, playChime, chimeSound);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_CONTROL_BOX_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String text = buf.readString(AbstractPaBlockEntity.MAX_TEXT_LENGTH);
            int delaySeconds = buf.readVarInt();
            String tag = buf.readString(AbstractPaBlockEntity.MAX_TAG_LENGTH);
            boolean showChat = buf.readBoolean();
            boolean playChime = buf.readBoolean();
            String chimeSound = buf.readString(AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
            boolean randomOrder = buf.readBoolean();
            int autoMinSeconds = buf.readVarInt();
            int autoMaxSeconds = buf.readVarInt();

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                    box.applySettings(capText(text), delaySeconds, tag, showChat, playChime, chimeSound,
                            randomOrder, autoMinSeconds, autoMaxSeconds);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_SPEAKER_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int volume = buf.readVarInt();
            int radius = buf.readVarInt();

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
                    speaker.applySettings(volume, radius);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_AMBIENCE_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String sound = buf.readString(16);
            int volume = buf.readVarInt();
            int radius = buf.readVarInt();

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof AmbienceBlockEntity ambience) {
                    ambience.applySettings(sound, volume, radius);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UNLINK_ALL_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                    box.unlinkAllSpeakers();
                }
            });
        });
    }

    private static boolean canEdit(ServerPlayerEntity player, BlockPos pos) {
        ServerWorld world = player.getServerWorld();
        return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= MAX_EDIT_DISTANCE_SQ
                && world.canPlayerModifyAt(player, pos);
    }

    private static String capText(String text) {
        int maxText = Math.min(AbstractPaBlockEntity.MAX_TEXT_LENGTH, ServerConfig.get().maxTextLength);
        return text.length() > maxText ? text.substring(0, maxText) : text;
    }

    public static void sendAnnouncement(ServerPlayerEntity player, String text, float volume, BlockPos pos,
                                        boolean showChat, boolean playChime, String chimeSound) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(text, AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        buf.writeFloat(volume);
        buf.writeBlockPos(pos);
        buf.writeBoolean(showChat);
        buf.writeBoolean(playChime);
        buf.writeString(chimeSound, AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
        ServerPlayNetworking.send(player, ANNOUNCE_S2C, buf);
    }
}
