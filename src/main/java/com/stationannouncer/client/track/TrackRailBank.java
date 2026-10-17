package com.stationannouncer.client.track;

import com.stationannouncer.track.TrackPath;

/**
 * Pure-Java banking math for {@link TrackRailRenderer} (no Minecraft / MTR types, so
 * {@code tools/track_test/RailBankCheck.java} can prove it offline).
 *
 * <h2>Model-style roll — derivation</h2>
 * MTR draws a rail style per segment as (MatrixStack post-multiplies, so the LAST call acts first
 * on a model vertex):
 * <pre>
 *   world = T(mid + modelYOffset) · Ry(a) · Rx(b) · Rz(jitter) · v
 *   non-flip: a = π/2 − θ,      b = π − pitch
 *   flip "_2": a = π/2 − θ + π, b = π + pitch
 * </pre>
 * with right-handed rotations (GraphicsHolder → RotationAxis.POSITIVE_*), θ = atan2(dz, dx) of the
 * segment (= heading of A→B travel, since we walk our own path A→B) and "right" = (−sin θ, 0, cos θ).
 * Pushing the model axes through Ry·Rx gives:
 * <pre>
 *   non-flip: model +x = LEFT,  model +y = down (−cos p up-component), model +z = −tangent (backward)
 *   flip:     model +x = RIGHT, model +y = down,                        model +z = +tangent (forward)
 * </pre>
 * Both frames are right-handed. A right-handed rotation by α about the FORWARD tangent lowers the
 * right side (right (0,0,1) → (0, −sin α, cos α) for θ = 0), so "tilt φ > 0 = right side low" is a
 * rotation +φ about +tangent: Rz(−φ) in the non-flip frame (its z points backward) and Rz(+φ) in the
 * flip frame. The roll is inserted right after Rx, conjugated by a translation to the pivot:
 * {@code T(P) · Rz(roll) · T(−P)}. The pivot is the LOW rail at lateral offset
 * {@link TrackPath#pivotOffset} (+ = right), at the path height (h = 0, same as {@link TrackPath#bank}):
 * world offset from the model origin = right·u_p + (0, −modelYOffset, 0), i.e. in model coordinates
 * x = ∓u_p (non-flip −, flip +), y = modelYOffset·cos(pitch) (the z component is irrelevant for a
 * rotation about z). At pitch 0 this reproduces {@link TrackPath#bank} exactly for every model point.
 */
public final class TrackRailBank {
    private TrackRailBank() {
    }

    /** Allocation-free {@link TrackPath#bank}: writes {u', h'} into {@code out}. */
    public static void bank(double u, double h, double tiltDegrees, double[] out) {
        final double phi = Math.toRadians(tiltDegrees);
        final double pivot = TrackPath.pivotOffset(tiltDegrees);
        final double cos = Math.cos(phi);
        final double sin = Math.sin(phi);
        final double du = u - pivot;
        out[0] = pivot + du * cos + h * sin;
        out[1] = -du * sin + h * cos;
    }

    /** Roll (radians) to apply about the model's local z axis after MTR's yaw/pitch rotations. */
    public static double modelRoll(double tiltDegrees, boolean flip) {
        final double phi = Math.toRadians(tiltDegrees);
        return flip ? phi : -phi;
    }

    /** Model-local x of the roll pivot (the low rail). */
    public static double modelPivotX(double tiltDegrees, boolean flip) {
        final double pivot = TrackPath.pivotOffset(tiltDegrees);
        return flip ? pivot : -pivot;
    }

    /** Model-local y of the roll pivot (the path height, below the model origin by modelYOffset). */
    public static double modelPivotY(double modelYOffset, double pitch) {
        return modelYOffset * Math.cos(pitch);
    }

    /** Same segment count as {@link TrackPath#forEachSegment} (so banked rails split exactly like unbanked custom ones). */
    public static int segmentCount(double length, double interval) {
        return length < 0.5 || interval <= 0 ? Math.max(1, (int) Math.ceil(length / 0.5)) : Math.max(1, (int) Math.round(length / interval));
    }
}
