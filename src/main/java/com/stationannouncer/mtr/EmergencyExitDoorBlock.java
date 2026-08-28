package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The emergency exit door in a bank of fare-control gates.
 *
 * <p>Like the turnstile it never physically blocks anybody — walking through is
 * the whole interaction. It is <b>exit only</b>: the door faces the unpaid
 * side, so crossing from paid to unpaid is a legitimate exit and merely sets
 * the alarm off, while crossing the other way is fare evasion and is fined
 * through MTR's own {@link org.mtr.mod.data.TicketSystem}, the same balance the
 * fare machine tops up and the turnstile charges.</p>
 *
 * <p>The alarm is a world sound, not a per-player one, so the whole mezzanine
 * hears it exactly as they would in a real station. It runs for
 * {@value #ALARM_SECONDS} seconds and stops on its own — a station left
 * screaming forever is nobody's idea of a good build — and a redstone pulse
 * fires it too, so it can be wired to anything.</p>
 *
 * <p>Always two blocks tall, and it swings open on right-click like any other
 * door — opening an emergency exit is itself what sets the alarm off.</p>
 */
public class EmergencyExitDoorBlock extends Block implements com.stationannouncer.block.GateSection {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /**
     * A door is exactly two blocks, like every other doorway in the game — it
     * is a fixed piece of equipment, not something you stretch. Stacking it to
     * arbitrary heights let the leaf art warp across however many cells it was
     * given, which is what made it look wrong.
     */
    public static final net.minecraft.state.property.EnumProperty<net.minecraft.block.enums.DoubleBlockHalf> HALF
            = Properties.DOUBLE_BLOCK_HALF;
    /** Swings open on right-click, like a vanilla door. */
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** Shared posts with an adjoining wall run - see GateWallBlock. */
    public static final BooleanProperty LEFT = com.stationannouncer.block.GateWallBlock.LEFT;
    public static final BooleanProperty RIGHT = com.stationannouncer.block.GateWallBlock.RIGHT;
    /** Redstone input, with vanilla iron-door semantics: powered = open. */
    public static final BooleanProperty POWERED = Properties.POWERED;
    /** True while the alarm sounds; the blockstate shows the strobe lamp off it. */
    public static final BooleanProperty ALARM = BooleanProperty.of("alarm");

    public static final int ALARM_SECONDS = 15;
    private static final int ALARM_TICKS = ALARM_SECONDS * 20;
    /** The loop is 2 s, so re-issue it a little under that to avoid a gap. */
    private static final int ALARM_LOOP_TICKS = 38;

    /** Matches MTR's own evasion charge ($500, bytecode-verified) so sneaking
     * in through the emergency exit never beats jumping a turnstile. */
    public static final int EVASION_FINE = 500;

    private static final double HALF_THICKNESS = 3.0;
    /** One crossing per player per lane per two seconds. */
    private static final int RETRY_COOLDOWN_TICKS = 40;

    private record Crossing(long door, UUID player) {
    }

    private static final Map<Crossing, Long> LAST_CROSSING = new HashMap<>();

    /** Dropped when the server stops — the JVM outlives worlds in singleplayer. */
    public static void clearCrossings() {
        LAST_CROSSING.clear();
    }

    private final VoxelShape[] outlines = new VoxelShape[Direction.values().length];

    public EmergencyExitDoorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER)
                .with(OPEN, false).with(POWERED, false).with(ALARM, false)
                .with(LEFT, false).with(RIGHT, false));
        for (Direction facing : Direction.values()) {
            outlines[facing.ordinal()] = facing.getAxis() == Direction.Axis.X
                    ? createCuboidShape(8 - HALF_THICKNESS, 0, 0, 8 + HALF_THICKNESS, 16, 16)
                    : createCuboidShape(0, 0, 8 - HALF_THICKNESS, 16, 16, 8 + HALF_THICKNESS);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, OPEN, POWERED, ALARM, LEFT, RIGHT);
    }

    private BlockState withNeighbours(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        Direction right = facing.rotateYClockwise();
        return state.with(LEFT, com.stationannouncer.block.GateSection.joins(
                        world.getBlockState(pos.offset(right.getOpposite())), facing))
                .with(RIGHT, com.stationannouncer.block.GateSection.joins(
                        world.getBlockState(pos.offset(right)), facing));
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // The paid-side signage faces the placer: you stand where the signs
        // should read and place. The door never adopts a neighbouring run's
        // facing — the player's look direction is authoritative, and a door
        // reversed within a run simply frames itself with its own posts.
        Direction facing = context.getHorizontalPlayerFacing();
        BlockPos above = context.getBlockPos().up();
        if (above.getY() >= context.getWorld().getTopY() - 1
                || !context.getWorld().getBlockState(above).isReplaceable()) {
            return null; // needs two blocks of room
        }
        return withNeighbours(getDefaultState().with(FACING, facing),
                context.getWorld(), context.getBlockPos());
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         @org.jetbrains.annotations.Nullable net.minecraft.entity.LivingEntity placer,
                         net.minecraft.item.ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        world.setBlockState(pos.up(),
                state.with(HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
    }

    /** Right-click swings it, and opening an emergency exit sets the alarm off. */
    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, World world, BlockPos pos,
                                                 PlayerEntity player, net.minecraft.util.Hand hand,
                                                 net.minecraft.util.hit.BlockHitResult hit) {
        if (world.isClient) {
            return net.minecraft.util.ActionResult.SUCCESS;
        }
        BlockPos lower = state.get(HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER ? pos : pos.down();
        setOpen(world, lower, !state.get(OPEN));
        return net.minecraft.util.ActionResult.CONSUME;
    }

    /** Swings both halves; opening is what sets the alarm off. */
    private void setOpen(World world, BlockPos lower, boolean opening) {
        for (BlockPos cell : new BlockPos[]{lower, lower.up()}) {
            BlockState half = world.getBlockState(cell);
            if (half.getBlock() == this && half.get(OPEN) != opening) {
                world.setBlockState(cell, half.with(OPEN, opening), Block.NOTIFY_ALL);
            }
        }
        world.playSound(null, lower, opening
                        ? net.minecraft.sound.SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : net.minecraft.sound.SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        if (opening) {
            trigger(world, lower, world.getBlockState(lower));
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        net.minecraft.block.enums.DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == net.minecraft.block.enums.DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                && (!neighborState.isOf(this) || neighborState.get(HALF) == half)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        return withNeighbours(state, world, pos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(FACING).ordinal()];
    }

    /**
     * Closed, the door is a real barrier - unlike the turnstile, this is a
     * locked gate, and pushing on it is what the OPEN state is for. Open, the
     * doorway must be walkable AND collision-free, because the wrong-way
     * detection in {@link #onEntityCollision} only fires for blocks an entity
     * can actually intersect. A solid shape here was why the fare-evasion
     * logic could never trigger: nobody can stand inside a solid block.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? net.minecraft.util.shape.VoxelShapes.empty()
                : outlines[state.get(FACING).ordinal()];
    }

    // ------------------------------------------------------------ behaviour

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient || !(entity instanceof PlayerEntity player) || player.isSpectator()) {
            return;
        }
        long time = world.getTime();
        Crossing key = new Crossing(pos.asLong(), player.getUuid());
        Long last = LAST_CROSSING.get(key);
        if (last != null && time - last < RETRY_COOLDOWN_TICKS) {
            return;
        }
        LAST_CROSSING.put(key, time);
        if (LAST_CROSSING.size() > 512) {
            LAST_CROSSING.entrySet().removeIf(entry -> time - entry.getValue() > 400);
        }

        // The door FACES the unpaid side, so a player moving the same way the
        // door faces is on their way out; moving against it is coming in.
        Direction facing = state.get(FACING);
        double approach = entity.getVelocity().getX() * facing.getOffsetX()
                + entity.getVelocity().getZ() * facing.getOffsetZ();
        boolean entering = approach < 0;

        trigger(world, pos, state);
        if (entering) {
            fine(world, player);
        }
    }

    /** Charges the evasion fine through MTR, and says so in the action bar. */
    private static void fine(World world, PlayerEntity player) {
        org.mtr.mapping.holder.World holderWorld = new org.mtr.mapping.holder.World(world);
        org.mtr.mapping.holder.PlayerEntity holderPlayer = new org.mtr.mapping.holder.PlayerEntity(player);
        org.mtr.mod.data.TicketSystem.addBalance(holderWorld, holderPlayer, -EVASION_FINE);
        world.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO,
                SoundCategory.BLOCKS, 1.0f, 0.9f);
        player.sendMessage(Text.translatable("msg.station_announcer.gate.fined", EVASION_FINE,
                org.mtr.mod.data.TicketSystem.getBalance(holderWorld, holderPlayer)), true);
    }

    /** Starts the alarm on the whole stacked door and schedules its first loop. */
    public static void trigger(World world, BlockPos pos, BlockState state) {
        if (world.isClient) {
            return;
        }
        BlockPos bottom = state.contains(HALF)
                && state.get(HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? pos.down() : pos;
        for (BlockPos cell : new BlockPos[]{bottom, bottom.up()}) {
            BlockState half = world.getBlockState(cell);
            if (half.getBlock() instanceof EmergencyExitDoorBlock) {
                world.setBlockState(cell, half.with(ALARM, true), Block.NOTIFY_ALL);
            }
        }
        // Only the bottom cell keeps time and makes the noise, so a four-block
        // door is not four alarms shouting over each other.
        world.scheduleBlockTick(bottom, world.getBlockState(bottom).getBlock(), 1);
        world.setBlockState(bottom, world.getBlockState(bottom).with(POWERED, true), Block.NOTIFY_ALL);
    }

    @Override
    public void scheduledTick(BlockState state, net.minecraft.server.world.ServerWorld world,
                              BlockPos pos, net.minecraft.util.math.random.Random random) {
        if (!state.get(ALARM)) {
            return;
        }
        world.playSound(null, pos, ModContent.GATE_ALARM, SoundCategory.BLOCKS, 1.6f, 1.0f);
        Integer elapsed = ALARM_ELAPSED.merge(pos.asLong(), ALARM_LOOP_TICKS, Integer::sum);
        if (elapsed >= ALARM_TICKS) {
            ALARM_ELAPSED.remove(pos.asLong());
            for (BlockPos cell : new BlockPos[]{pos, pos.up()}) {
                BlockState half = world.getBlockState(cell);
                if (half.getBlock() instanceof EmergencyExitDoorBlock) {
                    world.setBlockState(cell, half.with(ALARM, false), Block.NOTIFY_ALL);
                }
            }
            return;
        }
        world.scheduleBlockTick(pos, this, ALARM_LOOP_TICKS);
    }

    /** How long each sounding door has been going, keyed by its bottom cell. */
    private static final Map<Long, Integer> ALARM_ELAPSED = new HashMap<>();

    /**
     * Redstone drives the door exactly like a vanilla iron door: power opens
     * it (which sounds the alarm, since opening always does), losing power
     * closes it. Anyone wiring it expects that; the earlier behaviour -
     * alarm without opening - read as broken. A hand-opened door is untouched
     * because POWERED only changes on a real edge. Either half's power counts,
     * so wiring can arrive at whatever height the build offers.
     */
    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
                               BlockPos sourcePos, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockPos lower = state.get(HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER ? pos : pos.down();
        BlockPos upper = lower.up();
        boolean receiving = world.isReceivingRedstonePower(lower) || world.isReceivingRedstonePower(upper);
        if (receiving == state.get(POWERED)) {
            return;
        }
        for (BlockPos cell : new BlockPos[]{lower, upper}) {
            BlockState half = world.getBlockState(cell);
            if (half.getBlock() == this) {
                world.setBlockState(cell, half.with(POWERED, receiving), Block.NOTIFY_LISTENERS);
            }
        }
        if (receiving != world.getBlockState(lower).get(OPEN)) {
            setOpen(world, lower, receiving);
        }
    }
}
