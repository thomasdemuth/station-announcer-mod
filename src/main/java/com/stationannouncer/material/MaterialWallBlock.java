package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.WallBlock;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * A wall in any full block's texture: vanilla {@link WallBlock} for every
 * behaviour (low / tall sides, posts, joining other walls — it is in
 * {@code #minecraft:walls} — and solid blocks) plus a {@link MaterialPalette}
 * slot. 324 vanilla states × 64 slots; the client model builds quads per
 * state lazily, so only states that are actually placed cost anything.
 */
public class MaterialWallBlock extends WallBlock implements MaterialBlock {
    public MaterialWallBlock(Settings settings) {
        super(settings);
        setDefaultState(MaterialPalette.withSlot(getDefaultState(), 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MaterialPalette.MAT_HI, MaterialPalette.MAT_LO);
    }

    /*
     * WallBlock builds its shape tables from getDefaultState().with(...), i.e.
     * only for material slot 0 — every other slot would get a null shape (and
     * crash the server's shape cache at boot). The shape never depends on the
     * material, so look it up on the slot-0 twin.
     */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return super.getOutlineShape(MaterialPalette.withSlot(state, 0), world, pos, context);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return super.getCollisionShape(MaterialPalette.withSlot(state, 0), world, pos, context);
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
