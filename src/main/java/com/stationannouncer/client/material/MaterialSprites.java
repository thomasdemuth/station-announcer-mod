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
 * just work.
 *
 * <p>{@link #version()} bumps whenever the answers may have changed (the
 * palette gained a material, resources reloaded); the shape models drop their
 * quad caches when it moves.</p>
 */
@Environment(EnvType.CLIENT)
public final class MaterialSprites {
    public record Set(Sprite[] sprites, int[] tints) {
        public Sprite sprite(Direction side) {
            return sprites[side.ordinal()];
        }

        public int tint(Direction side) {
            return tints[side.ordinal()];
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
        List<BakedQuad> unculled = model.getQuads(material, null, Random.create(42L));
        for (Direction side : Direction.values()) {
            BakedQuad quad = first(model.getQuads(material, side, Random.create(42L)), null);
            if (quad == null) {
                quad = first(unculled, side);
            }
            sprites[side.ordinal()] = quad != null ? quad.getSprite() : model.getParticleSprite();
            tints[side.ordinal()] = quad != null ? quad.getColorIndex() : -1;
        }
        return new Set(sprites, tints);
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
