package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A ceiling-hung holding light box (three round lenses, both sides), timed
 * off live MTR arrivals:
 * <ul>
 *   <li>YELLOW — lights 5 s before the train arrives and stays on while it
 *       dwells (a held train keeps it lit); goes dark 3 s before departure.</li>
 *   <li>GREEN — comes on at departure ("time to leave") and switches off
 *       15 s after the train has gone.</li>
 * </ul>
 * The platform is auto-detected; right-click WITH THE MTR BRUSH to pick a
 * specific platform instead (stored on the block).
 */
public class HoldingLightBlock extends FacingDecorBlock implements BlockEntityProvider {
    /** Green "time to leave" variant; false = yellow "train arriving". */
    public final boolean green;

    /**
     * Default timing, in seconds, when a block is left on automatic. Yellow
     * lights up that many seconds before the train arrives and goes dark that
     * many seconds before it departs; green lights up before departure and
     * goes dark that many seconds after it. Both are adjustable per block
     * with the MTR brush.
     */
    public int defaultOnSeconds() {
        return green ? 3 : 5;
    }

    public int defaultOffSeconds() {
        return green ? 15 : 6;
    }

    public HoldingLightBlock(Settings settings, boolean green, VoxelShape northShape) {
        super(settings, northShape);
        this.green = green;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        // Only the MTR brush opens the platform picker.
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
