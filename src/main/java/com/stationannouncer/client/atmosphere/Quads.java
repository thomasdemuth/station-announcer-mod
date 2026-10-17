package com.stationannouncer.client.atmosphere;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/** Opaque position-colour quads for the debug-quads layer (overlays, markers). */
@Environment(EnvType.CLIENT)
final class Quads {
    private Quads() {
    }

    static void quad(Matrix4f m, VertexConsumer b,
                     double x1, double y1, double z1, double x2, double y2, double z2,
                     double x3, double y3, double z3, double x4, double y4, double z4,
                     float r, float g, float bl) {
        b.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x3, (float) y3, (float) z3).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x4, (float) y4, (float) z4).color(r, g, bl, 1.0f).next();
    }

    /** Both windings of an axis-aligned rectangle in a z plane. */
    static void cardZ(Matrix4f m, VertexConsumer b, double x0, double y0, double x1, double y1, double z,
                      float r, float g, float bl) {
        quad(m, b, x1, y0, z, x0, y0, z, x0, y1, z, x1, y1, z, r, g, bl);
        quad(m, b, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, r, g, bl);
    }

    /** The twelve edges of a box as thin square bars. */
    static void boxEdges(Matrix4f m, VertexConsumer b, double x0, double y0, double z0,
                         double x1, double y1, double z1, double t, float r, float g, float bl) {
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) {
                bar(m, b, x0, y, z, x1, y, z, t, r, g, bl);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) {
                bar(m, b, x, y0, z, x, y1, z, t, r, g, bl);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) {
                bar(m, b, x, y, z0, x, y, z1, t, r, g, bl);
            }
        }
    }

    /** An axis-aligned square bar (2 crossed double-sided ribbons). */
    static void bar(Matrix4f m, VertexConsumer b, double ax, double ay, double az,
                    double bx, double by, double bz, double t, float r, float g, float bl) {
        boolean alongX = ax != bx;
        boolean alongY = ay != by;
        double[] s1 = alongY ? new double[]{t, 0, 0} : new double[]{0, t, 0};
        double[] s2 = alongX ? new double[]{0, 0, t} : alongY ? new double[]{0, 0, t} : new double[]{t, 0, 0};
        for (double[] s : new double[][]{s1, s2}) {
            quad(m, b, ax + s[0], ay + s[1], az + s[2], bx + s[0], by + s[1], bz + s[2],
                    bx - s[0], by - s[1], bz - s[2], ax - s[0], ay - s[1], az - s[2], r, g, bl);
            quad(m, b, ax - s[0], ay - s[1], az - s[2], bx - s[0], by - s[1], bz - s[2],
                    bx + s[0], by + s[1], bz + s[2], ax + s[0], ay + s[1], az + s[2], r, g, bl);
        }
    }
}
