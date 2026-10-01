package com.stationannouncer.client.material;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.material.MaterialBlocks;
import com.stationannouncer.material.MaterialPalette;
import com.stationannouncer.material.MaterialRampBlock;
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
 * Client half of the material ramps and stairs: the palette mirror, the
 * {@link ShapeModel}s for every state and both items, the render layer (cutout,
 * so leafy or glassy materials keep their holes), tints delegated to the
 * material's own colour provider (grass stays green), and the picker openers.
 */
@Environment(EnvType.CLIENT)
public final class MaterialClient {
    private static final Identifier RAMP_ITEM_MODEL = StationAnnouncer.id("item/material_ramp");
    private static final Identifier STAIRS_ITEM_MODEL = StationAnnouncer.id("item/material_stairs");

    private MaterialClient() {
    }

    public static void register() {
        ClientMaterialPalette.register();

        ModelLoadingPlugin.register(context -> {
            registerStates(context, MaterialBlocks.MATERIAL_RAMP, MaterialGeometry.RAMP);
            registerStates(context, MaterialBlocks.MATERIAL_STAIRS, MaterialGeometry.STAIRS);
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
                return null;
            });
        });

        BlockRenderLayerMap.INSTANCE.putBlock(MaterialBlocks.MATERIAL_RAMP, RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(MaterialBlocks.MATERIAL_STAIRS, RenderLayer.getCutoutMipped());

        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
            if (tintIndex < 0 || state == null) {
                return -1;
            }
            BlockState material = MaterialGeometry.materialOf(state);
            return MinecraftClient.getInstance().getBlockColors().getColor(material, world, pos, tintIndex);
        }, MaterialBlocks.MATERIAL_RAMP, MaterialBlocks.MATERIAL_STAIRS);
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> {
            if (tintIndex < 0) {
                return -1;
            }
            BlockState material = MaterialPalette.parse(MaterialPalette.materialOf(stack));
            return material == null ? -1 : MinecraftClient.getInstance().getBlockColors().getColor(material, null, null, tintIndex);
        }, MaterialBlocks.MATERIAL_RAMP_ITEM, MaterialBlocks.MATERIAL_STAIRS_ITEM);

        MaterialBlocks.PICKER_OPENER = hand -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(MaterialPickerScreen.forHand(hand, client.player.getStackInHand(hand)));
            }
        };
        MaterialBlocks.BLOCK_PICKER_OPENER = pos -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null && client.currentScreen == null) {
                MaterialPickerScreen screen = MaterialPickerScreen.forBlock(pos, client.world.getBlockState(pos));
                if (screen != null) {
                    client.setScreen(screen);
                }
            }
        };
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
