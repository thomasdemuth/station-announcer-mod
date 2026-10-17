package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side platform lists for the PA editor's Trains tab, from MTR's synced client
 * data: the platforms of the station a block stands in plus every platform close to it
 * (and to extra points — a control box's speakers), nearest first. The server decides
 * for real when a train comes; this is only what the editor shows.
 */
@Environment(EnvType.CLIENT)
public final class PaPlatformList {
    /** One platform as the editor lists it. */
    public record Option(long id, String label, int distance) {
    }

    private PaPlatformList() {
    }

    /**
     * Platforms within {@code radius} blocks of any of {@code points} (first point = the
     * block), plus the first point's station's platforms when {@code withStation}.
     */
    public static List<Option> near(List<BlockPos> points, int radius, boolean withStation) {
        Map<Long, Platform> found = new LinkedHashMap<>();
        if (points.isEmpty()) {
            return List.of();
        }
        try {
            if (withStation) {
                Station station = org.mtr.mod.InitClient.findStation(new org.mtr.mapping.holder.BlockPos(points.get(0)));
                if (station != null) {
                    station.savedRails.forEach(platform -> found.put(platform.getId(), platform));
                }
            }
            for (BlockPos point : points) {
                org.mtr.mod.InitClient.findClosePlatform(new org.mtr.mapping.holder.BlockPos(point), radius,
                        platform -> found.put(platform.getId(), platform));
            }
        } catch (Exception ignored) {
            // MTR data mid-sync: whatever was found so far
        }
        BlockPos origin = points.get(0);
        List<Option> out = new ArrayList<>();
        for (Platform platform : found.values()) {
            out.add(new Option(platform.getId(), label(platform), distance(origin, platform)));
        }
        out.sort(Comparator.comparingInt(Option::distance));
        return out;
    }

    /** "Albany · Platform 1", or "Platform #…" for an id the client does not know. */
    public static String label(long platformId) {
        try {
            Platform platform = org.mtr.mod.client.MinecraftClientData.getInstance().platformIdMap.get(platformId);
            if (platform != null) {
                return label(platform);
            }
        } catch (Exception ignored) {
            // not synced yet
        }
        return "Platform #" + Long.toHexString(platformId);
    }

    private static String label(Platform platform) {
        String name = RailroadRouteData.firstLang(platform.getName());
        String station = platform.area == null ? "" : RailroadRouteData.firstLang(platform.area.getName());
        String shortName = "Platform " + (name.isEmpty() ? "?" : name);
        return station.isEmpty() ? shortName : station + " · " + shortName;
    }

    private static int distance(BlockPos here, Platform platform) {
        try {
            Position mid = platform.getMidPosition();
            double dx = mid.getX() - here.getX();
            double dy = mid.getY() - here.getY();
            double dz = mid.getZ() - here.getZ();
            return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
