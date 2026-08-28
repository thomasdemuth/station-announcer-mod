package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * NYC subway handrail: a 2x2 px steel tube. FOUR BLOCKS, one per mounting
 * STYLE (wall / standing / double / floating — Thomas's second-round
 * design, mirroring how MSD splits its railing family), each block's item
 * right-click-cycling through six VARIANTS: flat, the 45° stair run, the
 * stair-to-floor transitions at a run's bottom and top (on the double
 * style these cap the twin rails in a U), and both corner turns.
 *
 * <p>Flat/sloped rails run along FACING's axis, slopes ascending toward
 * FACING; corners enter from behind (against FACING) and turn left or
 * right. WALL's facing points INTO the wall for the flat/corner variants
 * (clicked wall face, or the nearest solid wall when clicking a floor);
 * its sloped variants use FACING = ascent and MIRROR for which side the
 * rail and brackets sit — the side that actually has a wall, else the
 * side of the block that was clicked.</p>
 *
 * <p>ALIGN puts the rail at the block's left or right edge instead of the
 * centre — a stair-lane rail belongs at the side of the lane, not down
 * its middle. It is picked from where in the block you click (left third
 * / middle / right third across the run) on the floating and standing
 * styles' non-corner variants; wall and double rails have their side
 * decided by geometry already and stay centre.</p>
 */
public class HandrailBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Variant> VARIANT = EnumProperty.of("variant", Variant.class);
    /** Mirrors the wall style's sloped-variant rail + brackets to the other side. */
    public static final BooleanProperty MIRROR = BooleanProperty.of("mirror");
    /** Which side of the block the rail sits on, viewed along the ascent. */
    public static final EnumProperty<Alignment> ALIGN = EnumProperty.of("align", Alignment.class);

    public enum Style {
        WALL, STANDING, DOUBLE, FLOATING
    }

    public enum Alignment implements StringIdentifiable {
        LEFT("left"), CENTER("center"), RIGHT("right");

        private final String name;

        Alignment(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public enum Variant implements StringIdentifiable {
        FLAT("flat"), SLOPE("slope"), SLOPE_BOTTOM("slope_bottom"),
        SLOPE_TOP("slope_top"), CORNER_LEFT("corner_left"), CORNER_RIGHT("corner_right");

        private final String name;

        Variant(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }

        public boolean sloped() {
            return this == SLOPE || this == SLOPE_BOTTOM || this == SLOPE_TOP;
        }

        public boolean corner() {
            return this == CORNER_LEFT || this == CORNER_RIGHT;
        }

        /** Cycle order for the item; wraps. */
        public Variant next() {
            Variant[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** The left/right models shift the rail by this many model px off centre. */
    public static final double ALIGN_SHIFT = 5.5;

    private final Style style;
    private final VoxelShape[] alongShapes;
    private final VoxelShape[] alongLeft;
    private final VoxelShape[] alongRight;
    private final VoxelShape[] doubleShapes;
    private final VoxelShape[] wallShapes;
    private final VoxelShape[] wallSlope;
    private final VoxelShape[] wallSlopeMirror;
    private final VoxelShape[] cornerLeft;
    private final VoxelShape[] cornerRight;

    public HandrailBlock(Settings settings, Style style) {
        super(settings);
        this.style = style;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(VARIANT, Variant.FLAT).with(MIRROR, false).with(ALIGN, Alignment.CENTER));
        this.alongShapes = FacingDecorBlock.rotations(createCuboidShape(6.5, 0, 0, 9.5, 16, 16));
        this.alongLeft = FacingDecorBlock.rotations(
                createCuboidShape(6.5 - ALIGN_SHIFT, 0, 0, 9.5 - ALIGN_SHIFT, 16, 16));
        this.alongRight = FacingDecorBlock.rotations(
                createCuboidShape(6.5 + ALIGN_SHIFT, 0, 0, 9.5 + ALIGN_SHIFT, 16, 16));
        this.doubleShapes = FacingDecorBlock.rotations(createCuboidShape(2.5, 0, 0, 13.5, 16, 16));
        this.wallShapes = FacingDecorBlock.rotations(createCuboidShape(0, 0, 0.5, 16, 16, 5.5));
        // Sloped wall rails hug the east side (mirror: west) — the outline
        // matches the rail at x 12.5..14.5 plus its brackets reaching x16.
        this.wallSlope = FacingDecorBlock.rotations(createCuboidShape(12, 0, 0, 16, 16, 16));
        this.wallSlopeMirror = FacingDecorBlock.rotations(createCuboidShape(0, 0, 0, 4, 16, 16));
        VoxelShape left = VoxelShapes.union(
                createCuboidShape(6.5, 0, 6.5, 9.5, 16, 16),
                createCuboidShape(0, 0, 6.5, 9.5, 16, 9.5)).simplify();
        VoxelShape right = VoxelShapes.union(
                createCuboidShape(6.5, 0, 6.5, 9.5, 16, 16),
                createCuboidShape(6.5, 0, 6.5, 16, 16, 9.5)).simplify();
        this.cornerLeft = FacingDecorBlock.rotations(left);
        this.cornerRight = FacingDecorBlock.rotations(right);
    }

    public Style style() {
        return style;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, VARIANT, MIRROR, ALIGN);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Variant variant = state.get(VARIANT);
        VoxelShape[] shapes = switch (variant) {
            case CORNER_LEFT -> cornerLeft;
            case CORNER_RIGHT -> cornerRight;
            default -> {
                if (style == Style.WALL) {
                    yield variant.sloped()
                            ? (state.get(MIRROR) ? wallSlopeMirror : wallSlope)
                            : wallShapes;
                }
                if (style == Style.DOUBLE) {
                    yield doubleShapes;
                }
                yield switch (state.get(ALIGN)) {
                    case LEFT -> alongLeft;
                    case RIGHT -> alongRight;
                    case CENTER -> alongShapes;
                };
            }
        };
        return shapes[state.get(FACING).getHorizontal()];
    }

    /** Where the click landed across the run: −0.5 (left edge) .. +0.5 (right edge). */
    private static double clickAcross(ItemPlacementContext context, Direction right) {
        Vec3d offset = context.getHitPos()
                .subtract(Vec3d.ofCenter(context.getBlockPos()));
        return offset.getX() * right.getOffsetX() + offset.getZ() * right.getOffsetZ();
    }

    /** Is the neighbour toward {@code side} presenting a solid wall face back at us? */
    private static boolean solidWall(World world, BlockPos pos, Direction side) {
        BlockPos neighbour = pos.offset(side);
        return world.getBlockState(neighbour).isSideSolidFullSquare(world, neighbour, side.getOpposite());
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Variant variant = com.stationannouncer.item.HandrailItem.selectedVariant(context.getStack());
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        Direction facing;
        boolean mirror = false;
        Alignment align = Alignment.CENTER;
        if (style == Style.WALL && !variant.sloped()) {
            if (context.getSide().getAxis().isHorizontal()) {
                facing = context.getSide().getOpposite(); // clicked a wall: rail onto it
            } else {
                // Clicked a floor/ceiling: hug whichever neighbour actually is
                // a wall, trying the way the player looks first, then beside,
                // then behind — a rail on thin air helps nobody.
                Direction look = context.getHorizontalPlayerFacing();
                facing = look;
                for (Direction side : new Direction[]{look, look.rotateYClockwise(),
                        look.rotateYCounterclockwise(), look.getOpposite()}) {
                    if (solidWall(world, pos, side)) {
                        facing = side;
                        break;
                    }
                }
            }
        } else {
            facing = context.getHorizontalPlayerFacing();
            Direction right = facing.rotateYClockwise();
            if (style == Style.WALL) {
                // Sloped wall rail: sit on the side that actually has a wall.
                // Both or neither solid → the side of the block that was
                // clicked (the old guess used the click's side INVERTED, which
                // is why rails kept landing opposite the wall).
                boolean wallRight = solidWall(world, pos, right);
                boolean wallLeft = solidWall(world, pos, right.getOpposite());
                mirror = wallRight != wallLeft ? wallLeft : clickAcross(context, right) < 0;
            } else if (style != Style.DOUBLE && !variant.corner()) {
                // Rail sits on the third of the block you clicked.
                double across = clickAcross(context, right);
                align = across > 1 / 6.0 ? Alignment.RIGHT
                        : across < -1 / 6.0 ? Alignment.LEFT : Alignment.CENTER;
            }
        }
        return getDefaultState().with(VARIANT, variant).with(FACING, facing)
                .with(MIRROR, mirror).with(ALIGN, align);
    }
}
