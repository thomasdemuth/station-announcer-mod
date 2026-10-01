package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * Stairs in any full block's texture: vanilla {@link StairsBlock} for every
 * behaviour (straight / inner / outer corners, upside-down, waterlogging,
 * joining other stairs) plus a {@link MaterialPalette} slot, drawn by the
 * client's material model with vanilla's stair geometry and uv-locked
 * (world-aligned) texturing, so a flight reads as cut from the same block.
 */
public class MaterialStairsBlock extends StairsBlock implements MaterialBlock {
    public MaterialStairsBlock(Settings settings) {
        super(Blocks.SMOOTH_STONE.getDefaultState(), settings);
        setDefaultState(MaterialPalette.withSlot(getDefaultState(), 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MaterialPalette.MAT_HI, MaterialPalette.MAT_LO);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return MaterialBlock.withPlacedMaterial(super.getPlacementState(context), context);
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        return List.of(stackFor(this, state, builder.getWorld()));
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return stackFor(this, state, world instanceof World w ? w : null);
    }
}
