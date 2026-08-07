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
 * The placeable PA speaker block. Right-click opens the configuration screen
 * (client side); a redstone rising edge triggers the announcement.
 */
public class AnnouncerBlock extends BlockWithEntity {
    public static final MapCodec<AnnouncerBlock> CODEC = createCodec(AnnouncerBlock::new);
    public static final BooleanProperty POWERED = Properties.POWERED;

    public AnnouncerBlock(Settings settings) {
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
        return new AnnouncerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        // Delayed announcements only count down on the logical server.
        return world.isClient ? null : validateTicker(type, ModContent.ANNOUNCER_BLOCK_ENTITY, AnnouncerBlockEntity::serverTick);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof AnnouncerBlockEntity announcer) {
                // Indirection keeps client-only screen classes off the dedicated
                // server classpath; the opener is installed by the client entrypoint.
                StationAnnouncer.GUI_OPENER.accept(announcer);
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
            if (powered && world.getBlockEntity(pos) instanceof AnnouncerBlockEntity announcer) {
                announcer.trigger();
            }
        }
    }
}
