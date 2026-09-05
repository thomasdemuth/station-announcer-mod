package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.ClientPosters;
import com.stationannouncer.client.mtraddon.PosterLayout;
import com.stationannouncer.mtr.ServicePosterBlock;
import com.stationannouncer.mtr.ServicePosterBlockEntity;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/**
 * Paints the assigned service change poster onto the frame's plate through
 * {@link PosterLayout.WorldSurface} — the same layout the editor previews.
 *
 * <p>Poster source, in order: the client's live mirror (so an edit on the
 * dashboard shows here immediately), then the block entity's snapshot (kept
 * after the disruption is gone), then a blank sheet with a hint.</p>
 *
 * <p>Geometry: the plate is the full 16 px width of the block, 20 px tall from
 * y 6 of the lower block, one pixel proud of the wall. The sheet leaves a
 * half-pixel frame all round and is drawn 0.001 in front of the plate face.</p>
 */
@Environment(EnvType.CLIENT)
public class ServicePosterRenderer implements BlockEntityRenderer<ServicePosterBlockEntity> {
    private static final ServicePoster EMPTY = new ServicePoster(0, 0, "Service Change Poster", "", "",
            "", "", java.util.List.of(), "", java.util.List.of(
            ServicePoster.Block.text(ServicePoster.BlockType.TEXT, "Right-click this frame to hang a poster.")),
            "", "");

    @Override
    public void render(ServicePosterBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (!(entity.getCachedState().getBlock() instanceof ServicePosterBlock)) {
            return;
        }
        Direction facing = entity.getCachedState().get(ServicePosterBlock.FACING);
        ServicePoster poster = ClientPosters.byId(entity.getPosterId());
        if (poster == null) {
            poster = entity.getSnapshot();
        }
        if (poster == null) {
            poster = EMPTY;
        }

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));

        // Sheet: x 0.5..15.5 px across the plate, hung from the plate's top edge
        // and growing DOWN as far as the layout needs (PosterLayout.MAX_HEIGHT
        // reaches the plate's bottom edge). Scaled uniformly off the width.
        float sheetWidth = 15.0f / 16.0f;
        float unit = sheetWidth / PosterLayout.WIDTH;
        float top = (float) ((ServicePosterBlock.PLATE_TOP - 0.5) / 16.0);
        float front = 0.5f - 1.0f / 16.0f - 0.001f;   // plate face at model z 15

        // Nameplate convention: negative X and Y scale so the canvas reads
        // left-to-right for a viewer on the -Z (facing) side, origin top-left.
        matrices.translate(sheetWidth / 2.0f, top, front);
        matrices.scale(-unit, -unit, unit);
        PosterLayout.WorldSurface surface = new PosterLayout.WorldSurface(matrices, vertexConsumers);
        PosterLayout.Metrics metrics = PosterLayout.paint(surface, poster);
        // The black frame around the sheet, a hair BEHIND it (positive z is away
        // from the viewer here) and still in front of the plate face.
        float border = 0.5f / 16.0f / unit;   // half a pixel, in canvas units
        surface.rect(-border, -border, PosterLayout.WIDTH + border, metrics.height() + border,
                PosterLayout.BLACK, -1);
        matrices.pop();
    }
}
