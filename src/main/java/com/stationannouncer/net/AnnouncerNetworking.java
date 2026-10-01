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
 *   <li>{@code station_announcer:fire_now} (C2S) — an editor's "Fire": broadcast one message
 *       (possibly unsaved) through this source right away, or the next pool message.</li>
 *   <li>{@code station_announcer:network_query} (C2S) / {@code network_info} (S2C) — the control
 *       box screen's Network tab asks for every member's link health and settings.</li>
 *   <li>{@code station_announcer:network_edit} (C2S) — Network tab Save: unlink members and set
 *       speakers' volume/radius.</li>
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
    public static final Identifier FIRE_NOW_C2S = StationAnnouncer.id("fire_now");
    public static final Identifier NETWORK_QUERY_C2S = StationAnnouncer.id("network_query");
    public static final Identifier NETWORK_INFO_S2C = StationAnnouncer.id("network_info");
    public static final Identifier NETWORK_EDIT_C2S = StationAnnouncer.id("network_edit");

    /** Network rows on the wire: every speaker and display a box may hold. */
    public static final int MAX_NETWORK_ROWS = ControlBoxBlockEntity.MAX_SPEAKERS * 2;

    /** "Fire" may not be spammed: one broadcast per player per second. */
    private static final long FIRE_COOLDOWN_MS = 1000;
    private static final java.util.Map<java.util.UUID, Long> LAST_FIRE = new java.util.concurrent.ConcurrentHashMap<>();

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
            String text = buf.readString(ControlBoxBlockEntity.MAX_POOL_LENGTH);
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
                    box.applySettings(capPool(text), delaySeconds, tag, showChat, playChime, chimeSound,
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

        ServerPlayNetworking.registerGlobalReceiver(FIRE_NOW_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String message = buf.readString(AbstractPaBlockEntity.MAX_TEXT_LENGTH);

            server.execute(() -> {
                long now = System.currentTimeMillis();
                Long last = LAST_FIRE.get(player.getUuid());
                if (last != null && now - last < FIRE_COOLDOWN_MS) {
                    return;
                }
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof AbstractPaBlockEntity source) {
                    LAST_FIRE.put(player.getUuid(), now);
                    if (message.isBlank()) {
                        source.fireNow();
                    } else {
                        source.announceExternal(capText(message));
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(NETWORK_QUERY_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                    sendNetworkInfo(player, pos, box.describeNetwork());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(NETWORK_EDIT_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int unlinkCount = Math.min(buf.readVarInt(), MAX_NETWORK_ROWS);
            java.util.List<BlockPos> unlink = new java.util.ArrayList<>();
            for (int i = 0; i < unlinkCount; i++) {
                unlink.add(buf.readBlockPos());
            }
            int settingsCount = Math.min(buf.readVarInt(), MAX_NETWORK_ROWS);
            java.util.List<ControlBoxBlockEntity.SpeakerSettings> settings = new java.util.ArrayList<>();
            for (int i = 0; i < settingsCount; i++) {
                settings.add(new ControlBoxBlockEntity.SpeakerSettings(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()));
            }

            server.execute(() -> {
                if (canEdit(player, pos) && player.getServerWorld().getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                    box.applyNetworkEdit(unlink, settings);
                    sendNetworkInfo(player, pos, box.describeNetwork());
                }
            });
        });
    }

    private static void sendNetworkInfo(ServerPlayerEntity player, BlockPos boxPos,
                                        java.util.List<ControlBoxBlockEntity.Member> members) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(boxPos);
        int count = Math.min(members.size(), MAX_NETWORK_ROWS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ControlBoxBlockEntity.Member member = members.get(i);
            buf.writeBlockPos(member.pos());
            buf.writeBoolean(member.display());
            buf.writeByte(member.state());
            buf.writeVarInt(member.volume());
            buf.writeVarInt(member.radius());
            buf.writeString(member.name(), 128);
        }
        ServerPlayNetworking.send(player, NETWORK_INFO_S2C, buf);
    }

    /** Server stop: forget per-player cooldowns. */
    public static void clearCooldowns() {
        LAST_FIRE.clear();
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

    /**
     * A control box pool: the server's text cap applies to EACH message (it is
     * "how long may one announcement be"), the pool as a whole to
     * {@link ControlBoxBlockEntity#MAX_POOL_LENGTH}.
     */
    private static String capPool(String pool) {
        String[] entries = ControlBoxBlockEntity.splitAll(pool);
        StringBuilder out = new StringBuilder();
        for (String entry : entries) {
            boolean off = com.stationannouncer.pa.PaText.isDisabled(entry);
            String text = capText(com.stationannouncer.pa.PaText.stripDisabled(entry));
            if (!out.isEmpty()) {
                out.append(" ").append(ControlBoxBlockEntity.MESSAGE_SEPARATOR).append(" ");
            }
            out.append(off ? com.stationannouncer.pa.PaText.DISABLED_PREFIX : "").append(text);
        }
        return out.length() > ControlBoxBlockEntity.MAX_POOL_LENGTH
                ? out.substring(0, ControlBoxBlockEntity.MAX_POOL_LENGTH) : out.toString();
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
