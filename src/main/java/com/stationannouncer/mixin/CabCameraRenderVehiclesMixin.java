package com.stationannouncer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.stationannouncer.client.mtraddon.cab.CabCamera;
import com.stationannouncer.client.mtraddon.cab.CabCinematic;
import org.mtr.mapping.holder.Camera;
import org.mtr.mod.render.RenderVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * <b>What:</b> keeps a ridden train steady while the camera leans out ({@link CabCamera}).
 * MTR assumes the camera yaw equals the rider's yaw (± 180° for third-person front view):
 * in curves it rotates the ridden train by {@code playerYaw − cameraYaw}
 * ({@code getRenderPositionAndRotation}) and adds π when they differ by more than 90°
 * (the vehicle loop lambda). A leaned camera's extra yaw would swing the whole train, so both
 * reads get the yaw without the lean. Identity whenever nobody leans. During the startup
 * cinematic ({@link CabCinematic}) the camera looks from outside the train, so MTR gets the yaw
 * vanilla computed for the player's own view instead ({@link CabCinematic#mtrYaw}).
 *
 * <p>Client-only target. javap MTR FABRIC-4.0.1+1.20.4: {@code Camera.getYaw()F} (mapping holder)
 * is called once in {@code getRenderPositionAndRotation} (bytecode 150) and once in
 * {@code lambda$render$16} (201, the per-vehicle loop).</p>
 */
@Mixin(value = RenderVehicles.class, remap = false)
public abstract class CabCameraRenderVehiclesMixin {
    @WrapOperation(method = {"getRenderPositionAndRotation", "lambda$render$16"},
            at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/holder/Camera;getYaw()F"))
    private static float stationannouncer$unleanedYaw(Camera camera, Operation<Float> original) {
        return CabCinematic.mtrYaw(CabCamera.unleanedYaw(original.call(camera)));
    }
}
