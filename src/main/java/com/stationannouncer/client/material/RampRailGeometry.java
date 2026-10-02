package com.stationannouncer.client.material;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.material.RampRailBlock;
import com.stationannouncer.material.RampRailBlock.Shape;
import com.stationannouncer.material.RampRailBlock.Style;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ramp rail quads ({@link RampRailBlock}). Everything is authored in the
 * NORTH frame — uphill toward z = 0, the rail on the LEFT edge (x =
 * {@link RampRailBlock#EDGE}) — then mirrored for RIGHT and turned to FACING.
 * Heights come from {@link RampRailBlock#surface}, so a rail rises exactly
 * with the ramp segment it measures from and meets its neighbours' rails at
 * the block boundary (both ends of a 1:12 segment are shared heights).
 *
 * <p>Tubes are octagons whose cross-section stays VERTICAL as they climb
 * (sheared, not tilted): at 4.76° that is invisible, and it makes two
 * neighbours' tube ends identical polygons, so joints need no end faces and
 * nothing is ever coplanar across them. Corner and end fittings are slightly
 * fatter elbows (the turnstile tubing's cast fittings); every leg stops at a
 * fitting or post face rather than overlapping the leg it turns into.</p>
 */
@Environment(EnvType.CLIENT)
public final class RampRailGeometry implements ShapeModel.Geometry {
    private static final ShapeModel.Tex STEEL = new ShapeModel.Tex.Fixed("steel");
    private static final ShapeModel.Tex GLASS = new ShapeModel.Tex.Fixed("glass");
    private static final float COS_22 = (float) Math.cos(Math.toRadians(22.5));
    /** Leg ends stop this far from a fitting / post centre. */
    private static final float GAP = 1f;

    private final Style style;

    public RampRailGeometry(Style style) {
        this.style = style;
    }

    public Map<String, Identifier> textures() {
        return switch (style) {
            case GLASS -> Map.of("steel", StationAnnouncer.id("block/ramp_rail_steel"),
                    "glass", StationAnnouncer.id("block/ramp_rail_glass"));
            case PICKETS -> Map.of("steel", StationAnnouncer.id("block/ramp_rail_steel"));
            default -> Map.of("steel", StationAnnouncer.id("block/handrail_steel"));
        };
    }

    @Override
    public List<ShapeModel.Face> faces(BlockState state) {
        List<ShapeModel.Face> local = new ArrayList<>();
        Ctx ctx = new Ctx(state, local);
        if (style == Style.DOUBLE) {
            buildDouble(ctx);
        } else {
            buildLine(ctx, RampRailBlock.EDGE);
        }
        boolean right = state.get(RampRailBlock.RIGHT);
        int y = Geo.yFor(state.get(RampRailBlock.FACING));
        List<ShapeModel.Face> out = new ArrayList<>(local.size());
        for (ShapeModel.Face face : local) {
            out.add(Geo.rotate(right ? mirror(face) : face, 0, y));
        }
        return out;
    }

    // ------------------------------------------------------------ layout

    /** One rail line at x = cx: the selected shape's legs, posts, fittings and ends. */
    private void buildLine(Ctx ctx, float cx) {
        Shape shape = ctx.state.get(RampRailBlock.SHAPE);
        switch (shape) {
            case FLAT, SLOPE -> {
                leg(ctx, cx, 0, 16, false, false, 0);
                midPost(ctx, cx, 8);
            }
            case START -> {
                float t = RampRailBlock.EXTENSION;
                leg(ctx, cx, 0, t - gap(), false, true, 0);
                terminal(ctx, cx, t);
            }
            case END -> {
                float t = 16 - RampRailBlock.EXTENSION;
                leg(ctx, cx, t + gap(), 16, true, false, 0);
                terminal(ctx, cx, t);
            }
            case CORNER_OUTER, CORNER_INNER -> {
                boolean outer = shape == Shape.CORNER_OUTER;
                float cz = outer ? cx : 16 - cx;
                leg(ctx, cx, cz + GAP, 16, true, false, 0);
                // the second leg, authored as a north-frame leg then turned onto the cross line
                if (outer) {
                    leg(ctx, cx, 0, 16 - cx - GAP, false, true, 90);
                } else {
                    leg(ctx, cx, 0, cx - GAP, false, true, 270);
                }
                corner(ctx, cx, cz);
            }
        }
    }

    /** The double (centre divider) rail: two lines, centre posts with a cross arm, U-ends. */
    private void buildDouble(Ctx ctx) {
        Shape shape = ctx.state.get(RampRailBlock.SHAPE);
        float a = 4f, b = 12f;
        switch (shape) {
            case FLAT, SLOPE -> {
                tube(ctx, a, 0, 16, 0, false, false, 1f, 0);
                tube(ctx, b, 0, 16, 0, false, false, 1f, 0);
                float c = ctx.rail(8);
                ctx.bar(3.3f, c - 1.4f, 8, 12.7f, c - 1.4f, 8, 0.6f, STEEL, true, true);
                ctx.vbar(8, 8, ctx.bot(8), c - 1.4f, 0.85f, STEEL, false, false);
                foot(ctx, 8, 8);
            }
            case START, END -> {
                boolean start = shape == Shape.START;
                float t = start ? RampRailBlock.EXTENSION : 16 - RampRailBlock.EXTENSION;
                for (float x : new float[]{a, b}) {
                    if (start) {
                        tube(ctx, x, 0, t - GAP, 0, false, true, 1f, 0);
                    } else {
                        tube(ctx, x, t + GAP, 16, 0, true, false, 1f, 0);
                    }
                    fitting(ctx, x, t);
                }
                float c = ctx.rail(t);
                ctx.bar(a + GAP, c, t, b - GAP, c, t, 1f, STEEL, true, true);
                ctx.vbar(8, t, ctx.bot(t), c - 0.5f, 0.85f, STEEL, false, false);
                foot(ctx, 8, t);
            }
            case CORNER_OUTER, CORNER_INNER -> {
                boolean outer = shape == Shape.CORNER_OUTER;
                for (float x : new float[]{a, b}) {
                    float cz = outer ? x : 16 - x;
                    tube(ctx, x, cz + GAP, 16, 0, true, false, 1f, 0);
                    if (outer) {
                        tube(ctx, x, 0, 16 - x - GAP, 0, false, true, 1f, 90);
                    } else {
                        tube(ctx, x, 0, x - GAP, 0, false, true, 1f, 270);
                    }
                    fitting(ctx, x, cz);
                }
            }
        }
    }

    private float gap() {
        return style == Style.FLOATING ? 0f : GAP;
    }

    // ------------------------------------------------------------ per-style pieces

    /** A straight stretch of the line at x = cx over north-frame z [za, zb], turned by {@code rot}. */
    private void leg(Ctx ctx, float cx, float za, float zb, boolean capA, boolean capB, int rot) {
        Ctx c = ctx.turned(rot);
        switch (style) {
            case STANDING -> {
                tube(c, cx, za, zb, 0, capA, capB, 1f, 0);
                tube(c, cx, za, zb, 7f - RampRailBlock.RAIL_HEIGHT, capA, capB, 0.7f, 0);
            }
            case FLOATING -> tube(c, cx, za, zb, 0, capA, capB, 1f, 0);
            case WALL -> {
                tube(c, cx, za, zb, 0, capA, capB, 1f, 0);
                for (float z : new float[]{4, 12}) {
                    if (z > za + 1.5f && z < zb - 1.5f) {
                        bracket(c, cx, z);
                    }
                }
            }
            case GLASS -> glassRun(c, cx, za, zb);
            case PICKETS -> picketRun(c, cx, za, zb);
            default -> {
            }
        }
        c.flush();
    }

    /** Octagonal tube along the line, dy below/above the rail centre. */
    private static void tube(Ctx ctx, float cx, float za, float zb, float dy, boolean capA, boolean capB, float r, int rot) {
        Ctx c = ctx.turned(rot);
        c.bar(cx, c.rail(za) + dy, za, cx, c.rail(zb) + dy, zb, r, STEEL, capA, capB);
        c.flush();
    }

    /** Mid-run support of a straight piece. */
    private void midPost(Ctx ctx, float cx, float z) {
        switch (style) {
            case STANDING -> {
                ctx.vbar(cx, z, ctx.bot(z), ctx.rail(z), 0.85f, STEEL, false, false);
                foot(ctx, cx, z);
            }
            case PICKETS -> squarePost(ctx, cx, z, 0.9f, ctx.rail(z) + 1.1f);
            default -> {
            }
        }
    }

    /** End of a run (START / END): the ADA extension finishes in a post or a return to the wall. */
    private void terminal(Ctx ctx, float cx, float t) {
        float c = ctx.rail(t);
        switch (style) {
            case STANDING -> {
                fitting(ctx, cx, t);
                ctx.vbar(cx, t, ctx.bot(t), c - 1.45f, 1f, STEEL, false, false);
                foot(ctx, cx, t);
            }
            case WALL -> {
                fitting(ctx, cx, t);
                ctx.bar(0.3f, c, t, cx - 1f, c, t, 1f, STEEL, false, false);
                ctx.sheared(0, 0.5f, t - 1.4f, t + 1.4f, c - 1.6f, c - 1.6f, c + 1.6f, c + 1.6f, STEEL,
                        false, true, true, true, true, true);
            }
            case GLASS, PICKETS -> squarePost(ctx, cx, t, 1f, c + 1.3f);
            default -> {
            }
        }
    }

    private void corner(Ctx ctx, float cx, float cz) {
        switch (style) {
            case STANDING -> {
                fitting(ctx, cx, cz);
                ctx.vbar(cx, cz, ctx.bot(cz), ctx.rail(cz) - 1.45f, 1f, STEEL, false, false);
                foot(ctx, cx, cz);
            }
            case WALL, FLOATING -> fitting(ctx, cx, cz);
            case GLASS, PICKETS -> squarePost(ctx, cx, cz, 1f, ctx.rail(cz) + 1.3f);
            default -> {
            }
        }
    }

    /** Cast elbow / end fitting around the rail centre at (x, z). */
    private static void fitting(Ctx ctx, float x, float z) {
        float c = ctx.rail(z);
        ctx.vbar(x, z, c - 1.45f, c + 1.45f, 1.45f, STEEL, true, true);
    }

    /** Base plate under a post (none when the post stands beside the ramp on the cell floor — then flat). */
    private static void foot(Ctx ctx, float x, float z) {
        float s = 1.8f;
        float b0 = ctx.bot(z - s), b1 = ctx.bot(z + s);
        ctx.sheared(x - s, x + s, z - s, z + s, b0, b1, b0 + 0.6f, b1 + 0.6f, STEEL,
                true, true, true, true, true, false);
    }

    /** Wall bracket: plate on the wall (x 0), arm under the rail, stub up into it. */
    private static void bracket(Ctx ctx, float cx, float z) {
        float c = ctx.rail(z);
        float arm = c - 1.9f;
        ctx.sheared(0, 0.5f, z - 1.2f, z + 1.2f, c - 3.2f, c - 3.2f, c - 0.6f, c - 0.6f, STEEL,
                false, true, true, true, true, true);
        ctx.bar(0.3f, arm, z, cx, arm, z, 0.45f, STEEL, false, true);
        ctx.vbar(cx, z, arm, c - 0.5f, 0.45f, STEEL, false, false);
    }

    private static void squarePost(Ctx ctx, float cx, float z, float half, float top) {
        float b0 = ctx.bot(z - half), b1 = ctx.bot(z + half);
        ctx.sheared(cx - half, cx + half, z - half, z + half, b0, b1, top, top, STEEL,
                true, true, true, true, true, false);
        float capHalf = half + 0.25f;
        ctx.sheared(cx - capHalf, cx + capHalf, z - capHalf, z + capHalf, top, top, top + 0.45f, top + 0.45f, STEEL,
                true, true, true, true, true, true);
    }

    /** MSD glass: base shoe, glass panel, flat top rail (the handrail). No end faces — every leg end is a joint or a post. */
    private static void glassRun(Ctx ctx, float cx, float za, float zb) {
        float ba = ctx.bot(za), bb = ctx.bot(zb), ca = ctx.rail(za), cb = ctx.rail(zb);
        ctx.sheared(cx - 0.8f, cx + 0.8f, za, zb, ba, bb, ba + 1.4f, bb + 1.4f, STEEL,
                true, true, false, false, true, false);
        ctx.sheared(cx - 0.25f, cx + 0.25f, za, zb, ba + 1.2f, bb + 1.2f, ca - 0.7f, cb - 0.7f, GLASS,
                true, true, false, false, false, false);
        ctx.sheared(cx - 0.8f, cx + 0.8f, za, zb, ca - 1f, cb - 1f, ca + 1f, cb + 1f, STEEL,
                true, true, false, false, true, true);
    }

    /** MSD pickets: bottom rail, mid rail, flat top rail, pickets on a 4 px pitch (clear of posts). */
    private void picketRun(Ctx ctx, float cx, float za, float zb) {
        float ba = ctx.bot(za), bb = ctx.bot(zb), ca = ctx.rail(za), cb = ctx.rail(zb);
        ctx.sheared(cx - 0.5f, cx + 0.5f, za, zb, ba + 1.5f, bb + 1.5f, ba + 2.5f, bb + 2.5f, STEEL,
                true, true, false, false, true, true);
        ctx.sheared(cx - 0.5f, cx + 0.5f, za, zb, ca - 2.6f, cb - 2.6f, ca - 1.6f, cb - 1.6f, STEEL,
                true, true, false, false, true, true);
        ctx.sheared(cx - 0.8f, cx + 0.8f, za, zb, ca - 0.8f, cb - 0.8f, ca + 0.8f, cb + 0.8f, STEEL,
                true, true, false, false, true, true);
        boolean midPost = ctx.rot == 0 && (ctx.state.get(RampRailBlock.SHAPE) == Shape.FLAT
                || ctx.state.get(RampRailBlock.SHAPE) == Shape.SLOPE);
        for (float z = 2; z < 16; z += 4) {
            if (z < za + 0.8f || z > zb - 0.8f || (midPost && Math.abs(z - 8) < 1.5f)) {
                continue;
            }
            float b = ctx.bot(z) + 2.2f, t = ctx.rail(z) - 1.8f;
            ctx.sheared(cx - 0.3f, cx + 0.3f, z - 0.3f, z + 0.3f, b, b, t, t, STEEL,
                    true, true, true, true, false, false);
        }
    }

    // ------------------------------------------------------------ primitives

    /** Build context: the state's heights plus a local turn (corner legs) applied on flush. */
    private static final class Ctx {
        final BlockState state;
        final List<ShapeModel.Face> sink;
        final int rot;
        final List<ShapeModel.Face> faces;

        Ctx(BlockState state, List<ShapeModel.Face> sink) {
            this(state, sink, 0);
        }

        private Ctx(BlockState state, List<ShapeModel.Face> sink, int rot) {
            this.state = state;
            this.sink = sink;
            this.rot = rot;
            this.faces = rot == 0 ? sink : new ArrayList<>();
        }

        Ctx turned(int degrees) {
            return degrees == 0 ? this : new Ctx(state, sink, degrees);
        }

        void flush() {
            if (faces != sink) {
                for (ShapeModel.Face face : faces) {
                    sink.add(Geo.rotate(face, 0, rot));
                }
                faces.clear();
            }
        }

        float rail(float z) {
            return RampRailBlock.surface(state, z) + RampRailBlock.RAIL_HEIGHT;
        }

        float bot(float z) {
            return RampRailBlock.bottom(state, z);
        }

        /** Octagonal tube from A to B (mostly horizontal); the cross-section stays vertical. */
        void bar(float ax, float ay, float az, float bx, float by, float bz, float r, ShapeModel.Tex tex,
                 boolean capA, boolean capB) {
            float dx = bx - ax, dz = bz - az;
            float hl = (float) Math.sqrt(dx * dx + dz * dz);
            if (hl < 1e-4f) {
                return;
            }
            float px = dz / hl, pz = -dx / hl;
            float big = r / COS_22;
            float[][] pa = new float[8][], pb = new float[8][];
            for (int k = 0; k < 8; k++) {
                double th = Math.toRadians(-22.5 + 45 * k);
                float u = (float) (big * Math.cos(th)), v = (float) (big * Math.sin(th));
                pa[k] = new float[]{ax + u * px, ay + v, az + u * pz};
                pb[k] = new float[]{bx + u * px, by + v, bz + u * pz};
            }
            float len = (float) Math.sqrt(dx * dx + dz * dz + (by - ay) * (by - ay));
            float u0 = 0.5f, u1 = 0.5f + Math.min(15f, len);
            for (int k = 0; k < 8; k++) {
                int k1 = (k + 1) % 8;
                double mid = Math.toRadians(45 * k);
                float nu = (float) Math.cos(mid), nv = (float) Math.sin(mid);
                float nx = nu * px, ny = nv, nz = nu * pz;
                float[] band = band(nv);
                float[] v = {pa[k][0], pa[k][1], pa[k][2], pa[k1][0], pa[k1][1], pa[k1][2],
                        pb[k1][0], pb[k1][1], pb[k1][2], pb[k][0], pb[k][1], pb[k][2]};
                float[] uv = {u0, band[0], u0, band[1], u1, band[1], u1, band[0]};
                faces.add(Geo.quad(v, nx, ny, nz, dominant(nx, ny, nz), null, tex, uv));
            }
            float ux = dx / len, uy = (by - ay) / len, uz = dz / len;
            if (capA) {
                cap(pa, -ux, -uy, -uz, tex);
            }
            if (capB) {
                cap(pb, ux, uy, uz, tex);
            }
        }

        /** Vertical octagonal post at (x, z). */
        void vbar(float x, float z, float y0, float y1, float r, ShapeModel.Tex tex, boolean capBottom, boolean capTop) {
            if (y1 - y0 < 0.05f) {
                return;
            }
            float big = r / COS_22;
            float[][] lo = new float[8][], hi = new float[8][];
            for (int k = 0; k < 8; k++) {
                double th = Math.toRadians(-22.5 + 45 * k);
                float u = (float) (big * Math.cos(th)), w = (float) (big * Math.sin(th));
                lo[k] = new float[]{x + u, y0, z + w};
                hi[k] = new float[]{x + u, y1, z + w};
            }
            float u1 = 0.5f + Math.min(15f, y1 - y0);
            for (int k = 0; k < 8; k++) {
                int k1 = (k + 1) % 8;
                double mid = Math.toRadians(45 * k);
                float nx = (float) Math.cos(mid), nz = (float) Math.sin(mid);
                float[] v = {lo[k][0], lo[k][1], lo[k][2], lo[k1][0], lo[k1][1], lo[k1][2],
                        hi[k1][0], hi[k1][1], hi[k1][2], hi[k][0], hi[k][1], hi[k][2]};
                float[] uv = {0.5f, 5f, 0.5f, 9f, u1, 9f, u1, 5f};
                faces.add(Geo.quad(v, nx, 0, nz, dominant(nx, 0, nz), null, tex, uv));
            }
            if (capBottom) {
                cap(lo, 0, -1, 0, tex);
            }
            if (capTop) {
                cap(hi, 0, 1, 0, tex);
            }
        }

        /** Octagon end as three quads. */
        private void cap(float[][] p, float nx, float ny, float nz, ShapeModel.Tex tex) {
            int[][] quads = {{0, 1, 2, 3}, {0, 3, 4, 7}, {4, 5, 6, 7}};
            float[] uv = {6, 6, 6, 8, 8, 8, 8, 6};
            for (int[] q : quads) {
                float[] v = new float[12];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(p[q[i]], 0, v, i * 3, 3);
                }
                faces.add(Geo.quad(v, nx, ny, nz, dominant(nx, ny, nz), null, tex, uv));
            }
        }

        /**
         * Box over x [x0,x1] × z [z0,z1] whose bottom runs b0→b1 and top t0→t1
         * along z (a sloped plate / panel / rail). Flags pick the faces:
         * west, east, north (z0), south (z1), top, bottom.
         */
        void sheared(float x0, float x1, float z0, float z1, float b0, float b1, float t0, float t1, ShapeModel.Tex tex,
                     boolean west, boolean east, boolean north, boolean south, boolean top, boolean bottom) {
            float[] side = tex == GLASS ? new float[]{0.5f, 0.5f, 15.5f, 15.5f} : new float[]{0.5f, 5f, 15.5f, 9f};
            float[] small = {6f, 6f, 8f, 8f};
            if (west) {
                add(new float[]{x0, b0, z0, x0, b1, z1, x0, t1, z1, x0, t0, z0}, -1, 0, 0, side, tex);
            }
            if (east) {
                add(new float[]{x1, b0, z0, x1, t0, z0, x1, t1, z1, x1, b1, z1}, 1, 0, 0, side, tex);
            }
            if (north) {
                add(new float[]{x0, b0, z0, x0, t0, z0, x1, t0, z0, x1, b0, z0}, 0, 0, -1, small, tex);
            }
            if (south) {
                add(new float[]{x0, b1, z1, x1, b1, z1, x1, t1, z1, x0, t1, z1}, 0, 0, 1, small, tex);
            }
            float dz = z1 - z0;
            if (top) {
                float slope = (t1 - t0) / dz;
                add(new float[]{x0, t0, z0, x0, t1, z1, x1, t1, z1, x1, t0, z0}, 0, 1, -slope, side, tex);
            }
            if (bottom) {
                float slope = (b1 - b0) / dz;
                add(new float[]{x0, b0, z0, x1, b0, z0, x1, b1, z1, x0, b1, z1}, 0, -1, slope, side, tex);
            }
        }

        private void add(float[] v, float nx, float ny, float nz, float[] rect, ShapeModel.Tex tex) {
            float[] uv = {rect[0], rect[1], rect[0], rect[3], rect[2], rect[3], rect[2], rect[1]};
            faces.add(Geo.quad(v, nx, ny, nz, dominant(nx, ny, nz), null, tex, uv));
        }
    }

    /** Texture band of the handrail steel by facet orientation: lit crown, mid barrel, dark keel. */
    private static float[] band(float upness) {
        if (upness > 0.5f) {
            return new float[]{0.6f, 2f};
        }
        if (upness < -0.5f) {
            return new float[]{12f, 15f};
        }
        return new float[]{5f, 9f};
    }

    private static Direction dominant(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) {
            return ny >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return nx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** x → 16 − x (the RIGHT-side rail); ShapeModel re-winds from the flipped normal. */
    private static ShapeModel.Face mirror(ShapeModel.Face face) {
        float[] v = face.v().clone();
        for (int i = 0; i < 4; i++) {
            v[i * 3] = 16 - v[i * 3];
        }
        return new ShapeModel.Face(v, -face.nx(), face.ny(), face.nz(), flipX(face.face()),
                face.cull() == null ? null : flipX(face.cull()), face.tex(), face.uv());
    }

    private static Direction flipX(Direction d) {
        return d == Direction.EAST ? Direction.WEST : d == Direction.WEST ? Direction.EAST : d;
    }
}
