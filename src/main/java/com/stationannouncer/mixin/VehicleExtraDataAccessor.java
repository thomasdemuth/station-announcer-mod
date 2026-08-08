package com.stationannouncer.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>What:</b> exposes {@code VehicleExtraData.openDoors()} to the hold-rule code.
 *
 * <p><b>Why a mixin:</b> {@code openDoors()} is <em>protected</em> in
 * FABRIC-4.0.1+1.20.4 (javap-verified) — TSC's own {@code Vehicle} calls it from
 * the same {@code org.mtr.core.data} package, but our engine cannot. The hold
 * mixin must actively re-assert open doors while a train is held: MTR's stock
 * flow closes doors on the FIRST {@code startUp} attempt at doorCloseTime, so a
 * hold that engages after that point would otherwise sit with doors shut
 * (Thomas's in-game bug report).</p>
 *
 * <p><b>Thread:</b> invoked from {@link VehicleMixin} on the SIMULATOR thread,
 * inside the vehicle's own simulate call — the same context from which stock
 * {@code simulateStopped} mutates its own {@code VehicleExtraData}.</p>
 *
 * <p><b>Toggle:</b> only reached behind {@code holdRules.enabled} (the invoker
 * itself is inert glue with no injected behavior).</p>
 */
@Mixin(value = VehicleExtraData.class, remap = false)
public interface VehicleExtraDataAccessor {
    @Invoker("openDoors")
    void stationAnnouncer$openDoors();
}
