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
    public static final int FORMAT = 1;

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

    /** kind: exit | platform | opening. */
    public record Anchor(String id, String kind, String name, double x, double y, double z) {
    }

    /** kind: walk | stairs | escalator | lift | fare. {@code seconds} is time on top of walking (lift, gate). */
    public record Leg(String kind, double meters, double dy, double seconds) {
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
        return json;
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
