package com.stationannouncer.atmosphere.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.stationannouncer.atmosphere.LightLuminance;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-position luminance for failing lights (dim / warm / dead), on the
 * server AND the client light engine. Two entry points read a block's
 * luminance: {@code getLightSourceLuminance} (live block updates) and the
 * initial whole-chunk pass (the lambda target {@code method_51532}).
 */
@Mixin(ChunkBlockLightProvider.class)
public abstract class ChunkBlockLightProviderMixin {
    @Inject(method = "getLightSourceLuminance", at = @At("RETURN"), cancellable = true)
    private void stationAnnouncer$conditionLuminance(long pos, BlockState state, CallbackInfoReturnable<Integer> cir) {
        int value = cir.getReturnValueI();
        if (value > 0) {
            ChunkProvider provider = ((ChunkLightProviderAccessor) this).stationAnnouncer$chunkProvider();
            int adjusted = LightLuminance.adjust(provider.getWorld(), pos, value);
            if (adjusted != value) {
                cir.setReturnValue(adjusted);
            }
        }
    }

    @ModifyExpressionValue(method = "method_51532",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;getLuminance()I"))
    private int stationAnnouncer$conditionLuminanceInitial(int value, BlockPos pos, BlockState state) {
        if (value <= 0) {
            return value;
        }
        ChunkProvider provider = ((ChunkLightProviderAccessor) this).stationAnnouncer$chunkProvider();
        return LightLuminance.adjust(provider.getWorld(), pos.asLong(), value);
    }
}
