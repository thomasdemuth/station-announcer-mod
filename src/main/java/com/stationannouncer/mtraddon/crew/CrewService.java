package com.stationannouncer.mtraddon.crew;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.mtr.mod.item.ItemDriverKey;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Train crews for manual driving (MANUAL_DRIVING_PLAN.md phase 1), server side.
 *
 * <p><b>Roles.</b> Each client reports which vehicle it rides and what its held MTR key
 * allows ({@link CrewProtocol#ROLE_C2S}); a driver key (can drive) claims the DRIVER
 * slot, MTR's guard key (doors only) the GUARD slot, one of each per vehicle, first come.
 * The claim is checked against the item really in the main hand and must be refreshed
 * by the heartbeat. Whether the player is actually aboard is checked by
 * {@link CrewEngine} on the simulator thread (it owns the vehicle's riding list), which
 * publishes {@link CrewState#driverAboard} / {@link CrewState#guardAboard} — only an
 * aboard role counts for the interlocks, OPTO and the status.</p>
 *
 * <p><b>Threads.</b> Packets, the ticker and the command run on the SERVER thread; the
 * engine runs on SIMULATOR threads. They share {@link CrewState} objects through a
 * {@link ConcurrentHashMap}; every shared field is volatile and has exactly one writer
 * side. All timestamps are WALL-CLOCK millis, never the simulator clock — Instant Deploy
 * rewinds that clock (see CLAUDE.md, the 2026-08-19 wedged-train postmortem), and a crew
 * is about real people pressing keys in real time.</p>
 *
 * <p><b>Horns</b> are chosen per train type (car 0's vehicle id, reported by the driver's
 * client) from {@code <save>/station-announcer-addon/crew_horns.json}, set with
 * {@code /crew horn <name>}; unknown types use the NYC horn.</p>
 */
public final class CrewService {
    /** vehicle id → crew. A state lives while it has at least one claimed role. */
    static final ConcurrentHashMap<Long, CrewState> VEHICLES = new ConcurrentHashMap<>();
    /** player → the vehicle they claimed a role on. Server thread only. */
    private static final Map<UUID, Long> MEMBERS = new HashMap<>();
    /** Last status sent to each player, so only changes go out. Server thread only. */
    private static final Map<UUID, Status> LAST_STATUS = new HashMap<>();
    /** player → vehicle whose occupied slot they were already told about (no repeats). */
    private static final Map<UUID, Long> TOLD_TAKEN = new HashMap<>();
    /** The feed object last sent to each player (identity), so only new ones go out. Server thread only. */
    private static final Map<UUID, CabFeed> LAST_FEED = new HashMap<>();
    /** Players whose horn is sounding. Server thread only. */
    private static final Set<UUID> HORN_ON = new HashSet<>();
    /** One-off notices raised on simulator threads, sent by the server ticker. */
    static final ConcurrentLinkedQueue<Notice> NOTICES = new ConcurrentLinkedQueue<>();
    /** (player, code) → wall time last sent, so a held key does not spam the action bar. */
    private static final Map<String, Long> NOTICE_SENT = new HashMap<>();

    /** train type → horn index. Replaced wholesale on change; read on the server thread. */
    private static volatile Map<String, Byte> horns = Map.of();
    private static Path hornFile;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long NOTICE_REPEAT_MILLIS = 1_500;
    private static int tickCounter;

    record Notice(UUID player, byte code) {
    }

    /** What a crew member's client is told. Vigilance time left is sent alongside, not compared. */
    private record Status(long vehicleId, byte role, boolean hasDriver, boolean hasGuard, int flags,
                          byte vigilance, byte horn, byte incident, long incidentUntil) {
        static final Status NONE = new Status(0, CrewProtocol.ROLE_NONE, false, false, 0, CrewProtocol.VIGIL_OFF,
                CrewProtocol.DEFAULT_HORN, (byte) 0, 0);
    }

    private CrewService() {
    }

    public static void register() {
        registerSounds();
        registerReceivers();
        registerCommand();
        ServerLifecycleEvents.SERVER_STARTED.register(CrewService::loadHorns);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        ServerTickEvents.END_SERVER_TICK.register(CrewService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(handler.player));
    }

    static boolean enabled() {
        return AddonServerConfig.get().crew.enabled;
    }

    // ------------------------------------------------------------------ sounds

    private static void registerSounds() {
        for (String name : CrewProtocol.HORNS) {
            registerSound("horn_" + name);
        }
        registerSound(CrewProtocol.SOUND_BUZZER);
        registerSound(CrewProtocol.SOUND_VIGIL_WARN);
        registerSound(CrewProtocol.SOUND_VIGIL_TRIP);
    }

    private static void registerSound(String path) {
        Identifier id = StationAnnouncer.id(path);
        Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    // ----------------------------------------------------------------- packets

    private static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.ROLE_C2S, (server, player, handler, buf, sender) -> {
            long vehicleId = buf.readLong();
            byte role = buf.readByte();
            int vigilanceSeconds = buf.readVarInt();
            String trainType = buf.readString(CrewProtocol.MAX_TRAIN_TYPE);
            server.execute(() -> claim(player, vehicleId, role, vigilanceSeconds, trainType));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.BUZZ_C2S, (server, player, handler, buf, sender) ->
                server.execute(() -> buzz(player)));
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.HORN_C2S, (server, player, handler, buf, sender) -> {
            boolean on = buf.readBoolean();
            server.execute(() -> horn(player, on));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.SLOT_LIST_C2S, (server, player, handler, buf, sender) -> {
            long manualSidingId = buf.readLong();
            server.execute(() -> listSlots(player, manualSidingId));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.SLOT_CLAIM_C2S, (server, player, handler, buf, sender) -> {
            long manualSidingId = buf.readLong();
            long autoSidingId = buf.readLong();
            int index = buf.readVarInt();
            long slotYard = buf.readLong();
            server.execute(() -> claimSlot(player, manualSidingId, autoSidingId, index, slotYard));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.SLOT_RELEASE_C2S, (server, player, handler, buf, sender) -> {
            long manualSidingId = buf.readLong();
            server.execute(() -> releaseSlot(player, manualSidingId));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.HORN_SET_C2S, (server, player, handler, buf, sender) -> {
            byte horn = CrewProtocol.clampHorn(buf.readByte());
            server.execute(() -> setHorn(player, horn));
        });
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.STARTUP_C2S, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    CrewState state = stateOf(player);
                    if (state != null && player.getUuid().equals(state.driver) && !state.startedUp) {
                        state.startedUp = true;
                        notice(player, CrewProtocol.MSG_STARTED_UP);
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(CrewProtocol.VIGIL_ACK_C2S, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    CrewState state = stateOf(player);
                    if (state != null && player.getUuid().equals(state.driver)) {
                        state.lastVigilAck = System.currentTimeMillis();
                    }
                }));
    }

    /** What the item in the main hand really allows (the client's claim is checked against it). */
    private static byte heldRole(ServerPlayerEntity player) {
        Item item = player.getMainHandStack().getItem();
        if (item instanceof ItemDriverKey key) {
            if (key.canDrive) {
                return CrewProtocol.ROLE_DRIVER;
            }
            if (key.canOpenDoors) {
                return CrewProtocol.ROLE_GUARD;
            }
        }
        return CrewProtocol.ROLE_NONE;
    }

    private static void claim(ServerPlayerEntity player, long vehicleId, byte role, int vigilanceSeconds, String trainType) {
        UUID uuid = player.getUuid();
        if (!enabled() || vehicleId == 0 || role == CrewProtocol.ROLE_NONE || role != heldRole(player)) {
            leave(player);
            return;
        }
        Long current = MEMBERS.get(uuid);
        if (current != null && current != vehicleId) {
            leave(player);
        }
        CrewState state = VEHICLES.computeIfAbsent(vehicleId, CrewState::new);
        long now = System.currentTimeMillis();
        int vigilance = vigilanceSeconds <= 0 ? 0
                : Math.max(CrewProtocol.MIN_VIGIL_SECONDS, Math.min(CrewProtocol.MAX_VIGIL_SECONDS, vigilanceSeconds));
        if (role == CrewProtocol.ROLE_DRIVER) {
            if (state.driver != null && !state.driver.equals(uuid) && now - state.driverSeen < CrewProtocol.ROLE_TIMEOUT_MILLIS) {
                tellTaken(player, vehicleId, CrewProtocol.MSG_DRIVER_TAKEN);
                dropIfEmpty(state);
                return;
            }
            if (uuid.equals(state.guard)) {
                state.guard = null; // swapped keys: guard → driver
            }
            if (!uuid.equals(state.driver)) {
                state.startedUp = false; // a new driver starts the train up again
            }
            state.driver = uuid;
            state.driverSeen = now;
            state.driverVigilance = vigilance;
            state.trainType = trainType;
        } else {
            if (state.guard != null && !state.guard.equals(uuid) && now - state.guardSeen < CrewProtocol.ROLE_TIMEOUT_MILLIS) {
                tellTaken(player, vehicleId, CrewProtocol.MSG_GUARD_TAKEN);
                dropIfEmpty(state);
                return;
            }
            if (uuid.equals(state.driver)) {
                stopHorn(player);
                state.driver = null;
            }
            state.guard = uuid;
            state.guardSeen = now;
            if (state.trainType.isEmpty()) {
                state.trainType = trainType;
            }
        }
        MEMBERS.put(uuid, vehicleId);
        TOLD_TAKEN.remove(uuid);
    }

    private static void tellTaken(ServerPlayerEntity player, long vehicleId, byte code) {
        Long told = TOLD_TAKEN.get(player.getUuid());
        if (told == null || told != vehicleId) {
            TOLD_TAKEN.put(player.getUuid(), vehicleId);
            sendMessage(player, code);
        }
    }

    /** Drops every role this player holds (stopped riding, put the key away, disconnected). */
    private static void leave(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        stopHorn(player);
        TOLD_TAKEN.remove(uuid);
        Long vehicleId = MEMBERS.remove(uuid);
        if (vehicleId == null) {
            return;
        }
        CrewState state = VEHICLES.get(vehicleId);
        if (state != null) {
            if (uuid.equals(state.driver)) {
                state.driver = null;
            }
            if (uuid.equals(state.guard)) {
                state.guard = null;
            }
            dropIfEmpty(state);
        }
    }

    private static void dropIfEmpty(CrewState state) {
        if (state.driver == null && state.guard == null) {
            VEHICLES.remove(state.vehicleId, state);
        }
    }

    private static CrewState stateOf(ServerPlayerEntity player) {
        Long vehicleId = MEMBERS.get(player.getUuid());
        return vehicleId == null ? null : VEHICLES.get(vehicleId);
    }

    private static void buzz(ServerPlayerEntity player) {
        CrewState state = stateOf(player);
        if (state == null) {
            return;
        }
        UUID uuid = player.getUuid();
        long now = System.currentTimeMillis();
        if (now - state.lastBuzzFrom(uuid) < 120) {
            return; // key bounce
        }
        UUID partner;
        byte fromRole;
        if (uuid.equals(state.driver)) {
            state.lastDriverBuzz = now;
            partner = state.guardAboard ? state.guard : null;
            fromRole = CrewProtocol.ROLE_DRIVER;
        } else if (uuid.equals(state.guard)) {
            state.lastGuardBuzz = now;
            partner = state.driverAboard ? state.driver : null;
            fromRole = CrewProtocol.ROLE_GUARD;
        } else {
            return;
        }
        ServerPlayerEntity target = partner == null ? null : player.server.getPlayerManager().getPlayer(partner);
        if (target == null) {
            notice(player, CrewProtocol.MSG_NO_PARTNER);
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(fromRole);
        ServerPlayNetworking.send(target, CrewProtocol.BUZZ_S2C, buf);
    }

    private static void horn(ServerPlayerEntity player, boolean on) {
        CrewState state = stateOf(player);
        boolean driver = state != null && player.getUuid().equals(state.driver) && state.driverAboard;
        if (on && !driver) {
            return;
        }
        if (on == HORN_ON.contains(player.getUuid())) {
            return;
        }
        if (on) {
            HORN_ON.add(player.getUuid());
        } else {
            HORN_ON.remove(player.getUuid());
        }
        broadcastHorn(player, hornFor(state), on);
    }

    private static void stopHorn(ServerPlayerEntity player) {
        if (HORN_ON.remove(player.getUuid())) {
            broadcastHorn(player, hornFor(stateOf(player)), false);
        }
    }

    private static void broadcastHorn(ServerPlayerEntity source, byte horn, boolean on) {
        double range = Math.max(16, AddonServerConfig.get().crew.hornRange);
        double rangeSq = range * range;
        for (ServerPlayerEntity listener : source.getServerWorld().getPlayers()) {
            // Always tell the listeners who heard it start, even if they moved away since.
            if (!on || listener.squaredDistanceTo(source) <= rangeSq) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeVarInt(source.getId());
                buf.writeByte(horn);
                buf.writeBoolean(on);
                ServerPlayNetworking.send(listener, CrewProtocol.HORN_S2C, buf);
            }
        }
    }

    private static byte hornFor(CrewState state) {
        return state == null ? CrewProtocol.DEFAULT_HORN
                : horns.getOrDefault(state.trainType, CrewProtocol.DEFAULT_HORN);
    }

    private static void sendMessage(ServerPlayerEntity player, byte code) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(code);
        ServerPlayNetworking.send(player, CrewProtocol.MESSAGE_S2C, buf);
    }

    /** Rate-limited notice from the server thread. */
    private static void notice(ServerPlayerEntity player, byte code) {
        String key = player.getUuid() + ":" + code;
        long now = System.currentTimeMillis();
        Long last = NOTICE_SENT.get(key);
        if (last == null || now - last >= NOTICE_REPEAT_MILLIS) {
            NOTICE_SENT.put(key, now);
            sendMessage(player, code);
        }
    }

    // ------------------------------------------------------------------ slots

    /**
     * Runs {@code task} on the simulator that owns {@code sidingId} (the slot engine's thread),
     * only for a player who is the driver of a vehicle of that siding.
     */
    private static void onSidingSimulator(ServerPlayerEntity player, long sidingId,
                                          java.util.function.BiConsumer<org.mtr.core.simulation.Simulator, Long> task) {
        CrewState state = stateOf(player);
        var simulators = com.stationannouncer.mtraddon.disruption.MtrSimulators.get();
        if (state == null || !player.getUuid().equals(state.driver) || simulators == null) {
            return;
        }
        long vehicleId = state.vehicleId;
        for (org.mtr.core.simulation.Simulator simulator : simulators) {
            simulator.run(() -> {
                org.mtr.core.data.Siding siding = simulator.sidingIdMap.get(sidingId);
                if (siding == null) {
                    return;
                }
                for (org.mtr.core.data.Vehicle vehicle : ((com.stationannouncer.mixin.SidingVehiclesAccessor) (Object) siding)
                        .stationAnnouncer$getVehicles()) {
                    if (vehicle.getId() == vehicleId) {
                        task.accept(simulator, vehicleId);
                        return;
                    }
                }
            });
        }
    }

    private static void listSlots(ServerPlayerEntity player, long manualSidingId) {
        UUID uuid = player.getUuid();
        java.util.Collection<com.stationannouncer.mtraddon.DepotGroup> groups =
                com.stationannouncer.mtraddon.AddonStore.depotGroupsView().values();
        MinecraftServer server = player.server;
        onSidingSimulator(player, manualSidingId, (simulator, vehicleId) -> {
            java.util.List<SlotEngine.Option> options = SlotEngine.listOptions(simulator, manualSidingId, groups, uuid);
            long now = simulator.getCurrentMillis();
            server.execute(() -> {
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                if (target == null) {
                    return;
                }
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeLong(manualSidingId);
                buf.writeLong(now);
                buf.writeVarInt(options.size());
                for (SlotEngine.Option option : options) {
                    buf.writeLong(option.autoSidingId());
                    buf.writeVarInt(option.index());
                    buf.writeLong(option.slotYard());
                    buf.writeLong(option.manualYard());
                    buf.writeString(cap(option.firstStop()), 64);
                    buf.writeLong(option.firstDeparture());
                    buf.writeString(cap(option.routeName()), 64);
                    buf.writeString(cap(option.routeNumber()), 64);
                    buf.writeInt(option.routeColor());
                    buf.writeString(cap(option.destination()), 64);
                    buf.writeBoolean(option.claimedByYou());
                }
                ServerPlayNetworking.send(target, CrewProtocol.SLOT_LIST_S2C, buf);
            });
        });
    }

    private static void claimSlot(ServerPlayerEntity player, long manualSidingId, long autoSidingId, int index, long slotYard) {
        UUID uuid = player.getUuid();
        java.util.Collection<com.stationannouncer.mtraddon.DepotGroup> groups =
                com.stationannouncer.mtraddon.AddonStore.depotGroupsView().values();
        onSidingSimulator(player, manualSidingId, (simulator, vehicleId) -> {
            Byte error = SlotEngine.claim(simulator, manualSidingId, autoSidingId, index, slotYard, uuid, groups);
            NOTICES.add(new Notice(uuid, error == null ? CrewProtocol.MSG_SLOT_CLAIMED : error));
        });
    }

    private static void releaseSlot(ServerPlayerEntity player, long manualSidingId) {
        UUID uuid = player.getUuid();
        onSidingSimulator(player, manualSidingId, (simulator, vehicleId) -> {
            if (SlotEngine.release(manualSidingId, uuid)) {
                NOTICES.add(new Notice(uuid, CrewProtocol.MSG_SLOT_RELEASED));
            }
        });
    }

    private static String cap(String s) {
        return s == null ? "" : s.length() > 64 ? s.substring(0, 64) : s;
    }

    // ------------------------------------------------------------------ ticker

    private static void tick(MinecraftServer server) {
        if (!NOTICES.isEmpty()) {
            Notice notice;
            while ((notice = NOTICES.poll()) != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(notice.player());
                if (player != null) {
                    notice(player, notice.code());
                }
            }
        }
        if (++tickCounter % 5 != 0) {
            return;
        }
        if (NOTICE_SENT.size() > 256) {
            NOTICE_SENT.clear();
        }
        if (MEMBERS.isEmpty() && LAST_STATUS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Expire claims whose heartbeat stopped (client crashed, key swapped without a packet).
        for (UUID uuid : MEMBERS.keySet().toArray(new UUID[0])) {
            CrewState state = VEHICLES.get(MEMBERS.get(uuid));
            boolean stale = state == null
                    || (uuid.equals(state.driver) && now - state.driverSeen > CrewProtocol.ROLE_TIMEOUT_MILLIS)
                    || (uuid.equals(state.guard) && now - state.guardSeen > CrewProtocol.ROLE_TIMEOUT_MILLIS)
                    || (!uuid.equals(state.driver) && !uuid.equals(state.guard));
            if (stale) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    leave(player);
                } else {
                    MEMBERS.remove(uuid);
                    if (state != null) {
                        if (uuid.equals(state.driver)) {
                            state.driver = null;
                        }
                        if (uuid.equals(state.guard)) {
                            state.guard = null;
                        }
                        dropIfEmpty(state);
                    }
                }
            }
        }
        // Cab feeds: each new one (built twice a second on the simulator thread) to the crew.
        for (Map.Entry<UUID, Long> entry : MEMBERS.entrySet()) {
            CrewState state = VEHICLES.get(entry.getValue());
            CabFeed feed = state == null ? null : state.feed;
            if (feed != null && feed != LAST_FEED.get(entry.getKey())) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null) {
                    LAST_FEED.put(entry.getKey(), feed);
                    PacketByteBuf buf = PacketByteBufs.create();
                    feed.write(buf);
                    ServerPlayNetworking.send(player, CrewProtocol.FEED_S2C, buf);
                }
            }
        }
        LAST_FEED.keySet().retainAll(MEMBERS.keySet());
        // Push changed statuses (and one final NONE to anyone who just left a crew).
        Set<UUID> seen = new HashSet<>();
        for (Map.Entry<UUID, Long> entry : MEMBERS.entrySet()) {
            CrewState state = VEHICLES.get(entry.getValue());
            if (state == null) {
                continue;
            }
            seen.add(entry.getKey());
            Status status = statusFor(entry.getKey(), state);
            if (!status.equals(LAST_STATUS.get(entry.getKey()))) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null) {
                    LAST_STATUS.put(entry.getKey(), status);
                    sendStatus(player, status, status.vigilance() == CrewProtocol.VIGIL_WARNING
                            ? Math.max(0, state.vigilanceDeadline - now) : 0);
                }
            }
        }
        LAST_STATUS.keySet().removeIf(uuid -> {
            if (seen.contains(uuid)) {
                return false;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                sendStatus(player, Status.NONE, 0);
            }
            return true;
        });
    }

    private static Status statusFor(UUID uuid, CrewState state) {
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        boolean isDriver = uuid.equals(state.driver);
        byte role = isDriver ? CrewProtocol.ROLE_DRIVER : CrewProtocol.ROLE_GUARD;
        int flags = 0;
        if (state.guardReady()) {
            flags |= CrewProtocol.FLAG_GUARD_READY;
        }
        if (state.driverReady()) {
            flags |= CrewProtocol.FLAG_DRIVER_READY;
        }
        if (config.driverWaitsForGuard) {
            flags |= CrewProtocol.FLAG_DRIVER_WAITS;
        }
        if (config.guardWaitsForDriver) {
            flags |= CrewProtocol.FLAG_GUARD_WAITS;
        }
        if (config.guardOwnsDoors) {
            flags |= CrewProtocol.FLAG_GUARD_OWNS_DOORS;
        }
        if (state.doorsThisStop && !state.moving) {
            flags |= CrewProtocol.FLAG_DOORS_THIS_STOP;
        }
        if (state.lastDoorMultiplier > 0) {
            flags |= CrewProtocol.FLAG_DOORS_OPEN;
        }
        if (state.startedUp || !config.startupRequired) {
            flags |= CrewProtocol.FLAG_STARTED_UP;
        }
        byte vigilance = isDriver ? state.vigilanceState : CrewProtocol.VIGIL_OFF;
        return new Status(state.vehicleId, role, state.driver != null && state.driverAboard,
                state.guard != null && state.guardAboard, flags, vigilance, hornFor(state), state.incident, state.incidentUntil);
    }

    private static void sendStatus(ServerPlayerEntity player, Status status, long vigilanceMillisLeft) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(status.vehicleId());
        buf.writeByte(status.role());
        buf.writeBoolean(status.hasDriver());
        buf.writeBoolean(status.hasGuard());
        buf.writeByte(status.flags());
        buf.writeByte(status.vigilance());
        buf.writeVarInt((int) Math.min(Integer.MAX_VALUE, vigilanceMillisLeft));
        buf.writeByte(status.horn());
        buf.writeByte(status.incident());
        buf.writeVarInt(status.incidentUntil() <= 0 ? 0 : (int) Math.max(1, Math.min(Integer.MAX_VALUE, status.incidentUntil() - System.currentTimeMillis())));
        ServerPlayNetworking.send(player, CrewProtocol.STATUS_S2C, buf);
    }

    private static void clear() {
        VEHICLES.clear();
        MEMBERS.clear();
        LAST_STATUS.clear();
        TOLD_TAKEN.clear();
        HORN_ON.clear();
        NOTICES.clear();
        NOTICE_SENT.clear();
        LAST_FEED.clear();
        SlotEngine.clearAll();
        horns = Map.of();
        hornFile = null;
    }

    // ------------------------------------------------------------------- horns

    private static void loadHorns(MinecraftServer server) {
        hornFile = server.getSavePath(WorldSavePath.ROOT).resolve("station-announcer-addon").resolve("crew_horns.json").normalize();
        Map<String, Byte> loaded = new HashMap<>();
        if (Files.isRegularFile(hornFile)) {
            try {
                Type type = new TypeToken<Map<String, String>>() { }.getType();
                Map<String, String> raw = GSON.fromJson(Files.readString(hornFile, StandardCharsets.UTF_8), type);
                if (raw != null) {
                    raw.forEach((trainType, name) -> {
                        int index = hornIndex(name);
                        if (trainType != null && index >= 0) {
                            loaded.put(trainType, (byte) index);
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                StationAnnouncer.LOGGER.warn("Could not read {}: {}", hornFile, e.toString());
            }
        }
        horns = Map.copyOf(loaded);
    }

    /** The cab menu's horn choice: same rule as {@code /crew horn} (op, while crewing the train). */
    private static void setHorn(ServerPlayerEntity player, byte horn) {
        CrewState state = stateOf(player);
        if (state == null || state.trainType.isEmpty()
                || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
            notice(player, CrewProtocol.MSG_HORN_NOT_ALLOWED);
            return;
        }
        Map<String, Byte> next = new HashMap<>(horns);
        next.put(state.trainType, horn);
        horns = Map.copyOf(next);
        saveHorns();
        notice(player, CrewProtocol.MSG_HORN_SET);
    }

    private static int hornIndex(String name) {
        for (int i = 0; i < CrewProtocol.HORNS.length; i++) {
            if (CrewProtocol.HORNS[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void saveHorns() {
        if (hornFile == null) {
            return;
        }
        Map<String, String> raw = new java.util.TreeMap<>();
        horns.forEach((trainType, index) -> raw.put(trainType, CrewProtocol.HORNS[CrewProtocol.clampHorn(index)]));
        try {
            Files.createDirectories(hornFile.getParent());
            Files.writeString(hornFile, GSON.toJson(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not write {}: {}", hornFile, e.toString());
        }
    }

    // ----------------------------------------------------------------- command

    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("crew")
                        .then(CommandManager.literal("status").executes(CrewService::statusCommand))
                        .then(CommandManager.literal("devdepots")
                                .requires(source -> net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                                        && source.hasPermissionLevel(2))
                                .then(CommandManager.argument("depot", StringArgumentType.greedyString())
                                        .executes(CrewService::devDepotsCommand)))
                        .then(CommandManager.literal("devmanual")
                                .requires(source -> net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                                        && source.hasPermissionLevel(2))
                                .then(CommandManager.argument("depot", StringArgumentType.greedyString())
                                        .executes(CrewService::devManualCommand)))
                        .then(CommandManager.literal("horn")
                                .requires(source -> source.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel))
                                .then(CommandManager.argument("horn", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (String name : CrewProtocol.HORNS) {
                                                builder.suggest(name);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(CrewService::hornCommand)))));
    }

    /**
     * Dev rig only (registered in the development environment): turns the last siding of the named
     * depot into a manual siding (160 km/h, 60 s manual→automatic) and drops its trains so a
     * manual one spawns — a manual siding in a depot WITH automatic sidings, to test slots.
     */
    private static int devManualCommand(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "depot").toLowerCase();
        var simulators = com.stationannouncer.mtraddon.disruption.MtrSimulators.get();
        ServerCommandSource source = context.getSource();
        if (simulators == null) {
            return 0;
        }
        for (org.mtr.core.simulation.Simulator simulator : simulators) {
            simulator.run(() -> {
                for (org.mtr.core.data.Depot depot : simulator.depots) {
                    if (!depot.getName().toLowerCase().contains(name)) {
                        continue;
                    }
                    org.mtr.core.data.Siding chosen = null;
                    for (org.mtr.core.data.Siding siding : depot.savedRails) {
                        if (chosen == null || siding.getName().compareTo(chosen.getName()) > 0) {
                            chosen = siding;
                        }
                    }
                    if (chosen == null) {
                        continue;
                    }
                    chosen.setIsManual(true);
                    chosen.setMaxManualSpeed(160 / 3600.0);
                    chosen.setManualToAutomaticTime(60_000);
                    // Every train of the siding goes (on-route ones would hold the single manual
                    // allowance and come home as automatic vehicles); a manual one spawns next tick.
                    ((com.stationannouncer.mixin.SidingVehiclesAccessor) (Object) chosen).stationAnnouncer$getVehicles().clear();
                    ((com.stationannouncer.mixin.SidingSlotAccessor) (Object) chosen).stationAnnouncer$getDepartures().clear();
                    org.mtr.core.data.Position mid = chosen.getMidPosition();
                    String text = "Siding " + chosen.getName() + " of " + depot.getName() + " is now manual (id " + chosen.getId()
                            + ") at " + mid.getX() + " " + mid.getY() + " " + mid.getZ();
                    source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(text), false));
                    StationAnnouncer.LOGGER.info("[slot] dev: {}", text);
                    return;
                }
            });
        }
        return 1;
    }

    /** Dev rig only: logs every depot whose name contains the text ("manual" = every depot with a manual siding). */
    private static int devDepotsCommand(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "depot").toLowerCase();
        var simulators = com.stationannouncer.mtraddon.disruption.MtrSimulators.get();
        var groups = com.stationannouncer.mtraddon.AddonStore.depotGroupsView().values();
        if (simulators == null) {
            return 0;
        }
        for (org.mtr.core.simulation.Simulator simulator : simulators) {
            simulator.run(() -> {
                for (org.mtr.core.data.Depot depot : simulator.depots) {
                    boolean anyManual = false;
                    for (org.mtr.core.data.Siding siding : depot.savedRails) {
                        anyManual |= siding.getIsManual();
                    }
                    if (name.equals("manual") ? !anyManual : !depot.getName().toLowerCase().contains(name)) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("[slot] dev depot '%s' id=%d routes=%s groups=", depot.getName(), depot.getId(), depot.getRouteIds()));
                    for (com.stationannouncer.mtraddon.DepotGroup group : groups) {
                        if (group.indexOf(depot.getId()) >= 0) {
                            sb.append(group.name()).append(' ');
                        }
                    }
                    long now = simulator.getCurrentMillis();
                    for (org.mtr.core.data.Siding siding : depot.savedRails) {
                        org.mtr.core.data.Position mid = siding.getMidPosition();
                        var vehicles = ((com.stationannouncer.mixin.SidingVehiclesAccessor) (Object) siding).stationAnnouncer$getVehicles();
                        int idle = 0;
                        for (org.mtr.core.data.Vehicle vehicle : vehicles) {
                            idle += vehicle.getIsOnRoute() ? 0 : 1;
                        }
                        // Seconds until each departure's next occurrence (to pick a slot whose automatic train is home).
                        var departures = ((com.stationannouncer.mixin.SidingSlotAccessor) (Object) siding).stationAnnouncer$getDepartures();
                        long repeat = ((com.stationannouncer.mixin.SidingSlotAccessor) (Object) siding).stationAnnouncer$getRepeatInterval(86_400_000L);
                        StringBuilder next = new StringBuilder();
                        for (int i = 0; i < departures.size() && i < 6 && repeat > 0; i++) {
                            long raw = departures.getLong(i);
                            long inSeconds = Math.floorMod(raw - now, repeat) / 1000;
                            next.append(next.length() == 0 ? "" : ",").append(i).append(':').append(inSeconds).append('s');
                        }
                        sb.append(String.format(" | siding '%s' id=%d manual=%b departures=%d [%s] vehicles=%d idle=%d stopTimes=%d at %d %d %d",
                                siding.getName(), siding.getId(), siding.getIsManual(), departures.size(), next,
                                vehicles.size(), idle, SlotEngine.stopTimes(siding).size(), mid.getX(), mid.getY(), mid.getZ()));
                    }
                    StationAnnouncer.LOGGER.info(sb.toString());
                }
            });
        }
        return 1;
    }

    private static int statusCommand(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        CrewState state = player == null ? null : stateOf(player);
        if (state == null) {
            context.getSource().sendFeedback(() -> Text.literal("Not crewing a train. Ride one holding a driver key or a guard key."), false);
            return 0;
        }
        Status status = statusFor(player.getUuid(), state);
        String text = String.format("Train %s · you: %s · driver %s · guard %s · guard ready %s · driver ready %s · vigilance %d · horn %s · type %s",
                Long.toHexString(state.vehicleId),
                status.role() == CrewProtocol.ROLE_DRIVER ? "driver" : "guard",
                status.hasDriver() ? "aboard" : "none", status.hasGuard() ? "aboard" : "none",
                state.guardReady(), state.driverReady(), state.vigilanceState,
                CrewProtocol.HORNS[CrewProtocol.clampHorn(status.horn())],
                state.trainType.isEmpty() ? "?" : state.trainType);
        context.getSource().sendFeedback(() -> Text.literal(text), false);
        return 1;
    }

    private static int hornCommand(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        CrewState state = player == null ? null : stateOf(player);
        String name = StringArgumentType.getString(context, "horn");
        int index = hornIndex(name);
        if (index < 0) {
            context.getSource().sendError(Text.literal("Unknown horn. Choose: " + String.join(", ", CrewProtocol.HORNS)));
            return 0;
        }
        if (state == null || state.trainType.isEmpty()) {
            context.getSource().sendError(Text.literal("Ride the train holding a driver key or a guard key, then run this again."));
            return 0;
        }
        Map<String, Byte> next = new HashMap<>(horns);
        next.put(state.trainType, (byte) index);
        horns = Map.copyOf(next);
        saveHorns();
        String trainType = state.trainType;
        context.getSource().sendFeedback(() -> Text.literal("Horn for " + trainType + " set to " + name + "."), true);
        return 1;
    }
}
