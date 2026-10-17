package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.cab.CabHud;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.render.DrivingGuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> skips MTR's own driving overlay (speedometer, notch, platform bar —
 * bottom right) while the cab HUD is drawing, so the two never overlap; the cab HUD
 * shows all of it. With the classic HUD style, or whenever the cab HUD has nothing to
 * draw, MTR's overlay renders exactly as stock.
 *
 * <p>Target (javap 4.0.1): {@code public static void render(GraphicsHolder)} on
 * {@code org.mtr.mod.render.DrivingGuiRenderer}. Client thread.</p>
 */
@Mixin(value = DrivingGuiRenderer.class, remap = false)
public abstract class DrivingGuiRendererMixin {
    @Inject(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;)V", at = @At("HEAD"), cancellable = true)
    private static void stationAnnouncer$replacedByCabHud(GraphicsHolder graphicsHolder, CallbackInfo ci) {
        if (CabHud.replacesMtrOverlay()) {
            ci.cancel();
        }
    }
}
