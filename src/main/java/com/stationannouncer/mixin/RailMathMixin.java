package com.stationannouncer.mixin;

import com.stationannouncer.mtr.track.TrackRailMathAccess;
import com.stationannouncer.mtr.track.TrackRailMathState;
import com.stationannouncer.mtr.track.TrackSide;
import com.stationannouncer.track.TrackPath;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>What:</b> custom per-rail track geometry (TRACK_GEOMETRY_PLAN.md). Every MTR {@code RailMath}
 * gets a {@link TrackRailMathState}; while the track registry holds a matching profile for the rail,
 * every public geometry query answers from that profile's {@link TrackPath} instead of MTR's two
 * segments — so trains (PathData → {@code getPosition}/{@code getLength}), siding path finding,
 * rail rendering ({@code render}), stats and bounds all follow the custom shape, on the server
 * simulator AND on clients. With no profile everything is MTR's own code.
 *
 * <ul>
 *   <li>{@code <init>(Position, Angle, Position, Angle, Rail$Shape, D)V} RETURN — captures the
 *       arguments (the constructor itself calls {@code render}/{@code getMaxVerticalRadius} for
 *       its bounds BEFORE this, while the state is still null, so those calls stay MTR's) and
 *       resolves eagerly so the bounds are right from the start.</li>
 *   <li>{@code getPosition(DZ)} and {@code getLength()} are OVERWRITTEN (bodies = MTR 4.0.1's,
 *       verified against the bytecode): they are the hottest calls in MTR (every vehicle, every
 *       tick / frame) and a cancellable {@code @Inject} would allocate a CallbackInfoReturnable per
 *       call for every rail. The no-profile path costs one field read + one volatile long compare.</li>
 *   <li>{@code render}, {@code getHorizontalRadii}, {@code getVerticalRadius},
 *       {@code getMaxVerticalRadius}: HEAD-cancelled only when a custom path applies.</li>
 *   <li>Bounds {@code minX..maxZ} are {@code @Mutable}: rewritten whenever the resolved path changes.</li>
 * </ul>
 * {@code TrackRailMathState.asMtr} switches the overrides off for the current thread — that is how
 * {@link #stationannouncer$mtrPosition} reaches MTR's original positions ({@code getPositionY} calls
 * {@code getLength()} internally).
 *
 * Common target (runs on the TSC simulator and on clients): register in the mixin config's main
 * {@code mixins} list. Descriptors javap-verified against MTR FABRIC-4.0.1+1.20.4.
 */
@Mixin(value = RailMath.class, remap = false)
public abstract class RailMathMixin implements TrackRailMathAccess, TrackRailMathState.BoundsSink {
    @Shadow
    @Final
    @Mutable
    public long minX;
    @Shadow
    @Final
    @Mutable
    public long minY;
    @Shadow
    @Final
    @Mutable
    public long minZ;
    @Shadow
    @Final
    @Mutable
    public long maxX;
    @Shadow
    @Final
    @Mutable
    public long maxY;
    @Shadow
    @Final
    @Mutable
    public long maxZ;

    @Shadow
    @Final
    private double h1;
    @Shadow
    @Final
    private double k1;
    @Shadow
    @Final
    private double h2;
    @Shadow
    @Final
    private double k2;
    @Shadow
    @Final
    private double r1;
    @Shadow
    @Final
    private double r2;
    @Shadow
    @Final
    private double tStart1;
    @Shadow
    @Final
    private double tEnd1;
    @Shadow
    @Final
    private double tStart2;
    @Shadow
    @Final
    private double tEnd2;
    @Shadow
    @Final
    private boolean reverseT1;
    @Shadow
    @Final
    private boolean reverseT2;
    @Shadow
    @Final
    private boolean isStraight1;
    @Shadow
    @Final
    private boolean isStraight2;

    @Unique
    private TrackRailMathState stationannouncer$state;

    @Shadow
    private double getPositionY(double value) {
        throw new AssertionError();
    }

    @Shadow
    private static Vector getPositionXZ(double h, double k, double r, double t, double radiusOffset, boolean isStraight) {
        throw new AssertionError();
    }

    @Inject(method = "<init>(Lorg/mtr/core/data/Position;Lorg/mtr/core/tool/Angle;Lorg/mtr/core/data/Position;Lorg/mtr/core/tool/Angle;Lorg/mtr/core/data/Rail$Shape;D)V", at = @At("RETURN"))
    private void stationannouncer$capture(Position position1, Angle angle1, Position position2, Angle angle2, Rail.Shape shape, double verticalRadius, CallbackInfo ci) {
        try {
            final TrackRailMathState state = new TrackRailMathState(
                new long[]{position1.getX(), position1.getY(), position1.getZ(), position2.getX(), position2.getY(), position2.getZ()},
                angle1 == null ? 0 : angle1.angleDegrees, angle2 == null ? 0 : angle2.angleDegrees,
                shape == null ? null : shape.name(), verticalRadius, TrackSide.isClientThread(),
                new long[]{minX, minY, minZ, maxX, maxY, maxZ}, (TrackRailMathState.BoundsSink) (Object) this);
            stationannouncer$state = state;
            state.path();
        } catch (RuntimeException e) {
            stationannouncer$state = null; // never break MTR's rail construction
        }
    }

    @Override
    public void stationannouncer$setBounds(long newMinX, long newMinY, long newMinZ, long newMaxX, long newMaxY, long newMaxZ) {
        minX = newMinX;
        minY = newMinY;
        minZ = newMinZ;
        maxX = newMaxX;
        maxY = newMaxY;
        maxZ = newMaxZ;
    }

    /**
     * @author Station Announcer (track geometry)
     * @reason Hot path: custom track profiles must reach every vehicle without a per-call allocation.
     * Falls through to MTR 4.0.1's exact body when no custom path applies.
     */
    @Overwrite
    public Vector getPosition(double rawValue, boolean reverse) {
        final TrackRailMathState state = stationannouncer$state;
        if (state != null) {
            final Vector custom = state.positionOrNull(rawValue, reverse);
            if (custom != null) {
                return custom;
            }
        }
        final double count1 = Math.abs(tEnd1 - tStart1);
        final double count2 = Math.abs(tEnd2 - tStart2);
        final double clampedValue = Utilities.clamp(rawValue, 0.0, count1 + count2);
        final double value = reverse ? count1 + count2 - clampedValue : clampedValue;
        final double y = getPositionY(value);
        return value <= count1
            ? getPositionXZ(h1, k1, r1, (double) (reverseT1 ? -1 : 1) * value + tStart1, 0.0, isStraight1).add(0.0, y, 0.0)
            : getPositionXZ(h2, k2, r2, (double) (reverseT2 ? -1 : 1) * (value - count1) + tStart2, 0.0, isStraight2).add(0.0, y, 0.0);
    }

    /**
     * @author Station Announcer (track geometry)
     * @reason See {@link #getPosition}; MTR 4.0.1's exact body when no custom path applies.
     */
    @Overwrite
    public double getLength() {
        final TrackRailMathState state = stationannouncer$state;
        if (state != null) {
            final double custom = state.lengthOrNaN();
            if (custom == custom) {
                return custom;
            }
        }
        return Math.abs(tEnd2 - tStart2) + Math.abs(tEnd1 - tStart1);
    }

    @Inject(method = "render(Lorg/mtr/core/data/RailMath$RenderRail;DFF)V", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$render(RailMath.RenderRail callback, double interval, float offsetRadius1, float offsetRadius2, CallbackInfo ci) {
        final TrackPath path = stationannouncer$overridePath();
        if (path != null) {
            path.render(callback::renderRail, interval, offsetRadius1, offsetRadius2);
            ci.cancel();
        }
    }

    @Inject(method = "getHorizontalRadii()Lorg/mtr/libraries/it/unimi/dsi/fastutil/doubles/DoubleDoubleImmutablePair;", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$horizontalRadii(CallbackInfoReturnable<DoubleDoubleImmutablePair> cir) {
        final TrackPath path = stationannouncer$overridePath();
        if (path != null) {
            final double[] radii = path.horizontalRadii();
            cir.setReturnValue(new DoubleDoubleImmutablePair(radii[0], radii[1]));
        }
    }

    @Inject(method = "getVerticalRadius()D", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$verticalRadius(CallbackInfoReturnable<Double> cir) {
        final TrackPath path = stationannouncer$overridePath();
        if (path != null) {
            cir.setReturnValue(path.getVerticalRadius());
        }
    }

    @Inject(method = "getMaxVerticalRadius()D", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$maxVerticalRadius(CallbackInfoReturnable<Double> cir) {
        final TrackPath path = stationannouncer$overridePath();
        if (path != null) {
            cir.setReturnValue(path.getMaxVerticalRadius());
        }
    }

    @Unique
    private TrackPath stationannouncer$overridePath() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? null : state.overridePath();
    }

    // ------------------------------------------------------------------ TrackRailMathAccess

    @Override
    public TrackPath stationannouncer$trackPath() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? null : state.path();
    }

    @Override
    public double[][] stationannouncer$mtrSegments() {
        return new double[][]{
            {isStraight1 ? 1 : 0, Math.abs(tEnd1 - tStart1), r1, reverseT1 ? 1 : 0},
            {isStraight2 ? 1 : 0, Math.abs(tEnd2 - tStart2), r2, reverseT2 ? 1 : 0},
        };
    }

    @Override
    public double stationannouncer$mtrLength() {
        return Math.abs(tEnd2 - tStart2) + Math.abs(tEnd1 - tStart1);
    }

    @Override
    public double[] stationannouncer$mtrPosition(double rawValue, boolean reverse) {
        final Vector vector = TrackRailMathState.asMtr(() -> getPosition(rawValue, reverse));
        return new double[]{vector.x, vector.y, vector.z};
    }

    @Override
    public long[] stationannouncer$nodes() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? new long[6] : new long[]{state.ax, state.ay, state.az, state.bx, state.by, state.bz};
    }

    @Override
    public double[] stationannouncer$angles() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? new double[2] : new double[]{state.angleA, state.angleB};
    }

    @Override
    public String stationannouncer$shapeName() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? "QUADRATIC" : state.shapeName;
    }

    @Override
    public double stationannouncer$verticalRadiusArg() {
        final TrackRailMathState state = stationannouncer$state;
        return state == null ? 0 : state.verticalRadiusArg;
    }
}
