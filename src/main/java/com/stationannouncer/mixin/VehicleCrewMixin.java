package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.crew.CrewEngine;
import com.stationannouncer.mtraddon.crew.SlotEngine;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.generated.data.VehicleSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> the train-crew hooks (driver / guard interlocks, guard-owned doors,
 * vigilance — see {@link CrewEngine}). Kept out of {@link VehicleMixin} on purpose: that
 * class is shared by several features under active work; this one belongs to the crew
 * feature alone.
 *
 * <p><b>Where (javap-verified against MTR FABRIC-4.0.1+1.20.4):</b></p>
 * <ul>
 *   <li>package-private {@code updateRidingEntities(ObjectArrayList)V} — where MTR applies
 *       each rider's accelerate / brake / door / ATO input. HEAD filters the list (door
 *       toggles the rules forbid are replaced by copies without them); TAIL undoes a power
 *       step the rules forbid. {@link VehicleMixin} also injects HEAD/TAIL here (door
 *       obstructions); the two are independent in either order.</li>
 *   <li>private {@code simulateStopped(JLObjectArrayList;I)V} HEAD — stop and door tracking,
 *       and the neutral-power clamp, before MTR's own "power &gt; 0 → startUp" check.</li>
 *   <li>private {@code simulateMoving(JLObjectArrayList;I)V} HEAD — vigilance.</li>
 *   <li>private {@code simulateInDepot()V} HEAD — keeps "aboard" current while the train
 *       waits in its siding (a manual train sits there until its driver takes power).</li>
 * </ul>
 *
 * <p><b>Thread:</b> the vehicle's SIMULATOR thread. <b>Toggle:</b> {@code crew.enabled};
 * with no crew anywhere every hook returns after one {@code isEmpty()}.</p>
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleCrewMixin extends VehicleSchema {
    @Shadow
    @Final
    public VehicleExtraData vehicleExtraData;

    protected VehicleCrewMixin(TransportMode transportMode, Data data) {
        super(transportMode, data);
    }

    @Inject(method = "updateRidingEntities(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V",
            at = @At("HEAD"))
    private void stationAnnouncer$crewFilterInputs(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities,
                                                   CallbackInfo ci) {
        CrewEngine.filterInputs(getId(), vehicleExtraData, data, vehicleRidingEntities);
    }

    @Inject(method = "updateRidingEntities(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V",
            at = @At("TAIL"))
    private void stationAnnouncer$crewAfterInputs(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities,
                                                  CallbackInfo ci) {
        CrewEngine.afterInputs(getId(), vehicleExtraData, data);
    }

    @Inject(method = "simulateStopped(JLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;I)V",
            at = @At("HEAD"))
    private void stationAnnouncer$crewTickStopped(
            long millisElapsed,
            ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions,
            int currentIndex, CallbackInfo ci) {
        CrewEngine.tickStopped(getId(), vehicleExtraData, railProgress, speed, data);
    }

    @Inject(method = "simulateInDepot()V", at = @At("HEAD"))
    private void stationAnnouncer$crewTickInDepot(CallbackInfo ci) {
        CrewEngine.tickInDepot(getId(), vehicleExtraData, railProgress, data);
    }

    /**
     * Slot takeover H4: a manual train leaving its siding ({@code simulateInDepot} calls
     * {@code startUp(-1, data.getCurrentMillis())} when its driver takes power — javap 4.0.1, the
     * only startUp call there). When the train holds a timetable slot, MTR is told the slot's
     * virtual yard time instead, so its deviation is slot-relative and the automatic fallback after
     * the manual→automatic timeout chases the slot. Simulator thread (client vehicles never start
     * up from the depot themselves).
     */
    @ModifyArg(method = "simulateInDepot()V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;startUp(JJ)V"), index = 1)
    private long stationAnnouncer$slotYardTime(long sidingDepartureTime) {
        return SlotEngine.manualStart(getId(), vehicleExtraData.getSidingId(), sidingDepartureTime);
    }

    @Inject(method = "simulateMoving(JLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;I)V",
            at = @At("HEAD"))
    private void stationAnnouncer$crewTickMoving(
            long millisElapsed,
            ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions,
            int currentIndex, CallbackInfo ci) {
        CrewEngine.tickMoving(getId(), vehicleExtraData, railProgress, speed, data);
    }
}
