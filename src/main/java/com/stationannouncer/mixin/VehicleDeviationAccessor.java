package com.stationannouncer.mixin;

import org.mtr.core.data.Vehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> read access to {@code Vehicle.deviation} ({@code private long},
 * javap-verified against MTR FABRIC-4.0.1+1.20.4) — the schedule deviation in
 * milliseconds, positive = late — for the dispatch stream's per-vehicle snapshot.
 *
 * <p><b>Why a mixin:</b> there is no getter in 4.0.1; the value is server/simulator-only
 * (client vehicles have {@code siding == null} and never compute it). Note MTR updates
 * it in {@code updateDeviation()} only when the vehicle stops, so the streamed value is
 * the deviation as of the last stop, not continuous — documented in the schema.</p>
 *
 * <p><b>Thread:</b> called exclusively on the owning dimension's SIMULATOR thread from
 * {@link com.stationannouncer.mtraddon.dispatch.DispatchSampler} (enqueued via
 * {@code simulator.run}) — the thread that writes the field.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled}; the sampler only runs while SSE clients
 * are connected.</p>
 */
@Mixin(value = Vehicle.class, remap = false)
public interface VehicleDeviationAccessor {
    @Accessor(value = "deviation", remap = false)
    long stationAnnouncer$getDeviation();
}
