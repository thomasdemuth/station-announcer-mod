package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.DrivingHudScreen;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Cab HUD + train crew settings (the H key), on FlatUi — no vanilla widgets (Thomas's rule).
 * Sections: HUD, cab seat, lean, crew, passengers (only once {@code AddonClientConfig} has the
 * {@code cabPassengers*} fields), and the crew/cab key bindings as read-only text.
 *
 * <p>Edits go straight into {@link AddonClientConfig} so the HUD/seat behind the (non-pausing,
 * half-dimmed) screen previews them; the values on open are kept and Cancel / Esc puts them back.
 * Save persists to disk. "Classic options…" opens the old {@link DrivingHudScreen} (classic panel
 * rows, lookahead, corner…) — its Done persists everything, so a later Cancel here re-persists the
 * restored values, and fields that screen changed are adopted as the new baseline.</p>
 *
 * <p>Sized for ~426x240 GUI px: two columns when there is room, one otherwise; the body scrolls.
 * Click targets are recorded while drawing, so they always match what is on screen.</p>
 */
@Environment(EnvType.CLIENT)
public final class CabSettingsScreen extends Screen {
    private static final int HIT_SLIDER = -1;

    // Toggles (hit id = T_*).
    private static final int T_HUD = 1;
    private static final int T_SEAT = 2;
    private static final int T_LEAN = 3;
    private static final int T_VIGIL = 4;
    private static final int T_SKIP = 5;
    private static final int T_CREW_PANEL = 6;
    private static final int T_PASSENGERS = 7;
    // Segmented rows (arg = cell index).
    private static final int SEG_STYLE = 10;
    private static final int SEG_UNITS = 11;
    // Buttons.
    private static final int B_SAVE = 20;
    private static final int B_CANCEL = 21;
    private static final int B_CLASSIC = 22;
    private static final int B_CONTROLS = 23;
    private static final int B_SEAT_DEFAULTS = 24;

    // Slider keys.
    private static final int S_SCALE = 1;
    private static final int S_RATE = 2;
    private static final int S_INSET = 3;
    private static final int S_LATERAL = 4;
    private static final int S_EYE = 5;
    private static final int S_LEAN = 6;
    private static final int S_VIGIL = 7;
    private static final int S_PASSENGERS = 8;

    // Ranges (blocks = metres in MTR). Inset ≥ CabSeat's 0.35 floor; eye ±1.5 = CabSeat's clamp;
    // lean ≥ CabCamera's 0.6 minimum.
    private static final double INSET_MIN = 0.35;
    private static final double INSET_MAX = 4.0;
    private static final double LATERAL_MAX = 1.5;
    private static final double EYE_MAX = 1.5;
    private static final double LEAN_MIN = 0.6;
    private static final double LEAN_MAX = 3.0;
    private static final double STEP = 0.05;
    private static final int PASSENGERS_MIN = 0;
    private static final int PASSENGERS_MAX = 200;

    /** Every config field this screen edits; snapshotted on open, restored on Cancel. Missing ones are skipped. */
    private static final String[] FIELDS = {
            "hudEnabled", "hudStyle", "cabMph", "cabScale", "hudUpdateHz",
            "cabSeatEnabled", "cabSeatInset", "cabSeatLateral", "cabSeatEyeOffset",
            "cabLeanEnabled", "cabLeanMax",
            "crewVigilanceEnabled", "crewVigilanceSeconds", "crewSkipStartup", "crewPanelEnabled",
            "cabPassengers", "cabPassengersMax"};

    /** Key bindings listed read-only in the Keys section (translation keys). */
    private static final String[] KEYS = {
            "key.station_announcer.crew_buzzer", "key.station_announcer.crew_horn",
            "key.station_announcer.crew_vigilance", "key.station_announcer.cab_detail",
            "key.station_announcer.cab_menu", "key.station_announcer.cab_seat",
            "key.station_announcer.cab_lean", "key.station_announcer.driving_hud_settings"};

    private static final int ROW = 15;
    private static final int PAD = 8;

    @Nullable
    private final Screen parent;
    private final Object[] original;
    /** Snapshot taken right before opening the classic screen; compared on return. */
    @Nullable
    private Object[] beforeClassic;
    /** True once another screen may have written the config file (classic Done). */
    private boolean persistedElsewhere;

    /** x, y, w, h, id, arg. */
    private final List<int[]> hits = new ArrayList<>();
    /** key → trackX, trackW, min, max (slider positions are ints). */
    private final Map<Integer, int[]> sliders = new HashMap<>();
    private int dragKey = Integer.MIN_VALUE;
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;
    private int scroll;
    private int contentHeight;
    private int viewHeight;

    public CabSettingsScreen(@Nullable Screen parent) {
        super(Text.translatable("gui.station_announcer.cab_settings.title"));
        this.parent = parent;
        this.original = capture(AddonClientConfig.get());
    }

    @Override
    protected void init() {
        // Back from the classic screen: whatever it changed was persisted by its Done (its Cancel
        // restores exactly what it saw), so adopt those values as the baseline Cancel returns to.
        if (beforeClassic != null) {
            Object[] now = capture(AddonClientConfig.get());
            for (int i = 0; i < now.length; i++) {
                if (!Objects.equals(beforeClassic[i], now[i])) {
                    original[i] = now[i];
                }
            }
            beforeClassic = null;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        c.fill(0, 0, width, height, 0x88000000);
        int w = Math.min(420, width - 8);
        int h = Math.min(300, height - 8);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.pane(c, x, y, w, h);
        c.drawText(textRenderer, title, x + PAD, y + 7, FlatUi.TEXT, false);
        String hint = tr("gui.station_announcer.cab_settings.hint");
        int titleW = textRenderer.getWidth(title);
        c.drawText(textRenderer, trim(hint, w - titleW - PAD * 3), x + PAD * 2 + titleW, y + 7, FlatUi.TEXT_FAINT, false);
        FlatUi.rect(c, x + 1, y + 20, w - 2, 1, FlatUi.BORDER);

        // Body (scrolls).
        int bodyTop = y + 22;
        int bodyBottom = y + h - 26;
        viewHeight = bodyBottom - bodyTop;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentHeight - viewHeight));
        c.enableScissor(x + 1, bodyTop, x + w - 1, bodyBottom);
        clipTop = bodyTop;
        clipBottom = bodyBottom;
        boolean twoColumns = w >= 330;
        int inner = w - PAD * 2;
        int colW = twoColumns ? (inner - 12) / 2 : inner;
        int leftX = x + PAD;
        int rightX = twoColumns ? leftX + colW + 12 : leftX;
        int top = bodyTop + 2 - scroll;
        int leftEnd = drawLeft(c, mx, my, leftX, top, colW);
        int rightEnd = drawRight(c, mx, my, rightX, twoColumns ? top : leftEnd + 4, colW);
        contentHeight = Math.max(leftEnd, rightEnd) - top + 4;
        c.disableScissor();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        FlatUi.scrollThumb(c, x + w - 1, bodyTop, viewHeight, contentHeight, scroll);

        // Footer.
        FlatUi.rect(c, x + 1, bodyBottom, w - 2, 1, FlatUi.BORDER);
        int by = y + h - 21;
        String classic = tr("gui.station_announcer.cab_settings.classic");
        int classicW = textRenderer.getWidth(classic) + 14;
        button(c, mx, my, classic, x + PAD, by, classicW, 16, FlatUi.ButtonStyle.GHOST, B_CLASSIC, 0);
        String save = tr("gui.station_announcer.cab_settings.save");
        String cancel = tr("gui.station_announcer.cab_settings.cancel");
        int saveW = Math.max(56, textRenderer.getWidth(save) + 16);
        int cancelW = Math.max(56, textRenderer.getWidth(cancel) + 16);
        button(c, mx, my, save, x + w - PAD - saveW, by, saveW, 16, FlatUi.ButtonStyle.PRIMARY, B_SAVE, 0);
        button(c, mx, my, cancel, x + w - PAD - saveW - 4 - cancelW, by, cancelW, 16, FlatUi.ButtonStyle.FLAT, B_CANCEL, 0);
    }

    /** HUD, cab seat, lean. Returns the y below the last row. */
    private int drawLeft(DrawContext c, int mx, int my, int x, int y, int w) {
        AddonClientConfig config = AddonClientConfig.get();
        y = heading(c, "gui.station_announcer.cab_settings.section_hud", x, y, w);
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.hud_enabled"), config.hudEnabled, true, x, y, w, T_HUD);
        y += ROW;
        boolean classic = config.isClassicHud();
        segRow(c, mx, my, tr("gui.station_announcer.cab_settings.style"),
                new String[]{tr("gui.station_announcer.cab_settings.style_cab"), tr("gui.station_announcer.cab_settings.style_classic")},
                classic ? 1 : 0, x, y, w, SEG_STYLE);
        y += ROW;
        segRow(c, mx, my, tr("gui.station_announcer.cab_settings.units"), new String[]{"km/h", "mph"},
                config.cabMph ? 1 : 0, x, y, w, SEG_UNITS);
        y += ROW;
        int scale = MathHelper.clamp(config.cabScale, 1, 4);
        slider(c, mx, my, S_SCALE, tr("gui.station_announcer.cab_settings.scale"), scale, 1, 4, scale + "x", !classic, x, y, w);
        y += ROW;
        int hz = MathHelper.clamp(config.hudUpdateHz, 1, 20);
        slider(c, mx, my, S_RATE, tr("gui.station_announcer.cab_settings.update_rate"), hz, 1, 20, hz + " Hz", true, x, y, w);
        y += ROW;

        y = heading(c, "gui.station_announcer.cab_settings.section_seat", x, y + 4, w);
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.seat_enabled"), config.cabSeatEnabled, true, x, y, w, T_SEAT);
        y += ROW;
        boolean seat = config.cabSeatEnabled;
        double inset = MathHelper.clamp(config.cabSeatInset, INSET_MIN, INSET_MAX);
        slider(c, mx, my, S_INSET, tr("gui.station_announcer.cab_settings.seat_inset"), steps(inset - INSET_MIN), 0,
                steps(INSET_MAX - INSET_MIN), metres(inset, false), seat, x, y, w);
        y += ROW;
        double lateral = MathHelper.clamp(config.cabSeatLateral, -LATERAL_MAX, LATERAL_MAX);
        slider(c, mx, my, S_LATERAL, tr("gui.station_announcer.cab_settings.seat_lateral"), steps(lateral + LATERAL_MAX), 0,
                steps(LATERAL_MAX * 2), metres(lateral, true), seat, x, y, w);
        y += ROW;
        double eye = MathHelper.clamp(config.cabSeatEyeOffset, -EYE_MAX, EYE_MAX);
        slider(c, mx, my, S_EYE, tr("gui.station_announcer.cab_settings.seat_eye"), steps(eye + EYE_MAX), 0,
                steps(EYE_MAX * 2), metres(eye, true), seat, x, y, w);
        y += ROW;
        String defaults = tr("gui.station_announcer.cab_settings.seat_defaults");
        int defaultsW = textRenderer.getWidth(defaults) + 12;
        button(c, mx, my, defaults, x, y, defaultsW, 13, FlatUi.ButtonStyle.GHOST, B_SEAT_DEFAULTS, 0);
        int overrides = config.cabSeatTrains == null ? 0 : config.cabSeatTrains.size();
        if (overrides > 0) {
            String note = Text.translatable("gui.station_announcer.cab_settings.seat_overrides", overrides).getString();
            c.drawText(textRenderer, trim(note, w - defaultsW - 6), x + defaultsW + 6, y + 3, FlatUi.TEXT_FAINT, false);
        }
        y += ROW;

        y = heading(c, "gui.station_announcer.cab_settings.section_lean", x, y + 4, w);
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.lean_enabled"), config.cabLeanEnabled, true, x, y, w, T_LEAN);
        y += ROW;
        double lean = MathHelper.clamp(config.cabLeanMax, LEAN_MIN, LEAN_MAX);
        slider(c, mx, my, S_LEAN, tr("gui.station_announcer.cab_settings.lean_max"), steps(lean - LEAN_MIN), 0,
                steps(LEAN_MAX - LEAN_MIN), metres(lean, false), config.cabLeanEnabled, x, y, w);
        y += ROW;
        return y;
    }

    /** Crew, passengers, keys. Returns the y below the last row. */
    private int drawRight(DrawContext c, int mx, int my, int x, int y, int w) {
        AddonClientConfig config = AddonClientConfig.get();
        y = heading(c, "gui.station_announcer.cab_settings.section_crew", x, y, w);
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.vigilance"), config.crewVigilanceEnabled, true, x, y, w, T_VIGIL);
        y += ROW;
        int vigil = MathHelper.clamp(config.crewVigilanceSeconds, CrewProtocol.MIN_VIGIL_SECONDS, CrewProtocol.MAX_VIGIL_SECONDS);
        slider(c, mx, my, S_VIGIL, tr("gui.station_announcer.cab_settings.vigilance_every"), vigil,
                CrewProtocol.MIN_VIGIL_SECONDS, CrewProtocol.MAX_VIGIL_SECONDS, seconds(vigil), config.crewVigilanceEnabled, x, y, w);
        y += ROW;
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.skip_startup"), config.crewSkipStartup, true, x, y, w, T_SKIP);
        y += ROW;
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.crew_panel"), config.crewPanelEnabled,
                config.isClassicHud(), x, y, w, T_CREW_PANEL);
        y += ROW;

        y = heading(c, "gui.station_announcer.cab_settings.section_passengers", x, y + 4, w);
        boolean passengersOn = config.cabPassengers;
        toggleRow(c, mx, my, tr("gui.station_announcer.cab_settings.passengers"), passengersOn, true, x, y, w, T_PASSENGERS);
        y += ROW;
        int passengersMax = MathHelper.clamp(config.cabPassengersMax, PASSENGERS_MIN, PASSENGERS_MAX);
        slider(c, mx, my, S_PASSENGERS, tr("gui.station_announcer.cab_settings.passengers_max"), passengersMax,
                PASSENGERS_MIN, PASSENGERS_MAX, Integer.toString(passengersMax), passengersOn, x, y, w);
        y += ROW;

        y = heading(c, "gui.station_announcer.cab_settings.section_keys", x, y + 4, w);
        MinecraftClient client = MinecraftClient.getInstance();
        for (String key : KEYS) {
            KeyBinding binding = binding(client, key);
            if (binding == null) {
                continue;
            }
            boolean unbound = binding.isUnbound();
            String keyText = unbound ? tr("gui.station_announcer.cab_settings.key_unbound")
                    : binding.getBoundKeyLocalizedText().getString();
            int kw = Math.min(w / 2, textRenderer.getWidth(keyText) + 8);
            keyText = trim(keyText, kw - 8);
            FlatUi.rect(c, x + w - kw, y, kw, 12, unbound ? FlatUi.PANE : FlatUi.PANE_RAISED);
            FlatUi.outline(c, x + w - kw, y, kw, 12, FlatUi.BORDER_STRONG);
            c.drawText(textRenderer, keyText, x + w - kw + (kw - textRenderer.getWidth(keyText)) / 2, y + 2,
                    unbound ? FlatUi.TEXT_FAINT : FlatUi.TEXT, false);
            String label = Text.translatable(binding.getTranslationKey()).getString();
            c.drawText(textRenderer, trim(label, w - kw - 6), x, y + 2, FlatUi.TEXT_DIM, false);
            y += 14;
        }
        String change = tr("gui.station_announcer.cab_settings.change_keys");
        button(c, mx, my, change, x, y + 1, textRenderer.getWidth(change) + 12, 13, FlatUi.ButtonStyle.GHOST, B_CONTROLS, 0);
        y += ROW + 1;
        return y;
    }

    // ----------------------------------------------------------------- widgets

    private int heading(DrawContext c, String key, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, tr(key), x, y + 2);
        FlatUi.rect(c, x, y + 11, w, 1, FlatUi.BORDER);
        return y + 15;
    }

    private void hit(int x, int y, int w, int h, int id, int arg) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new int[]{x, top, w, bottom - top, id, arg});
        }
    }

    private void button(DrawContext c, int mx, int my, String label, int x, int y, int w, int h,
                        FlatUi.ButtonStyle style, int id, int arg) {
        boolean visible = y + h > clipTop && y < clipBottom;
        FlatUi.button(c, textRenderer, label, x, y, w, h, visible ? mx : -1, visible ? my : -1, style, true);
        hit(x, y, w, h, id, arg);
    }

    /** "Label ........ [switch]"; the whole row is the target. {@code relevant=false} dims the label. */
    private void toggleRow(DrawContext c, int mx, int my, String label, boolean on, boolean relevant,
                           int x, int y, int w, int id) {
        boolean hovered = hoverable(mx, my, x, y, w, 13);
        int ink = hovered ? FlatUi.TEXT : relevant ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT;
        c.drawText(textRenderer, trim(label, w - 24), x, y + 2, ink, false);
        int sx = x + w - 18;
        FlatUi.rect(c, sx, y + 2, 18, 9, on ? FlatUi.ACCENT_DIM : FlatUi.INPUT);
        FlatUi.outline(c, sx, y + 2, 18, 9, hovered ? FlatUi.TEXT_DIM : FlatUi.BORDER_STRONG);
        FlatUi.rect(c, on ? sx + 10 : sx + 1, y + 3, 7, 7, on ? FlatUi.TEXT : FlatUi.TEXT_FAINT);
        hit(x, y, w, 13, id, 0);
    }

    /** Label on the left, a segmented control filling the rest; arg = the clicked cell. */
    private void segRow(DrawContext c, int mx, int my, String label, String[] cells, int chosen,
                        int x, int y, int w, int id) {
        int labelW = labelWidth(w);
        c.drawText(textRenderer, trim(label, labelW - 4), x, y + 2, FlatUi.TEXT_DIM, false);
        int sx = x + labelW;
        int sw = w - labelW;
        boolean visible = y + 13 > clipTop && y < clipBottom;
        FlatUi.segmented(c, textRenderer, cells, chosen, sx, y, sw, 13, visible ? mx : -1, visible ? my : -1);
        int n = cells.length;
        for (int i = 0; i < n; i++) {
            int cx = sx + sw * i / n;
            hit(cx, y, sx + sw * (i + 1) / n - cx, 13, id, i);
        }
    }

    /** Label, track, value; integer positions. The track is the drag target. */
    private void slider(DrawContext c, int mx, int my, int key, String label, int value, int min, int max,
                        String valueText, boolean relevant, int x, int y, int w) {
        int labelW = labelWidth(w);
        int valueW = Math.max(34, textRenderer.getWidth(valueText) + 6);
        c.drawText(textRenderer, trim(label, labelW - 4), x, y + 2, relevant ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT, false);
        int tx = x + labelW;
        int tw = Math.max(16, w - labelW - valueW);
        sliders.put(key, new int[]{tx, tw, min, max});
        float v = max == min ? 0 : MathHelper.clamp((value - min) / (float) (max - min), 0f, 1f);
        boolean hot = dragKey == key || hoverable(mx, my, tx - 2, y, tw + 4, 13);
        FlatUi.rect(c, tx, y + 5, tw, 3, FlatUi.INPUT);
        FlatUi.rect(c, tx, y + 5, Math.round(tw * v), 3, hot ? FlatUi.ACCENT : relevant ? FlatUi.ACCENT_DIM : FlatUi.BORDER_STRONG);
        int thumb = tx + Math.round((tw - 4) * v);
        FlatUi.rect(c, thumb, y + 2, 4, 9, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        c.drawText(textRenderer, valueText, x + w - textRenderer.getWidth(valueText), y + 2,
                relevant ? FlatUi.TEXT : FlatUi.TEXT_FAINT, false);
        hit(tx - 2, y, tw + 4, 13, HIT_SLIDER, key);
    }

    private int labelWidth(int w) {
        return Math.min(96, Math.max(56, w * 2 / 5));
    }

    private boolean hoverable(int mx, int my, int x, int y, int w, int h) {
        return my >= clipTop && my < clipBottom && FlatUi.inside(mx, my, x, y, w, h);
    }

    // ------------------------------------------------------------------- input

    private void onHit(int id, int arg) {
        AddonClientConfig config = AddonClientConfig.get();
        switch (id) {
            case T_HUD -> config.hudEnabled = !config.hudEnabled;
            case T_SEAT -> config.cabSeatEnabled = !config.cabSeatEnabled;
            case T_LEAN -> config.cabLeanEnabled = !config.cabLeanEnabled;
            case T_VIGIL -> config.crewVigilanceEnabled = !config.crewVigilanceEnabled;
            case T_SKIP -> config.crewSkipStartup = !config.crewSkipStartup;
            case T_CREW_PANEL -> config.crewPanelEnabled = !config.crewPanelEnabled;
            case T_PASSENGERS -> config.cabPassengers = !config.cabPassengers;
            case SEG_STYLE -> config.hudStyle = arg == 1 ? "classic" : "cab";
            case SEG_UNITS -> config.cabMph = arg == 1;
            case B_SEAT_DEFAULTS -> {
                AddonClientConfig defaults = new AddonClientConfig();
                config.cabSeatInset = defaults.cabSeatInset;
                config.cabSeatLateral = defaults.cabSeatLateral;
                config.cabSeatEyeOffset = defaults.cabSeatEyeOffset;
            }
            case B_SAVE -> save();
            case B_CANCEL -> cancel();
            case B_CLASSIC -> {
                beforeClassic = capture(config);
                persistedElsewhere = true;
                if (client != null) {
                    client.setScreen(new DrivingHudScreen(this));
                }
            }
            case B_CONTROLS -> {
                if (client != null) {
                    client.setScreen(new KeybindsScreen(this, client.options));
                }
            }
            default -> {
            }
        }
    }

    private void onSlider(int key, int pos) {
        AddonClientConfig config = AddonClientConfig.get();
        switch (key) {
            case S_SCALE -> config.cabScale = pos;
            case S_RATE -> config.hudUpdateHz = pos;
            case S_INSET -> config.cabSeatInset = round(INSET_MIN + pos * STEP);
            case S_LATERAL -> config.cabSeatLateral = round(-LATERAL_MAX + pos * STEP);
            case S_EYE -> config.cabSeatEyeOffset = round(-EYE_MAX + pos * STEP);
            case S_LEAN -> config.cabLeanMax = round(LEAN_MIN + pos * STEP);
            case S_VIGIL -> config.crewVigilanceSeconds = MathHelper.clamp(Math.round(pos / 5f) * 5,
                    CrewProtocol.MIN_VIGIL_SECONDS, CrewProtocol.MAX_VIGIL_SECONDS);
            case S_PASSENGERS -> config.cabPassengersMax = MathHelper.clamp(pos, PASSENGERS_MIN, PASSENGERS_MAX);
            default -> {
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] r = hits.get(i);
            if (!FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                continue;
            }
            if (r[4] == HIT_SLIDER) {
                dragKey = r[5];
                drag(mx);
            } else {
                onHit(r[4], r[5]);
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragKey != Integer.MIN_VALUE) {
            drag(mx);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragKey = Integer.MIN_VALUE;
        return true;
    }

    private void drag(double mx) {
        int[] s = sliders.get(dragKey);
        if (s == null) {
            return;
        }
        double v = MathHelper.clamp((mx - s[0]) / Math.max(1, s[1] - 4), 0, 1);
        onSlider(dragKey, s[2] + (int) Math.round(v * (s[3] - s[2])));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = MathHelper.clamp(scroll - (int) Math.signum(vertical) * ROW, 0, Math.max(0, contentHeight - viewHeight));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || (keyCode == GLFW.GLFW_KEY_S && hasControlDown())) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void save() {
        AddonClientConfig.persist();
        close();
    }

    private void cancel() {
        apply(AddonClientConfig.get(), original);
        if (persistedElsewhere) {
            AddonClientConfig.persist();
        }
        close();
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static int steps(double value) {
        return (int) Math.round(value / STEP);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String metres(double value, boolean signed) {
        String number = String.format(Locale.ROOT, signed ? "%+.2f" : "%.2f", value);
        return number + " m";
    }

    private static String seconds(int s) {
        return s < 60 ? s + " s" : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Nullable
    private static KeyBinding binding(MinecraftClient client, String translationKey) {
        for (KeyBinding binding : client.options.allKeys) {
            if (binding.getTranslationKey().equals(translationKey)) {
                return binding;
            }
        }
        return null;
    }

    private String trim(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (textRenderer.getWidth(text) <= width) {
            return text;
        }
        return textRenderer.trimToWidth(text, Math.max(0, width - textRenderer.getWidth("…"))) + "…";
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    // Config snapshot by field name (reflection) for Cancel: one list (FIELDS) instead of a
    // hand-written copy per field.

    @Nullable
    private static Field field(String name) {
        try {
            return AddonClientConfig.class.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Object[] capture(AddonClientConfig config) {
        Object[] values = new Object[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            Field f = field(FIELDS[i]);
            if (f != null) {
                try {
                    values[i] = f.get(config);
                } catch (IllegalAccessException e) {
                    values[i] = null;
                }
            }
        }
        return values;
    }

    private static void apply(AddonClientConfig config, Object[] values) {
        for (int i = 0; i < FIELDS.length; i++) {
            Field f = field(FIELDS[i]);
            if (f != null && (values[i] != null || !f.getType().isPrimitive())) {
                set(f, config, values[i]);
            }
        }
    }

    private static void set(Field f, AddonClientConfig config, Object value) {
        try {
            f.set(config, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            StationAnnouncer.LOGGER.warn("Cab settings: could not set {}", f.getName(), e);
        }
    }
}
