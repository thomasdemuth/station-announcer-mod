package com.stationannouncer.client.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.material.Geo;
import com.stationannouncer.client.material.ShapeModel;
import com.stationannouncer.mtr.CurvedPlatformEdgeBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Geometry of {@link CurvedPlatformEdgeBlock}: the concrete body behind the cut
 * line, its slanted cut face toward the track, and the raised tactile strip
 * following the cut (the same texture and 0.5 px rise as {@code platform_edge},
 * sampled ALONG the cut so the dots run with the edge).
 *
 * <p>North frame (track at z = 0), line z = d(x) from d(0) = a to d(16) = b.
 * The body is the block square clipped to z ≥ d(x); the strip is the band
 * d(x) ≤ z ≤ d(x) + 8 clipped to the block. Strip edges on the block boundary
 * get no wall — the neighbouring cell continues the strip there.</p>
 */
public final class CurvedEdgeGeometry implements ShapeModel.Geometry {
    public static final Map<String, Identifier> TEXTURES = Map.of(
            "top", StationAnnouncer.id("block/platform_concrete_floor_top_c0_0"),
            "side", StationAnnouncer.id("block/platform_concrete_floor_side"),
            "strip", StationAnnouncer.id("block/platform_edge_strip"));
    /** The curved gap fillers add the filler slot and deck nosing (per style). */
    public static Map<String, Identifier> fillerTextures(boolean loop) {
        return Map.of(
                "top", StationAnnouncer.id("block/platform_concrete_floor_top_c0_0"),
                "side", StationAnnouncer.id("block/platform_concrete_floor_side"),
                "strip", StationAnnouncer.id("block/platform_edge_strip"),
                "slot", StationAnnouncer.id("block/gap_filler_slot"),
                "fascia", StationAnnouncer.id("block/gap_filler_fascia" + (loop ? "_loop" : "")));
    }
    private static final ShapeModel.Tex TOP = new ShapeModel.Tex.Fixed("top");
    private static final ShapeModel.Tex SIDE = new ShapeModel.Tex.Fixed("side");
    private static final ShapeModel.Tex STRIP_TEX = new ShapeModel.Tex.Fixed("strip");
    private static final ShapeModel.Tex SLOT = new ShapeModel.Tex.Fixed("slot");
    private static final ShapeModel.Tex FASCIA = new ShapeModel.Tex.Fixed("fascia");
    /** Deck underside = top of the gap filler slot (px); the plate rides just below it. */
    private static final float DECK = 15f;

    /** 0 = a plain curved edge; else the floor of the gap filler slot (12 Union Square, 10 South Ferry). */
    private final float slotFloor;

    public CurvedEdgeGeometry() {
        this(0f);
    }

    public CurvedEdgeGeometry(float slotFloor) {
        this.slotFloor = slotFloor;
    }
    private static final float RISE = 0.5f;
    private static final float EPS = 0.001f;

    @Override
    public List<ShapeModel.Face> faces(BlockState state) {
        float a = CurvedPlatformEdgeBlock.depthPx(state.get(CurvedPlatformEdgeBlock.CUT_A));
        float b = CurvedPlatformEdgeBlock.depthPx(state.get(CurvedPlatformEdgeBlock.CUT_B));
        List<ShapeModel.Face> faces = new ArrayList<>();
        if (slotFloor > 0) {
            slottedBody(faces, a, b, slotFloor);
        } else {
            body(faces, a, b);
        }
        strip(faces, a, b);
        int y = Geo.yFor(state.get(CurvedPlatformEdgeBlock.TRACK_SIDE));
        List<ShapeModel.Face> out = new ArrayList<>(faces.size());
        for (ShapeModel.Face face : faces) {
            out.add(Geo.rotate(face, 0, y));
        }
        return out;
    }

    // ------------------------------------------------------------------ body

    private static void body(List<ShapeModel.Face> faces, float a, float b) {
        List<float[]> poly = clip(square(), a, b, false);
        if (poly.size() < 3) {
            return;
        }
        polygon(faces, poly, 16f, Direction.UP, Direction.UP, TOP, null, 0, 0);
        polygon(faces, poly, 0f, Direction.DOWN, Direction.DOWN, SIDE, null, 0, 0);
        float[] centre = centroid(poly);
        for (int i = 0; i < poly.size(); i++) {
            float[] p = poly.get(i);
            float[] q = poly.get((i + 1) % poly.size());
            if (Math.abs(p[0] - q[0]) < EPS && Math.abs(p[1] - q[1]) < EPS) {
                continue;
            }
            Direction boundary = boundary(p, q);
            float[] n = outward(p, q, centre);
            float[] v = {p[0], 0, p[1], q[0], 0, q[1], q[0], 16, q[1], p[0], 16, p[1]};
            Direction face = boundary != null ? boundary : Direction.NORTH; // the cut faces the track
            faces.add(Geo.quad(v, n[0], 0, n[1], face, boundary, SIDE, null));
        }
    }

    /**
     * The gap filler's body: the same prism, but its cut face is only a base
     * (0..slotFloor) and a deck nosing (15..16) with the plate's slot between —
     * slot floor and ceiling in the slot texture, inner cheeks at the block's
     * two ends and a back wall, so neighbouring fillers' slots never show through.
     */
    private static void slottedBody(List<ShapeModel.Face> faces, float a, float b, float floor) {
        List<float[]> poly = clip(square(), a, b, false);
        if (poly.size() < 3) {
            return;
        }
        polygon(faces, poly, 16f, Direction.UP, Direction.UP, TOP, null, 0, 0);
        polygon(faces, poly, 0f, Direction.DOWN, Direction.DOWN, SIDE, null, 0, 0);
        polygon(faces, poly, floor, Direction.UP, null, SLOT, null, 0, 0);       // slot floor
        polygon(faces, poly, DECK, Direction.DOWN, null, SLOT, null, 0, 0);      // deck underside
        float[] centre = centroid(poly);
        for (int i = 0; i < poly.size(); i++) {
            float[] p = poly.get(i);
            float[] q = poly.get((i + 1) % poly.size());
            if (Math.abs(p[0] - q[0]) < EPS && Math.abs(p[1] - q[1]) < EPS) {
                continue;
            }
            Direction boundary = boundary(p, q);
            float[] n = outward(p, q, centre);
            if (boundary != null) {
                // Block sides stay whole: they are the slot's cheeks / back seen from outside.
                faces.add(Geo.quad(new float[]{p[0], 0, p[1], q[0], 0, q[1], q[0], 16, q[1], p[0], 16, p[1]},
                        n[0], 0, n[1], boundary, boundary, SIDE, null));
                continue;
            }
            // The cut: a base below the slot and the deck's nosing above it.
            faces.add(Geo.quad(new float[]{p[0], 0, p[1], q[0], 0, q[1], q[0], floor, q[1], p[0], floor, p[1]},
                    n[0], 0, n[1], Direction.NORTH, null, SIDE, null));
            faces.add(Geo.quad(new float[]{p[0], DECK, p[1], q[0], DECK, q[1], q[0], 16, q[1], p[0], 16, p[1]},
                    n[0], 0, n[1], Direction.NORTH, null, FASCIA, null));
        }
        // Inner cheeks (0.25 px in from each end) and the back wall, facing into the slot.
        float z0 = Math.max(0f, a + (b - a) * 0.25f / 16f);
        float z1 = Math.max(0f, a + (b - a) * 15.75f / 16f);
        if (z0 < 15.8f) {
            faces.add(Geo.quad(new float[]{0.25f, floor, z0, 0.25f, floor, 15.8f, 0.25f, DECK, 15.8f, 0.25f, DECK, z0},
                    1, 0, 0, Direction.EAST, null, SLOT, null));
        }
        if (z1 < 15.8f) {
            faces.add(Geo.quad(new float[]{15.75f, floor, z1, 15.75f, floor, 15.8f, 15.75f, DECK, 15.8f, 15.75f, DECK, z1},
                    -1, 0, 0, Direction.WEST, null, SLOT, null));
        }
        faces.add(Geo.quad(new float[]{0.25f, floor, 15.8f, 15.75f, floor, 15.8f, 15.75f, DECK, 15.8f, 0.25f, DECK, 15.8f},
                0, 0, -1, Direction.NORTH, null, SLOT, null));
    }

    // ----------------------------------------------------------------- strip

    private static void strip(List<ShapeModel.Face> faces, float a, float b) {
        List<float[]> band = clip(clip(square(), a, b, false), a + CurvedPlatformEdgeBlock.STRIP,
                b + CurvedPlatformEdgeBlock.STRIP, true);
        if (band.size() < 3) {
            return;
        }
        float top = 16f + RISE;
        // Top: u along the block, v = distance behind the cut mapped onto the strip
        // texture's 10 tactile rows (platform_edge_strip: rows 0..10 = the strip top).
        polygon(faces, band, top, Direction.UP, null, STRIP_TEX, new float[]{a, b}, 10f / CurvedPlatformEdgeBlock.STRIP, 0);
        float[] centre = centroid(band);
        for (int i = 0; i < band.size(); i++) {
            float[] p = band.get(i);
            float[] q = band.get((i + 1) % band.size());
            if (boundary(p, q) != null || (Math.abs(p[0] - q[0]) < EPS && Math.abs(p[1] - q[1]) < EPS)) {
                continue; // the neighbouring cell carries the strip on
            }
            float[] n = outward(p, q, centre);
            float len = (float) Math.hypot(q[0] - p[0], q[1] - p[1]);
            float[] v = {p[0], 16f, p[1], q[0], 16f, q[1], q[0], top, q[1], p[0], top, p[1]};
            // The strip's 0.5 px edge: the thin row under the tactile field (v 10..10.5).
            float[] uv = {0, 10.5f, Math.min(16f, len), 10.5f, Math.min(16f, len), 10f, 0, 10f};
            faces.add(Geo.quad(v, n[0], 0, n[1], n[1] <= 0 ? Direction.NORTH : Direction.SOUTH, null, STRIP_TEX, uv));
        }
    }

    // --------------------------------------------------------------- helpers

    private static List<float[]> square() {
        List<float[]> sq = new ArrayList<>(4);
        sq.add(new float[]{0, 0});
        sq.add(new float[]{16, 0});
        sq.add(new float[]{16, 16});
        sq.add(new float[]{0, 16});
        return sq;
    }

    /**
     * Sutherland–Hodgman against the line z = d(x) (d(0) = a, d(16) = b):
     * keeps z ≥ d(x), or z ≤ d(x) when {@code below}.
     */
    private static List<float[]> clip(List<float[]> poly, float a, float b, boolean below) {
        List<float[]> out = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            float[] p = poly.get(i);
            float[] q = poly.get((i + 1) % n);
            float fp = side(p, a, b, below);
            float fq = side(q, a, b, below);
            if (fp >= 0) {
                out.add(p);
            }
            if ((fp >= 0) != (fq >= 0)) {
                float t = fp / (fp - fq);
                out.add(new float[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
            }
        }
        return out;
    }

    private static float side(float[] p, float a, float b, boolean below) {
        float d = a + (b - a) * p[0] / 16f;
        return below ? d - p[1] : p[1] - d;
    }

    /** Which block side an edge lies on, or null for an inner (cut) edge. */
    private static Direction boundary(float[] p, float[] q) {
        if (p[0] < EPS && q[0] < EPS) {
            return Direction.WEST;
        }
        if (p[0] > 16 - EPS && q[0] > 16 - EPS) {
            return Direction.EAST;
        }
        if (p[1] < EPS && q[1] < EPS) {
            return Direction.NORTH;
        }
        if (p[1] > 16 - EPS && q[1] > 16 - EPS) {
            return Direction.SOUTH;
        }
        return null;
    }

    private static float[] centroid(List<float[]> poly) {
        float x = 0, z = 0;
        for (float[] p : poly) {
            x += p[0];
            z += p[1];
        }
        return new float[]{x / poly.size(), z / poly.size()};
    }

    /** Unit horizontal normal of edge p→q pointing away from the polygon's centre. */
    private static float[] outward(float[] p, float[] q, float[] centre) {
        float ex = q[0] - p[0], ez = q[1] - p[1];
        float nx = ez, nz = -ex;
        float mx = (p[0] + q[0]) / 2 - centre[0], mz = (p[1] + q[1]) / 2 - centre[1];
        if (nx * mx + nz * mz < 0) {
            nx = -nx;
            nz = -nz;
        }
        float len = (float) Math.hypot(nx, nz);
        return len < 1e-6f ? new float[]{0, -1} : new float[]{nx / len, nz / len};
    }

    /**
     * A horizontal polygon at height y, fanned into quads (a 5-gon is a quad plus
     * a triangle). {@code stripLine} = {a, b} makes the uv strip-relative: u = x,
     * v = (z − d(x)) × vScale.
     */
    private static void polygon(List<ShapeModel.Face> faces, List<float[]> poly, float y, Direction face,
                                Direction cull, ShapeModel.Tex tex, float[] stripLine, float vScale, int unused) {
        for (int i = 1; i + 1 < poly.size(); i += 2) {
            float[] p0 = poly.get(0);
            float[] p1 = poly.get(i);
            float[] p2 = poly.get(i + 1);
            float[] p3 = i + 2 < poly.size() ? poly.get(i + 2) : p2;
            float[] v = {p0[0], y, p0[1], p1[0], y, p1[1], p2[0], y, p2[1], p3[0], y, p3[1]};
            float[] uv = null;
            if (stripLine != null) {
                uv = new float[8];
                float[][] pts = {p0, p1, p2, p3};
                for (int k = 0; k < 4; k++) {
                    float d = stripLine[0] + (stripLine[1] - stripLine[0]) * pts[k][0] / 16f;
                    uv[k * 2] = pts[k][0];
                    uv[k * 2 + 1] = Math.max(0f, Math.min(10f, (pts[k][1] - d) * vScale));
                }
            }
            faces.add(Geo.quad(v, face.getOffsetX(), face.getOffsetY(), face.getOffsetZ(), face, cull, tex, uv));
        }
    }
}
