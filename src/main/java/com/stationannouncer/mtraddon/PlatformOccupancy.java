package com.stationannouncer.mtraddon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Pure Java (no Minecraft / MTR types): who occupies one platform over a game day, and
 * where two trains would want it at the same time.
 *
 * <p>Input is what {@link PlatformSchedule} sends: per siding its departure phases and its
 * baked stop times at the platform. A {@link Plan} supplies the dwell each route WILL get
 * (the edited platform default / per-route override), so the platform screen re-times the
 * day live while its sliders move. A changed dwell moves that visit's departure and every
 * later visit of the same train cycle; arrivals at the platform are never moved by it.</p>
 *
 * <p>A conflict is a train arriving while an earlier, different train is still dwelling:
 * MTR's signalling makes it wait outside, so it runs late from there on. Same route =
 * "dwell ≥ headway"; another route = "platform occupied". With a connection hold rule the
 * platform can be held for up to {@code holdExtra} more — overlaps only inside that window
 * are reported as {@code maybe}.</p>
 */
public final class PlatformOccupancy {
    private PlatformOccupancy() {
    }

    /** A baked stop at the platform: millis after the siding's departure. */
    public record Stop(long routeId, int stopIndex, long start, long end) {
        public long baked() {
            return Math.max(0, end - start);
        }
    }

    public record Siding(long depotId, long sidingId, String depotName, long repeat, long[] phases, Stop[] stops) {
    }

    /** The dwell the platform will give a route, and the worst extra a hold rule can add. */
    public interface Plan {
        long dwell(long routeId, long bakedDwell);

        long holdExtra();
    }

    /**
     * One train at the platform. {@code arrive} is in {@code [0, day)}; {@code leave} and
     * {@code maybeLeave} may run past the day end. {@code train} identifies the physical
     * run (siding × departure) so a train never conflicts with itself.
     */
    public record Visit(long routeId, long depotId, String depotName, long arrive, long leave, long maybeLeave,
                        long bakedLeave, long train) {
        public long dwell() {
            return leave - arrive;
        }
    }

    /** {@code blocker} still holds the platform when {@code blocked} arrives; it waits {@code waitMillis}. */
    public record Conflict(Visit blocker, Visit blocked, long waitMillis, boolean maybe) {
        public boolean sameRoute() {
            return blocker.routeId() == blocked.routeId();
        }
    }

    public record Result(long day, List<Visit> visits, List<Conflict> conflicts) {
        /** Shortest gap between consecutive arrivals of one route here (0 = fewer than two). */
        public long headway(long routeId) {
            List<Long> arrivals = new ArrayList<>();
            for (Visit visit : visits) {
                if (visit.routeId() == routeId) {
                    arrivals.add(visit.arrive());
                }
            }
            if (arrivals.size() < 2) {
                return 0;
            }
            arrivals.sort(Long::compare);
            long best = Long.MAX_VALUE;
            for (int i = 0; i < arrivals.size(); i++) {
                long next = i + 1 < arrivals.size() ? arrivals.get(i + 1) : arrivals.get(0) + day;
                best = Math.min(best, next - arrivals.get(i));
            }
            return best;
        }
    }

    /**
     * {@code day} is only the fallback cycle: sidings repeat their departures every
     * {@link Siding#repeat()} (which need not be the game day — a depot can run a 60-minute
     * cycle on a 20-minute day), so the timeline's cycle is the longest repeat, and a siding
     * with a shorter one is repeated to fill it.
     */
    public static Result compute(List<Siding> sidings, long day, Plan plan) {
        List<Visit> visits = new ArrayList<>();
        for (Siding siding : sidings) {
            day = Math.max(day, siding.repeat());
        }
        if (day <= 0) {
            return new Result(day, visits, List.of());
        }
        long holdExtra = Math.max(0, plan.holdExtra());
        for (Siding siding : sidings) {
            Stop[] stops = siding.stops().clone();
            Arrays.sort(stops, Comparator.comparingLong(Stop::start));
            long repeat = siding.repeat() > 0 ? siding.repeat() : day;
            long copies = Math.max(1, Math.min(64, day / repeat));
            for (int d = 0; d < siding.phases().length * copies; d++) {
                long departure = siding.phases()[(int) (d % siding.phases().length)] + (d / siding.phases().length) * repeat;
                long train = siding.sidingId() * 4_099L + d;
                long shift = 0;
                Visit open = null;
                for (Stop stop : stops) {
                    long baked = stop.baked();
                    long planned = Math.max(0, plan.dwell(stop.routeId(), baked));
                    long arrive = departure + stop.start() + shift;
                    long leave = arrive + planned;
                    shift += planned - baked;
                    if (open != null && arrive <= open.leave()) {
                        // Same train still here (MTR merges a route ending where the next
                        // one starts into one stop): extend the earlier visit.
                        long merged = Math.max(open.leave(), leave);
                        open = new Visit(open.routeId(), open.depotId(), open.depotName(), open.arrive(), merged,
                                merged + holdExtra, Math.max(open.bakedLeave(), departure + stop.end()), train);
                        continue;
                    }
                    if (open != null) {
                        visits.add(normalise(open, day));
                    }
                    open = new Visit(stop.routeId(), siding.depotId(), siding.depotName(), arrive, leave,
                            leave + holdExtra, departure + stop.end(), train);
                }
                if (open != null) {
                    visits.add(normalise(open, day));
                }
            }
        }
        visits.sort(Comparator.comparingLong(Visit::arrive));
        return new Result(day, visits, conflicts(visits, day));
    }

    private static Visit normalise(Visit visit, long day) {
        long arrive = Math.floorMod(visit.arrive(), day);
        long delta = arrive - visit.arrive();
        return new Visit(visit.routeId(), visit.depotId(), visit.depotName(), arrive, visit.leave() + delta,
                visit.maybeLeave() + delta, visit.bakedLeave() + delta, visit.train());
    }

    /** Sweep over the cyclic day: each visit against the visits arriving while it is (maybe) there. */
    private static List<Conflict> conflicts(List<Visit> sorted, long day) {
        List<Conflict> out = new ArrayList<>();
        int n = sorted.size();
        for (int i = 0; i < n; i++) {
            Visit first = sorted.get(i);
            long until = Math.max(first.leave(), first.maybeLeave());
            for (int k = 1; k < n + 1; k++) {
                int j = (i + k) % n;
                long wrap = (i + k) >= n ? day : 0;
                Visit second = sorted.get(j);
                long arrive = second.arrive() + wrap;
                if (arrive >= until || k >= n) {
                    break;
                }
                if (second.train() == first.train()) {
                    continue;
                }
                if (arrive < first.leave()) {
                    out.add(new Conflict(first, second, first.leave() - arrive, false));
                } else {
                    out.add(new Conflict(first, second, first.maybeLeave() - arrive, true));
                }
            }
        }
        out.sort(Comparator.comparing(Conflict::maybe).thenComparing(Comparator.comparingLong(Conflict::waitMillis).reversed()));
        return out;
    }
}
