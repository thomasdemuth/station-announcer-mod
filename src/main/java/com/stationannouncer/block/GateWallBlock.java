package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

/**
 * A fare-control dividing wall: the black ironwork that fences off a mezzanine.
 *
 * <p>Two variants share this class and differ only in texture — the ornate
 * scrollwork panel and the flat woven grille — and they are deliberately built
 * on the same frame, with posts at the block edges and rails at matching
 * heights, so a run of one can meet a run of the other and the ironwork lines
 * up across the joint.</p>
 *
 * <p>Height is however many you stack. {@link #UP} and {@link #DOWN} record
 * whether the run continues, which is all the model needs to know: a cell with
 * nothing above draws the capping rail, and one with nothing below draws the
 * kick rail. Panels in the middle of a run draw neither, so a tall wall reads
 * as one continuous screen rather than a stack of framed panels.</p>
 *
 * <p>Any gate wall connects to any other, whichever pattern — that is what
 * lets a scrollwork section run into a grille section without a seam.</p>
 */
public class GateWallBlock extends Block implements GateSection {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty UP = Properties.UP;
    public static final BooleanProperty DOWN = Properties.DOWN;
    /**
     * Whether the run continues sideways. Needed for SHARED POSTS: every
     * section draws a post on its left edge, and only the right-hand end of a
     * run draws a closing one, so a joint shows exactly one post instead of two
     * uprights jammed together.
     */
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /**
     * Whether this cell carries an upright.
     *
     * <p>The bar fence wants one every block — the post IS part of the bar
     * rhythm. The grille does not: a post every block chopped the mesh into
     * one-block panels, so it posts only every {@link #postEvery} blocks and
     * the mesh runs unbroken between them.</p>
     */
    public static final BooleanProperty POST = BooleanProperty.of("post");

    /** Depth of the panel, in model pixels either side of the block centre. */
    private static final double HALF_THICKNESS = 2.0;

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    /** How many blocks of infill may run between uprights. */
    private final int postEvery;

    public GateWallBlock(Settings settings, int postEvery) {
        super(settings);
        this.postEvery = Math.max(1, postEvery);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(UP, false).with(DOWN, false).with(LEFT, false).with(RIGHT, false)
                .with(POST, true));
        for (Direction facing : Direction.values()) {
            outlines[facing.ordinal()] = facing.getAxis() == Direction.Axis.X
                    ? createCuboidShape(8 - HALF_THICKNESS, 0, 0, 8 + HALF_THICKNESS, 16, 16)
                    : createCuboidShape(0, 0, 8 - HALF_THICKNESS, 16, 16, 8 + HALF_THICKNESS);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT, POST);
    }

    /**
     * Any section joins any other of the same facing - bars to grille to door -
     * so a run can change pattern or take a doorway without breaking.
     */
    private BlockState withNeighbours(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        // How far along the run this cell sits, so uprights land on a regular
        // spacing rather than wherever a block happens to have been placed.
        Direction left = right.getOpposite();
        int distance = 0;
        BlockPos scan = pos;
        while (distance < 48 && GateSection.joins(world.getBlockState(scan.offset(left)), facing)) {
            scan = scan.offset(left);
            distance++;
        }
        return state.with(UP, GateSection.joins(world.getBlockState(pos.up()), facing))
                .with(DOWN, GateSection.joins(world.getBlockState(pos.down()), facing))
                .with(LEFT, GateSection.joins(world.getBlockState(pos.offset(left)), facing))
                .with(RIGHT, GateSection.joins(world.getBlockState(pos.offset(right)), facing))
                .with(POST, distance % postEvery == 0);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Stacking onto an existing run adopts its facing, so a tall wall never
        // ends up half-turned because the player walked round it.
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        for (Direction side : Direction.values()) {
            BlockState neighbour = context.getWorld().getBlockState(context.getBlockPos().offset(side));
            if (neighbour.getBlock() instanceof GateSection && neighbour.contains(FACING)) {
                facing = neighbour.get(FACING);
                break;
            }
        }
        return withNeighbours(getDefaultState().with(FACING, facing),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withNeighbours(state, world, pos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }
}
