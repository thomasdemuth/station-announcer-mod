package com.stationannouncer.client.track;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.track.TrackRails;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackRegistry;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

/**
 * TRAINS LEAN WITH TILTED TRACK (client, render thread only). Riders' cameras never roll.
 *
 * <h2>Data flow, per vehicle per frame</h2>
 * <ol>
 *   <li>{@code RenderVehicles.render} calls {@code VehicleExtension.getSmoothedVehicleCarsAndPositions};
 *       {@link #beginCapture} (HEAD) looks up the vehicle's cached {@link TrackVehicleLeanMath.PathTilts}
 *       (rebuilt only when {@link TrackRegistry#version()} moves or MTR replaces the vehicle). No tilted
 *       rail on the path → nothing is recorded and every later hook returns after one field read.</li>
 *   <li>Inside it, MTR's private {@code Vehicle.getBogiePositions(value, …)} runs once per bogie with
 *       {@code value} = that bogie's PATH DISTANCE (railProgress already smoothed); {@link #recordBogie}
 *       turns it into a tilt along the bogie's local +z and remembers it next to the returned pair's
 *       first {@code Vector} (a fresh object — an exact identity key).</li>
 *   <li>RenderVehicles then builds a {@code PositionAndRotation} per bogie from that pair
 *       ({@link #onBogiePose}) and one per car from its bogie poses ({@link #onCarPose} = mean of the
 *       bogies). The tilt rides on the pose ({@link TrackLeanPose}).</li>
 *   <li>Everything MTR derives from a pose then rolls with it: the body/interior/doors/lights/displays
 *       and bogie models (matrix, {@link #applyMatrixRoll}), gangway &amp; barrier connection quads, floors,
 *       doorways and gangway walking positions ({@code transformForwards}, {@link #rollLocal}), the riding
 *       player's position and camera point ({@code transformForwards}/{@code transformBackwards}, and
 *       {@link #rollNegatedOffset} in {@code getRenderPositionAndRotation}), and other riders' rendered
 *       positions ({@link #rollLocalVector} in {@code renderPlayer}). Cameras and player models stay
 *       upright.</li>
 * </ol>
 *
 * <p>Any exception disables the lean for the session (logged once) instead of breaking vehicle rendering.</p>
 */
public final class TrackVehicleLean {
    private static boolean broken;

    /** The client vehicle whose bogies are being recorded right now (identity compare only), or null. */
    private static Object capturing;
    private static boolean capturingReversed;
    private static TrackVehicleLeanMath.PathTilts capturingTilts;

    /** Recorded bogies of the LAST captured tilted vehicle: first sample Vector → tilt. */
    private static Vector[] recordedVectors = new Vector[32];
    private static double[] recordedTilts = new double[32];
    private static int recordedCount;
    private static int recordedCursor;

    /** Set around VehicleRidingMovement's walking-input transform, which is a direction, not a point. */
    private static boolean suppressBackwards;

    private static final double[] SCRATCH = new double[2];
    private static final double[] BOGIE_TILTS = new double[8];
    private static final double[] BOGIE_YAWS = new double[8];

    private TrackVehicleLean() {
    }

    // ------------------------------------------------------------------ capture

    /** HEAD of {@code VehicleExtension.getSmoothedVehicleCarsAndPositions}. */
    public static void beginCapture(VehicleExtension vehicle) {
        capturing = null;
        capturingTilts = null;
        clearRecorded();
        if (broken) {
            return;
        }
        try {
            final TrackVehicleLeanMath.PathTilts tilts = tilts(vehicle);
            if (tilts.any) {
                capturing = vehicle;
                capturingReversed = vehicle.getReversed();
                capturingTilts = tilts;
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    /** RETURN of {@code getSmoothedVehicleCarsAndPositions}: stop recording (the records stay for the poses). */
    public static void endCapture() {
        capturing = null;
        capturingTilts = null;
    }

    /** RETURN of {@code Vehicle.getBogiePositions(value, overrideY)} — any Vehicle, any thread. */
    public static void recordBogie(Object vehicle, double value, ObjectObjectImmutablePair<Vector, Vector> pair) {
        if (vehicle != capturing || pair == null) {
            return; // server-side / untilted / not rendering: one static read
        }
        final TrackVehicleLeanMath.PathTilts tilts = capturingTilts;
        if (tilts == null) {
            return;
        }
        if (recordedCount == recordedVectors.length) {
            final int size = recordedCount * 2;
            final Vector[] vectors = new Vector[size];
            final double[] values = new double[size];
            System.arraycopy(recordedVectors, 0, vectors, 0, recordedCount);
            System.arraycopy(recordedTilts, 0, values, 0, recordedCount);
            recordedVectors = vectors;
            recordedTilts = values;
        }
        recordedVectors[recordedCount] = pair.left();
        recordedTilts[recordedCount] = tilts.tiltAt(value, capturingReversed);
        recordedCount++;
    }

    /** RETURN of {@code new PositionAndRotation(Vector position1, Vector position2, boolean)} (the bogie pose). */
    public static void onBogiePose(PositionAndRotation pose, Vector position1) {
        if (recordedCount == 0) {
            return;
        }
        int index = -1;
        if (recordedCursor < recordedCount && recordedVectors[recordedCursor] == position1) {
            index = recordedCursor;
        } else {
            for (int i = 0; i < recordedCount; i++) {
                if (recordedVectors[i] == position1) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            return;
        }
        recordedCursor = index + 1;
        final double tilt = recordedTilts[index];
        if (Math.abs(tilt) > TrackVehicleLeanMath.MIN_TILT) {
            ((TrackLeanPose) (Object) pose).stationannouncer$setLeanTilt(tilt);
        }
    }

    /** RETURN of {@code new PositionAndRotation(ObjectArrayList bogiePoses, VehicleCar, boolean)} (the car pose). */
    public static void onCarPose(PositionAndRotation pose, ObjectArrayList<PositionAndRotation> bogiePoses) {
        if (recordedCount == 0 || bogiePoses == null) {
            return;
        }
        final int count = Math.min(bogiePoses.size(), BOGIE_TILTS.length);
        boolean any = false;
        for (int i = 0; i < count; i++) {
            final PositionAndRotation bogie = bogiePoses.get(i);
            BOGIE_TILTS[i] = ((TrackLeanPose) (Object) bogie).stationannouncer$leanTilt();
            BOGIE_YAWS[i] = bogie.yaw;
            any |= BOGIE_TILTS[i] != 0;
        }
        if (any) {
            final double tilt = TrackVehicleLeanMath.carTilt(pose.yaw, BOGIE_TILTS, BOGIE_YAWS, count);
            if (Math.abs(tilt) > TrackVehicleLeanMath.MIN_TILT) {
                ((TrackLeanPose) (Object) pose).stationannouncer$setLeanTilt(tilt);
            }
        }
    }

    /** RETURN of {@code RenderVehicles.getRenderPositionAndRotation}: the rider-relative copy keeps the roll. */
    public static void copyLean(PositionAndRotation from, PositionAndRotation to) {
        if (from == null || to == null || from == to) {
            return;
        }
        final double tilt = ((TrackLeanPose) (Object) from).stationannouncer$leanTilt();
        if (tilt != 0) {
            ((TrackLeanPose) (Object) to).stationannouncer$setLeanTilt(tilt);
        }
    }

    // ------------------------------------------------------------------ applying the roll

    /**
     * Inside RenderVehicles' stored-matrix lambda, between {@code rotateXRadians(pitch + π)} and the
     * oscillation {@code rotateZDegrees}: model frame roll {@code T(Pm)·Rz(φ)·T(−Pm)}. Order matches the
     * gangway quads, which MTR builds as {@code transformForwards(u.rotateZ(oscillation))}.
     */
    public static void applyMatrixRoll(PositionAndRotation pose, GraphicsHolder graphicsHolder) {
        final TrackLeanPose lean = (TrackLeanPose) (Object) pose;
        final double tilt = lean.stationannouncer$leanTilt();
        if (tilt == 0) {
            return;
        }
        final double pivot = TrackVehicleLeanMath.modelPivotX(tilt);
        graphicsHolder.translate(pivot, 0, 0);
        graphicsHolder.rotateZRadians((float) Math.toRadians(tilt));
        graphicsHolder.translate(-pivot, 0, 0);
    }

    /** Rolls a car-local point ({@code Vector} or {@code Vector3d}); null when there is no roll or the type is unknown. */
    public static Object rollLocal(PositionAndRotation pose, Object value) {
        final TrackLeanPose lean = (TrackLeanPose) (Object) pose;
        if (lean.stationannouncer$leanTilt() == 0) {
            return null;
        }
        if (value instanceof Vector vector) {
            TrackVehicleLeanMath.roll(vector.x, vector.y, lean.stationannouncer$leanCos(), lean.stationannouncer$leanSin(), lean.stationannouncer$leanPivotX(), SCRATCH);
            return new Vector(SCRATCH[0], SCRATCH[1], vector.z);
        }
        if (value instanceof Vector3d vector) {
            TrackVehicleLeanMath.roll(vector.getXMapped(), vector.getYMapped(), lean.stationannouncer$leanCos(), lean.stationannouncer$leanSin(), lean.stationannouncer$leanPivotX(), SCRATCH);
            return new Vector3d(SCRATCH[0], SCRATCH[1], vector.getZMapped());
        }
        return null;
    }

    /** Inverse of {@link #rollLocal}, for {@code transformBackwards} results; null = leave as is. */
    public static Object unrollLocal(PositionAndRotation pose, Object value) {
        if (suppressBackwards) {
            return null;
        }
        final TrackLeanPose lean = (TrackLeanPose) (Object) pose;
        if (lean.stationannouncer$leanTilt() == 0) {
            return null;
        }
        if (value instanceof Vector vector) {
            TrackVehicleLeanMath.unroll(vector.x, vector.y, lean.stationannouncer$leanCos(), lean.stationannouncer$leanSin(), lean.stationannouncer$leanPivotX(), SCRATCH);
            return new Vector(SCRATCH[0], SCRATCH[1], vector.z);
        }
        if (value instanceof Vector3d vector) {
            TrackVehicleLeanMath.unroll(vector.getXMapped(), vector.getYMapped(), lean.stationannouncer$leanCos(), lean.stationannouncer$leanSin(), lean.stationannouncer$leanPivotX(), SCRATCH);
            return new Vector3d(SCRATCH[0], SCRATCH[1], vector.getZMapped());
        }
        return null;
    }

    /** Same as {@link #rollLocal} for a {@code Vector}, returning the input when there is no roll. */
    public static Vector rollLocalVector(PositionAndRotation pose, Vector value) {
        if (pose == null || value == null) {
            return value;
        }
        final Object rolled = rollLocal(pose, value);
        return rolled == null ? value : (Vector) rolled;
    }

    /**
     * {@code getRenderPositionAndRotation} starts from {@code new Vector(−offset)} (the riding player's
     * car-local point, negated) — returns {@code −roll(offset)} so the camera sits on the ROLLED point.
     */
    public static Vector rollNegatedOffset(PositionAndRotation ridingPose, Vector negatedOffset) {
        if (ridingPose == null || negatedOffset == null) {
            return negatedOffset;
        }
        final TrackLeanPose lean = (TrackLeanPose) (Object) ridingPose;
        if (lean.stationannouncer$leanTilt() == 0) {
            return negatedOffset;
        }
        TrackVehicleLeanMath.roll(-negatedOffset.x, -negatedOffset.y, lean.stationannouncer$leanCos(), lean.stationannouncer$leanSin(), lean.stationannouncer$leanPivotX(), SCRATCH);
        return new Vector(-SCRATCH[0], -SCRATCH[1], negatedOffset.z);
    }

    public static void setSuppressBackwards(boolean suppress) {
        suppressBackwards = suppress;
    }

    // ------------------------------------------------------------------ cache

    private static TrackVehicleLeanMath.PathTilts tilts(VehicleExtension vehicle) {
        final TrackLeanVehicle holder = (TrackLeanVehicle) (Object) vehicle;
        final long version = TrackRegistry.version();
        final TrackVehicleLeanMath.PathTilts cached = holder.stationannouncer$leanTilts();
        if (cached != null && cached.version == version) {
            return cached;
        }
        final TrackVehicleLeanMath.PathTilts built = build(vehicle.vehicleExtraData.immutablePath, version);
        holder.stationannouncer$setLeanTilts(built);
        return built;
    }

    private static TrackVehicleLeanMath.PathTilts build(ObjectImmutableList<PathData> path, long version) {
        final int size = path.size();
        TrackPath[] paths = null;
        for (int i = 0; i < size; i++) {
            final PathData pathData = path.get(i);
            // Client vehicle paths always have their rail cached (PacketUpdateVehiclesLifts → writePathCache).
            final Rail rail = pathData.getRail();
            final TrackPath trackPath = rail == null ? null : TrackRails.path(rail);
            if (trackPath != null && trackPath.hasTilt()) {
                if (paths == null) {
                    paths = new TrackPath[size];
                }
                paths[i] = trackPath;
            }
        }
        if (paths == null) {
            return TrackVehicleLeanMath.PathTilts.none(version);
        }
        final double[] starts = new double[size];
        final boolean[] reversed = new boolean[size];
        for (int i = 0; i < size; i++) {
            final PathData pathData = path.get(i);
            starts[i] = pathData.getStartDistance();
            reversed[i] = pathData.reversePositions;
        }
        return new TrackVehicleLeanMath.PathTilts(version, starts, paths, reversed);
    }

    private static void clearRecorded() {
        if (recordedCount > 0) {
            java.util.Arrays.fill(recordedVectors, 0, recordedCount, null);
        }
        recordedCount = 0;
        recordedCursor = 0;
    }

    private static void fail(Throwable e) {
        broken = true;
        capturing = null;
        capturingTilts = null;
        clearRecorded();
        StationAnnouncer.LOGGER.error("Track vehicle lean failed; trains will not lean on tilted track for this session", e);
    }
}
