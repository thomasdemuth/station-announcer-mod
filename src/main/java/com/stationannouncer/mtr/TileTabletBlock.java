package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A tile-wall block carrying one of the little black name tablets, one letter
 * per tile, the way a platform wall spells its station out along its length.
 *
 * <p>The block itself is an ordinary white tile block — the tablet is drawn by
 * the block entity renderer, so it can hold text, follow the station name, and
 * spill past this block's own edges for a name wider than a metre. The tablet
 * is centred on the block, so you place one block where you want the middle of
 * the word and it grows both ways from there.</p>
 */
public class TileTabletBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /** Which of the block's four tile rows carries the tablet, counted from the top. */
    public static final IntProperty ROW = IntProperty.of("row", 0, 3);

    /** Tile rows in a block: the grid every texture here is drawn on. */
    public static final int ROWS = 4;

    public TileTabletBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(ROW, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROW);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new TileTabletBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // The tablet reads toward whoever placed it.
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    /** The MTR brush opens the tablet's editor; everything else passes through. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof TileTabletBlockEntity tablet) {
                StationAnnouncer.GUI_OPENER.accept(tablet);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
