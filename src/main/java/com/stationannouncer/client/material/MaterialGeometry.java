package com.stationannouncer.client.material;

import com.stationannouncer.material.MaterialPalette;
import com.stationannouncer.material.MaterialRampBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of the material ramp and stairs, textured from the block's palette
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
}
