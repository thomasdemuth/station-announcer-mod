package com.stationannouncer.mixin;

import com.stationannouncer.client.track.TrackRailRenderer;
import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.render.RenderRails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> draws TILTED rails (track geometry feature, rail cant) banked about the low rail.
 * For a rail whose active custom path has tilt, MTR's per-rail drawing is HEAD-cancelled and
 * {@link TrackRailRenderer} draws the same things banked; every other rail returns immediately
 * and MTR draws it untouched.
 *
 * <ul>
 *   <li>{@code renderRailStandard(ClientWorld, Rail, float, RenderState, float, Identifier, float×4)} —
 *       the 10-argument overload is the ONLY place styles (incl. {@code default_3d}) and the flat
 *       default rail are drawn; the 4-argument TRAIN/BOAT overload just delegates to it, and the cable
 *       car / airplane branches call it directly, so hooking it alone covers every transport mode
 *       exactly once. The cable car's line pass is not touched (cable rails never take a profile).</li>
 *   <li>{@code renderSignalsStandard(ClientWorld, Rail)} and {@code renderRailOneWayArrows(Rail, float)}
 *       — the coloured overlays shown while holding rail-related items.</li>
 * </ul>
 *
 * {@code RenderState} is a private enum, hence {@link Coerce} to Object; its constants are
 * NORMAL / COLORED / FLASHING ({@code hasColor} = not NORMAL).
 *
 * Descriptors verified with javap against MTR FABRIC-4.0.1+1.20.4. Client-only target: register in
 * the mixin config's {@code client} section.
 */
@Mixin(value = RenderRails.class, remap = false)
public class RenderRailsTiltMixin {
    @Inject(method = "renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;FLorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V", at = @At("HEAD"), cancellable = true)
    private static void stationAnnouncer$renderTiltedRail(ClientWorld clientWorld, Rail rail, float yOffset, @Coerce Object renderState, float railWidth, Identifier defaultTexture, float u1, float v1, float u2, float v2, CallbackInfo ci) {
        final String state = renderState instanceof Enum<?> ? ((Enum<?>) renderState).name() : "NORMAL";
        final boolean hasColor = !"NORMAL".equals(state);
        final boolean flashing = "FLASHING".equals(state);
        if (TrackRailRenderer.renderStandard(clientWorld, rail, yOffset, hasColor, flashing, railWidth, defaultTexture, u1, v1, u2, v2)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSignalsStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;)V", at = @At("HEAD"), cancellable = true)
    private static void stationAnnouncer$renderTiltedSignals(ClientWorld clientWorld, Rail rail, CallbackInfo ci) {
        if (TrackRailRenderer.renderSignals(clientWorld, rail)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderRailOneWayArrows(Lorg/mtr/core/data/Rail;F)V", at = @At("HEAD"), cancellable = true)
    private static void stationAnnouncer$renderTiltedArrows(Rail rail, float yOffset, CallbackInfo ci) {
        if (TrackRailRenderer.renderOneWayArrows(rail, yOffset)) {
            ci.cancel();
        }
    }
}
