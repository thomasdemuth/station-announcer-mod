package com.stationannouncer.client.mtraddon.cab;

import com.mojang.authlib.GameProfile;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.DrivingHud;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.resource.VehicleResourceCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Cosmetic passengers on the platforms of the train this client drives or guards
 * (MANUAL_DRIVING_PLAN.md §3.9 / §6 item 10 — Thomas: "AI passengers can be cosmetic, and you
 * should be penalized for leaving people on the platform"). Client-only: nothing is sent to the
 * server and nothing exists unless this client is crewing a train.
 *
 * <p><b>Where.</b> Only the platform the train stands at and its next stop, taken from the
 * train's own path (the dwell segments {@link DrivingHud} also reads), at most
 * {@link #MAX_PLATFORMS} at a time, built once when within {@link #SPAWN_RANGE} blocks and dropped
 * (with their people) beyond {@link #DESPAWN_RANGE}. {@link PaxPlatform} probes the floor beside
 * the rail to find which side(s) the platform is on.</p>
 *
 * <p><b>Who.</b> {@link PaxEntity} — client-only player entities with default skins, walked at
 * 20 Hz through the vanilla remote-player interpolation. A small waiting crowd (0–10) stands
 * facing the track, mostly where the train will stop; now and then someone new walks in from the
 * back of the platform.</p>
 *
 * <p><b>The stop.</b> When the train stands at the platform with its doors open: a few people
 * step out of the doorways and walk off toward the back and along the platform; then everyone
 * waiting for this train (most of the crowd — some wait for another line) walks to the nearest
 * doorway and steps in (= boarded, despawned). Doorways come from MTR's own vehicle resource
 * ({@code VehicleResourceCache.doorways}, the boxes MTR uses to let players walk in), mapped to
 * the world with MTR's car transform; a car whose resource is not cached gets evenly spaced
 * doors. If the doors close while people are still walking to them, they turn back; whoever
 * wanted this train and is still on the platform when it DEPARTS is "left behind" →
 * {@link CabScore#leftBehind} (counted at departure, so a re-opened door can still save them).
 * Now and then a late runner sprints for the doors while they are open.</p>
 *
 * <p>{@link #boardingCount()} = people still boarding while the doors are open (for the guard's
 * "N passengers still boarding").</p>
 *
 * <p>Cost: one MTR vehicle lookup every 10 ticks while idle; while active, ≤ {@code max}
 * entities stepped per tick, a platform/next-stop refresh once a second, and the block probing
 * plus door lookup once per platform / per stop. Nothing per frame.</p>
 */
@Environment(EnvType.CLIENT)
public final class CabPassengers {
    private static final double SPAWN_RANGE = 88;
    /** Metres past a platform's end that still count as standing at it (FeedBuilder.PLATFORM_END_TOLERANCE). */
    private static final double STOP_END_TOLERANCE = 3;
    private static final double DESPAWN_RANGE = 96;
    private static final int MAX_PLATFORMS = 4;
    private static final int REFRESH_TICKS = 20;
    private static final int IDLE_CHECK_TICKS = 10;
    private static final double WALK_MIN = 1.9;
    private static final double WALK_RANGE = 0.8;
    private static final double RUN_SPEED = 4.6;
    /** A waiting passenger farther than this from every doorway does not try for this train. */
    private static final double MAX_DOOR_REACH = 30;
    /** Where "inside the car" is, as a fraction of the doorway's lateral offset. */
    private static final double INSIDE_FRACTION = 0.25;
    private static final float INTENT_CHANCE = 0.85f;
    private static final int ENTITY_ID_START = -1_900_000;
    private static final String PROFILE_NAME = "Passenger";

    /** World ticks per trickle roll (someone new may walk onto a waiting platform). */
    private static final long TRICKLE_PERIOD = 160;
    private static final List<PaxPerson> PEOPLE = new ArrayList<>();
    private static final List<PaxPlatform> PLATFORMS = new ArrayList<>();
    /** vehicleId#car/total → doorway centres in car space (x, z pairs); empty = resource has none. */
    private static final Map<String, float[]> DOORWAYS = new HashMap<>();

    @Nullable
    private static ClientWorld world;
    private static long vehicleId;
    private static int ticks;
    private static int idleTicks;
    private static int errorCooldown;
    private static int nextEntityId = ENTITY_ID_START;

    /** Platform of the current stop session (doors opened there), 0 = none. */
    private static long sessionPlatform;
    private static boolean sessionDoorsOpen;
    private static int sessionRunners;
    private static int sessionTicks;
    private static long sessionSeed;
    /** Doorways of the standing train: 4 floats each — outside x, z, inside x, z. */
    private static float[] doors = new float[64];
    private static int doorCount;
    private static int boarding;

    private CabPassengers() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CabPassengers::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            forget();
            DOORWAYS.clear();
        });
    }

    /** Passengers still boarding the crewed train while its doors are open (0 otherwise). */
    public static int boardingCount() {
        return boarding;
    }

    private static boolean enabled() {
        return AddonClientConfig.get().cabPassengers;
    }

    private static int maxPassengers() {
        return Math.max(0, Math.min(200, AddonClientConfig.get().cabPassengersMax));
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftClient client) {
        ClientWorld current = client.world;
        if (current == null || client.player == null) {
            if (world != null || !PEOPLE.isEmpty()) {
                forget();
            }
            return;
        }
        if (errorCooldown > 0) {
            errorCooldown--;
            return;
        }
        boolean active = world != null;
        if (!active && ++idleTicks % IDLE_CHECK_TICKS != 0) {
            return;
        }
        VehicleExtension vehicle = enabled() && maxPassengers() > 0 ? DrivingHud.findDrivenVehicle() : null;
        if (vehicle == null) {
            if (active) {
                clear();
            }
            return;
        }
        if (current != world || vehicle.getId() != vehicleId) {
            clear();
            world = current;
            vehicleId = vehicle.getId();
            ticks = 0;
        }
        ticks++;
        try {
            update(client, vehicle);
        } catch (RuntimeException e) {
            // MTR's client data can be swapped underneath us mid-sync; drop the crowd and retry later.
            clear();
            errorCooldown = 200;
        }
    }

    private static void update(MinecraftClient client, VehicleExtension vehicle) {
        double railProgress = DrivingHud.readRailProgress(vehicle);
        if (Double.isNaN(railProgress)) {
            return;
        }
        VehicleExtraData extra = vehicle.vehicleExtraData;
        ObjectImmutableList<PathData> path = extra.immutablePath;
        if (path.isEmpty()) {
            return;
        }
        int headIndex = Utilities.getIndexFromConditionalList(path, railProgress);
        long sidingId = extra.getSidingId();
        int standing = standingSegment(path, headIndex, railProgress, vehicle.getSpeed(), sidingId);
        long stoppedAt = standing < 0 ? 0 : path.get(standing).getSavedRailBaseId();
        if (ticks % REFRESH_TICKS == 1) {
            refresh(client, extra, path, headIndex, railProgress, sidingId, stoppedAt, standing);
        }
        handleStop(vehicle, extra, stoppedAt);
        trickle();
        move();
    }

    // ------------------------------------------------------------- platforms

    private static void refresh(MinecraftClient client, VehicleExtraData extra, ObjectImmutableList<PathData> path,
                                int headIndex, double railProgress, long sidingId, long stoppedAt, int standing) {
        double px = client.player.getX();
        double pz = client.player.getZ();
        for (int i = PLATFORMS.size() - 1; i >= 0; i--) {
            if (PLATFORMS.get(i).distanceTo(px, pz) > DESPAWN_RANGE) {
                removePlatform(i);
            }
        }
        if (stoppedAt != 0) {
            ensure(stoppedAt, path.get(standing), extra, px, pz);
        }
        PathData next = nextStop(extra, path, headIndex, railProgress, sidingId);
        if (next != null && next.getSavedRailBaseId() != stoppedAt) {
            ensure(next.getSavedRailBaseId(), next, extra, px, pz);
        }
    }

    /** The next platform dwell segment ahead (the head segment counts while rolling into it). */
    @Nullable
    private static PathData nextStop(VehicleExtraData extra, ObjectImmutableList<PathData> path, int headIndex,
                                     double railProgress, long sidingId) {
        if (headIndex >= 0 && headIndex < path.size()) {
            PathData head = path.get(headIndex);
            if (DrivingHud.isPlatformDwell(head, sidingId) && head.getEndDistance() > railProgress + 0.5) {
                return head;
            }
        }
        for (DrivingHud.WalkEntry entry : DrivingHud.buildWalk(extra, path, headIndex, railProgress, 100)) {
            if (DrivingHud.isPlatformDwell(entry.pathData(), sidingId)) {
                return entry.pathData();
            }
        }
        return null;
    }

    private static void ensure(long platformId, PathData pathData, VehicleExtraData extra, double px, double pz) {
        if (find(platformId) != null || PLATFORMS.size() >= MAX_PLATFORMS || world == null) {
            return;
        }
        double length = Math.max(0, pathData.getEndDistance() - pathData.getStartDistance());
        Vector a = pathData.getPosition(0);
        Vector b = pathData.getPosition(length);
        Vector m = pathData.getPosition(length / 2);
        double nearest = Math.min(distance(a, px, pz), Math.min(distance(b, px, pz), distance(m, px, pz)));
        if (nearest > SPAWN_RANGE) {
            return;
        }
        // Shared seed: the driver's and the guard's clients build the same crowd for this visit.
        long seed = mix(mix(platformId, vehicleId), Double.doubleToLongBits(pathData.getEndDistance()));
        Random random = new Random(seed);
        PaxPlatform platform = PaxPlatform.build(world, platformId, seed, pathData, extra.getTotalVehicleLength(), random);
        if (platform == null) {
            return; // part of it is in an unloaded chunk — try again next refresh
        }
        PLATFORMS.add(platform);
        for (int i = 0; i < platform.target; i++) {
            spawnWaiting(platform, false, random.nextLong());
        }
    }

    private static void removePlatform(int index) {
        PaxPlatform platform = PLATFORMS.remove(index);
        if (sessionPlatform == platform.platformId) {
            resetSession(); // out of range — the train left it long ago, nothing to score
        }
        for (int i = PEOPLE.size() - 1; i >= 0; i--) {
            if (PEOPLE.get(i).platform == platform) {
                despawn(i);
            }
        }
    }

    @Nullable
    private static PaxPlatform find(long platformId) {
        for (PaxPlatform platform : PLATFORMS) {
            if (platform.platformId == platformId) {
                return platform;
            }
        }
        return null;
    }

    /**
     * The platform dwell segment the stopped train stands at, or -1 — the server's tolerant test
     * ({@code FeedBuilder.platformAt}): the head anywhere along the platform, up to 3 m past its end.
     * A manually driven train never stops exactly on MTR's end point (the old exact test meant the
     * crowd never boarded a driven train).
     */
    private static int standingSegment(ObjectImmutableList<PathData> path, int headIndex, double railProgress,
                                       double speed, long sidingId) {
        if (speed > 1E-4 || path.isEmpty()) {
            return -1;
        }
        int head = Math.min(path.size() - 1, Math.max(0, headIndex));
        for (int i = Math.max(0, head - 2); i <= Math.min(path.size() - 1, head + 1); i++) {
            PathData segment = path.get(i);
            if (DrivingHud.isPlatformDwell(segment, sidingId) && railProgress >= segment.getStartDistance() - 0.5
                    && railProgress <= segment.getEndDistance() + STOP_END_TOLERANCE) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ stop

    private static void handleStop(VehicleExtension vehicle, VehicleExtraData extra, long stoppedAt) {
        if (sessionPlatform != 0 && sessionPlatform != stoppedAt) {
            endSession(); // departed (or the stop ended some other way)
        }
        PaxPlatform platform = stoppedAt == 0 ? null : find(stoppedAt);
        if (platform == null || platform.isEmpty()) {
            boarding = 0;
            return;
        }
        boolean open = vehicle.persistentVehicleData.getAdjustedDoorMultiplier(extra) > 0;
        if (sessionPlatform == 0) {
            if (!open || vehicle.persistentVehicleData.getDoorValue() < 0.5) {
                boarding = 0;
                return;
            }
            startSession(vehicle, platform);
        } else if (open != sessionDoorsOpen) {
            sessionDoorsOpen = open;
            if (open) {
                queueBoarders(platform, 10);
            } else {
                sendBack(platform);
            }
        }
        if (sessionDoorsOpen && ++sessionTicks % 20 == 0 && sessionRunners < 2) {
            long roll = mix(sessionSeed, sessionTicks);
            if (unit(roll) < 0.05f) {
                spawnRunner(platform, roll);
            }
        }
        boarding = sessionDoorsOpen ? countBoarding(platform) : 0;
    }

    private static void startSession(VehicleExtension vehicle, PaxPlatform platform) {
        sessionPlatform = platform.platformId;
        sessionDoorsOpen = true;
        sessionRunners = 0;
        sessionTicks = 0;
        sessionSeed = mix(platform.seed, 0x5E55_10E5L);
        computeDoors(vehicle);
        Random random = new Random(sessionSeed);
        int alighting = random.nextInt(4) + random.nextInt(4);
        int spawned = 0;
        for (int i = 0; i < alighting; i++) {
            if (spawnAlighter(platform, random)) {
                spawned++;
            }
        }
        // Let them off first.
        queueBoarders(platform, 20 + spawned * 6);
    }

    private static void queueBoarders(PaxPlatform platform, int baseDelay) {
        for (PaxPerson person : PEOPLE) {
            if (person.platform == platform && person.intent
                    && (person.state == PaxPerson.WAITING || person.state == PaxPerson.TO_SLOT)) {
                person.state = PaxPerson.QUEUED;
                person.delay = baseDelay + bounded(mix(person.seed, sessionSeed + baseDelay), 50);
            }
        }
    }

    /** Doors closing: whoever was on the way turns back (counted only if the train then leaves). */
    private static void sendBack(PaxPlatform platform) {
        for (PaxPerson person : PEOPLE) {
            if (person.platform == platform
                    && (person.state == PaxPerson.QUEUED || person.state == PaxPerson.TO_DOOR)) {
                goToSlot(person);
            }
        }
    }

    /** The train left the platform: score whoever wanted it and is still on the platform. */
    private static void endSession() {
        PaxPlatform platform = find(sessionPlatform);
        if (platform != null) {
            int left = 0;
            for (int i = PEOPLE.size() - 1; i >= 0; i--) {
                PaxPerson person = PEOPLE.get(i);
                if (person.platform != platform) {
                    continue;
                }
                if (person.state == PaxPerson.INTO_TRAIN) {
                    despawn(i); // in the doorway as it left: on board
                } else if (person.waitingForTrain()) {
                    left++;
                    if (person.state == PaxPerson.QUEUED || person.state == PaxPerson.TO_DOOR) {
                        goToSlot(person);
                    }
                    person.intent = (mix(person.seed, 0x1EF7L) & 1) == 0; // some give up, some wait for the next one
                }
            }
            if (left > 0) {
                CabScore.leftBehind(left);
            }
        }
        resetSession();
    }

    private static void resetSession() {
        sessionPlatform = 0;
        sessionDoorsOpen = false;
        sessionRunners = 0;
        doorCount = 0;
        boarding = 0;
    }

    private static int countBoarding(PaxPlatform platform) {
        int count = 0;
        for (PaxPerson person : PEOPLE) {
            if (person.platform == platform && person.intent && (person.state == PaxPerson.QUEUED
                    || person.state == PaxPerson.TO_DOOR || person.state == PaxPerson.INTO_TRAIN)) {
                count++;
            }
        }
        return count;
    }

    // ----------------------------------------------------------------- doors

    /** Doorway positions of the standing train, in the world (once per stop). */
    private static void computeDoors(VehicleExtension vehicle) {
        doorCount = 0;
        // The unsmoothed positions (core Vehicle): the smoothed variant is hooked for the lean renderer.
        ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>>> cars
                = vehicle.getVehicleCarsAndPositions();
        int total = cars.size();
        TransportMode mode = vehicle.getTransportMode();
        for (int i = 0; i < total; i++) {
            VehicleCar car = cars.get(i).left();
            ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>> bogies = cars.get(i).right();
            if (bogies.isEmpty() || bogies.size() > 2) {
                continue; // MTR's car transform only handles one or two bogies
            }
            ObjectArrayList<PositionAndRotation> bogiePositions = new ObjectArrayList<>(bogies.size());
            for (ObjectObjectImmutablePair<Vector, Vector> bogie : bogies) {
                bogiePositions.add(new PositionAndRotation(bogie.left(), bogie.right(), false));
            }
            // Exactly how RenderVehicles places the car (pitch ignored: platforms are level).
            PositionAndRotation carPosition = new PositionAndRotation(bogiePositions, car, false);
            double halfWidth = car.getWidth() / 2;
            float[] local = doorways(mode, car.getVehicleId(), i, total);
            if (local != null) {
                for (int j = 0; j + 1 < local.length; j += 2) {
                    addDoor(carPosition, local[j], local[j + 1], halfWidth);
                }
            } else {
                double length = car.getLength();
                int count = Math.max(1, (int) Math.round(length / 7));
                for (int k = 0; k < count; k++) {
                    double z = (k + 0.5) * length / count - length / 2;
                    addDoor(carPosition, halfWidth, z, halfWidth);
                    addDoor(carPosition, -halfWidth, z, halfWidth);
                }
            }
        }
    }

    private static void addDoor(PositionAndRotation carPosition, double localX, double localZ, double halfWidth) {
        if (Math.abs(localX) < 0.3) {
            return; // an end/gangway doorway, not a side door
        }
        double sign = Math.signum(localX);
        double outsideX = sign * (Math.max(Math.abs(localX), halfWidth) + 0.15);
        Vector outside = carPosition.transformForwards(new Vector(outsideX, 0, localZ), Vector::rotateX, Vector::rotateY, Vector::add);
        Vector inside = carPosition.transformForwards(new Vector(localX * INSIDE_FRACTION, 0, localZ), Vector::rotateX, Vector::rotateY, Vector::add);
        if (doorCount * 4 + 4 > doors.length) {
            float[] grown = new float[doors.length * 2];
            System.arraycopy(doors, 0, grown, 0, doors.length);
            doors = grown;
        }
        int o = doorCount * 4;
        doors[o] = (float) outside.x;
        doors[o + 1] = (float) outside.z;
        doors[o + 2] = (float) inside.x;
        doors[o + 3] = (float) inside.z;
        doorCount++;
    }

    /**
     * Side doorway centres (car space, x across / z along, in blocks) from MTR's vehicle resource,
     * merging the several boxes one doorway may be made of. Null when the resource is not cached
     * yet (try again next stop) or defines no side doorways (cached as such).
     */
    @Nullable
    private static float[] doorways(TransportMode mode, String vehicleResourceId, int car, int total) {
        String key = vehicleResourceId + '#' + car + '/' + total;
        float[] cached = DOORWAYS.get(key);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        float[][] found = {null};
        try {
            CustomResourceLoader.getVehicleById(mode, vehicleResourceId, details -> {
                VehicleResourceCache cache = details.left().getCachedVehicleResource(car, total, false);
                if (cache != null) {
                    found[0] = mergeDoorways(cache.doorways);
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
        if (found[0] != null) {
            DOORWAYS.put(key, found[0]);
        }
        return found[0] == null || found[0].length == 0 ? null : found[0];
    }

    private static float[] mergeDoorways(ObjectImmutableList<Box> boxes) {
        float[] points = new float[boxes.size() * 3]; // x, z, weight
        int count = 0;
        for (Box box : boxes) {
            float x = (float) ((box.getMinXMapped() + box.getMaxXMapped()) / 2);
            float z = (float) ((box.getMinZMapped() + box.getMaxZMapped()) / 2);
            if (Math.abs(x) < 0.3f) {
                continue;
            }
            boolean merged = false;
            for (int i = 0; i < count; i++) {
                int o = i * 3;
                if (Math.signum(points[o]) == Math.signum(x) && Math.abs(points[o + 1] / points[o + 2] - z) < 0.8f) {
                    points[o] += x;
                    points[o + 1] += z;
                    points[o + 2] += 1;
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                int o = count * 3;
                points[o] = x;
                points[o + 1] = z;
                points[o + 2] = 1;
                count++;
            }
        }
        float[] result = new float[count * 2];
        for (int i = 0; i < count; i++) {
            result[i * 2] = points[i * 3] / points[i * 3 + 2];
            result[i * 2 + 1] = points[i * 3 + 1] / points[i * 3 + 2];
        }
        return result;
    }

    /** Index of the doorway whose outside point is nearest, or -1 beyond {@code maxDistance}. */
    private static int nearestDoor(double x, double z, double maxDistance) {
        int best = -1;
        double bestDistance = maxDistance * maxDistance;
        for (int i = 0; i < doorCount; i++) {
            double dx = doors[i * 4] - x;
            double dz = doors[i * 4 + 1] - z;
            double d = dx * dx + dz * dz;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- people

    @Nullable
    private static PaxPerson spawn(PaxPlatform platform, long seed, double x, double y, double z, float yaw) {
        if (world == null || PEOPLE.size() >= maxPassengers()) {
            return null;
        }
        // The UUID picks the default skin, so the same person looks the same on every crew client.
        UUID uuid = new UUID(mix(seed, 1), mix(seed, 2));
        PaxEntity entity = new PaxEntity(world, new GameProfile(uuid, PROFILE_NAME));
        entity.setId(nextEntityId);
        nextEntityId = nextEntityId <= Integer.MIN_VALUE + 1 ? ENTITY_ID_START : nextEntityId - 1;
        entity.refreshPositionAndAngles(x, y, z, yaw, 0);
        entity.updateTrackedPosition(x, y, z);
        entity.setHeadYaw(yaw);
        entity.setBodyYaw(yaw);
        entity.setOnGround(true);
        world.addEntity(entity);
        PaxPerson person = new PaxPerson(entity, platform, seed, x, y, z, yaw);
        person.speed = WALK_MIN + person.random.nextDouble() * WALK_RANGE;
        person.delay = person.random.nextInt(100);
        PEOPLE.add(person);
        return person;
    }

    /** A waiting passenger: standing at a free slot, or ({@code arriving}) walking in from the back. */
    private static void spawnWaiting(PaxPlatform platform, boolean arriving, long seed) {
        Random random = new Random(mix(seed, 3));
        int slot = platform.pickWaitingSlot(random);
        if (slot < 0) {
            return;
        }
        double x = platform.slotX[slot] + jitter(random);
        double z = platform.slotZ[slot] + jitter(random);
        double y = platform.slotY[slot];
        PaxPerson person;
        if (arriving) {
            int from = platform.pickBackSlot(random);
            if (from < 0) {
                return;
            }
            person = spawn(platform, seed, platform.slotX[from], platform.slotY[from], platform.slotZ[from],
                    PaxPlatform.yawOf(x - platform.slotX[from], z - platform.slotZ[from]));
            if (person == null) {
                return;
            }
            person.state = PaxPerson.TO_SLOT;
            person.targetX = x;
            person.targetZ = z;
            person.y = y;
        } else {
            person = spawn(platform, seed, x, y, z, platform.slotYaw[slot] + (random.nextFloat() - 0.5f) * 40);
            if (person == null) {
                return;
            }
            person.state = PaxPerson.WAITING;
        }
        person.slot = slot;
        platform.taken[slot] = true;
        person.intent = unit(mix(seed, 4)) < INTENT_CHANCE;
    }

    /** Someone stepping out of a doorway on a platform side; false if none fits. */
    private static boolean spawnAlighter(PaxPlatform platform, Random random) {
        for (int attempt = 0; attempt < 4 && doorCount > 0; attempt++) {
            int door = random.nextInt(doorCount);
            double outX = doors[door * 4];
            double outZ = doors[door * 4 + 1];
            if (platform.nearestSlot(outX, outZ, false, 1.8) < 0) {
                continue; // this doorway faces the track, not the platform
            }
            int back = platform.nearestSlot(outX, outZ, true, 5);
            if (back < 0) {
                continue;
            }
            double inX = doors[door * 4 + 2];
            double inZ = doors[door * 4 + 3];
            PaxPerson person = spawn(platform, random.nextLong(), inX, platform.slotY[back], inZ,
                    PaxPlatform.yawOf(outX - inX, outZ - inZ));
            if (person == null) {
                return false;
            }
            person.state = PaxPerson.ALIGHT_OUT;
            person.intent = false;
            person.targetX = outX;
            person.targetZ = outZ;
            person.nextX = platform.slotX[back] + jitter(person.random);
            person.nextZ = platform.slotZ[back] + jitter(person.random);
            // Then off along the platform toward its nearer end.
            double s = platform.slotS[back];
            double direction = s < platform.length / 2 ? -1 : 1;
            double room = direction < 0 ? s : platform.length - s;
            double walk = Math.max(0, Math.min(8 + person.random.nextDouble() * 6, room - 0.5));
            person.leaveX = person.nextX + platform.slotTanX[back] * direction * walk;
            person.leaveZ = person.nextZ + platform.slotTanZ[back] * direction * walk;
            return true;
        }
        return false;
    }

    /** A late passenger running for the open doors. */
    private static void spawnRunner(PaxPlatform platform, long seed) {
        int from = platform.pickBackSlot(new Random(mix(seed, 3)));
        if (from < 0) {
            return;
        }
        int door = nearestDoor(platform.slotX[from], platform.slotZ[from], 24);
        if (door < 0) {
            return;
        }
        PaxPerson person = spawn(platform, seed, platform.slotX[from], platform.slotY[from], platform.slotZ[from],
                platform.slotYaw[from]);
        if (person == null) {
            return;
        }
        sessionRunners++;
        person.intent = true;
        person.speed = RUN_SPEED;
        startTowardDoor(person, door);
    }

    private static void startBoarding(PaxPerson person) {
        int door = nearestDoor(person.x, person.z, MAX_DOOR_REACH);
        if (door < 0) {
            person.intent = false; // too far down the platform for this train
            person.state = PaxPerson.WAITING;
            return;
        }
        startTowardDoor(person, door);
    }

    private static void startTowardDoor(PaxPerson person, int door) {
        person.state = PaxPerson.TO_DOOR;
        person.targetX = doors[door * 4];
        person.targetZ = doors[door * 4 + 1];
        person.nextX = doors[door * 4 + 2];
        person.nextZ = doors[door * 4 + 3];
    }

    private static void goToSlot(PaxPerson person) {
        PaxPlatform platform = person.platform;
        if (person.slot < 0) {
            person.slot = platform.pickWaitingSlot(person.random);
            if (person.slot >= 0) {
                platform.taken[person.slot] = true;
            }
        }
        if (person.slot < 0) {
            person.state = PaxPerson.WAITING; // nowhere free: wait where they are
            return;
        }
        person.state = PaxPerson.TO_SLOT;
        person.targetX = platform.slotX[person.slot] + jitter(person.random);
        person.targetZ = platform.slotZ[person.slot] + jitter(person.random);
    }

    /** New arrivals while a platform waits for its train. */
    private static void trickle() {
        if (ticks % 20 != 0 || world == null) {
            return;
        }
        // Rolled on WORLD time (synced from the server), so crew clients agree on who walks in.
        long bucket = world.getTime() / TRICKLE_PERIOD;
        for (PaxPlatform platform : PLATFORMS) {
            if (platform.isEmpty() || platform.platformId == sessionPlatform || platform.trickleBucket == bucket) {
                continue;
            }
            boolean first = platform.trickleBucket == Long.MIN_VALUE;
            platform.trickleBucket = bucket;
            long roll = mix(platform.seed, bucket);
            if (!first && unit(roll) < 0.5f && waitingCount(platform) < platform.target + 2) {
                spawnWaiting(platform, true, roll);
            }
        }
    }

    private static int waitingCount(PaxPlatform platform) {
        int count = 0;
        for (PaxPerson person : PEOPLE) {
            if (person.platform == platform && (person.state == PaxPerson.WAITING
                    || person.state == PaxPerson.TO_SLOT || person.state == PaxPerson.QUEUED)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------- movement

    private static void move() {
        for (int i = PEOPLE.size() - 1; i >= 0; i--) {
            PaxPerson person = PEOPLE.get(i);
            switch (person.state) {
                case PaxPerson.WAITING -> idle(person);
                case PaxPerson.QUEUED -> {
                    if (sessionDoorsOpen && person.platform.platformId == sessionPlatform) {
                        if (--person.delay <= 0) {
                            startBoarding(person);
                        }
                    } else {
                        person.state = PaxPerson.WAITING;
                    }
                }
                default -> {
                    if (step(person)) {
                        arrive(person, i);
                    }
                }
            }
        }
    }

    /** One 20 Hz step toward the current target; true on arrival. */
    private static boolean step(PaxPerson person) {
        double dx = person.targetX - person.x;
        double dz = person.targetZ - person.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double stride = person.speed / 20;
        boolean arrived = distance <= stride;
        if (distance > 1E-4) {
            person.yaw = PaxPlatform.yawOf(dx, dz);
        }
        if (arrived) {
            person.x = person.targetX;
            person.z = person.targetZ;
        } else {
            person.x += dx / distance * stride;
            person.z += dz / distance * stride;
        }
        person.entity.updateTrackedPositionAndAngles(person.x, person.y, person.z, person.yaw, 0, 3);
        person.entity.updateTrackedHeadRotation(person.yaw, 3);
        return arrived;
    }

    private static void arrive(PaxPerson person, int index) {
        switch (person.state) {
            case PaxPerson.TO_SLOT -> {
                person.state = PaxPerson.WAITING;
                if (person.slot >= 0) {
                    person.yaw = person.platform.slotYaw[person.slot] + (person.random.nextFloat() - 0.5f) * 40;
                }
                person.entity.updateTrackedPositionAndAngles(person.x, person.y, person.z, person.yaw, 0, 8);
                person.entity.updateTrackedHeadRotation(person.yaw, 8);
                person.delay = 40 + person.random.nextInt(80);
                if (person.intent && sessionDoorsOpen && person.platform.platformId == sessionPlatform) {
                    person.state = PaxPerson.QUEUED; // arrived while the doors are open
                    person.delay = 10 + bounded(mix(person.seed, sessionSeed), 20);
                }
            }
            case PaxPerson.TO_DOOR -> {
                person.state = PaxPerson.INTO_TRAIN;
                person.targetX = person.nextX;
                person.targetZ = person.nextZ;
                person.speed = Math.min(person.speed, WALK_MIN);
            }
            case PaxPerson.ALIGHT_OUT -> {
                person.state = PaxPerson.ALIGHT_BACK;
                person.targetX = person.nextX;
                person.targetZ = person.nextZ;
            }
            case PaxPerson.ALIGHT_BACK -> {
                person.state = PaxPerson.LEAVING;
                person.targetX = person.leaveX;
                person.targetZ = person.leaveZ;
            }
            default -> despawn(index); // INTO_TRAIN (boarded) or LEAVING (walked off)
        }
    }

    /** Waiting: an occasional look around. */
    private static void idle(PaxPerson person) {
        if (--person.delay > 0) {
            return;
        }
        person.delay = 40 + person.random.nextInt(120);
        float look = person.yaw + (person.random.nextFloat() - 0.5f) * 70;
        person.entity.updateTrackedHeadRotation(look, 10);
    }

    private static double jitter(Random random) {
        return (random.nextDouble() - 0.5) * 0.35;
    }

    /** SplitMix64 finaliser over two inputs: the shared-seed hash. */
    static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int bounded(long hash, int bound) {
        return (int) Math.floorMod(hash, (long) bound);
    }

    private static float unit(long hash) {
        return (hash >>> 40) / (float) (1 << 24);
    }

    private static double distance(Vector v, double x, double z) {
        double dx = v.x - x;
        double dz = v.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // --------------------------------------------------------------- cleanup

    /** Removes one person (swap-remove: callers iterate backwards). */
    private static void despawn(int index) {
        PaxPerson person = PEOPLE.get(index);
        if (person.slot >= 0) {
            person.platform.taken[person.slot] = false;
            person.slot = -1;
        }
        removeEntity(person);
        PaxPerson last = PEOPLE.remove(PEOPLE.size() - 1);
        if (index < PEOPLE.size()) {
            PEOPLE.set(index, last);
        }
    }

    private static void removeEntity(PaxPerson person) {
        ClientWorld current = MinecraftClient.getInstance().world;
        if (world != null && current == world) {
            world.removeEntity(person.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    /** Despawns everything (the world is still the live one). */
    private static void clear() {
        for (PaxPerson person : PEOPLE) {
            removeEntity(person);
        }
        forget();
    }

    /** Drops every reference without touching the world (disconnect, world gone). */
    private static void forget() {
        PEOPLE.clear();
        PLATFORMS.clear();
        resetSession();
        world = null;
        vehicleId = 0;
        ticks = 0;
    }
}
