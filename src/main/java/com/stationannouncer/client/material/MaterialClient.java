package com.stationannouncer.client.material;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.material.MaterialBlocks;
import com.stationannouncer.material.MaterialMixedSlabBlock;
import com.stationannouncer.material.MaterialPalette;
import com.stationannouncer.material.MaterialRampBlock;
import com.stationannouncer.material.RampRailBlock;
import com.stationannouncer.material.RampRails;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.Map;

/**
 * Client half of the material ramps, stairs, slabs, walls and fences: the palette mirror, the
 * {@link ShapeModel}s for every state and both items, the render layer (cutout,
 * so leafy or glassy materials keep their holes), tints delegated to the
 * material's own colour provider (grass stays green), and the picker openers.
 */
@Environment(EnvType.CLIENT)
public final class MaterialClient {
    private static final Identifier RAMP_ITEM_MODEL = StationAnnouncer.id("item/material_ramp");
    private static final Identifier STAIRS_ITEM_MODEL = StationAnnouncer.id("item/material_stairs");
    private static final Identifier SLAB_ITEM_MODEL = StationAnnouncer.id("item/material_slab");
    private static final Identifier WALL_ITEM_MODEL = StationAnnouncer.id("item/material_wall");
    private static final Identifier FENCE_ITEM_MODEL = StationAnnouncer.id("item/material_fence");
    /** Pending re-meshes: {pos, ticks left}. */
    private static final java.util.List<long[]> REMESH = new java.util.ArrayList<>();

    private MaterialClient() {
    }

    public static void register() {
        ClientMaterialPalette.register();

        ModelLoadingPlugin.register(context -> {
            registerStates(context, MaterialBlocks.MATERIAL_RAMP, MaterialGeometry.RAMP);
            registerStates(context, MaterialBlocks.MATERIAL_STAIRS, MaterialGeometry.STAIRS);
            registerStates(context, MaterialBlocks.MATERIAL_SLAB, MaterialGeometry.SLAB);
            registerStates(context, MaterialBlocks.MATERIAL_MIXED_SLAB, MaterialGeometry.MIXED_SLAB);
            registerStates(context, MaterialBlocks.MATERIAL_WALL, MaterialGeometry.WALL);
            registerStates(context, MaterialBlocks.MATERIAL_FENCE, MaterialGeometry.FENCE);
            for (RampRailBlock rail : RampRails.BLOCKS.values()) {
                RampRailGeometry geometry = new RampRailGeometry(rail.style());
                registerStates(context, rail, geometry, geometry.textures(), "steel");
            }
            context.resolveModel().register(resolve -> {
                Identifier id = resolve.id();
                if (RAMP_ITEM_MODEL.equals(id)) {
                    return new ShapeModel.Item(MaterialGeometry.RAMP_ITEM,
                            MaterialBlocks.MATERIAL_RAMP.getDefaultState().with(MaterialRampBlock.FACING, Direction.NORTH),
                            MaterialPalette::materialOf, MaterialPalette::parse);
                }
                if (STAIRS_ITEM_MODEL.equals(id)) {
                    return new ShapeModel.Item(MaterialGeometry.STAIRS_ITEM,
                            MaterialBlocks.MATERIAL_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST)
                                    .with(StairsBlock.HALF, BlockHalf.BOTTOM).with(StairsBlock.SHAPE, StairShape.STRAIGHT),
                            MaterialPalette::materialOf, MaterialPalette::parse);
                }
                if (SLAB_ITEM_MODEL.equals(id)) {
                    return itemModel(MaterialGeometry.SLAB_ITEM, MaterialBlocks.MATERIAL_SLAB);
                }
                if (WALL_ITEM_MODEL.equals(id)) {
                    return itemModel(MaterialGeometry.WALL_ITEM, MaterialBlocks.MATERIAL_WALL);
                }
                if (FENCE_ITEM_MODEL.equals(id)) {
                    return itemModel(MaterialGeometry.FENCE_ITEM, MaterialBlocks.MATERIAL_FENCE);
                }
                return null;
            });
        });

        BlockRenderLayerMap.INSTANCE.putBlock(MaterialBlocks.MATERIAL_RAMP, RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getCutoutMipped(), MaterialBlocks.MATERIAL_STAIRS,
                MaterialBlocks.MATERIAL_SLAB, MaterialBlocks.MATERIAL_MIXED_SLAB, MaterialBlocks.MATERIAL_WALL,
                MaterialBlocks.MATERIAL_FENCE);
        BlockRenderLayerMap.INSTANCE.putBlock(RampRails.GLASS, RenderLayer.getTranslucent());

        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
            if (tintIndex < 0 || state == null) {
                return -1;
            }
            BlockState material = MaterialGeometry.materialOf(state);
            if (tintIndex >= ShapeModel.SECOND_TINT_OFFSET) {
                material = ClientMaterialPalette.state(MaterialMixedSlabBlock.topSlot(state));
                tintIndex -= ShapeModel.SECOND_TINT_OFFSET;
            }
            return MinecraftClient.getInstance().getBlockColors().getColor(material, world, pos, tintIndex);
        }, MaterialBlocks.MATERIAL_RAMP, MaterialBlocks.MATERIAL_STAIRS, MaterialBlocks.MATERIAL_SLAB,
                MaterialBlocks.MATERIAL_MIXED_SLAB,
                MaterialBlocks.MATERIAL_WALL, MaterialBlocks.MATERIAL_FENCE);
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> {
            if (tintIndex < 0) {
                return -1;
            }
            BlockState material = MaterialPalette.parse(MaterialPalette.materialOf(stack));
            return material == null ? -1 : MinecraftClient.getInstance().getBlockColors().getColor(material, null, null, tintIndex);
        }, MaterialBlocks.MATERIAL_RAMP_ITEM, MaterialBlocks.MATERIAL_STAIRS_ITEM, MaterialBlocks.MATERIAL_SLAB_ITEM,
                MaterialBlocks.MATERIAL_WALL_ITEM, MaterialBlocks.MATERIAL_FENCE_ITEM);

        // Re-mesh around a broken code-modelled block twice: next tick, and a second
        // later (after any rebuild that was already in flight has landed).
        MaterialBlocks.CLIENT_REMESH = pos -> {
            synchronized (REMESH) {
                REMESH.add(new long[]{pos.asLong(), 2});
                REMESH.add(new long[]{pos.asLong(), 20});
            }
        };
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            synchronized (REMESH) {
                if (REMESH.isEmpty()) {
                    return;
                }
                java.util.Iterator<long[]> it = REMESH.iterator();
                while (it.hasNext()) {
                    long[] entry = it.next();
                    if (--entry[1] > 0) {
                        continue;
                    }
                    it.remove();
                    if (client.worldRenderer != null && client.world != null) {
                        net.minecraft.util.math.BlockPos p = net.minecraft.util.math.BlockPos.fromLong(entry[0]);
                        client.worldRenderer.scheduleBlockRenders(p.getX() - 1, p.getY() - 1, p.getZ() - 1,
                                p.getX() + 1, p.getY() + 1, p.getZ() + 1);
                    }
                }
            }
        });

        MaterialBlocks.PICKER_OPENER = hand -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(MaterialPickerScreen.forHand(hand, client.player.getStackInHand(hand)));
            }
        };
        MaterialBlocks.BLOCK_PICKER_OPENER = (pos, top) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null && client.currentScreen == null) {
                MaterialPickerScreen screen = MaterialPickerScreen.forBlock(pos, client.world.getBlockState(pos), top);
                if (screen != null) {
                    client.setScreen(screen);
                }
            }
        };
    }

    private static ShapeModel.Item itemModel(ShapeModel.Geometry geometry, Block block) {
        return new ShapeModel.Item(geometry, block.getDefaultState(), MaterialPalette::materialOf, MaterialPalette::parse);
    }

    /** Every state of {@code block} → one shared shape model (it builds quads per state). */
    public static void registerStates(ModelLoadingPlugin.Context context, Block block, ShapeModel.Geometry geometry) {
        registerStates(context, block, geometry, Map.of(), null);
    }

    public static void registerStates(ModelLoadingPlugin.Context context, Block block, ShapeModel.Geometry geometry,
                                      Map<String, Identifier> textures, String particleKey) {
        ShapeModel model = new ShapeModel(geometry, textures, particleKey);
        context.registerBlockStateResolver(block, resolver -> {
            for (BlockState state : block.getStateManager().getStates()) {
                resolver.setModel(state, model);
            }
        });
    }
}
