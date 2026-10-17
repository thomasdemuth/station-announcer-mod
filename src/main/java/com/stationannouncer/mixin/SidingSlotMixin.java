package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.crew.SlotEngine;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timetable slot takeover hooks H1–H3 (see {@link SlotEngine} and SLOT_TAKEOVER_RESEARCH.md §0).
 * Targets javap-verified on MTR 4.0.1:
 * <ul>
 *   <li>{@code public void simulateTrain(J, ObjectArrayList)V} — its single
 *       {@code Vehicle.startUp(JJ)V} call is the deployment of a timetabled departure (H1
 *       redirect); its TAIL runs after the siding's vehicles moved and after it rebuilt
 *       {@code vehicleTimesAlongRoute} (H2). Instant Deploy's day replay passes a null positions
 *       list, which the engine treats as "replay: change nothing".</li>
 *   <li>{@code private void iterateArrivals(JJJJ, Siding$ArrivalConsumer)V} — the one place a
 *       siding lists its arrivals (boards, departures, OBA); cancelled for a manual siding while
 *       its train runs a slot (H3). The handler takes only the CallbackInfo because
 *       {@code Siding$ArrivalConsumer} is not accessible from here.</li>
 * </ul>
 * Simulator thread. With no claims every hook returns after one {@code isEmpty()}.
 */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingSlotMixin {
    @Redirect(method = "simulateTrain(JLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;startUp(JJ)V"))
    private void stationAnnouncer$slotHoldBack(Vehicle vehicle, long departureIndex, long sidingDepartureTime) {
        Siding siding = (Siding) (Object) this;
        Data data = ((NameColorDataAccessor) (Object) siding).stationAnnouncer$getData();
        if (!SlotEngine.suppressDeployment(siding, departureIndex, data)) {
            vehicle.startUp(departureIndex, sidingDepartureTime);
        }
    }

    @Inject(method = "simulateTrain(JLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V", at = @At("TAIL"))
    private void stationAnnouncer$slotAfterSimulate(
            long millisElapsed,
            ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions,
            CallbackInfo ci) {
        Siding siding = (Siding) (Object) this;
        SlotEngine.afterSimulateTrain(siding, ((NameColorDataAccessor) (Object) siding).stationAnnouncer$getData(),
                vehiclePositions == null);
    }

    @Inject(method = "iterateArrivals", at = @At("HEAD"), cancellable = true)
    private void stationAnnouncer$slotHideManualRows(CallbackInfo ci) {
        if (SlotEngine.hideManualRows((Siding) (Object) this)) {
            ci.cancel();
        }
    }
}
