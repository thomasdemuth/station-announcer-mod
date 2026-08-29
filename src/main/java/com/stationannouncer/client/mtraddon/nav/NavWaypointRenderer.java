package com.stationannouncer.client.mtraddon.nav;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * The in-world marker at the current navigation target: a bobbing diamond, a
 * slim vertical beam so it can be picked out from across a station, and the
 * live distance in blocks drawn facing the player.
 *
 * <p><b>Render phase.</b> {@code WorldRenderEvents.AFTER_ENTITIES} with OPAQUE
 * geometry, which is the one combination this project has found to render
 * stably (CLAUDE.md, iteration 14: translucent debug quads outside the block
 * entity pipeline flicker; the vanilla line shader renders worse still).</p>
 *
 * <p><b>Through walls.</b> The marker uses vanilla's debug-quad layer and
 * disables the depth test around its own explicit flush (see
 * {@link #THROUGH_WALLS}) — a custom {@code RenderLayer} is impossible from a
 * mod because {@code RenderLayer.MultiPhaseParameters} is protected. The text
 * rides vanilla's {@code SEE_THROUGH} text layer, which needs no such help.</p>
 *
 * <p>Costs nothing when navigation is off: the callback returns on a null
 * snapshot before touching a matrix.</p>
 */
@Environment(EnvType.CLIENT)
public final class NavWaypointRenderer {
    /**
     * Opaque quads, drawn with the depth test switched OFF around an explicit flush
     * so the marker shows through terrain.
     *
     * <p>A bespoke {@code RenderLayer} would be the tidy way to say "always depth
     * test", but {@code RenderLayer.MultiPhaseParameters} is PROTECTED — only
     * subclasses in {@code net.minecraft.client.render} may build one, so
     * {@code RenderLayer.of(...)} is unusable from a mod. Instead we reuse the same
     * layer {@code LinkLineRenderer} has rendered rock-solid for years
     * (CLAUDE.md: opaque colours in the entity phase; the line shader and
     * translucent quads both flicker) and toggle depth around the draw ourselves.
     * The flush MUST happen while the test is still disabled — the vertices are
     * only rasterised at {@code draw()}.</p>
     */
    private static final RenderLayer THROUGH_WALLS = RenderLayer.getDebugQuads();

    /** Half-width of the marker diamond, in blocks. */
    private static final float MARKER_RADIUS = 0.45f;

    /** Half-thickness of the beam, in blocks. */
    private static final float BEAM_RADIUS = 0.06f;

    /** How far the beam runs above and below the target. */
    private static final float BEAM_UP = 24.0f;
    private static final float BEAM_DOWN = 3.0f;

    /** The marker floats this far above the target and bobs by this much. */
    private static final float MARKER_LIFT = 1.6f;
    private static final float BOB_AMPLITUDE = 0.15f;

    /** Beyond this many blocks the marker is scaled up so it stays visible. */
    private static final double SCALE_START = 24;
    private static final double MAX_SCALE = 4.0;

    private static final int MARKER_COLOR = 0xFFFFCC33;
    private static final int BEAM_COLOR = 0xFFFFAA00;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private NavWaypointRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(NavWaypointRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (!AddonClientConfig.get().navWaypointEnabled) {
            return;
        }
        ClientNav.Snapshot snapshot = ClientNav.snapshot();
        if (snapshot == null || !snapshot.active() || snapshot.target() == null) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null || context.camera() == null) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        Vec3d target = snapshot.target();

        matrices.push();
        matrices.translate(target.x - camera.x, target.y - camera.y, target.z - camera.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer buffer = consumers.getBuffer(THROUGH_WALLS);

        // Beam first, then the diamond on top of it.
        beam(matrix, buffer);
        float bob = BOB_AMPLITUDE * MathHelper.sin(
                (System.currentTimeMillis() % 4000L) / 4000.0f * 2.0f * (float) Math.PI);
        diamond(matrix, buffer, MARKER_LIFT + bob);
        matrices.pop();

        // Flush inside the no-depth window: the quads are only rasterised by
        // draw(), so disabling the test after buffering would achieve nothing.
        // Restore immediately — everything drawn after us in this frame (the
        // distance text included) expects the normal depth state back.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            RenderSystem.disableDepthTest();
            immediate.draw(THROUGH_WALLS);
            RenderSystem.enableDepthTest();
        }

        drawDistance(context, matrices, consumers, camera, target, snapshot);
    }

    // ------------------------------------------------------------- geometry

    /** A slim four-sided column through the target so the marker reads from far off. */
    private static void beam(Matrix4f matrix, VertexConsumer buffer) {
        float r = BEAM_RADIUS;
        float bottom = -BEAM_DOWN;
        float top = BEAM_UP;
        // Two crossed ribbons — visible from every angle, no culling needed.
        quad(matrix, buffer, -r, bottom, 0, r, bottom, 0, r, top, 0, -r, top, 0, BEAM_COLOR);
        quad(matrix, buffer, 0, bottom, -r, 0, bottom, r, 0, top, r, 0, top, -r, BEAM_COLOR);
    }

    /** An octahedral marker: eight triangles drawn as degenerate quads. */
    private static void diamond(Matrix4f matrix, VertexConsumer buffer, float lift) {
        float r = MARKER_RADIUS;
        float top = lift + r;
        float bottom = lift - r;
        float mid = lift;
        float[][] ring = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
        for (int i = 0; i < 4; i++) {
            float[] a = ring[i];
            float[] b = ring[(i + 1) % 4];
            // Upper face.
            quad(matrix, buffer,
                    a[0], mid, a[1],
                    b[0], mid, b[1],
                    0, top, 0,
                    0, top, 0, MARKER_COLOR);
            // Lower face, shaded a touch darker so the solid reads as a solid.
            quad(matrix, buffer,
                    b[0], mid, b[1],
                    a[0], mid, a[1],
                    0, bottom, 0,
                    0, bottom, 0, shade(MARKER_COLOR, 0.7f));
        }
    }

    private static void quad(Matrix4f matrix, VertexConsumer buffer,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4, int argb) {
        vertex(matrix, buffer, x1, y1, z1, argb);
        vertex(matrix, buffer, x2, y2, z2, argb);
        vertex(matrix, buffer, x3, y3, z3, argb);
        vertex(matrix, buffer, x4, y4, z4, argb);
        // Reverse winding too, so the face shows whatever side the camera is on.
        vertex(matrix, buffer, x4, y4, z4, argb);
        vertex(matrix, buffer, x3, y3, z3, argb);
        vertex(matrix, buffer, x2, y2, z2, argb);
        vertex(matrix, buffer, x1, y1, z1, argb);
    }

    private static void vertex(Matrix4f matrix, VertexConsumer buffer, float x, float y, float z, int argb) {
        buffer.vertex(matrix, x, y, z)
                .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                .next();
    }

    private static int shade(int argb, float factor) {
        int r = Math.round(((argb >> 16) & 0xFF) * factor);
        int g = Math.round(((argb >> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ----------------------------------------------------------------- text

    /** Distance in blocks (plus the target's name), billboarded at the marker. */
    private static void drawDistance(WorldRenderContext context, MatrixStack matrices,
                                     VertexConsumerProvider consumers, Vec3d camera, Vec3d target,
                                     ClientNav.Snapshot snapshot) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        if (font == null) {
            return;
        }
        double distance = snapshot.distance();
        String line = distance < 0
                ? snapshot.targetLabel()
                : Text.translatable("gui.station_announcer.nav.waypoint_distance",
                        Math.round(distance)).getString();
        if (line.isEmpty()) {
            return;
        }
        double eye = camera.distanceTo(target);
        float scale = (float) (0.025 * MathHelper.clamp(eye / SCALE_START, 1.0, MAX_SCALE));

        matrices.push();
        matrices.translate(target.x - camera.x, target.y - camera.y + MARKER_LIFT + 0.9,
                target.z - camera.z);
        matrices.multiply(context.camera().getRotation());
        matrices.scale(-scale, -scale, scale);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float width = font.getWidth(line);
        font.draw(line, -width / 2.0f, 0.0f, TEXT_COLOR, false, matrix, consumers,
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
        matrices.pop();
        // No flush here: the world renderer's own immediate buffer draws the
        // text layer at the end of the entity phase, exactly as it does for
        // vanilla name tags.
    }
}
