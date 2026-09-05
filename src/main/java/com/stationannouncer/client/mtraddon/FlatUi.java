package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.MathHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A flat, hand-drawn widget kit for the poster tools — deliberately NOT the
 * vanilla look (Thomas: "don't worry about it feeling Minecrafty"). Everything is
 * rectangles and text on a dark ground: panes, flat and primary buttons, chips,
 * segmented controls, and {@link TextBox}, a from-scratch text input with word
 * wrap, caret, selection, clipboard and click-to-place.
 *
 * <p>Stateless drawing helpers take the mouse position and return whether they
 * are hovered; screens do their own hit-testing in {@code mouseClicked} with the
 * same rectangles. Render-thread only.</p>
 */
@Environment(EnvType.CLIENT)
public final class FlatUi {
    public static final int GROUND = 0xFF151518;
    public static final int PANE = 0xFF1E1E23;
    public static final int PANE_RAISED = 0xFF26262C;
    public static final int BORDER = 0xFF303038;
    public static final int BORDER_STRONG = 0xFF44444E;
    public static final int INPUT = 0xFF111114;
    public static final int HOVER = 0x14FFFFFF;
    public static final int SELECTED = 0x2E3D8BFF;
    public static final int ACCENT = 0xFF3D8BFF;
    public static final int ACCENT_DIM = 0xFF2B63B8;
    public static final int TEXT = 0xFFEDEDF0;
    public static final int TEXT_DIM = 0xFF9A9AA6;
    public static final int TEXT_FAINT = 0xFF63636E;
    public static final int DANGER = 0xFFE5484D;
    public static final int OK = 0xFF43C26B;
    public static final int CARET = 0xFFFFFFFF;
    public static final int SELECTION = 0x663D8BFF;

    public static final int BUTTON_HEIGHT = 18;

    private FlatUi() {
    }

    // ----------------------------------------------------------------- basics

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void rect(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x, y, x + w, y + h, argb);
    }

    public static void outline(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x, y, x + w, y + 1, argb);
        c.fill(x, y + h - 1, x + w, y + h, argb);
        c.fill(x, y, x + 1, y + h, argb);
        c.fill(x + w - 1, y, x + w, y + h, argb);
    }

    /** A pane: filled ground with a hairline border. */
    public static void pane(DrawContext c, int x, int y, int w, int h) {
        rect(c, x, y, w, h, PANE);
        outline(c, x, y, w, h, BORDER);
    }

    /** Small uppercase-style section heading. */
    public static void heading(DrawContext c, TextRenderer font, String text, int x, int y) {
        c.drawText(font, text.toUpperCase(java.util.Locale.ROOT), x, y, TEXT_FAINT, false);
    }

    public static void label(DrawContext c, TextRenderer font, String text, int x, int y) {
        c.drawText(font, text, x, y, TEXT_DIM, false);
    }

    public enum ButtonStyle { FLAT, PRIMARY, DANGER, GHOST }

    /** A flat button; returns true while hovered. {@code enabled=false} draws dimmed and never hovers. */
    public static boolean button(DrawContext c, TextRenderer font, String label, int x, int y, int w, int h,
                                 int mx, int my, ButtonStyle style, boolean enabled) {
        boolean hovered = enabled && inside(mx, my, x, y, w, h);
        int fill;
        int ink;
        switch (style) {
            case PRIMARY -> {
                fill = hovered ? ACCENT : ACCENT_DIM;
                ink = TEXT;
            }
            case DANGER -> {
                fill = hovered ? 0xFF7A2A2E : 0xFF3A2224;
                ink = hovered ? TEXT : DANGER;
            }
            case GHOST -> {
                fill = hovered ? PANE_RAISED : 0x00000000;
                ink = hovered ? TEXT : TEXT_DIM;
            }
            default -> {
                fill = hovered ? 0xFF34343C : PANE_RAISED;
                ink = TEXT;
            }
        }
        if (!enabled) {
            ink = TEXT_FAINT;
        }
        rect(c, x, y, w, h, fill);
        if (style != ButtonStyle.GHOST || hovered) {
            outline(c, x, y, w, h, style == ButtonStyle.PRIMARY ? fill : BORDER_STRONG);
        }
        int tw = font.getWidth(label);
        c.drawText(font, label, x + (w - tw) / 2, y + (h - 8) / 2, ink, false);
        return hovered;
    }

    public static boolean button(DrawContext c, TextRenderer font, String label, int x, int y, int w, int h,
                                 int mx, int my, ButtonStyle style) {
        return button(c, font, label, x, y, w, h, mx, my, style, true);
    }

    /** A small square icon button (a glyph); returns hovered. */
    public static boolean iconButton(DrawContext c, TextRenderer font, String glyph, int x, int y, int size,
                                     int mx, int my, int ink) {
        boolean hovered = inside(mx, my, x, y, size, size);
        if (hovered) {
            rect(c, x, y, size, size, PANE_RAISED);
            outline(c, x, y, size, size, BORDER_STRONG);
        }
        int tw = font.getWidth(glyph);
        c.drawText(font, glyph, x + (size - tw) / 2, y + (size - 8) / 2, hovered ? TEXT : ink, false);
        return hovered;
    }

    /** Coloured pill; returns its width. */
    public static int chip(DrawContext c, TextRenderer font, String label, int x, int y, int color, boolean hovered) {
        int w = font.getWidth(label) + 8;
        rect(c, x, y, w, 12, color);
        if (hovered) {
            outline(c, x, y, w, 12, TEXT);
        }
        c.drawText(font, label, x + 4, y + 2, AddonUi.readableOn(color), false);
        return w;
    }

    /**
     * A segmented control: one row of equal cells, the chosen one filled in the
     * accent. Returns the index under the mouse, or -1.
     */
    public static int segmented(DrawContext c, TextRenderer font, String[] labels, int chosen,
                                int x, int y, int w, int h, int mx, int my) {
        int n = labels.length;
        int hoveredIndex = -1;
        for (int i = 0; i < n; i++) {
            int cx = x + w * i / n;
            int cw = x + w * (i + 1) / n - cx;
            boolean hovered = inside(mx, my, cx, y, cw, h);
            if (hovered) {
                hoveredIndex = i;
            }
            boolean on = i == chosen;
            rect(c, cx, y, cw, h, on ? ACCENT_DIM : hovered ? 0xFF34343C : PANE_RAISED);
            int tw = font.getWidth(labels[i]);
            c.drawText(font, labels[i], cx + (cw - tw) / 2, y + (h - 8) / 2, on ? TEXT : TEXT_DIM, false);
        }
        outline(c, x, y, w, h, BORDER_STRONG);
        return hoveredIndex;
    }

    /** Thin scroll thumb along a pane's right edge. */
    public static void scrollThumb(DrawContext c, int right, int top, int viewHeight, int contentHeight, int scroll) {
        int max = Math.max(0, contentHeight - viewHeight);
        if (max <= 0) {
            return;
        }
        int track = viewHeight - 4;
        int thumb = Math.max(14, track * viewHeight / contentHeight);
        int thumbY = top + 2 + (track - thumb) * scroll / max;
        rect(c, right - 3, thumbY, 2, thumb, 0x55FFFFFF);
    }

    // ---------------------------------------------------------------- textbox

    /**
     * A from-scratch text input. Single-line boxes scroll horizontally to keep
     * the caret in view; multi-line boxes word-wrap to their width and scroll
     * vertically. Supports typing, backspace/delete, arrows (with shift for
     * selection), home/end, ctrl+A/C/X/V, click-to-place and drag-select.
     * Enter inserts a newline in multi-line boxes.
     */
    public static final class TextBox {
        private static final int PAD = 4;
        private static final int LINE = 10;

        public int x;
        public int y;
        public int w;
        public int h;
        public boolean multiline;
        public String placeholder = "";

        private final TextRenderer font;
        private final int maxLength;
        private String text = "";
        private int cursor;
        /** Selection anchor, or -1 when nothing is selected. */
        private int anchor = -1;
        private boolean focused;
        private boolean dragging;
        private int scrollX;
        private int scrollLine;
        private Consumer<String> onChange;
        private long blinkStart;

        /** Visual lines as [start, end) index ranges into {@link #text}. */
        private final List<int[]> lines = new ArrayList<>();
        private int wrappedWidth = -1;
        private String wrappedText;

        public TextBox(TextRenderer font, int maxLength, boolean multiline) {
            this.font = font;
            this.maxLength = maxLength;
            this.multiline = multiline;
        }

        public void setBounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public void onChange(Consumer<String> listener) {
            this.onChange = listener;
        }

        public String getText() {
            return text;
        }

        /** Replaces the content WITHOUT firing the change listener (loading a value). */
        public void load(String value) {
            text = value == null ? "" : value;
            if (text.length() > maxLength) {
                text = text.substring(0, maxLength);
            }
            cursor = text.length();
            anchor = -1;
            scrollX = 0;
            scrollLine = 0;
            wrappedWidth = -1;
        }

        public boolean isFocused() {
            return focused;
        }

        public void setFocused(boolean focused) {
            this.focused = focused;
            if (!focused) {
                anchor = -1;
                dragging = false;
            } else {
                blinkStart = System.currentTimeMillis();
            }
        }

        public boolean contains(double mx, double my) {
            return inside(mx, my, x, y, w, h);
        }

        // -- editing

        private boolean hasSelection() {
            return anchor >= 0 && anchor != cursor;
        }

        private int selStart() {
            return Math.min(anchor, cursor);
        }

        private int selEnd() {
            return Math.max(anchor, cursor);
        }

        private void deleteSelection() {
            if (!hasSelection()) {
                return;
            }
            int s = selStart();
            int e = selEnd();
            text = text.substring(0, s) + text.substring(e);
            cursor = s;
            anchor = -1;
            changed();
        }

        /** Inserts at the caret (replacing any selection); the poster's token buttons use this. */
        public void insert(String insertion) {
            if (insertion == null || insertion.isEmpty()) {
                return;
            }
            deleteSelection();
            String clean = multiline ? insertion : insertion.replace('\n', ' ');
            int room = maxLength - text.length();
            if (room <= 0) {
                return;
            }
            if (clean.length() > room) {
                clean = clean.substring(0, room);
            }
            text = text.substring(0, cursor) + clean + text.substring(cursor);
            cursor += clean.length();
            changed();
        }

        /** Inserts a token, padding it with spaces so it reads as a word. */
        public void insertToken(String token) {
            String before = text.substring(0, hasSelection() ? selStart() : cursor);
            String after = text.substring(hasSelection() ? selEnd() : cursor);
            String padded = token;
            if (!before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1))) {
                padded = " " + padded;
            }
            if (!after.isEmpty() && !Character.isWhitespace(after.charAt(0))) {
                padded = padded + " ";
            }
            insert(padded);
        }

        private void changed() {
            wrappedWidth = -1;
            blinkStart = System.currentTimeMillis();
            if (onChange != null) {
                onChange.accept(text);
            }
        }

        private void moveCursor(int to, boolean extend) {
            to = MathHelper.clamp(to, 0, text.length());
            if (extend) {
                if (anchor < 0) {
                    anchor = cursor;
                }
            } else {
                anchor = -1;
            }
            cursor = to;
            blinkStart = System.currentTimeMillis();
        }

        public boolean charTyped(char chr) {
            if (!focused || chr < ' ') {
                return false;
            }
            insert(String.valueOf(chr));
            return true;
        }

        public boolean keyPressed(int key, int modifiers) {
            if (!focused) {
                return false;
            }
            boolean shift = Screen.hasShiftDown();
            boolean ctrl = Screen.hasControlDown();
            switch (key) {
                case 259 -> { // backspace
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (cursor > 0) {
                        int from = ctrl ? wordStart(cursor) : cursor - 1;
                        text = text.substring(0, from) + text.substring(cursor);
                        cursor = from;
                        changed();
                    }
                    return true;
                }
                case 261 -> { // delete
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (cursor < text.length()) {
                        text = text.substring(0, cursor) + text.substring(cursor + 1);
                        changed();
                    }
                    return true;
                }
                case 263 -> { // left
                    if (hasSelection() && !shift) {
                        moveCursor(selStart(), false);
                    } else {
                        moveCursor(ctrl ? wordStart(cursor) : cursor - 1, shift);
                    }
                    return true;
                }
                case 262 -> { // right
                    if (hasSelection() && !shift) {
                        moveCursor(selEnd(), false);
                    } else {
                        moveCursor(ctrl ? wordEnd(cursor) : cursor + 1, shift);
                    }
                    return true;
                }
                case 265 -> { // up
                    moveVertical(-1, shift);
                    return true;
                }
                case 264 -> { // down
                    moveVertical(1, shift);
                    return true;
                }
                case 268 -> { // home
                    moveCursor(ctrl ? 0 : lineStartOf(cursor), shift);
                    return true;
                }
                case 269 -> { // end
                    moveCursor(ctrl ? text.length() : lineEndOf(cursor), shift);
                    return true;
                }
                case 257, 335 -> { // enter
                    if (multiline) {
                        insert("\n");
                        return true;
                    }
                    return false;
                }
                default -> {
                }
            }
            if (ctrl) {
                switch (key) {
                    case 65 -> { // A
                        anchor = 0;
                        cursor = text.length();
                        return true;
                    }
                    case 67 -> { // C
                        if (hasSelection()) {
                            MinecraftClient.getInstance().keyboard.setClipboard(text.substring(selStart(), selEnd()));
                        }
                        return true;
                    }
                    case 88 -> { // X
                        if (hasSelection()) {
                            MinecraftClient.getInstance().keyboard.setClipboard(text.substring(selStart(), selEnd()));
                            deleteSelection();
                        }
                        return true;
                    }
                    case 86 -> { // V
                        insert(MinecraftClient.getInstance().keyboard.getClipboard());
                        return true;
                    }
                    default -> {
                    }
                }
            }
            return false;
        }

        private int wordStart(int from) {
            int i = Math.min(from, text.length());
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            return i;
        }

        private int wordEnd(int from) {
            int i = Math.max(0, from);
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return i;
        }

        // -- layout

        private int innerWidth() {
            return Math.max(8, w - PAD * 2);
        }

        private void ensureWrapped() {
            int width = innerWidth();
            if (wrappedWidth == width && text.equals(wrappedText)) {
                return;
            }
            wrappedWidth = width;
            wrappedText = text;
            lines.clear();
            if (!multiline) {
                lines.add(new int[]{0, text.length()});
                return;
            }
            int start = 0;
            int n = text.length();
            while (true) {
                // Hard break?
                int hard = text.indexOf('\n', start);
                int paragraphEnd = hard < 0 ? n : hard;
                int lineStart = start;
                while (true) {
                    // Fit as many characters as possible, preferring a break after whitespace.
                    int end = lineStart;
                    int lastSpace = -1;
                    int i = lineStart;
                    while (i < paragraphEnd) {
                        if (font.getWidth(text.substring(lineStart, i + 1)) > width) {
                            break;
                        }
                        i++;
                        if (Character.isWhitespace(text.charAt(i - 1))) {
                            lastSpace = i;
                        }
                        end = i;
                    }
                    if (end >= paragraphEnd) {
                        lines.add(new int[]{lineStart, paragraphEnd});
                        break;
                    }
                    if (end == lineStart) {
                        end = lineStart + 1; // a glyph wider than the box: force progress
                    } else if (lastSpace > lineStart) {
                        end = lastSpace;
                    }
                    lines.add(new int[]{lineStart, end});
                    lineStart = end;
                }
                if (hard < 0) {
                    break;
                }
                start = hard + 1;
                if (start > n) {
                    break;
                }
                if (start == n) {
                    lines.add(new int[]{n, n});
                    break;
                }
            }
            if (lines.isEmpty()) {
                lines.add(new int[]{0, 0});
            }
        }

        /** Visual line index holding the given text index. */
        private int lineOf(int index) {
            ensureWrapped();
            for (int i = 0; i < lines.size(); i++) {
                int[] line = lines.get(i);
                boolean last = i == lines.size() - 1;
                if (index < line[1] || (index == line[1] && (last || endsHard(line)))) {
                    return i;
                }
            }
            return lines.size() - 1;
        }

        /** A line that ends right before a '\n' owns the caret at its end. */
        private boolean endsHard(int[] line) {
            return line[1] < text.length() && text.charAt(line[1]) == '\n';
        }

        private int lineStartOf(int index) {
            return lines.get(lineOf(index))[0];
        }

        private int lineEndOf(int index) {
            return lines.get(lineOf(index))[1];
        }

        private int caretX(int index) {
            int[] line = lines.get(lineOf(index));
            return font.getWidth(text.substring(line[0], Math.max(line[0], Math.min(index, line[1]))));
        }

        private int indexAt(int lineIndex, int px) {
            int[] line = lines.get(MathHelper.clamp(lineIndex, 0, lines.size() - 1));
            int best = line[0];
            for (int i = line[0]; i <= line[1]; i++) {
                int wdt = font.getWidth(text.substring(line[0], i));
                if (wdt <= px) {
                    best = i;
                } else {
                    // Snap to whichever side of the glyph is nearer.
                    int prev = font.getWidth(text.substring(line[0], Math.max(line[0], i - 1)));
                    if (px - prev > wdt - px) {
                        best = i;
                    }
                    break;
                }
            }
            return best;
        }

        private void moveVertical(int delta, boolean extend) {
            if (!multiline) {
                moveCursor(delta < 0 ? 0 : text.length(), extend);
                return;
            }
            int line = lineOf(cursor);
            int target = line + delta;
            if (target < 0 || target >= lines.size()) {
                moveCursor(delta < 0 ? 0 : text.length(), extend);
                return;
            }
            int px = caretX(cursor);
            moveCursor(indexAt(target, px), extend);
        }

        private int visibleLines() {
            return Math.max(1, (h - PAD * 2 + 2) / LINE);
        }

        private void keepCaretVisible() {
            ensureWrapped();
            if (multiline) {
                int line = lineOf(cursor);
                int visible = visibleLines();
                if (line < scrollLine) {
                    scrollLine = line;
                } else if (line >= scrollLine + visible) {
                    scrollLine = line - visible + 1;
                }
                scrollLine = MathHelper.clamp(scrollLine, 0, Math.max(0, lines.size() - visible));
            } else {
                int cx = caretX(cursor);
                int inner = innerWidth();
                if (cx - scrollX > inner) {
                    scrollX = cx - inner;
                } else if (cx - scrollX < 0) {
                    scrollX = cx;
                }
                scrollX = Math.max(0, Math.min(scrollX, Math.max(0, font.getWidth(text) - inner)));
            }
        }

        // -- mouse

        private int indexAtMouse(double mx, double my) {
            ensureWrapped();
            int px = (int) (mx - x - PAD) + (multiline ? 0 : scrollX);
            int lineIndex = multiline ? scrollLine + (int) Math.floor((my - y - PAD) / LINE) : 0;
            return indexAt(MathHelper.clamp(lineIndex, 0, lines.size() - 1), px);
        }

        /** Returns true when the click landed in the box (which also focuses it). */
        public boolean mouseClicked(double mx, double my, int button) {
            if (!contains(mx, my)) {
                return false;
            }
            setFocused(true);
            if (button == 0) {
                int index = indexAtMouse(mx, my);
                moveCursor(index, Screen.hasShiftDown());
                dragging = true;
            }
            return true;
        }

        public void mouseDragged(double mx, double my) {
            if (!dragging || !focused) {
                return;
            }
            int index = indexAtMouse(mx, my);
            moveCursor(index, true);
        }

        public void mouseReleased() {
            dragging = false;
        }

        public boolean mouseScrolled(double mx, double my, double amount) {
            if (!multiline || !contains(mx, my)) {
                return false;
            }
            ensureWrapped();
            scrollLine = MathHelper.clamp(scrollLine - (int) Math.signum(amount), 0,
                    Math.max(0, lines.size() - visibleLines()));
            return true;
        }

        // -- drawing

        public void render(DrawContext c, int mx, int my) {
            ensureWrapped();
            if (focused) {
                keepCaretVisible();
            }
            rect(c, x, y, w, h, INPUT);
            outline(c, x, y, w, h, focused ? ACCENT : contains(mx, my) ? BORDER_STRONG : BORDER);

            int left = x + PAD;
            int top = y + PAD;
            c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            if (text.isEmpty() && !placeholder.isEmpty()) {
                c.drawText(font, placeholder, left, top, TEXT_FAINT, false);
            }
            boolean caretOn = focused && ((System.currentTimeMillis() - blinkStart) / 500) % 2 == 0;
            int first = multiline ? scrollLine : 0;
            int visible = multiline ? visibleLines() + 1 : 1;
            for (int i = first; i < Math.min(lines.size(), first + visible); i++) {
                int[] line = lines.get(i);
                int ly = top + (i - first) * LINE;
                int lx = left - (multiline ? 0 : scrollX);
                String segment = text.substring(line[0], line[1]);
                if (hasSelection()) {
                    int s = Math.max(selStart(), line[0]);
                    int e = Math.min(selEnd(), line[1]);
                    if (s < e || (s == e && selStart() <= line[0] && selEnd() > line[1])) {
                        int sx = lx + font.getWidth(text.substring(line[0], s));
                        int ex = lx + font.getWidth(text.substring(line[0], Math.max(s, e)));
                        if (selEnd() > line[1] && i < lines.size() - 1) {
                            ex += 3; // show the wrapped-away space
                        }
                        c.fill(sx, ly - 1, Math.max(ex, sx + 1), ly + 9, SELECTION);
                    }
                }
                c.drawText(font, segment, lx, ly, TEXT, false);
                if (caretOn && lineOf(cursor) == i) {
                    int cx = lx + font.getWidth(text.substring(line[0], Math.max(line[0], Math.min(cursor, line[1]))));
                    c.fill(cx, ly - 1, cx + 1, ly + 9, CARET);
                }
            }
            c.disableScissor();
            if (multiline) {
                scrollThumb(c, x + w, y, h, lines.size() * LINE + PAD * 2, scrollLine * LINE);
            }
        }
    }
}
