package com.stationannouncer.mixin;

import com.stationannouncer.client.track.TrackLeanVehicle;
import com.stationannouncer.client.track.TrackVehicleLean;
import com.stationannouncer.client.track.TrackVehicleLeanMath;
import org.mtr.mod.data.VehicleExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vehicle lean (track tilt): brackets MTR's per-frame car placement
 * ({@code getSmoothedVehicleCarsAndPositions}, called only by {@code RenderVehicles.render}) so the
 * bogie hook ({@link VehicleLeanBogieMixin}) records this vehicle's bogie path distances, and holds the
 * vehicle's cached tilt table ({@link TrackLeanVehicle}). See {@link TrackVehicleLean}.
 *
 * <p>Client-only target. Signature javap-verified against MTR FABRIC-4.0.1+1.20.4:
 * {@code public ObjectArrayList getSmoothedVehicleCarsAndPositions(long)}.</p>
 */
@Mixin(value = VehicleExtension.class, remap = false)
public abstract class VehicleExtensionLeanMixin implements TrackLeanVehicle {
    @Unique
    private TrackVehicleLeanMath.PathTilts stationannouncer$leanTilts;

    @Override
    public TrackVehicleLeanMath.PathTilts stationannouncer$leanTilts() {
        return stationannouncer$leanTilts;
    }

    @Override
    public void stationannouncer$setLeanTilts(TrackVehicleLeanMath.PathTilts tilts) {
        stationannouncer$leanTilts = tilts;
    }

    @Inject(method = "getSmoothedVehicleCarsAndPositions(J)Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("HEAD"))
    private void stationannouncer$beginLeanCapture(long millisElapsed, CallbackInfoReturnable<?> cir) {
        TrackVehicleLean.beginCapture((VehicleExtension) (Object) this);
    }

    @Inject(method = "getSmoothedVehicleCarsAndPositions(J)Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("RETURN"))
    private void stationannouncer$endLeanCapture(long millisElapsed, CallbackInfoReturnable<?> cir) {
        TrackVehicleLean.endCapture();
    }
}
