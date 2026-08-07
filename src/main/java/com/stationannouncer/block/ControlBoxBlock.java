package com.stationannouncer.block;

import com.mojang.serialization.MapCodec;
import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The PA Control Box: same triggering surface as the Station Announcer
 * (right-click GUI, redstone rising edge, /announce tags) but broadcasts
 * through its linked speakers instead of playing at its own position.
 * Right-clicking with a Speaker Link passes through to the item so the box
 * can be selected for linking.
 */
public class ControlBoxBlock extends BlockWithEntity {
    public static final MapCodec<ControlBoxBlock> CODEC = createCodec(ControlBoxBlock::new);
    public static final BooleanProperty POWERED = Properties.POWERED;

    public ControlBoxBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Record the current power state on placement so a block placed next to
        // an already-active redstone line does not instantly announce.
        return getDefaultState().with(POWERED, ctx.getWorld().isReceivingRedstonePower(ctx.getBlockPos()));
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ControlBoxBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, ModContent.CONTROL_BOX_BLOCK_ENTITY, ControlBoxBlockEntity::serverTick);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player.getStackInHand(hand).isOf(ModContent.SPEAKER_LINK)) {
            return ActionResult.PASS; // let the Speaker Link select this box
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                StationAnnouncer.GUI_OPENER.accept(box);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isClient) {
            return;
        }
        boolean powered = world.isReceivingRedstonePower(pos);
        if (powered != state.get(POWERED)) {
            world.setBlockState(pos, state.with(POWERED, powered), Block.NOTIFY_LISTENERS);
            // Rising edge only: no retrigger while the signal stays on.
            if (powered && world.getBlockEntity(pos) instanceof ControlBoxBlockEntity box) {
                box.trigger();
            }
        }
    }
}
