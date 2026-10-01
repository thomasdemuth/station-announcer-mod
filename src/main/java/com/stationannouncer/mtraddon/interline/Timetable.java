package com.stationannouncer.mtraddon.interline;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pure timetable arithmetic — no MTR types, no state, safe on any thread.
 *
 * <p>{@link #departures} replicates MTR 4.0.1's departure writer exactly
 * ({@code Depot.generatePlatformDirectionsAndWriteDeparturesToSidings}, javap-verified):
 * per nominal hour {@code i} with slider value {@code f > 0}, the nominal interval is
 * {@code 14 400 000 / f} (integer division), departures run from
 * {@code max(hourStart, last + interval)} while inside the hour, and each nominal instant
 * {@code n} maps to real simulation time {@code n × gameMillisPerDay / 86 400 000}. So a
 * slider value {@code f} is a real headway of {@code gameMillisPerDay / (6 f)} — 200 s / f
 * on the default 20-minute day, which is why MTR's slider reads "200/f s".</p>
 */
public final class Timetable {
    static final long FREQUENCY_BASE_MILLIS = 14_400_000L;
    static final long NOMINAL_HOUR = 3_600_000L;
    static final long NOMINAL_DAY = 86_400_000L;
    static final int HOURS = 24;

    private Timetable() {
    }

    /** Sorted departure phases in {@code [0, gameMillisPerDay)} for 24 effective slider values. */
    public static long[] departures(long[] effective, long gameMillisPerDay) {
        if (gameMillisPerDay <= 0) {
            return new long[0];
        }
        long[] out = new long[64];
        int size = 0;
        long last = Long.MIN_VALUE;
        for (int hour = 0; hour < HOURS; hour++) {
            long frequency = effective[hour];
            if (frequency <= 0) {
                continue;
            }
            long interval = FREQUENCY_BASE_MILLIS / frequency;
            if (interval <= 0) {
                continue;
            }
            long hourMin = NOMINAL_HOUR * hour;
            long hourMax = NOMINAL_HOUR * (hour + 1);
            while (true) {
                long next = Math.max(hourMin, last + interval);
                if (next >= hourMax) {
                    break;
                }
                if (size == out.length) {
                    out = Arrays.copyOf(out, size * 2);
                }
                out[size++] = next * gameMillisPerDay / NOMINAL_DAY;
                last = next;
            }
        }
        return Arrays.copyOf(out, size);
    }

    /** Uniform all-day slider value → 24-entry array. */
    public static long[] uniform(long frequency) {
        long[] values = new long[HOURS];
        Arrays.fill(values, frequency);
        return values;
    }

    /** Real headway of a uniform slider value. */
    public static long headwayFor(long frequency, long gameMillisPerDay) {
        return frequency <= 0 ? 0 : gameMillisPerDay * (FREQUENCY_BASE_MILLIS / frequency) / NOMINAL_DAY;
    }

    /**
     * Combined arrival phases at a section's entry, sorted.
     *
     * @param departuresByDepot natural (undelayed) departure phases per depot
     * @param delays            delay per depot (millis, taken modulo the day); absent = 0
     * @param pads              extra dwell (millis) at given stops; a pad shifts every stop
     *                          after it in the depot's cycle
     */
    public static long[] arrivals(List<InterlineModel.Feed> feeds, Map<Long, long[]> departuresByDepot,
                                  Map<Long, Long> delays, Map<PadSite, Long> pads,
                                  Map<Long, InterlineModel.Depot> depots, long gameMillisPerDay) {
        int total = 0;
        for (InterlineModel.Feed feed : feeds) {
            long[] departures = departuresByDepot.get(feed.depotId());
            total += departures == null ? 0 : departures.length;
        }
        long[] out = new long[total];
        int size = 0;
        for (InterlineModel.Feed feed : feeds) {
            long[] departures = departuresByDepot.get(feed.depotId());
            if (departures == null) {
                continue;
            }
            Long delay = delays.get(feed.depotId());
            long shift = feed.travelMs() + (delay == null ? 0 : delay % gameMillisPerDay)
                    + padShift(feed, depots.get(feed.depotId()), pads);
            for (long departure : departures) {
                out[size++] = Math.floorMod(departure + shift, gameMillisPerDay);
            }
        }
        long[] result = size == out.length ? out : Arrays.copyOf(out, size);
        Arrays.sort(result);
        return result;
    }

    /** Where a pad sits: the route it belongs to and the stop index it is added at. */
    public record PadSite(long routeId, int stopIndex) {
    }

    /**
     * Total pad time a feed's trains have accumulated before reaching the entry: every pad
     * at a stop that comes earlier in the depot's cycle (an earlier route of the cycle, or
     * an earlier stop of the feed's own route).
     */
    static long padShift(InterlineModel.Feed feed, InterlineModel.Depot depot, Map<PadSite, Long> pads) {
        if (pads.isEmpty() || depot == null) {
            return 0;
        }
        long shift = 0;
        for (Map.Entry<PadSite, Long> pad : pads.entrySet()) {
            long millis = pad.getValue() == null ? 0 : pad.getValue();
            if (millis <= 0) {
                continue;
            }
            PadSite site = pad.getKey();
            int position = depot.routePosition(site.routeId());
            if (position < 0 || (site.stopIndex() == 0 && depot.mergedStart(site.routeId()))) {
                continue; // not this depot's route, or a stop merged into the previous route's
            }
            if (position < feed.routePos() || (position == feed.routePos() && site.stopIndex() < feed.entryIndex())) {
                shift += millis;
            }
        }
        return shift;
    }

    /**
     * Headway statistics of sorted phases over one cyclic game day. Gaps longer than four
     * times the day's average gap are service breaks (e.g. no trains at night) — counted,
     * but kept out of the mean/min/max/evenness so the night does not drown the daytime spacing.
     * {@code evenness} is the RMS deviation of the gaps from their mean, divided by the
     * mean: 0 = perfectly even, 1 = as uneven as trains in pairs.
     */
    public static InterlineModel.Stats stats(long[] sorted, long gameMillisPerDay) {
        int n = sorted.length;
        if (n < 2 || gameMillisPerDay <= 0) {
            return new InterlineModel.Stats(n, n == 1 ? gameMillisPerDay : 0, 0, 0, 0, 0);
        }
        long[] gaps = new long[n];
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = sorted[i + 1] - sorted[i];
        }
        gaps[n - 1] = sorted[0] + gameMillisPerDay - sorted[n - 1];
        // A service break (e.g. no trains overnight) is a gap four times the day's AVERAGE
        // gap. Not the median: trains bunched into tight groups have a tiny median, and the
        // long gaps between the groups — the very thing being scored — would be ignored.
        long breakAt = Math.max(1, gameMillisPerDay / n) * 4;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        int kept = 0;
        int breaks = 0;
        for (long gap : gaps) {
            if (gap > breakAt) {
                breaks++;
                continue;
            }
            sum += gap;
            kept++;
            min = Math.min(min, gap);
            max = Math.max(max, gap);
        }
        if (kept == 0) {
            return new InterlineModel.Stats(n, 0, 0, 0, 0, breaks);
        }
        double mean = (double) sum / kept;
        double variance = 0;
        for (long gap : gaps) {
            if (gap <= breakAt) {
                double delta = gap - mean;
                variance += delta * delta;
            }
        }
        variance /= kept;
        double evenness = mean <= 0 ? 0 : Math.sqrt(variance) / mean;
        return new InterlineModel.Stats(n, Math.round(mean), min, max, Math.round(evenness * 1000) / 1000.0, breaks);
    }
}
