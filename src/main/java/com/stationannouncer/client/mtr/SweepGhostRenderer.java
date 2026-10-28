package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.ItemStructureSweeper;
import com.stationannouncer.mtr.SweepGeometry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a picked sweep in the world before it is built: the track itself (a
 * cyan line along the rail with an arrow at the first node, so you can see
 * which way the template runs) and every block the sweep would place as a
 * see-through, full-bright ghost — shown while the sweeper screen is open or
 * the sweeper is held. Only faces that are not buried inside other ghost
 * blocks are drawn, nearest first, up to {@link #MAX_DRAW} blocks.
 */
@Environment(EnvType.CLIENT)
public final class SweepGhostRenderer {
    private static final int MAX_DRAW = 12000;
    private static final float ALPHA = 0.55f;
    private static final Direction[] FACES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};

    private static List<SweepPreview.Cell> visible = List.of();
    private static Map<BlockPos, BlockState> lookup = Map.of();
    private static int builtFor = -1;
    private static Vec3d builtAt = Vec3d.ZERO;

    private SweepGhostRenderer() {
    }

    static boolean shouldShow(MinecraftClient client) {
        if (SweepPreview.track() == null || client.player == null) {
            return false;
        }
        return client.currentScreen instanceof SweepScreen
                || client.player.getMainHandStack().getItem() instanceof ItemStructureSweeper
                || client.player.getOffHandStack().getItem() instanceof ItemStructureSweeper;
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null || !shouldShow(client)) {
            return;
        }
        SweepPreview.update();
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        drawTrack(matrices, consumers, cam, SweepPreview.track());
        prepare(cam);
        if (visible.isEmpty()) {
            return;
        }
        RenderLayer layer = RenderLayer.getEntityTranslucent(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        VertexConsumer ghost = new Faded(consumers.getBuffer(layer));
        BlockRenderManager brm = client.getBlockRenderManager();
        Random random = Random.create();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        for (SweepPreview.Cell cell : visible) {
            BlockState state = cell.state();
            BakedModel model = brm.getModel(state);
            BlockPos pos = cell.pos();
            matrices.push();
            matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            MatrixStack.Entry entry = matrices.peek();
            for (Direction face : FACES) {
                if (face != null && covered(pos.offset(face))) {
                    continue;
                }
                random.setSeed(42L);
                for (BakedQuad quad : model.getQuads(state, face, random)) {
                    float r = 1, g = 1, b = 1;
                    if (quad.hasColor() && client.world != null) {
                        int c = client.getBlockColors().getColor(state, client.world, pos, quad.getColorIndex());
                        r = (c >> 16 & 255) / 255f;
                        g = (c >> 8 & 255) / 255f;
                        b = (c & 255) / 255f;
                    }
                    ghost.quad(entry, quad, r * 0.8f, g * 0.92f, b, light, OverlayTexture.DEFAULT_UV);
                }
            }
            matrices.pop();
        }
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(layer);
        }
    }

    private static boolean covered(BlockPos neighbour) {
        BlockState n = lookup.get(neighbour);
        return n != null && n.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }

    /** Nearest non-buried cells, rebuilt when the plan changes or the camera moved a few blocks. */
    private static void prepare(Vec3d cam) {
        if (builtFor == SweepPreview.version() && builtAt.squaredDistanceTo(cam) < 16) {
            return;
        }
        builtFor = SweepPreview.version();
        builtAt = cam;
        List<SweepPreview.Cell> cells = SweepPreview.ghost();
        Map<BlockPos, BlockState> map = new HashMap<>(cells.size() * 2);
        for (SweepPreview.Cell c : cells) {
            map.put(c.pos(), c.state());
        }
        lookup = map;
        List<SweepPreview.Cell> open = new ArrayList<>();
        for (SweepPreview.Cell c : cells) {
            boolean buried = true;
            for (Direction d : Direction.values()) {
                if (!covered(c.pos().offset(d))) {
                    buried = false;
                    break;
                }
            }
            if (!buried) {
                open.add(c);
            }
        }
        if (open.size() > MAX_DRAW) {
            open.sort((a, b) -> Double.compare(a.pos().getSquaredDistance(cam), b.pos().getSquaredDistance(cam)));
            open = new ArrayList<>(open.subList(0, MAX_DRAW));
        }
        visible = open;
    }

    /** The picked track: a line just above the rail, plus an arrow at the first node pointing the way it runs. */
    private static void drawTrack(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cam, SweepGeometry.Polyline track) {
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry e = matrices.peek();
        for (int i = 1; i < track.size(); i++) {
            line(e, lines, track.x[i - 1] - cam.x, track.y[i - 1] + 0.15 - cam.y, track.z[i - 1] - cam.z,
                    track.x[i] - cam.x, track.y[i] + 0.15 - cam.y, track.z[i] - cam.z);
        }
        if (track.size() >= 2) {
            double[] a = track.at(0), b = track.at(Math.min(2.0, track.length()));
            double dx = b[0] - a[0], dz = b[2] - a[2], len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                dx /= len;
                dz /= len;
                double tipX = a[0] + dx * 1.5, tipZ = a[2] + dz * 1.5, y = a[1] + 0.15 - cam.y;
                double px = -dz, pz = dx;
                line(e, lines, tipX - cam.x, y, tipZ - cam.z, tipX - dx * 0.7 + px * 0.5 - cam.x, y, tipZ - dz * 0.7 + pz * 0.5 - cam.z);
                line(e, lines, tipX - cam.x, y, tipZ - cam.z, tipX - dx * 0.7 - px * 0.5 - cam.x, y, tipZ - dz * 0.7 - pz * 0.5 - cam.z);
            }
        }
    }

    private static void line(MatrixStack.Entry e, VertexConsumer lines, double x1, double y1, double z1, double x2, double y2, double z2) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        lines.vertex(e.getPositionMatrix(), (float) x1, (float) y1, (float) z1).color(0.25f, 0.85f, 1f, 1f)
                .normal(e.getNormalMatrix(), nx, ny, nz).next();
        lines.vertex(e.getPositionMatrix(), (float) x2, (float) y2, (float) z2).color(0.25f, 0.85f, 1f, 1f)
                .normal(e.getNormalMatrix(), nx, ny, nz).next();
    }

    /** Passes everything through with the alpha scaled down: the ghost look. */
    private record Faded(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, (int) (alpha * ALPHA));
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            delegate.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            delegate.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void next() {
            delegate.next();
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                           int overlay, int light, float normalX, float normalY, float normalZ) {
            delegate.vertex(x, y, z, red, green, blue, alpha * ALPHA, u, v, overlay, light, normalX, normalY, normalZ);
        }

        @Override
        public void fixedColor(int red, int green, int blue, int alpha) {
            delegate.fixedColor(red, green, blue, (int) (alpha * ALPHA));
        }

        @Override
        public void unfixColor() {
            delegate.unfixColor();
        }
    }
}
