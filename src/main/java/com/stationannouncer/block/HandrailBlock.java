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
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
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
 * right. WALL's facing points INTO the wall for the flat/corner variants;
 * its sloped variants use FACING = ascent and MIRROR for which side the
 * brackets reach (picked from where the player stands).</p>
 */
public class HandrailBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Variant> VARIANT = EnumProperty.of("variant", Variant.class);
    /** Mirrors the wall style's sloped-variant brackets to the other side. */
    public static final BooleanProperty MIRROR = BooleanProperty.of("mirror");

    public enum Style {
        WALL, STANDING, DOUBLE, FLOATING
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

        /** Cycle order for the item; wraps. */
        public Variant next() {
            Variant[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final Style style;
    private final VoxelShape[] alongShapes;
    private final VoxelShape[] wallShapes;
    private final VoxelShape[] cornerLeft;
    private final VoxelShape[] cornerRight;

    public HandrailBlock(Settings settings, Style style) {
        super(settings);
        this.style = style;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(VARIANT, Variant.FLAT).with(MIRROR, false));
        this.alongShapes = FacingDecorBlock.rotations(createCuboidShape(6.5, 0, 0, 9.5, 16, 16));
        this.wallShapes = FacingDecorBlock.rotations(createCuboidShape(0, 0, 0.5, 16, 16, 5.5));
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
        builder.add(FACING, VARIANT, MIRROR);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Variant variant = state.get(VARIANT);
        VoxelShape[] shapes = switch (variant) {
            case CORNER_LEFT -> cornerLeft;
            case CORNER_RIGHT -> cornerRight;
            default -> style == Style.WALL && !variant.sloped() ? wallShapes : alongShapes;
        };
        return shapes[state.get(FACING).getHorizontal()];
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Variant variant = com.stationannouncer.item.HandrailItem.selectedVariant(context.getStack());
        Direction facing;
        boolean mirror = false;
        if (style == Style.WALL && !variant.sloped()
                && context.getSide().getAxis().isHorizontal()) {
            facing = context.getSide().getOpposite(); // clicked a wall: rail onto it
        } else {
            facing = context.getHorizontalPlayerFacing();
        }
        if (style == Style.WALL && variant.sloped()) {
            // Brackets reach toward the wall beside the run — away from where
            // the player stands: mirror when they stand on the ascent's right.
            Direction right = facing.rotateYClockwise();
            net.minecraft.util.math.Vec3d offset = context.getHitPos()
                    .subtract(net.minecraft.util.math.Vec3d.ofCenter(context.getBlockPos()));
            double along = offset.getX() * right.getOffsetX() + offset.getZ() * right.getOffsetZ();
            mirror = along > 0;
        }
        return getDefaultState().with(VARIANT, variant).with(FACING, facing).with(MIRROR, mirror);
    }
}
