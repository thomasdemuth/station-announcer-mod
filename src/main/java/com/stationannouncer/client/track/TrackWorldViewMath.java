package com.stationannouncer.client.track;

/**
 * The World view's projection math, PURE JAVA (no Minecraft or JOML type) so
 * {@code tools/track_test/TrackWorldViewCheck} can verify it against matrices built exactly the way
 * {@code GameRenderer.renderWorld} builds them.
 *
 * <h2>The frame</h2>
 * One {@link #set} per rendered world frame, from what the world was ACTUALLY drawn with:
 * {@code projection} = the matrix {@code GameRenderer} loads (FOV incl. the sprint/water multipliers,
 * view bobbing / hurt tilt / nausea when they apply) and {@code view} = the camera rotation the world
 * renderer receives ({@code Rx(pitch) · Ry(yaw + 180)}); world positions enter relative to the camera
 * position. So {@code clip = P · V · (p − cam, 1)}, NDC = clip / w, and GUI px =
 * {@code ((ndcX + 1) / 2 · guiW, (1 − ndcY) / 2 · guiH)} with {@code guiW = framebufferWidth /
 * scaleFactor} (the exact extent of the GUI's orthographic projection, NOT the rounded scaled width).
 *
 * Matrices are COLUMN-MAJOR double[16] (JOML's {@code get(float[])} order): element (row r, col c)
 * at index {@code c * 4 + r}.
 */
public final class TrackWorldViewMath {
    /** Points closer than this (clip w ≈ view depth, blocks) are treated as behind the camera. */
    public static final double MIN_W = 0.05;

    private final double[] projection = new double[16];
    private final double[] pv = new double[16];
    private final double[] inverse = new double[16];
    private double camX;
    private double camY;
    private double camZ;
    private boolean valid;
    /** Increments on every {@link #set}. */
    private long frame;

    public boolean valid() {
        return valid;
    }

    public long frame() {
        return frame;
    }

    public double camX() {
        return camX;
    }

    public double camY() {
        return camY;
    }

    public double camZ() {
        return camZ;
    }

    /** P (column-major) — e.g. {@code [5]} = m11 = 1 / tan(fovY / 2) for a plain perspective. */
    public double projectionElement(int index) {
        return projection[index];
    }

    /** Stores one frame: projection and view (column-major), camera world position. */
    public void set(double[] projectionCM, double[] viewCM, double cameraX, double cameraY, double cameraZ) {
        System.arraycopy(projectionCM, 0, projection, 0, 16);
        multiply(projectionCM, viewCM, pv);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        valid = invert(pv, inverse);
        frame++;
    }

    /** Homogeneous clip coordinates of a world point: {x, y, z, w} into {@code out}. */
    public void clip(double x, double y, double z, double[] out) {
        final double rx = x - camX;
        final double ry = y - camY;
        final double rz = z - camZ;
        for (int r = 0; r < 4; r++) {
            out[r] = pv[r] * rx + pv[4 + r] * ry + pv[8 + r] * rz + pv[12 + r];
        }
    }

    /**
     * World → GUI px: {screenX, screenY, w} into {@code out} (w = clip w ≈ view depth). False when the
     * point is behind the camera (w &lt; {@link #MIN_W}) or the frame is not valid.
     */
    public boolean project(double x, double y, double z, double guiW, double guiH, double[] out) {
        if (!valid) {
            return false;
        }
        final double[] c = new double[4];
        clip(x, y, z, c);
        if (c[3] < MIN_W) {
            return false;
        }
        out[0] = (c[0] / c[3] + 1) / 2 * guiW;
        out[1] = (1 - c[1] / c[3]) / 2 * guiH;
        out[2] = c[3];
        return true;
    }

    /** Clip coordinates → GUI px {x, y} (w must be &gt; 0). */
    public static void toScreen(double[] c, double guiW, double guiH, double[] out) {
        out[0] = (c[0] / c[3] + 1) / 2 * guiW;
        out[1] = (1 - c[1] / c[3]) / 2 * guiH;
    }

    /**
     * The ray under a GUI pixel: {originX, originY, originZ, dirX, dirY, dirZ} (world; origin on the
     * near plane, direction toward the far plane, not normalised). False if the frame is not valid.
     */
    public boolean ray(double screenX, double screenY, double guiW, double guiH, double[] out) {
        if (!valid) {
            return false;
        }
        final double nx = screenX / guiW * 2 - 1;
        final double ny = 1 - screenY / guiH * 2;
        final double[] near = unproject(nx, ny, -1);
        final double[] far = unproject(nx, ny, 1);
        if (near == null || far == null) {
            return false;
        }
        out[0] = near[0] + camX;
        out[1] = near[1] + camY;
        out[2] = near[2] + camZ;
        out[3] = far[0] - near[0];
        out[4] = far[1] - near[1];
        out[5] = far[2] - near[2];
        return true;
    }

    /** Where the ray under a GUI pixel meets the plane {@code y = planeY}: {x, z}. */
    public boolean groundAt(double screenX, double screenY, double guiW, double guiH, double planeY, double maxDistance, double[] out) {
        final double[] r = new double[6];
        if (!ray(screenX, screenY, guiW, guiH, r) || Math.abs(r[4]) < 1e-12) {
            return false;
        }
        final double t = (planeY - r[1]) / r[4];
        if (t <= 0) {
            return false;
        }
        final double hx = r[3] * t;
        final double hy = r[4] * t;
        final double hz = r[5] * t;
        if (Math.sqrt(hx * hx + hy * hy + hz * hz) > maxDistance) {
            return false;
        }
        out[0] = r[0] + hx;
        out[1] = r[2] + hz;
        return true;
    }

    private double[] unproject(double nx, double ny, double nz) {
        final double[] v = {nx, ny, nz, 1};
        final double[] o = new double[4];
        for (int r = 0; r < 4; r++) {
            o[r] = inverse[r] * v[0] + inverse[4 + r] * v[1] + inverse[8 + r] * v[2] + inverse[12 + r] * v[3];
        }
        if (Math.abs(o[3]) < 1e-15) {
            return null;
        }
        return new double[]{o[0] / o[3], o[1] / o[3], o[2] / o[3]};
    }

    // ------------------------------------------------------------------ camera aiming

    /**
     * Minecraft yaw/pitch (degrees; yaw 0 = +z, pitch + = down) for a camera that must see the world
     * direction {@code (dx, dy, dz)} at NDC {@code (ndcX, ndcY)} instead of the screen centre — how the
     * orbit target ends up in the middle of the editor PANE while the world is drawn full-window.
     * {@code tanHalfH / tanHalfV} = tangents of half the horizontal / vertical field of view.
     *
     * Exact for a yaw/pitch camera (no roll): in camera space that pixel's ray is
     * {@code F + a·R + b·U} with {@code a = ndcX·tanH, b = ndcY·tanV}; its elevation depends on pitch
     * only (solved by bisection — monotone), then yaw follows from the azimuth. When the direction is
     * too steep for that pixel ({@link #reachable} false) the pitch saturates at ±89.9° and the target
     * lands off the requested point (still in front of the camera).
     */
    public static double[] aim(double dx, double dy, double dz, double ndcX, double ndcY, double tanHalfH, double tanHalfV) {
        final double a = ndcX * tanHalfH;
        final double b = ndcY * tanHalfV;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        final double want = Math.atan2(dy, horizontal);
        double lo = Math.toRadians(-89.9);
        double hi = Math.toRadians(89.9);
        for (int i = 0; i < 64; i++) {
            final double mid = (lo + hi) / 2;
            if (elevation(mid, a, b) > want) {
                lo = mid; // still looking too high: pitch further down
            } else {
                hi = mid;
            }
        }
        final double pitch = (lo + hi) / 2;
        final double forward = Math.cos(pitch) + b * Math.sin(pitch);
        final double targetYaw = horizontal < 1e-12 ? 0 : Math.atan2(-dx, dz);
        final double yaw = targetYaw - Math.atan2(a, forward);
        return new double[]{Math.toDegrees(yaw), Math.toDegrees(pitch)};
    }

    /**
     * Steepest DOWNWARD angle (degrees below the horizon) at which a target can still be put at NDC
     * {@code (ndcX, ndcY)} by a roll-free camera. Looking straight down, the screen-centre ray is
     * vertical but a ray off to the side or above the centre never is — so an orbit seen through a
     * pane that is not in the middle of the window must stop short of 90°. Upward the limit is mirrored
     * and never matters for an orbit that stays above its target.
     */
    public static double maxDownAngle(double ndcX, double ndcY, double tanHalfH, double tanHalfV) {
        return -Math.toDegrees(elevation(Math.toRadians(89.9), ndcX * tanHalfH, ndcY * tanHalfV));
    }

    /** Whether {@link #aim} can hit NDC (ndcX, ndcY) exactly for direction elevation {@code elevationRad}. */
    public static boolean reachable(double elevationRad, double ndcX, double ndcY, double tanHalfH, double tanHalfV) {
        final double a = ndcX * tanHalfH;
        final double b = ndcY * tanHalfV;
        return elevationRad >= elevation(Math.toRadians(89.9), a, b) && elevationRad <= elevation(Math.toRadians(-89.9), a, b);
    }

    /** Elevation of the camera-space ray (a, b, 1) for pitch {@code p} (radians, + = down). */
    static double elevation(double p, double a, double b) {
        final double vertical = -Math.sin(p) + b * Math.cos(p);
        final double forward = Math.cos(p) + b * Math.sin(p);
        return Math.atan2(vertical, Math.sqrt(forward * forward + a * a));
    }

    // ------------------------------------------------------------------ framing

    /**
     * Refines an orbit framing for an OFF-CENTRE pane: {@link TrackView3DCamera#fitPoints} assumes the
     * target is seen through the middle of the view, but the World view turns the camera so the target
     * sits in the pane's middle, which skews the picture. This re-projects the points through the real
     * (turned) camera and adjusts target and distance until they fill {@code margin} of the pane.
     * Coordinates are the orbit's (relative); {@code ndcX/ndcY} = the pane centre; sizes in GUI px.
     */
    public static void fitToPane(TrackView3DCamera orbit, float[] points, int count, double ndcX, double ndcY,
                                 double paneW, double paneH, double guiW, double guiH, double tanHalfH, double tanHalfV, double margin) {
        if (count == 0) {
            return;
        }
        final TrackWorldViewMath frame = new TrackWorldViewMath();
        final double[] projection = new double[16];
        projection[0] = 1 / tanHalfH;
        projection[5] = 1 / tanHalfV;
        projection[10] = -1.0001;
        projection[11] = -1;
        projection[14] = -0.1;
        final double paneCX = (ndcX + 1) / 2 * guiW;
        final double paneCY = (1 - ndcY) / 2 * guiH;
        final double focal = guiH / 2 * projection[5];
        final double[] out = new double[3];
        for (int pass = 0; pass < 60; pass++) {
            orbit.update();
            final double ex = orbitEye(orbit, 0);
            final double ey = orbitEye(orbit, 1);
            final double ez = orbitEye(orbit, 2);
            final double[] look = aim(orbit.tx - ex, orbit.ty - ey, orbit.tz - ez, ndcX, ndcY, tanHalfH, tanHalfV);
            frame.set(projection, viewMatrix(look[0], look[1]), ex, ey, ez);
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            boolean behind = false;
            for (int i = 0; i < count; i++) {
                if (!frame.project(points[i * 3], points[i * 3 + 1], points[i * 3 + 2], guiW, guiH, out) || out[2] < 0.5) {
                    behind = true;
                    break;
                }
                x0 = Math.min(x0, out[0]);
                x1 = Math.max(x1, out[0]);
                y0 = Math.min(y0, out[1]);
                y1 = Math.max(y1, out[1]);
            }
            if (behind) {
                orbit.distance *= 1.6;
                continue;
            }
            final double scale = Math.max((x1 - x0) / (paneW * margin), (y1 - y0) / (paneH * margin));
            final double offX = (x0 + x1) / 2 - paneCX;
            final double offY = (y0 + y1) / 2 - paneCY;
            if (Math.abs(scale - 1) < 0.01 && Math.abs(offX) < 0.5 && Math.abs(offY) < 0.5) {
                break;
            }
            // move the target by the screen imbalance (camera right/up at the target's depth) — damped:
            // the points sit at other depths, so the full step overshoots and can oscillate
            final double perPixel = orbit.distance / focal * 0.5;
            final double yaw = Math.toRadians(look[0]);
            final double pitch = Math.toRadians(look[1]);
            final double rx = -Math.cos(yaw);
            final double rz = -Math.sin(yaw);
            final double ux = -Math.sin(yaw) * Math.sin(pitch);
            final double uy = Math.cos(pitch);
            final double uz = Math.cos(yaw) * Math.sin(pitch);
            orbit.tx += (rx * offX - ux * offY) * perPixel;
            orbit.ty += (-uy * offY) * perPixel;
            orbit.tz += (rz * offX - uz * offY) * perPixel;
            if (scale > 1e-6) {
                orbit.distance *= Math.sqrt(Math.max(0.5, Math.min(2, scale)));
            }
        }
        orbit.update();
    }

    /** Eye position of a {@link TrackView3DCamera} (its basis fields are package-private; this keeps the maths in one place). */
    public static double orbitEye(TrackView3DCamera orbit, int axis) {
        final double ox = Math.cos(orbit.pitch) * Math.cos(orbit.yaw);
        final double oy = Math.sin(orbit.pitch);
        final double oz = Math.cos(orbit.pitch) * Math.sin(orbit.yaw);
        return switch (axis) {
            case 0 -> orbit.tx + orbit.distance * ox;
            case 1 -> orbit.ty + orbit.distance * oy;
            default -> orbit.tz + orbit.distance * oz;
        };
    }

    // ------------------------------------------------------------------ matrices

    /** out = a · b (column-major 4×4). */
    public static void multiply(double[] a, double[] b, double[] out) {
        final double[] t = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + r] * b[c * 4 + k];
                }
                t[c * 4 + r] = sum;
            }
        }
        System.arraycopy(t, 0, out, 0, 16);
    }

    /** General 4×4 inverse (Gauss–Jordan, partial pivoting). False if singular. */
    public static boolean invert(double[] m, double[] out) {
        final double[][] a = new double[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                a[r][c] = m[c * 4 + r];
            }
            a[r][4 + r] = 1;
        }
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            for (int r = col + 1; r < 4; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-14) {
                return false;
            }
            final double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            final double inv = 1 / a[col][col];
            for (int c = 0; c < 8; c++) {
                a[col][c] *= inv;
            }
            for (int r = 0; r < 4; r++) {
                if (r != col && a[r][col] != 0) {
                    final double f = a[r][col];
                    for (int c = 0; c < 8; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                out[c * 4 + r] = a[r][4 + c];
            }
        }
        return true;
    }

    /**
     * Fallback view matrix (column-major) for a yaw/pitch camera, the same as Minecraft's
     * {@code Rx(pitch) · Ry(yaw + 180)} — used before the first real frame was captured.
     */
    public static double[] viewMatrix(double yawDegrees, double pitchDegrees) {
        final double p = Math.toRadians(pitchDegrees);
        final double y = Math.toRadians(yawDegrees + 180);
        final double cp = Math.cos(p);
        final double sp = Math.sin(p);
        final double cy = Math.cos(y);
        final double sy = Math.sin(y);
        // Rx (column-major)
        final double[] rx = {1, 0, 0, 0, 0, cp, sp, 0, 0, -sp, cp, 0, 0, 0, 0, 1};
        // Ry (column-major)
        final double[] ry = {cy, 0, -sy, 0, 0, 1, 0, 0, sy, 0, cy, 0, 0, 0, 0, 1};
        final double[] out = new double[16];
        multiply(rx, ry, out);
        return out;
    }

    /** OpenGL perspective (column-major), like JOML {@code setPerspective(fovY, aspect, near, far)}. */
    public static double[] perspective(double fovY, double aspect, double near, double far) {
        final double h = 1 / Math.tan(fovY / 2);
        final double[] m = new double[16];
        m[0] = h / aspect;
        m[5] = h;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        return m;
    }
}
