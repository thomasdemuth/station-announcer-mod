package com.stationannouncer.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>What:</b> exposes {@code VehicleExtraData.setPowerLevel(int)} to the train-crew engine.
 *
 * <p><b>Why a mixin:</b> the setter is <em>protected</em> in FABRIC-4.0.1+1.20.4
 * (javap-verified); only TSC's own {@code Vehicle} calls it, from inside
 * {@code updateRidingEntities} (the driver's notch steps) and the blocked-track brake.
 * {@link com.stationannouncer.mtraddon.crew.CrewEngine} holds power at neutral while the
 * driver waits for the guard and applies the emergency brake when vigilance trips.</p>
 *
 * <p><b>Thread:</b> invoked from {@link VehicleCrewMixin} on the SIMULATOR thread, inside
 * the vehicle's own simulate / riding-update call. Separate from
 * {@link VehicleExtraDataAccessor} so the crew feature owns its own glue.</p>
 */
@Mixin(value = VehicleExtraData.class, remap = false)
public interface VehicleExtraDataCrewInvoker {
    @Invoker("setPowerLevel")
    void stationAnnouncer$crewSetPowerLevel(int powerLevel);
}
