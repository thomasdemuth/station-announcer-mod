package com.stationannouncer.client.track;

/**
 * Duck interface the {@code PositionAndRotationLeanMixin} adds to MTR's
 * {@code org.mtr.mod.render.PositionAndRotation}: the car / bogie roll from track tilt.
 * Tilt is in degrees measured along the pose's local +z (see {@link TrackVehicleLeanMath});
 * cos/sin/pivot are cached when it is set so the per-vertex transforms stay arithmetic only.
 * A pose that was never set has tilt 0 and every lean hook returns at once.
 */
public interface TrackLeanPose {
    double stationannouncer$leanTilt();

    double stationannouncer$leanCos();

    double stationannouncer$leanSin();

    /** u-frame x of the pivot (the low rail). */
    double stationannouncer$leanPivotX();

    /** Sets the tilt (degrees, + = right of local +z low) and derives cos/sin/pivot. */
    void stationannouncer$setLeanTilt(double tiltDegrees);
}
