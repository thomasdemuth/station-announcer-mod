package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * El mezzanine floor: a walkable steel-framed slab for any elevated floor -
 * the control house under the tracks, a stair landing, a footbridge. 4 px
 * concrete deck on two joists (AXIS = joist direction, from the placer's
 * look), corrugated pan underneath; open sides hang a channel fascia flush
 * with an el wall course in the next cell. The shape stops short of the
 * block's side faces, so wall courses beside it draw no slab bracket.
 * Assets: tools/gen_el2_mezz.py.
 */
public class ElMezzanineFloorBlock extends ElEdgePanelBlock {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 8, 0, 16, 16, 16);

    public ElMezzanineFloorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(AXIS);
    }

    @Override
    protected boolean sameSurface(BlockState state) {
        return state.getBlock() instanceof ElMezzanineFloorBlock;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        // a floor you extend keeps its joists running the same way
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState other = context.getWorld().getBlockState(context.getBlockPos().offset(d));
            if (sameSurface(other)) {
                return state.with(AXIS, other.get(AXIS));
            }
        }
        return state.with(AXIS, context.getHorizontalPlayerFacing().getAxis());
    }
}
