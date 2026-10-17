package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.PosterLayout.Surface;
import com.stationannouncer.mtr.sign.SignSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Random;

/**
 * Wear painted over an MTA sign panel (the sign editor's "Wear" setting,
 * ATMOSPHERE_PLAN.md §1 D): 1 weathered — dust settling toward the bottom
 * edge, a few scratches, rust weeping from the corner bolts; 2 worn — more of
 * it, a sun-faded patch and a sticker or two; 3 tagged — plus a marker tag
 * and more stickers. Seeded from the sign's own content, so the same sign
 * always wears the same way (and the editor preview matches the world).
 */
@Environment(EnvType.CLIENT)
final class SignWear {
    private static final int GRIME = 4;
    private static final int SCRATCH = 5;
    private static final int STICKER = 6;
    private static final int TAG = 7;

    private SignWear() {
    }

    static void paint(Surface s, SignSpec spec, float x0, float y0, float w, float h, boolean whitePanel) {
        paint(s, new Random(spec.rows().hashCode() * 31L + spec.style().ordinal() * 7L + (long) (w * 13 + h)),
                spec.panel().wear(), x0, y0, w, h, whitePanel);
    }

    /**
     * A hung service poster left up too long (the Maintenance Tool cycles it
     * on the frame): yellowed paper, a peeled corner, then the sign wear on top.
     */
    static void paintPoster(Surface s, long seed, int wear, float w, float h) {
        Random r = new Random(seed);
        s.rect(0, 0, w, h, (0x10 + 0x0E * wear) << 24 | 0xB8A060, GRIME - 1);
        // a corner peeled away, showing the dark plate behind
        float cut = Math.min(w, h) * (0.06f + 0.05f * wear) * (0.7f + r.nextFloat() * 0.6f);
        int corner = r.nextInt(4);
        float cx = corner % 2 == 0 ? 0 : w;
        float cy = corner < 2 ? 0 : h;
        float sx = corner % 2 == 0 ? 1 : -1;
        float sy = corner < 2 ? 1 : -1;
        s.poly(new float[]{cx, cx + sx * cut, cx}, new float[]{cy, cy, cy + sy * cut}, 0xFF2A2A2C, TAG + 1);
        // the curled flap
        s.poly(new float[]{cx + sx * cut, cx + sx * cut * 0.55f, cx},
                new float[]{cy, cy + sy * cut * 0.55f, cy + sy * cut}, 0xFFD8D2C2, TAG + 2);
        paint(s, r, wear, 0, 0, w, h, true);
    }

    private static void paint(Surface s, Random r, int wear, float x0, float y0, float w, float h, boolean whitePanel) {
        int dust = whitePanel ? 0x3A3026 : 0x9A8E7C;

        // dust settling on the lower part and along the bottom edge
        for (int i = 0; i < 6; i++) {
            float top = y0 + h * (0.5f + 0.085f * i);
            int alpha = Math.min(0x70, (5 + i * 4) * wear);
            s.rect(x0, top, x0 + w, y0 + h, alpha << 24 | dust, GRIME);
        }
        // a few faint, irregular grime runs from the top edge (never a regular row)
        int streaks = r.nextInt(wear + 1) + (wear >= 2 ? 1 : 0);
        for (int i = 0; i < streaks; i++) {
            float sx = x0 + w * (0.08f + r.nextFloat() * 0.84f);
            float sw = 0.8f + r.nextFloat() * 2.2f;
            float len = h * (0.15f + r.nextFloat() * 0.45f);
            int alpha = 0x0C + 0x05 * wear + r.nextInt(8);
            float drift = (r.nextFloat() - 0.5f) * sw;
            s.poly(new float[]{sx, sx + sw, sx + sw * 0.55f + drift, sx + sw * 0.45f + drift},
                    new float[]{y0, y0, y0 + len, y0 + len}, alpha << 24 | dust, GRIME);
        }
        // scratches, mostly near-horizontal (keys, bags, shoulders)
        int scratches = 2 + wear * 3 + r.nextInt(3);
        int scratch = whitePanel ? 0x606060 : 0xD8D8D8;
        for (int i = 0; i < scratches; i++) {
            float cx = x0 + r.nextFloat() * w;
            float cy = y0 + h * (0.2f + r.nextFloat() * 0.75f);
            float len = 3 + r.nextFloat() * Math.min(18, w * 0.3f);
            double angle = (r.nextFloat() - 0.5f) * 0.9f + (r.nextInt(5) == 0 ? Math.PI / 2 * 0.6 : 0);
            clipped(s, x0, y0, w, h, cx, cy, cx + (float) Math.cos(angle) * len, cy + (float) Math.sin(angle) * len,
                    0.22f + r.nextFloat() * 0.2f, (0x30 + r.nextInt(0x30)) << 24 | scratch, SCRATCH);
        }
        // rust weeping from the mounting bolts at the corners
        float inset = Math.min(2.5f, Math.min(w, h) * 0.08f);
        float[][] corners = {{x0 + inset, y0 + inset}, {x0 + w - inset, y0 + inset},
                {x0 + inset, y0 + h - inset}, {x0 + w - inset, y0 + h - inset}};
        for (float[] c : corners) {
            if (r.nextInt(4) >= wear + 1) {
                continue;
            }
            float rad = 0.5f + r.nextFloat() * 0.5f * wear;
            s.disc(c[0], c[1], rad, 0xA0 << 24 | 0x7A3E18, GRIME);
            float len = 2 + r.nextFloat() * 4 * wear;
            s.poly(new float[]{c[0] - rad * 0.6f, c[0] + rad * 0.6f, c[0] + 0.15f, c[0] - 0.15f},
                    new float[]{c[1], c[1], Math.min(y0 + h, c[1] + len), Math.min(y0 + h, c[1] + len)},
                    0x60 << 24 | 0x8A4A20, GRIME);
        }
        if (wear >= 2) {
            // a sun-faded / scrubbed patch
            float fx = x0 + w * (0.2f + r.nextFloat() * 0.6f);
            float fy = y0 + h * (0.3f + r.nextFloat() * 0.4f);
            s.disc(fx, fy, Math.min(w, h) * (0.25f + r.nextFloat() * 0.2f),
                    (whitePanel ? 0x10 : 0x12) << 24 | (whitePanel ? 0x8A7A50 : 0xFFFFFF), GRIME);
            // stickers
            int stickers = wear - 1 + r.nextInt(wear);
            for (int i = 0; i < stickers; i++) {
                sticker(s, r, x0, y0, w, h);
            }
        }
        if (wear >= 3) {
            tag(s, r, x0, y0, w, h);
        }
        // nothing may hang off the panel: the clip box for tag strokes is set in tag()
    }

    private static void sticker(Surface s, Random r, float x0, float y0, float w, float h) {
        float sw = 3 + r.nextFloat() * 4;
        float sh = 2 + r.nextFloat() * 2.5f;
        float cx = x0 + sw + r.nextFloat() * Math.max(1, w - 2 * sw);
        float cy = y0 + h * 0.35f + r.nextFloat() * h * 0.55f;
        double a = (r.nextFloat() - 0.5f) * 0.5f;
        int[] papers = {0xFFF2F0E8, 0xFFF5D547, 0xFF3B6FD0, 0xFFE8E8E8, 0xFFD94848};
        int paper = papers[r.nextInt(papers.length)];
        rotatedRect(s, cx, cy, sw, sh, a, paper, STICKER);
        int ink = (paper & 0xFFFFFF) == 0x3B6FD0 || (paper & 0xFFFFFF) == 0xD94848 ? 0xFFFFFFFF : 0xFF202020;
        // a printed band and two "lines of text"
        rotatedRect(s, cx, cy - sh * 0.28f, sw * 0.86f, sh * 0.18f, a, ink, STICKER + 1);
        rotatedRect(s, cx - sw * 0.1f, cy + sh * 0.12f, sw * 0.6f, sh * 0.08f, a, ink & 0x99FFFFFF, STICKER + 1);
        // a torn corner shows the panel through
        if (r.nextBoolean()) {
            float tx = cx + (float) Math.cos(a) * sw / 2;
            float ty = cy + (float) Math.sin(a) * sw / 2 + sh / 2;
            s.poly(new float[]{tx - sw * 0.25f, tx, tx}, new float[]{ty, ty, ty - sh * 0.35f}, 0xFF1A1A1A, STICKER + 1);
        }
    }

    /** A marker tag: a looping random-walk scrawl with a drip or two. */
    private static void tag(Surface s, Random r, float x0, float y0, float w, float h) {
        int[] inks = {0xE6C8C8D2, 0xE6E0408A, 0xE6202020, 0xE63BC4E8};
        int ink = inks[r.nextInt(inks.length)];
        float span = Math.min(w * 0.5f, 28);
        float sx = x0 + r.nextFloat() * Math.max(1, w - span);
        float base = y0 + h * (0.35f + r.nextFloat() * 0.35f);
        float px = sx;
        float py = base;
        float thick = Math.max(0.45f, Math.min(1.0f, h * 0.02f));
        int letters = 3 + r.nextInt(3);
        float letterW = span / letters;
        for (int l = 0; l < letters; l++) {
            int strokes = 3 + r.nextInt(3);
            for (int k = 0; k < strokes; k++) {
                float nx = sx + letterW * (l + r.nextFloat());
                float ny = base + (r.nextFloat() - 0.6f) * h * 0.28f;
                clipped(s, x0, y0, w, h, px, py, nx, ny, thick, ink, TAG);
                px = nx;
                py = ny;
            }
        }
        // underline swoosh
        clipped(s, x0, y0, w, h, sx, base + h * 0.12f, sx + span, base + h * 0.06f, thick * 0.8f, ink, TAG);
        for (int d = 0; d < 2; d++) {
            float dx = sx + r.nextFloat() * span;
            clipped(s, x0, y0, w, h, dx, base, dx + 0.1f, base + 2 + r.nextFloat() * 5, thick * 0.5f, ink, TAG);
        }
    }

    /** A stroke clipped to the panel box (Liang–Barsky), inset by its half width. */
    private static void clipped(Surface s, float x0, float y0, float w, float h,
                                float ax, float ay, float bx, float by, float width, int argb, int layer) {
        float m = width / 2 + 0.2f;
        float minX = x0 + m, maxX = x0 + w - m, minY = y0 + m, maxY = y0 + h - m;
        float dx = bx - ax, dy = by - ay;
        float t0 = 0, t1 = 1;
        float[] p = {-dx, dx, -dy, dy};
        float[] q = {ax - minX, maxX - ax, ay - minY, maxY - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return;
                }
            } else {
                float t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
            }
        }
        if (t0 < t1) {
            line(s, ax + dx * t0, ay + dy * t0, ax + dx * t1, ay + dy * t1, width, argb, layer);
        }
    }

    private static void line(Surface s, float ax, float ay, float bx, float by, float width, int argb, int layer) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        float nx = -dy / len * width / 2;
        float ny = dx / len * width / 2;
        s.poly(new float[]{ax + nx, bx + nx, bx - nx, ax - nx}, new float[]{ay + ny, by + ny, by - ny, ay - ny}, argb, layer);
    }

    private static void rotatedRect(Surface s, float cx, float cy, float w, float h, double a, int argb, int layer) {
        float c = (float) Math.cos(a);
        float sn = (float) Math.sin(a);
        float[] xs = new float[4];
        float[] ys = new float[4];
        float[][] k = {{-w / 2, -h / 2}, {w / 2, -h / 2}, {w / 2, h / 2}, {-w / 2, h / 2}};
        for (int i = 0; i < 4; i++) {
            xs[i] = cx + k[i][0] * c - k[i][1] * sn;
            ys[i] = cy + k[i][0] * sn + k[i][1] * c;
        }
        s.poly(xs, ys, argb, layer);
    }
}
