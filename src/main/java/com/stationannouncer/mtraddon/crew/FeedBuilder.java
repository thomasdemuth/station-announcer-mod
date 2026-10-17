package com.stationannouncer.mtraddon.crew;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Station;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a crewed vehicle's FULL path on the simulator thread (the server holds all of
 * it; the client only gets a short window) and summarises the line ahead for the cab
 * HUD: the platform stops to come, the current and next speed limit, and the first
 * signal with a three-aspect reading from MTR's block state.
 *
 * <p><b>Aspect:</b> {@code PathData.isSignalBlocked(vehicleId, DO_NOT_RESERVE)} answers
 * "is this signal block held by another vehicle?" without reserving anything (javap
 * 4.0.1: {@code Rail.isBlocked} only reserves for PRE/CURRENTLY_RESERVE). The first
 * signalled block ahead held → Danger; else the block after it held → Caution; else
 * Clear. Consecutive signalled segments with the same colours are one block.</p>
 */
final class FeedBuilder {
    private static final double SIGNAL_LOOKAHEAD = 2_500;
    private static final double LIMIT_LOOKAHEAD = 3_000;

    private FeedBuilder() {
    }

    static CabFeed build(long vehicleId, VehicleExtraData extra, double railProgress, double speed, Data data,
                         CabFeed.Slot slot) {
        ObjectImmutableList<PathData> path = extra.immutablePath;
        List<CabFeed.Stop> stops = new ArrayList<>();
        if (path.isEmpty()) {
            return new CabFeed(vehicleId, stops, false, 0, 0, 0, (byte) -1, 0, slot);
        }
        long sidingId = extra.getSidingId();
        int head = Math.max(0, Utilities.getIndexFromConditionalList(path, railProgress));
        head = Math.min(head, path.size() - 1);

        // Standing at a platform (anywhere along it — a driver never stops on the exact end point).
        long standing = platformAt(path, railProgress, speed, sidingId);
        boolean atPlatform = standing != 0;
        if (atPlatform) {
            // Distance to the stopping mark (the platform segment's end): + short of it, − past it.
            stops.add(stop(data, standing, (float) (standingEnd(path, railProgress, standing) - railProgress)));
        }

        int limit = (int) path.get(head).getSpeedLimitKilometersPerHour();
        int nextLimit = 0;
        float nextLimitDistance = 0;
        byte aspect = -1;
        float signalDistance = 0;
        IntAVLTreeSet firstBlockColors = null;
        boolean firstBlockHeld = false;
        boolean secondBlockSeen = false;
        IntAVLTreeSet previousColors = null;

        for (int i = head; i < path.size(); i++) {
            PathData pathData = path.get(i);
            double startAhead = pathData.getStartDistance() - railProgress;
            double endAhead = pathData.getEndDistance() - railProgress;
            if (endAhead <= 0) {
                continue;
            }
            // Stops (skipping the one we stand at, already listed).
            if (isPlatform(pathData, sidingId) && stops.size() < CabFeed.MAX_STOPS
                    && !(atPlatform && pathData.getSavedRailBaseId() == standing && startAhead < 1)) {
                long platformId = pathData.getSavedRailBaseId();
                if (stops.isEmpty() || stops.get(stops.size() - 1).platformId() != platformId || startAhead > 1) {
                    stops.add(stop(data, platformId, (float) endAhead));
                }
            }
            // Next different limit.
            long segmentLimit = pathData.getSpeedLimitKilometersPerHour();
            if (nextLimit == 0 && i > head && startAhead <= LIMIT_LOOKAHEAD && segmentLimit > 0 && segmentLimit != limit) {
                nextLimit = (int) segmentLimit;
                nextLimitDistance = (float) Math.max(0, startAhead);
            }
            // Signals: the first signalled block ahead and the one after it.
            IntAVLTreeSet colors = pathData.getSignalColors();
            if (!secondBlockSeen && startAhead <= SIGNAL_LOOKAHEAD) {
                if (colors.isEmpty()) {
                    previousColors = null;
                } else if (i > head || startAhead > 0) {
                    boolean held = blocked(pathData, vehicleId);
                    if (firstBlockColors == null) {
                        firstBlockColors = colors;
                        signalDistance = (float) Math.max(0, startAhead);
                        firstBlockHeld = held;
                    } else if (previousColors != null && previousColors.equals(colors) && aspect == -1) {
                        firstBlockHeld |= held; // same block continues
                    } else if (aspect == -1) {
                        // Start of the second block: decide the first block's aspect now.
                        aspect = firstBlockHeld ? (byte) 2 : held ? (byte) 1 : (byte) 0;
                        secondBlockSeen = true;
                    }
                    previousColors = colors;
                }
            }
            if (stops.size() >= CabFeed.MAX_STOPS && (nextLimit != 0 || startAhead > LIMIT_LOOKAHEAD)
                    && (secondBlockSeen || startAhead > SIGNAL_LOOKAHEAD)) {
                break;
            }
        }
        if (aspect == -1 && firstBlockColors != null) {
            aspect = firstBlockHeld ? (byte) 2 : (byte) 0;
        }
        return new CabFeed(vehicleId, stops, atPlatform, limit, nextLimit, nextLimitDistance, aspect, signalDistance, slot);
    }

    /** Metres a train may stand past a platform's end and still count as "at" it (a slight overrun). */
    static final double PLATFORM_END_TOLERANCE = 3;

    /**
     * The platform a stopped train stands at, or 0: its head anywhere along a platform segment, up
     * to {@link #PLATFORM_END_TOLERANCE} past the end. MTR's railProgress is the train's head; a
     * manually driven train stops wherever the driver brakes, never exactly on the end point.
     */
    static long platformAt(java.util.List<PathData> path, double railProgress, double speed, long sidingId) {
        if (speed > 1E-6 || path.isEmpty()) {
            return 0;
        }
        int head = Math.min(path.size() - 1, Math.max(0, Utilities.getIndexFromConditionalList(path, railProgress)));
        for (int i = Math.max(0, head - 2); i <= Math.min(path.size() - 1, head + 1); i++) {
            PathData segment = path.get(i);
            if (isPlatform(segment, sidingId) && railProgress >= segment.getStartDistance() - 0.5
                    && railProgress <= segment.getEndDistance() + PLATFORM_END_TOLERANCE) {
                return segment.getSavedRailBaseId();
            }
        }
        return 0;
    }

    /** End distance of the platform segment the train stands at (see {@link #platformAt}). */
    private static double standingEnd(java.util.List<PathData> path, double railProgress, long platformId) {
        int head = Math.min(path.size() - 1, Math.max(0, Utilities.getIndexFromConditionalList(path, railProgress)));
        for (int i = Math.max(0, head - 2); i <= Math.min(path.size() - 1, head + 1); i++) {
            PathData segment = path.get(i);
            if (segment.getSavedRailBaseId() == platformId && railProgress >= segment.getStartDistance() - 0.5
                    && railProgress <= segment.getEndDistance() + PLATFORM_END_TOLERANCE) {
                return segment.getEndDistance();
            }
        }
        return railProgress;
    }

    static boolean isPlatform(PathData pathData, long sidingId) {
        return pathData.getDwellTime() > 0 && pathData.getSavedRailBaseId() != 0 && pathData.getSavedRailBaseId() != sidingId;
    }

    private static boolean blocked(PathData pathData, long vehicleId) {
        try {
            return pathData.isSignalBlocked(vehicleId, Rail.BlockReservation.DO_NOT_RESERVE);
        } catch (RuntimeException e) {
            return false; // rail cache not ready (just loaded / being edited)
        }
    }

    private static CabFeed.Stop stop(Data data, long platformId, float distance) {
        return new CabFeed.Stop(platformId, stationName(data, platformId), distance);
    }

    static String stationName(Data data, long platformId) {
        Platform platform = data.platformIdMap.get(platformId);
        if (platform == null) {
            return "?";
        }
        Station station = platform.area;
        String raw = station != null && !station.getName().isEmpty() ? station.getName() : platform.getName();
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }
}
