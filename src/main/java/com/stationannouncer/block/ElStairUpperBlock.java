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
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The walk-through cell ABOVE a stair's treads that carries the stair's
 * in-cell walls ({@link SubwayStairBlock#LEFT} / RIGHT) on up to the ceiling:
 * plain vertical wall cells on the left and / or right edge of the flight,
 * nothing in between. Stack them until the soffit is reached - with the
 * stair's {@code wall_fill} underneath that makes the triangle wall of a stair
 * rising through a slab. FACING is the ascent (copied from the column below);
 * {@link #POST} follows the family's every-other-cell rhythm; {@link #HEAD}
 * marks the column over the top stair of the flight, which closes with a post
 * at its uphill edge. Assets: tools/gen_el2_stairs.py (upper_assets).
 */
public class ElStairUpperBlock extends Block {
    public enum Kind implements StringIdentifiable {
        NONE("none"), WALL("wall"), GLASS("glass");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Kind> LEFT = EnumProperty.of("left", Kind.class);
    public static final EnumProperty<Kind> RIGHT = EnumProperty.of("right", Kind.class);
    public static final BooleanProperty POST = BooleanProperty.of("post");
    public static final BooleanProperty HEAD = BooleanProperty.of("head");

    /** [left][right] -> per-facing shapes. */
    private final VoxelShape[][][] shapes = new VoxelShape[2][2][];

    public ElStairUpperBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(LEFT, Kind.NONE)
                .with(RIGHT, Kind.NONE).with(POST, true).with(HEAD, false));
        VoxelShape leftPanel = createCuboidShape(0, 0, 0, 2.4, 16, 16);
        VoxelShape rightPanel = createCuboidShape(13.6, 0, 0, 16, 16, 16);
        shapes[0][0] = FacingDecorBlock.rotations(VoxelShapes.union(leftPanel, rightPanel));   // never drawn; keeps it breakable
        shapes[1][0] = FacingDecorBlock.rotations(leftPanel);
        shapes[0][1] = FacingDecorBlock.rotations(rightPanel);
        shapes[1][1] = FacingDecorBlock.rotations(VoxelShapes.union(leftPanel, rightPanel));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT, POST, HEAD);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(LEFT) != Kind.NONE ? 1 : 0][state.get(RIGHT) != Kind.NONE ? 1 : 0]
                [state.get(FACING).getHorizontal()];
    }

    /** The stair this column stands over (through any upper cells below), or null. */
    @Nullable
    private static BlockState stairBelow(BlockView world, BlockPos pos) {
        for (int i = 1; i <= 8; i++) {
            BlockState state = world.getBlockState(pos.down(i));
            if (state.getBlock() instanceof SubwayStairBlock) {
                return state;
            }
            if (!(state.getBlock() instanceof ElStairUpperBlock)) {
                return null;
            }
        }
        return null;
    }

    public BlockState compute(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);
        BlockState stair = stairBelow(world, pos);
        if (stair != null) {
            facing = stair.get(SubwayStairBlock.FACING);
        }
        return state.with(FACING, facing).with(POST, StairFamily.postCell(pos, facing))
                .with(HEAD, stair != null && stair.get(SubwayStairBlock.TOP));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction facing = StairFamily.placementAscent(context);
        Kind kind = com.stationannouncer.item.ElStairUpperItem.selectedKind(context.getStack());
        // the half of the cell that was clicked decides the edge; dead centre = both
        Direction right = facing.rotateYClockwise();
        Vec3d rel = context.getHitPos().subtract(Vec3d.ofCenter(context.getBlockPos()));
        double lateral = rel.x * right.getOffsetX() + rel.z * right.getOffsetZ();
        BlockState state = getDefaultState().with(FACING, facing)
                .with(LEFT, lateral < 0.12 ? kind : Kind.NONE)
                .with(RIGHT, lateral > -0.12 ? kind : Kind.NONE);
        return compute(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN ? compute(state, world, pos) : state;
    }
}
