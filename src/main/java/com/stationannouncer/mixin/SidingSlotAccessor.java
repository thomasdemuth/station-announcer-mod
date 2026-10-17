package com.stationannouncer.mixin;

import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Timetable-slot takeover glue (SLOT_TAKEOVER_RESEARCH.md §0), all javap-verified on 4.0.1:
 * {@code private final LongArrayList departures} (raw yard-departure times per index),
 * {@code private final Long2LongAVLTreeMap vehicleTimesAlongRoute} (departure index → time
 * along the route; the ONLY link between a vehicle and its PIDS rows, cleared at the HEAD of
 * every {@code simulateTrain}), and package-private {@code long getRepeatInterval(long)}.
 * Read and written on the siding's SIMULATOR thread only.
 */
@Mixin(value = Siding.class, remap = false)
public interface SidingSlotAccessor {
    @Accessor("departures")
    LongArrayList stationAnnouncer$getDepartures();

    @Accessor("vehicleTimesAlongRoute")
    Long2LongAVLTreeMap stationAnnouncer$getVehicleTimesAlongRoute();

    @Invoker("getRepeatInterval")
    long stationAnnouncer$getRepeatInterval(long defaultAmount);
}
