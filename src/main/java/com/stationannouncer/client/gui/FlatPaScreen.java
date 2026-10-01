package com.stationannouncer.client.gui;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PA screens' shared FlatUi plumbing (no vanilla widgets): click targets
 * recorded while drawing (so they always match what is on screen, clipped to
 * scrolled panes), integer sliders, on/off switches, text-box focus and a
 * toast line. Subclasses draw in {@link #draw} and react in {@link #onHit},
 * {@link #onSlider} and {@link #onEnter}.
 */
@Environment(EnvType.CLIENT)
abstract class FlatPaScreen extends Screen {
    protected static final int HIT_SLIDER = -1;

    /** x, y, w, h, id, arg. */
    private final List<int[]> hits = new ArrayList<>();
    private final Map<Integer, int[]> sliders = new HashMap<>(); // key → trackX, trackW, min, max
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;
    private int dragKey = Integer.MIN_VALUE;
    protected TextBox focused;

    private String toast;
    private long toastUntil;
    private boolean toastBad;

    protected FlatPaScreen(Text title) {
        super(title);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------------------------------------------------------------- frame

    @Override
    public final void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        visibleBoxes.clear();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        draw(c, mx, my);
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int w = textRenderer.getWidth(toast) + 16;
            int x = (width - w) / 2;
            int y = height - 34;
            FlatUi.rect(c, x, y, w, 16, 0xF0202026);
            FlatUi.outline(c, x, y, w, 16, toastBad ? FlatUi.DANGER : FlatUi.ACCENT);
            c.drawText(textRenderer, toast, x + 8, y + 4, FlatUi.TEXT, false);
        }
    }

    protected abstract void draw(DrawContext c, int mx, int my);

    /** A click on a recorded target. */
    protected abstract void onHit(int id, int arg, double mx, double my);

    /** A slider was dragged to {@code value}. */
    protected abstract void onSlider(int key, int value);

    /** Enter with nothing else consuming it; return true when handled. */
    protected boolean onEnter() {
        return false;
    }

    /** Escape with no focused box: default closes. */
    protected boolean onEscape() {
        close();
        return true;
    }

    // ------------------------------------------------------------- targets

    protected void hit(int x, int y, int w, int h, int id, int arg) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new int[]{x, top, w, bottom - top, id, arg});
        }
    }

    /** Starts a scrolled region: draws clip and targets outside it are dropped. */
    protected void beginClip(DrawContext c, int x, int y, int w, int h) {
        c.enableScissor(x, y, x + w, y + h);
        clipTop = y;
        clipBottom = y + h;
    }

    protected void endClip(DrawContext c) {
        c.disableScissor();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
    }

    /**
     * Starts a popup: dims the screen, and everything registered so far (pane
     * targets, pane text boxes) stops taking clicks; a click outside the popup
     * reaches {@code backdropId}.
     */
    protected void beginModal(DrawContext c, int backdropId) {
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 200); // above anything a pane drew (item icons etc.)
        FlatUi.rect(c, 0, 0, width, height, 0x88000000);
        visibleBoxes.clear();
        hits.add(new int[]{0, 0, width, height, backdropId, 0});
    }

    protected void endModal(DrawContext c) {
        c.getMatrices().pop();
    }

    /** Draws a text box and registers it for clicks (unless scrolled out of view). */
    protected void box(DrawContext c, TextBox box, int x, int y, int w, int h, int mx, int my) {
        box.setBounds(x, y, w, h);
        box.render(c, mx, my);
        if (y + h > clipTop && y < clipBottom) {
            visibleBoxes.add(box);
        }
    }

    protected void focus(TextBox box) {
        if (focused != null && focused != box) {
            focused.setFocused(false);
        }
        focused = box;
        if (box != null) {
            box.setFocused(true);
        }
    }

    protected void toast(String text, boolean bad) {
        toast = text;
        toastBad = bad;
        toastUntil = System.currentTimeMillis() + 2_500;
    }

    // --------------------------------------------------------------- widgets

    /**
     * Label, track and value. {@code labelW} may be 0 for an unlabelled slider.
     * The whole row is the drag target.
     */
    protected void slider(DrawContext c, int mx, int my, int key, String label, int labelW, int value, int min, int max,
                          String valueText, int x, int y, int w) {
        int valueW = Math.max(30, textRenderer.getWidth(valueText) + 4);
        if (labelW > 0) {
            c.drawText(textRenderer, label, x, y + 2, FlatUi.TEXT_DIM, false);
        }
        int tx = x + labelW;
        int tw = Math.max(12, w - labelW - valueW - 4);
        sliders.put(key, new int[]{tx, tw, min, max});
        float v = max == min ? 0 : MathHelper.clamp((value - min) / (float) (max - min), 0f, 1f);
        boolean hot = dragKey == key || FlatUi.inside(mx, my, tx, y, tw, 12);
        FlatUi.rect(c, tx, y + 5, tw, 3, FlatUi.INPUT);
        FlatUi.rect(c, tx, y + 5, Math.round(tw * v), 3, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        int thumb = tx + Math.round((tw - 4) * v);
        FlatUi.rect(c, thumb, y + 2, 4, 9, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        c.drawText(textRenderer, valueText, tx + tw + 6, y + 2, FlatUi.TEXT, false);
        hit(tx - 2, y, tw + 4, 13, HIT_SLIDER, key);
    }

    /** A pill switch; returns its width. The caller records the hit. */
    protected int switchPill(DrawContext c, int x, int y, boolean on, boolean hovered) {
        int w = 18;
        FlatUi.rect(c, x, y + 2, w, 9, on ? FlatUi.ACCENT_DIM : FlatUi.INPUT);
        FlatUi.outline(c, x, y + 2, w, 9, hovered ? FlatUi.TEXT_DIM : FlatUi.BORDER_STRONG);
        FlatUi.rect(c, on ? x + w - 8 : x + 1, y + 3, 7, 7, on ? FlatUi.TEXT : FlatUi.TEXT_FAINT);
        return w;
    }

    /** "Label ......... [switch]" row; records the whole row as the hit. */
    protected void toggleRow(DrawContext c, int mx, int my, String label, boolean on, int x, int y, int w, int id, int arg) {
        boolean hovered = FlatUi.inside(mx, my, x, y, w, 13);
        c.drawText(textRenderer, label, x, y + 2, hovered ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
        switchPill(c, x + w - 18, y, on, hovered);
        hit(x, y, w, 13, id, arg);
    }

    protected boolean button(DrawContext c, int mx, int my, String label, int x, int y, int w, int h,
                             FlatUi.ButtonStyle style, boolean enabled, int id, int arg) {
        boolean hovered = FlatUi.button(c, textRenderer, label, x, y, w, h, mx, my, style, enabled);
        if (enabled) {
            hit(x, y, w, h, id, arg);
        }
        return hovered;
    }

    protected int textWidth(String text) {
        return textRenderer.getWidth(text);
    }

    protected String trim(String text, int width) {
        if (textRenderer.getWidth(text) <= width) {
            return text;
        }
        return textRenderer.trimToWidth(text, Math.max(0, width - textRenderer.getWidth("…"))) + "…";
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (TextBox box : visibleBoxes) {
            if (box.contains(mx, my)) {
                focus(box);
                box.mouseClicked(mx, my, button);
                return true;
            }
        }
        // Topmost (last drawn) target wins: popups draw after the panes.
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] r = hits.get(i);
            if (!FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                continue;
            }
            if (r[4] == HIT_SLIDER) {
                if (button == 0) {
                    dragKey = r[5];
                    drag(mx);
                }
                return true;
            }
            if (button == 0) {
                onHit(r[4], r[5], mx, my);
            }
            return true;
        }
        focus(null);
        onHit(0, 0, mx, my); // empty space: lets popups close
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragKey != Integer.MIN_VALUE) {
            drag(mx);
            return true;
        }
        if (focused != null) {
            focused.mouseDragged(mx, my);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragKey = Integer.MIN_VALUE;
        if (focused != null) {
            focused.mouseReleased();
        }
        return true;
    }

    private void drag(double mx) {
        int[] s = sliders.get(dragKey);
        if (s == null) {
            return;
        }
        float v = (float) MathHelper.clamp((mx - s[0]) / Math.max(1, s[1] - 4), 0, 1);
        onSlider(dragKey, s[2] + Math.round(v * (s[3] - s[2])));
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (focused != null) {
                focus(null);
                return true;
            }
            return onEscape();
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && onEnter()) {
            return true;
        }
        if (focused != null && focused.keyPressed(key, modifiers)) {
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return focused != null && focused.charTyped(chr);
    }
}
