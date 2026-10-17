package com.stationannouncer.mtr.track;

import com.stationannouncer.track.TrackEdit;
import com.stationannouncer.track.TrackKey;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackRegistry;
import org.mtr.core.tool.Vector;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The real logic behind the {@code RailMath} mixin ({@code com.stationannouncer.mixin.RailMathMixin}),
 * kept in a plain class so it can be exercised against MTR's real {@code RailMath} outside the game
 * (tools/track_test/TrackRailMathStateTest.java). One instance per RailMath, created at the end of
 * its constructor.
 *
 * <h2>Resolution</h2>
 * A RailMath follows a custom {@link TrackPath} when the registry (its side, see {@link TrackSide})
 * holds a profile under its {@link TrackKey}, the profile still {@link TrackProfile#matches matches}
 * the node positions + MTR angles, the shape is not CABLE, and the built path {@link TrackPath#closes()}.
 * That answer is cached together with the {@link TrackRegistry#version()} it was computed at; every
 * query does one volatile long compare and re-resolves only when the version moved. A re-resolution
 * whose profile object is unchanged keeps its path (no rebuild), and built paths are shared through a
 * per-side cache, so the many RailMath copies of one rail (client vehicle paths build their own
 * {@code defaultRail()} on every MTR update) build it once.
 *
 * <h2>Bounds</h2>
 * MTR's {@code minX..maxZ} are public final fields read directly ({@code Rail.closeTo},
 * {@code MinecraftClientData.RailWrapper}). Whenever the resolved path changes, the {@link BoundsSink}
 * (the mixin, writing those fields through {@code @Mutable}) receives the path's bounds — or MTR's
 * own again when the path goes away.
 *
 * <h2>Threads</h2>
 * Queried from the simulator thread (server) or the client thread; races between two resolving
 * threads are benign (both compute the same answer). The resolved triple is one immutable object
 * behind a volatile field, so a reader never sees a version with another version's path.
 */
public final class TrackRailMathState {
    /** Writes a RailMath's bounds fields (implemented by the mixin itself — no lambda in mixin code). */
    @FunctionalInterface
    public interface BoundsSink {
        void stationannouncer$setBounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ);
    }

    /** MTR's ORIGINAL position (ignoring any custom path) → {x, y, z}. */
    @FunctionalInterface
    public interface MtrPosition {
        double[] at(double rawValue, boolean reverse);
    }

    private record Resolved(long version, TrackProfile profile, TrackPath path) {
    }

    private record Cached(TrackProfile profile, String shape, double verticalRadius, TrackPath path) {
    }

    /** Per-thread scratch: the MTR bypass depth (see {@link #asMtr}) and a point buffer. */
    private static final class Scratch {
        int bypass;
        final double[] point = new double[5];
    }

    private static final Resolved UNRESOLVED = new Resolved(Long.MIN_VALUE, null, null);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final Map<String, Cached> CACHE_CLIENT = new ConcurrentHashMap<>();
    private static final Map<String, Cached> CACHE_SERVER = new ConcurrentHashMap<>();

    public final long ax, ay, az, bx, by, bz;
    public final double angleA, angleB;
    public final String shapeName;
    public final double verticalRadiusArg;
    public final boolean client;
    public final String key;
    private final boolean cable;
    private final long mtrMinX, mtrMinY, mtrMinZ, mtrMaxX, mtrMaxY, mtrMaxZ;
    private final BoundsSink sink;
    private volatile Resolved resolved = UNRESOLVED;

    /**
     * @param nodes  {ax, ay, az, bx, by, bz} — the positions the RailMath was constructed with (canonical order)
     * @param mtrBounds MTR's own {minX, minY, minZ, maxX, maxY, maxZ} as computed by the constructor
     */
    public TrackRailMathState(long[] nodes, double angleA, double angleB, String shapeName, double verticalRadiusArg,
                              boolean client, long[] mtrBounds, BoundsSink sink) {
        ax = nodes[0];
        ay = nodes[1];
        az = nodes[2];
        bx = nodes[3];
        by = nodes[4];
        bz = nodes[5];
        this.angleA = angleA;
        this.angleB = angleB;
        this.shapeName = shapeName == null ? "QUADRATIC" : shapeName;
        this.verticalRadiusArg = verticalRadiusArg;
        this.client = client;
        key = TrackKey.of(ax, ay, az, bx, by, bz);
        cable = "CABLE".equals(this.shapeName);
        mtrMinX = mtrBounds[0];
        mtrMinY = mtrBounds[1];
        mtrMinZ = mtrBounds[2];
        mtrMaxX = mtrBounds[3];
        mtrMaxY = mtrBounds[4];
        mtrMaxZ = mtrBounds[5];
        this.sink = sink;
    }

    // ------------------------------------------------------------------ resolution

    /** The custom path this RailMath follows now, or null (= plain MTR). Cheap when nothing changed. */
    public TrackPath path() {
        final Resolved current = resolved;
        final long version = TrackRegistry.version();
        return current.version == version ? current.path : resolve(version, current);
    }

    /** The profile behind {@link #path()} (null when none applies). */
    public TrackProfile profile() {
        path();
        return resolved.profile;
    }

    private TrackPath resolve(long version, Resolved previous) {
        TrackProfile profile = null;
        TrackPath path = null;
        if (!cable) {
            final TrackProfile candidate = TrackRegistry.lookup(key, client);
            if (candidate != null && candidate.matches(ax, ay, az, angleA, bx, by, bz, angleB)) {
                // previous.profile is only ever set together with a non-null path
                path = candidate == previous.profile ? previous.path : pathFor(candidate);
                profile = path == null ? null : candidate;
            }
        }
        if (path != previous.path && sink != null) {
            if (path == null) {
                sink.stationannouncer$setBounds(mtrMinX, mtrMinY, mtrMinZ, mtrMaxX, mtrMaxY, mtrMaxZ);
            } else {
                sink.stationannouncer$setBounds(path.minX, path.minY, path.minZ, path.maxX, path.maxY, path.maxZ);
            }
        }
        resolved = new Resolved(version, profile, path);
        return path;
    }

    /** Built path for {@code profile} on this rail (shared per side), or null if it does not close. */
    private TrackPath pathFor(TrackProfile profile) {
        final Map<String, Cached> cache = client ? CACHE_CLIENT : CACHE_SERVER;
        final Cached cached = cache.get(key);
        if (cached != null && cached.profile == profile && cached.shape.equals(shapeName) && cached.verticalRadius == verticalRadiusArg) {
            return cached.path;
        }
        TrackPath path;
        try {
            path = TrackPath.build(profile, shapeName, verticalRadiusArg);
            if (!path.closes()) {
                path = null;
            }
        } catch (RuntimeException e) {
            path = null;
        }
        cache.put(key, new Cached(profile, shapeName, verticalRadiusArg, path));
        return path;
    }

    /** Server stop / disconnect: drop shared built paths (they reference registry profiles). */
    public static void clearCache(boolean client) {
        (client ? CACHE_CLIENT : CACHE_SERVER).clear();
    }

    // ------------------------------------------------------------------ RailMath overrides

    /**
     * {@code RailMath.getPosition} when a custom path applies, else null (= run MTR's own code).
     * Allocates only the returned Vector (plus whatever {@link TrackPath#pointAt} needs).
     */
    public Vector positionOrNull(double rawValue, boolean reverse) {
        final TrackPath path = path();
        if (path == null) {
            return null;
        }
        final Scratch scratch = SCRATCH.get();
        if (scratch.bypass > 0) {
            return null;
        }
        final double length = path.getLength();
        final double clamped = Math.max(0, Math.min(length, rawValue));
        final double[] point = scratch.point;
        path.pointAt(reverse ? length - clamped : clamped, point);
        return new Vector(point[0], point[1], point[2]);
    }

    /** {@code RailMath.getLength} when a custom path applies, else NaN (= run MTR's own code). */
    public double lengthOrNaN() {
        final TrackPath path = path();
        if (path == null || SCRATCH.get().bypass > 0) {
            return Double.NaN;
        }
        return path.getLength();
    }

    /** The custom path unless {@link #asMtr} is active on this thread. */
    public TrackPath overridePath() {
        final TrackPath path = path();
        return path == null || SCRATCH.get().bypass > 0 ? null : path;
    }

    /** Runs {@code action} with every override of THIS THREAD switched off — MTR's original geometry. */
    public static <T> T asMtr(Supplier<T> action) {
        final Scratch scratch = SCRATCH.get();
        scratch.bypass++;
        try {
            return action.get();
        } finally {
            scratch.bypass--;
        }
    }

    // ------------------------------------------------------------------ import (editor)

    /**
     * The profile that reproduces MTR's own geometry of a rail: {@link TrackEdit#fromMtr} with travel
     * headings measured from MTR's ORIGINAL positions and snapped to the node axes; verified to close
     * and to match MTR's positions (21 samples, 1e-3) — otherwise, and for the node/angle combinations
     * MTR draws kinked, a freshly fitted chain ({@link TrackEdit#fitChain}, may be null).
     *
     * @param segments MTR's two segments as {isStraight (1/0), length, radius, reverseT (1/0)}
     */
    public static TrackProfile importProfile(long[] nodes, double angleA, double angleB, double[][] segments,
                                             double mtrLength, MtrPosition mtrPosition, String shapeName, double verticalRadiusArg) {
        if ("CABLE".equals(shapeName) || !(mtrLength > 0)) {
            return null;
        }
        final double d = Math.min(1e-3, mtrLength / 2);
        final double[] a0 = mtrPosition.at(0, false);
        final double[] a1 = mtrPosition.at(d, false);
        final double[] b0 = mtrPosition.at(mtrLength - d, false);
        final double[] b1 = mtrPosition.at(mtrLength, false);
        final double headingA = Math.atan2(a1[2] - a0[2], a1[0] - a0[0]);
        final double headingB = Math.atan2(b1[2] - b0[2], b1[0] - b0[0]);
        final List<double[]> list = Arrays.asList(segments);
        final TrackProfile profile = TrackEdit.fromMtr(nodes[0], nodes[1], nodes[2], angleA, headingA,
            nodes[3], nodes[4], nodes[5], angleB, headingB, list);
        final boolean kinked = Math.abs(TrackPath.wrap(TrackEdit.snapToAxis(headingA, angleA) - headingA)) > 1e-2
            || Math.abs(TrackPath.wrap(TrackEdit.snapToAxis(headingB, angleB) - headingB)) > 1e-2;
        if (!kinked && reproducesMtr(profile, mtrLength, mtrPosition, shapeName, verticalRadiusArg)) {
            return profile;
        }
        return TrackEdit.fitChain(profile);
    }

    private static boolean reproducesMtr(TrackProfile profile, double mtrLength, MtrPosition mtrPosition, String shapeName, double verticalRadiusArg) {
        final TrackPath path;
        try {
            path = TrackPath.build(profile, shapeName, verticalRadiusArg);
        } catch (RuntimeException e) {
            return false;
        }
        if (!path.closes() || Math.abs(path.getLength() - mtrLength) > 1e-3) {
            return false;
        }
        for (int i = 0; i <= 20; i++) {
            final double s = mtrLength * i / 20;
            final double[] mtr = mtrPosition.at(s, false);
            final double[] ours = path.getPosition(s, false);
            if (Math.abs(mtr[0] - ours[0]) > 1e-3 || Math.abs(mtr[1] - ours[1]) > 1e-3 || Math.abs(mtr[2] - ours[2]) > 1e-3) {
                return false;
            }
        }
        return true;
    }
}
