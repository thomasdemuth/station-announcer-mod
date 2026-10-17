package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.block.SelfSettle;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The blue-painted plywood partition that closes off a station area under
 * renovation. The painted sheet stands at the back of the cell (FACING =
 * the painted side, towards the player who placed it); behind it the 2x4
 * studs and plates show, like the work side of a real hoarding.
 *
 * <p>Runs: {@code left}/{@code right} = the same wall with the same facing
 * beside it (a batten covers the joint; free ends get a corner board).
 * Stacks: {@code up}/{@code down} = the same wall with the same facing
 * above/below (only the top of a stack has the cap rail, only the bottom
 * the kick base). Right-click with an empty hand cycles the painted notice.
 */
public class ConstructionWallBlock extends FacingDecorBlock {
    public enum Variant implements StringIdentifiable {
        PLAIN("plain"), NOTICE("notice"), NO_ADMITTANCE("no_admittance");

        private final String id;

        Variant(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final EnumProperty<Variant> VARIANT = EnumProperty.of("variant", Variant.class);
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    public static final BooleanProperty UP = BooleanProperty.of("up");
    public static final BooleanProperty DOWN = BooleanProperty.of("down");

    public ConstructionWallBlock(Settings settings) {
        super(settings, ConstructionProps.px(0, 0, 12.6, 16, 16, 16));
        setDefaultState(getDefaultState().with(VARIANT, Variant.PLAIN)
                .with(LEFT, false).with(RIGHT, false).with(UP, false).with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(VARIANT, LEFT, RIGHT, UP, DOWN);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) {
            return null;
        }
        // Stacking onto (or under) a wall keeps that wall's facing, whatever the look direction.
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        for (Direction d : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockState n = world.getBlockState(pos.offset(d));
            if (n.isOf(this)) {
                state = state.with(FACING, n.get(FACING));
                break;
            }
        }
        return withConnections(state, world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    public BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = ConstructionProps.right(facing);
        return state
                .with(LEFT, same(world, pos.offset(right.getOpposite()), facing))
                .with(RIGHT, same(world, pos.offset(right), facing))
                .with(UP, same(world, pos.up(), facing))
                .with(DOWN, same(world, pos.down(), facing));
    }

    private boolean same(BlockView world, BlockPos pos, Direction facing) {
        BlockState n = world.getBlockState(pos);
        return n.isOf(this) && n.get(FACING) == facing;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ConstructionProps.cycleWithEmptyHand(state, world, pos, player, hand, VARIANT, SoundEvents.BLOCK_WOOD_HIT);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        SelfSettle.settle(state, world, pos, Direction.DOWN);
    }
}
