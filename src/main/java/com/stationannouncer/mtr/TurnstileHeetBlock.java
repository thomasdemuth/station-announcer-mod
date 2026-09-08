package com.stationannouncer.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * High Entrance/Exit Turnstile (v2, 2026-09-06): the stainless full-height
 * "iron maiden", built to the real 5'6" x 3'8" x 7' footprint as a 2 wide x
 * 1 deep x 2 tall multiblock. The LANE cell (placed block) holds the curved
 * perforated cage the rider slides along and the rotor spindle on its comb
 * side boundary; the COMB cell on the rider's right holds the frame post,
 * fixed comb bars the rotor wings interleave with, and the indicator pylon.
 * The upper lane cell carries the top beam and the round drum canopy with
 * the ENTRY band. The three-wing curved-bar rotor is drawn by
 * {@code TurnstileRenderer} from the lane_lower cell's block entity.
 *
 * <p>Locked, both lane cells carry a barrier wall across the passage; on
 * MTR's approval {@link #OPEN} drops it and the rotor turns 120 degrees in
 * the rider's direction (the rotor is bidirectional — exits turn it back).
 */
public class TurnstileHeetBlock extends Block implements BlockEntityProvider, FareLane.Host {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);
    public static final BooleanProperty OPEN = TurnstileBlock.OPEN;
    public static final EnumProperty<TurnstileBlock.Indicator> INDICATOR = TurnstileBlock.INDICATOR;

    public enum Part implements StringIdentifiable {
        LANE_LOWER, LANE_UPPER, COMB_LOWER, COMB_UPPER;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        boolean lane() {
            return this == LANE_LOWER || this == LANE_UPPER;
        }

        boolean lower() {
            return this == LANE_LOWER || this == COMB_LOWER;
        }
    }

    // [open][facing.getHorizontal()]
    private final VoxelShape[][] laneCollision = new VoxelShape[2][];
    private final VoxelShape[] laneLowerOutline;
    private final VoxelShape[] laneUpperOutline;
    private final VoxelShape[] combLower;
    private final VoxelShape[] combUpper;

    public TurnstileHeetBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(PART, Part.LANE_LOWER)
                .with(OPEN, false).with(INDICATOR, TurnstileBlock.Indicator.OFF));
        VoxelShape wall = createCuboidShape(0, 0, 7, 16, 16, 9);
        laneCollision[0] = rotations(wall);
        laneCollision[1] = rotations(VoxelShapes.empty());
        // outline: cage arc footprint + spindle
        laneLowerOutline = rotations(VoxelShapes.union(
                createCuboidShape(2, 0, 0, 8, 16, 16),
                createCuboidShape(14, 0, 6, 16, 16, 10)).simplify());
        laneUpperOutline = rotations(VoxelShapes.union(
                createCuboidShape(2, 0, 0, 8, 11, 16),
                createCuboidShape(14, 0, 6, 16, 16, 10),
                createCuboidShape(0, 16, 6.5, 16, 18, 9.5),
                createCuboidShape(1, 18, 0, 16, 24, 16)).simplify());
        // comb cell (its own coords: post at x 13..15)
        VoxelShape combWall = createCuboidShape(0, 0, 6, 16, 16, 10);
        VoxelShape pylonLower = createCuboidShape(12, 8, 2, 16, 16, 5);
        VoxelShape pylonUpper = createCuboidShape(12, 0, 2, 16, 14.6, 5);
        combLower = rotations(VoxelShapes.union(combWall, pylonLower).simplify());
        combUpper = rotations(VoxelShapes.union(combWall, pylonUpper,
                createCuboidShape(0, 16, 6.5, 16, 18, 9.5)).simplify());
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, OPEN, INDICATOR);
    }

    static VoxelShape[] rotations(VoxelShape north) {
        VoxelShape east = FacingDecorBlock.rotateClockwise(north);
        VoxelShape south = FacingDecorBlock.rotateClockwise(east);
        VoxelShape west = FacingDecorBlock.rotateClockwise(south);
        return new VoxelShape[]{south, west, north, east};
    }

    private static VoxelShape rotated(VoxelShape[] shapes, BlockState state) {
        return shapes[state.get(FACING).getHorizontal()];
    }

    // ------------------------------------------------------------- layout --

    /** Comb cell is on the rider's right. */
    private static Direction toComb(BlockState state) {
        return state.get(FACING).rotateYClockwise();
    }

    /** The lane_lower (data) cell of the group this cell belongs to. */
    static BlockPos lanePos(BlockState state, BlockPos pos) {
        Part part = state.get(PART);
        BlockPos p = part.lane() ? pos : pos.offset(toComb(state).getOpposite());
        return part.lower() ? p : p.down();
    }

    private static BlockPos cellPos(BlockState state, BlockPos lane, Part part) {
        BlockPos p = part.lane() ? lane : lane.offset(toComb(state));
        return part.lower() ? p : p.up();
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        World world = context.getWorld();
        BlockState state = getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
        if (pos.getY() >= world.getTopY() - 1) {
            return null;
        }
        for (Part part : new Part[]{Part.LANE_UPPER, Part.COMB_LOWER, Part.COMB_UPPER}) {
            if (!world.getBlockState(cellPos(state, pos, part)).isReplaceable()) {
                return null;
            }
        }
        return state;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        for (Part part : new Part[]{Part.LANE_UPPER, Part.COMB_LOWER, Part.COMB_UPPER}) {
            world.setBlockState(cellPos(state, pos, part), state.with(PART, part), Block.NOTIFY_ALL);
        }
    }

    /** Removing any cell takes the whole group (loot is gated on lane_lower). */
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!newState.isOf(this)) {
            BlockPos lane = lanePos(state, pos);
            for (Part part : Part.values()) {
                BlockPos cell = cellPos(state, lane, part);
                BlockState other = world.getBlockState(cell);
                if (!cell.equals(pos) && other.isOf(this) && other.get(PART) == part
                        && other.get(FACING) == state.get(FACING)) {
                    world.breakBlock(cell, true);
                }
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    /** Creative players get no drop: pull the data cell first, silently. */
    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient && player.isCreative()) {
            BlockPos lane = lanePos(state, pos);
            BlockState laneState = world.getBlockState(lane);
            if (!lane.equals(pos) && laneState.isOf(this)) {
                world.setBlockState(lane, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
            }
        }
        return super.onBreak(world, pos, state, player);
    }

    // ------------------------------------------------------------- shapes --

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(PART)) {
            case LANE_LOWER -> rotated(laneLowerOutline, state);
            case LANE_UPPER -> rotated(laneUpperOutline, state);
            case COMB_LOWER -> rotated(combLower, state);
            case COMB_UPPER -> rotated(combUpper, state);
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(PART)) {
            case LANE_LOWER, LANE_UPPER -> rotated(laneCollision[state.get(OPEN) ? 1 : 0], state);
            case COMB_LOWER -> rotated(combLower, state);
            case COMB_UPPER -> rotated(combUpper, state);
        };
    }

    // ------------------------------------------------------ block entity --

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(PART) == Part.LANE_LOWER ? new TurnstileBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || state.get(PART) != Part.LANE_LOWER || type != MtrStationDecor.TURNSTILE_BLOCK_ENTITY) {
            return null;
        }
        return (w, p, s, be) -> FareLane.tickLock(this, w, p, s, (TurnstileBlockEntity) be);
    }

    // --------------------------------------------------------------- fare --

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient || !state.get(PART).lane() || !(entity instanceof PlayerEntity)) {
            return;
        }
        BlockPos lane = lanePos(state, pos);
        BlockState laneState = state.get(PART) == Part.LANE_LOWER ? state : world.getBlockState(lane);
        if (!laneState.isOf(this) || !(world.getBlockEntity(lane) instanceof TurnstileBlockEntity be)) {
            return;
        }
        FareLane.onRiderInLane(this, laneState, world, lane, entity, be);
    }

    @Override
    public boolean allowEntry() {
        return true;
    }

    @Override
    public Direction facing(BlockState state) {
        return state.get(FACING);
    }

    @Override
    public Vec3d barrierCentre(BlockState state, BlockPos pos) {
        return Vec3d.ofCenter(pos);
    }

    @Override
    public double barrierHalfWidth() {
        return 0.5;
    }

    @Override
    public boolean isOpen(BlockState state) {
        return state.get(OPEN);
    }

    @Override
    public void setOpen(World world, BlockPos pos, BlockState state, boolean open) {
        BlockPos lane = lanePos(state, pos);
        for (Part part : new Part[]{Part.LANE_LOWER, Part.LANE_UPPER}) {
            BlockPos cell = cellPos(state, lane, part);
            BlockState s = world.getBlockState(cell);
            if (s.isOf(this) && s.get(OPEN) != open) {
                world.setBlockState(cell, s.with(OPEN, open), Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    public void setIndicator(World world, BlockPos pos, TurnstileBlock.Indicator indicator, int clearTicks) {
        BlockState state = world.getBlockState(pos);
        if (!state.isOf(this)) {
            return;
        }
        BlockPos cell = cellPos(state, lanePos(state, pos), Part.COMB_UPPER);
        BlockState comb = world.getBlockState(cell);
        if (comb.isOf(this) && comb.get(PART) == Part.COMB_UPPER) {
            world.setBlockState(cell, comb.with(INDICATOR, indicator));
            TurnstileBlock.rememberDeadline(cell, world.getTime() + clearTicks);
            world.scheduleBlockTick(cell, this, clearTicks);
        }
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        TurnstileBlock.clearIndicatorTick(state, world, pos);
    }

    // A full-height rotor really does clunk: the barrier sound in both directions.
    @Override
    public org.mtr.mapping.holder.SoundEvent entrySound() {
        return org.mtr.mod.SoundEvents.TICKET_BARRIER.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent entrySoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent exitSound() {
        return org.mtr.mod.SoundEvents.TICKET_BARRIER.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent exitSoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_BARRIER_CONCESSIONARY.get();
    }
}
