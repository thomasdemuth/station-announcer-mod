package com.stationannouncer.client.material;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A block model whose quads are computed in Java from the blockstate — the
 * shapes JSON cannot express (a 1:12 slope, a cut along a curve) and the
 * textures JSON cannot know in advance (any full block's, from the material
 * palette).
 *
 * <p>It is a plain {@link BakedModel}: {@code getQuads(state, side, random)}
 * builds (once per state, then cached) and returns the quads. That is the path
 * Sodium meshes without the Fabric renderer API, so these blocks get the
 * normal chunk pipeline — smooth lighting, AO, face culling — on Thomas's
 * Sodium-without-Indium setup.</p>
 *
 * <p>A {@link Geometry} describes faces in block pixels (0..16) with the
 * direction they project their texture from ({@code face}) and the side they
 * are culled against ({@code cull}, null = never). Textures are world-aligned
 * like vanilla's {@code uvlock}: a face's uv comes from its vertex positions,
 * so neighbouring blocks — and full blocks of the same material — continue the
 * texture seamlessly. Winding is fixed up from the intended normal, so
 * geometry code never has to get vertex order right.</p>
 */
@Environment(EnvType.CLIENT)
public final class ShapeModel implements UnbakedModel, BakedModel {
    /** Where a face's texture comes from. */
    public sealed interface Tex permits Tex.Material, Tex.Fixed {
        /** The block's material, the sprite it uses on {@code face}'s side. */
        record Material() implements Tex {
        }

        /** A fixed sprite by name (a key of the model's texture map). */
        record Fixed(String key) implements Tex {
        }

        Tex MATERIAL = new Material();
    }

    /**
     * One face: 4 vertices (x,y,z in px; a triangle repeats a vertex), the
     * outward normal it should face, the direction its texture projects from,
     * its cull side (nullable), its texture, and an optional explicit uv
     * (8 floats, 0..16 px on the sprite) replacing the projected one.
     */
    public record Face(float[] v, float nx, float ny, float nz, Direction face, @Nullable Direction cull, Tex tex,
                       float @Nullable [] uv) {
        public static Face of(float[] v, Direction face, @Nullable Direction cull, Tex tex) {
            return new Face(v, face.getOffsetX(), face.getOffsetY(), face.getOffsetZ(), face, cull, tex, null);
        }
    }

    /** Computes a state's faces (block pixels), and which material they wear. */
    public interface Geometry {
        List<Face> faces(BlockState state);

        /** The material a state wears (null for fixed-texture geometry). */
        @Nullable
        default BlockState material(BlockState state) {
            return null;
        }

        /**
         * Smooth-lighting AO for this geometry. Vanilla samples AO for a face that
         * is not flush with the block side from the block's OWN cell and treats any
         * full-cube neighbour as an occluder, so a long 1:12 slope picks up a dark
         * band beside a full-cube landing (seen on the rig). Sloped geometry turns
         * it off and lights flat: an upward slope then reads as bright as any top.
         */
        default boolean ambientOcclusion() {
            return true;
        }
    }

    private final Geometry geometry;
    private final Map<String, Identifier> textures;
    private final @Nullable String particleKey;

    // baked
    private Map<String, Sprite> sprites = Map.of();
    private Sprite particle;
    private final Map<BlockState, List<BakedQuad>[]> cache = new ConcurrentHashMap<>();
    private volatile int cacheVersion = -1;

    public ShapeModel(Geometry geometry, Map<String, Identifier> textures, @Nullable String particleKey) {
        this.geometry = geometry;
        this.textures = textures;
        this.particleKey = particleKey;
    }

    // ------------------------------------------------------------ unbaked

    @Override
    public Collection<Identifier> getModelDependencies() {
        return List.of();
    }

    @Override
    public void setParents(Function<Identifier, UnbakedModel> modelLoader) {
    }

    @Nullable
    @Override
    public BakedModel bake(Baker baker, Function<SpriteIdentifier, Sprite> textureGetter, ModelBakeSettings rotation, Identifier modelId) {
        // A resource reload re-bakes every model; material sprites from the old atlas must go.
        MaterialSprites.invalidate();
        ShapeModel baked = new ShapeModel(geometry, textures, particleKey);
        Map<String, Sprite> resolved = new java.util.HashMap<>();
        textures.forEach((key, id) -> resolved.put(key,
                textureGetter.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, id))));
        baked.sprites = resolved;
        baked.particle = particleKey != null && resolved.containsKey(particleKey) ? resolved.get(particleKey)
                : textureGetter.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                new Identifier("minecraft", "block/smooth_stone")));
        return baked;
    }

    // ------------------------------------------------------------ baked

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, Random random) {
        if (state == null) {
            return List.of();
        }
        int version = MaterialSprites.version();
        if (version != cacheVersion) {
            cache.clear();
            cacheVersion = version;
        }
        List<BakedQuad>[] bySide = cache.computeIfAbsent(state, this::build);
        return bySide[side == null ? 6 : side.ordinal()];
    }

    @SuppressWarnings("unchecked")
    private List<BakedQuad>[] build(BlockState state) {
        List<BakedQuad>[] bySide = new List[7];
        for (int i = 0; i < 7; i++) {
            bySide[i] = new ArrayList<>();
        }
        BlockState material = geometry.material(state);
        MaterialSprites.Set materialSprites = material == null ? null : MaterialSprites.of(material);
        for (Face face : geometry.faces(state)) {
            Sprite sprite;
            int tint = -1;
            if (face.tex() instanceof Tex.Fixed fixed) {
                sprite = sprites.getOrDefault(fixed.key(), particle);
            } else if (materialSprites != null) {
                sprite = materialSprites.sprite(face.face());
                tint = materialSprites.tint(face.face());
            } else {
                sprite = particle;
            }
            bySide[face.cull() == null ? 6 : face.cull().ordinal()].add(bake(face, sprite, tint));
        }
        return bySide;
    }

    /** One face → a BakedQuad (vertex layout: xyz floats, colour, uv floats, light, normal). */
    public static BakedQuad bake(Face face, Sprite sprite, int tint) {
        float[] v = face.v();
        // Wind counter-clockwise seen from outside. The polygon's own normal comes from
        // Newell's method over all four vertices: a clipped polygon can put its first
        // three vertices on one line (or repeat one, for a triangle), and a normal taken
        // from those alone then points nowhere and the face gets culled.
        int[] order = {0, 1, 2, 3};
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float xi = v[i * 3], yi = v[i * 3 + 1], zi = v[i * 3 + 2];
            float xj = v[j * 3], yj = v[j * 3 + 1], zj = v[j * 3 + 2];
            cx += (yi - yj) * (zi + zj);
            cy += (zi - zj) * (xi + xj);
            cz += (xi - xj) * (yi + yj);
        }
        if (cx * face.nx() + cy * face.ny() + cz * face.nz() < 0) {
            order = new int[]{0, 3, 2, 1};
        }
        float len = (float) Math.sqrt(face.nx() * face.nx() + face.ny() * face.ny() + face.nz() * face.nz());
        int normal = packNormal(face.nx() / len, face.ny() / len, face.nz() / len);
        int[] data = new int[32];
        for (int i = 0; i < 4; i++) {
            int src = order[i];
            float x = v[src * 3], y = v[src * 3 + 1], z = v[src * 3 + 2];
            float u, w;
            if (face.uv() != null) {
                u = face.uv()[src * 2];
                w = face.uv()[src * 2 + 1];
            } else {
                float[] projected = project(face.face(), x, y, z);
                u = projected[0];
                w = projected[1];
            }
            int o = i * 8;
            data[o] = Float.floatToRawIntBits(x / 16f);
            data[o + 1] = Float.floatToRawIntBits(y / 16f);
            data[o + 2] = Float.floatToRawIntBits(z / 16f);
            data[o + 3] = -1;
            data[o + 4] = Float.floatToRawIntBits(sprite.getFrameU(clamp16(u) / 16f));
            data[o + 5] = Float.floatToRawIntBits(sprite.getFrameV(clamp16(w) / 16f));
            data[o + 6] = 0;
            data[o + 7] = normal;
        }
        return new BakedQuad(data, tint, face.face(), sprite, true);
    }

    private static float clamp16(float value) {
        return Math.max(0f, Math.min(16f, value));
    }

    /** Vanilla's auto-uv per face direction (what uvlock gives a rotated full block). */
    static float[] project(Direction face, float x, float y, float z) {
        return switch (face) {
            case UP -> new float[]{x, z};
            case DOWN -> new float[]{x, 16 - z};
            case NORTH -> new float[]{16 - x, 16 - y};
            case SOUTH -> new float[]{x, 16 - y};
            case WEST -> new float[]{z, 16 - y};
            case EAST -> new float[]{16 - z, 16 - y};
        };
    }

    private static int packNormal(float x, float y, float z) {
        return ((int) (x * 127) & 0xFF) | (((int) (y * 127) & 0xFF) << 8) | (((int) (z * 127) & 0xFF) << 16);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return geometry.ambientOcclusion();
    }

    @Override
    public boolean hasDepth() {
        return true;
    }

    @Override
    public boolean isSideLit() {
        return true;
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    @Override
    public Sprite getParticleSprite() {
        return particle;
    }

    @Override
    public ModelTransformation getTransformation() {
        return ModelTransformation.NONE;
    }

    @Override
    public ModelOverrideList getOverrides() {
        return ModelOverrideList.EMPTY;
    }

    // ------------------------------------------------------------ item

    /**
     * The item model for a material block: renders the geometry of
     * {@code itemState} in the material the stack carries. Vanilla's
     * {@code block/block} display transforms; one baked model per material,
     * built on first use.
     */
    public static final class Item implements UnbakedModel {
        private static final Identifier BLOCK_PARENT = new Identifier("minecraft", "block/block");
        private final Geometry geometry;
        private final BlockState itemState;
        private final Function<net.minecraft.item.ItemStack, String> materialOf;
        private final Function<String, BlockState> parse;

        public Item(Geometry geometry, BlockState itemState, Function<net.minecraft.item.ItemStack, String> materialOf,
                    Function<String, BlockState> parse) {
            this.geometry = geometry;
            this.itemState = itemState;
            this.materialOf = materialOf;
            this.parse = parse;
        }

        @Override
        public Collection<Identifier> getModelDependencies() {
            return List.of(BLOCK_PARENT);
        }

        @Override
        public void setParents(Function<Identifier, UnbakedModel> modelLoader) {
            modelLoader.apply(BLOCK_PARENT).setParents(modelLoader);
        }

        @Nullable
        @Override
        public BakedModel bake(Baker baker, Function<SpriteIdentifier, Sprite> textureGetter, ModelBakeSettings rotation, Identifier modelId) {
            ModelTransformation transformation = ModelTransformation.NONE;
            if (baker.getOrLoadModel(BLOCK_PARENT) instanceof JsonUnbakedModel parent) {
                transformation = parent.getTransformations();
            }
            Sprite particle = textureGetter.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                    new Identifier("minecraft", "block/smooth_stone")));
            return new ItemBaked(transformation, particle, new Overrides(baker, this, transformation, particle));
        }

        /** Per-material baked item models. */
        private static final class Overrides extends ModelOverrideList {
            private final Item owner;
            private final ModelTransformation transformation;
            private final Sprite particle;
            private final Map<String, BakedModel> byMaterial = new ConcurrentHashMap<>();
            private volatile int version = -1;

            Overrides(Baker baker, Item owner, ModelTransformation transformation, Sprite particle) {
                super(baker, null, List.of());
                this.owner = owner;
                this.transformation = transformation;
                this.particle = particle;
            }

            @Nullable
            @Override
            public BakedModel apply(BakedModel model, net.minecraft.item.ItemStack stack,
                                    @Nullable net.minecraft.client.world.ClientWorld world,
                                    @Nullable net.minecraft.entity.LivingEntity entity, int seed) {
                if (MaterialSprites.version() != version) {
                    byMaterial.clear();
                    version = MaterialSprites.version();
                }
                String material = owner.materialOf.apply(stack);
                return byMaterial.computeIfAbsent(material, key -> {
                    BlockState materialState = owner.parse.apply(key);
                    MaterialSprites.Set set = materialState == null ? null : MaterialSprites.of(materialState);
                    List<BakedQuad>[] bySide = sideLists();
                    for (Face face : owner.geometry.faces(owner.itemState)) {
                        Sprite sprite = set == null ? particle : set.sprite(face.face());
                        int tint = set == null ? -1 : set.tint(face.face());
                        bySide[face.cull() == null ? 6 : face.cull().ordinal()].add(ShapeModel.bake(face, sprite, tint));
                    }
                    Sprite itemParticle = set == null ? particle : set.sprite(Direction.NORTH);
                    return new StaticBaked(bySide, transformation, itemParticle);
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] sideLists() {
        List<BakedQuad>[] bySide = new List[7];
        for (int i = 0; i < 7; i++) {
            bySide[i] = new ArrayList<>();
        }
        return bySide;
    }

    /** The item model shell: no quads of its own, everything comes from the overrides. */
    private record ItemBaked(ModelTransformation transformation, Sprite particle, ModelOverrideList overrides)
            implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, Random random) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return true;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return particle;
        }

        @Override
        public ModelTransformation getTransformation() {
            return transformation;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return overrides;
        }
    }

    /** A finished quad list (item form of one material). */
    private record StaticBaked(List<BakedQuad>[] bySide, ModelTransformation transformation, Sprite particle)
            implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, Random random) {
            return bySide[side == null ? 6 : side.ordinal()];
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return true;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return particle;
        }

        @Override
        public ModelTransformation getTransformation() {
            return transformation;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }
}
