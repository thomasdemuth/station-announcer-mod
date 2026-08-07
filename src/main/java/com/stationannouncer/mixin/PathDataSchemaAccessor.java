package com.stationannouncer.mixin;

import org.mtr.core.generated.data.PathDataSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> writable access to {@code PathDataSchema.dwellTime}
 * ({@code protected final long}, javap-verified against MTR FABRIC-4.0.1+1.20.4)
 * so {@link com.stationannouncer.mtraddon.DwellOverrideEngine} can rewrite a
 * freshly generated path segment's baked dwell time (Feature 2, per-route dwell
 * overrides). {@code @Mutable} strips the {@code final}.
 *
 * <p><b>Why a mixin:</b> {@code PathData} exposes only {@code getDwellTime()};
 * the field is final with no setter anywhere in TSC — dwell is designed to be
 * baked at path-generation time, which is exactly the moment we rewrite it.</p>
 *
 * <p><b>Thread:</b> invoked on the SIMULATOR thread from the two
 * {@link SidingMixin} injection points only, on path objects that are still
 * being assembled (no vehicle is running on them yet).</p>
 *
 * <p><b>Toggle:</b> {@code dwellOverrides.enabled} in
 * {@code config/station-announcer-addon.json} — the engine bails out before
 * ever calling this when the feature is off.</p>
 */
@Mixin(value = PathDataSchema.class, remap = false)
public interface PathDataSchemaAccessor {
    @Mutable
    @Accessor(value = "dwellTime", remap = false)
    void stationAnnouncer$setDwellTime(long dwellTime);
}
