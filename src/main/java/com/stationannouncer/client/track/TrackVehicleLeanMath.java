package com.stationannouncer.client.track;

import com.stationannouncer.track.TrackPath;

/**
 * PURE-JAVA half of the vehicle lean (no Minecraft / MTR types), so the distance → tilt mapping and
 * the roll can be checked offline ({@code tools/track_test/VehicleLeanCheck.java}). The MTR-typed half
 * is {@link TrackVehicleLean}.
 *
 * <h2>Frames</h2>
 * MTR places every car / bogie with a {@code PositionAndRotation} (position, yaw, pitch). In MTR's
 * {@code Vector} terms a car-local point {@code u} lands in the world at
 * {@code position + u.rotateX(pitch).rotateY(yaw)} ({@code transformForwards}). That "u frame" has
 * y UP and +z pointing from the bogie pair's FIRST sample point to its second
 * ({@code yaw = atan2(x2 - x1, z2 - z1)}). Both MTR rotations are proper rotations, so the frame keeps
 * Minecraft's handedness: for an observer travelling along local +z with y up, the RIGHT hand points
 * to local <b>−x</b> (check: yaw 0 → +z = south; facing south, west = −x is on the right).
 *
 * <p>The car MODEL is drawn in a flipped frame {@code m = (−u.x, −u.y, u.z)} (MTR's
 * {@code rotateY(yaw + π) · rotateX(pitch + π)}); {@link #modelPivotX} gives the pivot there.</p>
 *
 * <h2>The roll</h2>
 * A car whose local +z sees tilt τ (degrees, + = RIGHT side low, i.e. the track's convention measured
 * along local +z) is rolled about local z, pivoting on the low rail at path height:
 * <pre>
 *   u' = P + Rz(φ)(u − P),   φ = toRadians(τ),   P = (−TrackPath.pivotOffset(τ), 0, 0)
 *   Rz(φ) = standard right-handed rotation: x' = x cos φ − y sin φ,  y' = x sin φ + y cos φ
 * </pre>
 * Check: τ &gt; 0 → P = (−HALF_GAUGE, 0) = the right rail, fixed; the left rail (+HALF_GAUGE, 0) rises by
 * 2·HALF_GAUGE·sin φ; the roof (0, h) moves to x = −h sin φ, i.e. to the right — exactly
 * {@link TrackPath#bank}. In the model frame the same roll is {@code T(Pm)·Rz(φ)·T(−Pm)} with
 * {@code Pm = (pivotOffset(τ), 0, 0)} (conjugating Rz by the flip Rz(π) leaves the angle unchanged).
 */
public final class TrackVehicleLeanMath {
    /** Below this many degrees a car is treated as level (no roll, no work). */
    public static final double MIN_TILT = 1e-4;

    private TrackVehicleLeanMath() {
    }

    /**
     * Tilt (degrees) seen by something looking along local +z, at {@code raw} = distance into one
     * MTR {@code PathData} (path distance − {@code pathData.getStartDistance()}).
     *
     * <h3>Derivation (sign)</h3>
     * <ol>
     *   <li>{@code PathData.getPosition(raw) = rail.railMath.getPosition(raw, pathData.reversePositions)}.
     *       The rail's RailMath is ALWAYS built in canonical node order (Rail swaps its positions when
     *       {@code position1 > position2}), so the RailMath's first node is the profile's A, and
     *       {@code getPosition(raw, rev)} sits at {@code sA = rev ? L − raw : raw} metres from A
     *       (raw clamped to [0, L]) — our RailMath mixin keeps exactly these semantics.</li>
     *   <li>{@code pathData.reversePositions = start.compareTo(end) > 0}: the path walks the rail B→A,
     *       so dsA/dd = −1; otherwise +1.</li>
     *   <li>In {@code Vehicle.getBogiePositions(value)} the bogie's first sample is at
     *       {@code value + (reversed ? −c : +c)} and its second at {@code value − (reversed ? −c : +c)}:
     *       local +z (first → second) points toward DECREASING path distance d when the vehicle is not
     *       reversed and toward increasing d when it is. The car frame follows the same rule (bogie 1 →
     *       bogie 2), and {@link #carTilt} re-checks it per bogie by yaw anyway.</li>
     *   <li>So local +z runs along the rail A→B iff {@code dirA = (reversed ? +1 : −1) · (rev ? −1 : +1)}
     *       is +1. Walking A→B the tilt is {@code path.tilt(sA)}; walking B→A it is
     *       {@code path.tilt(L − sA, true) = −path.tilt(sA)} (right and left swap). Hence
     *       <b>τ = dirA · path.tilt(sA)</b>.</li>
     * </ol>
     */
    public static double localTilt(TrackPath path, double raw, boolean pathReversed, boolean vehicleReversed) {
        final double length = path.length();
        final double clamped = Math.max(0, Math.min(length, raw));
        final double sA = pathReversed ? length - clamped : clamped;
        final int dirA = (vehicleReversed ? 1 : -1) * (pathReversed ? -1 : 1);
        return dirA * path.tilt(sA);
    }

    /** Local x (u frame) of the roll pivot = the low rail; 0 for no tilt. */
    public static double pivotX(double tiltDegrees) {
        return -TrackPath.pivotOffset(tiltDegrees);
    }

    /** Model-frame (flipped) x of the roll pivot. */
    public static double modelPivotX(double tiltDegrees) {
        return TrackPath.pivotOffset(tiltDegrees);
    }

    /** u' = P + Rz(φ)(u − P) for x/y (z is untouched). {@code out} = {x', y'}. */
    public static void roll(double x, double y, double cos, double sin, double pivotX, double[] out) {
        final double dx = x - pivotX;
        out[0] = pivotX + dx * cos - y * sin;
        out[1] = dx * sin + y * cos;
    }

    /** Inverse of {@link #roll}: u = P + Rz(−φ)(u' − P). */
    public static void unroll(double x, double y, double cos, double sin, double pivotX, double[] out) {
        final double dx = x - pivotX;
        out[0] = pivotX + dx * cos + y * sin;
        out[1] = -dx * sin + y * cos;
    }

    /**
     * A car's tilt from its bogies: the mean of the bogie tilts, each flipped when that bogie's local
     * +z points the other way from the car's (|yaw difference| &gt; 90°). {@code bogieTilts} and
     * {@code bogieYaws} are read for {@code count} entries.
     */
    public static double carTilt(double carYaw, double[] bogieTilts, double[] bogieYaws, int count) {
        if (count <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += Math.cos(carYaw - bogieYaws[i]) >= 0 ? bogieTilts[i] : -bogieTilts[i];
        }
        return sum / count;
    }

    /**
     * The tilted rails of one vehicle path, by path distance. Built once per vehicle per
     * {@code TrackRegistry.version()} by {@link TrackVehicleLean}; {@link #NONE}-style instances
     * ({@code any == false}) make the whole lean a single field read per frame.
     */
    public static final class PathTilts {
        public final long version;
        /** True when at least one rail of the path carries tilt. */
        public final boolean any;
        private final double[] starts;
        private final TrackPath[] paths;
        private final boolean[] reversed;

        /**
         * @param starts   every PathData's start distance, ascending (MTR's list order)
         * @param paths    the TILTED custom path of each PathData's rail, null where none / untilted
         * @param reversed each PathData's {@code reversePositions}
         */
        public PathTilts(long version, double[] starts, TrackPath[] paths, boolean[] reversed) {
            this.version = version;
            this.starts = starts;
            this.paths = paths;
            this.reversed = reversed;
            boolean found = false;
            for (final TrackPath path : paths) {
                found |= path != null;
            }
            any = found;
        }

        public static PathTilts none(long version) {
            return new PathTilts(version, new double[0], new TrackPath[0], new boolean[0]);
        }

        /**
         * Tilt at path distance {@code d} seen along local +z (see {@link #localTilt}). The PathData is
         * the last one whose start ≤ d — MTR's {@code Utilities.getIndexFromConditionalList}
         * ({@code PathData.matchesCondition = value >= startDistance}); before the first one → 0.
         */
        public double tiltAt(double d, boolean vehicleReversed) {
            if (!any) {
                return 0;
            }
            int low = -1;
            int high = starts.length;
            while (high - low > 1) {
                final int mid = (low + high) >>> 1;
                if (starts[mid] <= d) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            if (low < 0 || paths[low] == null) {
                return 0;
            }
            return localTilt(paths[low], d - starts[low], reversed[low], vehicleReversed);
        }
    }
}
