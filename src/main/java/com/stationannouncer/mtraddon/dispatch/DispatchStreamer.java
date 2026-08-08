package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
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
 * signal rails). Every payload carries {@code schemaVersion} and {@code serverTime}.
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
                    fullFrame = frame("full", buildFull(dimensionIndex, sample));
                }
                frame = fullFrame;
            } else {
                if (deltaFrame == null) {
                    deltaFrame = frame("delta", buildDelta(dimensionIndex, sample, state, currentVehicles, currentSignals));
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

        if (dead != null) {
            dead.forEach(DispatchStreamer::unregister);
        }
    }

    private static JsonObject payloadHeader(int dimensionIndex, long sampledAt) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("serverTime", sampledAt);
        json.addProperty("dimension", dimensionIndex);
        return json;
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
