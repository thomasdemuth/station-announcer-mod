package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handrails and railings for the 1:12 {@link MaterialRampBlock}: four MTA
 * stainless styles (standing on posts with a guard rail, wall brackets, the
 * twin-rail centre divider, floating) and two Station Decoration styles
 * (glass panel on a base shoe, metal pickets). A slope of 4.76° is beyond
 * JSON models, so like the ramp itself the geometry is computed client-side
 * ({@code client.material.RampRailGeometry}).
 *
 * <p><b>The rail follows the ramp.</b> {@link #SEG} is the ramp segment the
 * rail measures from and {@link #MODE} where that ramp is: ON = directly
 * below (the rail stands in the lane, at its edge — "in the ramp's cell"),
 * BESIDE = the next cell over (the rail hugs the shared edge at the ramp's
 * height), FLOOR = no ramp (a level rail on the floor: the run-up before a
 * ramp, a landing made of ordinary blocks). FACING is the ramp's uphill
 * direction (look direction when there is no ramp). Rail centre = ramp
 * surface + {@link #RAIL_HEIGHT}, the subway handrails' height, so ramp and
 * stair rails line up.</p>
 *
 * <p>{@link #SHAPE} comes from the item's MSD-style cycle (right-click the
 * air). START is the level ADA extension at the bottom of a run (the ramp is
 * ahead, uphill), END the one at the top (the ramp is behind); both finish in
 * a post / return. The corners turn on a level landing: OUTER runs along the
 * rail's own edge and turns across the front of the cell (around the outside
 * of a landing), INNER turns out through the side edge (the inside of a
 * turn). {@link #RIGHT} is the side of the lane the rail is on, viewed
 * uphill — from where you click, toward the ramp when BESIDE, toward a solid
 * wall for the wall style; the double rail is always centred.</p>
 */
public class RampRailBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Shape> SHAPE = EnumProperty.of("shape", Shape.class);
    public static final IntProperty SEG = IntProperty.of("seg", 0, MaterialRampBlock.RUN);
    public static final EnumProperty<Mode> MODE = EnumProperty.of("mode", Mode.class);
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");

    /** Rail centre above the walking surface, px (the subway handrails' 14.5). */
    public static final float RAIL_HEIGHT = 14.5f;
    /** Lateral offset of an edge rail's centre line from its edge, px. */
    public static final float EDGE = 2.5f;
    /** The ADA level extension past each end of a run, px (≈ 12 in.). */
    public static final float EXTENSION = 6f;

    public enum Style {
        STANDING, WALL, DOUBLE, FLOATING, GLASS, PICKETS
    }

    public enum Shape implements StringIdentifiable {
        FLAT("flat"), START("start"), SLOPE("slope"), END("end"), CORNER_OUTER("corner_outer"), CORNER_INNER("corner_inner");

        private final String name;

        Shape(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }

        public Shape next() {
            Shape[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean corner() {
            return this == CORNER_OUTER || this == CORNER_INNER;
        }
    }

    public enum Mode implements StringIdentifiable {
        FLOOR("floor"), ON("on"), BESIDE("beside");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    private final Style style;
    private final Map<BlockState, VoxelShape> outlines = new ConcurrentHashMap<>();
    private final Map<BlockState, VoxelShape> collisions = new ConcurrentHashMap<>();

    public RampRailBlock(Settings settings, Style style) {
        super(settings);
        this.style = style;
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(SHAPE, Shape.FLAT)
                .with(SEG, 0).with(MODE, Mode.FLOOR).with(RIGHT, false));
    }

    public Style style() {
        return style;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE, SEG, MODE, RIGHT);
    }

    // ------------------------------------------------------------ heights (shared with the client geometry)

    /** Walking-surface height under the rail at north-frame z (16 = downhill end), in this cell's px. */
    public static float surface(BlockState state, float z) {
        int seg = state.get(SEG);
        float base = state.get(MODE) == Mode.ON ? -16f : 0f;
        if (state.get(SHAPE) != Shape.SLOPE || seg >= MaterialRampBlock.RUN) {
            return base + MaterialRampBlock.lowPx(seg);
        }
        float low = MaterialRampBlock.lowPx(seg);
        float high = MaterialRampBlock.highPx(seg);
        return base + low + (high - low) * (16f - z) / 16f;
    }

    /** Where posts and panels stand: the ramp surface, or this cell's floor when BESIDE the ramp. */
    public static float bottom(BlockState state, float z) {
        return state.get(MODE) == Mode.BESIDE ? 0f : surface(state, z);
    }

    // ------------------------------------------------------------ shapes

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines.computeIfAbsent(state, s -> buildShape(s, 16.5f));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // fence height above the surface, so nobody hops the rail
        return collisions.computeIfAbsent(state, s -> buildShape(s, 24f));
    }

    /** Strips along the rail line(s), from the post foot to {@code above} over the surface. */
    private VoxelShape buildShape(BlockState state, float above) {
        float x0, x1;
        if (style == Style.DOUBLE) {
            x0 = 2.5f;
            x1 = 13.5f;
        } else if (style == Style.WALL) {
            x0 = 0f;
            x1 = EDGE + 1.7f;
        } else {
            x0 = EDGE - 1.7f;
            x1 = EDGE + 1.7f;
        }
        Shape shape = state.get(SHAPE);
        VoxelShape out = VoxelShapes.empty();
        switch (shape) {
            case FLAT, SLOPE -> out = strips(state, x0, x1, 0, 16, above);
            case START -> out = strips(state, x0, x1, 0, EXTENSION + 1.5f, above);
            case END -> out = strips(state, x0, x1, 16 - EXTENSION - 1.5f, 16, above);
            case CORNER_OUTER, CORNER_INNER -> {
                // level: one leg along the rail's edge, one across the cell
                float lo = bottom(state, 8), hi = clampTop(surface(state, 8) + above);
                boolean outer = shape == Shape.CORNER_OUTER;
                if (style == Style.DOUBLE) {
                    // two concentric Ls: cover the band they sweep
                    out = VoxelShapes.union(box(state, 2.5f, lo, 2.5f, 13.5f, hi, 16),
                            outer ? box(state, 2.5f, lo, 2.5f, 16, hi, 13.5f)
                                    : box(state, 0, lo, 2.5f, 13.5f, hi, 13.5f));
                } else {
                    float cz = outer ? EDGE : 16 - EDGE;
                    out = VoxelShapes.union(box(state, x0, lo, cz - 1.7f, x1, hi, 16),
                            outer ? box(state, x0, lo, cz - 1.7f, 16, hi, cz + 1.7f)
                                    : box(state, 0, lo, cz - 1.7f, x1, hi, cz + 1.7f));
                }
            }
        }
        return out.simplify();
    }

    private VoxelShape strips(BlockState state, float x0, float x1, float z0, float z1, float above) {
        VoxelShape out = VoxelShapes.empty();
        int n = state.get(SHAPE) == Shape.SLOPE ? 4 : 1;
        for (int k = 0; k < n; k++) {
            float za = z0 + (z1 - z0) * k / n;
            float zb = z0 + (z1 - z0) * (k + 1) / n;
            // uphill (smaller z) end is the higher one
            float top = clampTop(surface(state, za) + above);
            float lo = Math.min(bottom(state, za), bottom(state, zb));
            out = VoxelShapes.union(out, box(state, x0, lo, za, x1, top, zb));
        }
        return out;
    }

    private static float clampTop(float top) {
        return Math.min(32f, top);
    }

    /** A north-frame left-side box, mirrored for RIGHT and turned to FACING. */
    private static VoxelShape box(BlockState state, float x0, float y0, float z0, float x1, float y1, float z1) {
        if (state.get(RIGHT)) {
            float t = 16 - x1;
            x1 = 16 - x0;
            x0 = t;
        }
        y0 = Math.max(-16f, y0);
        y1 = Math.max(y0 + 1f, y1);
        float[] a = MaterialRampBlock.rotateXZ(state.get(FACING), x0, z0);
        float[] b = MaterialRampBlock.rotateXZ(state.get(FACING), x1, z1);
        return Block.createCuboidShape(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    // ------------------------------------------------------------ placement

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState state = getDefaultState().with(SHAPE, RampRailItem.selectedShape(context.getStack()));
        BlockState below = world.getBlockState(pos.down());
        Direction look = context.getHorizontalPlayerFacing();
        Direction beside = null;
        if (below.getBlock() instanceof MaterialRampBlock) {
            // On a sloped segment the rail climbs with it; on a landing the rail can turn,
            // so it follows the player's look (cross rails, corners)
            Direction facing = below.get(MaterialRampBlock.SEG) < MaterialRampBlock.RUN || followsRamp(state)
                    ? below.get(MaterialRampBlock.FACING) : look;
            state = state.with(MODE, Mode.ON).with(FACING, facing).with(SEG, below.get(MaterialRampBlock.SEG));
        } else if ((beside = rampBeside(world, pos, context)) != null) {
            BlockState ramp = world.getBlockState(pos.offset(beside));
            Direction facing = ramp.get(MaterialRampBlock.FACING);
            if (ramp.get(MaterialRampBlock.SEG) >= MaterialRampBlock.RUN && !followsRamp(state)
                    && look.getAxis() != beside.getAxis()) {
                facing = look; // beside a landing: run along whichever edge you face
            }
            state = state.with(MODE, Mode.BESIDE).with(FACING, facing).with(SEG, ramp.get(MaterialRampBlock.SEG))
                    .with(RIGHT, beside == facing.rotateYClockwise());
        } else {
            state = state.with(MODE, Mode.FLOOR).with(SEG, 0).with(FACING, floorFacing(world, pos, context));
        }
        if (beside == null) {
            state = state.with(RIGHT, side(world, pos, context, state.get(FACING)));
        }
        return state;
    }

    /** Shapes whose direction is the ramp's own (they extend or climb the run). */
    private static boolean followsRamp(BlockState state) {
        Shape shape = state.get(SHAPE);
        return shape == Shape.SLOPE || shape == Shape.START || shape == Shape.END;
    }

    /** A ramp in a horizontal neighbour running ALONG this cell (not into it); the clicked one first. */
    @Nullable
    private static Direction rampBeside(World world, BlockPos pos, ItemPlacementContext context) {
        Direction clicked = context.getSide().getOpposite();
        if (clicked.getAxis().isHorizontal() && runsAlong(world.getBlockState(pos.offset(clicked)), clicked)) {
            return clicked;
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (runsAlong(world.getBlockState(pos.offset(d)), d)) {
                return d;
            }
        }
        return null;
    }

    private static boolean runsAlong(BlockState state, Direction toward) {
        return state.getBlock() instanceof MaterialRampBlock
                && state.get(MaterialRampBlock.FACING).getAxis() != toward.getAxis();
    }

    /**
     * No ramp below or beside: a ramp starting just ahead (we are its run-up,
     * START), or one arriving from behind one level down (we are on its top,
     * END) sets the direction; otherwise the player's look.
     */
    private static Direction floorFacing(World world, BlockPos pos, ItemPlacementContext context) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState ahead = world.getBlockState(pos.offset(d));
            if (ahead.getBlock() instanceof MaterialRampBlock && ahead.get(MaterialRampBlock.FACING) == d) {
                return d;
            }
            BlockState arriving = world.getBlockState(pos.down().offset(d));
            if (arriving.getBlock() instanceof MaterialRampBlock
                    && arriving.get(MaterialRampBlock.FACING) == d.getOpposite()) {
                return d.getOpposite();
            }
        }
        return context.getHorizontalPlayerFacing();
    }

    /** Which side of the lane (viewed uphill) for a rail with no ramp beside it. */
    private boolean side(World world, BlockPos pos, ItemPlacementContext context, Direction facing) {
        if (style == Style.DOUBLE) {
            return false;
        }
        Direction right = facing.rotateYClockwise();
        if (style == Style.WALL) {
            boolean wallRight = solidWall(world, pos, right);
            boolean wallLeft = solidWall(world, pos, right.getOpposite());
            if (wallRight != wallLeft) {
                return wallRight;
            }
        }
        Vec3d offset = context.getHitPos().subtract(Vec3d.ofCenter(pos));
        return offset.getX() * right.getOffsetX() + offset.getZ() * right.getOffsetZ() > 0;
    }

    private static boolean solidWall(World world, BlockPos pos, Direction side) {
        BlockPos neighbour = pos.offset(side);
        return world.getBlockState(neighbour).isSideSolidFullSquare(world, neighbour, side.getOpposite());
    }

    /**
     * Keep measuring from the ramp when its segment changes (or one is built
     * under a floor rail). Never re-aims the rail: on a landing it may run
     * across the ramp on purpose — and /setblock runs this for every side.
     */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (!(neighborState.getBlock() instanceof MaterialRampBlock)) {
            return state;
        }
        Mode mode = state.get(MODE);
        if (direction == Direction.DOWN && mode != Mode.BESIDE) {
            return state.with(MODE, Mode.ON).with(SEG, neighborState.get(MaterialRampBlock.SEG));
        }
        if (mode == Mode.BESIDE && direction.getAxis().isHorizontal()) {
            Direction facing = state.get(FACING);
            Direction toward = state.get(RIGHT) ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
            if (direction == toward) {
                return state.with(SEG, neighborState.get(MaterialRampBlock.SEG));
            }
        }
        return state;
    }
}
