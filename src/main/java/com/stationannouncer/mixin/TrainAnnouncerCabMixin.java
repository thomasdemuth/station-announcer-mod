package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.cab.CabHud;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.MutableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <b>What:</b> MTR's trackside Train Announcer blocks ({@code BlockTrainAnnouncer}) post their
 * message to CHAT when a train passes ({@code IDrawing.narrateOrAnnounce} in
 * {@code BlockEntity.lambda$announce$0}, javap 4.0.1 offset 45 — the only call). Chat sits on top
 * of the cab HUD's drive panel (seen on the rig: "The elevator is located at the rear of the
 * platform." over the speed). While the cab HUD draws, the line goes to the HUD's event line for a
 * few seconds instead; the spoken announcement still plays. Same rule as MTR's next-station chat
 * ({@link VehicleExtensionCabMixin}). Client thread.
 */
@Mixin(targets = "org.mtr.mod.block.BlockTrainAnnouncer$BlockEntity", remap = false)
public abstract class TrainAnnouncerCabMixin {
    @Redirect(method = "lambda$announce$0()V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mod/client/IDrawing;narrateOrAnnounce(Ljava/lang/String;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V"))
    private void stationAnnouncer$announceWithoutChat(String narrate, ObjectArrayList<MutableText> chat) {
        if (!CabHud.replacesMtrOverlay() || chat.isEmpty()) {
            org.mtr.mod.client.IDrawing.narrateOrAnnounce(narrate, chat);
            return;
        }
        StringBuilder line = new StringBuilder();
        for (MutableText text : chat) {
            line.append(line.length() == 0 ? "" : " ").append(text.getString());
        }
        CabHud.notice(net.minecraft.text.Text.literal(line.toString()));
        org.mtr.mod.client.IDrawing.narrateOrAnnounce(narrate, new ObjectArrayList<>());
    }
}
