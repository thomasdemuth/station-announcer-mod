package com.stationannouncer.mtraddon.interline;

import java.util.List;
import java.util.Map;

/**
 * Plain, immutable snapshot of everything the interline tooling needs about one
 * dimension's railway — built on the simulator thread by {@link InterlineAnalyzer},
 * then read from any thread (solver worker, server thread, Jetty workers). No MTR types
 * cross this boundary.
 *
 * <p>All times are real milliseconds of simulation; "phase" values are taken modulo the
 * game day {@link Analysis#gameMillisPerDay}.</p>
 */
public final class InterlineModel {
    private InterlineModel() {
    }

    /**
     * One depot.
     *
     * @param routeIds      the depot's route cycle, in MTR's order (a train runs them in turn)
     * @param frequencies   the 24 raw hourly slider values
     * @param effective     the 24 values MTR actually uses (the current hour's value for
     *                      every hour while game time is frozen), which is what the departure
     *                      list is built from
     * @param intervalMs    mean real headway of the depot's own departures (0 = not derivable)
     * @param delayMs       the stored delay (0 = none)
     * @param appliedMs     the delay last written into its departures, -1 = never written
     * @param tunable       frequency-slider depot (real-time timetables and continuous
     *                      movement can be neither delayed nor re-timed)
     */
    public record Depot(long id, String name, int color, long[] routeIds, long[] frequencies, long[] effective,
                        long intervalMs, long delayMs, long appliedMs, boolean tunable, String reason,
                        int sidings, long[] groupIds, long[] mergedStartRoutes) {
        public Depot(long id, String name, int color, long[] routeIds, long[] frequencies, long[] effective,
                     long intervalMs, long delayMs, long appliedMs, boolean tunable, String reason,
                     int sidings, long[] groupIds) {
            this(id, name, color, routeIds, frequencies, effective, intervalMs, delayMs, appliedMs, tunable, reason,
                    sidings, groupIds, new long[0]);
        }

        /**
         * Whether, in THIS depot's cycle, the route's first stop is merged with the previous
         * route's last stop (same platform): MTR then runs one stop whose dwell belongs to
         * the previous route, so a hold at the route's own first stop does nothing here.
         */
        public boolean mergedStart(long routeId) {
            for (long id : mergedStartRoutes) {
                if (id == routeId) {
                    return true;
                }
            }
            return false;
        }

        /** The single slider value used all day, or -1 when the hours differ. */
        public long uniformFrequency() {
            long first = effective[0];
            for (long value : effective) {
                if (value != first) {
                    return -1;
                }
            }
            return first;
        }

        /** Position of a route in the depot's cycle, or -1. */
        public int routePosition(long routeId) {
            for (int i = 0; i < routeIds.length; i++) {
                if (routeIds[i] == routeId) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** One route (line service) with its stops. */
    public record Route(long id, String name, String number, int color, long[] platformIds,
                        long[] stationIds, String[] stationNames) {
    }

    /** A user-defined depot group (membership only — the tools suggest, nothing shifts by itself). */
    public record Group(long id, String name, long[] depotIds) {
    }

    /**
     * One depot's trains entering a section.
     *
     * @param travelMs    median time from the depot departure to arriving at the entry
     *                    platform, across the depot's sidings (from MTR's baked stop times)
     * @param spreadMs    max − min of that time across sidings (sidings further from the
     *                    main line reach the section later)
     * @param routePos    position of the route in the depot's cycle
     * @param entryIndex  index of the entry platform within the route's stops
     * @param stationArr  arrival at each of the section's stations, millis after the depot
     *                    departure (-1 = unknown) — the stringline's points
     * @param stationDep  departure from each of the section's stations (-1 = unknown)
     * @param approachDep departure from the stop before the section (-1 = none/unknown)
     */
    public record Feed(long depotId, long routeId, long travelMs, long spreadMs, int routePos, int entryIndex,
                       long[] stationArr, long[] stationDep, long approachDep) {
        public Feed(long depotId, long routeId, long travelMs, long spreadMs, int routePos, int entryIndex) {
            this(depotId, routeId, travelMs, spreadMs, routePos, entryIndex, new long[0], new long[0], -1);
        }
    }

    /**
     * A platform where holding a line's trains longer shifts its arrival at the section —
     * without touching the depot's timetable (so a line that runs out and back from the
     * same depot can be moved in ONE direction).
     *
     * @param kind       {@code before} = the stop just before the section; {@code turnaround}
     *                   = the last stop of the depot's previous route in its cycle (where the
     *                   train turns back); {@code origin} = the first stop of this route
     * @param forRouteId the section route whose trains this hold shifts
     */
    public record HoldSite(long routeId, int stopIndex, long platformId, String stationName, long dwellMs,
                           boolean overridden, String kind, long forRouteId) {
    }

    /**
     * The stop just before a section on one of its routes — where a dwell pad moves only
     * that route, only in this direction.
     *
     * @param dwellMs     the dwell trains get there now (an addon override, else the platform's)
     * @param overridden  whether {@code dwellMs} is an addon per-route override
     * @param index       index of this stop within the route
     */
    public record PrevStop(long routeId, long platformId, String stationName, long dwellMs, boolean overridden, int index) {
    }

    /** Headway statistics of a set of arrival instants over one game day. */
    public record Stats(int count, long meanMs, long minMs, long maxMs, double evenness, int breaks) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0);
    }

    /** Headways measured by analytics at the entry platform (departures of the section's routes). */
    public record Measured(int samples, long avgMs, long minMs, long maxMs, double irregularity) {
        public static final Measured NONE = new Measured(0, 0, 0, 0, 0);
    }

    /**
     * One interline section: a run of consecutive platforms that two or more routes share
     * in the same order (so the same direction of travel).
     *
     * @param id          stable id derived from the platform sequence
     * @param reverseId   the section covering the same stations the other way, or ""
     * @param scheduled   combined scheduled headway at the entry with today's settings
     */
    public record Section(String id, String name, String reverseId, long[] routeIds, long[] platformIds,
                          long[] stationIds, String[] stationNames, List<Feed> feeds, List<PrevStop> prevStops,
                          List<HoldSite> holds, Stats scheduled, Measured measured) {
        public Section(String id, String name, String reverseId, long[] routeIds, long[] platformIds,
                       long[] stationIds, String[] stationNames, List<Feed> feeds, List<PrevStop> prevStops,
                       Stats scheduled, Measured measured) {
            this(id, name, reverseId, routeIds, platformIds, stationIds, stationNames, feeds, prevStops,
                    holdsFrom(prevStops), scheduled, measured);
        }

        /** Tests/demo: every "stop before" as a hold site. */
        private static List<HoldSite> holdsFrom(List<PrevStop> prevStops) {
            List<HoldSite> holds = new java.util.ArrayList<>();
            for (PrevStop stop : prevStops) {
                holds.add(new HoldSite(stop.routeId(), stop.index(), stop.platformId(), stop.stationName(),
                        stop.dwellMs(), stop.overridden(), "before", stop.routeId()));
            }
            return List.copyOf(holds);
        }

        public long entryPlatformId() {
            return platformIds[0];
        }
    }

    /** Everything for one dimension. */
    public record Analysis(String dimension, long gameMillisPerDay, long builtAt, boolean timeMoving,
                           int maxFrequency, long maxPadMs, Map<Long, Depot> depots, Map<Long, Route> routes,
                           List<Group> groups, List<Section> sections, Map<String, Section> sectionsById) {
        public Section section(String id) {
            return id == null ? null : sectionsById.get(id);
        }
    }
}
