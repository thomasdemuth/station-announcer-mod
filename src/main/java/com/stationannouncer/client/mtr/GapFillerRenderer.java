package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.GapFillerBlock;
import com.stationannouncer.mtr.GapFillerBlockEntity;
import com.stationannouncer.mtr.GapFillerPhase;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a gap filler's moving plate; everything that does not move is the
 * block model. The plate slides between home (tip flush with the slot mouth)
 * and its full reach at the platform's own extend / retract speed, from
 * whatever position it is at — so a plate sent back out mid-retraction simply
 * turns round. The first frame after a chunk loads snaps to the blockstate.
 *
 * <p>Frame: north = track (model -z), pixels, rotated like the blockstate.
 * The plate is {@code reach + PLATE_OVERLAP} long, so at full reach its back
 * end is still {@code PLATE_OVERLAP} px inside the slot; at home the part
 * longer than the block runs on under the platform block behind (invisible
 * under that block's top). The South Ferry style drops as it goes — its
 * sections rode sloping rails down to the car side.</p>
 *
 * <p>Plate sprite layout (32 × 32, 2 texels per px across, rows along the
 * plate from the tip): rows 0-1 rubber nosing, 2-7 painted safety edge,
 * 8-23 an 8 px tile of the deck surface (repeated along the plate), 24-27
 * underside/side steel, 28-31 the rubber bumper's face.</p>
 */
public class GapFillerRenderer implements BlockEntityRenderer<GapFillerBlockEntity> {
    private static final Identifier PLATE_UNION = StationAnnouncer.id("block/gap_filler_plate");
    private static final Identifier PLATE_LOOP = StationAnnouncer.id("block/gap_filler_plate_loop");
    private static final float X0 = 0.3f;
    private static final float X1 = 15.7f;

    public GapFillerRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public boolean rendersOutsideBoundingBox(GapFillerBlockEntity be) {
        return true; // the plate reaches a block and a half toward the track
    }

    @Override
    public void render(GapFillerBlockEntity be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = be.getWorld();
        BlockState state = be.getCachedState();
        if (world == null || !(state.getBlock() instanceof GapFillerBlock block)) {
            return;
        }
        Direction side = state.get(GapFillerBlock.TRACK_SIDE);
        GapFillerPhase phase = state.get(GapFillerBlock.PHASE);
        float reach = state.get(GapFillerBlock.REACH) * 2f;

        // Advance the plate toward where the phase wants it.
        long nanos = System.nanoTime();
        float target = phase.outward() ? 1f : 0f;
        if (be.clientTravel < 0f) {
            be.clientTravel = phase == GapFillerPhase.EXTENDED ? 1f : phase == GapFillerPhase.RETRACTED ? 0f : 1f - target;
        } else {
            float elapsedMs = Math.min(250f, (nanos - be.clientLastNanos) / 1_000_000f);
            float duration = Math.max(200f, target > be.clientTravel ? be.getExtendMs() : be.getRetractMs());
            float step = elapsedMs / duration;
            be.clientTravel = target > be.clientTravel
                    ? Math.min(target, be.clientTravel + step)
                    : Math.max(target, be.clientTravel - step);
        }
        be.clientLastNanos = nanos;

        boolean loop = block.style == GapFillerBlock.Style.LOOP;
        // Union: a hydraulic ram at constant speed that stops dead. Loop: gravity —
        // gathering speed on the way out, slowing into the latch on the way home.
        float shaped = loop ? (float) Math.pow(be.clientTravel, 1.6) : be.clientTravel;
        float travel = shaped * reach;
        float drop = loop ? travel * GapFillerBlock.LOOP_SLOPE : 0f;
        // 0.02 below the deck's underside: no plane shared with the block model.
        float top = GapFillerBlock.PLATE_TOP - drop - 0.02f;
        float bottom = top - GapFillerBlock.PLATE_THICKNESS;
        float tipZ = -travel;
        float backZ = tipZ + reach + GapFillerBlock.PLATE_OVERLAP;

        var atlas = MinecraftClient.getInstance().getSpriteAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        Sprite sprite = atlas.apply(loop ? PLATE_LOOP : PLATE_UNION);
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getSolid());
        // Out over the gap the plate is lit by the air in front of the edge, not the slot.
        int outside = WorldRenderer.getLightmapCoordinates(world, be.getPos().offset(side));
        int plateLight = travel > 1f ? Math.max(light, outside) : light;

        matrices.push();
        matrices.translate(0.5, 0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yRotation(side)));
        matrices.translate(-0.5, 0, -0.5);
        matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        Matrix3f n = matrices.peek().getNormalMatrix();

        // Top: rubber nosing, painted edge, then the deck tile repeated along the plate.
        float z = tipZ;
        z = topStrip(m, n, vc, sprite, plateLight, z, Math.min(backZ, z + 1f), top, 0, 2);
        z = topStrip(m, n, vc, sprite, plateLight, z, Math.min(backZ, z + 3f), top, 2, 8);
        while (z < backZ - 0.001f) {
            float end = Math.min(backZ, z + 8f);
            z = topStrip(m, n, vc, sprite, plateLight, z, end, top, 8, 8 + (end - z) * 2);
        }
        // Bumper face toward the track.
        quad(m, n, vc, sprite, plateLight, 0, 0, -1,
                X1, bottom, tipZ, X0, bottom, tipZ, X0, top, tipZ, X1, top, tipZ,
                0, 28, 32, 32);
        // Underside and the two sides (steel band of the sprite).
        quad(m, n, vc, sprite, plateLight, 0, -1, 0,
                X0, bottom, tipZ, X1, bottom, tipZ, X1, bottom, backZ, X0, bottom, backZ,
                0, 24, 32, 28);
        quad(m, n, vc, sprite, plateLight, -1, 0, 0,
                X0, bottom, tipZ, X0, bottom, backZ, X0, top, backZ, X0, top, tipZ,
                0, 24, 32, 28);
        quad(m, n, vc, sprite, plateLight, 1, 0, 0,
                X1, bottom, backZ, X1, bottom, tipZ, X1, top, tipZ, X1, top, backZ,
                0, 24, 32, 28);
        matrices.pop();
    }

    /** One stretch of the plate's top from z0 to z1, sampling sprite rows v0..v1. Returns z1. */
    private static float topStrip(Matrix4f m, Matrix3f n, VertexConsumer vc, Sprite sprite, int light,
                                  float z0, float z1, float y, float v0, float v1) {
        if (z1 - z0 <= 0.001f) {
            return z0;
        }
        quad(m, n, vc, sprite, light, 0, 1, 0,
                X0, y, z1, X1, y, z1, X1, y, z0, X0, y, z0,
                0.6f, v0, 31.4f, v1);
        return z1;
    }

    /**
     * A quad given counter-clockwise (seen from outside) as a,b,c,d; the texture
     * window u0..u1 × v0..v1 is in sprite texels (0..32) and maps a→(u0,v1),
     * b→(u1,v1), c→(u1,v0), d→(u0,v0). Shaded by world-space normal like
     * vanilla's block faces.
     */
    private static void quad(Matrix4f m, Matrix3f n, VertexConsumer vc, Sprite sprite, int light,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1) {
        Vector3f wn = n.transform(new Vector3f(nx, ny, nz)).normalize();
        float shade = 0.6f * wn.x * wn.x + 0.8f * wn.z * wn.z + wn.y * wn.y * (wn.y > 0 ? 1.0f : 0.5f);
        float c = Math.max(0.35f, Math.min(1f, shade));
        float su0 = sprite.getFrameU(u0 / 32f), su1 = sprite.getFrameU(u1 / 32f);
        float sv0 = sprite.getFrameV(v0 / 32f), sv1 = sprite.getFrameV(v1 / 32f);
        vertex(m, n, vc, ax, ay, az, su0, sv1, c, light, nx, ny, nz);
        vertex(m, n, vc, bx, by, bz, su1, sv1, c, light, nx, ny, nz);
        vertex(m, n, vc, cx, cy, cz, su1, sv0, c, light, nx, ny, nz);
        vertex(m, n, vc, dx, dy, dz, su0, sv0, c, light, nx, ny, nz);
    }

    private static void vertex(Matrix4f m, Matrix3f n, VertexConsumer vc, float x, float y, float z,
                               float u, float v, float c, int light, float nx, float ny, float nz) {
        vc.vertex(m, x, y, z).color(c, c, c, 1f).texture(u, v).light(light).normal(n, nx, ny, nz).next();
    }

    /** Blockstate y rotation for the track side (model authored NORTH). */
    private static float yRotation(Direction side) {
        return switch (side) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }
}
