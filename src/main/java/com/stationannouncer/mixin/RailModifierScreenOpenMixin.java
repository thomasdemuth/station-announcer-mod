package com.stationannouncer.mixin;

import com.stationannouncer.client.track.TrackEditorScreen;
import org.mtr.mod.packet.ClientPacketHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> HEAD-cancels {@link ClientPacketHelper#openRailShapeModifierScreen(String)} — the
 * call that opens MTR's Rail Shape Modifier screen — and opens the station-announcer track editor
 * ({@link TrackEditorScreen}) instead, on every rail at the clicked node (Thomas's decision: ours
 * REPLACES MTR's screen; it still offers MTR's shape, vertical radius, styles and flip styles).
 *
 * <p><b>Why here:</b> in MTR 4.0.1 the ONLY caller is {@code BlockNode.onUse2} (brush +
 * right-click a rail node, client side), which has just resolved the facing rail with
 * {@code MinecraftClientData.getFacingRailAndBlockPos(false)}. Hooking the static helper instead of
 * {@code BlockNode} keeps the mixin on a client-only class; the editor re-asks
 * {@code getFacingRailAndBlockPos} for the clicked node (same frame, same crosshair → same answer)
 * and falls back to the rail's first node. Verified with a grep of the decompiled 4.0.1 sources:
 * no other class calls {@code openRailShapeModifierScreen} or constructs {@code RailModifierScreen}.</p>
 *
 * <p><b>Fallback:</b> if the editor cannot load the rail (no track integration, cable rail, any
 * exception) {@link TrackEditorScreen#openFromMtr} returns false and MTR's own screen opens as
 * before.</p>
 *
 * <p>Target verified with javap against MTR FABRIC-4.0.1+1.20.4:
 * {@code public static void openRailShapeModifierScreen(java.lang.String)}. Register in the
 * mixin config's {@code client} section.</p>
 */
@Mixin(value = ClientPacketHelper.class, remap = false)
public class RailModifierScreenOpenMixin {
    @Inject(method = "openRailShapeModifierScreen", at = @At("HEAD"), cancellable = true)
    private static void stationannouncer$openTrackEditor(String railId, CallbackInfo ci) {
        if (TrackEditorScreen.openFromMtr(railId)) {
            ci.cancel();
        }
    }
}
