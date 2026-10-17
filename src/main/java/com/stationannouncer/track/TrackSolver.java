package com.stationannouncer.track;

import com.stationannouncer.track.TrackProfile.Piece;
import com.stationannouncer.track.TrackProfile.PieceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Makes a chain meet node B: adjusts the UNLOCKED piece values (lengths and arc radii) so the
 * chain ends at B's centre with B's heading — three equations, as many unknowns as are free.
 *
 * Method: damped minimum-norm Gauss-Newton. Each step is the smallest SCALED change of the free
 * values that removes the residual ({@code Δ = −S²Jᵀ(JS²Jᵀ + λI)⁻¹r}, a 3×3 solve however many
 * values are free), so with more than three free values the ones you did not touch move as
 * little as possible, relative to their size. Radii are solved as curvature (1/R) — a radius
 * running off to infinity is just a curvature going to zero, which keeps it well-behaved.
 */
public final class TrackSolver {
    private static final int MAX_ITERATIONS = 80;
    /** Spiral integration step inside the solver (the final build uses a finer one). */
    private static final double SOLVE_STEP = 0.5;
    private static final double MIN_CURVATURE = 1e-6;

    private TrackSolver() {
    }

    /** One adjustable value. kind 0 = length, 1 = radius (solved as curvature). */
    public record Param(int piece, int kind) {
        public static final int LENGTH = 0;
        public static final int RADIUS = 1;

        public int id() {
            return piece * 2 + kind;
        }
    }

    public record Result(TrackProfile profile, boolean closes, double positionError, double headingError, int freeCount, String message) {
    }

    /** The free values of a profile (unlocked and not in {@code pinned}, ids from {@link Param#id()}). */
    public static List<Param> freeParams(TrackProfile profile, Set<Integer> pinned) {
        final List<Param> params = new ArrayList<>();
        for (int i = 0; i < profile.pieces.size(); i++) {
            final Piece piece = profile.pieces.get(i);
            final Param length = new Param(i, Param.LENGTH);
            if (!piece.lockLength && !pinned.contains(length.id())) {
                params.add(length);
            }
            final Param radius = new Param(i, Param.RADIUS);
            if (piece.type == PieceType.ARC && !piece.lockRadius && !pinned.contains(radius.id())) {
                params.add(radius);
            }
        }
        return params;
    }

    public static Result solve(TrackProfile profile) {
        return solve(profile, Collections.emptySet());
    }

    /**
     * @param pinned value ids held fixed for THIS solve in addition to the locked ones — e.g. the
     *               value the user is dragging right now.
     */
    public static Result solve(TrackProfile input, Set<Integer> pinned) {
        final TrackProfile profile = input.copy();
        final List<Param> params = freeParams(profile, pinned);
        final double targetX = profile.bx + 0.5;
        final double targetZ = profile.bz + 0.5;
        double[] end = integrate(profile);
        final double targetHeading = profile.headingB + 2 * Math.PI * Math.round((end[2] - profile.headingB) / (2 * Math.PI));
        final double weight = Math.max(4, Math.min(100, profile.totalLength() / 3));

        double[] residual = residual(end, targetX, targetZ, targetHeading, weight);
        double cost = norm2(residual);
        double lambda = 1e-6;
        final int n = params.size();
        final boolean alreadyClosed = Math.hypot(end[0] - targetX, end[1] - targetZ) < 1e-7 && Math.abs(end[2] - targetHeading) < 1e-9;
        if (n > 0 && !alreadyClosed) {
            for (int iteration = 0; iteration < MAX_ITERATIONS && cost > 1e-14; iteration++) {
                final double[] q = new double[n];
                final double[] scale = new double[n];
                for (int i = 0; i < n; i++) {
                    q[i] = get(profile, params.get(i));
                    scale[i] = params.get(i).kind() == Param.LENGTH ? Math.max(1, Math.abs(q[i])) : Math.max(0.002, Math.abs(q[i]));
                }
                // Jacobian (3 × n), central differences
                final double[][] jacobian = new double[3][n];
                for (int i = 0; i < n; i++) {
                    final double h = scale[i] * 1e-6;
                    set(profile, params.get(i), q[i] + h);
                    final double[] plus = residual(integrate(profile), targetX, targetZ, targetHeading, weight);
                    set(profile, params.get(i), q[i] - h);
                    final double[] minus = residual(integrate(profile), targetX, targetZ, targetHeading, weight);
                    set(profile, params.get(i), q[i]);
                    for (int r = 0; r < 3; r++) {
                        jacobian[r][i] = (plus[r] - minus[r]) / (2 * h);
                    }
                }
                boolean improved = false;
                for (int attempt = 0; attempt < 12 && !improved; attempt++) {
                    final double[] step = minNormStep(jacobian, scale, residual, lambda);
                    for (int i = 0; i < n; i++) {
                        set(profile, params.get(i), q[i] + step[i]);
                    }
                    final double[] newEnd = integrate(profile);
                    final double[] newResidual = residual(newEnd, targetX, targetZ, targetHeading, weight);
                    final double newCost = norm2(newResidual);
                    if (newCost < cost) {
                        residual = newResidual;
                        cost = newCost;
                        lambda = Math.max(1e-9, lambda / 4);
                        improved = true;
                    } else {
                        for (int i = 0; i < n; i++) {
                            set(profile, params.get(i), q[i]);
                        }
                        lambda = Math.max(lambda * 8, 1e-6);
                    }
                }
                if (!improved) {
                    break;
                }
            }
        }

        end = integrate(profile);
        final double positionError = Math.hypot(end[0] - targetX, end[1] - targetZ);
        final double headingError = Math.abs(TrackPath.wrap(end[2] - profile.headingB));
        final boolean closes = positionError < 0.01 && headingError < 0.001;
        final String message;
        if (closes) {
            message = "";
        } else if (n < 3) {
            message = "Unlock at least " + (3 - n) + " more value" + (3 - n == 1 ? "" : "s") + " — the track misses node B by " + format(positionError) + " blocks";
        } else {
            message = "Can't reach node B with these locks (misses by " + format(positionError) + " blocks, " + format(Math.toDegrees(headingError)) + "°)";
        }
        return new Result(profile, closes, positionError, headingError, n, message);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Δ = −S² Jᵀ (J S² Jᵀ + λ I)⁻¹ r. */
    private static double[] minNormStep(double[][] jacobian, double[] scale, double[] residual, double lambda) {
        final int n = scale.length;
        final double[][] m = new double[3][3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += jacobian[a][i] * scale[i] * scale[i] * jacobian[b][i];
                }
                m[a][b] = sum;
            }
        }
        final double trace = m[0][0] + m[1][1] + m[2][2];
        for (int a = 0; a < 3; a++) {
            m[a][a] += lambda * Math.max(trace, 1e-9) + 1e-12;
        }
        final double[] y = solve3(m, residual);
        final double[] step = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int a = 0; a < 3; a++) {
                sum += jacobian[a][i] * y[a];
            }
            step[i] = -scale[i] * scale[i] * sum;
        }
        return step;
    }

    private static double[] solve3(double[][] matrix, double[] rhs) {
        final double[][] a = new double[3][4];
        for (int r = 0; r < 3; r++) {
            System.arraycopy(matrix[r], 0, a[r], 0, 3);
            a[r][3] = rhs[r];
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            final double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            if (Math.abs(a[col][col]) < 1e-300) {
                continue;
            }
            for (int r = 0; r < 3; r++) {
                if (r != col) {
                    final double f = a[r][col] / a[col][col];
                    for (int c = col; c < 4; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        final double[] x = new double[3];
        for (int r = 0; r < 3; r++) {
            x[r] = Math.abs(a[r][r]) < 1e-300 ? 0 : a[r][3] / a[r][r];
        }
        return x;
    }

    private static double[] residual(double[] end, double targetX, double targetZ, double targetHeading, double weight) {
        return new double[]{end[0] - targetX, end[1] - targetZ, (end[2] - targetHeading) * weight};
    }

    private static double norm2(double[] v) {
        return v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
    }

    private static double get(TrackProfile profile, Param param) {
        final Piece piece = profile.pieces.get(param.piece());
        return param.kind() == Param.LENGTH ? piece.length : 1 / Math.max(TrackProfile.MIN_RADIUS, piece.radius);
    }

    private static void set(TrackProfile profile, Param param, double value) {
        final Piece piece = profile.pieces.get(param.piece());
        if (param.kind() == Param.LENGTH) {
            piece.length = Math.max(TrackProfile.MIN_LENGTH, Math.min(TrackProfile.MAX_CHAIN_LENGTH, value));
        } else {
            final double curvature = Math.max(MIN_CURVATURE, Math.min(1 / TrackProfile.MIN_RADIUS, value));
            piece.radius = 1 / curvature;
        }
    }

    /** End {x, z, heading} of the chain (fast; coarser spiral integration than {@link TrackPath}). */
    public static double[] integrate(TrackProfile profile) {
        final List<Piece> pieces = profile.pieces;
        final int count = pieces.size();
        final double[] k0 = new double[count];
        final double[] k1 = new double[count];
        TrackPath.curvatures(pieces, k0, k1);
        double x = profile.ax + 0.5;
        double z = profile.az + 0.5;
        double theta = profile.headingA;
        for (int i = 0; i < count; i++) {
            final Piece piece = pieces.get(i);
            final double length = Math.max(0, piece.length);
            if (piece.type == PieceType.SPIRAL && length > 0) {
                final int steps = 2 * Math.max(2, (int) Math.ceil(length / SOLVE_STEP / 2));
                final double h = length / steps;
                double sumX = 0;
                double sumZ = 0;
                for (int j = 0; j <= steps; j++) {
                    final double w = j == 0 || j == steps ? 1 : (j % 2 == 1 ? 4 : 2);
                    final double heading = TrackPath.spiralHeading(theta, k0[i], k1[i], length, j * h);
                    sumX += w * Math.cos(heading);
                    sumZ += w * Math.sin(heading);
                }
                x += sumX * h / 3;
                z += sumZ * h / 3;
            } else {
                final double[] end = TrackPath.pieceEnd(piece.type, x, z, theta, k0[i], length);
                x = end[0];
                z = end[1];
            }
            theta += (k0[i] + k1[i]) / 2 * length;
        }
        return new double[]{x, z, theta};
    }
}
