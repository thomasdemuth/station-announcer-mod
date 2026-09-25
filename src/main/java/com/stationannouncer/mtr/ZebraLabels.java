package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.ZebraBoardBlock;
import com.stationannouncer.block.ZebraBoardBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Zebra board labels, server side: makes the MTR brush the board's edit tool
 * and applies what the label editor saves. The board block and its block
 * entity are common code; only the brush and this packet need MTR.
 */
public final class ZebraLabels {
    /** C2S: the label editor saves (pos + text + alignment). */
    public static final Identifier UPDATE_ZEBRA_LABEL_C2S = StationAnnouncer.id("update_zebra_label");

    private ZebraLabels() {
    }

    public static void register() {
        ZebraBoardBlock.EDIT_TOOL = stack -> stack.isOf(org.mtr.mod.Items.BRUSH.get().data);

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_ZEBRA_LABEL_C2S,
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    String text = buf.readString(ZebraBoardBlockEntity.MAX_TEXT_LENGTH);
                    ZebraBoardBlockEntity.Align align = ZebraBoardBlockEntity.Align.byOrdinal(buf.readByte());
                    server.execute(() -> {
                        ServerWorld world = player.getServerWorld();
                        if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0
                                || !world.canPlayerModifyAt(player, pos)
                                || !(world.getBlockEntity(pos) instanceof ZebraBoardBlockEntity board)) {
                            return;
                        }
                        board.setText(text);
                        board.setAlign(align);
                        board.sync();
                    });
                });
    }
}
