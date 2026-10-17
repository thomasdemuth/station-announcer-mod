package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * The NYC subway station trash can: grey steel, black liner folded over the
 * rim, MTA label band on the front (FACING = the side the label faces).
 * FILL 0..3 = empty / half / full / overflowing; right-click with an empty
 * hand cycles it. Collision is the can body.
 */
public class TrashCanBlock extends FacingDecorBlock {
    public static final IntProperty FILL = IntProperty.of("fill", 0, 3);
    private static final VoxelShape BODY = Block.createCuboidShape(2.75, 0, 2.75, 13.25, 13, 13.25);
    private static final VoxelShape HEAPED = Block.createCuboidShape(2.75, 0, 2.75, 13.25, 15.5, 13.25);

    public TrashCanBlock(Settings settings) {
        super(settings, BODY);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(FILL);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            BlockState next = state.cycle(FILL);
            world.setBlockState(pos, next, Block.NOTIFY_ALL);
            world.playSound(null, pos, next.get(FILL) == 0 ? SoundEvents.ITEM_BUNDLE_REMOVE_ONE : SoundEvents.ITEM_BUNDLE_INSERT,
                    SoundCategory.BLOCKS, 0.8f, 0.8f + world.random.nextFloat() * 0.2f);
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(FILL) == 3 ? HEAPED : BODY;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return BODY;
    }
}
