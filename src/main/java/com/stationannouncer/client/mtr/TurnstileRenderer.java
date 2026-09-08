package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.TurnstileBlock;
import com.stationannouncer.mtr.TurnstileBlockEntity;
import com.stationannouncer.mtr.TurnstileHeetBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the MOVING part of a fare barrier from its block entity: the low
 * turnstile's tripod (three arms on a 45-degree-inclined spindle — the arms
 * lie along the edges of an equilateral pyramid, so one is horizontal
 * across the lane while the other two hang down-forward and down-back) or
 * the HEET's three-wing rotor of curved bars around a full-height spindle.
 *
 * <p>Everything is computed from the world clock against the block entity's
 * synced timestamps: a passage turns the barrier 120 degrees in the rider's
 * direction (the rest pose is three-fold symmetric, so the turn ends where
 * it started), a refusal rattles it against its lock. Geometry is emitted as
 * textured cuboids on the solid block layer using the family's own atlas
 * sprites, shaded by world-space normal like vanilla block faces.
 *
 * <p>Model frame = the generator's NORTH frame in pixels (rider walks -z,
 * cabinet x 11..16, HEET hub at x 16 / z 8); the blockstate's y rotation is
 * reproduced on the matrix stack.
 */
@Environment(EnvType.CLIENT)
public class TurnstileRenderer implements BlockEntityRenderer<TurnstileBlockEntity> {
    private static final Identifier STEEL = StationAnnouncer.id("block/ts_steel");
    private static final Identifier DARK = StationAnnouncer.id("block/ts_steel_dark");
    /** Grain-free steel for tubes: brushed columns along a thin pipe read as rope banding. */
    private static final Identifier TUBE = StationAnnouncer.id("block/ts_flat");

    /** Ticks for a low-turnstile 120-degree turn / a HEET rotor turn / the deny rattle. */
    private static final float TRIPOD_TURN_TICKS = 8f;
    private static final float ROTOR_TURN_TICKS = 10f;
    private static final float DENY_TICKS = 8f;

    // Tripod: pyramid apex (where the three arms meet) and the spindle axis,
    // both in the north frame. Axis points down into the lane at 45 degrees.
    private static final Vector3f FACE = new Vector3f(11.6f, 13.5f, 8f);   // where the spindle leaves the recess
    private static final Vector3f AXIS = new Vector3f(-1, -1, 0).normalize();
    private static final float APEX_OUT = 2.0f;                             // apex distance along the axis
    private static final Vector3f APEX = new Vector3f(FACE).add(new Vector3f(AXIS).mul(APEX_OUT));
    private static final float ARM_LENGTH = 9.6f;
    private static final float ARM_HALF = 0.65f;

    // Grab-rail arch (north frame): collar tops at (13.5, 30, 1.4) and the
    // neighbour's at x -2.5; semicircle R 8 between them.
    private static final float ARCH_X0 = 13.5f, ARCH_X1 = -2.5f, ARCH_Z = 1.4f;
    private static final float ARCH_BASE_Y = 30f, ARCH_RISE = 1.2f, ARCH_R = 8f, PIPE_R = 0.6f;
    private static final int ARCH_SEGMENTS = 12;

    // HEET rotor
    private static final float HUB_X = 16f;
    private static final float HUB_Z = 8f;
    private static final float ROTOR_ARC_R = 12f;
    private static final float ROTOR_SWEEP = (float) Math.toRadians(50);
    private static final int ROTOR_SEGMENTS = 3;
    private static final float BAR_Y0 = 3.6f;
    private static final float BAR_PITCH = 2.4f;
    private static final int BAR_COUNT = 12;
    private static final float SPINDLE_R = 1.5f;

    /** Dev rig only: absolute barrier angle per block position, overriding the clock (headless screenshots). */
    public static final java.util.Map<net.minecraft.util.math.BlockPos, Float> DEV_POSE = new java.util.HashMap<>();

    @Override
    public boolean rendersOutsideBoundingBox(TurnstileBlockEntity be) {
        return true; // the HEET rotor and arms reach into neighbouring cells
    }

    @Override
    public void render(TurnstileBlockEntity be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = be.getWorld();
        BlockState state = be.getCachedState();
        if (world == null || !state.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        float now = world.getTime() + tickDelta;
        boolean heet = state.getBlock() instanceof TurnstileHeetBlock;

        var atlas = MinecraftClient.getInstance().getSpriteAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        Sprite steel = atlas.apply(STEEL);
        Sprite dark = atlas.apply(DARK);
        Sprite tube = atlas.apply(TUBE);
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getSolid());

        matrices.push();
        matrices.translate(0.5, 0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yRotation(facing)));
        matrices.translate(-0.5, 0, -0.5);
        matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);

        Float posed = DEV_POSE.isEmpty() ? null : DEV_POSE.get(be.getPos());
        if (heet) {
            float angle = posed != null ? posed : turnAngle(be, now, ROTOR_TURN_TICKS) + denyAngle(be, now);
            drawRotor(matrices, vc, steel, dark, light, angle);
        } else {
            float angle = posed != null ? posed : turnAngle(be, now, TRIPOD_TURN_TICKS) + denyAngle(be, now);
            drawTripod(matrices, vc, tube, dark, light, angle);
            BlockState upper = world.getBlockState(be.getPos().up());
            if (upper.contains(TurnstileBlock.JOIN) && upper.get(TurnstileBlock.JOIN)) {
                int upperLight = net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(world, be.getPos().up());
                drawArch(matrices, vc, tube, upperLight);
            }
        }
        matrices.pop();
    }

    /** Blockstate y rotation for the facing (model authored NORTH). */
    private static float yRotation(Direction facing) {
        return switch (facing) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }

    /** 0..120 degrees (signed by travel direction) while a turn is in progress, else 0. */
    private static float turnAngle(TurnstileBlockEntity be, float now, float ticks) {
        if (be.turnStart() < 0) {
            return 0f;
        }
        float t = now - be.turnStart();
        if (t < 0 || t >= ticks) {
            return 0f;
        }
        float s = t / ticks;
        float ease = s * s * (3 - 2 * s);
        return 120f * ease * be.turnDir();
    }

    /** A short decaying rattle after a refused attempt. */
    private static float denyAngle(TurnstileBlockEntity be, float now) {
        if (be.denyStart() < 0) {
            return 0f;
        }
        float t = now - be.denyStart();
        if (t < 0 || t >= DENY_TICKS) {
            return 0f;
        }
        return 6f * MathHelper.sin(t * (float) Math.PI / 2f) * (1 - t / DENY_TICKS);
    }

    // ------------------------------------------------------------- tripod --

    private static void drawTripod(MatrixStack ms, VertexConsumer vc, Sprite steel, Sprite dark, int light, float angle) {
        // Spindle boss: octagonal cylinder along the axis out of the recess cover.
        ms.push();
        ms.translate(FACE.x, FACE.y, FACE.z);
        ms.multiply(new Quaternionf().rotationTo(1, 0, 0, AXIS.x, AXIS.y, AXIS.z));
        box(ms, vc, dark, -0.6f, -1.3f, -1.3f, APEX_OUT + 1.0f, 1.3f, 1.3f, light, 1f);
        ms.multiply(new Quaternionf().rotationX((float) Math.toRadians(45)));
        box(ms, vc, dark, -0.6f, -1.3f, -1.3f, APEX_OUT + 1.0f, 1.3f, 1.3f, light, 1f);
        ms.pop();

        for (int k = 0; k < 3; k++) {
            float a = (float) Math.toRadians(angle + 120f * k);
            Vector3f d = new Quaternionf().rotationAxis(a, AXIS.x, AXIS.y, AXIS.z).transform(new Vector3f(-1, 0, 0));
            ms.push();
            ms.translate(APEX.x, APEX.y, APEX.z);
            ms.multiply(new Quaternionf().rotationTo(1, 0, 0, d.x, d.y, d.z));
            // octagonal tube: core + 45-deg twin about its own axis
            box(ms, vc, steel, 0.8f, -ARM_HALF, -ARM_HALF, ARM_LENGTH, ARM_HALF, ARM_HALF, light, 1f);
            ms.multiply(new Quaternionf().rotationX((float) Math.toRadians(45)));
            box(ms, vc, steel, 0.8f, -ARM_HALF, -ARM_HALF, ARM_LENGTH, ARM_HALF, ARM_HALF, light, 1f);
            // rounded end: a slightly fatter short cap
            box(ms, vc, steel, ARM_LENGTH - 1.0f, -ARM_HALF - 0.1f, -ARM_HALF - 0.1f,
                    ARM_LENGTH + 0.15f, ARM_HALF + 0.1f, ARM_HALF + 0.1f, light, 0.92f);
            ms.pop();
        }
    }

    // --------------------------------------------------------------- arch --

    /** Octagonal pipe segment from a to b (px), in the x-y plane at ARCH_Z. */
    private static void pipe(MatrixStack ms, VertexConsumer vc, Sprite steel, int light,
                             float ax, float ay, float bx, float by, float over) {
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        ms.push();
        ms.translate((ax + bx) / 2, (ay + by) / 2, ARCH_Z);
        ms.multiply(new Quaternionf().rotationZ((float) Math.atan2(dy, dx)));
        box(ms, vc, steel, -len / 2 - over, -PIPE_R, -PIPE_R, len / 2 + over, PIPE_R, PIPE_R, light, 1f);
        ms.multiply(new Quaternionf().rotationX((float) Math.toRadians(45)));
        box(ms, vc, steel, -len / 2 - over, -PIPE_R, -PIPE_R, len / 2 + over, PIPE_R, PIPE_R, light, 1f);
        ms.pop();
    }

    /** Grab-rail arch from this pylon's collar over the lane to the neighbour's collar. */
    private static void drawArch(MatrixStack ms, VertexConsumer vc, Sprite steel, int light) {
        float cx = (ARCH_X0 + ARCH_X1) / 2, cy = ARCH_BASE_Y + ARCH_RISE;
        pipe(ms, vc, steel, light, ARCH_X0, ARCH_BASE_Y - 0.2f, ARCH_X0, cy, 0f);
        pipe(ms, vc, steel, light, ARCH_X1, ARCH_BASE_Y - 0.2f, ARCH_X1, cy, 0f);
        for (int i = 0; i < ARCH_SEGMENTS; i++) {
            double a0 = Math.PI * i / ARCH_SEGMENTS, a1 = Math.PI * (i + 1) / ARCH_SEGMENTS;
            float x0 = cx + ARCH_R * (float) Math.cos(a0), y0 = cy + ARCH_R * (float) Math.sin(a0);
            float x1 = cx + ARCH_R * (float) Math.cos(a1), y1 = cy + ARCH_R * (float) Math.sin(a1);
            pipe(ms, vc, steel, light, x0, y0, x1, y1, 0.18f);
        }
    }

    // -------------------------------------------------------------- rotor --

    private static void drawRotor(MatrixStack ms, VertexConsumer vc, Sprite steel, Sprite dark, int light, float angle) {
        // Spindle: octagonal column at the hub.
        ms.push();
        ms.translate(HUB_X, 0, HUB_Z);
        box(ms, vc, steel, -SPINDLE_R, 1, -SPINDLE_R, SPINDLE_R, 32, SPINDLE_R, light, 1f);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45));
        box(ms, vc, steel, -SPINDLE_R, 1, -SPINDLE_R, SPINDLE_R, 32, SPINDLE_R, light, 1f);
        ms.pop();
        // collars
        box(ms, vc, dark, HUB_X - 2.2f, 1, HUB_Z - 2.2f, HUB_X + 2.2f, 2.2f, HUB_Z + 2.2f, light, 1f);
        box(ms, vc, dark, HUB_X - 2.2f, 30.8f, HUB_Z - 2.2f, HUB_X + 2.2f, 32, HUB_Z + 2.2f, light, 1f);

        // Rest pose: one wing into the cage (-x, 180 deg), the others at +-60
        // toward the comb. Angles measured from +x toward +z; a passage toward
        // -z (dir +1) advances them.
        float s0 = (float) Math.asin(SPINDLE_R / ROTOR_ARC_R);
        for (int w = 0; w < 3; w++) {
            double phi = Math.toRadians(180 + 120 * w + angle);
            float rx = (float) Math.cos(phi), rz = (float) Math.sin(phi);
            float tx = -rz, tz = rx;
            for (int seg = 0; seg < ROTOR_SEGMENTS; seg++) {
                float sa = s0 + (ROTOR_SWEEP - s0) * seg / ROTOR_SEGMENTS;
                float sb = s0 + (ROTOR_SWEEP - s0) * (seg + 1) / ROTOR_SEGMENTS;
                float ax = HUB_X + ROTOR_ARC_R * MathHelper.sin(sa) * rx + ROTOR_ARC_R * (1 - MathHelper.cos(sa)) * tx;
                float az = HUB_Z + ROTOR_ARC_R * MathHelper.sin(sa) * rz + ROTOR_ARC_R * (1 - MathHelper.cos(sa)) * tz;
                float bx = HUB_X + ROTOR_ARC_R * MathHelper.sin(sb) * rx + ROTOR_ARC_R * (1 - MathHelper.cos(sb)) * tx;
                float bz = HUB_Z + ROTOR_ARC_R * MathHelper.sin(sb) * rz + ROTOR_ARC_R * (1 - MathHelper.cos(sb)) * tz;
                float dx = bx - ax, dz = bz - az;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dz, dx));
                ms.push();
                ms.translate((ax + bx) / 2, 0, (az + bz) / 2);
                ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
                float over = seg == 0 ? 0.0f : 0.25f;
                for (int k = 0; k < BAR_COUNT; k++) {
                    float y = BAR_Y0 + k * BAR_PITCH;
                    box(ms, vc, steel, -len / 2 - over, y - 0.5f, -0.5f, len / 2 + 0.25f, y + 0.5f, 0.5f, light, 1f);
                }
                ms.pop();
            }
        }
    }

    // ------------------------------------------------------------- cuboid --

    /** Textured cuboid in the current matrix frame (pixel units), shaded per face by world-space normal. */
    static void box(MatrixStack ms, VertexConsumer vc, Sprite sprite,
                    float x0, float y0, float z0, float x1, float y1, float z1, int light, float tint) {
        Matrix4f m = ms.peek().getPositionMatrix();
        Matrix3f n = ms.peek().getNormalMatrix();
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        float hx = (x1 - x0) / 2, hy = (y1 - y0) / 2, hz = (z1 - z0) / 2;
        // +x / -x
        face(m, n, vc, sprite, light, tint, cx, cy, cz, 1, 0, 0, 0, 0, -1, 0, 1, 0, hx, hz, hy);
        face(m, n, vc, sprite, light, tint, cx, cy, cz, -1, 0, 0, 0, 0, 1, 0, 1, 0, hx, hz, hy);
        // +y / -y
        face(m, n, vc, sprite, light, tint, cx, cy, cz, 0, 1, 0, 1, 0, 0, 0, 0, -1, hy, hx, hz);
        face(m, n, vc, sprite, light, tint, cx, cy, cz, 0, -1, 0, 1, 0, 0, 0, 0, 1, hy, hx, hz);
        // +z / -z
        face(m, n, vc, sprite, light, tint, cx, cy, cz, 0, 0, 1, 1, 0, 0, 0, 1, 0, hz, hx, hy);
        face(m, n, vc, sprite, light, tint, cx, cy, cz, 0, 0, -1, -1, 0, 0, 0, 1, 0, hz, hx, hy);
    }

    /**
     * One face: centre + normal N, in-plane axes U, V with U x V = N (so the
     * winding is counter-clockwise seen from outside), half-extents hn along
     * N and hu/hv along U/V. UV maps 1 px to 2 texels of the 32 px sprite.
     */
    private static void face(Matrix4f m, Matrix3f n, VertexConsumer vc, Sprite sprite, int light, float tint,
                             float cx, float cy, float cz,
                             float nx, float ny, float nz, float ux, float uy, float uz, float vx, float vy, float vz,
                             float hn, float hu, float hv) {
        Vector3f wn = n.transform(new Vector3f(nx, ny, nz)).normalize();
        float shade = 0.6f * wn.x * wn.x + 0.8f * wn.z * wn.z + wn.y * wn.y * (wn.y > 0 ? 1.0f : 0.5f);
        float c = MathHelper.clamp(shade, 0.35f, 1f) * tint;
        float fx = cx + nx * hn, fy = cy + ny * hn, fz = cz + nz * hn;
        // Sprite.getFrameU/V take a 0..1 fraction of the sprite (bytecode-verified);
        // 1 px of geometry = 1/16 of the 32-texel sprite = 2 texels.
        float u1 = Math.min(1f, 2 * hu / 16f), v1 = Math.min(1f, 2 * hv / 16f);
        float su0 = sprite.getFrameU(0), su1 = sprite.getFrameU(u1);
        float sv0 = sprite.getFrameV(0), sv1 = sprite.getFrameV(v1);
        vertex(m, n, vc, fx - ux * hu - vx * hv, fy - uy * hu - vy * hv, fz - uz * hu - vz * hv, su0, sv1, c, light, nx, ny, nz);
        vertex(m, n, vc, fx + ux * hu - vx * hv, fy + uy * hu - vy * hv, fz + uz * hu - vz * hv, su1, sv1, c, light, nx, ny, nz);
        vertex(m, n, vc, fx + ux * hu + vx * hv, fy + uy * hu + vy * hv, fz + uz * hu + vz * hv, su1, sv0, c, light, nx, ny, nz);
        vertex(m, n, vc, fx - ux * hu + vx * hv, fy - uy * hu + vy * hv, fz - uz * hu + vz * hv, su0, sv0, c, light, nx, ny, nz);
    }

    private static void vertex(Matrix4f m, Matrix3f n, VertexConsumer vc, float x, float y, float z,
                               float u, float v, float c, int light, float nx, float ny, float nz) {
        vc.vertex(m, x, y, z).color(c, c, c, 1f).texture(u, v).light(light).normal(n, nx, ny, nz).next();
    }
}
