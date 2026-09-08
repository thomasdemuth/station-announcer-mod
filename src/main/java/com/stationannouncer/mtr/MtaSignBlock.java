package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The MTA-style sign panel: a black plate, full block height or half, mounted
 * on a wall, hung from a ceiling or standing on a rail, whose face is painted
 * by {@code StationDecorRenderer.paintMtaSign} from the {@code SignFaces} on the
 * shared {@link StationDecorBlockEntity}. Right-click (empty hand or the MTR
 * brush) opens the sign editor.
 *
 * <p>Adjacent panels with the same facing, mount and size MERGE into one sign
 * across the run (LEFT/RIGHT, computed like the entrance sign): the run's first
 * cell draws the whole panel, and the first cell carrying a sign supplies the
 * content. Half signs show hanger rods / posts only at run ends.</p>
 *
 * <p>Geometry (the plate table in {@code tools/gen_sign_assets.py}) is a
 * three-way contract between generator, this class's shapes and the renderer.</p>
 */
public class MtaSignBlock extends FacingDecorBlock implements BlockEntityProvider {
    public enum Mount implements StringIdentifiable {
        WALL("wall"),
        HANGING("hanging"),
        STANDING("standing");

        private final String id;

        Mount(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final EnumProperty<Mount> MOUNT = EnumProperty.of("mount", Mount.class);
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    /** Full block height, or the half-height (8 px) panel. */
    public final boolean full;

    private final VoxelShape[] wall;
    private final VoxelShape[] hanging;
    private final VoxelShape[] standing;

    public MtaSignBlock(Settings settings, boolean full) {
        super(settings, createCuboidShape(0, full ? 0 : 4, 7, 16, full ? 16 : 12, 9));
        this.full = full;
        setDefaultState(getDefaultState().with(MOUNT, Mount.WALL).with(LEFT, false).with(RIGHT, false));
        wall = rotations(createCuboidShape(0, full ? 0 : 4, 15, 16, full ? 16 : 12, 16));
        hanging = rotations(full
                ? createCuboidShape(0, 0, 7, 16, 16, 9)
                : createCuboidShape(0, 4, 7, 16, 16, 9));
        standing = rotations(full
                ? createCuboidShape(0, 0, 7, 16, 16, 9)
                : createCuboidShape(0, 0, 7, 16, 16, 9));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MOUNT, LEFT, RIGHT);
    }

    // ------------------------------------------------------------- plate

    /** Plate top in px from the block floor (the canvas hangs from here). */
    public float plateTop(BlockState state) {
        Mount mount = state.get(MOUNT);
        if (full) {
            return 16;
        }
        return mount == Mount.STANDING ? 16 : 12;
    }

    /** Plate height in px: 16 or 8. */
    public float plateHeight() {
        return full ? 16 : 8;
    }

    /** Model z of the plate's front face (viewer on -z). */
    public static float plateFront(BlockState state) {
        return state.get(MOUNT) == Mount.WALL ? 15 : 7;
    }

    /** Model z of the back face for double-sided mounts; wall signs are one-sided. */
    public static float plateBack(BlockState state) {
        return state.get(MOUNT) == Mount.WALL ? -1 : 9;
    }

    public static boolean doubleSided(BlockState state) {
        return state.get(MOUNT) != Mount.WALL;
    }

    // ---------------------------------------------------------- merging

    private boolean joins(WorldAccess world, BlockPos pos, BlockState self) {
        BlockState other = world.getBlockState(pos);
        return other.getBlock() == this && other.get(FACING) == self.get(FACING)
                && other.get(MOUNT) == self.get(MOUNT);
    }

    private BlockState withJoins(BlockState state, WorldAccess world, BlockPos pos) {
        Direction facing = state.get(FACING);
        // model +x is facing.rotateYClockwise(); LEFT is the -x neighbour
        return state.with(RIGHT, joins(world, pos.offset(facing.rotateYClockwise()), state))
                .with(LEFT, joins(world, pos.offset(facing.rotateYCounterclockwise()), state));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        BlockState state;
        if (side.getAxis().isHorizontal()) {
            // clicked a wall: flat on it, reading out of the wall
            state = getDefaultState().with(MOUNT, Mount.WALL).with(FACING, side);
        } else {
            state = getDefaultState()
                    .with(MOUNT, side == Direction.DOWN ? Mount.HANGING : Mount.STANDING)
                    .with(FACING, context.getHorizontalPlayerFacing().getOpposite());
        }
        return withJoins(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return withJoins(state, world, pos);
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = withJoins(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    // ------------------------------------------------------------ shapes

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape[] shapes = switch (state.get(MOUNT)) {
            case WALL -> wall;
            case HANGING -> hanging;
            default -> standing;
        };
        return shapes[state.get(FACING).getHorizontal()];
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
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
