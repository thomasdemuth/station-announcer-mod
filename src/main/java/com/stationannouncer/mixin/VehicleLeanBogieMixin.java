package com.stationannouncer.mixin;

import com.stationannouncer.client.track.TrackVehicleLean;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vehicle lean (track tilt): MTR's private {@code Vehicle.getBogiePositions(value, overrideY)} receives
 * each bogie's exact PATH DISTANCE — the one number the lean needs. Records it (as a tilt) for the
 * vehicle {@link TrackVehicleLean} is currently capturing; for every other Vehicle — including the
 * integrated server's simulation in single player — the hook is one static identity compare.
 *
 * <p>Register in the CLIENT section (the target is a core class, but only the client renders;
 * a dedicated server never loads this). Signature javap-verified against MTR FABRIC-4.0.1+1.20.4:
 * {@code private ObjectObjectImmutablePair getBogiePositions(double, DoubleArrayList)}.</p>
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleLeanBogieMixin {
    @Inject(method = "getBogiePositions(DLorg/mtr/libraries/it/unimi/dsi/fastutil/doubles/DoubleArrayList;)Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectObjectImmutablePair;", at = @At("RETURN"))
    private void stationannouncer$recordBogieTilt(double value, DoubleArrayList overrideY, CallbackInfoReturnable<ObjectObjectImmutablePair<Vector, Vector>> cir) {
        TrackVehicleLean.recordBogie(this, value, cir.getReturnValue());
    }
}
