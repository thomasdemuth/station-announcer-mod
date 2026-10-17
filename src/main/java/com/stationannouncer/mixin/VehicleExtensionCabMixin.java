package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.cab.CabHud;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.Text;
import org.mtr.mod.data.VehicleExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <b>What:</b> while the cab HUD is drawing, MTR's riding action-bar line ("Speed: 8.6 m/s
 * (31.1 km/h)" above 5 m/s, else "This station / Next station") is not sent — the cab HUD
 * shows the speed, the stop and the next stop itself, and Thomas asked for the old GUI to be
 * hidden. Passengers and the classic HUD style keep MTR's line.
 *
 * <p>Target (javap 4.0.1): both {@code ClientPlayerEntity.sendMessage(Text, boolean)} calls in
 * {@code VehicleExtension.simulate(J)V} (offsets 261 and 490 — the only two). Client thread.</p>
 */
@Mixin(value = VehicleExtension.class, remap = false)
public abstract class VehicleExtensionCabMixin {
    /**
     * MTR's next-station announcement ({@code IDrawing.narrateOrAnnounce} in
     * {@code lambda$simulate$3}, javap 4.0.1 offset 304) posts its lines to chat, which sits on top
     * of the cab HUD's drive panel. While the cab HUD draws, the chat lines are dropped (the HUD
     * shows the next stop); the spoken announcement still plays.
     */
    @Redirect(method = "lambda$simulate$3(Ljava/lang/String;ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mod/client/IDrawing;narrateOrAnnounce(Ljava/lang/String;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V"))
    private void stationAnnouncer$announceWithoutChat(String narrate,
                                                      org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.mapping.holder.MutableText> chat) {
        org.mtr.mod.client.IDrawing.narrateOrAnnounce(narrate,
                CabHud.replacesMtrOverlay() ? new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<>() : chat);
    }

    @Redirect(method = "simulate(J)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/holder/ClientPlayerEntity;sendMessage(Lorg/mtr/mapping/holder/Text;Z)V"))
    private void stationAnnouncer$hideRidingActionBar(ClientPlayerEntity player, Text text, boolean overlay) {
        if (!CabHud.replacesMtrOverlay()) {
            player.sendMessage(text, overlay);
        }
    }
}
