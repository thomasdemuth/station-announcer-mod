package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
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
import org.jetbrains.annotations.Nullable;

/**
 * A car-stop marker: the little enamel plates that tell an operator where to
 * stop a train of a given length ("6", "8", "10"), plus the yellow OPTO
 * boards that go with them. One to four plates stack downward from the
 * mounting point; each plate's colour and legend live in the block entity and
 * are edited with the MTR brush.
 *
 * <p>{@link #MOUNT} decides where the marker hangs from, which is what the
 * pole does:
 * <ul>
 *   <li>CEILING — a drop pole down the whole block with the plates at its
 *       bottom end, so stacking {@code stop_marker_pole} above lowers it;</li>
 *   <li>FLOOR — the same pole standing up with the plates on top;</li>
 *   <li>WALL — plates against the wall face, either flush ({@link #STANDOFF}
 *       false) or held out on a short bracket arm.</li>
 * </ul>
 */
public class StopMarkerBlock extends Block implements BlockEntityProvider {
    public enum Mount implements StringIdentifiable {
        CEILING("ceiling"),
        WALL("wall"),
        FLOOR("floor");

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
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** Wall units only: hold the plates out on a bracket instead of flat on the wall. */
    public static final BooleanProperty STANDOFF = BooleanProperty.of("standoff");

    /** Plate geometry, in model pixels: 4x4 squares stacked along the pole. */
    public static final double PLATE_SIZE = 4.0;
    public static final double PLATE_FRONT_CEILING = 6.4;
    public static final double PLATE_FRONT_WALL_FLUSH = 15.4;
    public static final double PLATE_FRONT_WALL_BRACKET = 7.4;
    public static final double PLATE_DEPTH = 0.6;

    /**
     * Outline shapes by [mount][standoff][signs - 1][horizontal facing];
     * collision by [mount] (the pole is symmetric, so it needs no rotation).
     */
    private final VoxelShape[][][][] outlines =
            new VoxelShape[Mount.values().length][2][StopMarkerBlockEntity.MAX_SIGNS][];
    private final VoxelShape[] collisions = new VoxelShape[Mount.values().length];

    public StopMarkerBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(MOUNT, Mount.CEILING)
                .with(FACING, Direction.NORTH)
                .with(STANDOFF, false));
        VoxelShape pole = createCuboidShape(7.0, 0.0, 7.0, 9.0, 16.0, 9.0);
        VoxelShape bracket = createCuboidShape(7.0, 7.0, 7.0, 9.0, 9.0, 16.0);
        for (Mount mount : Mount.values()) {
            // Only the pole itself blocks movement; the plates are thin hardware.
            collisions[mount.ordinal()] = mount == Mount.WALL ? VoxelShapes.empty() : pole;
            for (int standoff = 0; standoff <= 1; standoff++) {
                for (int signs = 1; signs <= StopMarkerBlockEntity.MAX_SIGNS; signs++) {
                    VoxelShape shape = plates(mount, standoff == 1, signs);
                    if (mount != Mount.WALL) {
                        shape = VoxelShapes.union(shape, pole);
                    } else if (standoff == 1) {
                        shape = VoxelShapes.union(shape, bracket);
                    }
                    outlines[mount.ordinal()][standoff][signs - 1] = rotations(shape.simplify());
                }
            }
        }
    }

    /** The four horizontal rotations of a north-facing shape, indexed by {@code Direction.getHorizontal()}. */
    private static VoxelShape[] rotations(VoxelShape north) {
        VoxelShape east = com.stationannouncer.block.FacingDecorBlock.rotateClockwise(north);
        VoxelShape south = com.stationannouncer.block.FacingDecorBlock.rotateClockwise(east);
        VoxelShape west = com.stationannouncer.block.FacingDecorBlock.rotateClockwise(south);
        // Direction.getHorizontal(): 0=south, 1=west, 2=north, 3=east
        return new VoxelShape[]{south, west, north, east};
    }

    /** The box the plate stack occupies, in the north-facing model frame. */
    private static VoxelShape plates(Mount mount, boolean standoff, int signs) {
        double height = PLATE_SIZE * signs;
        // Ceiling markers hang at the bottom of the block so poles stacked
        // above push them down; everything else sits at the top.
        double top = mount == Mount.CEILING ? height : 16.0;
        double front = switch (mount) {
            case WALL -> standoff ? PLATE_FRONT_WALL_BRACKET : PLATE_FRONT_WALL_FLUSH;
            default -> PLATE_FRONT_CEILING;
        };
        return createCuboidShape(6.0, top - height, front, 10.0, top, front + PLATE_DEPTH);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(MOUNT, FACING, STANDOFF);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StopMarkerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        if (side.getAxis().isVertical()) {
            // Clicked a ceiling or a floor: the plates read toward the player.
            return getDefaultState()
                    .with(MOUNT, side == Direction.DOWN ? Mount.CEILING : Mount.FLOOR)
                    .with(FACING, context.getHorizontalPlayerFacing().getOpposite());
        }
        // Clicked a wall: the plates face out of it.
        return getDefaultState().with(MOUNT, Mount.WALL).with(FACING, side);
    }

    /** The MTR brush opens the plate editor; everything else passes through. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof StopMarkerBlockEntity marker) {
                StationAnnouncer.GUI_OPENER.accept(marker);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // The stack length lives in the block entity; the shape cache probes
        // this with an empty view, which just yields the single-plate shape.
        int signs = world.getBlockEntity(pos) instanceof StopMarkerBlockEntity marker
                ? marker.getSigns().size() : 1;
        return outlines[state.get(MOUNT).ordinal()][state.get(STANDOFF) ? 1 : 0]
                [Math.min(signs, StopMarkerBlockEntity.MAX_SIGNS) - 1]
                [state.get(FACING).getHorizontal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collisions[state.get(MOUNT).ordinal()];
    }
}
