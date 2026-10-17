package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A caged incandescent "trouble light" on an orange cord. {@code hanging}
 * = true: it hangs from the block above by its hook (placed against the
 * underside of a block, or anywhere with a block above and nothing solid
 * below). {@code hanging} = false: it lies on the floor on its side, cord
 * trailing, turned per position by weighted model variants. Luminance 12
 * via settings; no collision.
 */
public class CagedWorkLampBlock extends Block {
    public static final BooleanProperty HANGING = BooleanProperty.of("hanging");

    private static final VoxelShape HANGING_SHAPE = ConstructionProps.px(5, 3, 5, 11, 16, 11);
    private static final VoxelShape LYING_SHAPE = ConstructionProps.px(3, 0, 3, 13, 5, 13);

    public CagedWorkLampBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(HANGING, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(HANGING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        boolean hanging;
        if (context.getSide() == Direction.DOWN) {
            hanging = true;
        } else if (context.getSide() == Direction.UP) {
            hanging = false;
        } else {
            BlockPos below = pos.down();
            hanging = !world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
                    && !world.getBlockState(pos.up()).isAir();
        }
        return getDefaultState().with(HANGING, hanging);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HANGING) ? HANGING_SHAPE : LYING_SHAPE;
    }
}
