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
 *   <li>WALL — plates against the wall face: flush, held out on a short
 *       bracket arm ({@link #STANDOFF}), or turned 90° into a blade sign
 *       standing just off the wall ({@link #BLADE}), which is what you read
 *       from a train coming along the platform rather than from in front.</li>
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

    /**
     * How a wall unit carries its plates. Kept as two independent booleans
     * rather than one enum so markers placed before the blade mount existed
     * keep their {@code standoff} value instead of resetting to flush.
     */
    public enum Style {
        /** Plates flat against the wall. */
        FLUSH,
        /** Plates held out from the wall on a short arm, still facing out of it. */
        BRACKET,
        /** Plates turned 90° and held just off the wall, read along it from both sides. */
        BLADE;

        /** The style a state is actually in (the enum constants shadow the property names, hence the qualifiers). */
        public static Style of(BlockState state) {
            if (state.get(MOUNT) != Mount.WALL) {
                return FLUSH;
            }
            // Blade wins: it IS held off the wall, so standoff adds nothing.
            return state.get(StopMarkerBlock.BLADE) ? BLADE
                    : state.get(StopMarkerBlock.STANDOFF) ? BRACKET : FLUSH;
        }
    }

    public static final EnumProperty<Mount> MOUNT = EnumProperty.of("mount", Mount.class);
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** Wall units only: hold the plates out on a bracket instead of flat on the wall. */
    public static final BooleanProperty STANDOFF = BooleanProperty.of("standoff");
    /** Wall units only: turn the plates 90° into a blade sign read along the wall. */
    public static final BooleanProperty BLADE = BooleanProperty.of("blade");

    /** Plate geometry, in model pixels: 4x4 squares stacked along the pole. */
    public static final double PLATE_SIZE = 4.0;
    public static final double PLATE_FRONT_CEILING = 6.4;
    public static final double PLATE_FRONT_WALL_FLUSH = 15.4;
    public static final double PLATE_FRONT_WALL_BRACKET = 7.4;
    public static final double PLATE_DEPTH = 0.6;
    /** Blade plates straddle the block's centre line, so their faces sit either side of x 8. */
    public static final double PLATE_FRONT_BLADE = 8.0 - PLATE_DEPTH / 2.0;

    /**
     * The wall-side edge of a blade plate. The plate reaches from here inward,
     * landing centred on the block's middle — which is where every pole in the
     * mod runs, so a blade lines up with a horizontal pole beside it.
     */
    public static final double BLADE_NEAR = 10.0;

    /**
     * A blade's stack is centred on the block's HORIZONTAL centre line too, not
     * hung from the top of the block like a flush or bracket marker. That line
     * is where a pole runs, so the blade's stub becomes exactly a pole's own
     * cross-section (x/y 7..9) and a horizontal pole runs straight into it.
     * Centring also means the stack always fits: four plates fill the block
     * exactly instead of hanging out of the bottom.
     */
    public static final double BLADE_CENTRE = 8.0;

    /**
     * Outline shapes by [mount][style][signs - 1][horizontal facing];
     * collision by [mount] (the pole is symmetric, so it needs no rotation).
     */
    private final VoxelShape[][][][] outlines =
            new VoxelShape[Mount.values().length][Style.values().length][StopMarkerBlockEntity.MAX_SIGNS][];
    private final VoxelShape[] collisions = new VoxelShape[Mount.values().length];

    public StopMarkerBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(MOUNT, Mount.CEILING)
                .with(FACING, Direction.NORTH)
                .with(STANDOFF, false)
                .with(BLADE, false));
        VoxelShape pole = createCuboidShape(7.0, 0.0, 7.0, 9.0, 16.0, 9.0);
        // Both arms hold the TOP plate (the one plate that always exists), so a
        // single-plate marker's bracket has something to hold instead of hanging
        // in mid-air below it.
        VoxelShape bracket = createCuboidShape(7.0, 13.0, 7.9, 9.0, 15.0, 16.0);
        // Exactly a pole's gauge, at a pole's height: the two read as one run.
        VoxelShape bladeArm = createCuboidShape(7.0, 7.0, BLADE_NEAR - 0.1, 9.0, 9.0, 16.0);
        for (Mount mount : Mount.values()) {
            // Only the pole itself blocks movement; the plates are thin hardware.
            collisions[mount.ordinal()] = mount == Mount.WALL ? VoxelShapes.empty() : pole;
            for (Style style : Style.values()) {
                for (int signs = 1; signs <= StopMarkerBlockEntity.MAX_SIGNS; signs++) {
                    VoxelShape shape = plates(mount, style, signs);
                    if (mount != Mount.WALL) {
                        shape = VoxelShapes.union(shape, pole);
                    } else if (style == Style.BRACKET) {
                        shape = VoxelShapes.union(shape, bracket);
                    } else if (style == Style.BLADE) {
                        shape = VoxelShapes.union(shape, bladeArm);
                    }
                    outlines[mount.ordinal()][style.ordinal()][signs - 1] = rotations(shape.simplify());
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
    private static VoxelShape plates(Mount mount, Style style, int signs) {
        double height = PLATE_SIZE * signs;
        // Ceiling markers hang at the bottom of the block so poles stacked
        // above push them down; everything else sits at the top.
        double top = mount == Mount.CEILING ? height : 16.0;
        if (mount == Mount.WALL && style == Style.BLADE) {
            // Turned 90°: the plate's width runs out from the wall, its
            // thickness across the block's centre line, and the stack centred
            // on that line vertically as well.
            double bladeTop = BLADE_CENTRE + height / 2.0;
            return createCuboidShape(PLATE_FRONT_BLADE, bladeTop - height, BLADE_NEAR - PLATE_SIZE,
                    PLATE_FRONT_BLADE + PLATE_DEPTH, bladeTop, BLADE_NEAR);
        }
        double front = switch (mount) {
            case WALL -> style == Style.BRACKET ? PLATE_FRONT_WALL_BRACKET : PLATE_FRONT_WALL_FLUSH;
            default -> PLATE_FRONT_CEILING;
        };
        return createCuboidShape(6.0, top - height, front, 10.0, top, front + PLATE_DEPTH);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(MOUNT, FACING, STANDOFF, BLADE);
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
        return outlines[state.get(MOUNT).ordinal()][Style.of(state).ordinal()]
                [Math.min(signs, StopMarkerBlockEntity.MAX_SIGNS) - 1]
                [state.get(FACING).getHorizontal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collisions[state.get(MOUNT).ordinal()];
    }
}
