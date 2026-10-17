package com.stationannouncer.mtr.track;

import com.stationannouncer.track.TrackKey;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;

/**
 * MTR-side helpers every track feature uses (renderers, vehicle lean, editor, server).
 *
 * Signatures are a CONTRACT other agents compile against: do not rename or remove; adding methods
 * is fine. Everything here works on either side — a client {@code Rail} reads the client registry
 * (incl. editor previews), a simulator {@code Rail} the server one (see {@link TrackSide}).
 */
public final class TrackRails {
    private TrackRails() {
    }

    /** Canonical key of a rail (TrackKey of the RailMath's node order). */
    public static String key(Rail rail) {
        final long[] nodes = access(rail).stationannouncer$nodes();
        return TrackKey.of(nodes[0], nodes[1], nodes[2], nodes[3], nodes[4], nodes[5]);
    }

    /** The custom path the rail currently follows (saved or, client-side, previewed), or null. */
    public static TrackPath path(Rail rail) {
        return access(rail).stationannouncer$trackPath();
    }

    /**
     * The profile to OPEN in the editor: the active custom profile if any (a copy — client-side that
     * is the preview draft while one is set), else MTR's own geometry imported exactly
     * ({@code TrackEdit.fromMtr}), else — for the few node/angle combinations MTR draws kinked — a
     * freshly fitted chain ({@code TrackEdit.fitChain}). Null only when nothing fits (or for CABLE
     * rails, which never take a profile) — the editor should say so.
     */
    public static TrackProfile editableProfile(Rail rail) {
        final TrackRailMathAccess access = access(rail);
        final TrackPath active = access.stationannouncer$trackPath();
        if (active != null) {
            return active.profile.copy();
        }
        return importMtr(access);
    }

    /** MTR's own geometry of the rail as a profile, ignoring any custom path (null if it cannot be imported). */
    public static TrackProfile importMtr(TrackRailMathAccess access) {
        final double[] angles = access.stationannouncer$angles();
        return TrackRailMathState.importProfile(access.stationannouncer$nodes(), angles[0], angles[1],
            access.stationannouncer$mtrSegments(), access.stationannouncer$mtrLength(), access::stationannouncer$mtrPosition,
            access.stationannouncer$shapeName(), access.stationannouncer$verticalRadiusArg());
    }

    public static TrackRailMathAccess access(Rail rail) {
        return (TrackRailMathAccess) rail.railMath;
    }

    // ------------------------------------------------------------------ MTR node order

    /**
     * The rail's nodes in MTR's canonical order — the order {@code Rail} hands them to
     * {@code RailMath} ({@code Rail.reversePositions} = {@code position1.compareTo(position2) > 0}
     * swaps them). Node A = index 0, node B = index 1. Profiles always run A → B.
     */
    public static Position[] canonicalNodes(Rail rail) {
        final long[] n = access(rail).stationannouncer$nodes();
        return new Position[]{new Position(n[0], n[1], n[2]), new Position(n[3], n[4], n[5])};
    }

    /** {MTR angle degrees at A, at B} as given to RailMath. */
    public static double[] canonicalAngles(Rail rail) {
        return access(rail).stationannouncer$angles();
    }

    /** True when {@code position} is the rail's canonical node A. */
    public static boolean isNodeA(Rail rail, Position position) {
        final long[] n = access(rail).stationannouncer$nodes();
        return n[0] == position.getX() && n[1] == position.getY() && n[2] == position.getZ();
    }

    /** The rail of {@code data} (simulator or client data) between two nodes given in any order, or null. */
    public static Rail find(Data data, long ax, long ay, long az, long bx, long by, long bz) {
        return Data.tryGet(data.positionsToRail, new Position(ax, ay, az), new Position(bx, by, bz));
    }

    /** The rail a profile/key describes, or null. */
    public static Rail find(Data data, TrackProfile profile) {
        return find(data, profile.ax, profile.ay, profile.az, profile.bx, profile.by, profile.bz);
    }
}
