package com.stationannouncer.mtraddon.disruption;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonStore;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.Map;

/**
 * Networking for Feature 6 (temporary stop changes + service disruptions),
 * following {@code AddonNetworking}'s patterns exactly: read on the netty thread,
 * act on the server thread, check the op level, cap every length. Kept in its own
 * class so the shared skeleton stays untouched; {@code AddonInit} registers the
 * receivers and pushes the two S2C syncs on join.
 *
 * <ul>
 *   <li>{@code station_announcer:addon_stop_changes} (S2C) — the full temporary
 *       stop-change map, sent on join and re-broadcast after every change and
 *       every expiry. Sent EMPTY while {@code stopChanges.enabled} is off.</li>
 *   <li>{@code station_announcer:addon_update_stop_change} (C2S) — set or clear
 *       one change. Structural validation here; for an ADDED stop the semantic
 *       rule from the spec ("the platform must already lie on the route's
 *       existing track path") is checked on the SIMULATOR thread by
 *       {@link StopOverlayEngine#validateAddition} before anything is stored, and
 *       the player gets an action-bar reason when it is refused.</li>
 *   <li>{@code station_announcer:addon_disruptions} (S2C) — the full disruption
 *       list, sent on join and after every edit/expiry. Sent EMPTY while
 *       {@code disruptions.enabled} is off.</li>
 *   <li>{@code station_announcer:addon_update_disruption} (C2S) — create, replace
 *       or delete one disruption.</li>
 * </ul>
 */
public final class DisruptionNetworking {
    public static final Identifier STOP_CHANGES_S2C = StationAnnouncer.id("addon_stop_changes");
    public static final Identifier UPDATE_STOP_CHANGE_C2S = StationAnnouncer.id("addon_update_stop_change");
    public static final Identifier DISRUPTIONS_S2C = StationAnnouncer.id("addon_disruptions");
    public static final Identifier UPDATE_DISRUPTION_C2S = StationAnnouncer.id("addon_update_disruption");

    /** Sanity cap on a stop index in a change key (mirrors the platform-group cap). */
    public static final int MAX_STOP_INDEX = 4_096;
    /** Hard wire cap; the effective limit is {@code disruptions.maxMessageLength}. */
    public static final int MAX_MESSAGE_LENGTH = 512;
    /** Hard wire cap; the effective limit is {@code disruptions.maxRoutesPerDisruption}. */
    public static final int MAX_ROUTES = 64;
    /** Hard wire cap on the number of synced entries in either direction. */
    public static final int MAX_SYNC_ENTRIES = 4_096;

    private DisruptionNetworking() {
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_STOP_CHANGE_C2S, (server, player, handler, buf, responseSender) -> {
            long routeId = buf.readLong();
            int stopIndex = buf.readVarInt();
            int kind = buf.readByte();       // 0 = skip this stop, 1 = add a stop after it
            boolean clear = buf.readBoolean();
            long platformId = buf.readLong();
            int durationMinutes = buf.readVarInt();
            if (stopIndex < 0 || stopIndex > MAX_STOP_INDEX || kind < 0 || kind > 1 || durationMinutes < 0) {
                return; // malformed — drop without touching anything
            }

            server.execute(() -> {
                AddonServerConfig config = AddonServerConfig.get();
                if (!config.stopChanges.enabled || !player.hasPermissionLevel(config.editPermissionLevel)) {
                    return;
                }
                long expiresAt = durationMinutes <= 0 ? 0
                        : System.currentTimeMillis()
                        + Math.min(durationMinutes, config.stopChanges.maxDurationMinutes) * 60_000L;
                if (clear) {
                    if (kind == 0) {
                        AddonStore.setDisabledStop(routeId, stopIndex, false, 0);
                    } else {
                        AddonStore.setAddedStop(routeId, stopIndex, 0, 0);
                    }
                    broadcastStopChanges(server);
                    return;
                }
                if (countForRoute(routeId) >= config.stopChanges.maxPerRoute) {
                    player.sendMessage(Text.translatable("msg.station_announcer.stop_change.too_many",
                            config.stopChanges.maxPerRoute), true);
                    return;
                }
                if (kind == 0) {
                    AddonStore.setDisabledStop(routeId, stopIndex, true, expiresAt);
                    broadcastStopChanges(server);
                    return;
                }
                if (platformId == 0) {
                    return;
                }
                // ADD: the platform must already be on the route's track. Route and
                // platform data live on the simulator threads, so validate there and
                // come back before storing anything.
                validateAndAdd(server, player, routeId, stopIndex, platformId, expiresAt);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_DISRUPTION_C2S, (server, player, handler, buf, responseSender) -> {
            boolean delete = buf.readBoolean();
            long id = buf.readLong();
            int severityOrdinal = buf.readVarInt();
            boolean active = buf.readBoolean();
            long startMillis = buf.readLong();
            long endMillis = buf.readLong();
            String message = buf.readString(MAX_MESSAGE_LENGTH);
            int routeCount = buf.readVarInt();
            if (routeCount < 0 || routeCount > MAX_ROUTES) {
                return; // malformed — drop without touching anything
            }
            long[] routeIds = new long[routeCount];
            for (int i = 0; i < routeCount; i++) {
                routeIds[i] = buf.readLong();
            }

            server.execute(() -> {
                AddonServerConfig config = AddonServerConfig.get();
                if (!config.disruptions.enabled || !player.hasPermissionLevel(config.editPermissionLevel)) {
                    return;
                }
                if (delete) {
                    if (id != 0) {
                        AddonStore.removeDisruption(id);
                        broadcastDisruptions(server);
                    }
                    return;
                }
                String text = message.trim();
                if (text.length() > config.disruptions.maxMessageLength) {
                    text = text.substring(0, config.disruptions.maxMessageLength);
                }
                if (text.isBlank()) {
                    return;
                }
                long[] routes = dedupe(routeIds, config.disruptions.maxRoutesPerDisruption);
                Disruption stored = AddonStore.putDisruption(new Disruption(id, routes, text,
                        Disruption.Severity.fromOrdinal(severityOrdinal),
                        Math.max(0, startMillis), Math.max(0, endMillis), active),
                        config.disruptions.maxActive);
                if (stored == null) {
                    player.sendMessage(Text.translatable("msg.station_announcer.disruption.too_many",
                            config.disruptions.maxActive), true);
                    return;
                }
                broadcastDisruptions(server);
            });
        });
    }

    /**
     * Hops onto each simulator thread to check the addition, then back onto the
     * server thread to store it (or to tell the player why not). Nothing is stored
     * until a simulator says yes.
     */
    private static void validateAndAdd(MinecraftServer server, ServerPlayerEntity player,
                                       long routeId, int stopIndex, long platformId, long expiresAt) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.stop_change.no_railway"), true);
            return;
        }
        // One reply per simulator; the first OK wins, and the last failure is
        // reported when nobody accepted.
        int[] pending = {simulators.size()};
        StopOverlayEngine.Result[] worst = {StopOverlayEngine.Result.UNKNOWN_ROUTE};
        boolean[] done = {false};
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                StopOverlayEngine.Result result;
                try {
                    result = StopOverlayEngine.validateAddition(simulator, routeId, stopIndex, platformId);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Temporary stop addition could not be validated", t);
                    result = StopOverlayEngine.Result.UNKNOWN_ROUTE;
                }
                StopOverlayEngine.Result finalResult = result;
                server.execute(() -> {
                    pending[0]--;
                    if (done[0]) {
                        return;
                    }
                    if (finalResult == StopOverlayEngine.Result.OK) {
                        done[0] = true;
                        AddonStore.setAddedStop(routeId, stopIndex, platformId, expiresAt);
                        broadcastStopChanges(server);
                        player.sendMessage(Text.translatable("msg.station_announcer.stop_change.added"), true);
                        return;
                    }
                    if (finalResult.ordinal() > worst[0].ordinal()) {
                        worst[0] = finalResult;
                    }
                    if (pending[0] <= 0) {
                        done[0] = true;
                        player.sendMessage(Text.translatable(reasonKey(worst[0])), true);
                    }
                });
            });
        }
    }

    private static String reasonKey(StopOverlayEngine.Result result) {
        return switch (result) {
            case UNKNOWN_PLATFORM -> "msg.station_announcer.stop_change.unknown_platform";
            case WRONG_MODE -> "msg.station_announcer.stop_change.wrong_mode";
            case DUPLICATE -> "msg.station_announcer.stop_change.duplicate";
            case NOT_ON_PATH -> "msg.station_announcer.stop_change.not_on_path";
            case NO_PATH -> "msg.station_announcer.stop_change.no_path";
            default -> "msg.station_announcer.stop_change.unknown_route";
        };
    }

    private static int countForRoute(long routeId) {
        String prefix = routeId + ":";
        int count = 0;
        for (String key : AddonStore.disabledStopsView().keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        for (String key : AddonStore.addedStopsView().keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static long[] dedupe(long[] routeIds, int cap) {
        long[] result = new long[Math.min(routeIds.length, cap)];
        int size = 0;
        outer:
        for (long id : routeIds) {
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
    public static void syncStopChangesTo(PacketSender sender) {
        sender.sendPacket(STOP_CHANGES_S2C, buildStopChangesBuf());
    }

    public static void broadcastStopChanges(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, STOP_CHANGES_S2C, buildStopChangesBuf());
        }
    }

    private static PacketByteBuf buildStopChangesBuf() {
        PacketByteBuf buf = PacketByteBufs.create();
        if (!AddonServerConfig.get().stopChanges.enabled) {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            return buf;
        }
        Map<String, Long> disabled = AddonStore.disabledStopsView();
        Map<String, long[]> added = AddonStore.addedStopsView();
        buf.writeVarInt(Math.min(disabled.size(), MAX_SYNC_ENTRIES));
        int written = 0;
        for (Map.Entry<String, Long> entry : disabled.entrySet()) {
            if (written++ >= MAX_SYNC_ENTRIES) {
                break;
            }
            long[] parsed = StopOverlayEngine.parseStopKey(entry.getKey());
            buf.writeLong(parsed == null ? 0 : parsed[0]);
            buf.writeVarInt(parsed == null ? 0 : (int) parsed[1]);
            buf.writeLong(entry.getValue() == null ? 0 : entry.getValue());
        }
        buf.writeVarInt(Math.min(added.size(), MAX_SYNC_ENTRIES));
        written = 0;
        for (Map.Entry<String, long[]> entry : added.entrySet()) {
            if (written++ >= MAX_SYNC_ENTRIES) {
                break;
            }
            long[] parsed = StopOverlayEngine.parseStopKey(entry.getKey());
            buf.writeLong(parsed == null ? 0 : parsed[0]);
            buf.writeVarInt(parsed == null ? 0 : (int) parsed[1]);
            buf.writeLong(entry.getValue()[0]);
            buf.writeLong(entry.getValue().length > 1 ? entry.getValue()[1] : 0);
        }
        return buf;
    }

    /** On join, through the connection event's sender. */
    public static void syncDisruptionsTo(PacketSender sender) {
        sender.sendPacket(DISRUPTIONS_S2C, buildDisruptionsBuf());
    }

    public static void broadcastDisruptions(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, DISRUPTIONS_S2C, buildDisruptionsBuf());
        }
    }

    private static PacketByteBuf buildDisruptionsBuf() {
        PacketByteBuf buf = PacketByteBufs.create();
        if (!AddonServerConfig.get().disruptions.enabled) {
            buf.writeVarInt(0);
            return buf;
        }
        Map<Long, Disruption> disruptions = AddonStore.disruptionsView();
        buf.writeVarInt(Math.min(disruptions.size(), MAX_SYNC_ENTRIES));
        int written = 0;
        for (Disruption disruption : disruptions.values()) {
            if (written++ >= MAX_SYNC_ENTRIES) {
                break;
            }
            buf.writeLong(disruption.id());
            buf.writeVarInt(disruption.severity().ordinal());
            buf.writeBoolean(disruption.active());
            buf.writeLong(disruption.startMillis());
            buf.writeLong(disruption.endMillis());
            buf.writeString(disruption.message(), MAX_MESSAGE_LENGTH);
            long[] routeIds = disruption.routeIds();
            int count = Math.min(routeIds.length, MAX_ROUTES);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeLong(routeIds[i]);
            }
        }
        return buf;
    }
}
