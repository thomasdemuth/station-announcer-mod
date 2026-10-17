package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.cab.CabCamera;
import com.stationannouncer.client.mtraddon.cab.CabCinematic;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> leaning out of the cab window and the cab seat's eye height ({@link CabCamera}).
 * After vanilla has placed and turned the camera, first person only, the camera is moved
 * sideways out of the car and turned along the train; the player entity is untouched.
 *
 * <p>The mod's FIRST mixin on a vanilla class (remapped through the refmap Loom generates).
 * Target (javap yarn 1.20.4+build.3): {@code public void update(BlockView, Entity, boolean
 * thirdPerson, boolean inverseView, float tickDelta)}; {@code protected setPos(DDD)V},
 * {@code protected setRotation(FF)V}. Client thread. Zero work unless leaning or seated.</p>
 */
@Mixin(Camera.class)
public abstract class CabCameraMixin {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract Vec3d getPos();

    @Shadow
    public abstract float getYaw();

    @Shadow
    public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void stationannouncer$cabCamera(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                            float tickDelta, CallbackInfo ci) {
        if (CabCinematic.camera(getPos(), getYaw(), getPitch(), tickDelta)) { // startup cinematic shot: replaces seat/lean
            setRotation(CabCinematic.yaw, CabCinematic.pitch);
            setPos(CabCinematic.x, CabCinematic.y, CabCinematic.z);
            return;
        }
        if (!CabCamera.active()) {
            return;
        }
        if (thirdPerson) {
            CabCamera.resetYaw();
            return;
        }
        Vec3d moved = CabCamera.cameraPos(area, focusedEntity, getPos(), tickDelta);
        if (moved == null) {
            return;
        }
        float yaw = getYaw();
        float leaned = CabCamera.cameraYaw(yaw);
        if (leaned != yaw) {
            setRotation(leaned, getPitch());
        }
        setPos(moved.x, moved.y, moved.z);
    }
}
