package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.pa.PaText;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side {@code {station}} lookup for PA announcements. MTR's stations live
 * on the simulator threads, so an announcement firing on the server thread
 * cannot ask them directly; instead every simulator publishes a small immutable
 * snapshot of station rectangles here every ten seconds, and the lookup is a
 * plain scan of that snapshot (smallest containing station wins).
 */
public final class PaStationNames {
    private static final int REFRESH_TICKS = 200;

    private record Area(String name, int minX, int maxX, int minZ, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        long size() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    /** Dimension id (MTR's world id) → station rectangles. */
    private static final Map<String, List<Area>> AREAS = new ConcurrentHashMap<>();
    private static int ticks;

    private PaStationNames() {
    }

    public static void register() {
        PaText.serverStation = PaStationNames::lookup;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--ticks <= 0) {
                ticks = REFRESH_TICKS;
                refresh();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            AREAS.clear();
            ticks = 0;
        });
    }

    private static void refresh() {
        var simulators = MtrSimulators.get();
        if (simulators == null) {
            return;
        }
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    List<Area> areas = new ArrayList<>();
                    for (Station station : simulator.stations) {
                        if (station != null) {
                            areas.add(new Area(FareLane.firstLang(station.getName()),
                                    (int) station.getMinX(), (int) station.getMaxX(),
                                    (int) station.getMinZ(), (int) station.getMaxZ()));
                        }
                    }
                    AREAS.put(simulator.dimension, List.copyOf(areas));
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.debug("PA: station snapshot failed", t);
                }
            });
        }
    }

    private static String lookup(World world, BlockPos pos) {
        String dimension;
        try {
            dimension = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return null;
        }
        List<Area> areas = AREAS.get(dimension);
        if (areas == null) {
            return null;
        }
        Area best = null;
        for (Area area : areas) {
            if (area.contains(pos.getX(), pos.getZ()) && (best == null || area.size() < best.size())) {
                best = area;
            }
        }
        return best == null ? null : best.name();
    }
}
