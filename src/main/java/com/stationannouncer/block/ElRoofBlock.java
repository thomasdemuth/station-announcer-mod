package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * El canopy roof: a shallow red standing-seam gable over a platform, any
 * length, any width up to {@code 2 * (MAX_LEVEL + 1) + 1} = 7 blocks.
 *
 * <p>{@link #AXIS} is the ridge direction (the way the placer looks). Each
 * cell works out where it sits ACROSS the roof by walking its crosswise
 * neighbours: {@link #LEVEL} = rows in from the nearest eave, {@link #SIDE} =
 * which eave it slopes down to, or {@link Side#CROWN} for the middle row of an
 * odd width. Every profile hands its neighbour the same boundary height, so
 * the family is closed: nothing can gap or step, whatever the width.
 *
 * <p>Along the ridge, {@link #END_NEG}/{@link #END_POS} mark open run ends
 * (truss panels); {@link #RIDGE} marks the two middle rows of an EVEN width,
 * which meet at a ridge on their shared boundary and each draw half a cap.
 *
 * <p>Everything is recomputed on placement, on neighbour updates (which
 * cascade along the row, since each change notifies the next cell) and in
 * {@link #onBlockAdded} for fills and pastes. Assets: tools/gen_el2_assets.py.
 */
public class ElRoofBlock extends Block {
    public static final int MAX_LEVEL = 2;

    public enum Side implements StringIdentifiable {
        NEG("neg"), POS("pos"), CROWN("crown");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
    public static final IntProperty LEVEL = IntProperty.of("level", 0, MAX_LEVEL);
    public static final BooleanProperty END_NEG = BooleanProperty.of("end_neg");
    public static final BooleanProperty END_POS = BooleanProperty.of("end_pos");
    public static final BooleanProperty RIDGE = BooleanProperty.of("ridge");

    /**
     * How this cell meets a PERPENDICULAR roof (an L-shaped canopy). "pos"/"neg"
     * is the along-ridge direction the perpendicular roof descends toward.
     * HIP: we are at an open run end and a perpendicular roof beside us
     * descends toward that end - the two slopes meet along the diagonal
     * (min surface, stepped model). VALLEY: the perpendicular roof next to us
     * along the ridge descends toward us - both slabs are drawn (max
     * surface). PEAK: our crown meets its crown - a pyramid. Roofs meeting at
     * a corner must have the same width for the diagonal to line up.
     */
    public enum Join implements StringIdentifiable {
        NONE("none"), HIP_POS("hip_pos"), HIP_NEG("hip_neg"), VALLEY_POS("valley_pos"), VALLEY_NEG("valley_neg"),
        PEAK("peak");

        private final String name;

        Join(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final EnumProperty<Join> JOIN = EnumProperty.of("join", Join.class);

    /**
     * Outline/collision follow the profile: a level-0 cell spans from the
     * frieze (y 0) to its high deck edge, an inner cell from just under its
     * low deck edge to its high edge (up to 28.4 px - VoxelShapes accept
     * boxes above 16, like fences). Numbers mirror tools/gen_el2_assets.py:
     * BASE 6, RISE 6.627, deck ~2 px.
     */
    private static final double BASE = 6.0;
    private static final double RISE = 16.0 * Math.tan(Math.toRadians(22.5));
    private static final VoxelShape[] SHAPES = new VoxelShape[Side.values().length * (MAX_LEVEL + 1)];

    static {
        for (Side side : Side.values()) {
            for (int level = 0; level <= MAX_LEVEL; level++) {
                double lo = level == 0 ? 0.0 : BASE + RISE * level - 1.0;
                double hi = side == Side.CROWN
                        ? BASE + RISE * level + RISE / 2.0 + 3.0
                        : BASE + RISE * (level + 1) + 2.5;
                SHAPES[side.ordinal() * (MAX_LEVEL + 1) + level] =
                        Block.createCuboidShape(0, lo, 0, 16, Math.min(32.0, hi), 16);
            }
        }
    }

    private static VoxelShape shapeOf(BlockState state) {
        return SHAPES[state.get(SIDE).ordinal() * (MAX_LEVEL + 1) + state.get(LEVEL)];
    }

    public ElRoofBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(AXIS, Direction.Axis.X).with(SIDE, Side.CROWN)
                .with(LEVEL, 0).with(END_NEG, false).with(END_POS, false).with(RIDGE, false).with(JOIN, Join.NONE));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS, SIDE, LEVEL, END_NEG, END_POS, RIDGE, JOIN);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeOf(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeOf(state);
    }

    /**
     * The crosswise direction the authored "neg" models descend toward. For an
     * x ridge that is model -z = north. The z-ridge models are the same files
     * turned y=90 in the blockstate, and a y=90 turn maps model -z to world
     * +x, so for a z ridge "neg" descends toward EAST (verified in game: with
     * WEST here every z-ridge roof rendered as a butterfly).
     */
    public static Direction crossNeg(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
    }

    /** The "negative" along-ridge direction: west for an x ridge, north for a z ridge. */
    public static Direction alongNeg(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
    }

    private boolean sameRoof(BlockState other, Direction.Axis axis) {
        return other.isOf(this) && other.get(AXIS) == axis;
    }

    private boolean perpendicularRoof(BlockState other, Direction.Axis axis) {
        return other.isOf(this) && other.get(AXIS) != axis;
    }

    /** Result of walking crosswise: same-axis cells counted, and whether a perpendicular roof stopped the walk. */
    private record Count(int n, boolean hitPerpendicular) {
    }

    private Count count(WorldAccess world, BlockPos pos, Direction dir, Direction.Axis axis) {
        int n = 0;
        BlockPos p = pos.offset(dir);
        while (n < 16) {
            BlockState s = world.getBlockState(p);
            if (sameRoof(s, axis)) {
                n++;
                p = p.offset(dir);
            } else {
                return new Count(n, perpendicularRoof(s, axis));
            }
        }
        return new Count(n, false);
    }

    /** Crosswise profile (side, level, ridge) from counts; null side means "contaminated by a perpendicular roof". */
    private record Profile(Side side, int level, boolean ridge, boolean clean) {
    }

    private Profile profileAt(WorldAccess world, BlockPos pos, Direction.Axis axis, boolean allowPerpendicular) {
        Direction neg = crossNeg(axis);
        Count cNeg = count(world, pos, neg, axis);
        Count cPos = count(world, pos, neg.getOpposite(), axis);
        int dNeg = cNeg.n(), dPos = cPos.n();
        boolean clean = !cNeg.hitPerpendicular() && !cPos.hitPerpendicular();
        if (!clean && allowPerpendicular) {
            // last resort: count the perpendicular roof's cells as part of our width
            dNeg = countAny(world, pos, neg);
            dPos = countAny(world, pos, neg.getOpposite());
        }
        Side side = dNeg < dPos ? Side.NEG : dNeg > dPos ? Side.POS : Side.CROWN;
        int level = Math.min(Math.min(dNeg, dPos), MAX_LEVEL);
        boolean ridge = side != Side.CROWN && Math.abs(dNeg - dPos) == 1;
        return new Profile(side, level, ridge, clean);
    }

    private int countAny(WorldAccess world, BlockPos pos, Direction dir) {
        int n = 0;
        BlockPos p = pos.offset(dir);
        while (n < 16 && world.getBlockState(p).isOf(this)) {
            n++;
            p = p.offset(dir);
        }
        return n;
    }

    /**
     * The cell's profile. Inside the corner square of an L the crosswise walk
     * runs into the other roof's cells, so the row is taken from the nearest
     * cell ALONG OUR RIDGE whose crosswise walk is clean (a row keeps its
     * profile along the whole ridge); only if none exists do we count the
     * perpendicular cells as our own.
     */
    private Profile profile(WorldAccess world, BlockPos pos, Direction.Axis axis) {
        Profile own = profileAt(world, pos, axis, false);
        if (own.clean()) {
            return own;
        }
        Direction along = alongNeg(axis);
        for (Direction dir : new Direction[]{along, along.getOpposite()}) {
            BlockPos p = pos.offset(dir);
            for (int i = 0; i < 16 && sameRoof(world.getBlockState(p), axis); i++, p = p.offset(dir)) {
                Profile other = profileAt(world, p, axis, false);
                if (other.clean()) {
                    return other;
                }
            }
        }
        return profileAt(world, pos, axis, true);
    }

    /** World direction a perpendicular (other-axis) roof cell descends toward, or null for a crown. */
    private static Direction descends(BlockState perpendicular) {
        Side side = perpendicular.get(SIDE);
        if (side == Side.CROWN) {
            return null;
        }
        Direction neg = crossNeg(perpendicular.get(AXIS));
        return side == Side.NEG ? neg : neg.getOpposite();
    }

    /**
     * Joins are decided from the perpendicular roof cell BESIDE us (crosswise):
     * it tells us which way the other roof descends (e) and at what level.
     * PEAK: we are a crown and it is a crown. HIP: same level, and along e we
     * run out of our own roof (open air, or the other roof continuing past
     * us). VALLEY: same level, and along -e the other roof stands in our run.
     * Cells with a same-axis neighbour crosswise are never joins.
     */
    private Join join(WorldAccess world, BlockPos pos, Direction.Axis axis, Profile profile) {
        Direction cross = crossNeg(axis);
        for (Direction c : new Direction[]{cross, cross.getOpposite()}) {
            BlockState beside = world.getBlockState(pos.offset(c));
            if (!perpendicularRoof(beside, axis)) {
                continue;
            }
            if (profile.side() == Side.CROWN) {
                return beside.get(SIDE) == Side.CROWN ? Join.PEAK : Join.NONE;
            }
            Direction e = descends(beside);
            if (e == null || beside.get(LEVEL) != profile.level()) {
                continue;
            }
            boolean ePos = e == alongNeg(axis).getOpposite();
            BlockState alongE = world.getBlockState(pos.offset(e));
            BlockState alongBack = world.getBlockState(pos.offset(e.getOpposite()));
            if (!alongE.isOf(this) || perpendicularRoof(alongE, axis)) {
                return ePos ? Join.HIP_POS : Join.HIP_NEG;
            }
            if (perpendicularRoof(alongBack, axis)) {
                return ePos ? Join.VALLEY_POS : Join.VALLEY_NEG;
            }
        }
        return Join.NONE;
    }

    private BlockState compute(BlockState state, WorldAccess world, BlockPos pos) {
        Direction.Axis axis = state.get(AXIS);
        Profile p = profile(world, pos, axis);
        Direction along = alongNeg(axis);
        Join join = join(world, pos, axis, p);
        // a hip corner has no gable: no truss panel toward the hip
        return state.with(SIDE, p.side()).with(LEVEL, p.level()).with(RIDGE, p.ridge())
                .with(JOIN, join)
                .with(END_NEG, join != Join.HIP_NEG && endsToward(world, pos.offset(along), axis, p))
                .with(END_POS, join != Join.HIP_POS && endsToward(world, pos.offset(along.getOpposite()), axis, p));
    }

    /**
     * A run end: no roof there, or a same-axis roof whose profile differs (the
     * width changes along the ridge) - both cells then draw their truss panel,
     * which closes the step between the two profiles. A perpendicular roof
     * there is a join, not an end.
     */
    private boolean endsToward(WorldAccess world, BlockPos there, Direction.Axis axis, Profile ours) {
        BlockState other = world.getBlockState(there);
        if (other.isOf(this) && other.get(AXIS) != axis) {
            return false;
        }
        if (!sameRoof(other, axis)) {
            return true;
        }
        Profile theirs = profile(world, there, axis);
        return theirs.side() != ours.side() || theirs.level() != ours.level();
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = getDefaultState().with(AXIS, context.getHorizontalPlayerFacing().getAxis());
        return compute(state, context.getWorld(), context.getBlockPos());
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = compute(state, world, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (!direction.getAxis().isHorizontal()) {
            return state;
        }
        return compute(state, world, pos);
    }
}
