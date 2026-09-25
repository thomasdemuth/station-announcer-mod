package com.stationannouncer.item;

import com.stationannouncer.block.SubwayStairBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;

/** Block item for the subway stairs (both finishes): continues a flight diagonally. */
public class StairFlightItem extends BlockItem {
    public StairFlightItem(Block block, Settings settings) {
        super(block, settings);
    }

    /**
     * FLIGHTS BY CLICKING: vanilla can only put a block next to the face you
     * click, so a staircase needed scaffolding under every step. Clicking the
     * top of a same stair continues its flight diagonally instead - the UPPER
     * step extends it up + forward, the LOWER step down + back - so a flight
     * of any length is one click per step (and one click per extra lane on the
     * stair's side face, which vanilla already handles). Sneak places normally.
     */
    @Override
    public net.minecraft.util.ActionResult place(net.minecraft.item.ItemPlacementContext context) {
        if (context.getSide() == net.minecraft.util.math.Direction.UP && !com.stationannouncer.block.StairFamily.forced(context)) {
            net.minecraft.util.math.BlockPos clicked = context.getBlockPos().down();
            net.minecraft.block.BlockState stair = context.getWorld().getBlockState(clicked);
            if (stair.isOf(getBlock())) {
                net.minecraft.util.math.Direction f = stair.get(SubwayStairBlock.FACING);
                double dy = context.getHitPos().y - clicked.getY();
                net.minecraft.util.math.BlockPos target = dy > 0.75 ? clicked.up().offset(f)
                        : clicked.down().offset(f.getOpposite());
                if (context.getWorld().getBlockState(target).isReplaceable()) {
                    return super.place(net.minecraft.item.ItemPlacementContext.offset(context, target,
                            net.minecraft.util.math.Direction.UP));
                }
            }
        }
        return super.place(context);
    }

}
