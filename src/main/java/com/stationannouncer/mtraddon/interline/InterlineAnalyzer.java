package com.stationannouncer.mtraddon.interline;

import com.stationannouncer.mixin.SidingStopTimesAccessor;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonSnapshots;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.DepotGroup;
import com.stationannouncer.mtraddon.DepotGroupEngine;
import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import com.stationannouncer.mtraddon.analytics.AnalyticsEvent;
import com.stationannouncer.mtraddon.analytics.AnalyticsRecorder;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.data.Trip;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link InterlineModel.Analysis} for one dimension. Runs on that dimension's
 * SIMULATOR thread (it reads routes, depots, sidings and their baked stop times, all
 * owned by that thread); everything it returns is plain immutable data.
 *
 * <p><b>Sections.</b> For every pair of routes, every maximal run of consecutive platforms
 * they share in the same order is found; runs with the same platform sequence merge into
 * one section served by the union of their routes. Platforms are directional, so each
 * direction is its own section; the opposite direction is paired by its reversed station
 * sequence. Two lines that share track but stop at different platforms (express vs local)
 * are not a section — a deliberate definition (INTERLINE_PLAN.md).</p>
 *
 * <p><b>Travel times.</b> For each depot feeding a section, the time from its departure to
 * arriving at the entry platform comes from MTR's own per-siding stop times
 * ({@link SidingStopTimesAccessor}) — the numbers PIDS arrivals are made of, so dwell
 * overrides and speed limits are already in them. Sidings differ slightly (a siding
 * further from the main line reaches it later): the median is used and the spread
 * reported.</p>
 */
public final class InterlineAnalyzer {
    /** Measured headways longer than this are service breaks, not headways. */
    private static final long MEASURED_BREAK_MILLIS = 30 * 60_000L;

    private InterlineAnalyzer() {
    }

    public static InterlineModel.Analysis analyze(Simulator simulator) {
        AddonServerConfig.DepotGroups config = AddonServerConfig.get().depotGroups;
        long day = simulator.getGameMillisPerDay();
        boolean timeMoving = simulator.isTimeMoving();
        int hour = simulator.getHour();

        // ---- routes
        Map<Long, InterlineModel.Route> routes = new LinkedHashMap<>();
        for (Route route : simulator.routes) {
            if (route == null) {
                continue;
            }
            List<RoutePlatformData> stops = route.getRoutePlatforms();
            long[] platforms = new long[stops.size()];
            long[] stations = new long[stops.size()];
            String[] names = new String[stops.size()];
            for (int i = 0; i < stops.size(); i++) {
                Platform platform = stops.get(i).platform;
                platforms[i] = platform == null ? 0 : platform.getId();
                Station station = platform == null ? null : platform.area;
                stations[i] = station == null ? 0 : station.getId();
                names[i] = station == null ? "?" : AnalyticsAggregator.displayName(station.getName());
            }
            routes.put(route.getId(), new InterlineModel.Route(route.getId(),
                    AnalyticsAggregator.displayName(route.getName()), route.getRouteNumber(), route.getColor(),
                    platforms, stations, names));
        }

        // ---- groups
        List<InterlineModel.Group> groups = new ArrayList<>();
        Map<Long, List<Long>> groupsByDepot = new HashMap<>();
        for (DepotGroup group : AddonStore.depotGroupsView().values()) {
            groups.add(new InterlineModel.Group(group.id(), group.name(), group.depotIds().clone()));
            for (long depotId : group.depotIds()) {
                groupsByDepot.computeIfAbsent(depotId, key -> new ArrayList<>()).add(group.id());
            }
        }

        // ---- depots
        Map<Long, InterlineModel.Depot> depots = new LinkedHashMap<>();
        Map<Long, List<Long>> depotsByRoute = new HashMap<>();
        var delays = AddonSnapshots.depotDelays();
        for (Depot depot : simulator.depots) {
            if (depot == null) {
                continue;
            }
            List<Long> routeIds = new ArrayList<>();
            for (Route route : depot.routes) {
                if (route != null) {
                    routeIds.add(route.getId());
                    List<Long> list = depotsByRoute.computeIfAbsent(route.getId(), key -> new ArrayList<>());
                    if (!list.contains(depot.getId())) {
                        list.add(depot.getId());
                    }
                }
            }
            long[] raw = new long[Timetable.HOURS];
            long[] effective = new long[Timetable.HOURS];
            for (int i = 0; i < Timetable.HOURS; i++) {
                raw[i] = depot.getFrequency(i);
                effective[i] = depot.getFrequency(timeMoving ? i : hour);
            }
            long interval = AnalyticsRecorder.depotDepartureIntervalMillis(simulator, depot);
            String reason = "";
            if (depot.getUseRealTime()) {
                reason = "real-time timetable";
            } else if (depot.getTransportMode().continuousMovement) {
                reason = "continuous movement";
            } else if (interval <= 0) {
                reason = "no frequency set";
            }
            List<Long> groupIds = groupsByDepot.getOrDefault(depot.getId(), List.of());
            List<Long> merged = new ArrayList<>();
            for (int i = 1; i < routeIds.size(); i++) {
                InterlineModel.Route previous = routes.get(routeIds.get(i - 1));
                InterlineModel.Route current = routes.get(routeIds.get(i));
                if (previous != null && current != null && previous.platformIds().length > 0 && current.platformIds().length > 0
                        && previous.platformIds()[previous.platformIds().length - 1] == current.platformIds()[0]) {
                    merged.add(current.id());
                }
            }
            depots.put(depot.getId(), new InterlineModel.Depot(depot.getId(),
                    AnalyticsAggregator.displayName(depot.getName()), depot.getColor(),
                    toArray(routeIds), raw, effective, interval, delays.get(depot.getId()),
                    DepotGroupEngine.lastApplied(depot.getId()), reason.isEmpty(), reason,
                    depot.savedRails.size(), toArray(groupIds), toArray(merged)));
        }

        // ---- sections
        Map<String, RunInfo> runs = detectRuns(routes, Math.max(1, config.minSharedStops));
        Map<Long, Depot> depotObjects = new HashMap<>();
        for (Depot depot : simulator.depots) {
            if (depot != null) {
                depotObjects.put(depot.getId(), depot);
            }
        }
        var dwellOverrides = AddonSnapshots.dwellOverrides();
        AnalyticsAggregator.Aggregate aggregate = AnalyticsAggregator.get(simulator.dimension);

        List<InterlineModel.Section> built = new ArrayList<>();
        Map<Long, long[]> naturalDepartures = new HashMap<>();
        for (RunInfo run : runs.values()) {
            long[] platforms = run.platforms;
            long[] routeIds = toArray(new ArrayList<>(run.routes));
            InterlineModel.Route first = routes.get(routeIds[0]);
            int firstIndex = indexOfRun(first.platformIds(), platforms);
            long[] stationIds = Arrays.copyOfRange(first.stationIds(), firstIndex, firstIndex + platforms.length);
            String[] stationNames = Arrays.copyOfRange(first.stationNames(), firstIndex, firstIndex + platforms.length);

            List<InterlineModel.Feed> feeds = new ArrayList<>();
            List<InterlineModel.PrevStop> prevStops = new ArrayList<>();
            List<InterlineModel.HoldSite> holds = new ArrayList<>();
            for (long routeId : routeIds) {
                InterlineModel.Route route = routes.get(routeId);
                int entryIndex = indexOfRun(route.platformIds(), platforms);
                if (entryIndex < 0) {
                    continue;
                }
                if (entryIndex > 0) {
                    long prevPlatformId = route.platformIds()[entryIndex - 1];
                    Platform prevPlatform = simulator.platformIdMap.get(prevPlatformId);
                    if (prevPlatform != null) {
                        Long2LongAVLTreeMap byRoute = dwellOverrides.get(prevPlatformId);
                        boolean overridden = byRoute != null && byRoute.containsKey(routeId);
                        long dwell = overridden ? byRoute.get(routeId) : prevPlatform.getDwellTime();
                        prevStops.add(new InterlineModel.PrevStop(routeId, prevPlatformId,
                                route.stationNames()[entryIndex - 1], dwell, overridden, entryIndex - 1));
                    }
                }
                for (long depotId : depotsByRoute.getOrDefault(routeId, List.of())) {
                    Depot depot = depotObjects.get(depotId);
                    InterlineModel.Depot info = depots.get(depotId);
                    if (depot == null || info == null) {
                        continue;
                    }
                    long[] travel = stopTime(depot, routeId, platforms[0], entryIndex, false);
                    long[] arr = new long[platforms.length];
                    long[] dep = new long[platforms.length];
                    for (int k = 0; k < platforms.length; k++) {
                        long[] a = k == 0 ? travel : stopTime(depot, routeId, platforms[k], entryIndex + k, false);
                        long[] d = stopTime(depot, routeId, platforms[k], entryIndex + k, true);
                        arr[k] = a == null ? -1 : a[0];
                        dep[k] = d == null ? -1 : d[0];
                    }
                    long[] approach = entryIndex > 0
                            ? stopTime(depot, routeId, route.platformIds()[entryIndex - 1], entryIndex - 1, true) : null;
                    feeds.add(new InterlineModel.Feed(depotId, routeId,
                            travel == null ? -1 : travel[0], travel == null ? 0 : travel[1],
                            info.routePosition(routeId), entryIndex, arr, dep, approach == null ? -1 : approach[0]));
                    // Where holding this depot's trains longer moves them into the section:
                    // the stop before, the terminal where the previous route of its cycle
                    // turns back, and this route's own first stop.
                    // Where this route starts at the platform the previous route of the
                    // cycle ends at, MTR merges the two into ONE stop whose dwell belongs to
                    // the earlier route (DwellOverrideEngine: primary + secondary route) —
                    // found on the rig 2026-09-30, where two "separate" holds at the same
                    // Sahara platform fought and the later one never took effect. Such a
                    // stop is offered once, as the earlier route's turnaround.
                    int position = info.routePosition(routeId);
                    InterlineModel.Route previous = position > 0 ? routes.get(info.routeIds()[position - 1]) : null;
                    boolean shared = previous != null && previous.platformIds().length > 0
                            && route.platformIds().length > 0
                            && previous.platformIds()[previous.platformIds().length - 1] == route.platformIds()[0];
                    if (!(shared && entryIndex - 1 == 0)) {
                        addHold(holds, simulator, dwellOverrides, routes, routeId, entryIndex - 1, "before", routeId, 0);
                    }
                    if (previous != null && previous.platformIds().length > 0) {
                        addHold(holds, simulator, dwellOverrides, routes, previous.id(), previous.platformIds().length - 1,
                                shared && entryIndex - 1 == 0 ? "before" : "turnaround", routeId, shared ? routeId : 0);
                    }
                    if (entryIndex >= 2 && !shared) {
                        addHold(holds, simulator, dwellOverrides, routes, routeId, 0, "origin", routeId, 0);
                    }
                }
            }

            // Scheduled combined headway with today's settings.
            Map<Long, Long> delayMap = new HashMap<>();
            List<InterlineModel.Feed> timed = new ArrayList<>();
            for (InterlineModel.Feed feed : feeds) {
                InterlineModel.Depot depot = depots.get(feed.depotId());
                if (feed.travelMs() < 0 || depot == null || !depot.tunable()) {
                    continue;
                }
                timed.add(feed);
                naturalDepartures.computeIfAbsent(depot.id(), id -> Timetable.departures(depot.effective(), day));
                delayMap.put(depot.id(), depot.delayMs());
            }
            InterlineModel.Stats scheduled = Timetable.stats(Timetable.arrivals(timed, naturalDepartures, delayMap,
                    Map.of(), depots, day), day);

            built.add(new InterlineModel.Section(sectionId(platforms), sectionName(stationNames), "",
                    routeIds, platforms, stationIds, stationNames, List.copyOf(feeds), List.copyOf(prevStops),
                    List.copyOf(holds), scheduled, measured(aggregate, platforms[0], routeIds)));
        }

        // Largest (most lines, then longest) first; cap.
        built.sort(Comparator.<InterlineModel.Section>comparingInt(section -> -section.routeIds().length)
                .thenComparingInt(section -> -section.platformIds().length)
                .thenComparing(InterlineModel.Section::name));
        if (built.size() > config.maxSections) {
            built = new ArrayList<>(built.subList(0, config.maxSections));
        }

        // Pair opposite directions by reversed station sequence.
        Map<String, String> byStations = new HashMap<>();
        for (InterlineModel.Section section : built) {
            byStations.putIfAbsent(Arrays.toString(section.stationIds()), section.id());
        }
        List<InterlineModel.Section> sections = new ArrayList<>(built.size());
        Map<String, InterlineModel.Section> byId = new LinkedHashMap<>();
        for (InterlineModel.Section section : built) {
            long[] reversed = section.stationIds().clone();
            reverse(reversed);
            boolean known = true;
            for (long stationId : reversed) {
                known &= stationId != 0;
            }
            String reverseId = known ? byStations.getOrDefault(Arrays.toString(reversed), "") : "";
            if (reverseId.equals(section.id())) {
                reverseId = "";
            }
            InterlineModel.Section paired = new InterlineModel.Section(section.id(), section.name(), reverseId,
                    section.routeIds(), section.platformIds(), section.stationIds(), section.stationNames(),
                    section.feeds(), section.prevStops(), section.holds(), section.scheduled(), section.measured());
            sections.add(paired);
            byId.put(paired.id(), paired);
        }

        return new InterlineModel.Analysis(simulator.dimension, day, System.currentTimeMillis(), timeMoving,
                config.maxFrequency, config.maxDwellPadSeconds * 1000L, Map.copyOf(depots), Map.copyOf(routes),
                List.copyOf(groups), List.copyOf(sections), byId);
    }

    // ---------------------------------------------------------------- sections

    private static final class RunInfo {
        final long[] platforms;
        final Set<Long> routes = new LinkedHashSet<>();

        RunInfo(long[] platforms) {
            this.platforms = platforms;
        }
    }

    /** Every maximal shared run between every pair of routes, merged by platform sequence. */
    static Map<String, RunInfo> detectRuns(Map<Long, InterlineModel.Route> routes, int minShared) {
        List<InterlineModel.Route> list = new ArrayList<>();
        for (InterlineModel.Route route : routes.values()) {
            if (route.platformIds().length >= minShared) {
                list.add(route);
            }
        }
        Map<String, RunInfo> runs = new LinkedHashMap<>();
        for (int a = 0; a < list.size(); a++) {
            long[] pa = list.get(a).platformIds();
            for (int b = a + 1; b < list.size(); b++) {
                long[] pb = list.get(b).platformIds();
                for (int i = 0; i < pa.length; i++) {
                    if (pa[i] == 0) {
                        continue;
                    }
                    for (int j = 0; j < pb.length; j++) {
                        if (pa[i] != pb[j] || (i > 0 && j > 0 && pa[i - 1] == pb[j - 1])) {
                            continue; // not a match, or not the START of a run
                        }
                        int length = 0;
                        while (i + length < pa.length && j + length < pb.length
                                && pa[i + length] == pb[j + length] && pa[i + length] != 0) {
                            length++;
                        }
                        if (length < minShared) {
                            continue;
                        }
                        long[] platforms = Arrays.copyOfRange(pa, i, i + length);
                        RunInfo run = runs.computeIfAbsent(Arrays.toString(platforms), key -> new RunInfo(platforms));
                        run.routes.add(list.get(a).id());
                        run.routes.add(list.get(b).id());
                    }
                }
            }
        }
        return runs;
    }

    static int indexOfRun(long[] haystack, long[] run) {
        outer:
        for (int i = 0; i + run.length <= haystack.length; i++) {
            for (int k = 0; k < run.length; k++) {
                if (haystack[i + k] != run[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Stable id from the platform sequence (FNV-1a over the ids). */
    static String sectionId(long[] platforms) {
        long hash = 0xcbf29ce484222325L;
        for (long id : platforms) {
            for (int shift = 0; shift < 64; shift += 8) {
                hash ^= (id >>> shift) & 0xFF;
                hash *= 0x100000001b3L;
            }
        }
        return "s" + Long.toHexString(hash);
    }

    private static String sectionName(String[] stationNames) {
        if (stationNames.length == 0) {
            return "?";
        }
        return stationNames[0] + " → " + stationNames[stationNames.length - 1];
    }

    // ---------------------------------------------------------------- timing

    /**
     * {median, spread} of the time from this depot's departure to arriving at the entry
     * platform on the given route, across its sidings; null when no siding has timings
     * yet (the depot has not generated).
     */
    /** One hold candidate (deduplicated on route + stop). */
    private static void addHold(List<InterlineModel.HoldSite> holds, Simulator simulator,
                                org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Long2LongAVLTreeMap> dwellOverrides,
                                Map<Long, InterlineModel.Route> routes, long routeId, int index, String kind, long forRouteId,
                                long fallbackRouteId) {
        InterlineModel.Route route = routes.get(routeId);
        if (route == null || index < 0 || index >= route.platformIds().length) {
            return;
        }
        for (InterlineModel.HoldSite hold : holds) {
            if (hold.routeId() == routeId && hold.stopIndex() == index) {
                return;
            }
        }
        long platformId = route.platformIds()[index];
        Platform platform = simulator.platformIdMap.get(platformId);
        if (platform == null) {
            return;
        }
        // The dwell MTR really uses: this route's override, else (at a merged route
        // boundary) the next route's override, else the platform's own dwell.
        Long2LongAVLTreeMap byRoute = dwellOverrides.get(platformId);
        boolean overridden = byRoute != null && byRoute.containsKey(routeId);
        boolean fallback = !overridden && fallbackRouteId != 0 && byRoute != null && byRoute.containsKey(fallbackRouteId);
        long dwell = overridden ? byRoute.get(routeId) : fallback ? byRoute.get(fallbackRouteId) : platform.getDwellTime();
        holds.add(new InterlineModel.HoldSite(routeId, index, platformId, route.stationNames()[index], dwell,
                overridden, kind, forRouteId));
    }

    /**
     * {median, spread} over the depot's sidings of when its trains arrive at
     * ({@code end = false}) or leave ({@code end = true}) a platform on a route, in millis
     * after the depot departure; null when no siding has a timing there.
     */
    private static long[] stopTime(Depot depot, long routeId, long entryPlatformId, int entryIndex, boolean end) {
        // A platform-grouped entry (terminal alternation, Feature 5) is served at one of the
        // group's member platforms, and MTR files the stop times under the platform the
        // train really stops at — so look there too.
        long[] candidates = {entryPlatformId};
        long[][] grouped = AddonSnapshots.platformGroups().get(routeId);
        if (grouped != null && entryIndex < grouped.length && grouped[entryIndex] != null) {
            candidates = new long[grouped[entryIndex].length + 1];
            candidates[0] = entryPlatformId;
            System.arraycopy(grouped[entryIndex], 0, candidates, 1, grouped[entryIndex].length);
        }
        List<Long> values = new ArrayList<>();
        for (Siding siding : depot.savedRails) {
            if (siding == null) {
                continue;
            }
            var byPlatform = ((SidingStopTimesAccessor) (Object) siding).stationAnnouncer$getPlatformTripStopTimes();
            List<Trip.StopTime> stopTimes = new ArrayList<>();
            for (long platformId : candidates) {
                ObjectArraySet<Trip.StopTime> atPlatform = byPlatform.get(platformId);
                if (atPlatform != null) {
                    stopTimes.addAll(atPlatform);
                }
            }
            long best = -1;
            boolean exact = false;
            for (Trip.StopTime stopTime : stopTimes) {
                if (stopTime.trip == null || stopTime.trip.route == null || stopTime.trip.route.getId() != routeId) {
                    continue;
                }
                boolean matches = stopTime.tripStopIndex == entryIndex;
                long value = end ? stopTime.endTime : stopTime.startTime;
                if (best < 0 || (matches && !exact) || (matches == exact && value < best)) {
                    best = value;
                    exact = matches;
                }
            }
            if (best >= 0) {
                values.add(best);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        values.sort(Long::compare);
        return new long[]{values.get(values.size() / 2), values.get(values.size() - 1) - values.get(0)};
    }

    /** Headways measured by analytics at the entry platform, for the section's routes. */
    private static InterlineModel.Measured measured(AnalyticsAggregator.Aggregate aggregate, long platformId, long[] routeIds) {
        if (aggregate == null || aggregate.events == null) {
            return InterlineModel.Measured.NONE;
        }
        List<Long> times = new ArrayList<>();
        for (AnalyticsEvent event : aggregate.events) {
            if (event.platformId() == platformId && contains(routeIds, event.routeId())) {
                times.add(event.atMillis());
            }
        }
        if (times.size() < 2) {
            return InterlineModel.Measured.NONE;
        }
        times.sort(Long::compare);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            long gap = times.get(i) - times.get(i - 1);
            if (gap > 0 && gap <= MEASURED_BREAK_MILLIS) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return InterlineModel.Measured.NONE;
        }
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long gap : gaps) {
            sum += gap;
            min = Math.min(min, gap);
            max = Math.max(max, gap);
        }
        double mean = (double) sum / gaps.size();
        double variance = 0;
        for (long gap : gaps) {
            variance += (gap - mean) * (gap - mean);
        }
        double irregularity = mean <= 0 ? 0 : Math.sqrt(variance / gaps.size()) / mean;
        return new InterlineModel.Measured(gaps.size(), Math.round(mean), min, max, Math.round(irregularity * 1000) / 1000.0);
    }

    // ---------------------------------------------------------------- helpers

    private static long[] toArray(List<Long> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static boolean contains(long[] values, long value) {
        for (long v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static void reverse(long[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            long t = values[i];
            values[i] = values[j];
            values[j] = t;
        }
    }
}
