package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * A slab in any full block's texture: vanilla {@link SlabBlock} (bottom / top /
 * double, waterlogging) plus a {@link MaterialPalette} slot. A second slab of
 * the same texture makes a plain double; one of another texture makes a
 * {@link MaterialMixedSlabBlock} (each half keeps its own).
 */
public class MaterialSlabBlock extends SlabBlock implements MaterialBlock {
    public MaterialSlabBlock(Settings settings) {
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
        BlockState existing = context.getWorld().getBlockState(context.getBlockPos());
        BlockState state = super.getPlacementState(context);
        if (existing.isOf(this) && state != null && state.get(TYPE) == SlabType.DOUBLE) {
            // Completing a double. Same texture: one double slab; otherwise a mixed slab.
            int placedSlot = MaterialPalette.slot(existing);
            int newSlot = MaterialPalette.slotFor(MaterialPalette.materialOf(context.getStack()), context.getWorld().isClient);
            boolean placedIsBottom = existing.get(TYPE) == SlabType.BOTTOM;
            return MaterialMixedSlabBlock.of(placedIsBottom ? placedSlot : newSlot, placedIsBottom ? newSlot : placedSlot);
        }
        return MaterialBlock.withPlacedMaterial(state, context);
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        ItemStack stack = stackFor(this, state, builder.getWorld());
        if (state.get(TYPE) == SlabType.DOUBLE) {
            stack.setCount(2);
        }
        return List.of(stack);
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
