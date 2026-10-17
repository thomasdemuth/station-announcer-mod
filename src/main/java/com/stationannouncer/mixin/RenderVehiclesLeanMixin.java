package com.stationannouncer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.stationannouncer.client.track.TrackVehicleLean;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vehicle lean (track tilt), the render side. See {@link TrackVehicleLean}.
 * <ul>
 *   <li>The stored matrix of every car and bogie ({@code getStoredMatrixTransformations}'s lambda —
 *       used by BOTH the plain model path and OptimizedRenderer's {@code queue}/{@code queueBogie}):
 *       roll about the low rail inserted after {@code rotateXRadians(pitch + π)}, before the
 *       oscillation {@code rotateZDegrees}. Body, interior, doors, lights, displays and bogies all hang
 *       off this matrix.</li>
 *   <li>{@code getRenderPositionAndRotation} (the riding player's camera-relative copy): the copy keeps the
 *       roll, and the rider's car-local point is ROLLED before MTR rotates it, so the camera sits on the
 *       rolled floor (no camera roll — only position).</li>
 *   <li>{@code renderPlayer}: other riders (and you in third person) are placed at the rolled point,
 *       still drawn upright.</li>
 * </ul>
 * Client-only target. Names/descriptors javap-verified against MTR FABRIC-4.0.1+1.20.4
 * ({@code lambda$getStoredMatrixTransformations$21(PositionAndRotation, double, GraphicsHolder)} is the
 * yaw/pitch/oscillation lambda; {@code getRenderPositionAndRotation} has two {@code Vector.rotateX}
 * calls — one per branch, both on {@code new Vector(−offset)}; {@code renderPlayer} has one).
 */
@Mixin(value = RenderVehicles.class, remap = false)
public abstract class RenderVehiclesLeanMixin {
    @Inject(method = "lambda$getStoredMatrixTransformations$21(Lorg/mtr/mod/render/PositionAndRotation;DLorg/mtr/mapping/mapper/GraphicsHolder;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/mapper/GraphicsHolder;rotateZDegrees(F)V"))
    private static void stationannouncer$rollMatrix(PositionAndRotation pose, double oscillationAmount, GraphicsHolder graphicsHolder, CallbackInfo ci) {
        TrackVehicleLean.applyMatrixRoll(pose, graphicsHolder);
    }

    @Inject(method = "getRenderPositionAndRotation", at = @At("RETURN"))
    private static void stationannouncer$keepLean(Vector3d offsetVector, Double offsetRotation, PositionAndRotation ridingCarPositionAndRotation,
                                                  PositionAndRotation renderingPositionAndRotation, Vector3d cameraShakeOffset, CallbackInfoReturnable<PositionAndRotation> cir) {
        TrackVehicleLean.copyLean(renderingPositionAndRotation, cir.getReturnValue());
    }

    @WrapOperation(method = "getRenderPositionAndRotation", at = @At(value = "INVOKE", target = "Lorg/mtr/core/tool/Vector;rotateX(D)Lorg/mtr/core/tool/Vector;"))
    private static Vector stationannouncer$rollRiderOffset(Vector negatedOffset, double pitch, Operation<Vector> original,
                                                           @Local(argsOnly = true, ordinal = 0) PositionAndRotation ridingCarPositionAndRotation) {
        return original.call(TrackVehicleLean.rollNegatedOffset(ridingCarPositionAndRotation, negatedOffset), pitch);
    }

    @WrapOperation(method = "renderPlayer", at = @At(value = "INVOKE", target = "Lorg/mtr/core/tool/Vector;rotateX(D)Lorg/mtr/core/tool/Vector;"))
    private static Vector stationannouncer$rollRiderPosition(Vector localPosition, double pitch, Operation<Vector> original,
                                                             @Local(argsOnly = true, ordinal = 0) PositionAndRotation playerCarPositionAndRotation) {
        return original.call(TrackVehicleLean.rollLocalVector(playerCarPositionAndRotation, localPosition), pitch);
    }
}
