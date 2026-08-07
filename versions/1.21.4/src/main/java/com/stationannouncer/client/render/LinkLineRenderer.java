package com.stationannouncer.client.render;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.PaDisplay;
import com.stationannouncer.block.SpeakerBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import java.util.HashSet;
import java.util.Set;

/**
 * While a Speaker Link is held (either hand), draws a thin, gently pulsing
 * white beam between every PA Control Box and each of its linked speakers —
 * in the style of MTR's lift/track connection preview. Beams are plain
 * translucent world-space quads (two crossed ribbons per link), which renders
 * stably from every angle, unlike the vanilla line shader.
 *
 * Pairs are gathered from both ends (boxes' speaker lists and speakers'
 * stored box positions, both synced to the client) and deduplicated, scanning
 * the chunks around the camera.
 */
@Environment(EnvType.CLIENT)
public final class LinkLineRenderer {
    /** How far around the player to look for boxes/speakers, in chunks. */
    private static final int SCAN_CHUNK_RADIUS = 6;

    /** Half-thickness of a beam, in blocks. */
    private static final float BEAM_RADIUS = 0.05f;

    /** One box↔speaker beam, deduplicated by its two packed endpoints. */
    private record Link(long boxPacked, long speakerPacked) {
    }

    private LinkLineRenderer() {
    }

    public static void register() {
        // Entity phase: same pipeline the PIDS screens use, which renders
        // reliably (AFTER_TRANSLUCENT + translucent quads flickered badly).
        WorldRenderEvents.AFTER_ENTITIES.register(LinkLineRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        ClientWorld world = context.world();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (player == null || world == null || matrices == null || consumers == null) {
            return;
        }
        if (!player.getMainHandStack().isOf(ModContent.SPEAKER_LINK)
                && !player.getOffHandStack().isOf(ModContent.SPEAKER_LINK)) {
            return;
        }

        Set<Link> links = collectLinks(world, player.getChunkPos());
        if (links.isEmpty()) {
            return;
        }

        // Gentle MTR-style pulse: fully opaque (translucency caused shimmering
        // where the crossed ribbons overlap), pulsing brightness instead.
        float pulse = 0.85f + 0.15f * MathHelper.sin((world.getTime() % 24000 + context.tickDelta()) * 0.25f);

        Vec3d camera = context.camera().getPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        for (Link link : links) {
            Vec3d from = Vec3d.ofCenter(BlockPos.fromLong(link.boxPacked()));
            Vec3d to = Vec3d.ofCenter(BlockPos.fromLong(link.speakerPacked()));
            drawBeam(matrix, buffer, from, to, pulse);
        }
        matrices.pop();
    }

    private static Set<Link> collectLinks(ClientWorld world, ChunkPos center) {
        Set<Link> links = new HashSet<>();
        for (int cx = center.x - SCAN_CHUNK_RADIUS; cx <= center.x + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = center.z - SCAN_CHUNK_RADIUS; cz <= center.z + SCAN_CHUNK_RADIUS; cz++) {
                if (!(world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof ControlBoxBlockEntity box) {
                        for (BlockPos speakerPos : box.getSpeakers()) {
                            links.add(new Link(box.getPos().asLong(), speakerPos.asLong()));
                        }
                        for (BlockPos displayPos : box.getDisplays()) {
                            links.add(new Link(box.getPos().asLong(), displayPos.asLong()));
                        }
                    } else if (be instanceof SpeakerBlockEntity speaker && speaker.getControlBoxPos() != null) {
                        links.add(new Link(speaker.getControlBoxPos().asLong(), speaker.getPos().asLong()));
                    } else if (be instanceof PaDisplay display && display.getPaControlBoxPos() != null) {
                        links.add(new Link(display.getPaControlBoxPos().asLong(), be.getPos().asLong()));
                    }
                }
            }
        }
        return links;
    }

    /** Two crossed white ribbons along the segment — visible from every angle. */
    private static void drawBeam(Matrix4f matrix, VertexConsumer buffer, Vec3d from, Vec3d to, float alpha) {
        Vec3d delta = to.subtract(from);
        if (delta.lengthSquared() < 1.0e-4) {
            return;
        }
        Vec3d dir = delta.normalize();
        Vec3d reference = Math.abs(dir.y) > 0.99 ? new Vec3d(1.0, 0.0, 0.0) : new Vec3d(0.0, 1.0, 0.0);
        Vec3d side1 = dir.crossProduct(reference).normalize().multiply(BEAM_RADIUS);
        Vec3d side2 = dir.crossProduct(side1).normalize().multiply(BEAM_RADIUS);

        ribbon(matrix, buffer, from, to, side1, alpha);
        ribbon(matrix, buffer, from, to, side2, alpha);
    }

    private static void ribbon(Matrix4f matrix, VertexConsumer buffer, Vec3d from, Vec3d to, Vec3d side, float brightness) {
        Vec3d a = from.add(side);
        Vec3d b = to.add(side);
        Vec3d c = to.subtract(side);
        Vec3d d = from.subtract(side);
        // Both windings so the ribbon is double-sided regardless of cull state.
        quad(matrix, buffer, a, b, c, d, brightness);
        quad(matrix, buffer, d, c, b, a, brightness);
    }

    private static void quad(Matrix4f matrix, VertexConsumer buffer,
                             Vec3d a, Vec3d b, Vec3d c, Vec3d d, float brightness) {
        vertex(matrix, buffer, a, brightness);
        vertex(matrix, buffer, b, brightness);
        vertex(matrix, buffer, c, brightness);
        vertex(matrix, buffer, d, brightness);
    }

    private static void vertex(Matrix4f matrix, VertexConsumer buffer, Vec3d pos, float brightness) {
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(brightness, brightness, brightness, 1.0f)
                .next();
    }
}
