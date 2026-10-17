package com.stationannouncer.client.mtraddon.cab;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.Random;

/** One passenger's walk state (plain mutable data; driven by {@link CabPassengers}' tick). */
@Environment(EnvType.CLIENT)
final class PaxPerson {
    /** Standing at its slot, facing the track. */
    static final int WAITING = 0;
    /** Walking to its waiting slot (arriving, or sent back after the doors closed). */
    static final int TO_SLOT = 1;
    /** Doors are open; boards when {@link #delay} runs out. */
    static final int QUEUED = 2;
    /** Walking to the nearest doorway. */
    static final int TO_DOOR = 3;
    /** Stepping through the doorway into the car; despawns on arrival (= boarded). */
    static final int INTO_TRAIN = 4;
    /** Alighting: stepping out of the doorway onto the platform. */
    static final int ALIGHT_OUT = 5;
    /** Alighting: walking to the back of the platform. */
    static final int ALIGHT_BACK = 6;
    /** Walking off along the platform; despawns on arrival. */
    static final int LEAVING = 7;

    final PaxEntity entity;
    final PaxPlatform platform;
    /** Shared-seed identity: the same person (skin, speed, intent, timing) on every crew client. */
    final long seed;
    /** This person's own random stream (positions jitter, idle looks), seeded from {@link #seed}. */
    final Random random;
    int state;
    double x;
    double y;
    double z;
    double targetX;
    double targetZ;
    /** Second leg after {@link #targetX}/{@link #targetZ} (doorway → inside the car, back of the platform → off). */
    double nextX;
    double nextZ;
    /** Third leg for alighting passengers: where they walk off along the platform. */
    double leaveX;
    double leaveZ;
    /** Blocks per second. */
    double speed;
    /** Reserved waiting slot, or -1. */
    int slot = -1;
    /** Wants to board the next train here. */
    boolean intent;
    /** Ticks until boarding starts ({@link #QUEUED}) or the next idle head turn ({@link #WAITING}). */
    int delay;
    float yaw;

    PaxPerson(PaxEntity entity, PaxPlatform platform, long seed, double x, double y, double z, float yaw) {
        this.entity = entity;
        this.platform = platform;
        this.seed = seed;
        random = new Random(seed);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    boolean waitingForTrain() {
        return intent && (state == WAITING || state == TO_SLOT || state == QUEUED || state == TO_DOOR);
    }
}
