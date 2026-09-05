package com.stationannouncer.item;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Item for the edge-run blocks (el walls, el railings). These stand in the
 * cell just OUTSIDE the platform edge with their panel on the near side, so
 * every platform cell stays free for benches and bins. Placing them by hand
 * would mean clicking the side of something at platform level, so this item
 * helps: click the TOP of a floor block in its far third (in your look
 * direction) and the block goes into the cell beyond the edge instead, if
 * that cell is free. Any other click places normally.
 */
public class EdgeRunItem extends BlockItem {
    public EdgeRunItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        if (context.getSide() == Direction.UP && context.getPlayer() != null) {
            Direction look = context.getHorizontalPlayerFacing();
            BlockPos clicked = context.getBlockPos().down();
            Vec3d hit = context.getHitPos();
            double along = (hit.x - (clicked.getX() + 0.5)) * look.getOffsetX()
                    + (hit.z - (clicked.getZ() + 0.5)) * look.getOffsetZ();
            BlockPos beyond = context.getBlockPos().offset(look);
            // STAIRWELL RIM: the cell beyond the edge is the well over a stair
            // flight - never drop the wall into it. Stand it in this rim cell
            // instead, panel toward the well (facing = the look direction).
            if (along > 0.17 && context.getWorld().getBlockState(clicked).isOpaqueFullCube(context.getWorld(), clicked)
                    && context.getWorld().getBlockState(beyond.down()).getBlock()
                            instanceof com.stationannouncer.block.SubwayStairBlock) {
                ActionResult placed = super.place(context);
                if (placed.isAccepted()) {
                    BlockPos at = context.getBlockPos();
                    BlockState state = context.getWorld().getBlockState(at);
                    if (state.getBlock() instanceof com.stationannouncer.block.EdgeRunBlock
                            && state.get(com.stationannouncer.block.FacingDecorBlock.FACING) != look) {
                        BlockState turned = state.with(com.stationannouncer.block.FacingDecorBlock.FACING, look);
                        turned = turned.getBlock().getStateForNeighborUpdate(turned, Direction.UP,
                                context.getWorld().getBlockState(at.up()), context.getWorld(), at, at.up());
                        context.getWorld().setBlockState(at, turned, net.minecraft.block.Block.NOTIFY_ALL);
                    }
                }
                return placed;
            }
            if (along > 0.17 && context.getWorld().getBlockState(clicked).isOpaqueFullCube(context.getWorld(), clicked)
                    && !context.getWorld().getBlockState(beyond.down()).isOpaqueFullCube(context.getWorld(), beyond.down())) {
                // stack: courses already in the outside column -> the new one goes on top
                int climbed = 0;
                while (climbed < 6 && context.getWorld().getBlockState(beyond).getBlock()
                        instanceof com.stationannouncer.block.EdgeRunBlock) {
                    beyond = beyond.up();
                    climbed++;
                }
                if (context.getWorld().getBlockState(beyond).isReplaceable()) {
                    return super.place(ItemPlacementContext.offset(context, beyond, look));
                }
            }
        }
        return super.place(context);
    }
}
