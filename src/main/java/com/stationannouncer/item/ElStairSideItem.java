package com.stationannouncer.item;

import com.stationannouncer.block.SubwayStairBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Item for the el stair side courses. They live in the cell BESIDE a stair
 * block, so clicking a stair tread in its outer third (across the flight)
 * puts the course into the cell on that side, at the stair's level; if courses
 * already stand there the new one goes on top of them (click the tread again
 * to stack). SNEAK + the same click puts the side on the stair's own edge
 * instead (railing item = railing, wall items = wall, posts-only item = bare
 * stringer; again = off). Any other click places normally.
 */
public class ElStairSideItem extends BlockItem {
    public ElStairSideItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        if (context.getSide() == Direction.UP) {
            BlockPos clicked = context.getBlockPos().down();
            BlockState stair = context.getWorld().getBlockState(clicked);
            if (stair.getBlock() instanceof SubwayStairBlock) {
                Direction right = stair.get(SubwayStairBlock.FACING).rotateYClockwise();
                Vec3d hit = context.getHitPos();
                double lateral = (hit.x - (clicked.getX() + 0.5)) * right.getOffsetX()
                        + (hit.z - (clicked.getZ() + 0.5)) * right.getOffsetZ();
                if (Math.abs(lateral) > 0.12 && context.getPlayer() != null && context.getPlayer().isSneaking()) {
                    // SNEAK: the side goes ON THE STAIR'S OWN EDGE instead of into the cell beside
                    // it (tight wells, lane dividers). Same item again takes it off.
                    SubwayStairBlock.InSide side = getBlock() == com.stationannouncer.ModContent.EL_STAIR_RAILING
                            ? SubwayStairBlock.InSide.RAILING
                            : getBlock() == com.stationannouncer.ModContent.EL_STAIR_OPEN
                            ? SubwayStairBlock.InSide.STRINGER : SubwayStairBlock.InSide.WALL;
                    if (!context.getWorld().isClient) {
                        ((SubwayStairBlock) stair.getBlock()).toggleSide(context.getWorld(), clicked, stair, lateral < 0, side);
                        context.getWorld().playSound(null, clicked, net.minecraft.sound.SoundEvents.BLOCK_METAL_PLACE,
                                net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 0.9f);
                    }
                    return ActionResult.success(context.getWorld().isClient);
                }
                if (Math.abs(lateral) > 0.12) {
                    Direction toward = lateral > 0 ? right : right.getOpposite();
                    BlockPos beside = clicked.offset(toward);
                    // stack: climb over courses already standing in the outside column
                    int climbed = 0;
                    while (climbed < 6 && context.getWorld().getBlockState(beside).getBlock()
                            instanceof com.stationannouncer.block.ElStairSideBlock) {
                        beside = beside.up();
                        climbed++;
                    }
                    if (context.getWorld().getBlockState(beside).isReplaceable()) {
                        return super.place(ItemPlacementContext.offset(context, beside, toward));
                    }
                    // the outside column is blocked (floor, wall...): do not dump the course onto the tread
                    return ActionResult.FAIL;
                }
            }
        }
        return super.place(context);
    }
}
