package com.stationannouncer.mtraddon.interline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The one wire format for the interline tooling, shared by the in-game screen (inside
 * an S2C packet) and the dispatch web page ({@code /dispatch/api/interline}). Every id is
 * a STRING — MTR ids are random 64-bit longs and JavaScript numbers are not.
 */
public final class InterlineJson {
    private InterlineJson() {
    }

    public static JsonObject analysis(InterlineModel.Analysis analysis) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("dimension", analysis.dimension());
        root.addProperty("gameMillisPerDay", analysis.gameMillisPerDay());
        root.addProperty("builtAt", analysis.builtAt());
        root.addProperty("timeMoving", analysis.timeMoving());
        root.addProperty("maxFrequency", analysis.maxFrequency());
        root.addProperty("maxPadMs", analysis.maxPadMs());

        JsonArray depots = new JsonArray();
        analysis.depots().values().stream()
                .sorted(java.util.Comparator.comparing(InterlineModel.Depot::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(depot -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("id", Long.toString(depot.id()));
                    json.addProperty("name", depot.name());
                    json.addProperty("color", depot.color());
                    json.add("routes", ids(depot.routeIds()));
                    json.add("freq", longs(depot.frequencies()));
                    json.add("effFreq", longs(depot.effective()));
                    json.addProperty("uniformFreq", depot.uniformFrequency());
                    json.addProperty("intervalMs", depot.intervalMs());
                    json.addProperty("delayMs", depot.delayMs());
                    json.addProperty("appliedMs", depot.appliedMs());
                    json.addProperty("tunable", depot.tunable());
                    json.addProperty("reason", depot.reason());
                    json.addProperty("sidings", depot.sidings());
                    json.add("groups", ids(depot.groupIds()));
                    json.add("mergedStarts", ids(depot.mergedStartRoutes()));
                    depots.add(json);
                });
        root.add("depots", depots);

        JsonArray routes = new JsonArray();
        analysis.routes().values().stream()
                .sorted(java.util.Comparator.comparing(InterlineModel.Route::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(route -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("id", Long.toString(route.id()));
                    json.addProperty("name", route.name());
                    json.addProperty("number", route.number());
                    json.addProperty("color", route.color());
                    JsonArray stations = new JsonArray();
                    for (String name : route.stationNames()) {
                        stations.add(name);
                    }
                    json.add("stations", stations);
                    routes.add(json);
                });
        root.add("routes", routes);

        JsonArray groups = new JsonArray();
        for (InterlineModel.Group group : analysis.groups()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", Long.toString(group.id()));
            json.addProperty("name", group.name());
            json.add("depots", ids(group.depotIds()));
            groups.add(json);
        }
        root.add("groups", groups);

        JsonArray sections = new JsonArray();
        for (InterlineModel.Section section : analysis.sections()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", section.id());
            json.addProperty("name", section.name());
            json.addProperty("reverse", section.reverseId());
            json.add("routes", ids(section.routeIds()));
            json.add("platforms", ids(section.platformIds()));
            JsonArray stations = new JsonArray();
            for (String name : section.stationNames()) {
                stations.add(name);
            }
            json.add("stations", stations);
            JsonArray feeds = new JsonArray();
            for (InterlineModel.Feed feed : section.feeds()) {
                JsonObject f = new JsonObject();
                f.addProperty("depot", Long.toString(feed.depotId()));
                f.addProperty("route", Long.toString(feed.routeId()));
                f.addProperty("travelMs", feed.travelMs());
                f.addProperty("spreadMs", feed.spreadMs());
                f.addProperty("routePos", feed.routePos());
                f.addProperty("entryIndex", feed.entryIndex());
                f.add("stationArr", longs(feed.stationArr()));
                f.add("stationDep", longs(feed.stationDep()));
                f.addProperty("approachDep", feed.approachDep());
                feeds.add(f);
            }
            json.add("feeds", feeds);
            JsonArray prev = new JsonArray();
            for (InterlineModel.PrevStop stop : section.prevStops()) {
                JsonObject p = new JsonObject();
                p.addProperty("route", Long.toString(stop.routeId()));
                p.addProperty("platform", Long.toString(stop.platformId()));
                p.addProperty("station", stop.stationName());
                p.addProperty("dwellMs", stop.dwellMs());
                p.addProperty("overridden", stop.overridden());
                p.addProperty("index", stop.index());
                prev.add(p);
            }
            json.add("prev", prev);
            JsonArray holds = new JsonArray();
            for (InterlineModel.HoldSite hold : section.holds()) {
                JsonObject h = new JsonObject();
                h.addProperty("route", Long.toString(hold.routeId()));
                h.addProperty("stopIndex", hold.stopIndex());
                h.addProperty("platform", Long.toString(hold.platformId()));
                h.addProperty("station", hold.stationName());
                h.addProperty("dwellMs", hold.dwellMs());
                h.addProperty("overridden", hold.overridden());
                h.addProperty("kind", hold.kind());
                h.addProperty("forRoute", Long.toString(hold.forRouteId()));
                holds.add(h);
            }
            json.add("holds", holds);
            json.add("scheduled", stats(section.scheduled()));
            InterlineModel.Measured measured = section.measured();
            JsonObject m = new JsonObject();
            m.addProperty("samples", measured.samples());
            m.addProperty("avgMs", measured.avgMs());
            m.addProperty("minMs", measured.minMs());
            m.addProperty("maxMs", measured.maxMs());
            m.addProperty("irregularity", measured.irregularity());
            json.add("measured", m);
            sections.add(json);
        }
        root.add("sections", sections);
        return root;
    }

    public static JsonObject stats(InterlineModel.Stats stats) {
        JsonObject json = new JsonObject();
        json.addProperty("count", stats.count());
        json.addProperty("meanMs", stats.meanMs());
        json.addProperty("minMs", stats.minMs());
        json.addProperty("maxMs", stats.maxMs());
        json.addProperty("evenness", stats.evenness());
        json.addProperty("breaks", stats.breaks());
        return json;
    }

    public static JsonObject error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message);
        return json;
    }

    private static JsonArray ids(long[] values) {
        JsonArray array = new JsonArray();
        for (long value : values) {
            array.add(Long.toString(value));
        }
        return array;
    }

    private static JsonArray longs(long[] values) {
        JsonArray array = new JsonArray();
        for (long value : values) {
            array.add(value);
        }
        return array;
    }
}
