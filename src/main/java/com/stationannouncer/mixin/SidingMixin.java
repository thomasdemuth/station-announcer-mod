package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.DwellOverrideEngine;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> two non-cancelling HEAD hooks into path generation for Feature 2
 * (per-route dwell overrides at platforms), both delegating to
 * {@link DwellOverrideEngine}:
 * <ul>
 *   <li>{@code generateRoute(Platform, Platform, int, long)} (public,
 *       javap-verified against MTR FABRIC-4.0.1+1.20.4) — called once per siding
 *       by {@code Depot.tick()} immediately after the depot's shared main path
 *       finishes generating. At HEAD, {@code depot.getPath()} is complete and no
 *       siding has copied it or built timetables yet, so rewriting the baked
 *       {@code PathData.dwellTime} values here reaches every siding's
 *       {@code pathMainRoute}, the timetable AND runtime dwell consistently
 *       (the segments are shared object references). Idempotent — each of the
 *       depot's sidings re-applies the same rewrite harmlessly.</li>
 *   <li>{@code finishGeneratingPath(Z)V} (private, javap-verified) — called from
 *       the per-siding path finders' completion callbacks (never per tick). The
 *       depot main path carries no arrival segment for the FIRST stop of the
 *       route; that dwell is baked into the last segment of this siding's
 *       {@code pathSidingToMainRoute}, which exists only once this callback
 *       fires. Injecting at HEAD lands before the guarded
 *       {@code generatePathDistancesAndTimeSegments()} call in the method body
 *       builds the timetable.</li>
 * </ul>
 *
 * <p><b>Why a mixin:</b> TSC has no path-generation event, and dwell is baked
 * into final fields during generation — there is no later moment where changing
 * it would keep the precomputed timetable consistent with runtime behavior.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread when
 * {@code useThreadedSimulation} is off). The engine reads only volatile immutable
 * snapshots and the simulator's own data; it never touches the Minecraft world.</p>
 *
 * <p><b>Toggle:</b> {@code dwellOverrides.enabled} in
 * {@code config/station-announcer-addon.json}; when off (or when no overrides
 * exist) both hooks bail out in O(1) with zero allocation. These are cold paths
 * anyway — they run only when a depot regenerates.</p>
 */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingMixin {
    /** javap-verified: {@code private final ObjectArrayList<PathData> pathSidingToMainRoute} on 4.0.1 Siding. */
    @Shadow
    @Final
    private ObjectArrayList<PathData> pathSidingToMainRoute;

    @Inject(method = "generateRoute(Lorg/mtr/core/data/Platform;Lorg/mtr/core/data/Platform;IJ)V", at = @At("HEAD"))
    private void stationAnnouncer$applyDwellOverridesToDepotPath(Platform firstPlatform, Platform lastPlatform,
                                                                int stopIndex, long cruisingAltitude, CallbackInfo ci) {
        // area is SavedRailBase's public field; for a Siding it is the owning Depot (may be null).
        DwellOverrideEngine.applyToDepotPath(((Siding) (Object) this).area);
    }

    @Inject(method = "finishGeneratingPath(Z)V", at = @At("HEAD"))
    private void stationAnnouncer$applyDwellOverridesToApproachPath(boolean failed, CallbackInfo ci) {
        DwellOverrideEngine.applyToSidingApproachPath(((Siding) (Object) this).area, pathSidingToMainRoute);
    }
}
