package com.stationannouncer.client.material;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry helpers for {@link ShapeModel} geometries: box faces, polygons,
 * and vanilla's blockstate rotations (x first, then y; y90 turns north to
 * east) applied to positions, normals and cull sides alike.
 */
@Environment(EnvType.CLIENT)
public final class Geo {
    private Geo() {
    }

    /** One face of a box, in the box's own frame. */
    public static ShapeModel.Face box(float x0, float y0, float z0, float x1, float y1, float z1, Direction side,
                                      @Nullable Direction cull, ShapeModel.Tex tex) {
        float[] v = switch (side) {
            case DOWN -> new float[]{x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1};
            case UP -> new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
            case NORTH -> new float[]{x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0};
            case SOUTH -> new float[]{x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1};
            case WEST -> new float[]{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1};
            case EAST -> new float[]{x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0};
        };
        return ShapeModel.Face.of(v, side, cull, tex);
    }

    /** All six faces of a box, culled against the block sides they touch. */
    public static List<ShapeModel.Face> cube(float x0, float y0, float z0, float x1, float y1, float z1, ShapeModel.Tex tex) {
        List<ShapeModel.Face> faces = new ArrayList<>(6);
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.DOWN, y0 <= 0 ? Direction.DOWN : null, tex));
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.UP, y1 >= 16 ? Direction.UP : null, tex));
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.NORTH, z0 <= 0 ? Direction.NORTH : null, tex));
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.SOUTH, z1 >= 16 ? Direction.SOUTH : null, tex));
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.WEST, x0 <= 0 ? Direction.WEST : null, tex));
        faces.add(box(x0, y0, z0, x1, y1, z1, Direction.EAST, x1 >= 16 ? Direction.EAST : null, tex));
        return faces;
    }

    /** A face with an arbitrary normal (a slope); its texture projects from {@code face}. */
    public static ShapeModel.Face quad(float[] v, float nx, float ny, float nz, Direction face,
                                       @Nullable Direction cull, ShapeModel.Tex tex, float @Nullable [] uv) {
        return new ShapeModel.Face(v, nx, ny, nz, face, cull, tex, uv);
    }

    /** Rotate a face like a blockstate's x (0/180) then y (0/90/180/270). */
    public static ShapeModel.Face rotate(ShapeModel.Face face, int x, int y) {
        if (x == 0 && y == 0) {
            return face;
        }
        float[] v = face.v().clone();
        for (int i = 0; i < 4; i++) {
            float[] p = rotatePoint(v[i * 3], v[i * 3 + 1], v[i * 3 + 2], x, y);
            v[i * 3] = p[0];
            v[i * 3 + 1] = p[1];
            v[i * 3 + 2] = p[2];
        }
        float[] n = rotateVector(face.nx(), face.ny(), face.nz(), x, y);
        return new ShapeModel.Face(v, n[0], n[1], n[2], rotate(face.face(), x, y),
                face.cull() == null ? null : rotate(face.cull(), x, y), face.tex(), face.uv());
    }

    public static float[] rotatePoint(float px, float py, float pz, int x, int y) {
        if (x == 180) {
            py = 16 - py;
            pz = 16 - pz;
        }
        for (int step = 0; step < ((y / 90) % 4 + 4) % 4; step++) {
            float nx = 16 - pz;
            pz = px;
            px = nx;
        }
        return new float[]{px, py, pz};
    }

    private static float[] rotateVector(float vx, float vy, float vz, int x, int y) {
        if (x == 180) {
            vy = -vy;
            vz = -vz;
        }
        for (int step = 0; step < ((y / 90) % 4 + 4) % 4; step++) {
            float nx = -vz;
            vz = vx;
            vx = nx;
        }
        return new float[]{vx, vy, vz};
    }

    public static Direction rotate(Direction direction, int x, int y) {
        Direction d = direction;
        if (x == 180) {
            d = switch (d) {
                case UP -> Direction.DOWN;
                case DOWN -> Direction.UP;
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                default -> d;
            };
        }
        if (d.getAxis().isHorizontal()) {
            for (int step = 0; step < ((y / 90) % 4 + 4) % 4; step++) {
                d = d.rotateYClockwise();
            }
        }
        return d;
    }

    /** The blockstate y rotation that turns a north-authored model to face {@code facing}. */
    public static int yFor(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }
}
