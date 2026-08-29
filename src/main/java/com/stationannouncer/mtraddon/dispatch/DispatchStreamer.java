package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.DoorObstructionEngine;
import com.stationannouncer.mtraddon.HoldRuleEngine;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.javax.servlet.AsyncContext;
import org.mtr.libraries.javax.servlet.ServletOutputStream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The SSE hub behind GET {@code /dispatch/api/stream}. Owns the connected clients, the
 * single sampling scheduler and the per-dimension delta state.
 *
 * <p><b>Performance contract (ARCHITECTURE §7½, hard requirements):</b></p>
 * <ul>
 *   <li><b>Zero clients = zero work.</b> The daemon scheduler thread starts when the
 *       first client registers and stops when the last one leaves (or at shutdown);
 *       while idle there are no timer ticks and no {@code simulator.run} enqueues.</li>
 *   <li>Every {@code dispatch.updateMillis} (clamped 100–5000) the scheduler enqueues
 *       ONE sampling Runnable per dimension that has ≥1 subscriber. The Runnable runs on
 *       that dimension's SIMULATOR thread ({@link DispatchSampler}), snapshots into
 *       plain POJOs and hands off; JSON serialization, delta computation and socket
 *       writes all happen here on the streamer thread.</li>
 *   <li>Dead connections are pruned on write failure.</li>
 *   <li>Client cap: {@code dispatch.maxClients} (default 8) — excess connections are
 *       refused by the servlet with HTTP 503.</li>
 * </ul>
 *
 * <p><b>Message framing:</b> named SSE events {@code full} (complete vehicle + signal
 * state; sent to a client on subscribe and to everyone every 10th tick) and
 * {@code delta} (changed/new vehicles, removed vehicle ids, changed signals, cleared
 * signal rails). Every payload carries {@code schemaVersion}, {@code serverTime} and
 * the full {@code players} list for its dimension (additive since schemaVersion 1).
 * A delta event is emitted even when empty — at ≈3 Hz it doubles as the keep-alive.</p>
 */
public final class DispatchStreamer {
    public static final int SCHEMA_VERSION = 1;
    private static final int FULL_EVERY_N_TICKS = 10;

    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Client> CLIENTS = new CopyOnWriteArrayList<>();
    /** Touched only by the streamer thread while it runs; cleared under LOCK when it stops. */
    private static final Map<Integer, DimensionState> DIMENSION_STATES = new ConcurrentHashMap<>();
    private static Thread schedulerThread; // guarded by LOCK
    private static volatile boolean running;
    /**
     * Bumped every time the scheduler is stopped. {@link #runLoop} captures its own value
     * and exits as soon as it is superseded — without this, a client that reconnects in
     * the window between {@code running = false} and the old thread noticing would start a
     * SECOND streamer thread while the old one is still looping (both would then write to
     * every client, doubling every frame). Guarded by LOCK for writes, volatile for the
     * loop's own read.
     */
    private static volatile int generation;

    private DispatchStreamer() {
    }

    public static int clampedUpdateMillis(AddonServerConfig.Dispatch config) {
        return Math.max(100, Math.min(5_000, config.updateMillis));
    }

    /** Connected SSE client. Writes are serialized on the instance monitor. */
    static final class Client {
        final AsyncContext asyncContext;
        final ServletOutputStream outputStream;
        final int dimensionIndex;
        volatile boolean needsFull = true;

        Client(AsyncContext asyncContext, ServletOutputStream outputStream, int dimensionIndex) {
            this.asyncContext = asyncContext;
            this.outputStream = outputStream;
            this.dimensionIndex = dimensionIndex;
        }

        /** Called from the streamer thread. Returns false on failure (client dead). */
        boolean write(byte[] frame) {
            try {
                synchronized (this) {
                    outputStream.write(frame);
                    outputStream.flush();
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        void close() {
            try {
                asyncContext.complete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class DimensionState {
        long tick;
        Map<Long, DispatchSampler.VehicleSnapshot> vehicles = new HashMap<>();
        Map<String, DispatchSampler.SignalSnapshot> signals = new HashMap<>();
        Set<Long> holds = Set.of();
        Set<Long> obstructed = Set.of();
        /** New clients read the backlog over /api/alerts; the stream pushes only newer. */
        long lastAlertSeq = DispatchEvents.latestSeq();
        /** vehicleId → detector state for the stalled/late alerts. */
        Map<Long, VehicleWatch> watch = new HashMap<>();
    }

    /** Per-vehicle detector memory (streamer thread only). */
    private static final class VehicleWatch {
        long stoppedSince;      // 0 = moving or legitimately stopped
        boolean stallAlerted;
        boolean lateAlerted;
    }

    static boolean hasCapacity() {
        return CLIENTS.size() < AddonServerConfig.get().dispatch.maxClients;
    }

    /**
     * Registers a client; returns false when the cap is hit (checked again under the
     * lock — the servlet's pre-check is only advisory). Starts the scheduler on 0→1.
     */
    static boolean register(AsyncContext asyncContext, ServletOutputStream outputStream, int dimensionIndex) {
        synchronized (LOCK) {
            // Refuse once the session is gone (SERVER_STOPPING clears the registry BEFORE
            // calling shutdown()), so a late connection can never restart the scheduler.
            if (!DispatchRegistry.isActive()) {
                return false;
            }
            if (CLIENTS.size() >= AddonServerConfig.get().dispatch.maxClients) {
                return false;
            }
            CLIENTS.add(new Client(asyncContext, outputStream, dimensionIndex));
            if (schedulerThread == null) {
                running = true;
                int myGeneration = generation;
                schedulerThread = new Thread(() -> runLoop(myGeneration), "station-announcer-dispatch-streamer");
                schedulerThread.setDaemon(true);
                schedulerThread.start();
            }
        }
        return true;
    }

    private static void unregister(Client client) {
        synchronized (LOCK) {
            CLIENTS.remove(client);
            if (CLIENTS.isEmpty()) {
                stopThreadLocked();
            }
        }
        client.close();
    }

    /** Called from AddonInit's SERVER_STOPPING hook. */
    public static void shutdown() {
        List<Client> toClose;
        synchronized (LOCK) {
            toClose = new ArrayList<>(CLIENTS);
            CLIENTS.clear();
            stopThreadLocked();
        }
        toClose.forEach(Client::close);
    }

    /**
     * Must hold LOCK. Idempotent: safe to call with no thread running, and safe to call
     * from the streamer thread itself (the last client disconnecting is detected during a
     * tick), in which case the loop's generation check ends it after the current tick.
     */
    private static void stopThreadLocked() {
        running = false;
        generation++;
        if (schedulerThread != null) {
            // Never interrupt ourselves into a bogus "interrupted" state mid-tick; the
            // generation check ends this thread cleanly. Other threads get woken up.
            if (schedulerThread != Thread.currentThread()) {
                schedulerThread.interrupt();
            }
            schedulerThread = null;
        }
        DIMENSION_STATES.clear();
        DispatchSampler.clearCaches();
    }

    // ------------------------------------------------------------------ scheduler ----

    private static void runLoop(int myGeneration) {
        while (running && generation == myGeneration) {
            long tickStart = System.currentTimeMillis();
            int interval = clampedUpdateMillis(AddonServerConfig.get().dispatch);
            try {
                tickOnce(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Dispatch streamer tick failed", t);
            }
            if (!running || generation != myGeneration) {
                return;
            }
            long sleepMillis = interval - (System.currentTimeMillis() - tickStart);
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void tickOnce(int interval) throws InterruptedException {
        // Dimensions with at least one subscriber.
        TreeSet<Integer> dimensionIndices = new TreeSet<>();
        for (Client client : CLIENTS) {
            dimensionIndices.add(client.dimensionIndex);
        }
        if (dimensionIndices.isEmpty()) {
            return;
        }

        // One sampling Runnable per subscribed dimension, on that simulator's own thread.
        CountDownLatch latch = new CountDownLatch(dimensionIndices.size());
        Map<Integer, DispatchSampler.DimensionSample> samples = new ConcurrentHashMap<>();
        for (int dimensionIndex : dimensionIndices) {
            Simulator simulator = DispatchRegistry.simulator(dimensionIndex);
            if (simulator == null) {
                latch.countDown();
                continue;
            }
            int index = dimensionIndex;
            try {
                simulator.run(() -> {
                    try {
                        samples.put(index, DispatchSampler.sample(simulator));
                    } catch (Throwable t) {
                        StationAnnouncer.LOGGER.warn("Dispatch sampling failed for dimension {}", index, t);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        }
        // Bounded wait: a stalled/stopped simulator only costs this tick, never a hang.
        latch.await(Math.max(50, interval), TimeUnit.MILLISECONDS);

        for (Map.Entry<Integer, DispatchSampler.DimensionSample> entry : samples.entrySet()) {
            processDimension(entry.getKey(), entry.getValue());
        }
    }

    // ------------------------------------------------- delta + serialization + writes ----

    private static void processDimension(int dimensionIndex, DispatchSampler.DimensionSample sample) {
        DimensionState state = DIMENSION_STATES.computeIfAbsent(dimensionIndex, key -> new DimensionState());
        boolean fullTick = state.tick % FULL_EVERY_N_TICKS == 0;
        state.tick++;

        Map<Long, DispatchSampler.VehicleSnapshot> currentVehicles = new HashMap<>();
        for (DispatchSampler.VehicleSnapshot vehicle : sample.vehicles) {
            currentVehicles.put(vehicle.id, vehicle);
        }
        Map<String, DispatchSampler.SignalSnapshot> currentSignals = new HashMap<>();
        for (DispatchSampler.SignalSnapshot signal : sample.signals) {
            currentSignals.put(signal.railId, signal);
        }

        // Live hold / obstruction state (thread-safe reads: both engines expose
        // ConcurrentHashMap-backed snapshots) plus the abnormal-state detectors,
        // which may append to the alert ring this same tick.
        Set<Long> holds = HoldRuleEngine.heldPlatforms();
        Set<Long> obstructed = DoorObstructionEngine.obstructedVehicleIds();
        runDetectors(state, sample, holds, obstructed);
        List<DispatchEvents.Alert> newAlerts = DispatchEvents.since(state.lastAlertSeq);
        if (!newAlerts.isEmpty()) {
            state.lastAlertSeq = newAlerts.get(newAlerts.size() - 1).seq();
        }
        boolean holdsChanged = !holds.equals(state.holds);
        boolean obstructedChanged = !obstructed.equals(state.obstructed);

        byte[] deltaFrame = null;
        byte[] fullFrame = null;
        List<Client> dead = null;
        for (Client client : CLIENTS) {
            if (client.dimensionIndex != dimensionIndex) {
                continue;
            }
            byte[] frame;
            if (fullTick || client.needsFull) {
                if (fullFrame == null) {
                    JsonObject payload = buildFull(dimensionIndex, sample);
                    payload.add("holds", idArray(holds));
                    payload.add("obstructed", idArray(obstructed));
                    addAlerts(payload, newAlerts);
                    fullFrame = frame("full", payload);
                }
                frame = fullFrame;
            } else {
                if (deltaFrame == null) {
                    JsonObject payload = buildDelta(dimensionIndex, sample, state, currentVehicles, currentSignals);
                    // Absent keys mean "unchanged" on deltas; the full frame re-baselines.
                    if (holdsChanged) {
                        payload.add("holds", idArray(holds));
                    }
                    if (obstructedChanged) {
                        payload.add("obstructed", idArray(obstructed));
                    }
                    addAlerts(payload, newAlerts);
                    deltaFrame = frame("delta", payload);
                }
                frame = deltaFrame;
            }
            if (client.write(frame)) {
                client.needsFull = false;
            } else {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(client);
            }
        }

        state.vehicles = currentVehicles;
        state.signals = currentSignals;
        state.holds = holds;
        state.obstructed = obstructed;

        if (dead != null) {
            dead.forEach(DispatchStreamer::unregister);
        }
    }

    // --------------------------------------------------------- abnormal-state alerts ----

    /** Deviation past this raises one {@code late} alert (until it recovers below). */
    private static final long LATE_ALERT_MILLIS = 180_000;
    /** Stationary this long with no legitimate reason raises one {@code stalled} alert. */
    private static final long STALL_ALERT_MILLIS = 90_000;

    /**
     * Streamer-thread detectors over the fresh sample. Alerts are edge-triggered per
     * vehicle: one when the condition starts, re-armed when it clears. A stop is
     * "legitimate" while the vehicle is dwelling, held at a platform, door-obstructed
     * or driven manually — everything else stopped for {@value #STALL_ALERT_MILLIS} ms
     * (a red signal in a deadlock, the class of problem Thomas found by walking there)
     * is worth a ticker line.
     */
    private static void runDetectors(DimensionState state, DispatchSampler.DimensionSample sample,
                                     Set<Long> holds, Set<Long> obstructed) {
        for (DispatchSampler.VehicleSnapshot vehicle : sample.vehicles) {
            VehicleWatch watch = state.watch.computeIfAbsent(vehicle.id, key -> new VehicleWatch());

            boolean veryLate = vehicle.deviationMs >= LATE_ALERT_MILLIS;
            if (veryLate && !watch.lateAlerted) {
                watch.lateAlerted = true;
                DispatchEvents.alert("late", "warn", vehicle.id, 0,
                        labelOf(vehicle) + " is " + (vehicle.deviationMs / 60_000) + "+ min behind schedule");
            } else if (!veryLate) {
                watch.lateAlerted = false;
            }

            boolean legitimatelyStopped = vehicle.dwellRemainingMs > 0 || vehicle.manual
                    || obstructed.contains(vehicle.id)
                    || (vehicle.prevPlatformId != 0 && holds.contains(vehicle.prevPlatformId)
                        && vehicle.platformFraction == 0);
            if (vehicle.speedKmh > 0 || legitimatelyStopped) {
                watch.stoppedSince = 0;
                watch.stallAlerted = false;
            } else {
                if (watch.stoppedSince == 0) {
                    watch.stoppedSince = sample.sampledAt;
                } else if (!watch.stallAlerted && sample.sampledAt - watch.stoppedSince >= STALL_ALERT_MILLIS) {
                    watch.stallAlerted = true;
                    DispatchEvents.alert("stalled", "bad", vehicle.id, 0,
                            labelOf(vehicle) + " has been stopped for "
                                    + ((sample.sampledAt - watch.stoppedSince) / 1000) + "s outside a dwell");
                }
            }
        }
        state.watch.keySet().removeIf(id -> {
            for (DispatchSampler.VehicleSnapshot vehicle : sample.vehicles) {
                if (vehicle.id == id) {
                    return false;
                }
            }
            return true;
        });
    }

    private static String labelOf(DispatchSampler.VehicleSnapshot vehicle) {
        String route = vehicle.routeNumber != null && !vehicle.routeNumber.isEmpty()
                ? vehicle.routeNumber
                : (vehicle.routeName == null ? "" : vehicle.routeName.split("\\|")[0]);
        String destination = vehicle.destination == null ? "" : vehicle.destination.split("\\|")[0];
        return ("Train " + route + (destination.isEmpty() ? "" : " to " + destination)).trim();
    }

    private static JsonArray idArray(Set<Long> ids) {
        JsonArray array = new JsonArray();
        for (long id : ids) {
            array.add(String.valueOf(id));
        }
        return array;
    }

    private static void addAlerts(JsonObject payload, List<DispatchEvents.Alert> alerts) {
        if (alerts.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (DispatchEvents.Alert alert : alerts) {
            array.add(alert.toJson());
        }
        payload.add("alerts", array);
    }

    private static JsonObject payloadHeader(int dimensionIndex, long sampledAt) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("serverTime", sampledAt);
        json.addProperty("dimension", dimensionIndex);
        // Every frame, full and delta alike, carries the complete player list for this
        // dimension: it is a handful of names, so there is nothing worth deltaing, and
        // building it here means neither frame builder can forget it.
        json.add("players", playersJson(dimensionIndex));
        return json;
    }

    /**
     * The dispatch map's live player layer, filtered to this frame's dimension.
     *
     * <p>Read on the streamer thread from {@link PlayerPositions}' volatile immutable
     * snapshot — no simulator involvement, no server-thread hop. The uuid is captured
     * but deliberately NOT sent: the map needs a label and a dot, not an identity.</p>
     */
    private static JsonArray playersJson(int dimensionIndex) {
        JsonArray array = new JsonArray();
        List<PlayerPositions.Position> positions = PlayerPositions.get();
        if (positions.isEmpty()) {
            return array;
        }
        Simulator simulator = DispatchRegistry.simulator(dimensionIndex);
        if (simulator == null) {
            return array;
        }
        String dimension = simulator.dimension;
        for (PlayerPositions.Position position : positions) {
            if (!dimension.equals(position.worldId())) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("name", position.name());
            json.addProperty("x", round(position.x()));
            json.addProperty("y", round(position.y()));
            json.addProperty("z", round(position.z()));
            array.add(json);
        }
        return array;
    }

    /** One decimal place: a player dot is never worth seventeen significant figures. */
    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static JsonObject buildFull(int dimensionIndex, DispatchSampler.DimensionSample sample) {
        JsonObject json = payloadHeader(dimensionIndex, sample.sampledAt);
        JsonArray vehicles = new JsonArray();
        for (DispatchSampler.VehicleSnapshot vehicle : sample.vehicles) {
            vehicles.add(vehicleJson(vehicle, true));
        }
        json.add("vehicles", vehicles);
        JsonArray signals = new JsonArray();
        for (DispatchSampler.SignalSnapshot signal : sample.signals) {
            signals.add(signalJson(signal));
        }
        json.add("signals", signals);
        return json;
    }

    private static JsonObject buildDelta(int dimensionIndex, DispatchSampler.DimensionSample sample,
                                         DimensionState previous,
                                         Map<Long, DispatchSampler.VehicleSnapshot> currentVehicles,
                                         Map<String, DispatchSampler.SignalSnapshot> currentSignals) {
        JsonObject json = payloadHeader(dimensionIndex, sample.sampledAt);

        JsonArray vehicles = new JsonArray();
        for (DispatchSampler.VehicleSnapshot vehicle : sample.vehicles) {
            DispatchSampler.VehicleSnapshot old = previous.vehicles.get(vehicle.id);
            if (old == null) {
                vehicles.add(vehicleJson(vehicle, true)); // new vehicle → include static block
            } else if (!vehicle.staticEquals(old)) {
                vehicles.add(vehicleJson(vehicle, true)); // route/consist changed mid-run
            } else if (!vehicle.dynamicEquals(old)) {
                vehicles.add(vehicleJson(vehicle, false));
            }
        }
        json.add("vehicles", vehicles);

        JsonArray removed = new JsonArray();
        for (Long id : previous.vehicles.keySet()) {
            if (!currentVehicles.containsKey(id)) {
                removed.add(String.valueOf(id));
            }
        }
        json.add("removed", removed);

        JsonArray signals = new JsonArray();
        for (DispatchSampler.SignalSnapshot signal : sample.signals) {
            DispatchSampler.SignalSnapshot old = previous.signals.get(signal.railId);
            if (old == null || !signal.sameState(old)) {
                signals.add(signalJson(signal));
            }
        }
        json.add("signals", signals);

        JsonArray signalsCleared = new JsonArray();
        for (String railId : previous.signals.keySet()) {
            if (!currentSignals.containsKey(railId)) {
                signalsCleared.add(railId);
            }
        }
        json.add("signalsCleared", signalsCleared);

        return json;
    }

    private static JsonObject vehicleJson(DispatchSampler.VehicleSnapshot vehicle, boolean includeStatic) {
        JsonObject json = new JsonObject();
        json.addProperty("id", String.valueOf(vehicle.id));
        json.addProperty("x", vehicle.x);
        json.addProperty("y", vehicle.y);
        json.addProperty("z", vehicle.z);
        json.addProperty("kmh", vehicle.speedKmh);
        json.addProperty("rev", vehicle.reversed);
        if (vehicle.railId != null) {
            json.addProperty("rail", vehicle.railId);
            json.addProperty("railT", vehicle.railT);
        }
        json.addProperty("doors", vehicle.doorsOpen);
        json.addProperty("dwellMs", vehicle.dwellRemainingMs);
        json.addProperty("devMs", vehicle.deviationMs);
        json.addProperty("manual", vehicle.manual);
        json.addProperty("stop", vehicle.stopIndex);
        // Route-relative progress for the stringline's live tips. Always present ("0"
        // = none): the frontend merges deltas over old state, so an omitted key would
        // leave a stale tip behind at the path's extremes.
        json.addProperty("pPlat", String.valueOf(vehicle.prevPlatformId));
        json.addProperty("nPlat", String.valueOf(vehicle.nextPlatformId));
        json.addProperty("pFrac", vehicle.platformFraction);
        if (includeStatic) {
            JsonObject route = new JsonObject();
            route.addProperty("id", String.valueOf(vehicle.routeId));
            route.addProperty("name", vehicle.routeName);
            route.addProperty("number", vehicle.routeNumber);
            route.addProperty("color", vehicle.routeColor);
            route.addProperty("dest", vehicle.destination);
            route.addProperty("nextStation", vehicle.nextStation);
            json.add("route", route);
            JsonObject consist = new JsonObject();
            consist.addProperty("sidingId", String.valueOf(vehicle.sidingId));
            consist.addProperty("siding", vehicle.sidingName);
            consist.addProperty("depot", vehicle.depotName);
            JsonArray cars = new JsonArray();
            for (String car : vehicle.cars) {
                cars.add(car);
            }
            consist.add("cars", cars);
            JsonArray carLengths = new JsonArray();
            for (double length : vehicle.carLengths) {
                carLengths.add(length);
            }
            consist.add("carLengths", carLengths);
            json.add("consist", consist);
        }
        return json;
    }

    private static JsonObject signalJson(DispatchSampler.SignalSnapshot signal) {
        JsonObject json = new JsonObject();
        json.addProperty("rail", signal.railId);
        JsonArray occupied = new JsonArray();
        for (long color : signal.occupiedColors) {
            occupied.add(color);
        }
        json.add("occupied", occupied);
        JsonArray reserved = new JsonArray();
        for (long color : signal.reservedColors) {
            reserved.add(color);
        }
        json.add("reserved", reserved);
        return json;
    }

    private static byte[] frame(String eventName, JsonObject payload) {
        return ("event: " + eventName + "\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
