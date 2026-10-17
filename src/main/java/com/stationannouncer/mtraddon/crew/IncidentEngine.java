package com.stationannouncer.mtraddon.crew;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.VehicleExtraDataAccessor;
import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.core.data.VehicleExtraData;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random incidents for CREWED trains (MANUAL_DRIVING_PLAN.md phase 5; Thomas: "things that
 * might delay the train a bit, but not ruin gameplay (and the automatic schedule)"). Door
 * obstructions stay in {@code DoorObstructionEngine}; this adds:
 * <ul>
 *   <li><b>Door fault</b> — at a door close, one door will not lock: the doors bounce open
 *       until a crew member cuts it out (two presses of the door key), backstop 45 s;</li>
 *   <li><b>Held by control</b> — on arriving at a platform: power is held for 8–25 s;</li>
 *   <li><b>Sick passenger</b> — on arriving at a platform: the doors stay open and power is
 *       held for 30–60 s;</li>
 *   <li><b>Passenger alarm</b> — rarely while moving: the emergency brake is applied, released
 *       10 s after the train has stopped.</li>
 * </ul>
 * One incident at a time per train; each rolls at most once per stop. Only while a player
 * drives or crews ({@link CrewState} exists and someone is aboard). Simulator thread; all
 * times wall-clock (immune to Instant Deploy's clock rewind); every incident has a hard end.
 */
final class IncidentEngine {
    static final byte NONE = 0;
    static final byte DOOR_FAULT = 1;
    static final byte HELD = 2;
    static final byte SICK_PASSENGER = 3;
    static final byte ALARM = 4;

    private static final long DOOR_FAULT_MAX_MILLIS = 45_000;
    private static final long ALARM_RELEASE_MILLIS = 10_000;

    private IncidentEngine() {
    }

    private static boolean crewed(CrewState state) {
        return state.driverAboard || state.guardAboard;
    }

    private static boolean roll(int percent) {
        return percent > 0 && ThreadLocalRandom.current().nextInt(1000) < percent * 10;
    }

    private static long between(int minSeconds, int maxSeconds) {
        int lo = Math.max(1, Math.min(minSeconds, maxSeconds));
        int hi = Math.max(lo, Math.max(minSeconds, maxSeconds));
        return 1000L * ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private static void start(CrewState state, byte type, long until, long now) {
        state.incident = type;
        state.incidentStarted = now;
        state.incidentUntil = until;
        state.doorFaultPresses = 0;
        byte code = switch (type) {
            case DOOR_FAULT -> CrewProtocol.MSG_INCIDENT_DOOR_FAULT;
            case HELD -> CrewProtocol.MSG_INCIDENT_HELD;
            case SICK_PASSENGER -> CrewProtocol.MSG_INCIDENT_SICK;
            default -> CrewProtocol.MSG_INCIDENT_ALARM;
        };
        notifyCrew(state, code);
        StationAnnouncer.LOGGER.info("[incident] train {}: type {} for {} s", Long.toHexString(state.vehicleId), type,
                until > 0 ? (until - now) / 1000 : -1);
    }

    private static void end(CrewState state) {
        if (state.incident != NONE) {
            state.incident = NONE;
            state.incidentUntil = 0;
            notifyCrew(state, CrewProtocol.MSG_INCIDENT_CLEARED);
        }
    }

    private static void notifyCrew(CrewState state, byte code) {
        if (state.driver != null) {
            CrewService.NOTICES.add(new CrewService.Notice(state.driver, code));
        }
        if (state.guard != null) {
            CrewService.NOTICES.add(new CrewService.Notice(state.guard, code));
        }
    }

    /** The train came to a stand at a stop where the doors will open: maybe held / sick passenger. */
    static void onArrival(CrewState state, long now) {
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        if (!config.incidents || !crewed(state) || state.incident != NONE) {
            return;
        }
        if (roll(config.sickPassengerPercent)) {
            start(state, SICK_PASSENGER, now + between(config.sickPassengerMinSeconds, config.sickPassengerMaxSeconds), now);
        } else if (roll(config.heldPercent)) {
            start(state, HELD, now + between(config.heldMinSeconds, config.heldMaxSeconds), now);
        }
    }

    /** The doors were just commanded shut at a stop: maybe a door fault (rolled once per stop). */
    static void onDoorClose(CrewState state, VehicleExtraData extra, long now) {
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        if (!config.incidents || !crewed(state) || state.incident != NONE || state.doorFaultRolled) {
            return;
        }
        state.doorFaultRolled = true;
        if (roll(config.doorFaultPercent)) {
            start(state, DOOR_FAULT, now + DOOR_FAULT_MAX_MILLIS, now);
            ((VehicleExtraDataAccessor) (Object) extra).stationAnnouncer$openDoors();
        }
    }

    /** A crew member pressed the door key during a door fault: two presses cut the door out. */
    static boolean onCrewDoorKey(CrewState state) {
        if (state.incident != DOOR_FAULT) {
            return false;
        }
        if (++state.doorFaultPresses >= 2) {
            end(state);
            return false; // let this press close the doors
        }
        return true; // swallow the first press (it is the cut-out, not a close)
    }

    /** Per stopped tick: keep doors open / power held while an incident needs it; end on time. */
    static void tickStopped(CrewState state, VehicleExtraData extra, long now) {
        switch (state.incident) {
            case DOOR_FAULT, SICK_PASSENGER -> {
                if (now >= state.incidentUntil) {
                    end(state);
                } else if (extra.getDoorMultiplier() < 0 && state.doorsThisStop) {
                    ((VehicleExtraDataAccessor) (Object) extra).stationAnnouncer$openDoors();
                }
            }
            case HELD -> {
                if (now >= state.incidentUntil) {
                    end(state);
                }
            }
            case ALARM -> {
                if (state.incidentUntil == 0) {
                    state.incidentUntil = now + ALARM_RELEASE_MILLIS; // stopped: release in 10 s
                } else if (now >= state.incidentUntil) {
                    end(state);
                }
            }
            default -> {
            }
        }
    }

    /** Per moving tick: a rare passenger alarm (chance per hour of driving, rolled once a second). */
    static void tickMoving(CrewState state, long now) {
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        if (now - state.lastAlarmRoll < 1000) {
            return;
        }
        state.lastAlarmRoll = now;
        if (!config.incidents || !crewed(state) || state.incident != NONE || config.passengerAlarmPerHour <= 0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < config.passengerAlarmPerHour / 3600.0) {
            start(state, ALARM, 0, now);
        }
    }

    /** Power may not be taken while one of these runs (the alarm also forces the emergency brake). */
    static boolean holdsPower(CrewState state) {
        return state.incident == HELD || state.incident == SICK_PASSENGER || state.incident == DOOR_FAULT;
    }

    static boolean forcesEmergencyBrake(CrewState state) {
        return state.incident == ALARM;
    }
}
