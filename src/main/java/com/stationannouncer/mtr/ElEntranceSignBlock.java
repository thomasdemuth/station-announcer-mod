package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The lit black entrance sign box hanging under the stair hood (Van Siclen
 * Av: "3" bullet + station name). Placed in the cell under the hood's front
 * edge, FACING the street; two straps reach the hood's frieze chord. Data
 * rides the shared {@link StationDecorBlockEntity} exactly like the entrance
 * railing sign: custom name, both faces on/off, route bullets per face - the
 * MTR brush opens {@code RailingSignScreen}. Both faces are painted by
 * {@code StationDecorRenderer.paintEntranceSign}.
 */
public class ElEntranceSignBlock extends FacingDecorBlock implements BlockEntityProvider {
    /** A same-facing sign continues to the left / right (looking at the front): no hanger strap on that end. */
    public static final net.minecraft.state.property.BooleanProperty LEFT = net.minecraft.state.property.BooleanProperty.of("left");
    public static final net.minecraft.state.property.BooleanProperty RIGHT = net.minecraft.state.property.BooleanProperty.of("right");

    public ElEntranceSignBlock(Settings settings) {
        super(settings, Block.createCuboidShape(0.0, 12.0, 2.0, 16.0, 24.0, 5.0));
        setDefaultState(getDefaultState().with(LEFT, false).with(RIGHT, false));
    }

    @Override
    protected void appendProperties(net.minecraft.state.StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(LEFT, RIGHT);
    }

    private static boolean joins(net.minecraft.world.WorldAccess world, BlockPos pos, Direction facing) {
        BlockState s = world.getBlockState(pos);
        return s.getBlock() instanceof ElEntranceSignBlock && s.get(FACING) == facing;
    }

    private static BlockState withJoins(BlockState state, net.minecraft.world.WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        // model x grows toward facing.rotateYClockwise() (the run direction the renderer uses)
        return state.with(RIGHT, joins(world, pos.offset(facing.rotateYClockwise()), facing))
                .with(LEFT, joins(world, pos.offset(facing.rotateYCounterclockwise()), facing));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(net.minecraft.item.ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : withJoins(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                net.minecraft.world.WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withJoins(state, world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = withJoins(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    /** The face the sign's FRONT looks at: the block's facing (the street side). */
    public static Direction frontOf(BlockState state) {
        return state.get(FACING);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                StationAnnouncer.GUI_OPENER.accept(decor);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
