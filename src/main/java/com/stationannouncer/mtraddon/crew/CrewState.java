package com.stationannouncer.mtraddon.crew;

import java.util.UUID;

/**
 * One vehicle's crew, shared between the server thread ({@link CrewService}: roles,
 * buzzes, acknowledgements) and the vehicle's simulator thread ({@link CrewEngine}:
 * who is really aboard, stop/door tracking, vigilance). Every field is volatile and
 * written by ONE side only, as marked. Times are wall-clock millis.
 */
final class CrewState {
    final long vehicleId;

    // ---- written on the server thread
    volatile UUID driver;
    volatile UUID guard;
    volatile long driverSeen;
    volatile long guardSeen;
    /** The driver's vigilance preference in seconds, 0 = off. */
    volatile int driverVigilance;
    /** Car 0's vehicle id as reported by a crew client; picks the horn. */
    volatile String trainType = "";
    volatile long lastDriverBuzz;
    volatile long lastGuardBuzz;
    volatile long lastVigilAck;
    /** The current driver has started the train up (reset whenever the driver changes). */
    volatile boolean startedUp;

    // ---- written on the simulator thread
    volatile boolean driverAboard;
    volatile boolean guardAboard;
    volatile boolean moving;
    /** The doors were opened at the current stop (the interlocks only apply then). */
    volatile boolean doorsThisStop;
    volatile long stopStart = System.currentTimeMillis();
    volatile long movingSince;
    /** When the doors were last commanded shut (open → closed target). */
    volatile long closeCommandAt;
    volatile int lastDoorMultiplier;
    /** Last accelerate / brake / doors / ATO input from the driver: any control input counts as vigilance. */
    volatile long lastDriverInput;
    volatile byte vigilanceState = CrewProtocol.VIGIL_OFF;
    volatile long vigilanceDeadline;
    volatile long trippedAt;
    /** The latest look-ahead + slot summary, and when it was built (wall clock). */
    volatile CabFeed feed;
    volatile long feedAt;
    /** Current incident ({@link IncidentEngine}), when it started and ends (0 = open-ended). */
    volatile byte incident;
    volatile long incidentStarted;
    volatile long incidentUntil;
    volatile int doorFaultPresses;
    volatile boolean doorFaultRolled;
    volatile long lastAlarmRoll;

    CrewState(long vehicleId) {
        this.vehicleId = vehicleId;
    }

    /** The guard buzzed after closing the doors at this stop. */
    boolean guardReady() {
        return !moving && doorsThisStop && lastDoorMultiplier < 0 && lastGuardBuzz > closeCommandAt;
    }

    /** The driver buzzed since the train came to a stand. */
    boolean driverReady() {
        return !moving && lastDriverBuzz > stopStart;
    }

    long lastBuzzFrom(UUID uuid) {
        return uuid.equals(driver) ? lastDriverBuzz : uuid.equals(guard) ? lastGuardBuzz : 0;
    }
}
