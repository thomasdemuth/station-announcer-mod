package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * Sloped red standing-seam roof over an el stair flight. FACING = ascent (the
 * roof descends with the stairs); a row of cells across the flight makes one
 * roof: cells with no same roof beside them ({@link #EDGE_LEFT} /
 * {@link #EDGE_RIGHT}, looking uphill) hang the lattice frieze and the eave
 * fascia on that side; {@link #UP} / {@link #DOWN} close the run
 * ends (a plate perpendicular to the slope uphill, a fascia at the foot).
 * Place it in the cell above the top side course - its frieze sits on the
 * course's top rail. Assets: tools/gen_el2_stairs.py.
 */
public class ElStairRoofBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty EDGE_LEFT = BooleanProperty.of("edge_left");
    public static final BooleanProperty EDGE_RIGHT = BooleanProperty.of("edge_right");
    /** How a run end is closed: RUN = another cell continues, END = open (fascia), LANDING = a landing roof takes over. */
    public enum End implements net.minecraft.util.StringIdentifiable {
        RUN("run"), END("end"), LANDING("landing");

        private final String name;

        End(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final net.minecraft.state.property.EnumProperty<End> UP =
            net.minecraft.state.property.EnumProperty.of("up", End.class);
    public static final net.minecraft.state.property.EnumProperty<End> DOWN =
            net.minecraft.state.property.EnumProperty.of("down", End.class);

    /**
     * The deck underside is at y 36 - z (model frame, z = downhill), so a
     * full-cell box blocked the flight. Four steps whose bottoms track the
     * underside (a 1 px margin under it) let a player walk beneath the roof
     * placed two cells above the treads; rotated per facing.
     */
    private static final VoxelShape[] SHAPES;

    static {
        VoxelShape north = net.minecraft.util.shape.VoxelShapes.empty();
        for (int i = 0; i < 4; i++) {
            double z0 = 4 * i, z1 = 4 * (i + 1);
            double bottom = 36 - z1 - 1;
            north = net.minecraft.util.shape.VoxelShapes.union(north,
                    Block.createCuboidShape(0, bottom, z0, 16, 32, z1));
        }
        SHAPES = FacingDecorBlock.rotations(north.simplify());
    }

    public ElStairRoofBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(EDGE_LEFT, true)
                .with(EDGE_RIGHT, true).with(UP, End.END).with(DOWN, End.END));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, EDGE_LEFT, EDGE_RIGHT, UP, DOWN);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(FACING).getHorizontal()];
    }

    private boolean same(WorldAccess world, BlockPos cell, Direction facing) {
        BlockState state = world.getBlockState(cell);
        return state.isOf(this) && state.get(FACING) == facing;
    }

    private BlockState compute(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        // a landing roof continues the deck: one up + forward at the top of the
        // flight (its deck is at our uphill edge height), in our own row behind
        // us at the foot (its deck is at our downhill edge height)
        boolean landingUp = world.getBlockState(pos.up().offset(facing)).getBlock() instanceof ElLandingRoofBlock;
        boolean landingDown = world.getBlockState(pos.offset(facing.getOpposite())).getBlock() instanceof ElLandingRoofBlock;
        End up = same(world, pos.up().offset(facing), facing) ? End.RUN : landingUp ? End.LANDING : End.END;
        End down = same(world, pos.down().offset(facing.getOpposite()), facing) ? End.RUN
                : landingDown ? End.LANDING : End.END;
        return state.with(EDGE_LEFT, !same(world, pos.offset(facing.rotateYCounterclockwise()), facing))
                .with(EDGE_RIGHT, !same(world, pos.offset(facing.rotateYClockwise()), facing))
                .with(UP, up).with(DOWN, down);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction facing = context.getHorizontalPlayerFacing();
        // adopt the ascent of a roof or side course beside / below us
        for (BlockPos near : new BlockPos[]{pos.east(), pos.west(), pos.north(), pos.south(), pos.down()}) {
            BlockState s = world.getBlockState(near);
            if (s.isOf(this)) {
                facing = s.get(FACING);
                break;
            }
            if (s.getBlock() instanceof ElStairSideBlock) {
                facing = s.get(ElStairSideBlock.FACING);
                break;
            }
        }
        return compute(getDefaultState().with(FACING, facing), world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = compute(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return compute(state, world, pos);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        refreshDiagonals(world, pos, state);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!newState.isOf(this)) {
            refreshDiagonals(world, pos, state);
        }
    }

    private void refreshDiagonals(World world, BlockPos pos, BlockState state) {
        Direction facing = state.get(FACING);
        ElLandingRoofBlock.refreshAround(world, pos, facing);
        for (BlockPos cell : new BlockPos[]{pos.up().offset(facing), pos.down().offset(facing.getOpposite())}) {
            BlockState neighbor = world.getBlockState(cell);
            if (neighbor.isOf(this)) {
                BlockState fresh = compute(neighbor, world, cell);
                if (fresh != neighbor) {
                    world.setBlockState(cell, fresh, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }
}
