package com.stationannouncer.client.gui;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.pa.PaText;
import com.stationannouncer.pa.TrainAnnouncements;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The list of train-timed announcements ({@link TrainAnnouncements.Row}) on FlatUi:
 * a card per row (switch, "30 s before it arrives · 2×", ▶, ×), the open row's editor
 * (event, timing, repeats, text with token buttons, sample line), + Add and a preset
 * set. Shared by a PA source's Trains tab and a route's on-board editor
 * ({@link RouteTrainScreen}); the host forwards clicks and slider drags whose ids are
 * at or above {@link #BASE}.
 */
@Environment(EnvType.CLIENT)
public final class TrainRowsEditor {
    /** Hit ids and slider keys from here up belong to the editor. */
    public static final int BASE = 100;
    private static final int H_ROW = BASE;
    private static final int H_SWITCH = BASE + 1;
    private static final int H_PLAY = BASE + 2;
    private static final int H_DELETE = BASE + 3;
    private static final int H_EVENT = BASE + 4;
    private static final int H_TOKEN = BASE + 5;
    private static final int H_ADD = BASE + 6;
    private static final int H_PRESET = BASE + 7;
    private static final int H_SWALLOW = BASE + 8;
    private static final int S_SECONDS = BASE;
    private static final int S_REPEATS = BASE + 1;
    private static final int S_GAP = BASE + 2;

    /** Token buttons: label, what it inserts. */
    private static final String[][] TOKENS = {
            {"{station}", PaText.STATION}, {"{dest}", PaText.DEST}, {"{route}", PaText.ROUTE},
            {"{next}", PaText.NEXT}, {"{nextstop}", PaText.NEXT_STOP}, {"{access}", PaText.ACCESS},
            {"{platform}", PaText.PLATFORM}, {"{transfer}", PaText.TRANSFER},
            {"{nexttransfer}", PaText.NEXT_TRANSFER}};

    private final FlatPaScreen host;
    /** true = a route's on-board set (riders hear it at every stop); false = a station PA source. */
    private final boolean onboard;
    private final Function<String, String> sample;
    private final Consumer<String> play;
    public final List<TrainAnnouncements.Row> rows = new ArrayList<>();
    public final TextBox editor;
    private int selected = -1;
    public boolean dirty;

    /**
     * @param sample turns a row's raw text into the sample the editor shows (tokens filled)
     * @param play   hears a row's raw text on this client
     */
    public TrainRowsEditor(FlatPaScreen host, boolean onboard, List<TrainAnnouncements.Row> initial,
                    Function<String, String> sample, Consumer<String> play) {
        this.host = host;
        this.onboard = onboard;
        this.sample = sample;
        this.play = play;
        rows.addAll(initial);
        editor = new TextBox(font(), TrainAnnouncements.MAX_TEXT, true);
        editor.placeholder = onboard ? "What riders hear at each stop…" : "What to say about the train…";
        editor.onChange(value -> {
            if (selected >= 0 && selected < rows.size()) {
                rows.set(selected, rows.get(selected).withText(value));
                dirty = true;
            }
        });
    }

    public void load(List<TrainAnnouncements.Row> value) {
        rows.clear();
        rows.addAll(value);
        selected = -1;
        dirty = false;
    }

    private static TextRenderer font() {
        return MinecraftClient.getInstance().textRenderer;
    }

    // ================================================================ drawing

    public int draw(DrawContext c, int mx, int my, int x, int y, int w) {
        if (rows.isEmpty()) {
            String hint = onboard
                    ? "Say something to riders at every stop: before arriving, when the doors open or close, or after leaving."
                    : "Speak before a train arrives, when its doors open or close, or after it leaves.";
            for (OrderedText line : font().wrapLines(Text.literal(hint), w)) {
                c.drawText(font(), line, x, y, FlatUi.TEXT_DIM, false);
                y += 10;
            }
            y += 2;
        }
        for (int i = 0; i < rows.size(); i++) {
            y = i == selected ? drawEditor(c, mx, my, i, x, y, w) : drawCard(c, mx, my, i, x, y, w);
            y += 3;
        }
        if (rows.size() < TrainAnnouncements.MAX_ROWS) {
            int half = (w - 4) / 2;
            host.button(c, mx, my, "+ Add", x, y, half, 14, FlatUi.ButtonStyle.FLAT, true, H_ADD, 0);
            host.button(c, mx, my, onboard ? "+ On-board set" : "+ Subway set", x + half + 4, y, w - half - 4, 14,
                    FlatUi.ButtonStyle.FLAT, true, H_PRESET, 0);
            y += 18;
        }
        String foot = onboard
                ? "{station} is the stop being served. {transfer} / {nexttransfer} name the other lines at this / the next stop. ▶ uses a sample stop. {access} stays silent until the station has a layout scan."
                : "{transfer} / {nexttransfer} name the other lines at this / the next stop. ▶ uses a sample train. {access} stays silent until the station has a layout scan.";
        for (OrderedText line : font().wrapLines(Text.literal(foot), w)) {
            c.drawText(font(), line, x, y, FlatUi.TEXT_FAINT, false);
            y += 10;
        }
        return y + 4;
    }

    private int drawCard(DrawContext c, int mx, int my, int i, int x, int y, int w) {
        TrainAnnouncements.Row row = rows.get(i);
        int h = 26;
        boolean hovered = FlatUi.inside(mx, my, x, y, w, h);
        FlatUi.rect(c, x, y, w, h, hovered ? FlatUi.PANE_RAISED : 0xFF1A1A1F);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER);
        host.hit(x, y, w, h, H_ROW, i);
        drawHeader(c, mx, my, i, row, x, y, w);
        String snippet = row.text().isBlank() ? "(empty)" : row.text();
        c.drawText(font(), host.trim(snippet, w - 10), x + 5, y + 15,
                row.enabled() && !row.text().isBlank() ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT, false);
        return y + h;
    }

    /** Switch, summary, ▶ and × — shared by the card and the open editor. */
    private void drawHeader(DrawContext c, int mx, int my, int i, TrainAnnouncements.Row row, int x, int y, int w) {
        int sx = x + 5;
        boolean swHover = FlatUi.inside(mx, my, sx - 2, y + 3, 12, 12);
        FlatUi.rect(c, sx, y + 5, 8, 8, row.enabled() ? FlatUi.OK : FlatUi.INPUT);
        FlatUi.outline(c, sx, y + 5, 8, 8, swHover ? FlatUi.TEXT : row.enabled() ? FlatUi.OK : FlatUi.BORDER_STRONG);
        host.hit(sx - 2, y + 3, 12, 12, H_SWITCH, i);
        c.drawText(font(), host.trim(row.summary(), w - 50), x + 18, y + 5,
                row.enabled() ? FlatUi.TEXT : FlatUi.TEXT_FAINT, false);
        int ix = x + w - 16;
        FlatUi.iconButton(c, font(), "×", ix, y + 3, 12, mx, my, FlatUi.DANGER);
        host.hit(ix, y + 3, 12, 12, H_DELETE, i);
        ix -= 14;
        FlatUi.iconButton(c, font(), "▶", ix, y + 3, 12, mx, my, FlatUi.OK);
        host.hit(ix, y + 3, 12, 12, H_PLAY, i);
    }

    private int drawEditor(DrawContext c, int mx, int my, int i, int x, int y, int w) {
        TrainAnnouncements.Row row = rows.get(i);
        int inX = x + 5;
        int inW = w - 10;
        editor.setBounds(inX, 0, inW, 100);
        int edH = MathHelper.clamp(editor.lineCount(), 2, 5) * 10 + 8;
        int tokenRows = tokenRows(inW);
        String filled = sample.apply(row.text());
        boolean gap = row.repeats() > 1;
        int h = 18 + 15 + 15 + 15 + 10 + 15 + (gap ? 15 : 0) + 2 + edH + 4 + tokenRows * 14
                + (filled.isBlank() ? 0 : 10) + 4;

        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.ACCENT_DIM);
        host.hit(x, y, w, h, H_SWALLOW, 0);
        drawHeader(c, mx, my, i, row, x, y, w);
        int yy = y + 18;
        TrainAnnouncements.Event[] events = TrainAnnouncements.Event.values();
        for (int r = 0; r < 2; r++) {
            String[] labels = {events[r * 2].shortLabel, events[r * 2 + 1].shortLabel};
            int chosen = row.event().ordinal() / 2 == r ? row.event().ordinal() % 2 : -1;
            FlatUi.segmented(c, font(), labels, chosen, inX, yy, inW, 13, mx, my);
            host.hit(inX, yy, inW / 2, 13, H_EVENT, r * 2);
            host.hit(inX + inW / 2, yy, inW - inW / 2, 13, H_EVENT, r * 2 + 1);
            yy += 15;
        }
        host.slider(c, mx, my, S_SECONDS, row.event().before ? "Before" : "After", 40, row.seconds(), 0,
                TrainAnnouncements.MAX_SECONDS, row.seconds() == 0 ? "at once" : row.seconds() + " s", inX, yy, inW);
        yy += 15;
        c.drawText(font(), host.trim("Plays " + row.timingLabel(), inW), inX, yy, FlatUi.TEXT_FAINT, false);
        yy += 10;
        host.slider(c, mx, my, S_REPEATS, "Repeat", 40, row.repeats(), 1, TrainAnnouncements.MAX_REPEATS,
                row.repeats() == 1 ? "once" : row.repeats() + "×", inX, yy, inW);
        yy += 15;
        if (gap) {
            host.slider(c, mx, my, S_GAP, "Every", 40, row.gapSeconds(), TrainAnnouncements.MIN_GAP,
                    TrainAnnouncements.MAX_GAP, row.gapSeconds() + " s", inX, yy, inW);
            yy += 15;
        }
        yy += 2;
        host.box(c, editor, inX, yy, inW, edH, mx, my);
        yy += edH + 4;
        int tx = inX;
        for (int t = 0; t < TOKENS.length; t++) {
            String label = "+ " + TOKENS[t][0];
            int bw = host.textWidth(label) + 8;
            if (tx + bw > inX + inW && tx > inX) {
                tx = inX;
                yy += 14;
            }
            FlatUi.button(c, font(), label, tx, yy, bw, 12, mx, my, FlatUi.ButtonStyle.FLAT);
            host.hit(tx, yy, bw, 12, H_TOKEN, t);
            tx += bw + 3;
        }
        yy += 14;
        if (!filled.isBlank()) {
            c.drawText(font(), host.trim("Sample: " + PaText.shown(filled), inW), inX, yy + 1, 0xFF8FB4E8, false);
        }
        return y + h;
    }

    /** How many rows the token buttons wrap onto at this width (must match drawEditor). */
    private int tokenRows(int width) {
        int used = 1;
        int tx = 0;
        for (String[] token : TOKENS) {
            int bw = host.textWidth("+ " + token[0]) + 8;
            if (tx + bw > width && tx > 0) {
                used++;
                tx = 0;
            }
            tx += bw + 3;
        }
        return used;
    }

    // ================================================================== input

    /** A click the host recorded with an id ≥ {@link #BASE}; true when handled. */
    public boolean onHit(int id, int arg) {
        switch (id) {
            case H_ROW -> select(arg == selected ? -1 : arg);
            case H_SWITCH -> {
                rows.set(arg, rows.get(arg).withEnabled(!rows.get(arg).enabled()));
                dirty = true;
            }
            case H_PLAY -> {
                TrainAnnouncements.Row row = rows.get(arg);
                if (row.text().isBlank()) {
                    host.toast("Nothing to say yet", true);
                } else {
                    play.accept(row.text());
                }
            }
            case H_DELETE -> {
                rows.remove(arg);
                dirty = true;
                select(-1);
            }
            case H_EVENT -> {
                if (selected >= 0) {
                    rows.set(selected, rows.get(selected).withEvent(TrainAnnouncements.Event.byIndex(arg)));
                    dirty = true;
                }
            }
            case H_TOKEN -> {
                if (selected >= 0) {
                    host.focus(editor);
                    editor.insertToken(TOKENS[arg][1]);
                }
            }
            case H_ADD -> {
                add(new TrainAnnouncements.Row(onboard ? TrainAnnouncements.Event.BEFORE_ARRIVAL
                        : TrainAnnouncements.Event.DOORS_OPEN, onboard ? 15 : 1, 1, 20, true, ""));
                select(rows.size() - 1);
            }
            case H_PRESET -> addPreset();
            case H_SWALLOW -> {
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A slider with a key ≥ {@link #BASE}; true when handled. */
    public boolean onSlider(int key, int value) {
        if (key < S_SECONDS || key > S_GAP) {
            return false;
        }
        if (selected >= 0 && selected < rows.size()) {
            TrainAnnouncements.Row row = rows.get(selected);
            rows.set(selected, switch (key) {
                case S_SECONDS -> row.withSeconds(value <= 30 ? value : Math.round(value / 5f) * 5);
                case S_REPEATS -> row.withRepeats(value);
                default -> row.withGap(Math.round(value / 5f) * 5);
            });
            dirty = true;
        }
        return true;
    }

    public boolean owns(TextBox box) {
        return box == editor;
    }

    public void select(int index) {
        selected = index >= 0 && index < rows.size() ? index : -1;
        if (selected >= 0) {
            editor.load(rows.get(selected).text());
            host.focus(editor);
        } else if (host.focused == editor) {
            host.focus(null);
        }
    }

    private void add(TrainAnnouncements.Row row) {
        if (rows.size() >= TrainAnnouncements.MAX_ROWS) {
            host.toast("At most " + TrainAnnouncements.MAX_ROWS + " train announcements", true);
            return;
        }
        rows.add(row);
        dirty = true;
    }

    private void addPreset() {
        int before = rows.size();
        if (onboard) {
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.BEFORE_ARRIVAL, 15, 1, 20, true,
                    "The next stop is {station}. {access}"));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.DOORS_OPEN, 1, 1, 20, true,
                    "This is {station}. {transfer} {nextstop}"));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.DOORS_CLOSING, 4, 1, 20, true,
                    "Stand clear of the closing doors, please."));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.AFTER_DEPARTURE, 5, 1, 20, true,
                    "This is a {dest}-bound {route} train. The next stop is {next}. {nexttransfer}"));
        } else {
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.BEFORE_ARRIVAL, 30, 1, 20, true,
                    "There is a {dest}-bound {route} train approaching the station. Please stand away from the platform edge."));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.DOORS_OPEN, 1, 1, 20, true,
                    "This is a {dest}-bound {route} train. {nextstop} {access}"));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.DOORS_CLOSING, 4, 1, 20, true,
                    "Stand clear of the closing doors, please."));
            add(new TrainAnnouncements.Row(TrainAnnouncements.Event.AFTER_DEPARTURE, 20, 1, 20, false,
                    "Please stand behind the yellow line while waiting for the next train."));
        }
        select(-1);
        host.toast("Added " + (rows.size() - before) + " announcements — Save to keep", false);
    }
}
