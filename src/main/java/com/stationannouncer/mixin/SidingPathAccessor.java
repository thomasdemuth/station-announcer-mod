package com.stationannouncer.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> read access to {@code Siding.pathMainRoute} — the baked main-route
 * path (javap-verified against MTR FABRIC-4.0.1+1.20.4:
 * {@code private final ObjectArrayList<PathData> pathMainRoute}, no getter).
 *
 * <p><b>Why:</b> Feature 5's route-cache re-apply must mirror what was actually
 * BAKED, and the path is the only ground truth for that. MTR's trip builder
 * ({@code Siding.generatePathDistancesAndTimeSegments}) walks the path's dwell
 * segments in lockstep with the depot's cached stop list, matching platform ids —
 * one disagreement and every stop after it silently loses its timetable entry
 * (empty PIDS for the rest of the route cycle). Verifying re-applied choices
 * against the path is what makes that drift impossible.</p>
 *
 * <p><b>Thread:</b> only used from {@link com.stationannouncer.mtraddon.PlatformGroupEngine}
 * inside depot generation / data-sync hooks — the per-dimension SIMULATOR thread,
 * reading that simulator's own data. READ ONLY: the list is never mutated.</p>
 */
@Mixin(value = Siding.class, remap = false)
public interface SidingPathAccessor {
    @Accessor("pathMainRoute")
    ObjectArrayList<PathData> stationAnnouncer$getPathMainRoute();
}
