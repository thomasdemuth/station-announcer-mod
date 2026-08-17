package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * The platform-end track gate: the red "Do not enter or cross tracks" plate
 * hinged to a square steel post, swung open when staff need through.
 *
 * <p>A real gate, not a painted panel: right-click swings the plate 90 degrees
 * about its hinge post, iron-door sounds and all. Closed it blocks the walkway;
 * open it is passable, and only the fixed hinge post keeps collision.</p>
 *
 * <p>It implements {@link GateSection} so it slots into a run of fare-control
 * ironwork the way the photos show — the bars either side treat it as part of
 * the run, and its own hinge post takes the place of the shared post at its
 * left edge, keeping the one-post-per-joint rhythm.</p>
 */
public class TrackWarningGateBlock extends Block implements GateSection {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = Properties.OPEN;

    private final VoxelShape[] closedShapes = new VoxelShape[Direction.values().length];
    private final VoxelShape[] postShapes = new VoxelShape[Direction.values().length];

    public TrackWarningGateBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(OPEN, false));
        for (Direction facing : Direction.values()) {
            boolean alongX = facing.getAxis() == Direction.Axis.X;
            closedShapes[facing.ordinal()] = alongX
                    ? createCuboidShape(6, 0, 0, 10, 16, 16)
                    : createCuboidShape(0, 0, 6, 16, 16, 10);
            // The hinge post straddles the block's left edge in the run frame;
            // a centred stub is close enough for collision on every facing.
            postShapes[facing.ordinal()] = alongX
                    ? createCuboidShape(6, 0, 0, 10, 16, 2)
                    : createCuboidShape(0, 0, 6, 2, 16, 10);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Placed into an ironwork run, it adopts the run's facing.
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        for (Direction side : Direction.values()) {
            BlockState neighbour = context.getWorld().getBlockState(context.getBlockPos().offset(side));
            if (neighbour.getBlock() instanceof GateSection && neighbour.contains(FACING)) {
                facing = neighbour.get(FACING);
                break;
            }
        }
        return getDefaultState().with(FACING, facing);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        boolean opening = !state.get(OPEN);
        world.setBlockState(pos, state.with(OPEN, opening), Block.NOTIFY_ALL);
        world.playSound(null, pos, opening
                        ? SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                SoundCategory.BLOCKS, 0.8f, 1.3f);
        return ActionResult.CONSUME;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN)
                ? postShapes[state.get(FACING).ordinal()]
                : closedShapes[state.get(FACING).ordinal()];
    }

    /** Closed it bars the way; open, only the hinge post remains solid. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN)
                ? VoxelShapes.empty()
                : closedShapes[state.get(FACING).ordinal()];
    }
}
