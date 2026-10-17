package com.stationannouncer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.stationannouncer.client.track.TrackVehicleLean;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.render.PositionAndRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vehicle lean (track tilt): the FIRST {@code transformBackwards} in {@code VehicleRidingMovement.movePlayer}
 * turns the rider's WASD input into a car-local walking DIRECTION (its rotateX and translate are
 * deliberately identity lambdas). Un-rolling a direction about the rail pivot would add a constant drift,
 * so the lean's {@code transformBackwards} hook is suppressed around exactly that call; the later point
 * transforms (gangway position, rolled standing point) do roll. See {@link TrackVehicleLean}.
 *
 * <p>Client-only target. javap (MTR FABRIC-4.0.1+1.20.4): movePlayer calls transformBackwards at
 * bytecode 165 (ordinal 0, the input direction) and 561 (ordinal 1, the gangway point).</p>
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class VehicleRidingMovementLeanMixin {
    @SuppressWarnings("rawtypes")
    // full descriptor: VehicleRidingMovement also has a private static movePlayer(DDD)V overload
    @WrapOperation(method = "movePlayer(JJILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/render/PositionAndRotation;)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/mtr/mod/render/PositionAndRotation;transformBackwards(Ljava/lang/Object;Lorg/mtr/mod/render/PositionAndRotation$Rotate;Lorg/mtr/mod/render/PositionAndRotation$Rotate;Lorg/mtr/mod/render/PositionAndRotation$Translate;)Ljava/lang/Object;",
                    ordinal = 0))
    private static Object stationannouncer$unrolledWalkInput(PositionAndRotation pose, Object movement, PositionAndRotation.Rotate rotateX,
                                                             PositionAndRotation.Rotate rotateY, PositionAndRotation.Translate translate, Operation<Object> original) {
        TrackVehicleLean.setSuppressBackwards(true);
        try {
            return original.call(pose, movement, rotateX, rotateY, translate);
        } finally {
            TrackVehicleLean.setSuppressBackwards(false);
        }
    }
}
