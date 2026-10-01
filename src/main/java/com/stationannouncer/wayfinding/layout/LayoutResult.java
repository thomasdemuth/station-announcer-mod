package com.stationannouncer.wayfinding.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One station's scanned walking layout — PURE JAVA data. Anchors are the
 * places a walk starts or ends (exit markers, platforms, street openings the
 * scan found without a marker); links are the walks between them, each as
 * legs a rider can be told about ("stairs down 6 m", "fare control", "lift")
 * plus a simplified path to draw.
 *
 * <p>Links are stored one way (exit → platform, platform → platform with the
 * lower index first, opening → platform); the reverse walk is the same path
 * backwards, which every consumer derives.</p>
 */
public final class LayoutResult {
    /** 2 = anchors carry access ({@link Access}), fare-control anchors, legs carry {@code at}, region/area. */
    public static final int FORMAT = 2;

    public long stationId;
    public String stationName = "";
    public long scannedAt;
    public long millis;
    public int cells;
    public int nodes;
    public final List<Anchor> anchors = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    /** Platform id → reachable without stairs/escalators from the street (an exit or an opening). */
    public final Map<Long, Boolean> platformStepFree = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    /** Anchor id → what riders can do from there (exits, openings, fare control). */
    public final Map<String, Access> access = new LinkedHashMap<>();
    /** The scanned box, absolute block coordinates inclusive: {minX, minY, minZ, maxX, maxY, maxZ}; null in tests. */
    public int[] region;
    /** How far the scan reached past the MTR station area, in blocks. */
    public int margin;
    /** The MTR station area (x/z, inclusive): {minX, minZ, maxX, maxZ}. */
    public long[] area;

    /** kind: exit | platform | opening | fare (a group of fare gates). */
    public record Anchor(String id, String kind, String name, double x, double y, double z) {
    }

    /**
     * kind: walk | stairs | escalator | lift | fare | emergency. {@code seconds} is time on top of
     * walking (lift, gate); {@code at} names the anchor a fare/emergency leg passes ("fare:0"), else null.
     */
    public record Leg(String kind, double meters, double dy, double seconds, String at) {
        public Leg(String kind, double meters, double dy, double seconds) {
            this(kind, meters, dy, seconds, null);
        }
    }

    /**
     * What a rider can do from one street-side anchor (an exit, an opening, a fare-control
     * entrance), or — for fare control — who walks through it.
     */
    public static final class Access {
        /** all | some | none: platforms reachable without stairs/escalators, of every boardable one; null = reaches none. */
        public String stepFree;
        /** Some step-free walk from here rides a lift (the exit is "step-free by lift"). */
        public boolean lift;
        /** Platform ids reachable step-free from here. */
        public final List<Long> stepFreeTo = new ArrayList<>();
        /** Platform ids reachable at all from here. */
        public final List<Long> reaches = new ArrayList<>();
        /** Fare control only: it stands in for the street (the station has no exit and no opening). */
        public boolean entrance;
        /** Fare control only: the anchors whose walks pass through it. */
        public final List<String> usedBy = new ArrayList<>();
    }

    public static final class Link {
        public String from;
        public String to;
        public double meters;
        public double extraSeconds;
        public boolean stepFree;
        public final List<Leg> legs = new ArrayList<>();
        public final List<double[]> path = new ArrayList<>();
        /** When this link needs stairs: the step-free alternative (via a lift / ramp), if there is one. */
        public Link stepFreeAlt;
    }

    public JsonObject toJson(boolean withPaths) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("station", Long.toString(stationId));
        json.addProperty("name", stationName);
        json.addProperty("scannedAt", scannedAt);
        json.addProperty("millis", millis);
        json.addProperty("cells", cells);
        json.addProperty("nodes", nodes);
        JsonArray anchorsJson = new JsonArray();
        for (Anchor anchor : anchors) {
            JsonObject a = new JsonObject();
            a.addProperty("id", anchor.id());
            a.addProperty("kind", anchor.kind());
            a.addProperty("name", anchor.name());
            a.add("pos", vec(anchor.x(), anchor.y(), anchor.z()));
            Access info = access.get(anchor.id());
            if (info != null) {
                if (info.stepFree != null) {
                    a.addProperty("stepFree", info.stepFree);
                }
                if (info.lift) {
                    a.addProperty("lift", true);
                }
                if (info.entrance) {
                    a.addProperty("entrance", true);
                }
                if (!info.stepFreeTo.isEmpty()) {
                    a.add("stepFreeTo", ids(info.stepFreeTo));
                }
                if (!info.reaches.isEmpty()) {
                    a.add("reaches", ids(info.reaches));
                }
                if (!info.usedBy.isEmpty()) {
                    JsonArray used = new JsonArray();
                    info.usedBy.forEach(used::add);
                    a.add("usedBy", used);
                }
            }
            anchorsJson.add(a);
        }
        json.add("anchors", anchorsJson);
        JsonArray linksJson = new JsonArray();
        for (Link link : links) {
            linksJson.add(linkJson(link, withPaths));
        }
        json.add("links", linksJson);
        JsonObject stepFree = new JsonObject();
        platformStepFree.forEach((id, value) -> stepFree.addProperty(Long.toString(id), value));
        json.add("platformStepFree", stepFree);
        JsonArray warningsJson = new JsonArray();
        warnings.forEach(warningsJson::add);
        json.add("warnings", warningsJson);
        if (region != null) {
            JsonObject r = new JsonObject();
            r.add("min", vec(region[0], region[1], region[2]));
            r.add("max", vec(region[3], region[4], region[5]));
            r.addProperty("margin", margin);
            json.add("region", r);
        }
        if (area != null) {
            JsonArray a = new JsonArray(4);
            for (long v : area) {
                a.add(v);
            }
            json.add("area", a);
        }
        return json;
    }

    private static JsonArray ids(List<Long> ids) {
        JsonArray out = new JsonArray(ids.size());
        ids.forEach(id -> out.add(Long.toString(id)));
        return out;
    }

    private static JsonObject linkJson(Link link, boolean withPaths) {
        JsonObject l = new JsonObject();
        l.addProperty("from", link.from);
        l.addProperty("to", link.to);
        l.addProperty("meters", round(link.meters));
        l.addProperty("extraSeconds", round(link.extraSeconds));
        l.addProperty("stepFree", link.stepFree);
        JsonArray legs = new JsonArray();
        for (Leg leg : link.legs) {
            JsonObject g = new JsonObject();
            g.addProperty("kind", leg.kind());
            g.addProperty("meters", round(leg.meters()));
            g.addProperty("dy", round(leg.dy()));
            if (leg.seconds() > 0) {
                g.addProperty("seconds", round(leg.seconds()));
            }
            if (leg.at() != null) {
                g.addProperty("at", leg.at());
            }
            legs.add(g);
        }
        l.add("legs", legs);
        if (withPaths) {
            JsonArray path = new JsonArray();
            for (double[] p : link.path) {
                path.add(vec(p[0], p[1], p[2]));
            }
            l.add("path", path);
        }
        if (link.stepFreeAlt != null) {
            l.add("stepFreeAlt", linkJson(link.stepFreeAlt, withPaths));
        }
        return l;
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray(3);
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
