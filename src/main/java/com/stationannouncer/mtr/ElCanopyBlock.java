package com.stationannouncer.mtr;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The platform canopies: a roof plane that tiles freely in both directions —
 * any platform width and length. AXIS is the run direction (ribs/ridge),
 * taken from the player's look; the four connection booleans track same
 * FAMILY neighbours.
 *
 * <p>The flat canopy uses them to hang its riveted fascia girder on every
 * open edge. The gable uses the CROSSWISE pair (the two sides square to the
 * run) to pick which stretch of one continuous 22.5° roof this cell is: no
 * crosswise neighbour = the single-row 45° gable, one = a wing rising to the
 * shared boundary, two = the crown carrying the ridge. So a 2- or 3-block
 * deep platform gets ONE roof with the ridge over the middle and the eaves
 * overhanging the platform edges, instead of a one-block ribbon awning.
 *
 * <p>Families never merge: a flat canopy beside a gable is a second roof
 * that keeps its own fascia, and the gable keeps its own eave and end truss,
 * so the two meet cleanly instead of trying to become one surface.
 *
 * <p>Authored low in the block, so a canopy directly above its posts touches
 * them, and its underside chord gives hanging blocks (PIDS, signs) steel to
 * land on.
 */
public class ElCanopyBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    public static final BooleanProperty NORTH = BooleanProperty.of("north");
    public static final BooleanProperty SOUTH = BooleanProperty.of("south");
    public static final BooleanProperty EAST = BooleanProperty.of("east");
    public static final BooleanProperty WEST = BooleanProperty.of("west");

    /** Which canopies merge into one roof — a flat never joins a gable. */
    public enum Family {FLAT, GABLE}

    private final Family family;
    private final VoxelShape outline;
    private final VoxelShape collision;

    /**
     * The gable's geometry reaches far higher than the deck it is given for
     * collision (ridge 13.1 px on a wide roof), so the two shapes differ: you
     * still walk on the roof plane you can see, but the selection box covers
     * the whole profile instead of stopping at the eave.
     */
    public ElCanopyBlock(Settings settings, double height) {
        this(settings, height, height > 4.0 ? Family.GABLE : Family.FLAT);
    }

    public ElCanopyBlock(Settings settings, double height, Family family) {
        super(settings);
        this.family = family;
        this.collision = createCuboidShape(0.0, 0.0, 0.0, 16.0, height, 16.0);
        this.outline = createCuboidShape(0.0, 0.0, 0.0, 16.0,
                family == Family.GABLE ? 14.0 : height, 16.0);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X)
                .with(NORTH, false).with(SOUTH, false).with(EAST, false).with(WEST, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, NORTH, SOUTH, EAST, WEST);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outline;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collision;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withConnections(getDefaultState()
                        .with(AXIS, context.getHorizontalPlayerFacing().getAxis()),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    private BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        Direction.Axis axis = state.get(AXIS);
        return state
                .with(NORTH, joins(world, pos.offset(Direction.NORTH), axis))
                .with(SOUTH, joins(world, pos.offset(Direction.SOUTH), axis))
                .with(EAST, joins(world, pos.offset(Direction.EAST), axis))
                .with(WEST, joins(world, pos.offset(Direction.WEST), axis));
    }

    /**
     * Same family AND same run: a canopy laid across another one is a second
     * roof, not a wider one — merging those would ask a cell to be a wing of
     * a ridge running the wrong way, and the two profiles could never meet.
     * Kept apart, each closes its own end and they read as a junction.
     */
    private boolean joins(WorldAccess world, BlockPos pos, Direction.Axis axis) {
        BlockState neighbor = world.getBlockState(pos);
        return neighbor.getBlock() instanceof ElCanopyBlock other
                && other.family == family && neighbor.get(AXIS) == axis;
    }
}
