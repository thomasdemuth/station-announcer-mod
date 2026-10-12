package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.mtr.RouteBullets;
import com.stationannouncer.mtraddon.LineStyles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.mtr.core.data.Route;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "Line style…" editor opened from MTR's Edit Route screen, Subway
 * Builder style: the bullet's TEXT, SHAPE (circle / diamond / square /
 * hexagon), COLOUR (MTA palette, hex, hue/saturation/brightness) and
 * LETTERING (auto / white / black), with live previews of the bullet on a
 * sign, a PIDS row and the Map+ map, and every other line's bullet beside it.
 *
 * <p>Everything applies to the whole LINE — every MTR route whose name shares
 * the part before {@code ||} (both directions). Shape, text and lettering go
 * to {@link LineStyles} on the server; the colour and the text are also
 * written into MTR's own routes (route colour + route number), so MTR's
 * screens, the PIDS's route-number bullets and the Map+ ribbons all agree.
 * The route this screen was opened from is changed in place: MTR's Edit Route
 * screen reloads its fields from it when we go back and saves it on close.</p>
 */
@Environment(EnvType.CLIENT)
public final class LineStyleScreen extends FlatPaScreen {
    private static final int HIT_SHAPE = 1;
    private static final int HIT_SWATCH = 2;
    private static final int HIT_INK = 3;
    private static final int HIT_OTHER = 4;
    private static final int HIT_AUTO = 5;
    private static final int HIT_RESET = 6;
    private static final int HIT_SAVE = 7;
    private static final int HIT_CANCEL = 8;

    private static final int SLIDER_HUE = 1;
    private static final int SLIDER_SAT = 2;
    private static final int SLIDER_VAL = 3;

    private static final int PAPER = 0xFFF4F1EA;
    private static final int SIGN_BLACK = 0xFF141414;
    private static final int PIDS_ROW = 0xFF2A2A2E;

    /** Name, colour. The MTA trunk colours first, then a general set. */
    private record Swatch(String name, int rgb) {
    }

    private static final Swatch[] PALETTE = {
            new Swatch("1 2 3 · 7 Av red", 0xEE352E),
            new Swatch("4 5 6 · Lexington green", 0x00933C),
            new Swatch("7 · Flushing purple", 0xB933AD),
            new Swatch("A C E · 8 Av blue", 0x0039A6),
            new Swatch("B D F M · 6 Av orange", 0xFF6319),
            new Swatch("G · Crosstown lime", 0x6CBE45),
            new Swatch("J Z · Nassau brown", 0x996633),
            new Swatch("L · Canarsie grey", 0xA7A9AC),
            new Swatch("N Q R W · Broadway yellow", 0xFCCC0A),
            new Swatch("S · Shuttle grey", 0x808183),
            new Swatch("T · 2 Av teal", 0x00ADD0),
            new Swatch("SIR · Staten Island navy", 0x1D2F6F),
            new Swatch("Crimson", 0xC8102E),
            new Swatch("Amber", 0xF7941D),
            new Swatch("Gold", 0xD4A017),
            new Swatch("Leaf", 0x8DC63F),
            new Swatch("Forest", 0x006B3F),
            new Swatch("Aqua", 0x00B2A9),
            new Swatch("Sky", 0x4FA3E0),
            new Swatch("Cobalt", 0x0072CE),
            new Swatch("Violet", 0x6A3D9A),
            new Swatch("Magenta", 0xE5007E),
            new Swatch("Pink", 0xF28AB0),
            new Swatch("Charcoal", 0x3C3C3C),
    };

    private static final String[] INK_LABELS = {"Auto", "White", "Black"};
    private static final String[] SHAPE_NAMES = {"Circle", "Diamond", "Square", "Hexagon"};
    /** Route numbers that name a direction rather than a service — never offered as bullet text. */
    private static final Set<String> DIRECTION_TOKENS = Set.of("IN", "OUT", "OU", "NB", "SB", "EB", "WB",
            "CW", "CCW", "UP", "DN");

    private final Route route;
    private final Screen parent;
    private final String line;
    private final String direction;
    private final String autoLabel;
    private final String initialText;
    private final int routeCount;
    private final List<PosterLayout.LineOption> others = new ArrayList<>();

    private LineStyles.Shape shape;
    private LineStyles.TextColor ink;
    private int rgb;
    private float hue;
    private float sat;
    private float val;

    private final FlatUi.TextBox labelBox;
    private final FlatUi.TextBox hexBox;

    private int scroll;
    private int contentHeight;
    private int viewTop;
    private int viewHeight;
    private int inkX;
    private int inkW;
    private int hoveredSwatch = -1;

    public LineStyleScreen(Route route, Screen parent) {
        super(Text.literal("Line style"));
        this.route = route;
        this.parent = parent;
        this.line = LineStyles.lineKey(route.getName());
        this.direction = AddonUi.splitLineAndDirection(route.getName())[1];
        this.autoLabel = RouteBullets.label(route.getName());
        LineStyles.Style style = ClientLineStyles.style(line);
        this.shape = style.shape();
        this.ink = style.text();
        setColor(route.getColor() & 0xFFFFFF);

        String text = style.label();
        if (text.isEmpty()) {
            String number = AddonUi.firstLang(route.getRouteNumber() == null ? "" : route.getRouteNumber()).trim();
            if (!number.isEmpty() && number.length() <= LineStyles.MAX_LABEL_LENGTH
                    && !DIRECTION_TOKENS.contains(number.toUpperCase(Locale.ROOT))) {
                text = number;
            }
        }
        this.initialText = text;

        int count = 0;
        try {
            for (Route other : MinecraftClientData.getDashboardInstance().routes) {
                if (line.equals(LineStyles.lineKey(other.getName()))) {
                    count++;
                }
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync
        }
        this.routeCount = Math.max(1, count);
        for (PosterLayout.LineOption option : PosterLayout.lines()) {
            if (!line.equals(LineStyles.lineKey(option.name()))) {
                others.add(option);
            }
        }

        this.labelBox = new FlatUi.TextBox(net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                LineStyles.MAX_LABEL_LENGTH, false);
        labelBox.load(text);
        labelBox.placeholder = autoLabel + " (auto)";
        this.hexBox = new FlatUi.TextBox(net.minecraft.client.MinecraftClient.getInstance().textRenderer, 7, false);
        hexBox.load(hex(rgb));
        hexBox.onChange(value -> {
            String digits = value.startsWith("#") ? value.substring(1) : value;
            if (digits.length() == 6) {
                try {
                    setColor(Integer.parseInt(digits, 16));
                } catch (NumberFormatException ignored) {
                    // not hex yet — keep typing
                }
            }
        });
    }

    // ------------------------------------------------------------------ state

    private void setColor(int newRgb) {
        rgb = newRgb & 0xFFFFFF;
        float r = (rgb >> 16 & 0xFF) / 255f;
        float g = (rgb >> 8 & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        val = max;
        sat = max <= 0 ? 0 : delta / max;
        if (delta > 0) {
            float h;
            if (max == r) {
                h = (g - b) / delta;
            } else if (max == g) {
                h = 2 + (b - r) / delta;
            } else {
                h = 4 + (r - g) / delta;
            }
            hue = ((h / 6f) % 1f + 1f) % 1f;
        }
        // grey: keep the hue the sliders were on
    }

    private void setFromHsv() {
        rgb = MathHelper.hsvToRgb(MathHelper.clamp(hue, 0, 0.9999f), sat, val) & 0xFFFFFF;
        if (!hexBox.isFocused()) {
            hexBox.load(hex(rgb));
        }
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private String labelText() {
        return LineStyles.cleanLabel(labelBox.getText());
    }

    private RouteBullets.Bullet bullet() {
        int color = 0xFF000000 | rgb;
        LineStyles.Style style = new LineStyles.Style(shape, labelText(), ink);
        return new RouteBullets.Bullet(style.labelOr(autoLabel), color, shape, style.darkText(color));
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);

        // Top bar: title, scope, Cancel / Save.
        c.drawText(textRenderer, "Line style", 8, 8, FlatUi.TEXT, false);
        int titleEnd = 8 + textWidth("Line style") + 8;
        String scope = line + " · " + (routeCount == 1 ? "1 route" : routeCount + " routes (every direction)");
        c.drawText(textRenderer, trim(scope, Math.max(20, width - titleEnd - 120)), titleEnd, 8, FlatUi.TEXT_DIM, false);
        button(c, mx, my, "Save", width - 56, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.PRIMARY, true, HIT_SAVE, 0);
        button(c, mx, my, "Cancel", width - 110, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.FLAT, true, HIT_CANCEL, 0);

        int top = 26;
        int bottom = height - 6;
        int previewW = MathHelper.clamp(Math.round(width * 0.42f), 150, 270);
        FlatUi.pane(c, 6, top, previewW, bottom - top);
        drawPreview(c, 6, top, previewW, bottom - top);

        int ix = 6 + previewW + 6;
        int iw = width - ix - 6;
        FlatUi.pane(c, ix, top, iw, bottom - top);
        viewTop = top + 1;
        viewHeight = bottom - top - 2;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentHeight - viewHeight));
        beginClip(c, ix + 1, viewTop, iw - 2, viewHeight);
        int end = drawInspector(c, mx, my, ix + 8, viewTop + 6 - scroll, iw - 16);
        contentHeight = end + scroll - viewTop + 6;
        endClip(c);
        FlatUi.scrollThumb(c, ix + iw - 1, viewTop, viewHeight, contentHeight, scroll);
    }

    // ---------------------------------------------------------------- preview

    private void drawPreview(DrawContext c, int x, int y, int w, int h) {
        RouteBullets.Bullet bullet = bullet();
        int pad = 8;
        int inner = w - pad * 2;

        // Hero: the bullet on a sign black and on paper, side by side, so the lettering is judged on both.
        // Three context strips below take 3 × (heading 11 + strip 26 + gap 8); the hero gets the rest.
        int heroH = MathHelper.clamp(h - pad * 2 - 8 - 3 * 45, 40, 84);
        int half = inner / 2;
        FlatUi.rect(c, x + pad, y + pad, half, heroH, SIGN_BLACK);
        FlatUi.rect(c, x + pad + half, y + pad, inner - half, heroH, PAPER);
        float heroR = Math.min(heroH * 0.34f, half * 0.3f);
        drawBullet(c, bullet, x + pad + half / 2f, y + pad + heroH / 2f, heroR);
        drawBullet(c, bullet, x + pad + half + (inner - half) / 2f, y + pad + heroH / 2f, heroR);
        int cy = y + pad + heroH + 8;

        // On a sign: an MTA black band with the station name and the services that stop.
        FlatUi.heading(c, textRenderer, "On a sign", x + pad, cy);
        cy += 11;
        int signH = 24;
        FlatUi.rect(c, x + pad, cy, inner, signH, SIGN_BLACK);
        FlatUi.rect(c, x + pad, cy + 2, inner, 1, 0xFFFFFFFF);
        c.drawText(textRenderer, "Canal St", x + pad + 6, cy + 10, 0xFFFFFFFF, false);
        float br = 7.5f;
        float bx = x + pad + inner - 6 - br;
        List<RouteBullets.Bullet> row = new ArrayList<>();
        row.add(bullet);
        for (int i = 0; i < others.size() && row.size() < 3; i++) {
            row.add(others.get(i).bullet());
        }
        for (int i = row.size() - 1; i >= 0; i--) {
            drawBullet(c, row.get(i), bx, cy + 3 + (signH - 3) / 2f, br);
            bx -= br * 2 + 4;
        }
        cy += signH + 8;

        // On a PIDS: one departure row.
        FlatUi.heading(c, textRenderer, "On a PIDS", x + pad, cy);
        cy += 11;
        int pidsH = 24;
        FlatUi.rect(c, x + pad, cy, inner, pidsH, 0xFF000000);
        FlatUi.rect(c, x + pad + 2, cy + 2, inner - 4, pidsH - 4, PIDS_ROW);
        drawBullet(c, bullet, x + pad + 5 + 8, cy + pidsH / 2f, 8);
        String dest = direction.isEmpty() ? line : direction;
        c.drawText(textRenderer, trim(dest, inner - 60), x + pad + 26, cy + 8, 0xFFFFFFFF, false);
        c.drawText(textRenderer, "3 min", x + pad + inner - 6 - textWidth("3 min"), cy + 8, 0xFFFFFFFF, false);
        cy += pidsH + 8;

        // On the map: a ribbon in the line colour, two stops, the bullet after a station name.
        FlatUi.heading(c, textRenderer, "On Map+", x + pad, cy);
        cy += 11;
        int mapH = MathHelper.clamp(y + h - pad - cy, 26, 34);
        if (cy + mapH > y + h - 2) {
            return;
        }
        FlatUi.rect(c, x + pad, cy, inner, mapH, PAPER);
        int ribbonY = cy + mapH / 2 + 4;
        FlatUi.rect(c, x + pad + 4, ribbonY - 2, inner - 8, 5, 0xFF000000 | rgb);
        int stop1 = x + pad + inner / 4;
        int stop2 = x + pad + inner * 3 / 4;
        for (int stop : new int[]{stop1, stop2}) {
            FlatUi.rect(c, stop - 3, ribbonY - 3, 7, 7, 0xFF1A1A1A);
            FlatUi.rect(c, stop - 2, ribbonY - 2, 5, 5, 0xFFFFFFFF);
        }
        c.drawText(textRenderer, "Canal St", stop1 - 10, cy + 3, 0xFF1A1A1A, false);
        drawBullet(c, bullet, stop1 - 10 + textWidth("Canal St") + 9, cy + 7, 5.5f);
    }

    /**
     * A bullet through the same code the posters and signs use, drawn at
     * PHYSICAL pixel resolution (the matrix undoes the GUI scale) so its edges
     * are smooth rather than stepped in GUI pixels.
     */
    private void drawBullet(DrawContext c, RouteBullets.Bullet bullet, float cx, float cy, float radius) {
        float k = (float) Math.max(1.0, client == null ? 1.0 : client.getWindow().getScaleFactor());
        c.getMatrices().push();
        c.getMatrices().scale(1 / k, 1 / k, 1);
        PosterLayout.GuiSurface surface = new PosterLayout.GuiSurface(c, 0, 0, k);
        PosterLayout.bulletShape(surface, bullet, cx, cy, radius, 2);
        PosterLayout.bulletLabel(surface, bullet, cx, cy, radius);
        c.getMatrices().pop();
    }

    // -------------------------------------------------------------- inspector

    private int drawInspector(DrawContext c, int mx, int my, int x, int y, int w) {
        // Bullet text.
        FlatUi.heading(c, textRenderer, "Bullet text", x, y);
        y += 11;
        box(c, labelBox, x, y, 44, 16, mx, my);
        button(c, mx, my, "Auto", x + 50, y, 36, 16, FlatUi.ButtonStyle.GHOST, true, HIT_AUTO, 0);
        String hint = labelText().isEmpty() ? "from the name: " + autoLabel : "also the MTR route number";
        c.drawText(textRenderer, trim(hint, w - 92), x + 92, y + 4, FlatUi.TEXT_FAINT, false);
        y += 20;
        String clash = clash();
        if (clash != null) {
            c.drawText(textRenderer, trim("Same bullet as " + clash, w), x, y, 0xFFE5A84D, false);
            y += 11;
        }
        y += 4;

        // Shape.
        FlatUi.heading(c, textRenderer, "Shape", x, y);
        y += 11;
        LineStyles.Shape[] shapes = LineStyles.Shape.values();
        int gap = 4;
        int tile = MathHelper.clamp((w - gap * (shapes.length - 1)) / shapes.length, 30, 48);
        RouteBullets.Bullet preview = bullet();
        for (int i = 0; i < shapes.length; i++) {
            int tx = x + i * (tile + gap);
            boolean on = shapes[i] == shape;
            boolean hovered = FlatUi.inside(mx, my, tx, y, tile, tile + 10);
            FlatUi.rect(c, tx, y, tile, tile + 10, on ? FlatUi.SELECTED : hovered ? FlatUi.PANE_RAISED : FlatUi.INPUT);
            FlatUi.outline(c, tx, y, tile, tile + 10, on ? FlatUi.ACCENT : hovered ? FlatUi.BORDER_STRONG : FlatUi.BORDER);
            drawBullet(c, preview.withShape(shapes[i]), tx + tile / 2f, y + tile / 2f, tile * 0.28f);
            String name = SHAPE_NAMES[i];
            c.drawText(textRenderer, name, tx + (tile - textWidth(name)) / 2, y + tile - 1,
                    on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hit(tx, y, tile, tile + 10, HIT_SHAPE, i);
        }
        y += tile + 10 + 8;

        // Colour.
        FlatUi.heading(c, textRenderer, "Colour", x, y);
        y += 11;
        int sw = 14;
        int perRow = Math.max(4, (w + 2) / (sw + 2));
        hoveredSwatch = -1;
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = x + (i % perRow) * (sw + 2);
            int sy = y + (i / perRow) * (sw + 2);
            boolean hovered = FlatUi.inside(mx, my, sx, sy, sw, sw);
            if (hovered) {
                hoveredSwatch = i;
            }
            FlatUi.rect(c, sx, sy, sw, sw, 0xFF000000 | PALETTE[i].rgb());
            if (PALETTE[i].rgb() == rgb) {
                FlatUi.outline(c, sx - 1, sy - 1, sw + 2, sw + 2, FlatUi.TEXT);
            } else if (hovered) {
                FlatUi.outline(c, sx, sy, sw, sw, FlatUi.TEXT_DIM);
            }
            hit(sx, sy, sw, sw, HIT_SWATCH, i);
        }
        y += ((PALETTE.length + perRow - 1) / perRow) * (sw + 2) + 2;
        String swatchName = hoveredSwatch >= 0 ? PALETTE[hoveredSwatch].name() + "  " + hex(PALETTE[hoveredSwatch].rgb())
                : paletteName();
        c.drawText(textRenderer, trim(swatchName, w), x, y, FlatUi.TEXT_DIM, false);
        y += 12;
        FlatUi.rect(c, x, y, 16, 16, 0xFF000000 | rgb);
        FlatUi.outline(c, x, y, 16, 16, FlatUi.BORDER_STRONG);
        box(c, hexBox, x + 20, y, 60, 16, mx, my);
        c.drawText(textRenderer, "the line colour in MTR", x + 86, y + 4, FlatUi.TEXT_FAINT, false);
        y += 20;
        slider(c, mx, my, SLIDER_HUE, "Hue", 30, Math.round(hue * 360), 0, 360, Math.round(hue * 360) + "°", x, y, w);
        y += 14;
        slider(c, mx, my, SLIDER_SAT, "Sat", 30, Math.round(sat * 100), 0, 100, Math.round(sat * 100) + "%", x, y, w);
        y += 14;
        slider(c, mx, my, SLIDER_VAL, "Bright", 30, Math.round(val * 100), 0, 100, Math.round(val * 100) + "%", x, y, w);
        y += 18;

        // Lettering.
        FlatUi.heading(c, textRenderer, "Lettering", x, y);
        y += 11;
        inkX = x;
        inkW = Math.min(w, 150);
        FlatUi.segmented(c, textRenderer, INK_LABELS, ink.ordinal(), inkX, y, inkW, 16, mx, my);
        hit(inkX, y, inkW, 16, HIT_INK, 0);
        y += 20;
        if (ink == LineStyles.TextColor.AUTO) {
            c.drawText(textRenderer, trim("Auto: " + (bullet().darkText() ? "black" : "white")
                    + " on this colour", w), x, y, FlatUi.TEXT_FAINT, false);
            y += 11;
        }
        y += 4;

        // Other lines.
        if (!others.isEmpty()) {
            FlatUi.heading(c, textRenderer, "Other lines", x, y);
            y += 11;
            int pitch = 18;
            int perLine = Math.max(1, (w + 2) / pitch);
            String hovered = null;
            for (int i = 0; i < others.size(); i++) {
                int ox = x + (i % perLine) * pitch;
                int oy = y + (i / perLine) * pitch;
                PosterLayout.LineOption option = others.get(i);
                if (FlatUi.inside(mx, my, ox, oy, 16, 16)) {
                    hovered = option.name();
                    FlatUi.rect(c, ox, oy, 16, 16, FlatUi.PANE_RAISED);
                }
                drawBullet(c, option.bullet(), ox + 8, oy + 8, 6.5f);
                hit(ox, oy, 16, 16, HIT_OTHER, i);
            }
            y += ((others.size() + perLine - 1) / perLine) * pitch + 2;
            c.drawText(textRenderer, trim(hovered != null ? hovered + " · click: its colour, shift: its look"
                    : "Click a line to use its colour", w), x, y, FlatUi.TEXT_FAINT, false);
            y += 14;
        }

        button(c, mx, my, "Reset to default", x, y, 96, 16, FlatUi.ButtonStyle.GHOST, true, HIT_RESET, 0);
        return y + 16;
    }

    /** The name of the palette swatch matching the current colour, or its hex. */
    private String paletteName() {
        for (Swatch swatch : PALETTE) {
            if (swatch.rgb() == rgb) {
                return swatch.name();
            }
        }
        return "Custom " + hex(rgb);
    }

    /** Another line whose bullet would look the same (same text, shape and colour), or null. */
    private String clash() {
        RouteBullets.Bullet mine = bullet();
        for (PosterLayout.LineOption option : others) {
            RouteBullets.Bullet theirs = option.bullet();
            if (theirs.label().equalsIgnoreCase(mine.label()) && theirs.shape() == mine.shape()
                    && (theirs.color() & 0xFFFFFF) == rgb) {
                return option.name();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        switch (id) {
            case HIT_SHAPE -> shape = LineStyles.Shape.byOrdinal(arg);
            case HIT_SWATCH -> {
                setColor(PALETTE[arg].rgb());
                hexBox.load(hex(rgb));
            }
            case HIT_INK -> {
                int index = MathHelper.clamp((int) ((mx - inkX) * INK_LABELS.length / Math.max(1, inkW)), 0,
                        INK_LABELS.length - 1);
                ink = LineStyles.TextColor.byOrdinal(index);
            }
            case HIT_OTHER -> {
                if (arg >= 0 && arg < others.size()) {
                    RouteBullets.Bullet theirs = others.get(arg).bullet();
                    setColor(theirs.color());
                    hexBox.load(hex(rgb));
                    if (hasShiftDown()) {
                        LineStyles.Style style = ClientLineStyles.style(others.get(arg).name());
                        shape = style.shape();
                        ink = style.text();
                    }
                }
            }
            case HIT_AUTO -> labelBox.load("");
            case HIT_RESET -> {
                shape = LineStyles.Shape.CIRCLE;
                ink = LineStyles.TextColor.AUTO;
                labelBox.load("");
                toast("Reset — the colour stays (it is MTR's line colour)", false);
            }
            case HIT_SAVE -> save();
            case HIT_CANCEL -> close();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        switch (key) {
            case SLIDER_HUE -> hue = value / 360f;
            case SLIDER_SAT -> sat = value / 100f;
            case SLIDER_VAL -> val = value / 100f;
            default -> {
                return;
            }
        }
        setFromHsv();
    }

    @Override
    protected boolean onEnter() {
        save();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = MathHelper.clamp(scroll - (int) Math.round(vertical * 14), 0, Math.max(0, contentHeight - viewHeight));
        return true;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    /**
     * Dev-only, through ClientLineStyles.devHook: {@code shape N}, {@code color RRGGBB},
     * {@code text X} ("-" = auto), {@code ink N}, {@code other N [shift]},
     * {@code scroll PX}, {@code reset}, {@code save}, {@code cancel}.
     */
    void devCommand(String args) {
        String[] a = args.split("\\s+");
        String arg = a.length > 1 ? a[1] : "";
        try {
            switch (a[0]) {
                case "shape" -> shape = LineStyles.Shape.byOrdinal(Integer.parseInt(arg));
                case "color" -> {
                    setColor(Integer.parseInt(arg.replace("#", ""), 16));
                    hexBox.load(hex(rgb));
                }
                case "text" -> labelBox.load(arg.equals("-") ? "" : arg);
                case "ink" -> ink = LineStyles.TextColor.byOrdinal(Integer.parseInt(arg));
                case "other" -> {
                    int index = Integer.parseInt(arg);
                    if (index >= 0 && index < others.size()) {
                        setColor(others.get(index).bullet().color());
                        hexBox.load(hex(rgb));
                        if (a.length > 2) {
                            LineStyles.Style style = ClientLineStyles.style(others.get(index).name());
                            shape = style.shape();
                            ink = style.text();
                        }
                    }
                }
                case "scroll" -> scroll = Integer.parseInt(arg);
                case "reset" -> onHit(HIT_RESET, 0, 0, 0);
                case "save" -> save();
                case "cancel" -> close();
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {
            // dev typo
        }
    }

    // ------------------------------------------------------------------- save

    private void save() {
        String text = labelText();
        LineStyles.Style style = new LineStyles.Style(shape, text, ink);
        ClientLineStyles.send(line, style);

        // Colour and route number belong to MTR: write them to every route of the line.
        // The route number only when the text was actually edited — operators who use it
        // for something else (a direction, a long code) keep theirs until they change it here.
        boolean textChanged = !text.equals(LineStyles.cleanLabel(initialText));
        route.setColor(rgb);
        if (textChanged) {
            route.setRouteNumber(text);
        }
        int updated = 0;
        try {
            MinecraftClientData dashboard = MinecraftClientData.getDashboardInstance();
            for (Route other : dashboard.routes) {
                if (other.getId() == route.getId() || !line.equals(LineStyles.lineKey(other.getName()))) {
                    continue;
                }
                boolean changed = false;
                if ((other.getColor() & 0xFFFFFF) != rgb) {
                    other.setColor(rgb);
                    changed = true;
                }
                if (textChanged && !text.equals(other.getRouteNumber())) {
                    other.setRouteNumber(text);
                    changed = true;
                }
                if (changed) {
                    InitClient.REGISTRY_CLIENT.sendPacketToServer(
                            new PacketUpdateData(new UpdateDataRequest(dashboard).addRoute(other)));
                    updated++;
                }
            }
            // This route too, now: MTR's own screen sends it again on close, identically.
            InitClient.REGISTRY_CLIENT.sendPacketToServer(
                    new PacketUpdateData(new UpdateDataRequest(dashboard).addRoute(route)));
        } catch (Exception e) {
            com.stationannouncer.StationAnnouncer.LOGGER.warn("Line style: could not update the line's routes", e);
        }
        if (updated > 0) {
            com.stationannouncer.StationAnnouncer.LOGGER.debug("Line style: updated {} sibling routes of {}", updated, line);
        }
        close();
    }
}
