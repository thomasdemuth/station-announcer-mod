package com.stationannouncer.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Trip;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to a siding's baked stop times: platform id → every {@code Trip.StopTime}
 * at that platform, whose {@code startTime}/{@code endTime} are millis AFTER the siding's
 * departure (the same numbers MTR's PIDS arrivals are built from). Written by
 * {@code Siding.generatePathDistancesAndTimeSegments} on the simulator thread; the
 * interline analyzer reads it on that same thread. Field verified with javap against
 * MTR FABRIC-4.0.1+1.20.4.
 */
@Mixin(value = Siding.class, remap = false)
public interface SidingStopTimesAccessor {
    @Accessor("platformTripStopTimes")
    Long2ObjectAVLTreeMap<ObjectArraySet<Trip.StopTime>> stationAnnouncer$getPlatformTripStopTimes();
}
