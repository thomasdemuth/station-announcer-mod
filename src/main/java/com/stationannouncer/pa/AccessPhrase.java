package com.stationannouncer.pa;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code {access}} sentence for one platform, worked out from a station's
 * layout scan (the JSON {@code LayoutResult.toJson(true)} writes). PURE JAVA (only
 * Gson), so it is unit-tested against the layout solver's synthetic stations.
 *
 * <p>Every walk from the street side (an exit, a suggested opening or a fare-control
 * entrance — never a platform-to-platform transfer) that is step-free, or the
 * step-free alternative of one that is not, ends where it first reaches the
 * platform's boarding zone: that spot is where the lift or ramp lands. A walk with a
 * {@code lift} leg is an ELEVATOR; one that still climbs at least a block without a
 * lift is a RAMP; anything else is a STEP-FREE ENTRANCE (level with the street). The
 * landing is projected onto the platform's track, rear end to front end in the
 * train's direction, and the thirds read front / center / rear.</p>
 *
 * <p>Results: "The elevator is at the rear of the platform." · "Elevators are at the
 * front and rear of the platform, and the ramp is at the center." · "This platform is
 * not accessible." (scanned, nothing step-free reaches it) · "" (the scan does not
 * know this platform).</p>
 */
public final class AccessPhrase {
    /** Climb (blocks) a lift-free step-free walk needs to count as a ramp rather than level. */
    static final double RAMP_CLIMB = 1.0;
    public static final String NOT_ACCESSIBLE = "This platform is not accessible.";

    public enum Kind {
        ELEVATOR("elevator", "elevators"),
        RAMP("ramp", "ramps"),
        ENTRANCE("step-free entrance", "step-free entrances");

        final String one;
        final String many;

        Kind(String one, String many) {
            this.one = one;
            this.many = many;
        }
    }

    /** Where along the platform. END_A/END_B = the two ends when the direction is unknown. */
    public enum Zone { FRONT, CENTER, REAR, END_A, END_B }

    /** One place step-free access lands on the platform. */
    public record Landing(Kind kind, double x, double y, double z) {
    }

    private AccessPhrase() {
    }

    /**
     * @param rear           the platform end the train leaves behind (x, y, z)
     * @param front          the end it travels toward
     * @param directionKnown false = which end is the front is not known (rear/front are just the ends)
     */
    public static String phrase(String layoutJson, long platformId, double[] rear, double[] front, boolean directionKnown) {
        if (layoutJson == null || layoutJson.isEmpty()) {
            return "";
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(layoutJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return "";
        }
        return phrase(root, platformId, rear, front, directionKnown);
    }

    public static String phrase(JsonObject root, long platformId, double[] rear, double[] front, boolean directionKnown) {
        String target = "platform:" + platformId;
        if (!hasAnchor(root, target)) {
            return ""; // scanned before this platform existed: say nothing
        }
        List<Landing> landings = landings(root, target);
        if (landings.isEmpty()) {
            return NOT_ACCESSIBLE;
        }
        Map<Kind, Set<Zone>> zones = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            for (Landing landing : landings) {
                if (landing.kind() == kind) {
                    zones.computeIfAbsent(kind, k -> EnumSet.noneOf(Zone.class))
                            .add(zone(landing, rear, front, directionKnown));
                }
            }
        }
        return sentence(zones);
    }

    /** Every street-side step-free landing on the platform (duplicates included). */
    public static List<Landing> landings(JsonObject root, String platformAnchor) {
        List<Landing> out = new ArrayList<>();
        JsonArray links = root.has("links") ? root.getAsJsonArray("links") : new JsonArray();
        for (JsonElement element : links) {
            JsonObject link = element.getAsJsonObject();
            if (!platformAnchor.equals(str(link, "to")) || str(link, "from").startsWith("platform:")) {
                continue;
            }
            JsonObject stepFree = bool(link, "stepFree") ? link
                    : link.has("stepFreeAlt") ? link.getAsJsonObject("stepFreeAlt") : null;
            if (stepFree == null) {
                continue;
            }
            JsonArray path = stepFree.has("path") ? stepFree.getAsJsonArray("path") : null;
            if (path == null || path.isEmpty()) {
                continue;
            }
            JsonArray last = path.get(path.size() - 1).getAsJsonArray();
            out.add(new Landing(kind(stepFree), last.get(0).getAsDouble(), last.get(1).getAsDouble(),
                    last.get(2).getAsDouble()));
        }
        return out;
    }

    static Kind kind(JsonObject link) {
        double climb = 0;
        JsonArray legs = link.has("legs") ? link.getAsJsonArray("legs") : new JsonArray();
        for (JsonElement element : legs) {
            JsonObject leg = element.getAsJsonObject();
            String kind = str(leg, "kind");
            if ("lift".equals(kind)) {
                return Kind.ELEVATOR;
            }
            if ("walk".equals(kind) && leg.has("dy")) {
                climb += Math.abs(leg.get("dy").getAsDouble());
            }
        }
        return climb >= RAMP_CLIMB ? Kind.RAMP : Kind.ENTRANCE;
    }

    /** Thirds of the platform along rear → front (horizontal projection). */
    static Zone zone(Landing landing, double[] rear, double[] front, boolean directionKnown) {
        double dx = front[0] - rear[0];
        double dz = front[2] - rear[2];
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq < 1e-6 ? 0.5
                : ((landing.x() - rear[0]) * dx + (landing.z() - rear[2]) * dz) / lengthSq;
        if (t > 2.0 / 3.0) {
            return directionKnown ? Zone.FRONT : Zone.END_B;
        }
        if (t < 1.0 / 3.0) {
            return directionKnown ? Zone.REAR : Zone.END_A;
        }
        return Zone.CENTER;
    }

    static String sentence(Map<Kind, Set<Zone>> zones) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Kind, Set<Zone>> entry : zones.entrySet()) {
            Set<Zone> where = entry.getValue();
            boolean plural = where.size() > 1;
            String noun = plural ? entry.getKey().many : entry.getKey().one;
            String verb = plural ? "are" : "is";
            String place = places(where);
            if (first) {
                out.append(plural ? capitalize(noun) : "The " + noun).append(' ').append(verb)
                        .append(" at ").append(place).append(" of the platform");
                first = false;
            } else {
                out.append(", and ").append(plural ? noun : "the " + noun).append(' ').append(verb)
                        .append(" at ").append(place);
            }
        }
        return out.append('.').toString();
    }

    private static String places(Set<Zone> where) {
        boolean bothEnds = where.contains(Zone.END_A) && where.contains(Zone.END_B);
        List<String> words = new ArrayList<>();
        if (where.contains(Zone.FRONT)) {
            words.add("the front");
        }
        if (bothEnds) {
            words.add("both ends");
        } else if (where.contains(Zone.END_A) || where.contains(Zone.END_B)) {
            words.add("one end");
        }
        if (where.contains(Zone.CENTER)) {
            words.add("the center");
        }
        if (where.contains(Zone.REAR)) {
            words.add("the rear");
        }
        if (words.size() == 1) {
            return words.get(0);
        }
        if (words.size() == 2) {
            // "the front and the rear" reads better as "the front and rear"
            return words.get(0) + " and " + words.get(1).replaceFirst("^the ", "");
        }
        return String.join(", ", words.subList(0, words.size() - 1)).replace(", the ", ", ")
                + " and " + words.get(words.size() - 1).replaceFirst("^the ", "");
    }

    private static boolean hasAnchor(JsonObject root, String id) {
        JsonArray anchors = root.has("anchors") ? root.getAsJsonArray("anchors") : new JsonArray();
        for (JsonElement element : anchors) {
            if (id.equals(str(element.getAsJsonObject(), "id"))) {
                return true;
            }
        }
        return false;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }
}
