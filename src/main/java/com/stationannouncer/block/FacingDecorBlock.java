package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A decorative block with a horizontal facing (bench, leaning bar, fare
 * machine). The outline shape is given for the NORTH facing and rotated for
 * the others.
 */
public class FacingDecorBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    private final VoxelShape north;
    private final VoxelShape east;
    private final VoxelShape south;
    private final VoxelShape west;

    public FacingDecorBlock(Settings settings, VoxelShape northShape) {
        super(settings);
        this.north = northShape;
        this.east = rotateClockwise(north);
        this.south = rotateClockwise(east);
        this.west = rotateClockwise(south);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    /**
     * Rotates a shape 90° clockwise (viewed from above) around the block center.
     * Simplified afterwards: merging the boxes back together once at startup
     * makes every later collision and raycast against the shape cheaper.
     */
    public static VoxelShape rotateClockwise(VoxelShape shape) {
        VoxelShape[] result = {VoxelShapes.empty()};
        for (Box box : shape.getBoundingBoxes()) {
            result[0] = VoxelShapes.union(result[0],
                    VoxelShapes.cuboid(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX));
        }
        return result[0].simplify();
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(FACING)) {
            case EAST -> east;
            case SOUTH -> south;
            case WEST -> west;
            default -> north;
        };
    }
}
