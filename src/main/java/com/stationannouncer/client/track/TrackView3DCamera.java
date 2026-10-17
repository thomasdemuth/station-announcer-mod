package com.stationannouncer.client.track;

/**
 * Orbit camera of the track editor's 3D pane (pure Java, relative to the scene focus).
 *
 * The camera sits {@link #distance} from the target {@code (tx, ty, tz)}, in the direction
 * {@code (cos pitch · cos yaw, sin pitch, cos pitch · sin yaw)} — so yaw uses the same angle
 * convention as track headings ({@code atan2(dz, dx)}) and positive pitch looks DOWN on the scene.
 * Screen space: x right, y down (GUI coordinates); right = forward × up, which matches what a player
 * facing the same way sees (no mirroring).
 */
public final class TrackView3DCamera {
    /** Vertical field of view. */
    public static final double FOV = Math.toRadians(42);
    public static final double MIN_DISTANCE = 1.5;
    public static final double MAX_DISTANCE = 3000;
    private static final double MIN_PITCH = Math.toRadians(-12);
    private static final double MAX_PITCH = Math.toRadians(89);

    public double yaw;
    public double pitch = Math.toRadians(30);
    public double distance = 30;
    public double tx;
    public double ty;
    public double tz;

    // basis (call update() after changing the fields above)
    double cx, cy, cz;
    double fx, fy, fz;
    double rx, ry, rz;
    double ux, uy, uz;

    public TrackView3DCamera() {
        update();
    }

    public void update() {
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
        distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        final double ox = Math.cos(pitch) * Math.cos(yaw);
        final double oy = Math.sin(pitch);
        final double oz = Math.cos(pitch) * Math.sin(yaw);
        cx = tx + distance * ox;
        cy = ty + distance * oy;
        cz = tz + distance * oz;
        fx = -ox;
        fy = -oy;
        fz = -oz;
        // right = forward × up(0,1,0) = (-fz, 0, fx), normalised
        final double rl = Math.sqrt(fz * fz + fx * fx);
        rx = -fz / rl;
        ry = 0;
        rz = fx / rl;
        // camera up = right × forward
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
    }

    /** Pixels per unit of (view-space x / depth) for a viewport this tall. */
    public static double focal(double viewHeight) {
        return viewHeight / 2 / Math.tan(FOV / 2);
    }

    /** Near clip distance: scales with the zoom so depth precision follows the view. */
    public double near() {
        return Math.max(0.05, distance * 0.02);
    }

    /**
     * Frames a sphere: target on its centre, distance so it fits both ways.
     * @param headingHint chord heading of the selected track (NaN = keep the current yaw)
     */
    public void fit(double x, double y, double z, double radius, double aspect, double headingHint) {
        tx = x;
        ty = y;
        tz = z;
        final double halfV = FOV / 2;
        final double halfH = Math.atan(Math.tan(halfV) * Math.max(0.2, aspect));
        distance = radius / Math.sin(Math.min(halfV, halfH)) * 0.98;
        if (Double.isFinite(headingHint)) {
            // look roughly along the track, from behind A and off to the side: the bank reads best
            yaw = headingHint + Math.PI + 0.85;
            pitch = Math.toRadians(32);
        }
        update();
    }

    /**
     * Exact framing of a point cloud for the CURRENT yaw/pitch: picks the target and the distance
     * so every point projects inside the viewport with {@code margin} (0..1 of the half size) —
     * a bounding sphere wastes most of the pane on long, flat tracks seen at an angle.
     * @param points xyz triples (relative coordinates)
     */
    public void fitPoints(float[] points, int count, double viewWidth, double viewHeight, double margin) {
        if (count == 0) {
            return;
        }
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            x0 = Math.min(x0, points[i * 3]);
            y0 = Math.min(y0, points[i * 3 + 1]);
            z0 = Math.min(z0, points[i * 3 + 2]);
            x1 = Math.max(x1, points[i * 3]);
            y1 = Math.max(y1, points[i * 3 + 1]);
            z1 = Math.max(z1, points[i * 3 + 2]);
        }
        tx = (x0 + x1) / 2;
        ty = (y0 + y1) / 2;
        tz = (z0 + z1) / 2;
        final double f = focal(viewHeight);
        final double halfW = viewWidth / 2 * margin;
        final double halfH = viewHeight / 2 * margin;
        for (int pass = 0; pass < 4; pass++) {
            update();
            // view-space offsets of every point from the target; depth = d + p·forward
            double need = MIN_DISTANCE;
            for (int i = 0; i < count; i++) {
                final double px = points[i * 3] - tx;
                final double py = points[i * 3 + 1] - ty;
                final double pz = points[i * 3 + 2] - tz;
                final double along = px * fx + py * fy + pz * fz;
                final double side = Math.abs(px * rx + py * ry + pz * rz);
                final double up = Math.abs(px * ux + py * uy + pz * uz);
                need = Math.max(need, Math.max(side * f / halfW, up * f / halfH) - along);
                need = Math.max(need, 1 - along);
            }
            distance = need;
            update();
            // centre the projected extent: shift the target by the screen-space imbalance
            double sx0 = Double.MAX_VALUE, sx1 = -Double.MAX_VALUE, sy0 = Double.MAX_VALUE, sy1 = -Double.MAX_VALUE;
            final double[] out = new double[3];
            for (int i = 0; i < count; i++) {
                if (project(points[i * 3], points[i * 3 + 1], points[i * 3 + 2], viewWidth, viewHeight, out)) {
                    sx0 = Math.min(sx0, out[0]);
                    sx1 = Math.max(sx1, out[0]);
                    sy0 = Math.min(sy0, out[1]);
                    sy1 = Math.max(sy1, out[1]);
                }
            }
            if (sx0 > sx1) {
                break;
            }
            final double offX = (sx0 + sx1) / 2 - viewWidth / 2;
            final double offY = (sy0 + sy1) / 2 - viewHeight / 2;
            if (Math.abs(offX) < 0.5 && Math.abs(offY) < 0.5) {
                break;
            }
            final double perPixel = distance / f;
            tx += (rx * offX - ux * offY) * perPixel;
            ty += (ry * offX - uy * offY) * perPixel;
            tz += (rz * offX - uz * offY) * perPixel;
        }
        update();
    }

    /** Sets the default viewing angle for a track whose chord runs along {@code heading} (NaN = keep). */
    public void defaultAngle(double heading) {
        if (Double.isFinite(heading)) {
            yaw = heading + Math.PI + 0.85;
        }
        pitch = Math.toRadians(32);
        update();
    }

    public void orbit(double dxPixels, double dyPixels) {
        yaw += dxPixels * 0.012;
        pitch += dyPixels * 0.012;
        update();
    }

    public void pan(double dxPixels, double dyPixels, double viewHeight) {
        final double perPixel = distance / focal(viewHeight);
        tx += (-rx * dxPixels + ux * dyPixels) * perPixel;
        ty += (-ry * dxPixels + uy * dyPixels) * perPixel;
        tz += (-rz * dxPixels + uz * dyPixels) * perPixel;
        update();
    }

    /**
     * Zooms by {@code factor} (&lt; 1 = closer) about the point under the cursor on the horizontal
     * plane through the target (falls back to the target when that plane is behind or too far).
     * {@code px, py} are relative to the viewport's top-left.
     */
    public void zoom(double factor, double px, double py, double viewWidth, double viewHeight) {
        final double old = distance;
        final double next = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, old * factor));
        final double k = next / old;
        final double f = focal(viewHeight);
        final double vx = (px - viewWidth / 2) / f;
        final double vy = -(py - viewHeight / 2) / f;
        final double dx = fx + rx * vx + ux * vy;
        final double dy = fy + ry * vx + uy * vy;
        final double dz = fz + rz * vx + uz * vy;
        double hx = tx;
        double hy = ty;
        double hz = tz;
        if (Math.abs(dy) > 1e-6) {
            final double t = (ty - cy) / dy;
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (t > 0 && t * len < old * 4) {
                hx = cx + dx * t;
                hy = ty;
                hz = cz + dz * t;
            }
        }
        tx = hx + (tx - hx) * k;
        ty = hy + (ty - hy) * k;
        tz = hz + (tz - hz) * k;
        distance = next;
        update();
    }

    /**
     * Projects a point: out = {screen x, screen y, view depth} relative to the viewport's top-left.
     * Returns false when the point is in front of the near plane (out not usable).
     */
    public boolean project(double x, double y, double z, double viewWidth, double viewHeight, double[] out) {
        final double dx = x - cx;
        final double dy = y - cy;
        final double dz = z - cz;
        final double depth = dx * fx + dy * fy + dz * fz;
        if (depth < near()) {
            return false;
        }
        final double f = focal(viewHeight);
        out[0] = viewWidth / 2 + (dx * rx + dy * ry + dz * rz) / depth * f;
        out[1] = viewHeight / 2 - (dx * ux + dy * uy + dz * uz) / depth * f;
        out[2] = depth;
        return true;
    }

    /** Screen direction (unit, y down) of a horizontal world direction, for the compass. */
    public double[] screenDirection(double wx, double wz) {
        final double sx = wx * rx + wz * rz;
        final double sy = -(wx * ux + wz * uz);
        final double len = Math.sqrt(sx * sx + sy * sy);
        return len < 1e-9 ? new double[]{0, -1} : new double[]{sx / len, sy / len};
    }
}
