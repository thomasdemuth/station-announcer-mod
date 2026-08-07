package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.PaDisplay;
import com.stationannouncer.block.PaDisplayBlock;
import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.BlockHitResult;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.Blocks;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemPlacementContext;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.LivingEntity;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.holder.WorldAccess;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.block.BlockPIDSBase;
import org.mtr.mod.block.IBlock;
import java.util.List;

/**
 * One block class for all five NYC PIDS variants — two-part multiblocks so
 * they can be broken from either end. Wall/standing styles occupy two blocks
 * vertically (half=lower holds the data); the hanging clock occupies two
 * blocks horizontally (side=left holds the data). Extends MTR's PIDS base, so
 * right-clicking ANY part with the MTR brush opens the standard PIDS config
 * screen (the canStoreData/getBlockPosWithData hooks route it to the data
 * half); only the rendering is ours.
 */
public class BlockPidsNyc extends BlockPIDSBase implements DirectionHelper, PaDisplayBlock {
    public final PidsStyle style;

    /** Outline shapes by [facing][primary], precomputed in the constructor. */
    private final VoxelShape[][] outlines = new VoxelShape[Direction.values().length][2];

    public BlockPidsNyc(PidsStyle style) {
        super(style.maxArrivals, BlockPidsNyc::isPrimary, BlockPidsNyc::primaryPos);
        this.style = style;
        // Built once per block, not once per query — getOutlineShape2 is hit by
        // every raycast, collision check and block outline the screen is in.
        for (Direction facing : Direction.values()) {
            for (int primary = 0; primary <= 1; primary++) {
                double[] shape = style.shape(primary == 1);
                outlines[facing.ordinal()][primary] = IBlock.getVoxelShapeByDirection(
                        shape[0], shape[1], shape[2], shape[3], shape[4], shape[5], facing);
            }
        }
    }

    // ------------------------------------------------------------ multiblock

    /** Does the block at this position hold the PIDS data (lower/left part)? */
    public static boolean isPrimary(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock().data instanceof BlockPidsNyc block)) {
            return false;
        }
        return isPrimaryState(block, state);
    }

    private static boolean isPrimaryState(BlockPidsNyc block, BlockState state) {
        if (block.style.horizontal) {
            return IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.LEFT;
        }
        return IBlock.getStatePropertySafe(state, IBlock.HALF) == IBlock.DoubleBlockHalf.LOWER;
    }

    /** Position of the data-holding part for the (possibly secondary) block at pos. */
    public static BlockPos primaryPos(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock().data instanceof BlockPidsNyc block) || isPrimaryState(block, state)) {
            return pos;
        }
        if (block.style.horizontal) {
            Direction facing = IBlock.getStatePropertySafe(state, FACING);
            return pos.offset(facing.rotateYClockwise().getOpposite());
        }
        return pos.down();
    }

    /** Direction from this part toward its partner part. */
    private Direction partnerDirection(BlockState state) {
        if (style.horizontal) {
            Direction right = IBlock.getStatePropertySafe(state, FACING).rotateYClockwise();
            return IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.LEFT ? right : right.getOpposite();
        }
        return IBlock.getStatePropertySafe(state, IBlock.HALF) == IBlock.DoubleBlockHalf.LOWER
                ? Direction.UP : Direction.DOWN;
    }

    // ------------------------------------------------------------- placement

    @Override
    public BlockState getPlacementState2(ItemPlacementContext context) {
        Direction facing = context.getPlayerFacing().getOpposite();
        BlockState state = getDefaultState2().with(new Property<>(FACING.data), facing.data);
        if (style.horizontal) {
            if (!IBlock.isReplaceable(context, facing.rotateYClockwise(), 2)) {
                return null;
            }
            return state.with(new Property<>(IBlock.SIDE.data), IBlock.EnumSide.LEFT);
        }
        if (!IBlock.isReplaceable(context, Direction.UP, 2)) {
            return null;
        }
        return state.with(new Property<>(IBlock.HALF.data), IBlock.DoubleBlockHalf.LOWER);
    }

    @Override
    public void onPlaced2(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced2(world, pos, state, placer, itemStack);
        if (!world.isClient()) {
            BlockPos partnerPos = pos.offset(partnerDirection(state));
            BlockState partnerState = style.horizontal
                    ? state.with(new Property<>(IBlock.SIDE.data), IBlock.EnumSide.RIGHT)
                    : state.with(new Property<>(IBlock.HALF.data), IBlock.DoubleBlockHalf.UPPER);
            world.setBlockState(partnerPos, partnerState, 3);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate2(BlockState state, Direction direction, BlockState neighborState,
                                                 WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        // Breaking either part removes the other, so the unit can be deleted
        // from the top or the bottom (or either side, for the hanging clock).
        if (direction.equals(partnerDirection(state)) && neighborState.getBlock().data != this) {
            return Blocks.getAirMapped().getDefaultState();
        }
        return super.getStateForNeighborUpdate2(state, direction, neighborState, world, pos, neighborPos);
    }

    // -------------------------------------------------------------- geometry

    @Override
    public void addBlockProperties(List<HolderBase<?>> properties) {
        // Called from the Block super-constructor, before this.style is set —
        // must not read instance fields. Vertical (half) is the default; the
        // Hanging subclass overrides with the side property.
        properties.add(FACING);
        properties.add(IBlock.HALF);
    }

    /** Horizontal (two-wide) variants: left/right instead of lower/upper. */
    public static class Hanging extends BlockPidsNyc {
        public Hanging(PidsStyle style) {
            super(style);
        }

        @Override
        public void addBlockProperties(List<HolderBase<?>> properties) {
            properties.add(FACING);
            properties.add(IBlock.SIDE);
        }
    }

    @Override
    public VoxelShape getOutlineShape2(BlockState state, BlockView view, BlockPos pos, ShapeContext context) {
        Direction facing = IBlock.getStatePropertySafe(state, FACING);
        boolean primary = state.getBlock().data instanceof BlockPidsNyc block && isPrimaryState(block, state);
        return outlines[facing.ordinal()][primary ? 1 : 0];
    }

    /** Speaker Link routing: clicks on either part act on the data-holding part. */
    @Override
    public net.minecraft.util.math.BlockPos paDataPos(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        return primaryPos(new World(world), new BlockPos(pos)).data;
    }

    /** Unlink from the PA Control Box when a player breaks either part. */
    @Override
    public void onBreak2(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient()) {
            PaDisplay.handleBroken(world.data, primaryPos(world, pos).data);
        }
        super.onBreak2(world, pos, state, player);
    }

    @Override
    public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        // Holding a Speaker Link: let the item handle the click (PA linking).
        if (player.isHolding(new org.mtr.mapping.holder.Item(ModContent.SPEAKER_LINK))) {
            return ActionResult.PASS;
        }
        // The brush opens OUR settings screen rather than MTR's, so every PIDS
        // in the mod shares one platform chooser. MTR's own screen is never
        // reached; the fields it offers that we do not are preserved server side.
        if (player.isHolding(org.mtr.mod.Items.BRUSH.get())) {
            if (world.isClient()) {
                BlockPos primary = primaryPos(world, pos);
                if (world.data.getBlockEntity(primary.data) instanceof PidsBlockEntity pids) {
                    MtrPids.CONFIG_GUI_OPENER.accept(pids);
                }
            }
            return ActionResult.SUCCESS;
        }
        ActionResult result = super.onUse2(state, world, pos, player, hand, hit);
        if (result == ActionResult.SUCCESS || style != PidsStyle.HANGING_MINI) {
            return result;
        }
        // Mini without a brush: open the "Next train" toggle screen (client side).
        if (world.isClient()) {
            BlockPos primary = primaryPos(world, pos);
            if (world.data.getBlockEntity(primary.data) instanceof PidsBlockEntity pids) {
                StationAnnouncer.GUI_OPENER.accept(pids);
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
        // Only the data half carries a block entity (and therefore the renderer).
        if (blockState.getBlock().data instanceof BlockPidsNyc block && !isPrimaryState(block, blockState)) {
            return null;
        }
        return new PidsBlockEntity(style, blockPos, blockState);
    }
}
