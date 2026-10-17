package com.stationannouncer.mtraddon.crew;

import com.stationannouncer.mixin.VehicleExtraDataCrewInvoker;
import com.stationannouncer.mtraddon.AddonServerConfig;
import org.mtr.core.data.Data;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.UUID;

/**
 * Crew rules enforced on the vehicle's SIMULATOR thread, called from
 * {@link com.stationannouncer.mixin.VehicleCrewMixin}:
 *
 * <ul>
 *   <li><b>Who is aboard.</b> A claimed role only counts while that player is in the
 *       vehicle's riding list ({@link CrewState#driverAboard} / {@link CrewState#guardAboard}).
 *       Driver without guard = OPTO: no interlocks, the driver works everything.</li>
 *   <li><b>Guard works the doors</b> ({@code crew.guardOwnsDoors}): with a guard aboard,
 *       the driver's door toggle is removed from the riding update before MTR applies it.</li>
 *   <li><b>Guard waits for the driver</b> ({@code crew.guardWaitsForDriver}): the guard's
 *       door CLOSE is removed until the driver has buzzed at this stop.</li>
 *   <li><b>Driver waits for the guard</b> ({@code crew.driverWaitsForGuard}): at a stop where
 *       the doors were opened, power is held at neutral until the guard buzzes after closing
 *       the doors. A stopped manual train only departs when power &gt; 0 (MTR's
 *       {@code simulateStopped} then calls {@code startUp}), so clamping power before that
 *       check is the whole interlock — no startUp cancel, no door re-opening.</li>
 *   <li><b>Vigilance</b> ({@code crew.vigilance}): while a driven manual train moves, any
 *       driver control input or the acknowledge key resets the timer; a warning runs for the
 *       last {@code vigilanceWarningSeconds}, then the emergency brake (EB) is applied and held
 *       until the train has stopped and the driver acknowledges.</li>
 * </ul>
 *
 * <p>Zero work when no vehicle has a crew: one {@code isEmpty()} on a concurrent map.
 * Clientside vehicles (the integrated client shares this JVM) are ignored by the
 * {@code Simulator} check.</p>
 */
public final class CrewEngine {
    private static final int EMERGENCY_BRAKE = -Vehicle.MAX_POWER_LEVEL - 1;
    private static final long FEED_MILLIS = 500;

    private CrewEngine() {
    }

    private static CrewState stateFor(long vehicleId, Data data) {
        if (CrewService.VEHICLES.isEmpty() || !(data instanceof Simulator) || !CrewService.enabled()) {
            return null;
        }
        return CrewService.VEHICLES.get(vehicleId);
    }

    /** HEAD of {@code updateRidingEntities}: notes driver activity and removes door inputs the rules forbid. */
    public static void filterInputs(long vehicleId, VehicleExtraData extra, Data data,
                                    ObjectArrayList<VehicleRidingEntity> entities) {
        CrewState state = stateFor(vehicleId, data);
        if (state == null) {
            return;
        }
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        long now = System.currentTimeMillis();
        for (int i = 0; i < entities.size(); i++) {
            VehicleRidingEntity entity = entities.get(i);
            boolean crew = entity.uuid.equals(state.driver) || entity.uuid.equals(state.guard);
            if (crew && entity.manualToggleDoors() && IncidentEngine.onCrewDoorKey(state)) {
                entities.set(i, withoutDoorToggle(entity)); // the press cut the faulty door out
                entity = entities.get(i);
            }
            if (entity.uuid.equals(state.driver)) {
                if (entity.manualAccelerate() || entity.manualBrake() || entity.manualToggleDoors() || entity.manualToggleAto()) {
                    state.lastDriverInput = now;
                }
                if (entity.manualToggleDoors() && config.guardOwnsDoors && state.guardAboard) {
                    entities.set(i, withoutDoorToggle(entity));
                    notice(state.driver, CrewProtocol.MSG_GUARD_HAS_DOORS);
                }
                if (entity.manualAccelerate()) {
                    if (needsStartup(state, extra, config)) {
                        notice(state.driver, CrewProtocol.MSG_STARTUP_NEEDED);
                    } else if (state.vigilanceState == CrewProtocol.VIGIL_TRIPPED) {
                        notice(state.driver, CrewProtocol.MSG_VIGIL_TRIPPED);
                    } else if (powerHeldForGuard(state, config)) {
                        notice(state.driver, CrewProtocol.MSG_WAIT_FOR_GUARD);
                    }
                }
            } else if (entity.uuid.equals(state.guard) && entity.manualToggleDoors()) {
                boolean closing = extra.getDoorMultiplier() > 0;
                if (closing && config.guardWaitsForDriver && state.driverAboard && !state.driverReady()) {
                    entities.set(i, withoutDoorToggle(entity));
                    notice(state.guard, CrewProtocol.MSG_WAIT_FOR_DRIVER);
                }
            }
        }
    }

    /** TAIL of {@code updateRidingEntities}: undo any power step the rules forbid. */
    public static void afterInputs(long vehicleId, VehicleExtraData extra, Data data) {
        CrewState state = stateFor(vehicleId, data);
        if (state != null) {
            clampPower(state, extra, AddonServerConfig.get().crew);
        }
    }

    /** HEAD of {@code simulateStopped}, every tick a vehicle stands still. */
    public static void tickStopped(long vehicleId, VehicleExtraData extra, double railProgress, double speed, Data data) {
        CrewState state = stateFor(vehicleId, data);
        if (state == null) {
            return;
        }
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        long now = System.currentTimeMillis();
        updateAboard(state, extra);
        if (state.moving) {
            state.moving = false;
            state.stopStart = now;
            state.doorsThisStop = false;
            state.doorFaultRolled = false;
        }
        int doors = extra.getDoorMultiplier();
        // The startup ritual's door test (before this driver has started up) is not a stop: no
        // incidents, and no "doors opened here" that would make the driver wait for a guard buzz.
        boolean doorTest = state.driverAboard && !state.startedUp;
        if (doors > 0 && !state.doorsThisStop && !doorTest) {
            state.doorsThisStop = true;
            IncidentEngine.onArrival(state, now); // doors opened at a stop: held / sick passenger
        }
        if (state.lastDoorMultiplier > 0 && doors < 0) {
            state.closeCommandAt = now;
            if (!doorTest) {
                IncidentEngine.onDoorClose(state, extra, now);
            }
        }
        state.lastDoorMultiplier = extra.getDoorMultiplier();
        IncidentEngine.tickStopped(state, extra, now);
        publishFeed(state, vehicleId, extra, railProgress, 0, data, now);

        if (state.vigilanceState == CrewProtocol.VIGIL_TRIPPED) {
            // Reset only by an acknowledgement given once the train is at a stand.
            if (state.lastVigilAck > Math.max(state.trippedAt, state.stopStart)) {
                state.vigilanceState = CrewProtocol.VIGIL_OK;
                notice(state.driver, CrewProtocol.MSG_VIGIL_RESET);
            }
        } else {
            state.vigilanceState = vigilanceSeconds(state, config) > 0 && state.driverAboard
                    ? CrewProtocol.VIGIL_OK : CrewProtocol.VIGIL_OFF;
        }
        clampPower(state, extra, config);
    }

    /** HEAD of {@code simulateInDepot}, every tick a vehicle waits in its siding: keeps "aboard" current there. */
    public static void tickInDepot(long vehicleId, VehicleExtraData extra, double railProgress, Data data) {
        CrewState state = stateFor(vehicleId, data);
        if (state == null) {
            return;
        }
        updateAboard(state, extra);
        publishFeed(state, vehicleId, extra, railProgress, 0, data, System.currentTimeMillis());
        state.moving = false;
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        if (state.vigilanceState != CrewProtocol.VIGIL_TRIPPED) {
            state.vigilanceState = vigilanceSeconds(state, config) > 0 && state.driverAboard
                    ? CrewProtocol.VIGIL_OK : CrewProtocol.VIGIL_OFF;
        }
        // Before MTR's own "manual and power > 0 → leave the siding" check: no startup, no power.
        clampPower(state, extra, config);
    }

    /** HEAD of {@code simulateMoving}, every tick a vehicle moves. */
    public static void tickMoving(long vehicleId, VehicleExtraData extra, double railProgress, double speed, Data data) {
        CrewState state = stateFor(vehicleId, data);
        if (state == null) {
            return;
        }
        AddonServerConfig.Crew config = AddonServerConfig.get().crew;
        long now = System.currentTimeMillis();
        updateAboard(state, extra);
        if (!state.moving) {
            state.moving = true;
            state.movingSince = now;
        }
        state.lastDoorMultiplier = extra.getDoorMultiplier();
        publishFeed(state, vehicleId, extra, railProgress, speed, data, now);
        IncidentEngine.tickMoving(state, now);
        if (IncidentEngine.forcesEmergencyBrake(state)) {
            clampPower(state, extra, config);
        }

        if (state.vigilanceState == CrewProtocol.VIGIL_TRIPPED) {
            clampPower(state, extra, config);
            return;
        }
        int seconds = vigilanceSeconds(state, config);
        if (seconds <= 0 || !state.driverAboard || !extra.getIsCurrentlyManual()) {
            state.vigilanceState = seconds > 0 && state.driverAboard ? CrewProtocol.VIGIL_OK : CrewProtocol.VIGIL_OFF;
            return;
        }
        long last = Math.max(state.movingSince, Math.max(state.lastDriverInput, state.lastVigilAck));
        long deadline = last + seconds * 1000L;
        long warning = Math.max(1, config.vigilanceWarningSeconds) * 1000L;
        if (now >= deadline) {
            state.vigilanceState = CrewProtocol.VIGIL_TRIPPED;
            state.trippedAt = now;
            clampPower(state, extra, config);
            notice(state.driver, CrewProtocol.MSG_VIGIL_TRIPPED);
        } else if (now >= deadline - warning) {
            state.vigilanceDeadline = deadline;
            state.vigilanceState = CrewProtocol.VIGIL_WARNING;
        } else {
            state.vigilanceState = CrewProtocol.VIGIL_OK;
        }
    }

    // ------------------------------------------------------------- internals

    /** Twice a second: the line ahead and the slot, for the crew's cab HUD (sent by CrewService). */
    private static void publishFeed(CrewState state, long vehicleId, VehicleExtraData extra, double railProgress,
                                    double speed, Data data, long wallNow) {
        if (wallNow - state.feedAt < FEED_MILLIS) {
            return;
        }
        state.feedAt = wallNow;
        state.feed = FeedBuilder.build(vehicleId, extra, railProgress, speed, data,
                SlotEngine.view(extra.getSidingId(), data.getCurrentMillis()));
    }

    private static void clampPower(CrewState state, VehicleExtraData extra, AddonServerConfig.Crew config) {
        int power = extra.getPowerLevel();
        if (state.vigilanceState == CrewProtocol.VIGIL_TRIPPED || IncidentEngine.forcesEmergencyBrake(state)) {
            if (power != EMERGENCY_BRAKE) {
                ((VehicleExtraDataCrewInvoker) (Object) extra).stationAnnouncer$crewSetPowerLevel(EMERGENCY_BRAKE);
            }
        } else if (power > 0 && (powerHeldForGuard(state, config) || needsStartup(state, extra, config)
                || IncidentEngine.holdsPower(state))) {
            ((VehicleExtraDataCrewInvoker) (Object) extra).stationAnnouncer$crewSetPowerLevel(0);
        }
    }

    /** A manual train whose current driver has not started it up yet may not take power. */
    private static boolean needsStartup(CrewState state, VehicleExtraData extra, AddonServerConfig.Crew config) {
        return config.startupRequired && state.driverAboard && !state.startedUp && extra.getIsManualAllowed();
    }

    private static boolean powerHeldForGuard(CrewState state, AddonServerConfig.Crew config) {
        return config.driverWaitsForGuard && state.driverAboard && state.guardAboard
                && !state.moving && state.doorsThisStop && !state.guardReady();
    }

    private static int vigilanceSeconds(CrewState state, AddonServerConfig.Crew config) {
        int seconds = switch (config.vigilance) {
            case "off" -> 0;
            case "required" -> state.driverVigilance > 0 ? state.driverVigilance : config.vigilanceDefaultSeconds;
            default -> state.driverVigilance;
        };
        return seconds <= 0 ? 0 : Math.max(CrewProtocol.MIN_VIGIL_SECONDS, Math.min(CrewProtocol.MAX_VIGIL_SECONDS, seconds));
    }

    private static void updateAboard(CrewState state, VehicleExtraData extra) {
        UUID driver = state.driver;
        UUID guard = state.guard;
        boolean[] aboard = new boolean[2];
        extra.iterateRidingEntities(entity -> {
            if (entity.uuid.equals(driver)) {
                aboard[0] = true;
            } else if (entity.uuid.equals(guard)) {
                aboard[1] = true;
            }
        });
        state.driverAboard = aboard[0];
        state.guardAboard = aboard[1];
    }

    /** The same riding update minus its door toggle (every other field copied as sent). */
    private static VehicleRidingEntity withoutDoorToggle(VehicleRidingEntity entity) {
        return new VehicleRidingEntity(entity.uuid, entity.getRidingCar(), entity.getX(), entity.getY(), entity.getZ(),
                entity.getIsOnGangway(), entity.isDriver(), entity.manualAccelerate(), entity.manualBrake(),
                false, entity.manualToggleAto(), entity.getDoorOverride());
    }

    private static void notice(UUID player, byte code) {
        if (player != null) {
            CrewService.NOTICES.add(new CrewService.Notice(player, code));
        }
    }
}
