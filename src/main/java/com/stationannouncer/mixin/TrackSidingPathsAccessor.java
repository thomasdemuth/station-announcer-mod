package com.stationannouncer.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> read access to a siding's two private path legs (javap-verified against MTR
 * FABRIC-4.0.1+1.20.4: {@code private final ObjectArrayList<PathData> pathSidingToMainRoute} and
 * {@code pathMainRouteToSiding}, no getters).
 *
 * <p><b>Why:</b> track geometry — when a rail's custom profile changes its LENGTH, every path that
 * crosses the rail carries stale cached distances; {@code com.stationannouncer.mtr.track.TrackService}
 * regenerates the depots whose main route ({@code Depot.getPath()}) or whose sidings' legs contain it.</p>
 *
 * <p><b>Thread:</b> the simulator thread only (inside {@code simulator.run}). READ ONLY.</p>
 */
@Mixin(value = Siding.class, remap = false)
public interface TrackSidingPathsAccessor {
    @Accessor("pathSidingToMainRoute")
    ObjectArrayList<PathData> stationannouncer$getPathSidingToMainRoute();

    @Accessor("pathMainRouteToSiding")
    ObjectArrayList<PathData> stationannouncer$getPathMainRouteToSiding();
}
