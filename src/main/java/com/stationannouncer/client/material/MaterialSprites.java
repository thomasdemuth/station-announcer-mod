package com.stationannouncer.client.material;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sprite (and tint index) a material block shows on each of its six
 * sides, read from that block's own baked model — so a log keeps bark on its
 * sides and rings on its ends, grass keeps its tinted top, and resource packs
 * just work. It also reads how each face's image is TURNED (a sideways log's
 * bark runs horizontally): {@link Set#uvTransform} is the transform from our
 * world-aligned projection to the material's own uv, applied when baking.
 *
 * <p>{@link #version()} bumps whenever the answers may have changed (the
 * palette gained a material, resources reloaded); the shape models drop their
 * quad caches when it moves.</p>
 */
@Environment(EnvType.CLIENT)
public final class MaterialSprites {
    public record Set(Sprite[] sprites, int[] tints, int[] uvTransforms) {
        public Sprite sprite(Direction side) {
            return sprites[side.ordinal()];
        }

        public int tint(Direction side) {
            return tints[side.ordinal()];
        }

        /** 0..7, see {@link MaterialSprites#transform}. */
        public int uvTransform(Direction side) {
            return uvTransforms[side.ordinal()];
        }
    }

    private static final Map<BlockState, Set> CACHE = new ConcurrentHashMap<>();
    private static volatile int version;

    private MaterialSprites() {
    }

    public static int version() {
        return version;
    }

    /** Forget everything (palette change, resource reload). */
    public static void invalidate() {
        CACHE.clear();
        version++;
    }

    public static Set of(BlockState material) {
        return CACHE.computeIfAbsent(material, MaterialSprites::read);
    }

    private static Set read(BlockState material) {
        BakedModel model = MinecraftClient.getInstance().getBlockRenderManager().getModel(material);
        Sprite[] sprites = new Sprite[6];
        int[] tints = new int[6];
        int[] transforms = new int[6];
        List<BakedQuad> unculled = model.getQuads(material, null, Random.create(42L));
        for (Direction side : Direction.values()) {
            BakedQuad quad = first(model.getQuads(material, side, Random.create(42L)), null);
            if (quad == null) {
                quad = first(unculled, side);
            }
            sprites[side.ordinal()] = quad != null ? quad.getSprite() : model.getParticleSprite();
            tints[side.ordinal()] = quad != null ? quad.getColorIndex() : -1;
            transforms[side.ordinal()] = quad != null ? detectTransform(quad, side) : 0;
        }
        return new Set(sprites, tints, transforms);
    }

    /**
     * The uv transform t (0..7: mirror if t ≥ 4, then t % 4 quarter turns) that
     * maps our world-aligned projection of each vertex to the uv the material's
     * own quad uses — e.g. a log with axis=x turns its bark a quarter.
     */
    static int detectTransform(BakedQuad quad, Direction side) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        Sprite sprite = quad.getSprite();
        float du = sprite.getMaxU() - sprite.getMinU();
        float dv = sprite.getMaxV() - sprite.getMinV();
        if (du == 0 || dv == 0) {
            return 0;
        }
        int best = 0;
        float bestError = Float.MAX_VALUE;
        for (int t = 0; t < 8; t++) {
            float error = 0;
            for (int i = 0; i < 4; i++) {
                int o = i * stride;
                float[] p = ShapeModel.project(side, Float.intBitsToFloat(data[o]) * 16f,
                        Float.intBitsToFloat(data[o + 1]) * 16f, Float.intBitsToFloat(data[o + 2]) * 16f);
                float[] q = transform(t, p[0], p[1]);
                float au = (Float.intBitsToFloat(data[o + 4]) - sprite.getMinU()) / du * 16f;
                float av = (Float.intBitsToFloat(data[o + 5]) - sprite.getMinV()) / dv * 16f;
                error += Math.abs(q[0] - au) + Math.abs(q[1] - av);
            }
            if (error < bestError - 0.01f) {
                bestError = error;
                best = t;
            }
        }
        return best;
    }

    /** Transform t applied to a uv in 0..16 (mirror u if t ≥ 4, then t % 4 quarter turns about the centre). */
    static float[] transform(int t, float u, float v) {
        if (t >= 4) {
            u = 16 - u;
        }
        for (int k = 0; k < (t & 3); k++) {
            float nu = 16 - v;
            v = u;
            u = nu;
        }
        return new float[]{u, v};
    }

    private static BakedQuad first(List<BakedQuad> quads, Direction face) {
        for (BakedQuad quad : quads) {
            if (face == null || quad.getFace() == face) {
                return quad;
            }
        }
        return null;
    }
}
