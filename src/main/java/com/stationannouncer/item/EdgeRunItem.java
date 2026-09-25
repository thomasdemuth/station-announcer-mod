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
            if (along > 0.17 && isFloor(context.getWorld(), clicked) && overStairWell(context.getWorld(), beyond)) {
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
            if (along > 0.17 && isFloor(context.getWorld(), clicked) && !isFloor(context.getWorld(), beyond.down())) {
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
                // the outside cell is taken (a column, another block): do not drop the course
                // onto the floor inside instead - that is never what a far-edge click meant
                return ActionResult.FAIL;
            }
        }
        return super.place(context);
    }

    /**
     * Something you stand on: its whole top face is solid (concrete floors,
     * platform edges, the mezzanine floor, decks...). Opaque-full-cube was
     * too strict - the mezzanine floor is a framed slab, not a cube.
     */
    /**
     * The open cell beyond the rim is a STAIR WELL (not the trackway or the
     * street): a flight or its walls somewhere below it. Checked over the whole
     * well depth, not just the cell under - the rule used to fire only beside
     * the top step (whose tread is right under the rim), so the rest of the
     * rim railing went into the well cells and the run jumped to the other
     * side of the rim line.
     */
    private static boolean overStairWell(net.minecraft.world.World world, BlockPos beyond) {
        for (int i = 1; i <= 8; i++) {
            BlockState state = world.getBlockState(beyond.down(i));
            if (state.getBlock() instanceof com.stationannouncer.block.SubwayStairBlock
                    || state.getBlock() instanceof com.stationannouncer.block.ElStairUpperBlock
                    || state.getBlock() instanceof com.stationannouncer.block.ElStairSideBlock) {
                return true;
            }
            if (!state.isAir() && !state.isReplaceable()) {
                return false;
            }
        }
        return false;
    }

    private static boolean isFloor(net.minecraft.world.World world, BlockPos pos) {
        return world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP);
    }
}
