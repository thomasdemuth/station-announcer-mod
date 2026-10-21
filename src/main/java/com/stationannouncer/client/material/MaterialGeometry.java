package com.stationannouncer.client.material;

import com.stationannouncer.material.MaterialMixedSlabBlock;
import com.stationannouncer.material.MaterialPalette;
import com.stationannouncer.material.MaterialRampBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.block.enums.WallShape;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Geometry of the material ramp, stairs, slab, wall and fence, textured from the block's palette
 * material. Everything is authored in a model frame and turned by the same
 * x/y rotations a blockstate JSON would use, so culling and uv-lock follow.
 */
@Environment(EnvType.CLIENT)
public final class MaterialGeometry {
    private static final ShapeModel.Tex MAT = ShapeModel.Tex.MATERIAL;

    private MaterialGeometry() {
    }

    /** The material a material block wears (client palette). */
    static BlockState materialOf(BlockState state) {
        return ClientMaterialPalette.state(MaterialPalette.slot(state));
    }

    // ------------------------------------------------------------------ ramp

    public static final ShapeModel.Geometry RAMP = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            int seg = state.get(MaterialRampBlock.SEG);
            List<ShapeModel.Face> faces = seg >= MaterialRampBlock.RUN
                    ? Geo.cube(0, 0, 0, 16, 16, 16, MAT)
                    : wedge(MaterialRampBlock.lowPx(seg), MaterialRampBlock.highPx(seg));
            int y = Geo.yFor(state.get(MaterialRampBlock.FACING));
            List<ShapeModel.Face> out = new ArrayList<>(faces.size());
            for (ShapeModel.Face face : faces) {
                out.add(Geo.rotate(face, 0, y));
            }
            return out;
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }

        @Override
        public boolean ambientOcclusion() {
            return false; // flat-lit slope: no AO band beside landings (ShapeModel.Geometry#ambientOcclusion)
        }
    };

    /**
     * A 1:12 wedge in the north frame: uphill edge (z = 0) at {@code high},
     * downhill edge (z = 16) at {@code low}.
     */
    static List<ShapeModel.Face> wedge(float low, float high) {
        List<ShapeModel.Face> faces = new ArrayList<>(6);
        faces.add(Geo.box(0, 0, 0, 16, high, 16, Direction.DOWN, Direction.DOWN, MAT));
        // The slope. Normal: perpendicular to (x axis) × (down-slope vector (0, low-high, 16)).
        float dy = low - high;
        float len = (float) Math.sqrt(dy * dy + 256f);
        faces.add(Geo.quad(new float[]{0, high, 0, 16, high, 0, 16, low, 16, 0, low, 16},
                0, 16f / len, -dy / len, Direction.UP, null, MAT, null));
        faces.add(Geo.box(0, 0, 0, 16, high, 16, Direction.NORTH, Direction.NORTH, MAT));
        if (low > 0.001f) {
            faces.add(Geo.box(0, 0, 0, 16, low, 16, Direction.SOUTH, Direction.SOUTH, MAT));
        }
        // Trapezoid sides (a triangle for segment 0: its downhill corner has no height).
        faces.add(Geo.quad(new float[]{0, 0, 0, 0, high, 0, 0, low, 16, 0, 0, 16},
                -1, 0, 0, Direction.WEST, Direction.WEST, MAT, null));
        faces.add(Geo.quad(new float[]{16, 0, 0, 16, high, 0, 16, low, 16, 16, 0, 16},
                1, 0, 0, Direction.EAST, Direction.EAST, MAT, null));
        return faces;
    }

    /** The ramp item icon: a steeper wedge that reads as "ramp" in an inventory slot. */
    public static final ShapeModel.Geometry RAMP_ITEM = state -> wedge(1f, 12f);

    // ---------------------------------------------------------------- stairs

    /**
     * Vanilla stairs: stairs / inner_stairs / outer_stairs elements (authored
     * facing EAST) and vanilla's exact blockstate rotation table.
     */
    public static final ShapeModel.Geometry STAIRS = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            Direction facing = state.get(StairsBlock.FACING);
            boolean top = state.get(StairsBlock.HALF) == BlockHalf.TOP;
            StairShape shape = state.get(StairsBlock.SHAPE);
            int straight = switch (facing) {
                case SOUTH -> 90;
                case WEST -> 180;
                case NORTH -> 270;
                default -> 0;
            };
            boolean left = shape == StairShape.INNER_LEFT || shape == StairShape.OUTER_LEFT;
            boolean right = shape == StairShape.INNER_RIGHT || shape == StairShape.OUTER_RIGHT;
            int y = straight;
            if (!top && left) {
                y = straight - 90;
            } else if (top && right) {
                y = straight + 90;
            }
            y = ((y % 360) + 360) % 360;
            int x = top ? 180 : 0;
            List<ShapeModel.Face> faces = stairElements(shape);
            List<ShapeModel.Face> out = new ArrayList<>(faces.size());
            for (ShapeModel.Face face : faces) {
                out.add(Geo.rotate(face, x, y));
            }
            return out;
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }
    };

    /** The stairs item: vanilla's straight stairs, unrotated (as the vanilla item model). */
    public static final ShapeModel.Geometry STAIRS_ITEM = state -> stairElements(StairShape.STRAIGHT);

    static List<ShapeModel.Face> stairElements(StairShape shape) {
        List<ShapeModel.Face> faces = new ArrayList<>();
        // Bottom slab (all three models).
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.DOWN, Direction.DOWN, MAT));
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.UP, null, MAT));
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.NORTH, Direction.NORTH, MAT));
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.SOUTH, Direction.SOUTH, MAT));
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.WEST, Direction.WEST, MAT));
        faces.add(Geo.box(0, 0, 0, 16, 8, 16, Direction.EAST, Direction.EAST, MAT));
        switch (shape) {
            case STRAIGHT -> {
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.UP, Direction.UP, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.NORTH, Direction.NORTH, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.SOUTH, Direction.SOUTH, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.WEST, null, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.EAST, Direction.EAST, MAT));
            }
            case INNER_LEFT, INNER_RIGHT -> {
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.UP, Direction.UP, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.NORTH, Direction.NORTH, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.SOUTH, Direction.SOUTH, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.WEST, null, MAT));
                faces.add(Geo.box(8, 8, 0, 16, 16, 16, Direction.EAST, Direction.EAST, MAT));
                faces.add(Geo.box(0, 8, 8, 8, 16, 16, Direction.UP, Direction.UP, MAT));
                faces.add(Geo.box(0, 8, 8, 8, 16, 16, Direction.NORTH, null, MAT));
                faces.add(Geo.box(0, 8, 8, 8, 16, 16, Direction.SOUTH, Direction.SOUTH, MAT));
                faces.add(Geo.box(0, 8, 8, 8, 16, 16, Direction.WEST, Direction.WEST, MAT));
            }
            default -> { // OUTER_LEFT, OUTER_RIGHT
                faces.add(Geo.box(8, 8, 8, 16, 16, 16, Direction.UP, Direction.UP, MAT));
                faces.add(Geo.box(8, 8, 8, 16, 16, 16, Direction.NORTH, null, MAT));
                faces.add(Geo.box(8, 8, 8, 16, 16, 16, Direction.SOUTH, Direction.SOUTH, MAT));
                faces.add(Geo.box(8, 8, 8, 16, 16, 16, Direction.WEST, null, MAT));
                faces.add(Geo.box(8, 8, 8, 16, 16, 16, Direction.EAST, Direction.EAST, MAT));
            }
        }
        return faces;
    }

    // ------------------------------------------------------------- slab

    /** Vanilla slab: bottom half, top half, or the full double. */
    public static final ShapeModel.Geometry SLAB = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            return switch (state.get(SlabBlock.TYPE)) {
                case TOP -> Geo.cube(0, 8, 0, 16, 16, 16, MAT);
                case DOUBLE -> Geo.cube(0, 0, 0, 16, 16, 16, MAT);
                default -> Geo.cube(0, 0, 0, 16, 8, 16, MAT);
            };
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }
    };

    /**
     * A mixed slab: the second material fills the half on FACING's side, the
     * base material the other half (up = as placed, base below).
     */
    public static final ShapeModel.Geometry MIXED_SLAB = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            Direction side = state.get(MaterialMixedSlabBlock.FACING);
            float[] second = halfBox(side);
            float[] base = halfBox(side.getOpposite());
            List<ShapeModel.Face> out = new ArrayList<>(Geo.cube(base[0], base[1], base[2], base[3], base[4], base[5], MAT));
            out.addAll(Geo.cube(second[0], second[1], second[2], second[3], second[4], second[5], ShapeModel.Tex.SECOND));
            return out;
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }

        @Override
        public @Nullable BlockState secondMaterial(BlockState state) {
            return ClientMaterialPalette.state(MaterialMixedSlabBlock.topSlot(state));
        }
    };

    /** The half of the block on {@code side}: x0 y0 z0 x1 y1 z1 in px. */
    static float[] halfBox(Direction side) {
        return switch (side) {
            case UP -> new float[]{0, 8, 0, 16, 16, 16};
            case DOWN -> new float[]{0, 0, 0, 16, 8, 16};
            case NORTH -> new float[]{0, 0, 0, 16, 16, 8};
            case SOUTH -> new float[]{0, 0, 8, 16, 16, 16};
            case WEST -> new float[]{0, 0, 0, 8, 16, 16};
            case EAST -> new float[]{8, 0, 0, 16, 16, 16};
        };
    }

    public static final ShapeModel.Geometry SLAB_ITEM = state -> Geo.cube(0, 0, 0, 16, 8, 16, MAT);

    // ------------------------------------------------------------- wall

    private static final List<EnumProperty<WallShape>> WALL_SIDES = List.of(
            WallBlock.NORTH_SHAPE, WallBlock.EAST_SHAPE, WallBlock.SOUTH_SHAPE, WallBlock.WEST_SHAPE);

    /**
     * Vanilla wall: template_wall_post (4..12, full height) when UP, and per side
     * template_wall_side (5..11, 14 high, reaching the block edge) or its tall
     * twin (16 high). Sides are authored NORTH and turned like vanilla's
     * multipart (north 0, east 90, south 180, west 270). As in vanilla, a side
     * has no inner end face: the post, or the opposite side, always covers it.
     */
    public static final ShapeModel.Geometry WALL = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            List<ShapeModel.Face> out = new ArrayList<>();
            if (state.get(WallBlock.UP)) {
                out.addAll(Geo.cube(4, 0, 4, 12, 16, 12, MAT));
            }
            for (int i = 0; i < 4; i++) {
                WallShape shape = state.get(WALL_SIDES.get(i));
                if (shape == WallShape.NONE) {
                    continue;
                }
                float top = shape == WallShape.TALL ? 16 : 14;
                for (ShapeModel.Face face : part(5, 0, 0, 11, top, 8, EnumSet.of(Direction.SOUTH))) {
                    out.add(Geo.rotate(face, 0, i * 90));
                }
            }
            return out;
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }
    };

    /** Vanilla wall_inventory: a post with a low wall through it. */
    public static final ShapeModel.Geometry WALL_ITEM = state -> {
        List<ShapeModel.Face> out = new ArrayList<>(Geo.cube(4, 0, 4, 12, 16, 12, MAT));
        out.addAll(part(5, 0, 0, 11, 13, 4, EnumSet.of(Direction.SOUTH)));
        out.addAll(part(5, 0, 12, 11, 13, 16, EnumSet.of(Direction.NORTH)));
        return out;
    };

    // ------------------------------------------------------------- fence

    private static final List<BooleanProperty> FENCE_SIDES = List.of(
            FenceBlock.NORTH, FenceBlock.EAST, FenceBlock.SOUTH, FenceBlock.WEST);

    /**
     * Vanilla fence: a 4 px post plus, per connected side, two 2 px rails
     * (y 6..9 and 12..15) from the block edge into the post. The rails' inner
     * ends sit inside the post and are left out.
     */
    public static final ShapeModel.Geometry FENCE = new ShapeModel.Geometry() {
        @Override
        public List<ShapeModel.Face> faces(BlockState state) {
            List<ShapeModel.Face> out = new ArrayList<>(Geo.cube(6, 0, 6, 10, 16, 10, MAT));
            for (int i = 0; i < 4; i++) {
                if (!state.get(FENCE_SIDES.get(i))) {
                    continue;
                }
                for (ShapeModel.Face face : fenceRails(0, 6)) {
                    out.add(Geo.rotate(face, 0, i * 90));
                }
            }
            return out;
        }

        @Override
        public @Nullable BlockState material(BlockState state) {
            return materialOf(state);
        }
    };

    /** Vanilla fence_inventory: two posts joined by two rails. */
    public static final ShapeModel.Geometry FENCE_ITEM = state -> {
        List<ShapeModel.Face> out = new ArrayList<>(Geo.cube(6, 0, 0, 10, 16, 4, MAT));
        out.addAll(Geo.cube(6, 0, 12, 10, 16, 16, MAT));
        for (float[] y : new float[][]{{5, 7}, {12, 14}}) {
            out.addAll(part(7, y[0], 4, 9, y[1], 12, EnumSet.of(Direction.NORTH, Direction.SOUTH)));
        }
        return out;
    };

    /** The two rails of a north fence side, from z0 to z1 (into the post). */
    private static List<ShapeModel.Face> fenceRails(float z0, float z1) {
        List<ShapeModel.Face> out = new ArrayList<>();
        out.addAll(part(7, 6, z0, 9, 9, z1, EnumSet.of(Direction.SOUTH)));
        out.addAll(part(7, 12, z0, 9, 15, z1, EnumSet.of(Direction.SOUTH)));
        return out;
    }

    /** A box's faces, culled at the block sides they touch, minus {@code omit}. */
    private static List<ShapeModel.Face> part(float x0, float y0, float z0, float x1, float y1, float z1,
                                              Set<Direction> omit) {
        List<ShapeModel.Face> out = new ArrayList<>(6);
        for (ShapeModel.Face face : Geo.cube(x0, y0, z0, x1, y1, z1, MAT)) {
            if (!omit.contains(face.face())) {
                out.add(face);
            }
        }
        return out;
    }
}
