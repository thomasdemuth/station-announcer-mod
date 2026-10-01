package com.stationannouncer.mtraddon.interline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suggests depot delays, dwell pads at the stop before a section and (in target mode)
 * depot frequencies, so trains arrive at an interline section evenly spaced.
 *
 * <p>Pure: works only on an immutable {@link InterlineModel.Analysis}, so it runs on any
 * thread (the solver worker, or a Jetty worker for the web view). Every candidate is
 * scored on the REAL combined arrival pattern of a whole game day
 * ({@link Timetable#arrivals}) rather than on idealised phases, so sidings, uneven hours
 * and the day wrap are all accounted for.</p>
 *
 * <p><b>Directions.</b> One depot delay moves both directions together. {@code balance}
 * minimises the worse of the two; {@code forward}/{@code reverse} make one direction
 * as even as possible; {@code options} returns several distinct solutions (different
 * orders of the lines on the trunk) with both directions' spacing, to pick from. Dwell
 * pads then fix the other direction: a pad at one route's stop before the section moves
 * only that route's trains from there on.</p>
 */
public final class InterlineSolver {
    private static final int COARSE_STEPS = 180;
    private static final int FINE_STEPS = 16;
    private static final int ROUNDS = 3;
    private static final int MAX_STARTS = 6;
    private static final long PAD_STEP_MILLIS = 1_000;
    private static final double PAD_MIN_GAIN = 0.02;

    private InterlineSolver() {
    }

    /**
     * A parsed suggestion request (same fields from the game packet and the web query).
     *
     * @param levers what the suggestion may change: {@code delays} (depot departure delays
     *               only), {@code holds} (longer dwell at platforms only — for a line that
     *               runs out and back from the same depot, where a depot delay would move
     *               both directions) or {@code both}
     */
    public record Request(String sectionId, String mode, long targetMs, String direction, String levers,
                          Set<Long> depots, long groupId, Map<Long, Double> weights) {
        public boolean pads() {
            return !"delays".equals(levers);
        }

        public boolean delays() {
            return !"holds".equals(levers);
        }

        public static Request fromJson(JsonObject json) {
            Set<Long> depots = new LinkedHashSet<>();
            if (json.has("depots") && json.get("depots").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("depots")) {
                    depots.add(parseLong(element.getAsString()));
                }
            }
            Map<Long, Double> weights = new HashMap<>();
            if (json.has("weights") && json.get("weights").isJsonObject()) {
                json.getAsJsonObject("weights").entrySet().forEach(entry ->
                        weights.put(parseLong(entry.getKey()), Math.max(0.01, entry.getValue().getAsDouble())));
            }
            String levers = string(json, "levers");
            if (!levers.equals("delays") && !levers.equals("holds") && !levers.equals("both")) {
                levers = !json.has("pads") || json.get("pads").getAsBoolean() ? "both" : "delays";
            }
            return new Request(string(json, "section"), string(json, "mode"),
                    json.has("targetMs") ? json.get("targetMs").getAsLong() : 0, string(json, "direction"),
                    levers, depots,
                    json.has("group") ? parseLong(json.get("group").getAsString()) : 0, weights);
        }
    }

    // ------------------------------------------------------------------ entry

    public static JsonObject solve(InterlineModel.Analysis analysis, Request request) {
        JsonObject out = new JsonObject();
        InterlineModel.Section forward = analysis.section(request.sectionId());
        if (forward == null) {
            return error("That section is no longer detected — refresh.");
        }
        InterlineModel.Section reverse = analysis.section(forward.reverseId());
        long day = analysis.gameMillisPerDay();
        if (day <= 0) {
            return error("The railway has no game-day length yet.");
        }
        String direction = switch (request.direction() == null ? "" : request.direction()) {
            case "forward", "reverse", "options" -> request.direction();
            default -> "balance";
        };
        if (reverse == null && "reverse".equals(direction)) {
            direction = "forward";
        }
        boolean target = "target".equals(request.mode());
        List<String> warnings = new ArrayList<>();

        // ---- who feeds the section, who may move
        Map<Long, InterlineModel.Depot> depots = analysis.depots();
        List<InterlineModel.Feed> feedsF = timedFeeds(forward, depots, warnings);
        List<InterlineModel.Feed> feedsR = reverse == null ? List.of() : timedFeeds(reverse, depots, warnings);
        Set<Long> involved = new LinkedHashSet<>();
        feedsF.forEach(feed -> involved.add(feed.depotId()));
        feedsR.forEach(feed -> involved.add(feed.depotId()));
        if (feedsF.isEmpty()) {
            return error("No depot's trains reach this section with timings yet — generate the depots first.");
        }
        Set<Long> allowed = request.depots();
        if (allowed.isEmpty() && request.groupId() != 0) {
            for (InterlineModel.Group group : analysis.groups()) {
                if (group.id() == request.groupId()) {
                    allowed = new LinkedHashSet<>();
                    for (long id : group.depotIds()) {
                        allowed.add(id);
                    }
                }
            }
        }
        List<Long> adjustable = new ArrayList<>();
        for (long id : involved) {
            if (allowed.isEmpty() || allowed.contains(id)) {
                adjustable.add(id);
            }
        }
        if (adjustable.isEmpty()) {
            return error("None of the chosen depots runs through this section.");
        }
        adjustable.sort(Long::compare);
        List<Long> fixed = new ArrayList<>();
        for (long id : involved) {
            if (!adjustable.contains(id)) {
                fixed.add(id);
            }
        }

        // ---- frequencies
        Map<Long, long[]> effective = new HashMap<>();
        for (long id : involved) {
            effective.put(id, depots.get(id).effective());
        }
        JsonObject targetJson = null;
        Map<Long, Long> chosenFrequency = new LinkedHashMap<>();
        if (target) {
            if (request.targetMs() <= 0) {
                return error("Enter a target headway.");
            }
            targetJson = chooseFrequencies(analysis, forward, feedsF, adjustable, fixed, request, effective,
                    chosenFrequency, warnings);
            chosenFrequency.forEach((id, f) -> effective.put(id, Timetable.uniform(f)));
        }
        for (long id : adjustable) {
            InterlineModel.Depot depot = depots.get(id);
            if (!target && depot.uniformFrequency() < 0) {
                warnings.add(depot.name() + " runs different frequencies through the day — the spacing is exact only in hours that match.");
            }
            Set<Long> others = new LinkedHashSet<>();
            for (long routeId : depot.routeIds()) {
                if (!contains(forward.routeIds(), routeId) && (reverse == null || !contains(reverse.routeIds(), routeId))) {
                    others.add(routeId);
                }
            }
            if (target && !others.isEmpty()) {
                List<String> names = new ArrayList<>();
                others.forEach(routeId -> {
                    InterlineModel.Route route = analysis.routes().get(routeId);
                    names.add(route == null ? "?" : route.name());
                });
                warnings.add(depot.name() + " also runs " + String.join(", ", names) + " — its new frequency applies there too.");
            }
        }
        Map<Long, long[]> departures = new HashMap<>();
        effective.forEach((id, values) -> departures.put(id, Timetable.departures(values, day)));
        Map<Long, Long> period = new HashMap<>();
        for (long id : adjustable) {
            long[] values = effective.get(id);
            long uniform = uniformOf(values);
            long interval = uniform > 0 ? Timetable.headwayFor(uniform, day) : depots.get(id).intervalMs();
            period.put(id, Math.max(1000, interval));
        }
        // Different headways can never interleave evenly (3 trains per 200 s from a 100 s
        // and a 200 s depot at best go 50/50/100) — say so, or a near-zero "improvement"
        // reads like the tool failing.
        boolean mixed = false;
        JsonArray matchOptions = null;
        if (!target) {
            Set<Long> intervals = new LinkedHashSet<>();
            List<String> parts = new ArrayList<>();
            for (long id : involved) {
                long interval = Timetable.headwayFor(Math.max(0, uniformOf(effective.get(id))), day);
                if (interval <= 0) {
                    interval = depots.get(id).intervalMs();
                }
                if (intervals.add(Math.round(interval / 1000.0))) {
                    parts.add(depots.get(id).name() + " every " + duration(interval));
                }
            }
            if (intervals.size() > 1) {
                mixed = true;
                warnings.add("Different frequencies (" + String.join(", ", parts)
                        + "): the best possible is the slower line filling the faster line's gaps, so this aims for the shortest longest wait. "
                        + "To get perfectly even trains, match the frequencies (below).");
                matchOptions = matchOptions(analysis, feedsF, adjustable, fixed, effective, involved);
            }
        }

        Scorer scorer = new Scorer(feedsF, feedsR, departures, depots, day, direction);
        Map<Long, Long> current = new LinkedHashMap<>();
        for (long id : involved) {
            current.put(id, depots.get(id).delayMs());
        }
        InterlineModel.Stats[] before = scorerFor(feedsF, feedsR, depots, day, direction, analysis, involved).stats(current, Map.of());

        // ---- delay search
        boolean pinFirst = fixed.isEmpty();
        List<Long> variables = new ArrayList<>(adjustable);
        long reference = pinFirst ? variables.remove(0) : 0;
        List<Map<Long, Long>> starts = starts(adjustable, variables, reference, pinFirst, current, departures,
                "reverse".equals(direction) ? feedsR : feedsF, period, day);
        if (!request.delays()) {
            // Platform holds only: every depot keeps the delay it has.
            variables.clear();
            starts = List.of(new LinkedHashMap<>(current));
        }
        // Seeds: the optimum of EACH objective (this way / other way / balance) from every
        // start. Coordinate descent on one objective alone can stall on a plateau that
        // another objective walks straight across, so every answer below is refined from
        // all of them. The one-way optima are also what the pad search and Options offer.
        String[] objectives = reverse == null ? new String[]{"forward"} : new String[]{"forward", "reverse", "balance"};
        Map<String, Map<Long, Long>> oneWayBest = new HashMap<>();
        List<Map<Long, Long>> seeds = new ArrayList<>();
        for (String objective : objectives) {
            Scorer directional = new Scorer(feedsF, feedsR, departures, depots, day, objective);
            double bestScore = Double.MAX_VALUE;
            for (Map<Long, Long> start : starts) {
                Map<Long, Long> delays = descend(directional, start, variables, period);
                seeds.add(delays);
                double score = directional.score(delays, Map.of());
                if (score < bestScore) {
                    bestScore = score;
                    oneWayBest.put(objective, delays);
                }
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map<Long, Long> seed : seeds) {
            Map<Long, Long> refined = descend(scorer, seed, variables, period);
            candidates.add(new Candidate(refined, scorer.score(refined, Map.of()), scorer.stats(refined, Map.of())));
            if ("options".equals(direction)) {
                candidates.add(new Candidate(seed, scorer.score(seed, Map.of()), scorer.stats(seed, Map.of())));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        List<Candidate> distinct = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean duplicate = false;
            for (Candidate kept : distinct) {
                duplicate |= same(kept.delays, candidate.delays, period);
            }
            if (!duplicate) {
                distinct.add(candidate);
            }
        }
        Candidate best = distinct.get(0);
        Map<Long, Long> bestDelays = request.delays()
                ? rebase(scorer, best.delays, adjustable, period, pinFirst, best.score) : new LinkedHashMap<>(current);

        // ---- dwell pads
        Map<Timetable.PadSite, Long> pads = new LinkedHashMap<>();
        List<InterlineModel.HoldSite> sites = new ArrayList<>();
        if (request.pads() && analysis.maxPadMs() > 0) {
            addSites(sites, forward, feedsF);
            if (reverse != null) {
                addSites(sites, reverse, feedsR);
            }
            if (!sites.isEmpty()) {
                pads = padSearch(scorer, bestDelays, sites, analysis.maxPadMs(), direction);
                if (request.delays() && !same(bestDelays, current, period)) {
                    // Keeping today's delays and only holding trains can beat new delays plus
                    // holds (a depot running the same section twice, e.g. out and back, where a
                    // delay moves both runs together): try it, keep the cheaper answer.
                    Map<Long, Long> kept = new LinkedHashMap<>(current);
                    Map<Timetable.PadSite, Long> keptPads = padSearch(scorer, kept, sites, analysis.maxPadMs(), direction);
                    if (finalScore(scorer, kept, keptPads, direction) < finalScore(scorer, bestDelays, pads, direction) - 0.005) {
                        bestDelays = kept;
                        pads = keptPads;
                    }
                }
                if (request.delays() && reverse != null && ("balance".equals(direction) || "options".equals(direction))) {
                    // A delay set that makes ONE direction perfect plus a pad that fixes the
                    // other often beats the best compromise plus a pad: try both.
                    double bestFinal = finalScore(scorer, bestDelays, pads, "balance");
                    for (String oneWay : new String[]{"forward", "reverse"}) {
                        Scorer directional = new Scorer(feedsF, feedsR, departures, depots, day, oneWay);
                        Map<Long, Long> seed = oneWayBest.get(oneWay);
                        if (seed == null) {
                            continue;
                        }
                        Map<Long, Long> refined = descend(directional, seed, variables, period);
                        Map<Long, Long> oneWayDelays = rebase(directional, refined, adjustable, period, pinFirst,
                                directional.score(refined, Map.of()));
                        Map<Timetable.PadSite, Long> oneWayPads = padSearch(scorer, oneWayDelays, sites, analysis.maxPadMs(), direction);
                        double score = finalScore(scorer, oneWayDelays, oneWayPads, "balance");
                        if (score < bestFinal - 0.01) {
                            bestFinal = score;
                            bestDelays = oneWayDelays;
                            pads = oneWayPads;
                        }
                    }
                }
            }
        }
        // Not worth a change: keep today's delays (and no pads) rather than churn the timetable.
        double beforeScore = combine(before, direction);
        double afterScore = combine(scorer.stats(bestDelays, pads), direction);
        boolean noGain = !target && beforeScore - afterScore < 0.02;
        if (noGain) {
            bestDelays = new LinkedHashMap<>(current);
            pads = new LinkedHashMap<>();
            warnings.add(0, mixed ? "Already the best spacing these frequencies allow — no change suggested."
                    : "Already as even as these depots allow — no change suggested.");
        }
        out.addProperty("unchanged", noGain);
        InterlineModel.Stats[] afterNoPads = scorer.stats(bestDelays, Map.of());
        InterlineModel.Stats[] after = scorer.stats(bestDelays, pads);

        // ---- reply
        out.addProperty("ok", true);
        out.addProperty("section", forward.id());
        out.addProperty("reverse", reverse == null ? "" : reverse.id());
        out.addProperty("mode", target ? "target" : "even");
        out.addProperty("direction", direction);
        out.add("adjustable", ids(adjustable));
        out.add("fixed", ids(fixed));
        JsonObject delaysJson = new JsonObject();
        bestDelays.forEach((id, millis) -> {
            if (adjustable.contains(id)) {
                delaysJson.addProperty(Long.toString(id), millis);
            }
        });
        out.add("delays", delaysJson);
        JsonObject headways = new JsonObject();
        period.forEach((id, millis) -> headways.addProperty(Long.toString(id), millis));
        out.add("headways", headways);
        JsonObject frequencies = new JsonObject();
        chosenFrequency.forEach((id, f) -> frequencies.addProperty(Long.toString(id), f));
        out.add("frequencies", frequencies);
        JsonArray padsJson = new JsonArray();
        for (InterlineModel.HoldSite site : sites) {
            Long extra = pads.get(new Timetable.PadSite(site.routeId(), site.stopIndex()));
            if (extra == null || extra <= 0) {
                continue;
            }
            JsonObject pad = new JsonObject();
            pad.addProperty("route", Long.toString(site.routeId()));
            pad.addProperty("platform", Long.toString(site.platformId()));
            pad.addProperty("station", site.stationName());
            pad.addProperty("stopIndex", site.stopIndex());
            pad.addProperty("kind", site.kind());
            pad.addProperty("forRoute", Long.toString(site.forRouteId()));
            pad.addProperty("extraMs", extra);
            pad.addProperty("currentDwellMs", site.dwellMs());
            pad.addProperty("dwellMs", Math.min(600_000, site.dwellMs() + extra));
            padsJson.add(pad);
        }
        out.add("pads", padsJson);
        out.add("before", pairJson(before));
        out.add("afterNoPads", pairJson(afterNoPads));
        out.add("after", pairJson(after));
        // Option 1 is the main suggestion (with its dwell pads); then the other distinct
        // delay sets, without pads.
        JsonArray candidatesJson = new JsonArray();
        List<Candidate> listed = new ArrayList<>();
        listed.add(new Candidate(bestDelays, combine(after, direction), after));
        for (Candidate candidate : distinct) {
            if (listed.size() >= 5) {
                break;
            }
            boolean duplicate = false;
            for (Candidate kept : listed) {
                duplicate |= same(kept.delays, candidate.delays, period);
            }
            if (!duplicate) {
                listed.add(candidate);
            }
        }
        for (Candidate candidate : listed) {
            JsonObject json = new JsonObject();
            JsonObject delays = new JsonObject();
            candidate.delays.forEach((id, millis) -> {
                if (adjustable.contains(id)) {
                    delays.addProperty(Long.toString(id), millis);
                }
            });
            json.add("delays", delays);
            json.add("stats", pairJson(candidate.stats));
            json.addProperty("score", round3(candidate.score));
            candidatesJson.add(json);
        }
        out.add("candidates", candidatesJson);
        out.addProperty("mixed", mixed);
        if (matchOptions != null) {
            out.add("matchOptions", matchOptions);
        }
        if (targetJson != null) {
            out.add("target", targetJson);
        }
        if (!fixed.isEmpty()) {
            List<String> names = new ArrayList<>();
            fixed.forEach(id -> names.add(depots.get(id).name()));
            warnings.add("Kept as they are (not selected): " + String.join(", ", names) + ".");
        }
        if (reverse == null) {
            warnings.add("No opposite-direction section was found, so only this direction is scored.");
        }
        if (request.pads() && sites.isEmpty()) {
            warnings.add("No platform found where a longer dwell would move these trains.");
        }
        if (!request.delays() && pads.isEmpty() && !noGain) {
            warnings.add("Platform holds alone cannot improve this section — allow depot delays too.");
        }
        JsonArray warningsJson = new JsonArray();
        new LinkedHashSet<>(warnings).forEach(warningsJson::add);
        out.add("warnings", warningsJson);
        return out;
    }

    // --------------------------------------------------------------- scoring

    private record Candidate(Map<Long, Long> delays, double score, InterlineModel.Stats[] stats) {
    }

    /** Scores a delay (+ pad) assignment against the forward and reverse sections. */
    private static final class Scorer {
        final List<InterlineModel.Feed> forward;
        final List<InterlineModel.Feed> reverse;
        final Map<Long, long[]> departures;
        final Map<Long, InterlineModel.Depot> depots;
        final long day;
        final String direction;

        Scorer(List<InterlineModel.Feed> forward, List<InterlineModel.Feed> reverse, Map<Long, long[]> departures,
               Map<Long, InterlineModel.Depot> depots, long day, String direction) {
            this.forward = forward;
            this.reverse = reverse;
            this.departures = departures;
            this.depots = depots;
            this.day = day;
            this.direction = direction;
        }

        InterlineModel.Stats[] stats(Map<Long, Long> delays, Map<Timetable.PadSite, Long> pads) {
            InterlineModel.Stats f = Timetable.stats(Timetable.arrivals(forward, departures, delays, pads, depots, day), day);
            InterlineModel.Stats r = reverse.isEmpty() ? null
                    : Timetable.stats(Timetable.arrivals(reverse, departures, delays, pads, depots, day), day);
            return new InterlineModel.Stats[]{f, r};
        }

        double score(Map<Long, Long> delays, Map<Timetable.PadSite, Long> pads) {
            InterlineModel.Stats[] s = stats(delays, pads);
            return combine(s, direction);
        }
    }

    /** The balance score of a delay + pad answer, with the same small per-second pad cost. */
    private static double finalScore(Scorer scorer, Map<Long, Long> delays, Map<Timetable.PadSite, Long> pads,
                                     String direction) {
        long total = 0;
        for (long value : pads.values()) {
            total += value;
        }
        return combine(scorer.stats(delays, pads), direction) + 0.0002 * (total / 1000.0);
    }

    /**
     * One direction's badness: the RMS spread of the gaps, plus a little for the longest
     * gap (the worst wait a rider sees). The second term only matters when perfect spacing
     * is impossible — mixed frequencies — where it prefers the answer with the shortest
     * longest wait among equally even ones.
     */
    static double badness(InterlineModel.Stats stats) {
        if (stats == null || stats.count() < 2 || stats.meanMs() <= 0) {
            return 0;
        }
        return stats.evenness() + 0.1 * Math.max(0, (double) stats.maxMs() / stats.meanMs() - 1);
    }

    static double combine(InterlineModel.Stats[] s, String direction) {
        double f = badness(s[0]);
        if (s[1] == null) {
            return f;
        }
        double r = badness(s[1]);
        return switch (direction) {
            case "forward" -> f + 0.001 * r;
            case "reverse" -> r + 0.001 * f;
            default -> Math.max(f, r) + 0.1 * (f + r);
        };
    }

    /** Stats of the CURRENT settings (stored delays, current frequencies, no pads). */
    private static Scorer scorerFor(List<InterlineModel.Feed> forward, List<InterlineModel.Feed> reverse,
                                    Map<Long, InterlineModel.Depot> depots, long day, String direction,
                                    InterlineModel.Analysis analysis, Set<Long> involved) {
        Map<Long, long[]> departures = new HashMap<>();
        for (long id : involved) {
            departures.put(id, Timetable.departures(depots.get(id).effective(), day));
        }
        return new Scorer(forward, reverse, departures, depots, day, direction);
    }

    // ---------------------------------------------------------------- search

    /**
     * Start points: the current delays, plus "ideal slot" assignments — the adjustable
     * depots' natural arrival phases placed at evenly spaced slots in several orders.
     */
    private static List<Map<Long, Long>> starts(List<Long> adjustable, List<Long> variables, long reference,
                                                boolean pinFirst, Map<Long, Long> current,
                                                Map<Long, long[]> departures, List<InterlineModel.Feed> feeds,
                                                Map<Long, Long> period, long day) {
        List<Map<Long, Long>> starts = new ArrayList<>();
        Map<Long, Long> fromCurrent = new LinkedHashMap<>(current);
        if (pinFirst) {
            fromCurrent.put(reference, 0L);
        }
        starts.add(fromCurrent);
        // Natural phase of each depot's first arrival at the entry.
        Map<Long, Long> phase = new HashMap<>();
        for (InterlineModel.Feed feed : feeds) {
            long[] dep = departures.get(feed.depotId());
            if (dep != null && dep.length > 0 && !phase.containsKey(feed.depotId())) {
                phase.put(feed.depotId(), dep[0] + feed.travelMs());
            }
        }
        int n = adjustable.size();
        List<int[]> orders = permutations(n, MAX_STARTS);
        for (int[] order : orders) {
            Map<Long, Long> start = new LinkedHashMap<>(current);
            long base = phase.getOrDefault(adjustable.get(0), 0L) + (pinFirst ? 0 : current.getOrDefault(adjustable.get(0), 0L));
            for (int k = 0; k < n; k++) {
                long id = adjustable.get(k);
                long interval = period.getOrDefault(id, 1000L);
                long want = base + interval * order[k] / n;
                long delay = Math.floorMod(want - phase.getOrDefault(id, 0L), interval);
                start.put(id, delay);
            }
            if (pinFirst) {
                long shift = start.get(reference);
                for (long id : adjustable) {
                    long interval = period.getOrDefault(id, 1000L);
                    start.put(id, Math.floorMod(start.get(id) - shift, interval));
                }
            }
            starts.add(start);
        }
        return starts;
    }

    /** Coordinate descent: coarse scan of each variable over one headway, then a fine scan. */
    private static Map<Long, Long> descend(Scorer scorer, Map<Long, Long> start, List<Long> variables, Map<Long, Long> period) {
        Map<Long, Long> delays = new LinkedHashMap<>(start);
        double best = scorer.score(delays, Map.of());
        for (int round = 0; round < ROUNDS && !variables.isEmpty(); round++) {
            boolean improved = false;
            for (long id : variables) {
                long interval = period.getOrDefault(id, 1000L);
                long keep = delays.getOrDefault(id, 0L);
                long step = Math.max(250, interval / COARSE_STEPS);
                for (long value = 0; value < interval; value += step) {
                    delays.put(id, value);
                    double score = scorer.score(delays, Map.of());
                    if (score < best - 1e-9) {
                        best = score;
                        keep = value;
                        improved = true;
                    }
                }
                long fine = Math.max(100, step / FINE_STEPS);
                long centre = keep;
                for (long value = centre - step; value <= centre + step; value += fine) {
                    long wrapped = Math.floorMod(value, interval);
                    delays.put(id, wrapped);
                    double score = scorer.score(delays, Map.of());
                    if (score < best - 1e-9) {
                        best = score;
                        keep = wrapped;
                        improved = true;
                    }
                }
                delays.put(id, roundTo(keep, 100));
            }
            if (!improved) {
                break;
            }
        }
        return delays;
    }

    /**
     * With no fixed depot to respect, the whole solution may slide by a constant: pick the
     * reference that keeps the total delay smallest without making the spacing worse.
     */
    private static Map<Long, Long> rebase(Scorer scorer, Map<Long, Long> delays, List<Long> adjustable,
                                          Map<Long, Long> period, boolean pinned, double score) {
        if (!pinned) {
            return delays;
        }
        Map<Long, Long> best = delays;
        long bestSum = sum(delays, adjustable);
        for (long pivot : adjustable) {
            long shift = delays.getOrDefault(pivot, 0L);
            Map<Long, Long> moved = new LinkedHashMap<>(delays);
            for (long id : adjustable) {
                moved.put(id, Math.floorMod(delays.getOrDefault(id, 0L) - shift, period.getOrDefault(id, 1000L)));
            }
            long total = sum(moved, adjustable);
            if (total < bestSum && scorer.score(moved, Map.of()) <= score + 0.005) {
                best = moved;
                bestSum = total;
            }
        }
        return best;
    }

    private static void addSites(List<InterlineModel.HoldSite> sites, InterlineModel.Section section,
                                 List<InterlineModel.Feed> feeds) {
        for (InterlineModel.HoldSite hold : section.holds()) {
            boolean served = false;
            for (InterlineModel.Feed feed : feeds) {
                served |= feed.routeId() == hold.forRouteId();
            }
            boolean duplicate = false;
            for (InterlineModel.HoldSite kept : sites) {
                duplicate |= kept.routeId() == hold.routeId() && kept.stopIndex() == hold.stopIndex();
            }
            if (served && !duplicate) {
                sites.add(hold);
            }
        }
    }

    /**
     * Pads (whole seconds, up to the configured maximum) at the stops before the
     * sections. In {@code forward}/{@code reverse} mode they fix the OTHER direction while
     * the chosen one may not get worse; in balance/options mode they improve the balance.
     */
    private static Map<Timetable.PadSite, Long> padSearch(Scorer scorer, Map<Long, Long> delays,
                                                          List<InterlineModel.HoldSite> sites, long maxPad,
                                                          String direction) {
        InterlineModel.Stats[] base = scorer.stats(delays, Map.of());
        double baseF = badness(base[0]);
        double baseR = badness(base[1]);
        double baseWorst = Math.max(baseF, baseR);
        java.util.function.ToDoubleFunction<Map<Timetable.PadSite, Long>> spacing = candidate -> {
            InterlineModel.Stats[] s = scorer.stats(delays, candidate);
            double f = badness(s[0]);
            double r = badness(s[1]);
            return switch (direction) {
                case "forward" -> (s[1] == null ? f : r) + 10 * Math.max(0, f - baseF - 0.01);
                case "reverse" -> f + 10 * Math.max(0, r - baseR - 0.01);
                // Balance: every improvement in either direction counts, as long as the
                // worse direction does not get worse.
                default -> f + r + 10 * Math.max(0, Math.max(f, r) - baseWorst - 0.01);
            };
        };
        // Small cost per second of pad: of two equally even answers, keep riders waiting least.
        java.util.function.ToDoubleFunction<Map<Timetable.PadSite, Long>> objective = candidate -> {
            long total = 0;
            for (long value : candidate.values()) {
                total += value;
            }
            return spacing.applyAsDouble(candidate) + 0.0002 * (total / 1000.0);
        };
        Map<Timetable.PadSite, Long> empty = new LinkedHashMap<>();
        double start = objective.applyAsDouble(empty);

        // 1. The best single pad (the usual answer: one line waits a little longer).
        Map<Timetable.PadSite, Long> pads = new LinkedHashMap<>();
        double best = start;
        for (InterlineModel.HoldSite stop : sites) {
            Timetable.PadSite site = new Timetable.PadSite(stop.routeId(), stop.stopIndex());
            long ceiling = Math.max(0, Math.min(maxPad, 600_000 - stop.dwellMs()));
            Map<Timetable.PadSite, Long> trial = new LinkedHashMap<>();
            for (long value = PAD_STEP_MILLIS; value <= ceiling; value += PAD_STEP_MILLIS) {
                trial.put(site, value);
                double score = objective.applyAsDouble(trial);
                if (score < best - 1e-9) {
                    best = score;
                    pads = new LinkedHashMap<>(trial);
                }
            }
        }
        // 2. Coordinate descent from there, letting further pads join.
        for (int round = 0; round < 3; round++) {
            boolean improved = false;
            for (InterlineModel.HoldSite stop : sites) {
                Timetable.PadSite site = new Timetable.PadSite(stop.routeId(), stop.stopIndex());
                long keep = pads.getOrDefault(site, 0L);
                long ceiling = Math.max(0, Math.min(maxPad, 600_000 - stop.dwellMs()));
                for (long value = 0; value <= ceiling; value += PAD_STEP_MILLIS) {
                    pads.put(site, value);
                    double score = objective.applyAsDouble(pads);
                    if (score < best - 1e-9) {
                        best = score;
                        keep = value;
                        improved = true;
                    }
                }
                pads.put(site, keep);
            }
            if (!improved) {
                break;
            }
        }
        pads.values().removeIf(value -> value <= 0);
        return spacing.applyAsDouble(empty) - spacing.applyAsDouble(pads) >= PAD_MIN_GAIN ? pads : new LinkedHashMap<>();
    }

    // ------------------------------------------------------------ frequencies

    /**
     * Mixed frequencies: what the section would look like with every adjustable depot on
     * ONE slider value — each distinct value in use, plus the one in between — so the
     * player can pick "raise the slow line" or "lower the fast line" in one click (each
     * runs as a target-headway suggestion). Equal frequencies can always be spaced evenly.
     */
    private static JsonArray matchOptions(InterlineModel.Analysis analysis, List<InterlineModel.Feed> feeds,
                                          List<Long> adjustable, List<Long> fixed, Map<Long, long[]> effective,
                                          Set<Long> involved) {
        long day = analysis.gameMillisPerDay();
        java.util.TreeSet<Long> values = new java.util.TreeSet<>();
        for (long id : adjustable) {
            long f = uniformOf(effective.get(id));
            if (f > 0) {
                values.add(f);
            }
        }
        if (values.size() >= 2) {
            long middle = Math.round((values.first() + values.last()) / 2.0);
            values.add(middle);
        }
        JsonArray out = new JsonArray();
        for (long f : values.descendingSet()) {
            long count = 0;
            for (InterlineModel.Feed feed : feeds) {
                long[] values24 = adjustable.contains(feed.depotId()) ? Timetable.uniform(f) : effective.get(feed.depotId());
                count += Timetable.departures(values24, day).length;
            }
            if (count <= 0) {
                continue;
            }
            JsonObject option = new JsonObject();
            option.addProperty("frequency", f);
            option.addProperty("headwayMs", Math.round((double) day / count));
            option.addProperty("depotHeadwayMs", Timetable.headwayFor(f, day));
            out.add(option);
        }
        return out;
    }

    /**
     * Target mode: uniform all-day slider values for the adjustable depots whose combined
     * trains come closest to the target headway at the section's entry, honouring the
     * weights (default: an equal share each, which is also the only split that can be
     * perfectly even).
     */
    private static JsonObject chooseFrequencies(InterlineModel.Analysis analysis, InterlineModel.Section section,
                                                List<InterlineModel.Feed> feeds, List<Long> adjustable,
                                                List<Long> fixed, Request request, Map<Long, long[]> effective,
                                                Map<Long, Long> chosen, List<String> warnings) {
        long day = analysis.gameMillisPerDay();
        int maxF = Math.max(1, analysis.maxFrequency());
        long targetMs = request.targetMs();
        // Arrivals per departure of each adjustable depot (a depot can pass the entry more than once per cycle).
        Map<Long, Integer> perDeparture = new HashMap<>();
        long fixedCount = 0;
        for (InterlineModel.Feed feed : feeds) {
            if (adjustable.contains(feed.depotId())) {
                perDeparture.merge(feed.depotId(), 1, Integer::sum);
            } else {
                fixedCount += Timetable.departures(effective.get(feed.depotId()), day).length;
            }
        }
        long[] countFor = new long[maxF + 1];
        boolean[] clean = new boolean[maxF + 1];
        for (int f = 1; f <= maxF; f++) {
            countFor[f] = Timetable.departures(Timetable.uniform(f), day).length;
            // MTR restarts the timetable at midnight: unless the interval divides the day,
            // the last train and the first one of the next day leave almost together.
            long interval = Timetable.FREQUENCY_BASE_MILLIS / f;
            clean[f] = Timetable.NOMINAL_DAY % interval == 0;
        }
        double weightSum = 0;
        double[] weights = new double[adjustable.size()];
        for (int k = 0; k < weights.length; k++) {
            weights[k] = request.weights().getOrDefault(adjustable.get(k), 1.0);
            weightSum += weights[k];
        }
        boolean equalWeights = true;
        for (double weight : weights) {
            equalWeights &= Math.abs(weight - weights[0]) < 1e-6;
        }
        int n = adjustable.size();
        List<int[]> combos = new ArrayList<>();
        if (n <= 3) {
            int[] f = new int[n];
            Arrays.fill(f, 1);
            while (true) {
                combos.add(f.clone());
                int k = 0;
                while (k < n && ++f[k] > maxF) {
                    f[k] = 1;
                    k++;
                }
                if (k == n) {
                    break;
                }
            }
        } else {
            for (int base = 1; base <= maxF; base++) {
                int[] f = new int[n];
                for (int k = 0; k < n; k++) {
                    f[k] = (int) Math.max(1, Math.min(maxF, Math.round(base * weights[k] * n / weightSum)));
                }
                combos.add(f);
            }
        }
        record Option(int[] f, double headway, double score) {
        }
        List<Option> options = new ArrayList<>();
        final double wSum = weightSum;
        final boolean eq = equalWeights;
        final long fixedArrivals = fixedCount;
        for (int[] f : combos) {
            long count = fixedArrivals;
            int fSum = 0;
            for (int k = 0; k < n; k++) {
                count += countFor[f[k]] * perDeparture.getOrDefault(adjustable.get(k), 1);
                fSum += f[k];
            }
            if (count <= 0) {
                continue;
            }
            double headway = (double) day / count;
            double error = Math.abs(headway - targetMs) / targetMs;
            double mismatch = 0;
            boolean allEqual = true;
            for (int k = 0; k < n; k++) {
                double share = (double) f[k] / fSum - weights[k] / wSum;
                mismatch += share * share;
                allEqual &= f[k] == f[0];
            }
            int unclean = 0;
            for (int value : f) {
                unclean += clean[value] ? 0 : 1;
            }
            // Unequal frequencies can never be evenly spaced: only worth it when an equal
            // split misses the target by a lot more.
            double score = error + 0.5 * mismatch + (eq && !allEqual ? 0.15 : 0) + 0.04 * unclean;
            options.add(new Option(f, headway, score));
        }
        options.sort(Comparator.comparingDouble(Option::score));
        if (options.isEmpty()) {
            warnings.add("No frequency combination reaches that headway.");
            return new JsonObject();
        }
        Option best = options.get(0);
        for (int k = 0; k < n; k++) {
            chosen.put(adjustable.get(k), (long) best.f()[k]);
        }
        double error = Math.abs(best.headway() - targetMs) / targetMs;
        if (error > 0.05) {
            warnings.add(String.format(Locale.ROOT, "The closest achievable headway is %s (target %s) — MTR frequencies come in steps of %s per depot.",
                    duration(Math.round(best.headway())), duration(targetMs), duration(day / 6)));
        }
        boolean allEqual = true;
        for (int value : best.f()) {
            allEqual &= value == best.f()[0];
        }
        for (int value : best.f()) {
            if (!clean[value]) {
                warnings.add("Frequency " + value + " does not divide the day evenly, so once a day two trains leave almost together at midnight.");
                break;
            }
        }
        if (!allEqual && n > 1) {
            warnings.add("The depots run different frequencies, so the trains cannot be perfectly evenly spaced.");
        }
        if (!fixed.isEmpty()) {
            warnings.add("Depots that are kept as they are still count towards the headway.");
        }
        JsonObject json = new JsonObject();
        json.addProperty("targetMs", targetMs);
        json.addProperty("achievedMs", Math.round(best.headway()));
        JsonArray alternatives = new JsonArray();
        Set<Long> seen = new java.util.HashSet<>();
        for (Option option : options) {
            long rounded = Math.round(option.headway());
            if (!seen.add(rounded)) {
                continue;
            }
            JsonObject alt = new JsonObject();
            JsonObject fs = new JsonObject();
            for (int k = 0; k < n; k++) {
                fs.addProperty(Long.toString(adjustable.get(k)), option.f()[k]);
            }
            alt.add("frequencies", fs);
            alt.addProperty("headwayMs", rounded);
            alternatives.add(alt);
            if (alternatives.size() >= 4) {
                break;
            }
        }
        json.add("alternatives", alternatives);
        return json;
    }

    // ---------------------------------------------------------------- helpers

    private static List<InterlineModel.Feed> timedFeeds(InterlineModel.Section section,
                                                        Map<Long, InterlineModel.Depot> depots, List<String> warnings) {
        List<InterlineModel.Feed> feeds = new ArrayList<>();
        for (InterlineModel.Feed feed : section.feeds()) {
            InterlineModel.Depot depot = depots.get(feed.depotId());
            if (depot == null) {
                continue;
            }
            if (!depot.tunable()) {
                warnings.add(depot.name() + " (" + depot.reason() + ") cannot be adjusted and is left out.");
                continue;
            }
            if (feed.travelMs() < 0) {
                warnings.add(depot.name() + " has no scheduled stop at the entry (not generated yet, or its trains skip it) and is left out.");
                continue;
            }
            feeds.add(feed);
        }
        return feeds;
    }

    private static List<int[]> permutations(int n, int limit) {
        List<int[]> out = new ArrayList<>();
        int[] current = new int[n];
        for (int i = 0; i < n; i++) {
            current[i] = i;
        }
        permute(current, 0, out, limit);
        return out;
    }

    private static void permute(int[] values, int index, List<int[]> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (index == values.length) {
            out.add(values.clone());
            return;
        }
        for (int i = index; i < values.length; i++) {
            swap(values, index, i);
            permute(values, index + 1, out, limit);
            swap(values, index, i);
        }
    }

    private static void swap(int[] values, int a, int b) {
        int t = values[a];
        values[a] = values[b];
        values[b] = t;
    }

    private static boolean same(Map<Long, Long> a, Map<Long, Long> b, Map<Long, Long> period) {
        for (Map.Entry<Long, Long> entry : period.entrySet()) {
            long interval = entry.getValue();
            long x = Math.floorMod(a.getOrDefault(entry.getKey(), 0L), interval);
            long y = Math.floorMod(b.getOrDefault(entry.getKey(), 0L), interval);
            long diff = Math.abs(x - y);
            diff = Math.min(diff, interval - diff);
            if (diff > Math.max(500, interval / 50)) {
                return false;
            }
        }
        return true;
    }

    private static long sum(Map<Long, Long> delays, List<Long> ids) {
        long total = 0;
        for (long id : ids) {
            total += delays.getOrDefault(id, 0L);
        }
        return total;
    }

    private static long uniformOf(long[] values) {
        long first = values[0];
        for (long value : values) {
            if (value != first) {
                return -1;
            }
        }
        return first;
    }

    private static long roundTo(long value, long step) {
        return Math.round((double) value / step) * step;
    }

    private static boolean contains(long[] values, long value) {
        for (long v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray ids(List<Long> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(Long.toString(id)));
        return array;
    }

    static JsonObject pairJson(InterlineModel.Stats[] stats) {
        JsonObject json = new JsonObject();
        json.add("fwd", InterlineJson.stats(stats[0]));
        if (stats[1] != null) {
            json.add("rev", InterlineJson.stats(stats[1]));
        }
        return json;
    }

    private static JsonObject error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message);
        return json;
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    static String duration(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
