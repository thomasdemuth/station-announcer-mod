package com.stationannouncer.atmosphere;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.LichenGrower;
import net.minecraft.block.MultifaceGrowthBlock;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

import java.util.Collection;

/**
 * A grime decal (ATMOSPHERE_PLAN.md §2.2): a vanilla multiface block, like glow lichen and sculk
 * vein. The six booleans {@code north east south west up down} come from
 * {@link MultifaceGrowthBlock} and name the SIDE OF THE CELL the decal is stuck to:
 * {@code down} = on the floor (the top of the block below), {@code up} = on the ceiling,
 * {@code north} = on the wall to the north, and so on. One air cell can carry a decal on every
 * face it touches, so a corner cell holds floor dust, wall dust and a ceiling stain at once.
 *
 * <p>No collision, replaceable (building over a decal overwrites it, like glow lichen),
 * drops nothing. It never grows: {@link #getGrower()} exists only because the superclass asks.
 *
 * <p><b>Which faces a kind allows</b> is decided by {@link #surfaces()}. The superclass calls
 * {@link #canHaveDirection} from {@code appendProperties}, i.e. from inside the
 * {@code Block} constructor, before any field of this class is assigned. So the answer cannot be
 * a constructor argument stored in a field; it is a constant returned by one of the three small
 * subclasses below ({@link AnySurface}, {@link FloorOnly}, {@link WallsAndCeiling}). A kind that
 * disallows a face has NO property for it at all (the superclass only adds properties for
 * allowed faces) - a floor-only decal's only property is {@code down}.
 *
 * <p>Variety comes from the generated multipart blockstate (weighted model lists per face), so
 * there is no variant property and nothing position-dependent in Java.
 */
public abstract class GrimeDecalBlock extends MultifaceGrowthBlock {
    /** Which faces of the cell a decal kind may sit on. */
    public enum Surfaces {
        /** floor, ceiling and all four walls */
        ANY,
        /** floor only ({@code down}) */
        FLOOR,
        /** the four walls and the ceiling ({@code up}); never the floor */
        WALLS_AND_CEILING;

        public boolean allows(Direction face) {
            return switch (this) {
                case ANY -> true;
                case FLOOR -> face == Direction.DOWN;
                case WALLS_AND_CEILING -> face != Direction.DOWN;
            };
        }
    }

    private final LichenGrower grower = new LichenGrower(this);

    protected GrimeDecalBlock(Settings settings) {
        super(settings);
    }

    /** Must return a constant: it is consulted from the superclass constructor. */
    public abstract Surfaces surfaces();

    @Override
    protected boolean canHaveDirection(Direction direction) {
        return surfaces().allows(direction);
    }

    @Override
    public LichenGrower getGrower() {
        return grower;
    }

    /** Another item builds over the decal; the same item adds a face (glow lichen's rule). */
    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        return !context.getStack().isOf(asItem()) || super.canReplace(state, context);
    }

    // ------------------------------------------------------------------ helpers for tools

    /** True if this kind may carry a decal on that face of its cell. */
    public boolean allows(Direction face) {
        return surfaces().allows(face);
    }

    /**
     * {@code base} (or this block's default state when {@code base} is not this block) with the
     * given faces switched on. Faces this kind does not allow are skipped. Returns null if no face
     * ended up on. Does NOT check support - see {@link #canStickTo}.
     */
    public BlockState withFaces(BlockState base, Collection<Direction> faces) {
        BlockState state = base != null && base.isOf(this) ? base : getDefaultState();
        boolean any = false;
        for (Direction face : faces) {
            if (allows(face)) {
                state = state.with(getProperty(face), true);
                any = true;
            }
        }
        for (Direction face : DIRECTIONS) {
            if (allows(face) && state.get(getProperty(face))) {
                any = true;
            }
        }
        return any ? state : null;
    }

    /** True if a decal on {@code face} of the cell at {@code pos} would have something to stick to. */
    public boolean canStickTo(BlockView world, BlockPos pos, Direction face) {
        return allows(face) && canGrowOn(world, face, pos.offset(face), world.getBlockState(pos.offset(face)));
    }

    // ------------------------------------------------------------------ the three shapes of kind

    /** Floor, walls and ceiling. */
    public static final class AnySurface extends GrimeDecalBlock {
        public static final MapCodec<AnySurface> CODEC = createCodec(AnySurface::new);

        public AnySurface(Settings settings) {
            super(settings);
        }

        @Override
        public Surfaces surfaces() {
            return Surfaces.ANY;
        }

        @Override
        protected MapCodec<AnySurface> getCodec() {
            return CODEC;
        }
    }

    /** Floor only: puddles, gum, oil. */
    public static final class FloorOnly extends GrimeDecalBlock {
        public static final MapCodec<FloorOnly> CODEC = createCodec(FloorOnly::new);

        public FloorOnly(Settings settings) {
            super(settings);
        }

        @Override
        public Surfaces surfaces() {
            return Surfaces.FLOOR;
        }

        @Override
        protected MapCodec<FloorOnly> getCodec() {
            return CODEC;
        }
    }

    /** Walls and ceiling: rust streaks, efflorescence. */
    public static final class WallsAndCeiling extends GrimeDecalBlock {
        public static final MapCodec<WallsAndCeiling> CODEC = createCodec(WallsAndCeiling::new);

        public WallsAndCeiling(Settings settings) {
            super(settings);
        }

        @Override
        public Surfaces surfaces() {
            return Surfaces.WALLS_AND_CEILING;
        }

        @Override
        protected MapCodec<WallsAndCeiling> getCodec() {
            return CODEC;
        }
    }
}
