package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import java.util.Map;

/**
 * Addon networking, following {@link com.stationannouncer.net.AnnouncerNetworking}'s
 * patterns: read everything on the netty thread, act on the server thread, validate
 * before applying, cap every length.
 *
 * <ul>
 *   <li>{@code station_announcer:addon_hold_rules} (S2C) — the full hold-rule map,
 *       sent on join and re-broadcast after every change. Tiny (a handful of longs
 *       per configured platform), and it lets the GUI open with current data and
 *       future HUDs read rules without a round trip.</li>
 *   <li>{@code station_announcer:addon_update_hold_rule} (C2S) — the hold-rule GUI's
 *       Save/Clear. {@code seconds == 0} or an empty watched list clears the rule.
 *       Requires op level {@code editPermissionLevel} (default 2) — these are
 *       dashboard-style dispatch settings, not block edits, so there is no
 *       block-distance check.</li>
 * </ul>
 */
public final class AddonNetworking {
    public static final Identifier HOLD_RULES_S2C = StationAnnouncer.id("addon_hold_rules");
    public static final Identifier UPDATE_HOLD_RULE_C2S = StationAnnouncer.id("addon_update_hold_rule");

    /** Cap on watched platforms per rule (also the GUI's picker cap). */
    public static final int MAX_WATCHED = 16;
    public static final int MIN_HOLD_WINDOW_SECONDS = 5;
    public static final int MAX_HOLD_WINDOW_SECONDS = 120;

    private AddonNetworking() {
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_HOLD_RULE_C2S, (server, player, handler, buf, responseSender) -> {
            long platformId = buf.readLong();
            int seconds = buf.readVarInt();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_WATCHED) {
                return; // malformed — drop without touching anything
            }
            long[] watched = new long[count];
            for (int i = 0; i < count; i++) {
                watched[i] = buf.readLong();
            }

            server.execute(() -> {
                if (!player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                if (seconds <= 0 || watched.length == 0) {
                    AddonStore.clearHoldRule(platformId);
                } else {
                    int clamped = Math.max(MIN_HOLD_WINDOW_SECONDS, Math.min(MAX_HOLD_WINDOW_SECONDS, seconds));
                    AddonStore.setHoldRule(platformId, dedupe(watched, platformId), clamped);
                }
                broadcastHoldRules(server);
            });
        });
    }

    /** Drops duplicates and the ruled platform itself (watching yourself is meaningless). */
    private static long[] dedupe(long[] watched, long platformId) {
        long[] result = new long[watched.length];
        int size = 0;
        outer:
        for (long id : watched) {
            if (id == platformId) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                if (result[i] == id) {
                    continue outer;
                }
            }
            result[size++] = id;
        }
        return size == watched.length ? result : java.util.Arrays.copyOf(result, size);
    }

    // ------------------------------------------------------------------ sync

    /** On join, through the connection event's sender. */
    public static void syncHoldRulesTo(PacketSender sender) {
        sender.sendPacket(HOLD_RULES_S2C, buildHoldRulesBuf());
    }

    /** After a change, to everyone (the map is tiny). */
    public static void broadcastHoldRules(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, HOLD_RULES_S2C, buildHoldRulesBuf());
        }
    }

    private static PacketByteBuf buildHoldRulesBuf() {
        Map<Long, AddonSnapshots.HoldRule> rules = AddonStore.holdRulesView();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(rules.size());
        rules.forEach((platformId, rule) -> {
            buf.writeLong(platformId);
            buf.writeVarInt(rule.seconds());
            buf.writeVarInt(rule.watched().length);
            for (long watched : rule.watched()) {
                buf.writeLong(watched);
            }
        });
        return buf;
    }
}
