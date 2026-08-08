package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.DoorObstructionEngine;
import com.stationannouncer.mtraddon.HoldRuleEngine;
import org.mtr.core.data.Data;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.generated.data.VehicleSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> HEAD-cancels {@link Vehicle#startUp(long, long)} — the single choke
 * point through which every automatic (and manual) train begins moving — when
 * {@link HoldRuleEngine} says the vehicle must be held at its platform because a
 * connecting train is approaching a watched platform (Feature 1, platform hold
 * rules) — or when {@link DoorObstructionEngine} rolled that something got stuck
 * in the doors (random door obstructions; the same engine is also bracketed
 * around {@code updateRidingEntities} for manual driving, see below).
 *
 * <p><b>Why a mixin:</b> TSC has no departure event or hold API; {@code startUp}
 * is called from deep inside {@code simulateStopped} with no extension point.
 * Every hold-cancel also re-asserts {@code openDoors()} (via
 * {@link VehicleExtraDataAccessor}) — cancelling before {@code closeDoors()} is
 * not sufficient on its own, because the doors already closed on the first
 * un-held startUp attempt if the hold engaged mid-close. The vehicle re-tries
 * {@code startUp} every simulation tick, so releasing the hold simply means
 * letting the next attempt through, which closes the doors and waits out the
 * door animation before moving.</p>
 *
 * <p><b>Thread:</b> the SIMULATOR thread for the vehicle's dimension (or the
 * server thread when {@code useThreadedSimulation} is off). The engine therefore
 * touches only volatile immutable snapshots, the simulator's own data, and
 * concurrent runtime maps — never the Minecraft world.</p>
 *
 * <p><b>Toggle:</b> {@code holdRules.enabled} in
 * {@code config/station-announcer-addon.json}; when off (or when no rules exist)
 * the injected code bails out in O(1) with zero allocation.</p>
 *
 * <p>Target verified with javap against MTR FABRIC-4.0.1+1.20.4:
 * {@code public void startUp(long, long)}; {@code vehicleExtraData} is a public
 * final field on {@code Vehicle}; {@code railProgress} and {@code data} are
 * protected fields inherited from {@code VehicleSchema} /
 * {@code NameColorDataBaseSchema} (hence the mixin extends {@code VehicleSchema});
 * {@code getId()} is public final on {@code NameColorDataBase}.</p>
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleMixin extends VehicleSchema {
    @Shadow
    @Final
    public VehicleExtraData vehicleExtraData;

    protected VehicleMixin(TransportMode transportMode, Data data) {
        super(transportMode, data);
    }

    @Inject(method = "startUp(JJ)V", at = @At("HEAD"), cancellable = true)
    private void stationAnnouncer$holdAtPlatform(long newDepartureIndex, long newSidingDepartureTime, CallbackInfo ci) {
        if (HoldRuleEngine.shouldHold(getId(), vehicleExtraData, railProgress, data)) {
            // Actively re-assert open doors for the held train. Cancelling alone is
            // not enough: stock flow already ran closeDoors() on the FIRST startUp
            // attempt (at doorCloseTime, ~4 s before real departure), so a hold that
            // engages after that point would otherwise sit with doors shut. This is
            // exactly what simulateStopped does during dwell — same thread, the
            // vehicle mutating its own VehicleExtraData — and it only happens on a
            // genuine platform-hold cancel (shouldHold's stopped-at-platform guard).
            // Bonus: reopening resets the door cooldown, so on release startUp
            // closes doors and waits the full door animation before moving. The
            // doorTarget flip reaches clients through the existing update flow
            // (writeVehiclePositions -> checkForUpdate -> client.update).
            ((VehicleExtraDataAccessor) (Object) vehicleExtraData).stationAnnouncer$openDoors();
            ci.cancel();
            return;
        }
        // Random door obstructions (toggle: doorObstruction.enabled). Checked only
        // when no hold is active, so the once-per-stop roll lands on the first
        // attempt that would genuinely have departed; the engine cancels + reopens
        // exactly like a mini-hold. Same thread and safety story as above.
        if (DoorObstructionEngine.shouldObstructDeparture(getId(), vehicleExtraData, railProgress, data)) {
            ci.cancel();
        }
    }

    /**
     * Door multiplier observed at the HEAD of {@code updateRidingEntities}, so the
     * TAIL can tell whether THIS call closed the doors (the driver's manual
     * door-close lands inside that method). Instance state is safe: each vehicle
     * is simulated by exactly one simulator thread.
     */
    @Unique
    private int stationAnnouncer$doorMultiplierBefore;

    /**
     * Manual-driving door obstructions (toggle: doorObstruction.enabled). The
     * driver's {@code manualToggleDoors} close is applied inside package-private
     * {@code updateRidingEntities(ObjectArrayList)V} (javap-verified against
     * 4.0.1) with no extension point, so we bracket it: HEAD records the door
     * state, TAIL hands the before/after pair to the engine, which — when a roll
     * says something got stuck — bounces the doors straight back open. Runs on
     * the SIMULATOR thread inside the vehicle's own simulate call; O(1) bail-out
     * in the engine when the feature is off.
     */
    @Inject(method = "updateRidingEntities(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V",
            at = @At("HEAD"))
    private void stationAnnouncer$captureDoorState(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities,
                                                   CallbackInfo ci) {
        stationAnnouncer$doorMultiplierBefore = vehicleExtraData.getDoorMultiplier();
    }

    @Inject(method = "updateRidingEntities(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V",
            at = @At("TAIL"))
    private void stationAnnouncer$bounceObstructedDoors(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities,
                                                        CallbackInfo ci) {
        DoorObstructionEngine.afterRidingUpdate(getId(), vehicleExtraData, railProgress, speed, data,
                stationAnnouncer$doorMultiplierBefore);
    }
}
