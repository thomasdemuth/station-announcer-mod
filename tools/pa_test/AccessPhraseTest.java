import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stationannouncer.pa.AccessPhrase;

/**
 * The {access} sentence (pure Java + Gson): hand-made layout JSON for the wording rules,
 * and LayoutSolverTest's synthetic subway (with and without its lift) end to end.
 * Run from the repo root:
 *   javac -d OUT -cp gson.jar src/main/java/com/stationannouncer/wayfinding/layout/{CellInfo,LayoutInput,LayoutResult,LayoutSolver}.java \
 *       src/main/java/com/stationannouncer/pa/AccessPhrase.java tools/layout_test/LayoutSolverTest.java tools/pa_test/AccessPhraseTest.java
 *   java -cp OUT:gson.jar AccessPhraseTest
 */
public class AccessPhraseTest {
    static int fails;

    static void check(String name, String got, String want) {
        boolean ok = want.equals(got);
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "\n     got:  " + got + "\n     want: " + want));
        if (!ok) {
            fails++;
        }
    }

    // a 30-block platform along +x at z 10: rear x 0, front x 30
    static final double[] REAR = {0, 60, 10};
    static final double[] FRONT = {30, 60, 10};

    static JsonObject layout(JsonObject... links) {
        JsonObject root = new JsonObject();
        JsonArray anchors = new JsonArray();
        JsonObject platform = new JsonObject();
        platform.addProperty("id", "platform:1");
        platform.addProperty("kind", "platform");
        anchors.add(platform);
        root.add("anchors", anchors);
        JsonArray array = new JsonArray();
        for (JsonObject link : links) {
            array.add(link);
        }
        root.add("links", array);
        return root;
    }

    /** A walk ending at (x, 60, 10); legs as kind/dy pairs. */
    static JsonObject walk(String from, boolean stepFree, double endX, Object... legs) {
        JsonObject link = new JsonObject();
        link.addProperty("from", from);
        link.addProperty("to", "platform:1");
        link.addProperty("stepFree", stepFree);
        JsonArray legArray = new JsonArray();
        for (int i = 0; i < legs.length; i += 2) {
            JsonObject leg = new JsonObject();
            leg.addProperty("kind", (String) legs[i]);
            leg.addProperty("dy", ((Number) legs[i + 1]).doubleValue());
            legArray.add(leg);
        }
        link.add("legs", legArray);
        JsonArray path = new JsonArray();
        JsonArray start = new JsonArray();
        start.add(endX);
        start.add(70);
        start.add(0);
        JsonArray end = new JsonArray();
        end.add(endX);
        end.add(60.05);
        end.add(9);
        path.add(start);
        path.add(end);
        link.add("path", path);
        return link;
    }

    static String say(JsonObject root, boolean known) {
        return AccessPhrase.phrase(root, 1, REAR, FRONT, known);
    }

    public static void main(String[] args) {
        check("lift at the rear", say(layout(walk("exit:A", true, 3, "walk", 0, "lift", -10, "walk", 0)), true),
                "The elevator is at the rear of the platform.");
        check("ramp in the middle", say(layout(walk("exit:A", true, 15, "walk", -1.5, "walk", -2)), true),
                "The ramp is at the center of the platform.");
        check("level entrance at the front", say(layout(walk("opening:0", true, 28, "walk", 0.3)), true),
                "The step-free entrance is at the front of the platform.");
        check("two elevators", say(layout(walk("exit:A", true, 2, "lift", -10), walk("exit:B", true, 29, "lift", -10)), true),
                "Elevators are at the front and rear of the platform.");
        check("same lift from two exits counts once",
                say(layout(walk("exit:A", true, 2, "lift", -10), walk("exit:B", true, 3, "lift", -10)), true),
                "The elevator is at the rear of the platform.");
        JsonObject stairsWithAlt = walk("exit:A", false, 5, "stairs", -10);
        stairsWithAlt.add("stepFreeAlt", walk("exit:A", true, 25, "walk", 0, "lift", -10));
        check("step-free alternative of a stairs walk + a ramp",
                say(layout(stairsWithAlt, walk("exit:B", true, 15, "walk", -3)), true),
                "The elevator is at the front of the platform, and the ramp is at the center.");
        check("stairs only", say(layout(walk("exit:A", false, 5, "stairs", -10)), true), AccessPhrase.NOT_ACCESSIBLE);
        check("transfers from another platform are not access",
                say(layout(walk("platform:2", true, 15, "lift", -5)), true), AccessPhrase.NOT_ACCESSIBLE);
        check("direction unknown: one end", say(layout(walk("exit:A", true, 3, "lift", -10)), false),
                "The elevator is at one end of the platform.");
        check("direction unknown: both ends",
                say(layout(walk("exit:A", true, 2, "lift", -10), walk("exit:B", true, 29, "lift", -10)), false),
                "Elevators are at both ends of the platform.");
        check("platform the scan does not know", AccessPhrase.phrase(layout(), 2, REAR, FRONT, true), "");
        check("no layout", AccessPhrase.phrase((String) null, 1, REAR, FRONT, true), "");

        // ---- end to end through the real solver: platform x 24.5..33.5 along z 10.5, lift at x 32
        String withLift = LayoutSolverTest.subway(true).solve().toJson(true).toString();
        String noLift = LayoutSolverTest.subway(false).solve().toJson(true).toString();
        double[] west = {24.5, 58, 10.5};
        double[] east = {33.5, 58, 10.5};
        check("solver: lift at the east end, trains run east", AccessPhrase.phrase(withLift, 1, west, east, true),
                "The elevator is at the front of the platform.");
        check("solver: ...trains run west", AccessPhrase.phrase(withLift, 1, east, west, true),
                "The elevator is at the rear of the platform.");
        check("solver: stairs only", AccessPhrase.phrase(noLift, 1, west, east, true), AccessPhrase.NOT_ACCESSIBLE);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
