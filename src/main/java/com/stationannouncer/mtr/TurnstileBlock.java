package com.stationannouncer.mtr;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The low Cubic tripod turnstile (v2, 2026-09-06). Stainless cabinet on the
 * rider's right with the MetroCard hood + OMNY tablet, indicator pylon at the
 * paid end, overhead grab-rail arch to the next unit ({@link #JOIN}), and a
 * tripod arm drawn by {@code TurnstileRenderer} from the lower half's
 * {@link TurnstileBlockEntity}.
 *
 * <p>The lane physically BLOCKS until MTR approves the fare: {@link #OPEN}
 * drops the barrier wall from both halves' collision while the arm turns
 * 120 degrees, and {@link FareLane#tickLock} closes it again once the rider
 * has cleared the plane. Fare logic itself lives in {@link FareLane}.
 *
 * <p>Exit-only lanes ({@code allowEntry = false}) process everyone as leaving
 * and carry a fixed no-entry roundel toward the unpaid side.
 */
public class TurnstileBlock extends TurnstileBaseBlock implements BlockEntityProvider, FareLane.Host {
    /** Another array unit continues on the lane side — the arch bridges to it. */
    public static final BooleanProperty JOIN = BooleanProperty.of("join");
    /** Barrier unlocked (collision wall gone, arm turning). */
    public static final BooleanProperty OPEN = BooleanProperty.of("open");
    /** Pylon lamp state, set from the fare callback on the UPPER half. */
    public static final EnumProperty<Indicator> INDICATOR = EnumProperty.of("indicator", Indicator.class);

    public enum Indicator implements StringIdentifiable {
        OFF, GO, STOP, WAIT;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * When each lit indicator is due to clear, keyed by the indicator cell's
     * pos, so a stale WAIT-clear tick cannot wipe a fresh GO early.
     */
    private static final Map<Long, Long> INDICATOR_DEADLINE = new HashMap<>();

    public static void clearAttempts() {
        FareLane.clearAttempts();
        INDICATOR_DEADLINE.clear();
    }

    private final boolean allowEntry;

    // [open ? 1 : 0][facing.getHorizontal()]
    private final VoxelShape[][] lowerCollision = new VoxelShape[2][];
    private final VoxelShape[][] upperCollision = new VoxelShape[2][];
    private final VoxelShape[] lowerOutline;
    // [join ? 1 : 0]
    private final VoxelShape[][] upperOutline = new VoxelShape[2][];

    public TurnstileBlock(Settings settings) {
        this(settings, true);
    }

    public TurnstileBlock(Settings settings, boolean allowEntry) {
        super(settings);
        this.allowEntry = allowEntry;
        setDefaultState(getDefaultState().with(JOIN, false).with(OPEN, false).with(INDICATOR, Indicator.OFF));

        VoxelShape cabinet = createCuboidShape(11, 0, 0, 16, 16, 16);
        VoxelShape wall = createCuboidShape(0, 0, 7, 11, 16, 9);
        VoxelShape tripod = createCuboidShape(0.5, 6, 2, 11.6, 16, 14);
        VoxelShape pylon = VoxelShapes.union(
                createCuboidShape(11, 0, 0, 16, 11, 4),
                createCuboidShape(12, 11, 0, 15, 14, 3));
        VoxelShape reader = createCuboidShape(11.6, 0, 6, 15.4, 4.4, 10.4);
        // the renderer's arch: riser + semicircle over the lane to the neighbour
        VoxelShape arch = createCuboidShape(0, 14, 0, 16, 24, 3);

        lowerCollision[0] = rotations(VoxelShapes.union(cabinet, wall).simplify());
        lowerCollision[1] = rotations(cabinet);
        lowerOutline = rotations(VoxelShapes.union(cabinet, tripod).simplify());
        VoxelShape upperFixed = VoxelShapes.union(pylon, reader).simplify();
        upperCollision[0] = rotations(VoxelShapes.union(upperFixed, wall).simplify());
        upperCollision[1] = rotations(upperFixed);
        upperOutline[0] = upperCollision[1];
        upperOutline[1] = rotations(VoxelShapes.union(upperFixed, arch).simplify());
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(JOIN, OPEN, INDICATOR);
    }

    @Override
    protected BlockState withNeighbours(BlockState state, WorldAccess world, BlockPos pos) {
        return state.with(JOIN, joinsLaneSide(state, world, pos));
    }

    // ------------------------------------------------------------- shapes --

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER
                ? rotated(upperOutline[state.get(JOIN) ? 1 : 0], state)
                : rotated(lowerOutline, state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int open = state.get(OPEN) ? 1 : 0;
        return state.get(HALF) == DoubleBlockHalf.UPPER
                ? rotated(upperCollision[open], state)
                : rotated(lowerCollision[open], state);
    }

    // ------------------------------------------------------ block entity --

    /** The data cell: the lower half. */
    private static BlockPos lanePos(BlockState state, BlockPos pos) {
        return state.get(HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.LOWER ? new TurnstileBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || state.get(HALF) != DoubleBlockHalf.LOWER
                || type != MtrStationDecor.TURNSTILE_BLOCK_ENTITY) {
            return null;
        }
        return (w, p, s, be) -> FareLane.tickLock(this, w, p, s, (TurnstileBlockEntity) be);
    }

    // --------------------------------------------------------------- fare --

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient || !(entity instanceof PlayerEntity)) {
            return;
        }
        BlockPos lane = lanePos(state, pos);
        BlockState laneState = state.get(HALF) == DoubleBlockHalf.LOWER ? state : world.getBlockState(lane);
        if (!laneState.isOf(this) || !(world.getBlockEntity(lane) instanceof TurnstileBlockEntity be)) {
            return;
        }
        FareLane.onRiderInLane(this, laneState, world, lane, entity, be);
    }

    @Override
    public boolean allowEntry() {
        return allowEntry;
    }

    @Override
    public Direction facing(BlockState state) {
        return state.get(FACING);
    }

    @Override
    public Vec3d barrierCentre(BlockState state, BlockPos pos) {
        // Lane centre is 2.5 px left of the block centre (cabinet x 11..16).
        Direction right = state.get(FACING).rotateYClockwise();
        return Vec3d.ofCenter(pos).add(Vec3d.of(right.getVector()).multiply(-2.5 / 16.0));
    }

    @Override
    public double barrierHalfWidth() {
        return 5.5 / 16.0;
    }

    @Override
    public boolean isOpen(BlockState state) {
        return state.get(OPEN);
    }

    @Override
    public void setOpen(World world, BlockPos pos, BlockState state, boolean open) {
        BlockPos lower = lanePos(state, pos);
        for (BlockPos p : new BlockPos[]{lower, lower.up()}) {
            BlockState s = world.getBlockState(p);
            if (s.isOf(this) && s.get(OPEN) != open) {
                world.setBlockState(p, s.with(OPEN, open), Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    public void setIndicator(World world, BlockPos pos, Indicator indicator, int clearTicks) {
        BlockPos upperPos = lanePos(world.getBlockState(pos), pos).up();
        BlockState upper = world.getBlockState(upperPos);
        if (upper.isOf(this) && upper.get(HALF) == DoubleBlockHalf.UPPER) {
            world.setBlockState(upperPos, upper.with(INDICATOR, indicator));
            INDICATOR_DEADLINE.put(upperPos.asLong(), world.getTime() + clearTicks);
            world.scheduleBlockTick(upperPos, this, clearTicks);
        }
    }

    /** Low turnstiles beep like MTR's ticket processors; the HEET overrides to the barrier clunk. */
    @Override
    public org.mtr.mapping.holder.SoundEvent entrySound() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent entrySoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY_CONCESSIONARY.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent exitSound() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_EXIT.get();
    }

    @Override
    public org.mtr.mapping.holder.SoundEvent exitSoundConcessionary() {
        return org.mtr.mod.SoundEvents.TICKET_PROCESSOR_EXIT_CONCESSIONARY.get();
    }

    /** Empty-hand right-click = balance enquiry, like MTR's enquiry processor. */
    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos,
                                                 PlayerEntity player, net.minecraft.util.Hand hand,
                                                 net.minecraft.util.hit.BlockHitResult hit) {
        if (!player.getStackInHand(hand).isEmpty()) {
            return net.minecraft.util.ActionResult.PASS;
        }
        if (world.isClient) {
            return net.minecraft.util.ActionResult.SUCCESS;
        }
        int balance = org.mtr.mod.data.TicketSystem.getBalance(
                new org.mtr.mapping.holder.World(world),
                new org.mtr.mapping.holder.PlayerEntity(player));
        world.playSound(null, pos, org.mtr.mod.SoundEvents.TICKET_PROCESSOR_ENTRY.get().data,
                net.minecraft.sound.SoundCategory.BLOCKS, 0.6f, 1.0f);
        player.sendMessage(Text.translatable("msg.station_announcer.fare.balance", balance), true);
        return net.minecraft.util.ActionResult.CONSUME;
    }

    /** Shared by the HEET: clears a lit indicator once its deadline has passed. */
    static void clearIndicatorTick(BlockState state, ServerWorld world, BlockPos pos) {
        if (state.get(INDICATOR) == Indicator.OFF) {
            return;
        }
        Long deadline = INDICATOR_DEADLINE.get(pos.asLong());
        if (deadline != null && world.getTime() < deadline) {
            return;
        }
        INDICATOR_DEADLINE.remove(pos.asLong());
        world.setBlockState(pos, state.with(INDICATOR, Indicator.OFF));
    }

    static void rememberDeadline(BlockPos pos, long due) {
        INDICATOR_DEADLINE.put(pos.asLong(), due);
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        clearIndicatorTick(state, world, pos);
    }
}
