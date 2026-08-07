package com.stationannouncer.block;

import com.mojang.serialization.MapCodec;
import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A PA network speaker. Plays whatever its linked control box broadcasts;
 * right-click opens the per-speaker volume/radius GUI (unless holding a
 * Speaker Link, which handles linking instead).
 */
public class SpeakerBlock extends BlockWithEntity {
    public static final MapCodec<SpeakerBlock> CODEC = createCodec(SpeakerBlock::new);

    public SpeakerBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        // Server-only ticker that re-validates a structure-pasted link once.
        return world.isClient ? null : validateTicker(type, ModContent.SPEAKER_BLOCK_ENTITY, SpeakerBlockEntity::serverTick);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player.getStackInHand(hand).isOf(ModContent.SPEAKER_LINK)) {
            return ActionResult.PASS; // let the Speaker Link handle link/unlink
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
                StationAnnouncer.GUI_OPENER.accept(speaker);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        // Actual removal (not chunk unload): drop out of the control box's list.
        if (!world.isClient && !state.isOf(newState.getBlock())
                && world.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            BlockPos boxPos = speaker.getControlBoxPos();
            if (boxPos != null && world.isChunkLoaded(boxPos)
                    && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box
                    && box.removeSpeaker(pos)) {
                box.sync();
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
