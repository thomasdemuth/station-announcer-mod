package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.AnnouncementComposer;
import com.stationannouncer.client.mtraddon.ClientAnnouncementTemplates;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.mod.data.VehicleExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-route announcement templates: intercepts MTR's on-board next-station
 * announcement at the point where the server's disabled-flag reply lands
 * ({@code lambda$simulate$3}, the consumer of
 * {@code PacketCheckRouteIdHasDisabledAnnouncements} — the moment MTR would
 * compose and speak/print its stock wording). When our synced template map has
 * an entry for the vehicle's current route, the template is rendered and sent
 * through MTR's own {@code IDrawing.narrateOrAnnounce}, and MTR's default is
 * cancelled. No template → untouched stock behaviour; MTR's per-route
 * "Disable Next Station Announcements" checkbox stays the master kill switch
 * either way.
 *
 * <p>Targeting a synthetic lambda by name is deliberate and safe here: the mod
 * pins MTR {@code 4.0.1} (gradle.properties), and the signature was
 * javap-verified against that exact jar.</p>
 */
@Mixin(value = VehicleExtension.class, remap = false)
public abstract class VehicleExtensionAnnounceMixin {

    @Inject(method = "lambda$simulate$3(Ljava/lang/String;ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;)V",
            at = @At("HEAD"), cancellable = true)
    private void stationAnnouncer$templatedAnnouncement(String thisRouteName, int thisRouteColor,
                                                        String nextRouteName, int nextRouteColor,
                                                        String thisStationName, String nextStationName,
                                                        Boolean isDisabled, CallbackInfo ci) {
        VehicleExtraData extra = ((Vehicle) (Object) this).vehicleExtraData;
        String template = ClientAnnouncementTemplates.get(extra.getThisRouteId());
        if (template == null || template.isEmpty()) {
            return; // no template for this route — MTR's stock announcement runs
        }
        ci.cancel();
        if (isDisabled != null && isDisabled) {
            return; // the route's disable checkbox still silences everything
        }
        AnnouncementComposer.announce(AnnouncementComposer.render(template, extra));
    }
}
