package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * The platform-end track gate: the red "Do not enter or cross tracks" plate
 * hinged to a square steel post, swung open when staff need through.
 *
 * <p>A real gate, not a painted panel: right-click swings the plate 90 degrees
 * about its hinge post, iron-door sounds and all. Closed it blocks the walkway;
 * open it is passable, and only the fixed hinge post keeps collision.</p>
 *
 * <p>It implements {@link GateSection} so it slots into a run of fare-control
 * ironwork the way the photos show — the bars either side treat it as part of
 * the run, and its own hinge post takes the place of the shared post at that
 * edge, keeping the one-post-per-joint rhythm. Which edge is chosen when the
 * gate is placed, from the half of the block that was clicked, so a pair of
 * gates can meet in the middle of an opening.</p>
 */
public class TrackWarningGateBlock extends Block implements GateSection {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** Which edge the hinge post stands on, picked at placement time. */
    public static final EnumProperty<DoorHinge> HINGE = Properties.DOOR_HINGE;

    /** The hinge post in the model's FACING=north frame (2 px wide, 6 deep). */
    private static final double POST_WIDTH = 2.0;
    private static final double POST_Z0 = 4.5, POST_Z1 = 10.5;

    private final VoxelShape[] closedShapes = new VoxelShape[Direction.values().length];
    private final VoxelShape[][] postShapes =
            new VoxelShape[Direction.values().length][DoorHinge.values().length];

    public TrackWarningGateBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(OPEN, false)
                .with(HINGE, DoorHinge.LEFT));
        for (Direction facing : Direction.values()) {
            boolean alongX = facing.getAxis() == Direction.Axis.X;
            closedShapes[facing.ordinal()] = alongX
                    ? createCuboidShape(6, 0, 0, 10, 16, 16)
                    : createCuboidShape(0, 0, 6, 16, 16, 10);
            // The post sits at model x 0..2 (left hinge) or 14..16 (right),
            // rotated into the world exactly the way the blockstate rotates
            // the model - anything else and the outline misses the post.
            postShapes[facing.ordinal()][DoorHinge.LEFT.ordinal()] =
                    rotated(facing, 0, 0, POST_Z0, POST_WIDTH, 16, POST_Z1);
            postShapes[facing.ordinal()][DoorHinge.RIGHT.ordinal()] =
                    rotated(facing, 16 - POST_WIDTH, 0, POST_Z0, 16, 16, POST_Z1);
        }
    }

    /** A model-frame box turned by the same y rotation the blockstate applies. */
    private static VoxelShape rotated(Direction facing, double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        double[] a = mapXZ(facing, x0, z0);
        double[] b = mapXZ(facing, x1, z1);
        return createCuboidShape(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]),
                                 Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    private static double[] mapXZ(Direction facing, double x, double z) {
        return switch (facing) {
            case EAST -> new double[]{16 - z, x};            // y = 90
            case SOUTH -> new double[]{16 - x, 16 - z};      // y = 180
            case WEST -> new double[]{z, 16 - x};            // y = 270
            default -> new double[]{x, z};
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Placed into an ironwork run, it adopts the run's facing.
        Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        for (Direction side : Direction.values()) {
            BlockState neighbour = context.getWorld().getBlockState(context.getBlockPos().offset(side));
            if (neighbour.getBlock() instanceof GateSection && neighbour.contains(FACING)) {
                facing = neighbour.get(FACING);
                break;
            }
        }
        return getDefaultState().with(FACING, facing).with(HINGE, hingeFor(facing, context));
    }

    /**
     * The post goes on the half of the block the player clicked, so which side
     * a gate hangs from is a placement decision rather than a second block.
     *
     * <p>The model's local +X is {@code facing.rotateYClockwise()} (the
     * blockstate turns the north-frame model by 0/90/180/270), so a click on
     * the negative side of that axis is the model's left edge.</p>
     */
    private static DoorHinge hingeFor(Direction facing, ItemPlacementContext context) {
        Direction right = facing.rotateYClockwise();
        Vec3d rel = context.getHitPos().subtract(Vec3d.ofCenter(context.getBlockPos()));
        double along = rel.x * right.getOffsetX() + rel.z * right.getOffsetZ();
        return along < 0 ? DoorHinge.LEFT : DoorHinge.RIGHT;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        boolean opening = !state.get(OPEN);
        world.setBlockState(pos, state.with(OPEN, opening), Block.NOTIFY_ALL);
        world.playSound(null, pos, opening
                        ? SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                SoundCategory.BLOCKS, 0.8f, 1.3f);
        return ActionResult.CONSUME;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN)
                ? postShapes[state.get(FACING).ordinal()][state.get(HINGE).ordinal()]
                : closedShapes[state.get(FACING).ordinal()];
    }

    /** Closed it bars the way; open, only the hinge post remains solid. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN)
                ? VoxelShapes.empty()
                : closedShapes[state.get(FACING).ordinal()];
    }
}
