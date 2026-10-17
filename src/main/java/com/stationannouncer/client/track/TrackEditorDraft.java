package com.stationannouncer.client.track;

import com.google.gson.Gson;
import com.stationannouncer.mtr.track.TrackRailMathAccess;
import com.stationannouncer.mtr.track.TrackRails;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.core.data.Rail;
import org.mtr.mod.client.MinecraftClientData;

/**
 * One rail's editing state inside {@link TrackEditorScreen}: the profile as opened, the working
 * draft, its built {@link TrackPath}, MTR's own settings (shape, vertical radius) as edited, and
 * MTR's original shape (the faint ghost in the plan, and what "Reset to MTR shape" goes back to).
 *
 * The rail object itself is re-fetched by hex id whenever an MTR packet is built ({@link #freshRail}):
 * a style flip or another player's edit replaces the {@code Rail} in MTR's client data, and
 * {@code Rail.copy} of a stale instance would silently undo that change.
 */
@Environment(EnvType.CLIENT)
final class TrackEditorDraft {
    private static final Gson GSON = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();

    final String hexId;
    final String key;
    /** Node A..B (canonical) = {ax, ay, az, bx, by, bz}. */
    final long[] nodes;
    /** True when the clicked node is this rail's A end (else it is the B end). */
    final boolean nodeIsA;
    final boolean hadCustom;
    final TrackProfile opened;
    private final String openedJson;
    /** MTR's own geometry imported (null if it could not be imported). */
    final TrackProfile mtrImport;
    private final String mtrImportJson;
    final String openedShape;
    final double openedVerticalRadius;
    final double speedKmh;
    /** MTR's original centre line as x,z pairs (plan ghost). */
    final double[] ghost;

    TrackProfile profile;
    TrackPath path;
    String mtrShape;
    double verticalRadius;
    /** "Reset to MTR shape" was used: Save drops the custom profile if the draft is still MTR's shape. */
    boolean resetRequested;
    String solveMessage = "";

    // derived on rebuild
    double[] polyX = new double[0];
    double[] polyZ = new double[0];
    double[] polyS = new double[0];
    double maxGradePercent;
    double maxTilt;
    double minRadius;
    private String profileJson;

    private TrackEditorDraft(Rail rail, String key, long[] nodes, boolean nodeIsA, TrackProfile opened, TrackProfile mtrImport,
                             String shape, double verticalRadius, double speedKmh, double[] ghost) {
        hexId = rail.getHexId();
        this.key = key;
        this.nodes = nodes;
        this.nodeIsA = nodeIsA;
        hadCustom = TrackRegistry.client(key) != null;
        this.opened = opened.copy();
        openedJson = GSON.toJson(this.opened);
        this.mtrImport = mtrImport;
        mtrImportJson = mtrImport == null ? null : GSON.toJson(mtrImport);
        openedShape = shape;
        openedVerticalRadius = verticalRadius;
        mtrShape = shape;
        this.verticalRadius = verticalRadius;
        this.speedKmh = speedKmh;
        this.ghost = ghost;
        profile = opened.copy();
        rebuild();
    }

    /**
     * Builds the draft for {@code rail} seen from node (x, y, z), or null if the rail cannot be edited
     * (cable, or the track integration is missing).
     */
    static TrackEditorDraft of(Rail rail, long x, long y, long z) {
        try {
            if (rail.railMath.getShape() == Rail.Shape.CABLE) {
                return null;
            }
            final TrackRailMathAccess access = TrackRails.access(rail);
            final long[] nodes = access.stationannouncer$nodes().clone();
            final String key = TrackRails.key(rail);
            final boolean nodeIsA = nodes[0] == x && nodes[1] == y && nodes[2] == z;
            final TrackProfile mtr = importMtr(access);
            // editableProfile = the active custom profile (previews were cleared by the screen) else MTR's import
            final TrackProfile opened = TrackRails.editableProfile(rail);
            if (opened == null) {
                return null;
            }
            final double speed = Math.max(rail.getSpeedLimitKilometersPerHour(false), rail.getSpeedLimitKilometersPerHour(true));
            return new TrackEditorDraft(rail, key, nodes, nodeIsA, opened, mtr, access.stationannouncer$shapeName(),
                access.stationannouncer$verticalRadiusArg(), speed, ghost(access));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** MTR's own geometry as a profile (ignoring any custom path) — what "Reset to MTR shape" goes back to; null if it cannot be imported. */
    static TrackProfile importMtr(TrackRailMathAccess access) {
        return TrackRails.importMtr(access);
    }

    private static double[] ghost(TrackRailMathAccess access) {
        final double length = access.stationannouncer$mtrLength();
        final int n = Math.max(8, Math.min(400, (int) Math.ceil(length / 0.5)));
        final double[] out = new double[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            final double[] p = access.stationannouncer$mtrPosition(length * i / n, false);
            out[i * 2] = p[0];
            out[i * 2 + 1] = p[2];
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    void setProfile(TrackProfile next, String message) {
        profile = next;
        solveMessage = message == null ? "" : message;
        rebuild();
    }

    void rebuild() {
        path = TrackPath.build(profile, mtrShape, verticalRadius);
        profileJson = GSON.toJson(profile);
        final double length = path.length();
        final int n = Math.max(16, Math.min(1600, (int) Math.ceil(length / 0.25)));
        polyX = new double[n + 1];
        polyZ = new double[n + 1];
        polyS = new double[n + 1];
        final double[] point = new double[5];
        double grade = 0;
        double tilt = 0;
        for (int i = 0; i <= n; i++) {
            final double s = length * i / n;
            path.pointAt(s, point);
            polyX[i] = point[0];
            polyZ[i] = point[2];
            polyS[i] = s;
            if (i % 2 == 0) {
                grade = Math.max(grade, Math.abs(path.grade(s)));
                tilt = Math.max(tilt, Math.abs(path.tilt(s)));
            }
        }
        maxGradePercent = grade * 100;
        maxTilt = tilt;
        minRadius = path.horizontalRadii()[0];
        if (path.closes()) {
            solveMessage = "";
        } else if (solveMessage.isEmpty()) {
            solveMessage = String.format(java.util.Locale.ROOT, "Misses node B by %.2f blocks", path.positionError());
        }
    }

    boolean closes() {
        return path.closes();
    }

    /** The geometry/height/tilt differs from what was opened. */
    boolean changed() {
        return !profileJson.equals(openedJson);
    }

    boolean mtrChanged() {
        return !mtrShape.equals(openedShape) || Math.abs(verticalRadius - openedVerticalRadius) > 1e-6;
    }

    boolean isMtrShape() {
        return mtrImportJson != null && mtrImportJson.equals(profileJson);
    }

    boolean dirty() {
        return changed() || mtrChanged() || (resetRequested && hadCustom && isMtrShape());
    }

    /** What Save would do with the geometry: 0 nothing, 1 save the profile, 2 reset to MTR. */
    int saveAction() {
        if (resetRequested && isMtrShape()) {
            return hadCustom ? 2 : 0;
        }
        return changed() ? 1 : 0;
    }

    /** Blocks Save: the draft would be sent but does not reach node B. */
    boolean blocksSave() {
        return saveAction() == 1 && !closes();
    }

    Rail freshRail() {
        return MinecraftClientData.getInstance().railIdMap.get(hexId);
    }

    /** JSON of the current draft (undo compares these). */
    String json() {
        return profileJson;
    }

    /** The rail's identity (nodes, MTR angles, headings) as a profile — what {@code TrackEdit.parallel} takes. */
    TrackProfile identity() {
        return opened.copy();
    }

    /** Undo state of this draft. */
    Object[] snapshot() {
        return new Object[]{profile.copy(), mtrShape, verticalRadius, resetRequested, solveMessage, profileJson};
    }

    /** True when {@link #snapshot} state equals the current one. */
    boolean sameAs(Object[] state) {
        return profileJson.equals(state[5]) && mtrShape.equals(state[1]) && Math.abs(verticalRadius - (Double) state[2]) < 1e-9
            && resetRequested == (Boolean) state[3];
    }

    void restore(Object[] state) {
        profile = ((TrackProfile) state[0]).copy();
        mtrShape = (String) state[1];
        verticalRadius = (Double) state[2];
        resetRequested = (Boolean) state[3];
        solveMessage = (String) state[4];
        rebuild();
    }

    /** Back to exactly what was opened (a follower whose source went back to its own opened shape). */
    void revertGeometry() {
        resetRequested = false;
        setProfile(opened.copy(), "");
    }

    /** Distance of SPLINE anchor {@code i} along the path (segment starts; the last anchor = length). */
    double anchorS(int i) {
        return path.anchorS(i);
    }

    boolean isSpline() {
        return profile.geometry == TrackProfile.Geometry.SPLINE && profile.anchors.size() >= 2;
    }

    // ------------------------------------------------------------------ node end helpers

    /** Node position of the far end (x, y, z). */
    long[] farNode() {
        return nodeIsA ? new long[]{nodes[3], nodes[4], nodes[5]} : new long[]{nodes[0], nodes[1], nodes[2]};
    }

    /** Travel heading (A→B frame) at the clicked node's end of this rail. */
    double headingAtNode() {
        return nodeIsA ? profile.headingA : profile.headingB;
    }

    /** Heading of the track LEAVING the clicked node (towards the far node). */
    double outgoingHeading() {
        return nodeIsA ? profile.headingA : profile.headingB + Math.PI;
    }

    /** Stored end tilt at the clicked node (tiltA or tiltB, A→B frame). */
    double tiltAtNode() {
        return nodeIsA ? profile.tiltA : profile.tiltB;
    }

    void setTiltAtNode(double degrees) {
        if (nodeIsA) {
            profile.tiltA = TrackProfile.clampTilt(degrees);
        } else {
            profile.tiltB = TrackProfile.clampTilt(degrees);
        }
        rebuild();
    }

    /** Distance along the chain of the clicked node (0 or length). */
    double nodeS() {
        return nodeIsA ? 0 : path.length();
    }
}
