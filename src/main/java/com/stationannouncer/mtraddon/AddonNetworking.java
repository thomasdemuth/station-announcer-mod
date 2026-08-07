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
 *   <li>{@code station_announcer:addon_dwell_overrides} (S2C) — the full
 *       per-route dwell override map (Feature 2), sent on join and re-broadcast
 *       after every change so the GUI opens with current data.</li>
 *   <li>{@code station_announcer:addon_update_dwell_overrides} (C2S) — the
 *       per-route dwell GUI's Save. Always carries the FULL set of overridden
 *       routes for one platform (an empty list clears the platform). Same
 *       permission model as the hold-rule packet; route count capped, dwell
 *       clamped to MTR's own platform dwell range (1 s – 600 s).</li>
 *   <li>{@code station_announcer:addon_lift_doors} (S2C) — the full lift door-side
 *       map (Feature 3), sent on join and re-broadcast after every change. One
 *       long + one byte per configured lift. Sent EMPTY when
 *       {@code multiDoorLifts.enabled} is off, which is what keeps the client's
 *       lift-render mixin on the stock path.</li>
 *   <li>{@code station_announcer:addon_update_lift_doors} (C2S) — the door-sides
 *       GUI's Save/Reset: lift id + a 4-bit side mask; mask 0 clears the entry
 *       (back to stock MTR behavior). Same permission model as the others.</li>
 * </ul>
 */
public final class AddonNetworking {
    public static final Identifier HOLD_RULES_S2C = StationAnnouncer.id("addon_hold_rules");
    public static final Identifier UPDATE_HOLD_RULE_C2S = StationAnnouncer.id("addon_update_hold_rule");
    public static final Identifier DWELL_OVERRIDES_S2C = StationAnnouncer.id("addon_dwell_overrides");
    public static final Identifier UPDATE_DWELL_OVERRIDES_C2S = StationAnnouncer.id("addon_update_dwell_overrides");
    public static final Identifier LIFT_DOORS_S2C = StationAnnouncer.id("addon_lift_doors");
    public static final Identifier UPDATE_LIFT_DOORS_C2S = StationAnnouncer.id("addon_update_lift_doors");

    /** Cap on watched platforms per rule (also the GUI's picker cap). */
    public static final int MAX_WATCHED = 16;
    public static final int MIN_HOLD_WINDOW_SECONDS = 5;
    public static final int MAX_HOLD_WINDOW_SECONDS = 120;

    /** Cap on overridden routes per platform in one save packet. */
    public static final int MAX_ROUTE_OVERRIDES = 32;
    /**
     * Dwell clamp, mirroring MTR's own platform dwell range: the PlatformScreen
     * sliders allow 0.5 s – 600 s (MAX_DWELL_TIME = 1200 half-seconds); we floor
     * at a full second per the addon spec.
     */
    public static final int MIN_DWELL_MILLIS = 1_000;
    public static final int MAX_DWELL_MILLIS = 600_000;

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

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_DWELL_OVERRIDES_C2S, (server, player, handler, buf, responseSender) -> {
            long platformId = buf.readLong();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ROUTE_OVERRIDES) {
                return; // malformed — drop without touching anything
            }
            long[] routeIds = new long[count];
            int[] millis = new int[count];
            for (int i = 0; i < count; i++) {
                routeIds[i] = buf.readLong();
                millis[i] = buf.readVarInt();
            }

            server.execute(() -> {
                if (!player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                // LinkedHashMap keeps the GUI's order and drops duplicate route ids
                // (last write wins). Clamp to MTR's own dwell range.
                Map<Long, Long> byRoute = new java.util.LinkedHashMap<>();
                for (int i = 0; i < routeIds.length; i++) {
                    long clamped = Math.max(MIN_DWELL_MILLIS, Math.min(MAX_DWELL_MILLIS, millis[i]));
                    byRoute.put(routeIds[i], clamped);
                }
                AddonStore.setDwellOverrides(platformId, byRoute);
                broadcastDwellOverrides(server);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_LIFT_DOORS_C2S, (server, player, handler, buf, responseSender) -> {
            long liftId = buf.readLong();
            int mask = buf.readByte() & 0x0F;

            server.execute(() -> {
                if (!AddonServerConfig.get().multiDoorLifts.enabled
                        || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                // mask 0 clears the entry (setLiftDoors treats all-off as clear).
                AddonStore.setLiftDoors(liftId, LiftDoorSides.fromMask(mask));
                broadcastLiftDoors(server);
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

    /** On join, through the connection event's sender. */
    public static void syncDwellOverridesTo(PacketSender sender) {
        sender.sendPacket(DWELL_OVERRIDES_S2C, buildDwellOverridesBuf());
    }

    /** After a change, to everyone (a few longs per configured platform). */
    public static void broadcastDwellOverrides(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, DWELL_OVERRIDES_S2C, buildDwellOverridesBuf());
        }
    }

    /** On join, through the connection event's sender. */
    public static void syncLiftDoorsTo(PacketSender sender) {
        sender.sendPacket(LIFT_DOORS_S2C, buildLiftDoorsBuf());
    }

    /** After a change, to everyone (one long + one byte per configured lift). */
    public static void broadcastLiftDoors(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, LIFT_DOORS_S2C, buildLiftDoorsBuf());
        }
    }

    private static PacketByteBuf buildLiftDoorsBuf() {
        PacketByteBuf buf = PacketByteBufs.create();
        if (!AddonServerConfig.get().multiDoorLifts.enabled) {
            // Feature off: an empty map keeps every client on MTR's stock render path.
            buf.writeVarInt(0);
            return buf;
        }
        Map<Long, LiftDoorSides> doors = AddonStore.liftDoorsView();
        buf.writeVarInt(doors.size());
        doors.forEach((liftId, sides) -> {
            buf.writeLong(liftId);
            buf.writeByte(sides.mask());
        });
        return buf;
    }

    private static PacketByteBuf buildDwellOverridesBuf() {
        Map<Long, java.util.LinkedHashMap<Long, Long>> overrides = AddonStore.dwellOverridesView();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(overrides.size());
        overrides.forEach((platformId, byRoute) -> {
            buf.writeLong(platformId);
            buf.writeVarInt(byRoute.size());
            byRoute.forEach((routeId, millis) -> {
                buf.writeLong(routeId);
                buf.writeVarInt(millis.intValue());
            });
        });
        return buf;
    }
}
