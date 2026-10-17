package com.stationannouncer.mixin;

import com.stationannouncer.client.track.TrackLeanPose;
import com.stationannouncer.client.track.TrackVehicleLean;
import com.stationannouncer.client.track.TrackVehicleLeanMath;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.render.PositionAndRotation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vehicle lean (track tilt): gives MTR's car/bogie pose a roll ({@link TrackLeanPose}) and makes the
 * pose's own point transforms honour it, so every consumer of {@code transformForwards} /
 * {@code transformBackwards} (gangway + barrier connection quads, floors, doorways, gangway walking
 * positions, the riding player's position) agrees with the rolled body. See {@link TrackVehicleLean}.
 *
 * <p>Untilted poses (lifts, every car on level track) keep tilt 0: each hook is one field compare.
 * Client-only target. Signatures javap-verified against MTR FABRIC-4.0.1+1.20.4:
 * {@code <init>(Vector, Vector, boolean)}, {@code <init>(ObjectArrayList, VehicleCar, boolean)},
 * {@code <T> T transformForwards(T, Rotate, Rotate, Translate)} and {@code transformBackwards} (same).</p>
 */
@Mixin(value = PositionAndRotation.class, remap = false)
public abstract class PositionAndRotationLeanMixin implements TrackLeanPose {
    @Shadow
    @Final
    public Vector position;
    @Shadow
    @Final
    public double yaw;
    @Shadow
    @Final
    public double pitch;

    @Unique
    private double stationannouncer$tilt;
    @Unique
    private double stationannouncer$cos; // only read while tilt != 0 (no initialiser: keeps the constructors untouched)
    @Unique
    private double stationannouncer$sin;
    @Unique
    private double stationannouncer$pivotX;

    @Override
    public double stationannouncer$leanTilt() {
        return stationannouncer$tilt;
    }

    @Override
    public double stationannouncer$leanCos() {
        return stationannouncer$cos;
    }

    @Override
    public double stationannouncer$leanSin() {
        return stationannouncer$sin;
    }

    @Override
    public double stationannouncer$leanPivotX() {
        return stationannouncer$pivotX;
    }

    @Override
    public void stationannouncer$setLeanTilt(double tiltDegrees) {
        final double radians = Math.toRadians(tiltDegrees);
        stationannouncer$tilt = tiltDegrees;
        stationannouncer$cos = Math.cos(radians);
        stationannouncer$sin = Math.sin(radians);
        stationannouncer$pivotX = TrackVehicleLeanMath.pivotX(tiltDegrees);
    }

    /** Bogie pose (RenderVehicles' {@code lambda$render$0}). */
    @Inject(method = "<init>(Lorg/mtr/core/tool/Vector;Lorg/mtr/core/tool/Vector;Z)V", at = @At("RETURN"))
    private void stationannouncer$bogieLean(Vector position1, Vector position2, boolean hasPitch, CallbackInfo ci) {
        TrackVehicleLean.onBogiePose((PositionAndRotation) (Object) this, position1);
    }

    /** Car pose (RenderVehicles' {@code lambda$render$1}) = mean of its bogie poses. */
    @Inject(method = "<init>(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;Lorg/mtr/core/data/VehicleCar;Z)V", at = @At("RETURN"))
    private void stationannouncer$carLean(ObjectArrayList<PositionAndRotation> bogiePositions, VehicleCar vehicleCar, boolean hasPitch, CallbackInfo ci) {
        TrackVehicleLean.onCarPose((PositionAndRotation) (Object) this, bogiePositions);
    }

    /** position + R(yaw, pitch) · ROLL(u): the roll is applied to the local point first, then MTR's own steps. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "transformForwards", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$rollForwards(Object initialValue, PositionAndRotation.Rotate rotateX, PositionAndRotation.Rotate rotateY, PositionAndRotation.Translate translate, CallbackInfoReturnable<Object> cir) {
        if (stationannouncer$tilt == 0) {
            return;
        }
        final Object rolled = TrackVehicleLean.rollLocal((PositionAndRotation) (Object) this, initialValue);
        if (rolled != null) {
            cir.setReturnValue(translate.apply(rotateY.apply(rotateX.apply(rolled, (float) pitch), (float) yaw), position.x, position.y, position.z));
        }
    }

    /** ROLL⁻¹(R⁻¹(world − position)). Skipped for MTR's walking-input direction (see VehicleRidingMovementLeanMixin). */
    @Inject(method = "transformBackwards", at = @At("RETURN"), cancellable = true)
    private void stationannouncer$rollBackwards(Object initialValue, PositionAndRotation.Rotate<?> rotateX, PositionAndRotation.Rotate<?> rotateY, PositionAndRotation.Translate<?> translate, CallbackInfoReturnable<Object> cir) {
        if (stationannouncer$tilt == 0) {
            return;
        }
        final Object unrolled = TrackVehicleLean.unrollLocal((PositionAndRotation) (Object) this, cir.getReturnValue());
        if (unrolled != null) {
            cir.setReturnValue(unrolled);
        }
    }
}
