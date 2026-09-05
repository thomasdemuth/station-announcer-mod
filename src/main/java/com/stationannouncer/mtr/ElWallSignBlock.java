package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.ElWallBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The windscreen's station-name band: a wall course whose panel carries a
 * black board painted by {@code StationDecorRenderer.paintWallSign} on the
 * RIDER side only (the facing side). Adjacent sign courses in one run merge
 * into one board; a custom name on any segment wins. MTR brush opens the
 * name screen.
 */
public class ElWallSignBlock extends ElWallBlock implements BlockEntityProvider {
    public ElWallSignBlock(Settings settings) {
        super(settings);
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
