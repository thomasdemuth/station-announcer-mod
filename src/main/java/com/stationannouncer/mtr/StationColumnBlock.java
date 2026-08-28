package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
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
 * A column with the station name on a black board (both flange faces).
 * Right-click opens the custom-name screen; the name auto-follows the MTR
 * station otherwise.
 */
public class StationColumnBlock extends ColumnBlock implements BlockEntityProvider {
    /** How far (blocks) the name board floats off the column centre — the
     * renderer reads this so slimmer columns (the el family) keep the board
     * hugging their face instead of the iron column's flange plane. */
    private final float boardOffset;

    public StationColumnBlock(Settings settings, VoxelShape northShape) {
        this(settings, northShape, 0.25f);
    }

    public StationColumnBlock(Settings settings, VoxelShape northShape, float boardOffset) {
        super(settings, northShape);
        this.boardOffset = boardOffset;
    }

    public float boardOffset() {
        return boardOffset;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                StationAnnouncer.GUI_OPENER.accept(decor);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
