package com.stationannouncer.mixin;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.AddonRenderLifts;
import com.stationannouncer.client.mtraddon.ClientLiftDoors;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.render.RenderLifts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> HEAD-cancels {@link RenderLifts#render(long, Vector3d)} — MTR's
 * per-frame lift renderer — and delegates to
 * {@link AddonRenderLifts#render(long, Vector3d)}, which renders configured
 * lifts with doorway boxes, cab door openings and boarding on any of the four
 * cab sides, and runs logic identical to stock for every unconfigured lift
 * (Feature 3, multi-sided multi-door elevators).
 *
 * <p><b>Why a mixin:</b> the doorway boxes, cab model and player
 * mount/move calls are all hardcoded inside this one static method (front
 * {@code -Z} always, back {@code +Z} only when double-sided); there is no hook
 * for extra doorways, so the whole method is swapped for a faithful adaptation.
 * Registered in the mixin config's {@code client} section — the target class is
 * client-only.</p>
 *
 * <p><b>Thread:</b> the RENDER thread (called from MTR's MainRenderer every
 * frame). Everything it reads — {@link ClientLiftDoors}' map (swapped wholesale
 * on the client thread by the sync packet) and MTR's own client data — is what
 * stock reads on the same thread.</p>
 *
 * <p><b>Toggle:</b> {@code multiDoorLifts.enabled} in
 * {@code config/station-announcer-addon.json} (server side): when off, the
 * server syncs an EMPTY door-side map, so the {@code ClientLiftDoors.isEmpty()}
 * guard below fails and this injection no-ops with a single static map read —
 * MTR's stock path then runs completely untouched. The same O(1) bail covers
 * "feature on but no lift configured".</p>
 *
 * <p><b>Fallback:</b> the delegate is wrapped in try/catch; if it ever throws,
 * the takeover is disabled for the rest of the session (logged once) and the
 * frame falls through to stock rendering, so a defect here can never crash the
 * render loop or blank all lifts permanently.</p>
 *
 * <p>Target verified with javap against MTR FABRIC-4.0.1+1.20.4:
 * {@code public static void render(long, org.mtr.mapping.holder.Vector3d)}.</p>
 */
@Mixin(value = RenderLifts.class, remap = false)
public class RenderLiftsMixin {
    @Unique
    private static boolean stationAnnouncer$broken = false;

    @Inject(method = "render(JLorg/mtr/mapping/holder/Vector3d;)V", at = @At("HEAD"), cancellable = true)
    private static void stationAnnouncer$renderMultiDoorLifts(long millisElapsed, Vector3d cameraShakeOffset, CallbackInfo ci) {
        if (stationAnnouncer$broken || ClientLiftDoors.isEmpty()) {
            return; // O(1) bail: stock RenderLifts.render runs untouched
        }
        try {
            AddonRenderLifts.render(millisElapsed, cameraShakeOffset);
            ci.cancel();
        } catch (Throwable e) {
            // Throwable, not Exception: a linkage error (e.g. an MTR update changing a
            // member) must also fall back to stock instead of crashing the render loop.
            stationAnnouncer$broken = true;
            StationAnnouncer.LOGGER.error("Multi-door lift renderer failed; falling back to stock MTR lift rendering for this session", e);
            // no cancel: stock renders this frame (worst case one partially double-rendered frame)
        }
    }
}
