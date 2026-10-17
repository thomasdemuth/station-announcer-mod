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
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * Yellow "CAUTION" cordon tape strung at waist height along {@code axis}.
 * Each end looks at the next cell along the run:
 * <ul>
 *   <li>{@code tape}: more tape on the same axis - the band runs on;</li>
 *   <li>{@code wall}: a full solid face - the tape is taped flat to it;</li>
 *   <li>{@code post}: anything else with a shape (a cone, a barricade,
 *       a fence post, a scaffold, tape running across) - the band reaches
 *       to that cell's centre and is knotted round it;</li>
 *   <li>{@code none}: nothing there - a loose end droops.</li>
 * </ul>
 * {@code double} adds the second, lower strand (right-click with an empty
 * hand toggles it). Collision is a thin full-length wall 1.5 blocks high,
 * so the tape really cordons an area off.
 */
public class CautionTapeBlock extends Block {
    public enum End implements StringIdentifiable {
        NONE("none"), TAPE("tape"), WALL("wall"), POST("post");

        private final String id;

        End(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    /** The −x end (axis x) or −z end (axis z). */
    public static final EnumProperty<End> LEFT = EnumProperty.of("left", End.class);
    /** The +x end (axis x) or +z end (axis z). */
    public static final EnumProperty<End> RIGHT = EnumProperty.of("right", End.class);
    public static final BooleanProperty DOUBLE = BooleanProperty.of("double");

    private static final VoxelShape COLLIDE_X = ConstructionProps.px(0, 0, 7.5, 16, 24, 8.5);
    private static final VoxelShape COLLIDE_Z = ConstructionProps.px(7.5, 0, 0, 8.5, 24, 16);
    private static final VoxelShape OUTLINE_X = ConstructionProps.px(0, 11.5, 7, 16, 14.5, 9);
    private static final VoxelShape OUTLINE_Z = ConstructionProps.px(7, 11.5, 0, 9, 14.5, 16);
    private static final VoxelShape OUTLINE_X2 = ConstructionProps.union(OUTLINE_X, ConstructionProps.px(0, 4, 7, 16, 7, 9));
    private static final VoxelShape OUTLINE_Z2 = ConstructionProps.union(OUTLINE_Z, ConstructionProps.px(7, 4, 0, 9, 7, 16));

    public CautionTapeBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X)
                .with(LEFT, End.NONE).with(RIGHT, End.NONE).with(DOUBLE, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, LEFT, RIGHT, DOUBLE);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        // Carry on an existing run; otherwise string the tape ACROSS the player's view.
        Direction.Axis axis = context.getHorizontalPlayerFacing().rotateYClockwise().getAxis();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState n = world.getBlockState(pos.offset(d));
            if (n.isOf(this) && n.get(AXIS) == d.getAxis()) {
                axis = d.getAxis();
                break;
            }
        }
        return withEnds(getDefaultState().with(AXIS, axis), world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withEnds(state, world, pos);
    }

    public BlockState withEnds(BlockState state, BlockView world, BlockPos pos) {
        Direction.Axis axis = state.get(AXIS);
        Direction plus = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        return state.with(LEFT, endToward(world, pos, plus.getOpposite(), axis))
                .with(RIGHT, endToward(world, pos, plus, axis));
    }

    private End endToward(BlockView world, BlockPos pos, Direction dir, Direction.Axis axis) {
        BlockPos at = pos.offset(dir);
        BlockState n = world.getBlockState(at);
        if (n.isOf(this)) {
            return n.get(AXIS) == axis ? End.TAPE : End.POST;
        }
        if (n.isAir() || n.isReplaceable()) {
            return End.NONE;
        }
        if (n.isSideSolidFullSquare(world, at, dir.getOpposite())) {
            return End.WALL;
        }
        return n.getOutlineShape(world, at).isEmpty() ? End.NONE : End.POST;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ConstructionProps.cycleWithEmptyHand(state, world, pos, player, hand, DOUBLE, SoundEvents.BLOCK_WOOL_PLACE);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        boolean x = state.get(AXIS) == Direction.Axis.X;
        if (state.get(DOUBLE)) {
            return x ? OUTLINE_X2 : OUTLINE_Z2;
        }
        return x ? OUTLINE_X : OUTLINE_Z;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(AXIS) == Direction.Axis.X ? COLLIDE_X : COLLIDE_Z;
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
