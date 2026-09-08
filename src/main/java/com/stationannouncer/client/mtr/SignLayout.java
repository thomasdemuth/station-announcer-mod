package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.PosterLayout;
import com.stationannouncer.client.mtraddon.PosterLayout.Surface;
import com.stationannouncer.mtr.sign.SignSpec;
import com.stationannouncer.mtr.sign.SignSpec.Align;
import com.stationannouncer.mtr.sign.SignSpec.Row;
import com.stationannouncer.mtr.sign.SignSpec.Style;
import com.stationannouncer.mtr.sign.SignSpec.Tile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out and paints one MTA-style sign face onto a {@link Surface}, so the
 * editor preview ({@code GuiSurface}) and the block ({@code WorldSurface}) are
 * the same picture. Coordinates are canvas units: 64 per block, the panel
 * {@code width} x {@code height} with its origin top-left.
 *
 * <p>The Vignelli grid, reduced to what a block can carry: the panel splits
 * into equal-height ROWS; each row is a run of TILES. Arrows pinned
 * {@code left} / {@code right} sit on the panel edge they point toward, and
 * the rest of the row flows from the left (or centres — station plates). When
 * a row is too wide for the panel its text and bullets shrink together until
 * it fits, the way a sign shop condenses a long station name.</p>
 *
 * <p>Text is MTR's Noto Sans (the {@code mtr:mtr} font) — the closest thing to
 * Helvetica Medium already on every client. Its glyphs are taller than
 * vanilla's 8-unit box at the same painter size, so every text size here is
 * expressed as a CAP HEIGHT and converted through {@link #CAP_PER_SIZE} /
 * {@link #TOP_PER_SIZE}, both measured on the rig.</p>
 */
@Environment(EnvType.CLIENT)
public final class SignLayout {
    /** MTR's bundled Noto Sans SemiBold, declared as a normal font resource. */
    public static final Identifier FONT = new Identifier("mtr", "mtr");

    /**
     * Cap height of the MTR font in canvas units per unit of painter size, and
     * where the cap line sits relative to the draw y. Measured on the rig
     * (2026-09-06): Noto Sans SemiBold at MTR's size 12 draws its caps 0.76 of
     * the painter size tall with the cap line exactly at the draw y — smaller
     * than the 12/8 the declared size suggests, because vanilla scales a TTF
     * provider's em to the 8-unit line box.
     */
    public static final float CAP_PER_SIZE = 0.76f;
    public static final float TOP_PER_SIZE = 0.0f;

    public static final int BLACK = 0xFF0E0E10;
    public static final int WHITE = 0xFFF7F7F7;
    public static final int PAPER = 0xFFF2F2EE;
    public static final int INK = 0xFF141416;
    public static final int GREY = 0xFF8A8A90;
    /** The MTA exit red: white "Exit" on a red field. */
    public static final int EXIT_RED = 0xFFEE352E;

    private static final float PAD_X = 3;
    private static final float PAD_Y = 2;
    private static final float MIN_SHRINK = 0.25f;
    /** How far a row condenses before its text stacks onto two lines instead. */
    private static final float WRAP_SHRINK = 0.78f;

    private SignLayout() {
    }

    /** Where one tile landed, for click-to-select in the editor. */
    public record TileBounds(int row, int tile, float x1, float y1, float x2, float y2) {
        public boolean contains(float x, float y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    /** Row bands [top, bottom] and every tile's bounds, after a paint. */
    public record Metrics(List<float[]> rowBounds, List<TileBounds> tiles) {
        public TileBounds at(float x, float y) {
            for (TileBounds bounds : tiles) {
                if (bounds.contains(x, y)) {
                    return bounds;
                }
            }
            return null;
        }

        public int rowAt(float y) {
            for (int i = 0; i < rowBounds.size(); i++) {
                if (y >= rowBounds.get(i)[0] && y < rowBounds.get(i)[1]) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ================================================================= text

    /** Painter size for a cap height. */
    public static float sizeFor(float cap) {
        return cap / CAP_PER_SIZE;
    }

    /** Draws {@code text} with its cap line at {@code capTop}. */
    public static void cap(Surface s, String text, float x, float capTop, float cap, int ink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float size = sizeFor(cap);
        s.text(text, x, capTop - TOP_PER_SIZE * size, size, ink, false);
    }

    /** Draws {@code text} vertically centred on {@code cy}. */
    public static void capCentered(Surface s, String text, float x, float cy, float cap, int ink) {
        cap(s, text, x, cy - cap / 2.0f, cap, ink);
    }

    public static float capWidth(Surface s, String text, float cap) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return s.width(text, sizeFor(cap), false);
    }

    // ================================================================ paint

    /** Paints the panel and its rows; returns where everything landed. */
    public static Metrics paint(Surface s, SignSpec spec, float width, float height, SignContext ctx) {
        boolean white = spec.style() == Style.WHITE_BAND;
        int ink = white ? INK : WHITE;
        s.rect(0, 0, width, height, white ? PAPER : BLACK, 0);
        float top = PAD_Y;
        if (white) {
            float band = Math.min(height * 0.16f, 6);
            s.rect(0, 0, width, band, BLACK, 1);
            top = band + PAD_Y;
        } else if (spec.style() == Style.BLACK) {
            // The thin white line under the top edge of every black MTA panel
            // (it was meant to hide the mounting channel; now it is the look).
            float lineY = Math.min(height * 0.09f, 4);
            float thickness = Math.max(0.6f, height * 0.02f);
            s.rect(0, lineY, width, lineY + thickness, WHITE, 1);
            top = lineY + thickness + PAD_Y * 0.5f;
        }
        List<float[]> rowBounds = new ArrayList<>();
        List<TileBounds> tiles = new ArrayList<>();
        List<Row> rows = spec.rows();
        if (rows.isEmpty()) {
            return new Metrics(rowBounds, tiles);
        }
        float rowHeight = (height - top - PAD_Y) / rows.size();
        for (int r = 0; r < rows.size(); r++) {
            float rowTop = top + r * rowHeight;
            rowBounds.add(new float[]{rowTop, rowTop + rowHeight});
            paintRow(s, rows.get(r), r, rowTop, rowHeight, width, ink, ctx, tiles);
        }
        return new Metrics(rowBounds, tiles);
    }

    // ------------------------------------------------------------- one row

    /** A tile measured at the current shrink factor. */
    private static final class Placed {
        final Tile tile;
        final int index;
        final float width;
        final int pin; // -1 left edge, +1 right edge, 0 flow

        Placed(Tile tile, int index, float width, int pin) {
            this.tile = tile;
            this.index = index;
            this.width = width;
            this.pin = pin;
        }
    }

    /** How a row is being fitted: overall shrink, two-line wrapping, and a per-tile text cap. */
    private record Fit(float shrink, boolean wrap, float maxText) {
        static final Fit NATURAL = new Fit(1.0f, false, Float.MAX_VALUE);
    }

    private static boolean textBearing(Tile tile) {
        return switch (tile.type()) {
            case TEXT, STATION_NAME, DESTINATION, EXIT -> true;
            default -> false;
        };
    }

    /**
     * Fits a row into the panel width the way a sign shop would: first at
     * natural size; if too wide, text wraps onto two lines; then everything
     * shrinks together down to {@link #MIN_SHRINK}; and what still does not
     * fit is trimmed with an ellipsis, the text tiles sharing the width the
     * fixed tiles (bullets, arrows) leave over.
     */
    private static void paintRow(Surface s, Row row, int rowIndex, float rowTop, float rowHeight, float width,
                                 int ink, SignContext ctx, List<TileBounds> out) {
        List<Tile> tileList = row.tiles();
        if (tileList.isEmpty()) {
            return;
        }
        float available = width - 2 * PAD_X;
        Fit fit = Fit.NATURAL;
        List<Placed> placed = measure(s, tileList, rowHeight, fit, ctx);
        float total = total(placed, gap(rowHeight, fit));
        // A little condensing first (real sign shops do), then stack the text
        // on two lines, then condense further, and trim what still overflows.
        while (total > available && fit.shrink() > WRAP_SHRINK) {
            fit = new Fit(Math.max(WRAP_SHRINK, fit.shrink() * Math.max(0.8f, available / total) - 0.01f), false,
                    Float.MAX_VALUE);
            placed = measure(s, tileList, rowHeight, fit, ctx);
            total = total(placed, gap(rowHeight, fit));
        }
        if (total > available) {
            fit = new Fit(1.0f, true, Float.MAX_VALUE);
            placed = measure(s, tileList, rowHeight, fit, ctx);
            total = total(placed, gap(rowHeight, fit));
        }
        while (total > available && fit.shrink() > MIN_SHRINK) {
            fit = new Fit(Math.max(MIN_SHRINK, fit.shrink() * Math.max(0.6f, available / total) - 0.01f), true,
                    Float.MAX_VALUE);
            placed = measure(s, tileList, rowHeight, fit, ctx);
            total = total(placed, gap(rowHeight, fit));
        }
        float gap = gap(rowHeight, fit);
        if (total > available) {
            float fixed = Math.max(0, placed.size() - 1) * gap;
            int textCount = 0;
            for (Placed p : placed) {
                if (textBearing(p.tile)) {
                    textCount++;
                    fixed += fixedPart(s, p.tile, rowHeight, fit, ctx);
                } else {
                    fixed += p.width;
                }
            }
            float budget = Math.max(6, (available - fixed) / Math.max(1, textCount));
            fit = new Fit(fit.shrink(), fit.wrap(), budget);
            placed = measure(s, tileList, rowHeight, fit, ctx);
        }

        // Pinned arrows take the edges; the rest flows between them.
        float leftWidth = 0;
        float rightWidth = 0;
        float middleWidth = 0;
        int middleCount = 0;
        for (Placed p : placed) {
            if (p.pin < 0) {
                leftWidth += p.width + gap;
            } else if (p.pin > 0) {
                rightWidth += p.width + gap;
            } else {
                middleWidth += p.width;
                middleCount++;
            }
        }
        if (middleCount > 1) {
            middleWidth += (middleCount - 1) * gap;
        }
        float middleStart = PAD_X + leftWidth;
        float middleAvail = Math.max(0, width - PAD_X - rightWidth - middleStart);
        float x = middleStart;
        if (row.align() == Align.CENTER) {
            x = middleStart + Math.max(0, (middleAvail - middleWidth) / 2.0f);
        }
        float leftX = PAD_X;
        float rightX = width - PAD_X;
        float cy = rowTop + rowHeight / 2.0f;
        for (Placed p : placed) {
            float tx;
            if (p.pin < 0) {
                tx = leftX;
                leftX += p.width + gap;
            } else if (p.pin > 0) {
                rightX -= p.width;
                tx = rightX;
                rightX -= gap;
            } else {
                tx = x;
                x += p.width + gap;
            }
            paintTile(s, p.tile, tx, cy, rowHeight, fit, ink, ctx);
            out.add(new TileBounds(rowIndex, p.index, tx, rowTop, tx + p.width, rowTop + rowHeight));
        }
    }

    /** Space between tiles, condensing with the row. */
    private static float gap(float rowHeight, Fit fit) {
        return rowHeight * 0.22f * fit.shrink();
    }

    private static float total(List<Placed> placed, float gap) {
        float total = 0;
        for (Placed p : placed) {
            total += p.width;
        }
        return total + Math.max(0, placed.size() - 1) * gap;
    }

    private static List<Placed> measure(Surface s, List<Tile> tiles, float rowHeight, Fit fit, SignContext ctx) {
        List<Placed> placed = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            int pin = tile.type() == SignSpec.TileType.ARROW
                    ? ("left".equals(tile.arg()) ? -1 : "right".equals(tile.arg()) ? 1 : 0) : 0;
            placed.add(new Placed(tile, i, tileWidth(s, tile, rowHeight, fit, ctx), pin));
        }
        return placed;
    }

    /** The part of a text-bearing tile that cannot be trimmed (its bullet, the word "Exit"...). */
    private static float fixedPart(Surface s, Tile tile, float rowHeight, Fit fit, SignContext ctx) {
        return switch (tile.type()) {
            case DESTINATION -> bulletDiameter(rowHeight, fit.shrink()) * 1.3f;
            case EXIT -> exitWidth(s, tile, rowHeight, fit.shrink(), ctx)
                    - exitStackWidth(s, tile, rowHeight, fit.shrink(), ctx);
            default -> 0;
        };
    }

    // ------------------------------------------------------------- metrics

    private static float bulletDiameter(float rowHeight, float shrink) {
        return rowHeight * 0.74f * shrink;
    }

    private static float textCap(Tile tile, float rowHeight, float shrink, int lineCount) {
        float ratio = switch (tile.num()) {
            case 1 -> 0.30f;
            case 2 -> 0.56f;
            default -> 0.42f;
        };
        if (lineCount > 1) {
            ratio = Math.min(ratio, 0.31f);
        }
        return rowHeight * ratio * shrink;
    }

    /** Explicit lines of a text tile (at most two), or a two-line wrap when the row asked for one. */
    private static String[] lines(String text, boolean wrap) {
        String[] split = text.split("\n", -1);
        if (split.length > 1) {
            return split.length > 2 ? new String[]{split[0], split[1]} : split;
        }
        return wrap ? wrapTwo(text) : split;
    }

    /** Splits at the space nearest the middle; one line when there is no space. */
    private static String[] wrapTwo(String text) {
        int best = -1;
        int mid = text.length() / 2;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ' && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) {
                best = i;
            }
        }
        if (best <= 0 || best >= text.length() - 1) {
            return new String[]{text};
        }
        return new String[]{text.substring(0, best), text.substring(best + 1)};
    }

    private static float tokenWidth(Surface s, String line, float cap) {
        float size = sizeFor(cap);
        float total = 0;
        float space = s.width(" ", size, false);
        boolean first = true;
        for (PosterLayout.Item item : PosterLayout.tokenize(s, line, size, false)) {
            if ("\n".equals(item.token())) {
                continue;
            }
            if (!first) {
                total += space * 0.9f;
            }
            total += item.width();
            first = false;
        }
        return total;
    }

    private static String clip(Surface s, String text, float cap, float maxWidth) {
        return maxWidth == Float.MAX_VALUE ? text : PosterLayout.fit(s, text, sizeFor(cap), false, maxWidth);
    }

    private static float tileWidth(Surface s, Tile tile, float rowHeight, Fit fit, SignContext ctx) {
        float shrink = fit.shrink();
        switch (tile.type()) {
            case BULLETS -> {
                List<String> lines = bulletLines(tile, ctx);
                if (lines.isEmpty()) {
                    return 0;
                }
                float d = bulletDiameter(rowHeight, shrink);
                return lines.size() * d + (lines.size() - 1) * d * 0.16f;
            }
            case TEXT -> {
                String[] lines = lines(tile.text(), fit.wrap());
                float cap = textCap(tile, rowHeight, shrink, lines.length);
                float w = 0;
                for (String line : lines) {
                    w = Math.max(w, Math.min(fit.maxText(), tokenWidth(s, line, cap)));
                }
                return w;
            }
            case ARROW -> {
                return rowHeight * 0.62f * shrink;
            }
            case STATION_NAME -> {
                String[] lines = lines(stationText(tile, ctx), fit.wrap());
                float cap = rowHeight * (lines.length > 1 ? 0.32f : 0.46f) * shrink;
                float w = 0;
                for (String line : lines) {
                    w = Math.max(w, Math.min(fit.maxText(), capWidth(s, line, cap)));
                }
                return w;
            }
            case EXIT -> {
                return exitWidth(s, tile, rowHeight, shrink, ctx)
                        - exitStackWidth(s, tile, rowHeight, shrink, ctx)
                        + Math.min(fit.maxText(), exitStackWidth(s, tile, rowHeight, shrink, ctx));
            }
            case DESTINATION -> {
                float d = bulletDiameter(rowHeight, shrink);
                String text = destinationText(tile);
                if (text.isEmpty()) {
                    return d;
                }
                String[] lines = lines(text, fit.wrap());
                float cap = rowHeight * (lines.length > 1 ? 0.3f : 0.42f) * shrink;
                float w = 0;
                for (String line : lines) {
                    w = Math.max(w, Math.min(fit.maxText(), capWidth(s, line, cap)));
                }
                return d * 1.3f + w;
            }
            case SPACER -> {
                return Math.max(0, tile.num()) * shrink;
            }
            default -> {
                return 0;
            }
        }
    }

    // --------------------------------------------------------------- tiles

    private static void paintTile(Surface s, Tile tile, float x, float cy, float rowHeight, Fit fit, int ink,
                                  SignContext ctx) {
        float shrink = fit.shrink();
        switch (tile.type()) {
            case BULLETS -> {
                float d = bulletDiameter(rowHeight, shrink);
                float bx = x;
                for (String line : bulletLines(tile, ctx)) {
                    bullet(s, line, bx + d / 2.0f, cy, d);
                    bx += d * 1.16f;
                }
            }
            case TEXT -> {
                String[] lines = lines(tile.text(), fit.wrap());
                float cap = textCap(tile, rowHeight, shrink, lines.length);
                paintLines(s, lines, x, cy, cap, ink, fit.maxText(), true);
            }
            case ARROW -> {
                float size = rowHeight * 0.62f * shrink;
                PosterLayout.bigArrow(s, x + size / 2.0f, cy, tile.num(), size / 40.0f, ink, 2);
            }
            case STATION_NAME -> {
                String[] lines = lines(stationText(tile, ctx), fit.wrap());
                float cap = rowHeight * (lines.length > 1 ? 0.32f : 0.46f) * shrink;
                paintLines(s, lines, x, cy, cap, ink, fit.maxText(), false);
            }
            case EXIT -> paintExit(s, tile, x, cy, rowHeight, shrink, ink, ctx, fit.maxText());
            case DESTINATION -> {
                float d = bulletDiameter(rowHeight, shrink);
                String routeName = tile.routes().isEmpty() ? "" : tile.routes().get(0);
                String line = com.stationannouncer.client.mtraddon.AddonUi.splitLineAndDirection(routeName)[0];
                bullet(s, line, x + d / 2.0f, cy, d);
                String text = destinationText(tile);
                if (!text.isEmpty()) {
                    String[] lines = lines(text, fit.wrap());
                    float cap = rowHeight * (lines.length > 1 ? 0.3f : 0.42f) * shrink;
                    paintLines(s, lines, x + d * 1.3f, cy, cap, ink, fit.maxText(), false);
                }
            }
            default -> {
            }
        }
    }

    /** One or two lines of text vertically centred on {@code cy}, each clipped to {@code maxText}. */
    private static void paintLines(Surface s, String[] lines, float x, float cy, float cap, int ink, float maxText,
                                   boolean tokens) {
        if (lines.length == 1) {
            String line = clip(s, lines[0], cap, maxText);
            if (tokens) {
                tokens(s, line, x, cy - cap / 2.0f, cap, ink);
            } else {
                capCentered(s, line, x, cy, cap, ink);
            }
            return;
        }
        float pitch = cap * 1.45f;
        float top = cy - pitch / 2.0f - cap / 2.0f;
        for (int i = 0; i < 2; i++) {
            String line = clip(s, lines[i], cap, maxText);
            if (tokens) {
                tokens(s, line, x, top + i * pitch, cap, ink);
            } else {
                cap(s, line, x, top + i * pitch, cap, ink);
            }
        }
    }

    /** Token-bearing text on one line with its cap line at {@code capTop}. */
    private static void tokens(Surface s, String line, float x, float capTop, float cap, int ink) {
        float size = sizeFor(cap);
        float space = s.width(" ", size, false);
        float y = capTop - TOP_PER_SIZE * size;
        boolean first = true;
        for (PosterLayout.Item item : PosterLayout.tokenize(s, line, size, false)) {
            if ("\n".equals(item.token())) {
                continue;
            }
            if (!first) {
                x += space * 0.9f;
            }
            if (item.word() != null) {
                s.text(item.word(), x, y, size, ink, false);
            } else {
                // Symbols are authored against the vanilla glyph box; centre them on the cap line.
                PosterLayout.symbol(s, item.token(), item.arg(), x, capTop + cap / 2.0f - size / 2.0f, size, ink);
            }
            x += item.width();
            first = false;
        }
    }

    // ------------------------------------------------------------- bullets

    private static List<String> bulletLines(Tile tile, SignContext ctx) {
        if ("auto".equals(tile.arg()) && ctx != null) {
            return ctx.stationLines();
        }
        return tile.routes();
    }

    /**
     * One route bullet: a disc in the line colour with its label, an express
     * diamond for a {@code d:} prefix, the no-entry roundel for the sentinel.
     */
    public static void bullet(Surface s, String line, float cx, float cy, float diameter) {
        boolean diamond = line.startsWith("d:");
        String name = diamond ? line.substring(2) : line;
        float r = diameter / 2.0f;
        if (RouteBullets.isNoEntry(name)) {
            s.disc(cx, cy, r, RouteBullets.NO_ENTRY_COLOR, 2);
            s.rect(cx - r * 0.62f, cy - Math.max(0.8f, r * 0.17f), cx + r * 0.62f, cy + Math.max(0.8f, r * 0.17f),
                    WHITE, 3);
            return;
        }
        RouteBullets.Bullet bullet = PosterLayout.lineBullet(name);
        if (diamond) {
            float dr = r * 1.12f;
            s.poly(new float[]{cx, cx + dr, cx, cx - dr}, new float[]{cy - dr, cy, cy + dr, cy}, bullet.color(), 2);
        } else {
            s.disc(cx, cy, r, bullet.color(), 2);
        }
        String label = bullet.label();
        float cap = diameter * (label.length() > 1 ? 0.48f : 0.6f);
        int ink = RouteBullets.needsDarkText(bullet.color()) ? INK : WHITE;
        capCentered(s, label, cx - capWidth(s, label, cap) / 2.0f, cy, cap, ink);
    }

    // ------------------------------------------------------------ dynamic

    private static String stationText(Tile tile, SignContext ctx) {
        String text = tile.text();
        if (text.isEmpty()) {
            text = ctx != null ? ctx.stationName() : "";
            if (text.isEmpty()) {
                text = "Subway"; // outside any MTR station: what an entrance sign says anyway
            }
        }
        return tile.num() == 1 ? text.toUpperCase(java.util.Locale.ROOT) : text;
    }

    private static String destinationText(Tile tile) {
        String routeName = tile.routes().isEmpty() ? "" : tile.routes().get(0);
        return switch (tile.num()) {
            case 1 -> {
                String direction = SignContext.direction(routeName);
                yield direction.isEmpty() ? toDestination(routeName) : direction;
            }
            case 2 -> tile.text();
            default -> toDestination(routeName);
        };
    }

    private static String toDestination(String routeName) {
        String destination = SignContext.destination(routeName);
        if (destination.isEmpty()) {
            String direction = SignContext.direction(routeName);
            return direction.isEmpty() ? "" : direction;
        }
        return "to " + destination;
    }

    // ---------------------------------------------------------------- exit

    private static final float EXIT_WORD_RATIO = 0.46f;
    private static final float EXIT_SMALL_RATIO = 0.22f;

    private record ExitParts(String word, String streets, String corner, String name) {
    }

    private static ExitParts exitParts(Tile tile, SignContext ctx) {
        SignContext.Exit exit = ctx != null ? ctx.exit(tile.text()) : null;
        String word = tile.hasFlag(SignSpec.EXIT_WORD) ? "Exit" : "";
        String streets = "";
        if (tile.hasFlag(SignSpec.EXIT_STREETS)) {
            if (exit != null && !exit.destinations().isEmpty()) {
                streets = String.join(" & ", exit.destinations());
            } else if (exit == null && !tile.text().isEmpty()) {
                streets = tile.text(); // no such exit here (yet): show what was typed
            }
        }
        String name = tile.hasFlag(SignSpec.EXIT_NAME) && exit != null ? exit.name() : "";
        return new ExitParts(word, streets, tile.arg(), name);
    }

    /** Side padding of the red exit field, in row heights. */
    private static final float EXIT_PAD = 0.14f;

    private static float exitWidth(Surface s, Tile tile, float rowHeight, float shrink, SignContext ctx) {
        return exitContentWidth(s, tile, rowHeight, shrink, ctx) + 2 * EXIT_PAD * rowHeight * shrink;
    }

    /** Width of the trimmable street / corner stack alone. */
    private static float exitStackWidth(Surface s, Tile tile, float rowHeight, float shrink, SignContext ctx) {
        ExitParts parts = exitParts(tile, ctx);
        float small = rowHeight * EXIT_SMALL_RATIO * shrink;
        return Math.max(capWidth(s, parts.streets(), small), capWidth(s, parts.corner(), small));
    }

    private static float exitContentWidth(Surface s, Tile tile, float rowHeight, float shrink, SignContext ctx) {
        ExitParts parts = exitParts(tile, ctx);
        float big = rowHeight * EXIT_WORD_RATIO * shrink;
        float small = rowHeight * EXIT_SMALL_RATIO * shrink;
        float gap = rowHeight * 0.18f * shrink;
        float w = 0;
        if (!parts.word().isEmpty()) {
            w += capWidth(s, parts.word(), big);
        }
        float stack = Math.max(capWidth(s, parts.streets(), small), capWidth(s, parts.corner(), small));
        if (stack > 0) {
            w += (w > 0 ? gap : 0) + stack;
        }
        if (!parts.name().isEmpty()) {
            w += (w > 0 ? gap : 0) + capWidth(s, parts.name(), small * 1.2f) + small * 1.2f;
        }
        return w;
    }

    private static void paintExit(Surface s, Tile tile, float x, float cy, float rowHeight, float shrink, int panelInk,
                                  SignContext ctx, float maxText) {
        ExitParts raw = exitParts(tile, ctx);
        float smallCap = rowHeight * EXIT_SMALL_RATIO * shrink;
        ExitParts parts = new ExitParts(raw.word(), clip(s, raw.streets(), smallCap, maxText),
                clip(s, raw.corner(), smallCap, maxText), raw.name());
        // White on the MTA exit red, whatever the panel style.
        float pad = EXIT_PAD * rowHeight * shrink;
        float fieldHalf = rowHeight * 0.44f * shrink;
        float stackNatural = exitStackWidth(s, tile, rowHeight, shrink, ctx);
        float contentWidth = exitContentWidth(s, tile, rowHeight, shrink, ctx) - stackNatural
                + Math.min(maxText, stackNatural);
        s.rect(x, cy - fieldHalf, x + contentWidth + 2 * pad, cy + fieldHalf, EXIT_RED, 1);
        int ink = WHITE;
        x += pad;
        float big = rowHeight * EXIT_WORD_RATIO * shrink;
        float small = rowHeight * EXIT_SMALL_RATIO * shrink;
        float gap = rowHeight * 0.18f * shrink;
        if (!parts.word().isEmpty()) {
            capCentered(s, parts.word(), x, cy, big, ink);
            x += capWidth(s, parts.word(), big) + gap;
        }
        boolean streets = !parts.streets().isEmpty();
        boolean corner = !parts.corner().isEmpty();
        float stack = Math.max(capWidth(s, parts.streets(), small), capWidth(s, parts.corner(), small));
        if (streets || corner) {
            float pitch = small * 1.5f;
            if (streets && corner) {
                cap(s, parts.streets(), x, cy - pitch / 2.0f - small / 2.0f, small, ink);
                cap(s, parts.corner(), x, cy + pitch / 2.0f - small / 2.0f, small, ink);
            } else {
                capCentered(s, streets ? parts.streets() : parts.corner(), x, cy, small, ink);
            }
            x += stack + gap;
        }
        if (!parts.name().isEmpty()) {
            float cap = small * 1.2f;
            float w = capWidth(s, parts.name(), cap) + cap;
            float h = cap * 1.7f;
            s.rect(x, cy - h / 2.0f, x + w, cy + h / 2.0f, ink, 2);
            s.rect(x + 0.8f, cy - h / 2.0f + 0.8f, x + w - 0.8f, cy + h / 2.0f - 0.8f, EXIT_RED, 3);
            capCentered(s, parts.name(), x + cap / 2.0f, cy, cap, ink);
        }
    }
}
