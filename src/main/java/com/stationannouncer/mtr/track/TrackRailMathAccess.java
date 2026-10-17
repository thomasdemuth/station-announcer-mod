package com.stationannouncer.mtr.track;

import com.stationannouncer.track.TrackPath;

/**
 * Duck interface the {@code RailMath} mixin adds to MTR's {@code org.mtr.core.data.RailMath}.
 * Cast any RailMath to this: {@code ((TrackRailMathAccess) rail.railMath)}.
 *
 * SKELETON written by the coordinator — the server/common integration agent owns the mixin that
 * implements it. Do not change the method names (renderers and the editor call them).
 */
public interface TrackRailMathAccess {
    /** The custom path this RailMath currently follows, or null when it is plain MTR geometry. Re-resolves lazily when {@code TrackRegistry.version()} moves. */
    TrackPath stationannouncer$trackPath();

    /**
     * MTR's own two segments as {isStraight (1/0), length, |radius|, reverseT (1/0)} — the input
     * {@code TrackEdit.fromMtr} expects. Always MTR's ORIGINAL geometry, even when a custom path is active.
     */
    double[][] stationannouncer$mtrSegments();

    /** MTR's original length (ignores any custom path). */
    double stationannouncer$mtrLength();

    /** MTR's original position (ignores any custom path) → {x, y, z}. */
    double[] stationannouncer$mtrPosition(double rawValue, boolean reverse);

    /** The arguments this RailMath was constructed with. */
    long[] stationannouncer$nodes();

    /** {angleA degrees, angleB degrees}. */
    double[] stationannouncer$angles();

    String stationannouncer$shapeName();

    double stationannouncer$verticalRadiusArg();
}
