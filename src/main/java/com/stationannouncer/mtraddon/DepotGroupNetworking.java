package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.Map;

/**
 * Networking for depot groups, following {@code AddonNetworking}'s patterns exactly:
 * read on the netty thread, act on the server thread, check the op level, cap every
 * length. Its own class so the shared skeleton and Feature 6's channels stay untouched;
 * {@code AddonInit} registers the receiver and pushes the S2C sync on join.
 *
 * <ul>
 *   <li>{@code station_announcer:addon_depot_groups} (S2C) — the full group list plus,
 *       per member depot, the phase offset the simulators last computed for it (so the
 *       GUI can show "+2m 30s" without knowing the railway's game-day length). Sent on
 *       join and re-broadcast after every edit and after every offset refresh. Sent
 *       EMPTY while {@code depotGroups.enabled} is off.</li>
 *   <li>{@code station_announcer:addon_update_depot_group} (C2S) — create, rename,
 *       re-member or delete one group. The member list is always sent in full, IN
 *       OFFSET ORDER (index 0 = the reference depot). Structural validation happens
 *       here (op level, feature flag, caps, dedupe, zero ids dropped, no depot in two
 *       groups); depot ids are only resolved against the railway at USE time on the
 *       simulator thread, exactly like platform-group members — depots can be deleted
 *       at any moment after a save.</li>
 * </ul>
 */
public final class DepotGroupNetworking {
    public static final Identifier DEPOT_GROUPS_S2C = StationAnnouncer.id("addon_depot_groups");
    public static final Identifier UPDATE_DEPOT_GROUP_C2S = StationAnnouncer.id("addon_update_depot_group");
    /** C2S, empty payload: recompute every group's offsets and re-write the grouped depots' departures. */
    public static final Identifier REFRESH_DEPOT_GROUPS_C2S = StationAnnouncer.id("addon_refresh_depot_groups");

    /** Hard wire cap on members per group; the effective limit is {@code depotGroups.maxDepotsPerGroup}. */
    public static final int MAX_DEPOTS = 32;
    /** Hard wire cap on the number of synced groups; the effective limit is {@code depotGroups.maxGroups}. */
    public static final int MAX_GROUPS = 64;
    /** Hard wire cap on a group name; the effective limit is {@code depotGroups.maxNameLength}. */
    public static final int MAX_NAME_LENGTH = 64;
    /** Name given to a group saved with an empty name (a literal — see the save handler). */
    public static final String DEFAULT_GROUP_NAME = "Depot group";

    private DepotGroupNetworking() {
    }

    public static void registerServerReceivers() {
        // The refresh button: same recompute-and-rewrite pass a group edit runs,
        // for when something OUTSIDE the group changed — depot frequencies most
        // of all, since the offset is derived from them and a frequency edit
        // does not necessarily make MTR re-write the departure lists.
        ServerPlayNetworking.registerGlobalReceiver(REFRESH_DEPOT_GROUPS_C2S,
                (server, player, handler, buf, responseSender) -> server.execute(() -> {
                    AddonServerConfig config = AddonServerConfig.get();
                    if (!config.depotGroups.enabled || !player.hasPermissionLevel(config.editPermissionLevel)) {
                        return;
                    }
                    DepotGroupEngine.refreshOffsets(server, true);
                    player.sendMessage(Text.translatable("msg.station_announcer.depot_group.refreshed"), true);
                }));

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_DEPOT_GROUP_C2S, (server, player, handler, buf, responseSender) -> {
            boolean delete = buf.readBoolean();
            long id = buf.readLong();
            String name = buf.readString(MAX_NAME_LENGTH);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_DEPOTS) {
                return; // malformed — drop without touching anything
            }
            long[] depotIds = new long[count];
            for (int i = 0; i < count; i++) {
                depotIds[i] = buf.readLong();
            }

            server.execute(() -> {
                AddonServerConfig config = AddonServerConfig.get();
                if (!config.depotGroups.enabled || !player.hasPermissionLevel(config.editPermissionLevel)) {
                    return;
                }
                if (delete) {
                    if (id != 0) {
                        AddonStore.removeDepotGroup(id);
                        afterEdit(server);
                    }
                    return;
                }
                long[] members = dedupe(depotIds, config.depotGroups.maxDepotsPerGroup);
                // A depot may only be in one group: two groups both staggering the same
                // depot would fight over its offset.
                long claimed = firstClaimedElsewhere(members, id);
                if (claimed != 0) {
                    player.sendMessage(Text.translatable("msg.station_announcer.depot_group.already_grouped"), true);
                    return;
                }
                String label = name.trim();
                if (label.length() > config.depotGroups.maxNameLength) {
                    label = label.substring(0, config.depotGroups.maxNameLength);
                }
                if (label.isBlank()) {
                    // A literal, not a translation: a dedicated server does not load the
                    // mod's lang file, so Text.translatable(...).getString() would store
                    // the raw key as the group's name.
                    label = DEFAULT_GROUP_NAME;
                }
                if (members.length == 0) {
                    // An emptied group is a deleted group.
                    if (id != 0) {
                        AddonStore.removeDepotGroup(id);
                        afterEdit(server);
                    }
                    return;
                }
                DepotGroup stored = AddonStore.putDepotGroup(new DepotGroup(id, label, members), config.depotGroups.maxGroups);
                if (stored == null) {
                    player.sendMessage(Text.translatable("msg.station_announcer.depot_group.too_many",
                            config.depotGroups.maxGroups), true);
                    return;
                }
                afterEdit(server);
            });
        });
    }

    /**
     * Broadcast right away (so the edited list appears instantly), then ask the simulators
     * to recompute the offsets and re-write the affected depots' departures; that second
     * step rebroadcasts with the fresh numbers once they answer.
     */
    private static void afterEdit(MinecraftServer server) {
        broadcastDepotGroups(server);
        // Recompute the offsets AND re-write the affected depots' departure timetables, so
        // an edit is audible on the railway immediately rather than at the next depot
        // regeneration (see DepotGroupEngine.refreshOffsets for why that is safe). The
        // rebroadcast with the fresh offsets happens when the simulators answer.
        DepotGroupEngine.refreshOffsets(server, true);
    }

    /** The first member already claimed by a DIFFERENT group, or 0. */
    private static long firstClaimedElsewhere(long[] members, long thisGroupId) {
        Map<Long, DepotGroup> groups = AddonStore.depotGroupsView();
        for (long depotId : members) {
            for (DepotGroup group : groups.values()) {
                if (group.id() != thisGroupId && group.indexOf(depotId) >= 0) {
                    return depotId;
                }
            }
        }
        return 0;
    }

    private static long[] dedupe(long[] depotIds, int cap) {
        long[] result = new long[Math.min(depotIds.length, cap)];
        int size = 0;
        outer:
        for (long id : depotIds) {
            if (id == 0 || size >= result.length) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                if (result[i] == id) {
                    continue outer;
                }
            }
            result[size++] = id;
        }
        return size == result.length ? result : java.util.Arrays.copyOf(result, size);
    }

    // ------------------------------------------------------------------ sync

    /** On join, through the connection event's sender. */
    public static void syncDepotGroupsTo(PacketSender sender) {
        sender.sendPacket(DEPOT_GROUPS_S2C, buildDepotGroupsBuf());
    }

    /** After a change (a few longs per group). */
    public static void broadcastDepotGroups(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, DEPOT_GROUPS_S2C, buildDepotGroupsBuf());
        }
    }

    private static PacketByteBuf buildDepotGroupsBuf() {
        PacketByteBuf buf = PacketByteBufs.create();
        if (!AddonServerConfig.get().depotGroups.enabled) {
            // Feature off: an empty list keeps the GUI from offering dead edits.
            buf.writeVarInt(0);
            return buf;
        }
        Map<Long, DepotGroup> groups = AddonStore.depotGroupsView();
        Map<Long, Long> offsets = DepotGroupEngine.offsetsView();
        buf.writeVarInt(Math.min(groups.size(), MAX_GROUPS));
        int written = 0;
        for (DepotGroup group : groups.values()) {
            if (written++ >= MAX_GROUPS) {
                break;
            }
            buf.writeLong(group.id());
            buf.writeString(group.name(), MAX_NAME_LENGTH);
            long[] members = group.depotIds();
            int count = Math.min(members.length, MAX_DEPOTS);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeLong(members[i]);
                Long offset = offsets.get(members[i]);
                // -1 = "not computed yet" (the depot has not generated since the edit,
                // or its interval is not derivable); the GUI says so instead of "+0s".
                buf.writeVarInt(offset == null ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, offset)));
            }
        }
        return buf;
    }
}
