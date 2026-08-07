package com.stationannouncer.mtr;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Ceiling conduit, the lifeblood of any proper subway station. Each block
 * holds 1–4 parallel pipes (keep clicking to add more, sea-pickle style) in
 * one of three sizes and five paint colors. A fresh pipe placed against an
 * existing run inherits its size and color; an isolated one rolls them
 * randomly. Pipes connect to matching neighbors in all six directions —
 * corners and vertical drops form coupling boxes automatically, and open
 * ends finish in a collar cap.
 *
 * <p>Right-click with the MTR brush to repaint a whole connected segment
 * (sneak-brush changes the size instead).
 */
public class PipeBlock extends Block {
    public enum PipeColor implements StringIdentifiable {
        OFF_WHITE("off_white", 0xE8E4DC),
        BLACK("black", 0x232326),
        GRAY("gray", 0x96989B),
        RED("red", 0xAA2823),
        BLUE("blue", 0x284696);

        public final String id;
        public final int rgb;

        PipeColor(String id, int rgb) {
            this.id = id;
            this.rgb = rgb;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final IntProperty SIZE = IntProperty.of("size", 1, 3);
    public static final IntProperty PIPES = IntProperty.of("pipes", 1, 4);
    public static final EnumProperty<PipeColor> COLOR = EnumProperty.of("color", PipeColor.class);
    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty WEST = Properties.WEST;
    public static final BooleanProperty UP = Properties.UP;
    public static final BooleanProperty DOWN = Properties.DOWN;

    /** Pipe bank diameter per size, for the outline slab. */
    private static final double[] DIAMETER = {2.0, 3.0, 4.0};

    private final VoxelShape[] outlines = new VoxelShape[3];

    public PipeBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(SIZE, 2).with(PIPES, 1).with(COLOR, PipeColor.OFF_WHITE)
                .with(NORTH, false).with(EAST, false).with(SOUTH, false)
                .with(WEST, false).with(UP, false).with(DOWN, false));
        for (int size = 1; size <= 3; size++) {
            outlines[size - 1] = Block.createCuboidShape(0.0, 14.0 - DIAMETER[size - 1], 0.0, 16.0, 16.0, 16.0);
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(SIZE, PIPES, COLOR, NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    // ------------------------------------------------------------ placement

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        // Keep clicking with the pipe item to pack more pipes into the block.
        return context.getStack().isOf(asItem()) && state.get(PIPES) < 4 && !context.shouldCancelInteraction();
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState existing = world.getBlockState(pos);
        if (existing.isOf(this)) {
            return existing.with(PIPES, Math.min(4, existing.get(PIPES) + 1));
        }
        // Continue an adjacent run's look, or roll a fresh random pipe.
        int size = 1 + world.getRandom().nextInt(3);
        PipeColor color = PipeColor.values()[world.getRandom().nextInt(PipeColor.values().length)];
        for (Direction direction : Direction.values()) {
            BlockState neighbor = world.getBlockState(pos.offset(direction));
            if (neighbor.getBlock() instanceof PipeBlock) {
                size = neighbor.get(SIZE);
                color = neighbor.get(COLOR);
                break;
            }
        }
        BlockState state = getDefaultState().with(SIZE, size).with(COLOR, color);
        return withConnections(state, world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withConnections(state, world, pos);
    }

    private static BlockState withConnections(BlockState state, WorldAccess world, BlockPos pos) {
        return state
                .with(NORTH, connects(state, world.getBlockState(pos.north())))
                .with(EAST, connects(state, world.getBlockState(pos.east())))
                .with(SOUTH, connects(state, world.getBlockState(pos.south())))
                .with(WEST, connects(state, world.getBlockState(pos.west())))
                .with(UP, connects(state, world.getBlockState(pos.up())))
                .with(DOWN, connects(state, world.getBlockState(pos.down())));
    }

    /** Pipes join only within a segment: same size and same color. */
    private static boolean connects(BlockState self, BlockState neighbor) {
        return neighbor.getBlock() instanceof PipeBlock
                && neighbor.get(SIZE) == self.get(SIZE)
                && neighbor.get(COLOR) == self.get(COLOR);
    }

    // ------------------------------------------------------------ brush edit

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        // Repaint (or resize, when sneaking) the whole connected segment.
        int newSize = state.get(SIZE);
        PipeColor newColor = state.get(COLOR);
        if (player.isSneaking()) {
            newSize = state.get(SIZE) % 3 + 1;
        } else {
            newColor = PipeColor.values()[(state.get(COLOR).ordinal() + 1) % PipeColor.values().length];
        }
        int oldSize = state.get(SIZE);
        PipeColor oldColor = state.get(COLOR);
        Set<BlockPos> segment = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos);
        segment.add(pos);
        while (!queue.isEmpty() && segment.size() < 128) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.offset(direction);
                if (segment.contains(next)) {
                    continue;
                }
                BlockState nextState = world.getBlockState(next);
                if (nextState.getBlock() instanceof PipeBlock
                        && nextState.get(SIZE) == oldSize && nextState.get(COLOR) == oldColor) {
                    segment.add(next);
                    queue.add(next);
                }
            }
        }
        for (BlockPos member : segment) {
            BlockState memberState = world.getBlockState(member);
            world.setBlockState(member, memberState.with(SIZE, newSize).with(COLOR, newColor), Block.NOTIFY_ALL);
        }
        return ActionResult.CONSUME;
    }

    // ------------------------------------------------------------- shapes

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines[state.get(SIZE) - 1];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty(); // ceiling conduit never blocks movement
    }
}
