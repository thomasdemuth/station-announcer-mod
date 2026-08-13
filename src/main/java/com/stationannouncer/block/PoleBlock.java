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
 * A thin pole that lies along whichever axis it was placed against, like a
 * vanilla log — click a ceiling or floor for an upright drop, click a wall for
 * a horizontal run to hang markers off.
 *
 * <p>The axis defaults to Y, so poles placed before this block could turn stay
 * exactly as they were.</p>
 */
public class PoleBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.AXIS;

    /** Outline shapes by {@link Direction.Axis#ordinal()} (X, Y, Z). */
    private final VoxelShape[] shapes = new VoxelShape[3];

    /**
     * @param radius half the pole's thickness in model pixels (2 px gauge = 1.0)
     */
    public PoleBlock(Settings settings, double radius) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.Y));
        double min = 8.0 - radius;
        double max = 8.0 + radius;
        shapes[Direction.Axis.X.ordinal()] = createCuboidShape(0.0, min, min, 16.0, max, max);
        shapes[Direction.Axis.Y.ordinal()] = createCuboidShape(min, 0.0, min, max, 16.0, max);
        shapes[Direction.Axis.Z.ordinal()] = createCuboidShape(min, min, 0.0, max, max, 16.0);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // The pole runs out of the face that was clicked, so a run continues
        // itself when you keep clicking the end of it.
        return getDefaultState().with(AXIS, context.getSide().getAxis());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(AXIS).ordinal()];
    }
}
