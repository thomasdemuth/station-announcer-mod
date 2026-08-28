package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The station agent's booth: a fixed two-block kiosk (door-style multiblock —
 * lower wainscot cabinet with counter lip, upper glazing with the speak
 * grille facing FACING). Pure scenery; the fare machinery lives elsewhere.
 */
public class ElBoothBlock extends FacingDecorBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    private final VoxelShape shape;

    public ElBoothBlock(Settings settings) {
        super(settings, Block.createCuboidShape(0.8, 0.0, 0.8, 15.2, 16.0, 15.2));
        this.shape = Block.createCuboidShape(0.8, 0.0, 0.8, 15.2, 16.0, 15.2);
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(HALF);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shape;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        BlockPos above = context.getBlockPos().up();
        if (state == null || above.getY() >= context.getWorld().getTopY() - 1
                || !context.getWorld().getBlockState(above).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return state.with(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                && (!neighborState.isOf(this) || neighborState.get(HALF) == half)) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }
}
