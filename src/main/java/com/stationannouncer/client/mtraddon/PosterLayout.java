package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.mtr.CanvasPainter;
import com.stationannouncer.client.mtr.RouteBullets;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.mtr.core.data.SimplifiedRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one poster layout, painted onto an abstract {@link Surface} so the editor
 * preview (a {@link GuiSurface} over a {@code DrawContext}) and the hung frame
 * (a {@link WorldSurface} over the block-entity quad pipeline) are guaranteed to
 * agree pixel for pixel.
 *
 * <p>Canvas is {@value #WIDTH} x {@value #HEIGHT} units (portrait 4:5, the
 * proportion of the real posters). Modelled on the MTA service-change sheet:
 * black title bar with a logo, grey band with the big "All Times" line, two date
 * lines and the affected-line bullets, a category rule, the body column, and a
 * black footer.</p>
 *
 * <p><b>Inline tokens</b> in body text (see {@link ServicePoster}):
 * {@code {b:NAME}} line bullet, {@code {d:NAME}} express diamond, {@code {wc}}
 * wheelchair, {@code {<} {>} {^} {v}} small arrows. Lines are resolved by their
 * line name against MTR's simplified routes at draw time.</p>
 */
@Environment(EnvType.CLIENT)
public final class PosterLayout {
    public static final int WIDTH = 128;
    /**
     * The sheet fills the whole frame: the plate is 25 px tall and the sheet
     * 15 px wide, so 25/15 x 128 units — close to the 11 x 17 in. of the real
     * posters. Short bodies leave white space above the footer, as the real
     * sheets do; long ones shrink their body text through {@link #BODY_SCALES}.
     */
    public static final int HEIGHT = 212;
    public static final int MAX_HEIGHT = HEIGHT;

    private static final float MARGIN = 8;
    private static final float RIGHT = WIDTH - MARGIN;
    private static final float TITLE_BOTTOM = 14;
    private static final float BAND_BOTTOM = 50;
    private static final float FOOTER_HEIGHT = 14;
    private static final float BODY_GAP = 3;
    /** Body text shrink steps tried once the sheet is at its maximum height. */
    private static final float[] BODY_SCALES = {1.0f, 0.9f, 0.8f, 0.7f, 0.6f};

    public static final int BLACK = 0xFF0E0E10;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int PAPER = 0xFFF7F7F5;
    public static final int BAND = 0xFFD9D9D3;
    public static final int INK = 0xFF141416;
    public static final int GREY_INK = 0xFF4A4A50;
    public static final int WHEELCHAIR_BLUE = 0xFF1F63C0;

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z<>^]+)(?::([^}]*))?}");

    private PosterLayout() {
    }

    // ================================================================ surface

    /** What a poster needs from whatever it is drawn on. Coordinates in canvas units. */
    public interface Surface {
        void rect(float x1, float y1, float x2, float y2, int argb, int layer);

        /** Convex polygon. */
        void poly(float[] xs, float[] ys, int argb, int layer);

        void disc(float cx, float cy, float radius, int argb, int layer);

        /** Text with its top-left at x/y; {@code size} is the glyph height in canvas units. */
        void text(String string, float x, float y, float size, int argb, boolean bold);

        float width(String string, float size, boolean bold);
    }

    // ================================================================= paint

    /**
     * How a poster lays out: the sheet height, the body text scale it settled on,
     * and (after a real paint) each body block's [top, bottom] in canvas units —
     * what lets the editor select a block by clicking the preview.
     */
    public record Metrics(float height, float bodyScale, List<float[]> blockBounds) {
    }

    /** Where the header band ends and the footer starts, for click mapping. */
    public static float headerBottom() {
        return BAND_BOTTOM;
    }

    public static float footerTop(float height) {
        return height - FOOTER_HEIGHT;
    }

    /**
     * Fits the poster: the body text shrinks through {@link #BODY_SCALES} until
     * it fits the sheet; past the last step it clips at the footer.
     */
    public static Metrics measure(Surface s, ServicePoster poster) {
        Surface measuring = new MeasureSurface(s);
        float bodyTop = bodyTop(measuring, poster);
        for (float scale : BODY_SCALES) {
            float end = paintBody(measuring, poster, bodyTop, Float.MAX_VALUE, scale, null);
            float height = end + BODY_GAP + FOOTER_HEIGHT;
            if (height <= HEIGHT) {
                return new Metrics(HEIGHT, scale, List.of());
            }
        }
        return new Metrics(HEIGHT, BODY_SCALES[BODY_SCALES.length - 1], List.of());
    }

    /** Paints the poster and returns its metrics (the height actually used). */
    public static Metrics paint(Surface s, ServicePoster poster) {
        Metrics measured = measure(s, poster);
        List<float[]> bounds = new ArrayList<>();
        Metrics metrics = new Metrics(measured.height(), measured.bodyScale(), bounds);
        float height = metrics.height();
        float footerTop = height - FOOTER_HEIGHT;
        // Sheet.
        s.rect(0, 0, WIDTH, height, PAPER, 0);
        // Title bar.
        s.rect(0, 0, WIDTH, TITLE_BOTTOM, BLACK, 1);
        s.text(poster.kind(), MARGIN, 4, 6, WHITE, true);
        String logo = poster.logo();
        if (!logo.isEmpty()) {
            s.text(logo, RIGHT - s.width(logo, 6.5f, true), 3.6f, 6.5f, WHITE, true);
        }
        // Grey band: timing, dates, header bullets.
        s.rect(0, TITLE_BOTTOM, WIDTH, BAND_BOTTOM, BAND, 1);
        float bulletsLeft = RIGHT;
        List<String> lines = poster.headerLines();
        if (!lines.isEmpty()) {
            float radius = lines.size() <= 2 ? 11 : lines.size() <= 4 ? 8 : 6;
            float pitch = radius * 2 + 3;
            float cy = (TITLE_BOTTOM + BAND_BOTTOM) / 2;
            float cx = RIGHT - radius;
            for (int i = lines.size() - 1; i >= 0; i--) {
                RouteBullets.Bullet bullet = lineBullet(lines.get(i));
                s.disc(cx, cy, radius, bullet.color(), 2);
                float labelSize = radius * 1.15f;
                int ink = RouteBullets.needsDarkText(bullet.color()) ? INK : WHITE;
                s.text(bullet.label(), cx - s.width(bullet.label(), labelSize, true) / 2, cy - labelSize / 2,
                        labelSize, ink, true);
                cx -= pitch;
            }
            bulletsLeft = cx + pitch - radius - 4;
        }
        float timingWidth = bulletsLeft - MARGIN;
        float timingSize = 15;
        while (timingSize > 7 && s.width(poster.timing(), timingSize, true) > timingWidth) {
            timingSize -= 0.5f;
        }
        s.text(poster.timing(), MARGIN, 17, timingSize, INK, true);
        s.text(fit(s, poster.date1(), 5, false, timingWidth), MARGIN, 36, 5, INK, false);
        s.text(fit(s, poster.date2(), 5, false, timingWidth), MARGIN, 42.5f, 5, INK, false);

        // Category rule + body.
        float bodyTop = bodyTop(s, poster);
        paintBody(s, poster, bodyTop, footerTop - BODY_GAP, metrics.bodyScale(), bounds);

        // Footer.
        s.rect(0, footerTop, WIDTH, height, BLACK, 1);
        float footerY = footerTop + 4.5f;
        String left = poster.footerLeft();
        String right = poster.footerRight();
        float span = RIGHT - MARGIN;
        float footerSize = 4.5f;
        // The left text keeps up to half the footer; the right takes what is left.
        String leftText = fit(s, left, footerSize, true, right.isEmpty() ? span : span * 0.5f);
        float leftWidth = s.width(leftText, footerSize, true);
        s.text(leftText, MARGIN, footerY, footerSize, WHITE, true);
        if (!right.isEmpty()) {
            String rightText = fit(s, right, footerSize, true, span - leftWidth - 4);
            s.text(rightText, RIGHT - s.width(rightText, footerSize, true), footerY, footerSize, WHITE, true);
        }
        return metrics;
    }

    /** Draws the category rule and returns where the body starts. */
    private static float bodyTop(Surface s, ServicePoster poster) {
        float y = BAND_BOTTOM + 4;
        if (!poster.category().isEmpty()) {
            s.text(fit(s, poster.category(), 5.5f, true, RIGHT - MARGIN), MARGIN, y, 5.5f, INK, true);
            y += 7;
            s.rect(MARGIN, y, RIGHT, y + 1.2f, INK, 1);
            y += 4;
        }
        return y;
    }

    /** Draws the body blocks from {@code y} down to {@code limit}; returns the y below the last one. */
    private static float paintBody(Surface s, ServicePoster poster, float y, float limit, float scale,
                                   List<float[]> bounds) {
        for (ServicePoster.Block block : poster.blocks()) {
            float top = Math.min(y, limit);
            if (y < limit) {
                y = paintBlock(s, block, y, limit, scale);
            }
            if (bounds != null) {
                bounds.add(new float[]{top, Math.min(y, limit)});
            }
        }
        return y;
    }

    private static float paintBlock(Surface s, ServicePoster.Block block, float y, float limit, float scale) {
        switch (block.type()) {
            case HEADLINE -> {
                return flow(s, block.text(), y, 10 * scale, 11.2f * scale, INK, true, limit) + 3;
            }
            case TEXT -> {
                return flow(s, block.text(), y, 5.5f * scale, 6.6f * scale, INK, false, limit) + 3;
            }
            case SUBHEAD -> {
                return flow(s, block.text(), y, 5.5f * scale, 6.6f * scale, INK, true, limit) + 1;
            }
            case ARROW -> {
                float arrow = 30 * scale;
                if (y + arrow > limit) {
                    return limit;
                }
                bigArrow(s, WIDTH / 2.0f, y + arrow / 2, block.arg(), scale, INK, 2);
                return y + arrow + 3;
            }
            case RULE -> {
                s.rect(MARGIN, y + 1, RIGHT, y + 2, INK, 1);
                return y + 6;
            }
            default -> {
                return y + 6;
            }
        }
    }

    // ------------------------------------------------------------- text flow

    /** One flow item: a word, or a token drawn as a symbol. */
    public record Item(String word, String token, String arg, float width) {
    }

    /**
     * Lays out token-bearing text in the body column from {@code y} down,
     * wrapping on words, and returns the y below the last line. Nothing is
     * drawn past the footer.
     */
    private static float flow(Surface s, String text, float y, float size, float lineHeight, int ink, boolean bold,
                              float limit) {
        List<Item> items = tokenize(s, text, size, bold);
        float x = MARGIN;
        float space = s.width(" ", size, bold);
        boolean lineEmpty = true;
        for (Item item : items) {
            if ("\n".equals(item.token())) {
                // Explicit line break (Enter in the editor).
                y += lineHeight;
                x = MARGIN;
                lineEmpty = true;
                continue;
            }
            if (!lineEmpty && x + item.width() > RIGHT) {
                y += lineHeight;
                x = MARGIN;
                lineEmpty = true;
            }
            if (y + size > limit + 1) {
                return limit;
            }
            if (item.word() != null) {
                s.text(item.word(), x, y, size, ink, bold);
            } else {
                symbol(s, item.token(), item.arg(), x, y, size, ink);
            }
            x += item.width() + space * 0.9f;
            lineEmpty = false;
        }
        return lineEmpty && items.isEmpty() ? y : y + lineHeight;
    }

    public static List<Item> tokenize(Surface s, String text, float size, boolean bold) {
        List<Item> items = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return items;
        }
        Matcher matcher = TOKEN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            addWords(items, s, text.substring(last, matcher.start()), size, bold);
            String token = matcher.group(1).toLowerCase();
            String arg = matcher.group(2) == null ? "" : matcher.group(2);
            items.add(new Item(null, token, arg, symbolWidth(token, size)));
            last = matcher.end();
        }
        addWords(items, s, text.substring(last), size, bold);
        return items;
    }

    private static void addWords(List<Item> items, Surface s, String run, float size, boolean bold) {
        String[] paragraphs = run.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            if (p > 0) {
                items.add(new Item(null, "\n", "", 0));
            }
            for (String word : paragraphs[p].trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    items.add(new Item(word, null, null, s.width(word, size, bold)));
                }
            }
        }
    }

    // --------------------------------------------------------------- symbols

    public static float symbolWidth(String token, float size) {
        return switch (token) {
            case "b", "d" -> size * 1.15f;
            case "wc" -> size * 1.05f;
            case "<", ">", "^", "v" -> size * 0.9f;
            default -> size;
        };
    }

    public static void symbol(Surface s, String token, String arg, float x, float y, float size, int ink) {
        float cy = y + size * 0.5f;
        switch (token) {
            case "b" -> {
                RouteBullets.Bullet bullet = lineBullet(arg);
                float radius = size * 0.575f;
                s.disc(x + radius, cy, radius, bullet.color(), 2);
                float labelSize = radius * 1.15f;
                s.text(bullet.label(), x + radius - s.width(bullet.label(), labelSize, true) / 2,
                        cy - labelSize / 2, labelSize, RouteBullets.needsDarkText(bullet.color()) ? INK : WHITE, true);
            }
            case "d" -> {
                RouteBullets.Bullet bullet = lineBullet(arg);
                float radius = size * 0.62f;
                float cx = x + size * 0.575f;
                s.poly(new float[]{cx, cx + radius, cx, cx - radius},
                        new float[]{cy - radius, cy, cy + radius, cy}, bullet.color(), 2);
                float labelSize = radius * 1.0f;
                s.text(bullet.label(), cx - s.width(bullet.label(), labelSize, true) / 2,
                        cy - labelSize / 2, labelSize, RouteBullets.needsDarkText(bullet.color()) ? INK : WHITE, true);
            }
            case "wc" -> wheelchair(s, x, y + size * 0.02f, size * 1.0f, 2);
            case "<" -> bigArrow(s, x + size * 0.45f, cy, 4, size / 40.0f, ink, 2);
            case ">" -> bigArrow(s, x + size * 0.45f, cy, 0, size / 40.0f, ink, 2);
            case "^" -> bigArrow(s, x + size * 0.45f, cy, 2, size / 40.0f, ink, 2);
            case "v" -> bigArrow(s, x + size * 0.45f, cy, 6, size / 40.0f, ink, 2);
            default -> s.text("{" + token + "}", x, y, size, ink, false);
        }
    }

    /** The international access symbol: white figure on a blue tile. */
    public static void wheelchair(Surface s, float x, float y, float size, int layer) {
        float u = size / 16.0f;
        s.rect(x, y, x + size, y + size, WHEELCHAIR_BLUE, layer);
        // head
        s.disc(x + 9.2f * u, y + 3.2f * u, 1.9f * u, WHITE, layer + 1);
        // torso down to the seat, then the thigh forward
        s.rect(x + 8.0f * u, y + 5.2f * u, x + 10.4f * u, y + 10.2f * u, WHITE, layer + 1);
        s.rect(x + 8.0f * u, y + 8.4f * u, x + 13.2f * u, y + 10.2f * u, WHITE, layer + 1);
        // shin down to the footrest
        s.rect(x + 11.6f * u, y + 8.4f * u, x + 13.2f * u, y + 13.4f * u, WHITE, layer + 1);
        s.rect(x + 11.6f * u, y + 12.4f * u, x + 14.4f * u, y + 13.6f * u, WHITE, layer + 1);
        // wheel: white ring
        s.disc(x + 7.0f * u, y + 10.6f * u, 4.4f * u, WHITE, layer + 1);
        s.disc(x + 7.0f * u, y + 10.6f * u, 2.9f * u, WHEELCHAIR_BLUE, layer + 2);
    }

    /**
     * A block arrow pointing in {@code dir} (0 = right, anticlockwise in 45°
     * steps), centred on cx/cy, at {@code scale} x the 40-unit reference size.
     */
    public static void bigArrow(Surface s, float cx, float cy, int dir, float scale, int argb, int layer) {
        float a = (float) Math.toRadians(dir * 45.0);
        float cos = MathHelper.cos(a);
        float sin = MathHelper.sin(a);
        // Shaft (a quad) then head (a triangle), authored pointing right.
        float[][] shaft = {{-20, -5}, {4, -5}, {4, 5}, {-20, 5}};
        float[][] head = {{4, -13}, {20, 0}, {4, 13}};
        for (float[][] part : new float[][][]{shaft, head}) {
            float[] xs = new float[part.length];
            float[] ys = new float[part.length];
            for (int i = 0; i < part.length; i++) {
                float px = part[i][0] * scale;
                float py = part[i][1] * scale;
                // y is down on the canvas, so an anticlockwise turn is x cos + y sin / -x sin + y cos.
                xs[i] = cx + px * cos + py * sin;
                ys[i] = cy - px * sin + py * cos;
            }
            s.poly(xs, ys, argb, layer);
        }
    }

    public static String fit(Surface s, String text, float size, boolean bold, float maxWidth) {
        if (text == null || text.isEmpty() || s.width(text, size, bold) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && s.width(text.substring(0, end) + ellipsis, size, bold) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    // ================================================================= lines

    /** One line as the editor offers it: its name and how its bullet draws. */
    public record LineOption(String name, RouteBullets.Bullet bullet) {
    }

    private static final Map<String, RouteBullets.Bullet> LINE_BULLETS = new LinkedHashMap<>();
    private static long lineExpiry;
    private static final long LINE_TTL_MS = 3_000;
    private static final RouteBullets.Bullet UNKNOWN = new RouteBullets.Bullet("?", 0xFF7A7A80);

    /**
     * The bullet for a LINE name (the part of an MTR route name before its
     * {@code ||} direction). Unknown names draw grey with the name's own label,
     * so a poster still reads on a world whose lines were renamed.
     */
    public static RouteBullets.Bullet lineBullet(String lineName) {
        if (lineName == null || lineName.isEmpty()) {
            return UNKNOWN;
        }
        RouteBullets.Bullet bullet = lineMap().get(lineName);
        if (bullet != null) {
            return bullet;
        }
        return new RouteBullets.Bullet(RouteBullets.label(lineName), UNKNOWN.color());
    }

    /** Every line in the world, in MTR's order, for the editor's picker. */
    public static List<LineOption> lines() {
        List<LineOption> options = new ArrayList<>();
        lineMap().forEach((name, bullet) -> options.add(new LineOption(name, bullet)));
        return options;
    }

    private static Map<String, RouteBullets.Bullet> lineMap() {
        long now = System.currentTimeMillis();
        if (now < lineExpiry) {
            return LINE_BULLETS;
        }
        lineExpiry = now + LINE_TTL_MS;
        LINE_BULLETS.clear();
        try {
            for (SimplifiedRoute route : org.mtr.mod.client.MinecraftClientData.getInstance().simplifiedRoutes) {
                String raw = route.getName();
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                String line = AddonUi.splitLineAndDirection(raw)[0];
                if (!line.isEmpty()) {
                    LINE_BULLETS.putIfAbsent(line, new RouteBullets.Bullet(RouteBullets.label(raw),
                            0xFF000000 | route.getColor()));
                }
            }
        } catch (Exception ignored) {
            // MTR data mid-sync — the list comes back short for a moment
        }
        // Simplified routes only exist for routes with generated paths; the
        // dashboard's full route list (what the disruption editor lists) covers
        // the rest, so a line on a poster is never grey just for lacking a depot.
        try {
            for (org.mtr.core.data.Route route : org.mtr.mod.client.MinecraftClientData.getDashboardInstance().routes) {
                String raw = route.getName();
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                String line = AddonUi.splitLineAndDirection(raw)[0];
                if (!line.isEmpty()) {
                    LINE_BULLETS.putIfAbsent(line, new RouteBullets.Bullet(RouteBullets.label(raw),
                            0xFF000000 | route.getColor()));
                }
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync
        }
        return LINE_BULLETS;
    }

    // ============================================================== template

    /** A sensible starting poster for a disruption: its lines up top, its message as the headline. */
    public static ServicePoster.Builder template(ClientDisruptions.Entry disruption) {
        ServicePoster.Builder draft = new ServicePoster.Builder();
        draft.disruptionId = disruption.id();
        boolean severe = disruption.severity() >= 2;
        draft.kind = severe ? "Service Change" : "Planned Work";
        draft.category = severe ? "SERVICE CHANGE" : "PLANNED WORK";
        draft.timing = "All Times";
        long now = System.currentTimeMillis();
        if (disruption.endMillis() > now) {
            long minutes = Math.max(1, (disruption.endMillis() - now) / 60_000L);
            draft.date1 = minutes >= 120 ? "For the next " + (minutes / 60) + " hours" : "For the next " + minutes + " min";
        } else {
            draft.date1 = "Until further notice";
        }
        java.util.Set<String> lines = new java.util.LinkedHashSet<>();
        for (long routeId : disruption.routeIds()) {
            org.mtr.core.data.Route route = DisruptionsScreen.findRoute(routeId);
            if (route != null) {
                String line = AddonUi.splitLineAndDirection(route.getName())[0];
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        draft.headerLines.addAll(lines.stream().limit(ServicePoster.MAX_HEADER_LINES).toList());
        StringBuilder headline = new StringBuilder();
        for (String line : lines) {
            headline.append("{b:").append(line).append("} ");
        }
        headline.append(disruption.message());
        draft.blocks.add(ServicePoster.Block.text(ServicePoster.BlockType.HEADLINE, headline.toString().trim()));
        draft.footerLeft = "Subway";
        return draft;
    }

    // ============================================================== surfaces

    /** Measures like the real surface, draws nothing — the fitting pass. */
    private static final class MeasureSurface implements Surface {
        private final Surface real;

        MeasureSurface(Surface real) {
            this.real = real;
        }

        @Override
        public void rect(float x1, float y1, float x2, float y2, int argb, int layer) {
        }

        @Override
        public void poly(float[] xs, float[] ys, int argb, int layer) {
        }

        @Override
        public void disc(float cx, float cy, float radius, int argb, int layer) {
        }

        @Override
        public void text(String string, float x, float y, float size, int argb, boolean bold) {
        }

        @Override
        public float width(String string, float size, boolean bold) {
            return real.width(string, size, bold);
        }
    }

    /** Draws onto a GUI at {@code ox/oy}, scaled so one canvas unit is {@code scale} pixels. */
    public static final class GuiSurface implements Surface {
        private final DrawContext context;
        private final TextRenderer font;
        private final float ox;
        private final float oy;
        private final float scale;

        /** A resource font id (e.g. MTR's {@code mtr:mtr}) for the text, or null for vanilla. */
        private final net.minecraft.util.Identifier fontId;

        public GuiSurface(DrawContext context, float ox, float oy, float scale) {
            this(context, ox, oy, scale, null);
        }

        public GuiSurface(DrawContext context, float ox, float oy, float scale,
                          net.minecraft.util.Identifier fontId) {
            this.context = context;
            this.font = MinecraftClient.getInstance().textRenderer;
            this.ox = ox;
            this.oy = oy;
            this.scale = scale;
            this.fontId = fontId;
        }

        private net.minecraft.text.Text styled(String string) {
            return net.minecraft.text.Text.literal(string)
                    .setStyle(net.minecraft.text.Style.EMPTY.withFont(fontId));
        }

        private int px(float x) {
            return Math.round(ox + x * scale);
        }

        private int py(float y) {
            return Math.round(oy + y * scale);
        }

        @Override
        public void rect(float x1, float y1, float x2, float y2, int argb, int layer) {
            int l = px(x1);
            int t = py(y1);
            int r = Math.max(l + 1, px(x2));
            int b = Math.max(t + 1, py(y2));
            context.fill(l, t, r, b, argb);
        }

        @Override
        public void poly(float[] xs, float[] ys, int argb, int layer) {
            int n = xs.length;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                minY = Math.min(minY, ys[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            int top = py(minY);
            int bottom = py(maxY);
            for (int row = top; row < Math.max(bottom, top + 1); row++) {
                float sy = (row + 0.5f - oy) / scale;
                float left = Float.MAX_VALUE;
                float right = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    float y1 = ys[i];
                    float y2 = ys[j];
                    if ((sy >= y1 && sy < y2) || (sy >= y2 && sy < y1)) {
                        float t = (sy - y1) / (y2 - y1);
                        float x = xs[i] + t * (xs[j] - xs[i]);
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                    }
                }
                if (left <= right) {
                    int l = px(left);
                    int r = Math.max(l + 1, px(right));
                    context.fill(l, row, r, row + 1, argb);
                }
            }
        }

        @Override
        public void disc(float cx, float cy, float radius, int argb, int layer) {
            int top = py(cy - radius);
            int bottom = py(cy + radius);
            for (int row = top; row < Math.max(bottom, top + 1); row++) {
                float sy = (row + 0.5f - oy) / scale - cy;
                float half = radius * radius - sy * sy;
                if (half <= 0) {
                    continue;
                }
                float hw = (float) Math.sqrt(half);
                int l = px(cx - hw);
                int r = Math.max(l + 1, px(cx + hw));
                context.fill(l, row, r, row + 1, argb);
            }
        }

        @Override
        public void text(String string, float x, float y, float size, int argb, boolean bold) {
            if (string == null || string.isEmpty()) {
                return;
            }
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.translate(ox + x * scale, oy + y * scale, 0);
            float glyph = size * scale / 8.0f;
            matrices.scale(glyph, glyph, 1);
            if (fontId != null) {
                net.minecraft.text.Text text = styled(string);
                context.drawText(font, text, 0, 0, argb, false);
                if (bold) {
                    matrices.translate(0.5f, 0, 0);
                    context.drawText(font, text, 0, 0, argb, false);
                }
            } else {
                context.drawText(font, string, 0, 0, argb, false);
                if (bold) {
                    // Faked bold: a second pass half a glyph unit to the right (size/16 canvas units).
                    matrices.translate(0.5f, 0, 0);
                    context.drawText(font, string, 0, 0, argb, false);
                }
            }
            matrices.pop();
        }

        @Override
        public float width(String string, float size, boolean bold) {
            if (string == null || string.isEmpty()) {
                return 0;
            }
            int raw = fontId != null ? font.getWidth(styled(string)) : font.getWidth(string);
            return raw * size / 8.0f + (bold ? size / 16.0f : 0);
        }
    }

    /** Draws through the block-entity pipeline (full-bright debug quads + polygon-offset text). */
    public static final class WorldSurface implements Surface {
        private static final int SEGMENTS = 20;

        private final MatrixStack matrices;
        private final VertexConsumerProvider consumers;
        private final CanvasPainter painter;
        private final TextRenderer font;

        public WorldSurface(MatrixStack matrices, VertexConsumerProvider consumers) {
            this(matrices, consumers, null);
        }

        /** @param fontId a resource font id for the text (e.g. MTR's {@code mtr:mtr}), or null for vanilla. */
        public WorldSurface(MatrixStack matrices, VertexConsumerProvider consumers,
                            net.minecraft.util.Identifier fontId) {
            this.matrices = matrices;
            this.consumers = consumers;
            this.painter = new CanvasPainter(matrices, consumers).withFont(fontId);
            this.font = MinecraftClient.getInstance().textRenderer;
        }

        private static float z(int layer) {
            // Painter text sits at -1.2; everything else stacks in front of the sheet behind it.
            return -0.15f * layer;
        }

        @Override
        public void rect(float x1, float y1, float x2, float y2, int argb, int layer) {
            painter.quad(x1, y1, x2, y2, z(layer), argb);
        }

        @Override
        public void poly(float[] xs, float[] ys, int argb, int layer) {
            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            float depth = z(layer);
            // Triangle fan from the first vertex; each triangle is a quad with a repeated corner.
            for (int i = 1; i + 1 < xs.length; i++) {
                CanvasPainter.quad3d(buffer, matrix,
                        xs[0], ys[0], depth, xs[i], ys[i], depth,
                        xs[i + 1], ys[i + 1], depth, xs[i + 1], ys[i + 1], depth, argb);
            }
        }

        @Override
        public void disc(float cx, float cy, float radius, int argb, int layer) {
            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            float depth = z(layer);
            for (int i = 0; i < SEGMENTS; i++) {
                float a1 = (float) (2 * Math.PI * i / SEGMENTS);
                float a2 = (float) (2 * Math.PI * (i + 1) / SEGMENTS);
                CanvasPainter.quad3d(buffer, matrix, cx, cy, depth,
                        cx + radius * MathHelper.cos(a1), cy + radius * MathHelper.sin(a1), depth,
                        cx + radius * MathHelper.cos(a2), cy + radius * MathHelper.sin(a2), depth,
                        cx, cy, depth, argb);
            }
        }

        @Override
        public void text(String string, float x, float y, float size, int argb, boolean bold) {
            painter.text(string, x, y, size, argb);
            if (bold) {
                painter.text(string, x + size / 16.0f, y, size, argb);
            }
        }

        @Override
        public float width(String string, float size, boolean bold) {
            if (string == null || string.isEmpty()) {
                return 0;
            }
            return painter.width(string, size) + (bold ? size / 16.0f : 0);
        }
    }
}
