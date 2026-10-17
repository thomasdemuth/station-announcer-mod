package com.stationannouncer.client.track;

/**
 * Projects a {@link TrackView3DScene.Mesh} through a {@link TrackView3DCamera} into screen-space
 * quads WITH DEPTH (pure Java; the in-game sink feeds them to the GUI's depth-tested position-colour
 * layer, the offline check rasterises them into a z-buffer).
 *
 * Depth: every vertex gets {@code z = Z_BASE + Z_RANGE · near / depth} — larger = nearer, which is the
 * GUI's own convention (items at z 150 draw over the background at 0). A value linear in 1/depth
 * interpolates EXACTLY across a projected planar triangle, so the GPU's depth test resolves the
 * perspective scene correctly even though the projection itself runs here. Range: (Z_BASE, Z_BASE +
 * Z_RANGE] ⊂ the GUI's visible ±10000.
 *
 * Polygons crossing the near plane are clipped; every emitted quad is wound so its screen-space
 * shoelace sum (x right, y down) is negative = counter-clockwise after the GUI's y-flipping ortho =
 * front-facing, so back-face culling (on in the GUI layer) never drops a face.
 */
public final class TrackView3DProjector {
    public static final float Z_BASE = 60;
    public static final float Z_RANGE = 3000;

    /** Receives screen-space quads (viewport offset already applied). Triangles repeat their last vertex. */
    public interface Sink {
        void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                  float x2, float y2, float z2, float x3, float y3, float z3, int argb);
    }

    private final double[] vx = new double[8];
    private final double[] vy = new double[8];
    private final double[] vd = new double[8];
    private final double[] cxs = new double[8];
    private final double[] cys = new double[8];
    private final double[] cds = new double[8];
    private final float[] sx = new float[8];
    private final float[] sy = new float[8];
    private final float[] sz = new float[8];

    /**
     * @param x,y,w,h viewport in sink units (GUI px)
     * @param pixel   size of one real pixel in sink units (line widths are given in real pixels)
     */
    public void draw(TrackView3DScene.Mesh mesh, TrackView3DCamera cam, float x, float y, float w, float h, float pixel, Sink sink) {
        draw(mesh, cam, x, y, w, h, pixel, Double.POSITIVE_INFINITY, sink);
    }

    /** As above, skipping quads whose first corner is deeper than {@code maxDepth} (level of detail). */
    public void draw(TrackView3DScene.Mesh mesh, TrackView3DCamera cam, float x, float y, float w, float h, float pixel,
                     double maxDepth, Sink sink) {
        final double f = TrackView3DCamera.focal(h);
        final double near = cam.near();
        final float[] q = mesh.quads;
        for (int i = 0; i < mesh.quadCount; i++) {
            final int o = i * 12;
            int inFront = 0;
            for (int k = 0; k < 4; k++) {
                view(cam, q[o + k * 3], q[o + k * 3 + 1], q[o + k * 3 + 2], k);
                if (vd[k] >= near) {
                    inFront++;
                }
            }
            if (inFront == 0 || vd[0] > maxDepth) {
                continue;
            }
            int n = 4;
            double[] px = vx;
            double[] py = vy;
            double[] pd = vd;
            if (inFront < 4) {
                n = clip(4, near);
                px = cxs;
                py = cys;
                pd = cds;
            }
            if (n < 3) {
                continue;
            }
            boolean anyInside = false;
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                sx[k] = (float) (x + w / 2 + px[k] / pd[k] * f);
                sy[k] = (float) (y + h / 2 - py[k] / pd[k] * f);
                sz[k] = (float) (Z_BASE + Z_RANGE * near / pd[k]);
                minX = Math.min(minX, sx[k]);
                maxX = Math.max(maxX, sx[k]);
                minY = Math.min(minY, sy[k]);
                maxY = Math.max(maxY, sy[k]);
            }
            anyInside = maxX >= x && minX <= x + w && maxY >= y && minY <= y + h;
            if (!anyInside) {
                continue;
            }
            emitPolygon(n, mesh.quadColors[i], sink);
        }

        final float[] l = mesh.lines;
        for (int i = 0; i < mesh.lineCount; i++) {
            final int o = i * 6;
            view(cam, l[o], l[o + 1], l[o + 2], 0);
            view(cam, l[o + 3], l[o + 4], l[o + 5], 1);
            if (vd[0] < near && vd[1] < near) {
                continue;
            }
            if (vd[0] < near || vd[1] < near) {
                final int a = vd[0] < near ? 0 : 1;
                final int b = 1 - a;
                final double t = (near - vd[a]) / (vd[b] - vd[a]);
                vx[a] += (vx[b] - vx[a]) * t;
                vy[a] += (vy[b] - vy[a]) * t;
                vd[a] = near;
            }
            final float x0 = (float) (x + w / 2 + vx[0] / vd[0] * f);
            final float y0 = (float) (y + h / 2 - vy[0] / vd[0] * f);
            final float x1 = (float) (x + w / 2 + vx[1] / vd[1] * f);
            final float y1 = (float) (y + h / 2 - vy[1] / vd[1] * f);
            final float z0 = (float) (Z_BASE + Z_RANGE * near / vd[0]);
            final float z1 = (float) (Z_BASE + Z_RANGE * near / vd[1]);
            if (Math.max(x0, x1) < x - 4 || Math.min(x0, x1) > x + w + 4 || Math.max(y0, y1) < y - 4 || Math.min(y0, y1) > y + h + 4) {
                continue;
            }
            final double dx = x1 - x0;
            final double dy = y1 - y0;
            final double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4) {
                continue;
            }
            final double half = mesh.lineWidths[i] * pixel / 2;
            final float nx = (float) (-dy / len * half);
            final float ny = (float) (dx / len * half);
            final float ex = (float) (dx / len * half * 0.5);
            final float ey = (float) (dy / len * half * 0.5);
            sx[0] = x0 - ex + nx;
            sy[0] = y0 - ey + ny;
            sz[0] = z0;
            sx[1] = x0 - ex - nx;
            sy[1] = y0 - ey - ny;
            sz[1] = z0;
            sx[2] = x1 + ex - nx;
            sy[2] = y1 + ey - ny;
            sz[2] = z1;
            sx[3] = x1 + ex + nx;
            sy[3] = y1 + ey + ny;
            sz[3] = z1;
            emitPolygon(4, mesh.lineColors[i], sink);
        }
    }

    private void view(TrackView3DCamera cam, double x, double y, double z, int k) {
        final double dx = x - cam.cx;
        final double dy = y - cam.cy;
        final double dz = z - cam.cz;
        vx[k] = dx * cam.rx + dy * cam.ry + dz * cam.rz;
        vy[k] = dx * cam.ux + dy * cam.uy + dz * cam.uz;
        vd[k] = dx * cam.fx + dy * cam.fy + dz * cam.fz;
    }

    /** Sutherland–Hodgman against depth ≥ near; result in cxs/cys/cds. */
    private int clip(int n, double near) {
        int out = 0;
        for (int k = 0; k < n; k++) {
            final int j = (k + 1) % n;
            final boolean kIn = vd[k] >= near;
            final boolean jIn = vd[j] >= near;
            if (kIn) {
                cxs[out] = vx[k];
                cys[out] = vy[k];
                cds[out] = vd[k];
                out++;
            }
            if (kIn != jIn) {
                final double t = (near - vd[k]) / (vd[j] - vd[k]);
                cxs[out] = vx[k] + (vx[j] - vx[k]) * t;
                cys[out] = vy[k] + (vy[j] - vy[k]) * t;
                cds[out] = near;
                out++;
            }
        }
        return out;
    }

    /** Emits the convex polygon in sx/sy/sz as a fan of quads, wound front-facing. */
    private void emitPolygon(int n, int color, Sink sink) {
        double area = 0;
        for (int k = 0; k < n; k++) {
            final int j = (k + 1) % n;
            area += (double) sx[k] * sy[j] - (double) sx[j] * sy[k];
        }
        if (Math.abs(area) < 1e-7) {
            return;
        }
        final boolean flip = area > 0;
        for (int start = 1; start + 1 < n; start += 2) {
            final int a = 0;
            final int b = start;
            final int c = start + 1;
            final int d = Math.min(start + 2, n - 1);
            if (flip) {
                sink.quad(sx[a], sy[a], sz[a], sx[d], sy[d], sz[d], sx[c], sy[c], sz[c], sx[b], sy[b], sz[b], color);
            } else {
                sink.quad(sx[a], sy[a], sz[a], sx[b], sy[b], sz[b], sx[c], sy[c], sz[c], sx[d], sy[d], sz[d], color);
            }
        }
    }
}
