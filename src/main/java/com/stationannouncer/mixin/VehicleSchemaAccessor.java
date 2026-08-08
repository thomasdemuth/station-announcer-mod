package com.stationannouncer.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> read access to three protected {@code VehicleSchema} fields
 * (javap-verified against MTR FABRIC-4.0.1+1.20.4: {@code protected double speed},
 * {@code protected double railProgress}, {@code protected long elapsedDwellTime}) for
 * the dispatch sampler's per-vehicle snapshot — speed (m/ms, converted to km/h),
 * position along the path (current rail + progress fraction) and dwell countdown.
 *
 * <p><b>Why a mixin:</b> 4.0.1 exposes no getters for these ({@code getIsOnRoute} and
 * friends exist, the raw motion fields do not). {@code VehicleMixin} reaches the same
 * fields by extending the schema class, but the sampler is not itself a mixin, so an
 * accessor interface is the clean read-only path.</p>
 *
 * <p><b>Thread:</b> called exclusively on the owning dimension's SIMULATOR thread from
 * {@link com.stationannouncer.mtraddon.dispatch.DispatchSampler} (enqueued via
 * {@code simulator.run}) — the thread that writes these fields.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled}; the sampler only runs while SSE clients
 * are connected.</p>
 */
@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleSchemaAccessor {
    @Accessor(value = "speed", remap = false)
    double stationAnnouncer$getSpeed();

    @Accessor(value = "railProgress", remap = false)
    double stationAnnouncer$getRailProgress();

    @Accessor(value = "elapsedDwellTime", remap = false)
    long stationAnnouncer$getElapsedDwellTime();
}
