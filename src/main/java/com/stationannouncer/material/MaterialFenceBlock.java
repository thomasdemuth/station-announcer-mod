package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * A fence in any full block's texture: vanilla {@link FenceBlock} (connects to
 * solid blocks, fence gates and — being in {@code #minecraft:wooden_fences} —
 * wooden fences; leads tie on) plus a {@link MaterialPalette} slot.
 */
public class MaterialFenceBlock extends FenceBlock implements MaterialBlock {
    public MaterialFenceBlock(Settings settings) {
        super(settings);
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

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, net.minecraft.entity.player.PlayerEntity player) {
        MaterialBlocks.clientRemesh(world, pos);
        return super.onBreak(world, pos, state, player);
    }
}
