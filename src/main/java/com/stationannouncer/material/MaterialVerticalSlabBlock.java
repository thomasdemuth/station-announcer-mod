package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A slab standing on its edge, in any full block's texture. {@link #FACING} is
 * the side of the cell the half-block thickness sits on.
 *
 * <p>Placement: clicked against a wall face, it sits against that block; clicked
 * on a floor/ceiling, it goes to the edge of the cell nearest the click (the
 * player's facing when the click is near the centre). A second vertical slab in
 * the empty half fills the cell: same texture = a full double slab, another
 * texture = a {@link MaterialMixedSlabBlock} split down the middle. The MTR
 * brush turns it a quarter (sneak = the other way).</p>
 */
public class MaterialVerticalSlabBlock extends Block implements MaterialBlock, Waterloggable {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        SHAPES.put(Direction.NORTH, Block.createCuboidShape(0, 0, 0, 16, 16, 8));
        SHAPES.put(Direction.SOUTH, Block.createCuboidShape(0, 0, 8, 16, 16, 16));
        SHAPES.put(Direction.WEST, Block.createCuboidShape(0, 0, 0, 8, 16, 16));
        SHAPES.put(Direction.EAST, Block.createCuboidShape(8, 0, 0, 16, 16, 16));
    }

    public MaterialVerticalSlabBlock(Settings settings) {
        super(settings);
        setDefaultState(MaterialPalette.withSlot(getDefaultState(), 0)
                .with(FACING, Direction.NORTH).with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED, MaterialPalette.MAT_HI, MaterialPalette.MAT_LO);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES.get(state.get(FACING));
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    @Override
    public boolean canPathfindThrough(BlockState state, BlockView world, BlockPos pos, NavigationType type) {
        return false;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState existing = world.getBlockState(pos);
        if (existing.isOf(this)) {
            // Filling the empty half: same texture = full double slab, else a mixed slab split this way.
            int base = MaterialPalette.slot(existing);
            int added = MaterialPalette.slotFor(MaterialPalette.materialOf(context.getStack()), world.isClient);
            return MaterialMixedSlabBlock.of(base, added, existing.get(FACING).getOpposite());
        }
        Direction side = context.getSide();
        Direction facing;
        if (side.getAxis().isHorizontal()) {
            facing = side.getOpposite(); // against the block that was clicked
        } else {
            Vec3d hit = context.getHitPos();
            double dx = hit.x - pos.getX() - 0.5;
            double dz = hit.z - pos.getZ() - 0.5;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 0.15) {
                facing = context.getHorizontalPlayerFacing();
            } else if (Math.abs(dx) > Math.abs(dz)) {
                facing = dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                facing = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
        }
        boolean water = world.getFluidState(pos).getFluid() == Fluids.WATER;
        return MaterialBlock.withPlacedMaterial(getDefaultState().with(FACING, facing).with(WATERLOGGED, water), context);
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        if (!context.getStack().isOf(asItem())) {
            return false;
        }
        if (context.canReplaceExisting()) {
            // clicked this slab itself: only its inner face leads into the empty half
            return context.getSide() == state.get(FACING).getOpposite();
        }
        return true; // vanilla slab parity: placing into this cell from a neighbour fills it
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!MaterialBlock.isBrush(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            Direction facing = state.get(FACING);
            world.setBlockState(pos, state.with(FACING, player.isSneaking()
                    ? facing.rotateYCounterclockwise() : facing.rotateYClockwise()), Block.NOTIFY_ALL);
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        return List.of(stackFor(this, state, builder.getWorld()));
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return stackFor(this, state, world instanceof World w ? w : null);
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        MaterialBlocks.clientRemesh(world, pos);
        return super.onBreak(world, pos, state, player);
    }
}
