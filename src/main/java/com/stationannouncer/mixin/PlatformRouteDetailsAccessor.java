package com.stationannouncer.mixin;

import org.mtr.core.data.Platform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> accessor mixin for Feature 5 (dynamic platform selection) onto
 * MTR's package-private {@code Depot$PlatformRouteDetails} — the element type of
 * the depot's private {@code platformsInRoute} list. Exposes get/set of its
 * {@code private final Platform platform} field ({@code @Mutable} strips the
 * final) so {@link com.stationannouncer.mtraddon.PlatformGroupEngine} can swap a
 * group stop's platform in the depot's generation input without reconstructing
 * the object (its constructor is private and its {@code route}/{@code
 * platformIndex} fields must stay untouched).
 *
 * <p><b>Why a mixin:</b> the class, its fields and its constructor are all
 * inaccessible (javap-verified against MTR FABRIC-4.0.1+1.20.4:
 * {@code class Depot$PlatformRouteDetails} with
 * {@code private final Platform platform}, {@code private final Route route},
 * {@code private final int platformIndex}); TSC offers no API to influence which
 * platform a depot generates toward.</p>
 *
 * <p><b>Thread:</b> only ever used from {@link DepotMixin}'s hooks, i.e. the
 * per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off).</p>
 *
 * <p><b>Toggle:</b> {@code dynamicPlatforms.enabled} in
 * {@code config/station-announcer-addon.json} — gated in the engine before any
 * accessor call.</p>
 */
@Mixin(targets = "org.mtr.core.data.Depot$PlatformRouteDetails", remap = false)
public interface PlatformRouteDetailsAccessor {
    @Accessor("platform")
    Platform stationAnnouncer$getPlatform();

    @Mutable
    @Accessor("platform")
    void stationAnnouncer$setPlatform(Platform platform);
}
