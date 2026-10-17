package com.stationannouncer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.stationannouncer.client.mtraddon.cab.CabCamera;
import com.stationannouncer.client.mtraddon.cab.CabSeat;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mod.client.GangwayMovementPositions;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.render.PositionAndRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> the cab seat ({@link CabSeat}) and the lean's riding data ({@link CabCamera}).
 * At the HEAD of MTR's per-car {@code movePlayer}, for the vehicle this client rides:
 * <ul>
 *   <li>seated driver, leading car → MTR's own riding statics are pointed at the seat (car
 *       number, car-local X/Z, off the gangway), so MTR's code that follows moves the player
 *       there, clamps it onto a floor and picks Y; when the seat moved, MTR's own
 *       {@code sendPositionUpdateTime} is set so its next tick sends the server the new spot;</li>
 *   <li>the car the player rides → its pose and the player's car-local point are recorded for
 *       the lean.</li>
 * </ul>
 * While seated, MTR's two walking-input reads ({@code getSidewaysSpeedMapped} /
 * {@code getForwardSpeedMapped}, two calls each) return 0, so WASD cannot walk out of the seat.
 *
 * <p>Client-only target. javap MTR FABRIC-4.0.1+1.20.4: private statics {@code ridingVehicleId J},
 * {@code ridingVehicleCarNumber I}, {@code ridingVehicleX/Y/Z D}, {@code isOnGangway Z},
 * {@code sendPositionUpdateTime J}; {@code movePlayer(JJILObjectArrayList;GangwayMovementPositions×3;
 * PositionAndRotation)V} calls {@code ClientPlayerEntity.getSidewaysSpeedMapped()F} at 84/102 and
 * {@code getForwardSpeedMapped()F} at 116/134. Full descriptor because of the private
 * {@code movePlayer(DDD)V} overload. Lifts (AddonRenderLifts/RenderLifts) call the same method
 * with a lift id: never the seat's vehicle, so they pass straight through.</p>
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class CabSeatRidingMixin {
    @Shadow
    private static long ridingVehicleId;
    @Shadow
    private static int ridingVehicleCarNumber;
    @Shadow
    private static double ridingVehicleX;
    @Shadow
    private static double ridingVehicleY;
    @Shadow
    private static double ridingVehicleZ;
    @Shadow
    private static boolean isOnGangway;
    @Shadow
    private static long sendPositionUpdateTime;

    private static final String MOVE_PLAYER = "movePlayer(JJILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/render/PositionAndRotation;)V";

    @Inject(method = MOVE_PLAYER, at = @At("HEAD"))
    private static void stationannouncer$cabSeat(long millisElapsed, long vehicleId, int carNumber,
                                                 ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
                                                 GangwayMovementPositions previousCarGangway, GangwayMovementPositions thisCarGangway1,
                                                 GangwayMovementPositions thisCarGangway2, PositionAndRotation positionAndRotation,
                                                 CallbackInfo ci) {
        if (vehicleId != ridingVehicleId || vehicleId == 0) {
            return; // not the ride (other trains, lifts while on a train): one compare
        }
        if (CabSeat.seatThisCar(vehicleId, carNumber, floorsAndDoorways)) {
            ridingVehicleCarNumber = carNumber;
            ridingVehicleX = CabSeat.targetX;
            ridingVehicleZ = CabSeat.targetZ;
            isOnGangway = false;
            if (CabSeat.seatMoved(carNumber) && sendPositionUpdateTime == 0) {
                sendPositionUpdateTime = System.currentTimeMillis();
            }
        }
        if (carNumber == ridingVehicleCarNumber) {
            CabCamera.recordRide(vehicleId, carNumber, positionAndRotation, ridingVehicleX, ridingVehicleY, ridingVehicleZ,
                    floorsAndDoorways);
        }
    }

    @WrapOperation(method = MOVE_PLAYER, at = @At(value = "INVOKE",
            target = "Lorg/mtr/mapping/holder/ClientPlayerEntity;getSidewaysSpeedMapped()F"))
    private static float stationannouncer$seatedNoStrafe(ClientPlayerEntity player, Operation<Float> original) {
        return CabSeat.walkLocked ? 0F : original.call(player);
    }

    @WrapOperation(method = MOVE_PLAYER, at = @At(value = "INVOKE",
            target = "Lorg/mtr/mapping/holder/ClientPlayerEntity;getForwardSpeedMapped()F"))
    private static float stationannouncer$seatedNoWalk(ClientPlayerEntity player, Operation<Float> original) {
        return CabSeat.walkLocked ? 0F : original.call(player);
    }
}
