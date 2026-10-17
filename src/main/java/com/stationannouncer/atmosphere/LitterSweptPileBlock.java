package com.stationannouncer.atmosphere;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
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
import net.minecraft.world.WorldView;

/**
 * A small swept-up pile of dust and bits. {@code broom=true} adds a push
 * broom lying against it, as if the porter just stepped away; right-click
 * with an empty hand toggles the broom. No collision, replaceable, needs a
 * solid floor.
 */
public class LitterSweptPileBlock extends Block {
    public static final BooleanProperty BROOM = BooleanProperty.of("broom");
    private static final VoxelShape PILE = Block.createCuboidShape(3, 0, 3, 13, 3, 13);
    private static final VoxelShape WITH_BROOM = VoxelShapes.union(PILE, Block.createCuboidShape(1, 0, 1, 15, 3, 15)).simplify();

    public LitterSweptPileBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(BROOM, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(BROOM);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            world.setBlockState(pos, state.cycle(BROOM), Block.NOTIFY_ALL);
            world.playSound(null, pos, BlockSoundGroup.BAMBOO_WOOD.getPlaceSound(), SoundCategory.BLOCKS, 0.6f, 1.1f);
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return Block.sideCoversSmallSquare(world, pos.down(), Direction.UP);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canPlaceAt(world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(BROOM) ? WITH_BROOM : PILE;
    }
}
