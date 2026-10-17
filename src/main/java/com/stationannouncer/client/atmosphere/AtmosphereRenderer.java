package com.stationannouncer.client.atmosphere;

import com.stationannouncer.atmosphere.LightCondition;
import com.stationannouncer.atmosphere.LightConditionState;
import com.stationannouncer.atmosphere.MaintenanceToolItem;
import com.stationannouncer.block.FareMachineBlock;
import com.stationannouncer.mtr.TurnstileBlock;
import com.stationannouncer.mtr.TurnstileHeetBlock;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the atmosphere layer's per-position effects, entity phase:
 * <ul>
 *   <li><b>Failing lamps</b>: the light block's own model drawn again, a
 *       hair in front of each face, at the lamp's current "lens" brightness (and WARM tint)
 *       — so a flickering tube really goes dark for a frame without the
 *       room's light level changing (that only changes for DIM/WARM/DEAD,
 *       through the light engine).</li>
 *   <li><b>Maintenance markers</b> while the Maintenance Tool is held: a
 *       coloured frame on every light with a condition.</li>
 *   <li><b>Out of service</b>: a black bag + "OUT OF ORDER" sign over a
 *       MetroCard machine, caution tape across a turnstile / HEET lane.</li>
 * </ul>
 * Never holds a VertexConsumer across a text draw or a layer switch (text
 * switches layers and flushes the buffer — that crashed the stop markers once).
 */
@Environment(EnvType.CLIENT)
public final class AtmosphereRenderer {
    private static final double LAMP_RANGE = 72;
    private static final double MARKER_RANGE = 48;
    private static final double OOS_RANGE = 48;
    private static final float LAMP_OFFSET = 0.004f;
    private static final Direction[] FACES_AND_NULL = {Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null};

    private AtmosphereRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(AtmosphereRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (world == null || player == null || matrices == null || consumers == null) {
            return;
        }
        Vec3d cam = context.camera().getPos();
        drawWeatheringBox(client, player, matrices, consumers, cam);
        if (AtmosphereClient.LIGHTS.isEmpty() && AtmosphereClient.OUT_OF_SERVICE.isEmpty()) {
            return;
        }
        Frustum frustum = context.frustum();
        long now = System.currentTimeMillis();
        boolean holdingTool = player.getMainHandStack().getItem() instanceof MaintenanceToolItem
                || player.getOffHandStack().getItem() instanceof MaintenanceToolItem;

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        List<long[]> markers = holdingTool ? new ArrayList<>() : null;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (Long2IntMap.Entry e : AtmosphereClient.LIGHTS.long2IntEntrySet()) {
            long packedPos = e.getLongKey();
            mutable.set(packedPos);
            double dx = mutable.getX() + 0.5 - cam.x;
            double dy = mutable.getY() + 0.5 - cam.y;
            double dz = mutable.getZ() + 0.5 - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > LAMP_RANGE * LAMP_RANGE) {
                continue;
            }
            if (frustum != null && !frustum.isVisible(new Box(mutable))) {
                continue;
            }
            int packed = e.getIntValue();
            LightCondition condition = LightConditionState.condition(packed);
            if (markers != null && d2 < MARKER_RANGE * MARKER_RANGE) {
                markers.add(new long[]{packedPos, condition.color});
            }
            BlockState state = world.getBlockState(mutable);
            if (state.getLuminance() <= 0 || state.isAir()) {
                continue; // switched off anyway (or the block is gone)
            }
            int seed = LightCondition.seed(packedPos, LightConditionState.salt(packed));
            float lens = condition.lens(seed, now);
            if (lens >= 0.985f && condition != LightCondition.WARM) {
                continue;
            }
            drawLamp(world, matrices, consumers, mutable.toImmutable(), state, condition, lens);
        }

        if (!AtmosphereClient.OUT_OF_SERVICE.isEmpty()) {
            drawOutOfService(world, matrices, consumers, cam, client.textRenderer);
        }
        if (markers != null && !markers.isEmpty()) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(now / 220.0);
            VertexConsumer lines = consumers.getBuffer(RenderLayer.getDebugQuads());
            Matrix4f m = matrices.peek().getPositionMatrix();
            for (long[] mk : markers) {
                BlockPos p = BlockPos.fromLong(mk[0]);
                int c = (int) mk[1];
                float r = ((c >> 16) & 0xFF) / 255f * pulse;
                float g = ((c >> 8) & 0xFF) / 255f * pulse;
                float b = (c & 0xFF) / 255f * pulse;
                Quads.boxEdges(m, lines, p.getX() - 0.03, p.getY() - 0.03, p.getZ() - 0.03,
                        p.getX() + 1.03, p.getY() + 1.03, p.getZ() + 1.03, 0.018, r, g, b);
            }
        }
        matrices.pop();
    }

    // ------------------------------------------------------- weathering box

    /** First corner picked: the box it would weather, out to the block under the crosshair. */
    private static void drawWeatheringBox(MinecraftClient client, ClientPlayerEntity player, MatrixStack matrices,
                                          VertexConsumerProvider consumers, Vec3d cam) {
        net.minecraft.item.ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof com.stationannouncer.atmosphere.WeatheringToolItem)) {
            return;
        }
        BlockPos a = com.stationannouncer.atmosphere.WeatheringToolItem.corner(stack);
        if (a == null) {
            return;
        }
        BlockPos b = a;
        if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            b = hit.getBlockPos();
        }
        com.stationannouncer.atmosphere.WeatherSpec spec = com.stationannouncer.atmosphere.WeatherSpec.read(stack);
        int top = Math.max(a.getY(), b.getY()) + (a.getY() == b.getY() ? spec.height : 0);
        float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getDebugQuads());
        Quads.boxEdges(matrices.peek().getPositionMatrix(), lines,
                Math.min(a.getX(), b.getX()) - 0.02, Math.min(a.getY(), b.getY()) - 0.02, Math.min(a.getZ(), b.getZ()) - 0.02,
                Math.max(a.getX(), b.getX()) + 1.02, top + 1.02, Math.max(a.getZ(), b.getZ()) + 1.02,
                0.03, 0.62f * pulse, 0.45f * pulse, 0.25f * pulse);
        matrices.pop();
    }

    // ----------------------------------------------------------------- lamps

    private static void drawLamp(ClientWorld world, MatrixStack matrices, VertexConsumerProvider consumers,
                                 BlockPos pos, BlockState state, LightCondition condition, float lens) {
        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        if (model == null) {
            return;
        }
        // Dim by COLOUR at full light: dimming through the lightmap tints a
        // tube brown (vanilla's warm block-light ramp). A burnt-out fixture is
        // only as bright as the room around it (its own light is gone, so the
        // light just below it is everyone else's).
        float f = lens;
        if (condition == LightCondition.DEAD) {
            int ambient = world.getLightLevel(pos.down());
            f = Math.max(0.16f, ambient / 15f * 0.7f);
        } else if (condition.flickers()) {
            f = Math.max(0.16f, lens);
        }
        float[] tint = condition.tint();
        float tr = tint[0] * f;
        float tg = tint[1] * f;
        float tb = tint[2] * f;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        VertexConsumer buffer = consumers.getBuffer(RenderLayers.getEntityBlockLayer(state, false));
        long seed = state.getRenderingSeed(pos);
        Random random = Random.create();
        for (Direction face : FACES_AND_NULL) {
            random.setSeed(seed);
            for (BakedQuad quad : model.getQuads(state, face, random)) {
                float r = tr;
                float g = tg;
                float b = tb;
                if (quad.hasColor()) {
                    int color = MinecraftClient.getInstance().getBlockColors().getColor(state, world, pos, quad.getColorIndex());
                    r *= ((color >> 16) & 0xFF) / 255f;
                    g *= ((color >> 8) & 0xFF) / 255f;
                    b *= (color & 0xFF) / 255f;
                }
                // a hair in front of the real face, along its own normal
                Direction n = quad.getFace();
                matrices.push();
                matrices.translate(pos.getX() + n.getOffsetX() * LAMP_OFFSET, pos.getY() + n.getOffsetY() * LAMP_OFFSET,
                        pos.getZ() + n.getOffsetZ() * LAMP_OFFSET);
                buffer.quad(matrices.peek(), quad, r, g, b, light, OverlayTexture.DEFAULT_UV);
                matrices.pop();
            }
        }
    }

    // ------------------------------------------------------- out of service

    private record Tag(BlockPos pos, int yRot, double x, double y, double z, String[] lines, int light) {
    }

    private static void drawOutOfService(ClientWorld world, MatrixStack matrices, VertexConsumerProvider consumers,
                                         Vec3d cam, TextRenderer font) {
        List<Tag> tags = new ArrayList<>();
        VertexConsumer quads = consumers.getBuffer(RenderLayer.getDebugQuads());
        for (long cell : AtmosphereClient.OUT_OF_SERVICE) {
            BlockPos pos = BlockPos.fromLong(cell);
            if (pos.getSquaredDistanceFromCenter(cam.x, cam.y, cam.z) > OOS_RANGE * OOS_RANGE) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            float shade = 0.3f + 0.7f * world.getLightLevel(pos.up()) / 15f;
            if (state.getBlock() instanceof FareMachineBlock && state.get(FareMachineBlock.HALF) == DoubleBlockHalf.LOWER) {
                int yRot = yRotation(state.get(Properties.HORIZONTAL_FACING));
                frame(matrices, pos, yRot);
                bag(matrices.peek().getPositionMatrix(), quads, shade);
                matrices.pop();
                tags.add(new Tag(pos, yRot, 8, 20.5, 7.45, new String[]{"OUT OF", "ORDER"},
                        WorldRenderer.getLightmapCoordinates(world, pos.up())));
            } else if (state.getBlock() instanceof TurnstileBlock && state.get(TurnstileBlock.HALF) == DoubleBlockHalf.LOWER) {
                int yRot = yRotation(state.get(TurnstileBlock.FACING));
                frame(matrices, pos, yRot);
                tape(matrices.peek().getPositionMatrix(), quads, 0.4, 10.6, shade);
                tagCard(matrices.peek().getPositionMatrix(), quads, 5.5, shade);
                matrices.pop();
                tags.add(new Tag(pos, yRot, 5.5, 14.5, 8, new String[]{"NOT IN", "SERVICE"},
                        WorldRenderer.getLightmapCoordinates(world, pos)));
            } else if (state.getBlock() instanceof TurnstileHeetBlock) {
                int yRot = yRotation(state.get(TurnstileHeetBlock.FACING));
                frame(matrices, pos, yRot);
                tape(matrices.peek().getPositionMatrix(), quads, 1.0, 15.0, shade);
                tagCard(matrices.peek().getPositionMatrix(), quads, 8, shade);
                matrices.pop();
                tags.add(new Tag(pos, yRot, 8, 14.5, 8, new String[]{"NOT IN", "SERVICE"},
                        WorldRenderer.getLightmapCoordinates(world, pos)));
            }
        }
        // Text last: it switches render layers (the quads buffer is dead after this).
        for (Tag tag : tags) {
            frame(matrices, tag.pos, tag.yRot);
            for (int side = 0; side < 2; side++) {
                matrices.push();
                if (side == 1) {
                    matrices.translate(0.5, 0, 0.5);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
                    matrices.translate(-0.5, 0, -0.5);
                }
                // north frame: the tag faces -z on side 0 (the machine's front / the paid side)
                double zFace = side == 0 ? tag.z / 16.0 - 0.014 : (16 - tag.z) / 16.0 - 0.018;
                double xc = side == 0 ? tag.x / 16.0 : (16 - tag.x) / 16.0;
                matrices.translate(xc, tag.y / 16.0, zFace);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
                float s = 1 / 112f;
                matrices.scale(s, -s, s);
                Matrix4f m = matrices.peek().getPositionMatrix();
                int lineH = 9;
                int top = -lineH * tag.lines.length / 2;
                for (int i = 0; i < tag.lines.length; i++) {
                    String line = tag.lines[i];
                    float w = font.getWidth(line);
                    font.draw(Text.literal(line), -w / 2f, top + i * lineH, 0xFFC8102E, false, m, consumers,
                            TextRenderer.TextLayerType.POLYGON_OFFSET, 0, tag.light);
                }
                matrices.pop();
                if (tag.pos != null && world.getBlockState(tag.pos).getBlock() instanceof FareMachineBlock) {
                    break; // the bag sign is one-sided (the machine stands against a wall)
                }
            }
            matrices.pop();
        }
    }

    private static int yRotation(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    /** Pushes a matrix in the block's NORTH model frame (blockstate y rotation applied). */
    private static void frame(MatrixStack matrices, BlockPos pos, int yRot) {
        matrices.push();
        matrices.translate(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yRot));
        matrices.translate(-0.5, 0, -0.5);
    }

    /** A black plastic bag over the 2-block fare machine (north frame: x 1..15, y 0..28, z 8..16). */
    private static void bag(Matrix4f m, VertexConsumer b, float shade) {
        double x0 = 0.55 / 16, x1 = 15.45 / 16, y1 = 28.6 / 16, z0 = 7.55 / 16, z1 = 15.98 / 16;
        // front: crinkled vertical folds
        int folds = 7;
        for (int i = 0; i < folds; i++) {
            double xa = x0 + (x1 - x0) * i / folds;
            double xb = x0 + (x1 - x0) * (i + 1) / folds;
            float v = (0.07f + 0.035f * ((i * 5) % 3)) * shade;
            double bulge = (i % 2 == 0 ? 0.004 : 0);
            Quads.quad(m, b, xb, 0, z0 - bulge, xa, 0, z0 - bulge, xa, y1, z0 - bulge, xb, y1, z0 - bulge, v, v, v * 1.05f);
        }
        float side = 0.06f * shade;
        float top = 0.11f * shade;
        Quads.quad(m, b, x0, 0, z0, x0, 0, z1, x0, y1, z1, x0, y1, z0, side, side, side);       // west
        Quads.quad(m, b, x1, 0, z1, x1, 0, z0, x1, y1, z0, x1, y1, z1, side, side, side);       // east
        Quads.quad(m, b, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, top, top, top);        // top
        // the sign card taped on the front
        float w = 0.86f * shade;
        double sx0 = 3.0 / 16, sx1 = 13.0 / 16, sy0 = 17.6 / 16, sy1 = 23.4 / 16, sz = z0 - 0.008;
        Quads.quad(m, b, sx1, sy0, sz, sx0, sy0, sz, sx0, sy1, sz, sx1, sy1, sz, w, w, w * 0.97f);
        // two strips of yellow tape holding it
        float ty = 0.85f * shade;
        double tz = sz - 0.003;
        Quads.quad(m, b, 4.6 / 16, sy1 - 0.02, tz, 2.6 / 16, sy1 - 0.02, tz, 2.6 / 16, sy1 + 0.07, tz, 4.6 / 16, sy1 + 0.07, tz, ty, ty * 0.8f, 0.1f * shade);
        Quads.quad(m, b, 13.4 / 16, sy1 - 0.02, tz, 11.4 / 16, sy1 - 0.02, tz, 11.4 / 16, sy1 + 0.07, tz, 13.4 / 16, sy1 + 0.07, tz, ty, ty * 0.8f, 0.1f * shade);
    }

    /** Yellow-and-black caution tape in an X across the lane plane (north frame, z = 8). */
    private static void tape(Matrix4f m, VertexConsumer b, double xa, double xb, float shade) {
        double x0 = xa / 16, x1 = xb / 16, y0 = 4.0 / 16, y1 = 22.0 / 16, z = 0.5;
        stripe(m, b, x0, y0, x1, y1, z, shade);
        stripe(m, b, x0, y1, x1, y0, z + 0.006, shade);
    }

    /** The white "NOT IN SERVICE" card where the tape crosses (both sides). */
    private static void tagCard(Matrix4f m, VertexConsumer b, double xc, float shade) {
        float w = 0.9f * shade;
        double x0 = (xc - 3.7) / 16, x1 = (xc + 3.7) / 16, y0 = 12.0 / 16, y1 = 17.0 / 16;
        Quads.cardZ(m, b, x0, y0, x1, y1, 0.5 - 0.008, w, w, w);
        Quads.cardZ(m, b, x0, y0, x1, y1, 0.5 + 0.014, w, w, w);
    }

    /** One tape strip (double-sided), yellow with black chevron blocks every few cm. */
    private static void stripe(Matrix4f m, VertexConsumer b, double ax, double ay, double bx, double by, double z, float shade) {
        double len = Math.hypot(bx - ax, by - ay);
        double ux = (bx - ax) / len, uy = (by - ay) / len;
        double nx = -uy * 0.055, ny = ux * 0.055; // tape half-width ≈ 0.9 px
        int segs = Math.max(4, (int) Math.round(len / 0.09));
        for (int i = 0; i < segs; i++) {
            double t0 = len * i / segs, t1 = len * (i + 1) / segs;
            double px0 = ax + ux * t0, py0 = ay + uy * t0, px1 = ax + ux * t1, py1 = ay + uy * t1;
            boolean black = i % 3 == 2;
            float r = (black ? 0.06f : 0.95f) * shade, g = (black ? 0.06f : 0.80f) * shade, bl = (black ? 0.06f : 0.08f) * shade;
            Quads.quad(m, b, px0 - nx, py0 - ny, z, px1 - nx, py1 - ny, z, px1 + nx, py1 + ny, z, px0 + nx, py0 + ny, z, r, g, bl);
            Quads.quad(m, b, px0 + nx, py0 + ny, z, px1 + nx, py1 + ny, z, px1 - nx, py1 - ny, z, px0 - nx, py0 - ny, z, r, g, bl);
        }
    }
}
