package com.stationannouncer.atmosphere;

import com.stationannouncer.block.SelfSettle;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
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
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * One bay of galvanized tube-and-clamp scaffold: standards on the cell
 * corners, ledgers and transoms at the top, a diagonal brace on the FACING
 * side, a plank deck on top.
 *
 * <p>Standards, ledgers and transoms sit ON the cell edges, so neighbouring
 * bays share them. To draw each shared piece once, a bay leaves out the
 * pieces on its east / south edges when another bay is there
 * ({@code east}/{@code south}); the west / north ones are always its own.
 * {@code up}/{@code down} = another bay above/below (base plates only at
 * the bottom of a stack, pipe caps only at the top). The deck shows on the
 * top lift, or anywhere {@code deck} is switched on (right-click with an
 * empty hand). Collision: the four standards plus the deck when it shows.
 */
public class ScaffoldFrameBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty EAST = BooleanProperty.of("east");
    public static final BooleanProperty SOUTH = BooleanProperty.of("south");
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");
    public static final BooleanProperty DECK = BooleanProperty.of("deck");

    private static final VoxelShape STANDARDS = ConstructionProps.union(
            ConstructionProps.px(0, 0, 0, 0.9, 16, 0.9),
            ConstructionProps.px(15.1, 0, 0, 16, 16, 0.9),
            ConstructionProps.px(0, 0, 15.1, 0.9, 16, 16),
            ConstructionProps.px(15.1, 0, 15.1, 16, 16, 16));
    private static final VoxelShape WITH_DECK = ConstructionProps.union(STANDARDS,
            ConstructionProps.px(0, 15, 0, 16, 16, 16));

    public ScaffoldFrameBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(EAST, false).with(SOUTH, false)
                .with(UP, false).with(DOWN, false).with(DECK, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, EAST, SOUTH, UP, DOWN, DECK);
    }

    /** Whether the plank deck is drawn (and walkable) in this state. */
    public static boolean hasDeck(BlockState state) {
        return state.get(DECK) || !state.get(UP);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withConnections(getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite()),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    public BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
        return state.with(EAST, world.getBlockState(pos.east()).isOf(this))
                .with(SOUTH, world.getBlockState(pos.south()).isOf(this))
                .with(UP, world.getBlockState(pos.up()).isOf(this))
                .with(DOWN, world.getBlockState(pos.down()).isOf(this));
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ConstructionProps.cycleWithEmptyHand(state, world, pos, player, hand, DECK, SoundEvents.BLOCK_WOOD_HIT);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return hasDeck(state) ? WITH_DECK : STANDARDS;
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
