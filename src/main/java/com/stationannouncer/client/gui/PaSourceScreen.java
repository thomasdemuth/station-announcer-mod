package com.stationannouncer.client.gui;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.client.StationAnnouncerClient;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.client.render.LinkLineRenderer;
import com.stationannouncer.net.AnnouncerNetworking;
import com.stationannouncer.pa.PaText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The PA Control Box and Station Announcer editor, on FlatUi.
 *
 * <ul>
 *   <li><b>Messages</b> (left): one card per announcement — switch it off
 *       without deleting it, reorder, ▶ hear it on this client only, insert
 *       {@code {station}} / {@code {time}}, or "Say as…" a word
 *       ({@code {Bklyn|Brooklyn}}). The announcer has exactly one card.</li>
 *   <li><b>Settings</b> (right): order, chat, chime (with preview), delay,
 *       automatic playback (box) or volume and radius (announcer), tag.</li>
 *   <li><b>Network</b> (box only): every linked speaker and display with its
 *       live health from the server, per-speaker volume/radius, unlink one,
 *       Apply to all, Unlink all — all held until Save.</li>
 *   <li>Top bar: <b>Templates</b> (add or replace from saved / built-in
 *       templates, save your own) and <b>Fire</b> (broadcast the selected
 *       card, unsaved or not, or the next pool message, to players in range).</li>
 * </ul>
 *
 * <p>The pool is still stored as one "||"-joined string (switched-off entries
 * carry PaText's {@code {off}} prefix), so older boxes load unchanged.</p>
 */
@Environment(EnvType.CLIENT)
public class PaSourceScreen extends FlatPaScreen {
    private static final int MAX_MESSAGES = 64;
    private static final int MAX_AUTO = ControlBoxBlockEntity.MAX_AUTO_SECONDS;

    // hit ids
    private static final int H_BACKDROP = 1;
    private static final int H_CARD = 2;
    private static final int H_CARD_SWITCH = 3;
    private static final int H_CARD_PLAY = 4;
    private static final int H_CARD_UP = 5;
    private static final int H_CARD_DOWN = 6;
    private static final int H_CARD_DELETE = 7;
    private static final int H_ADD = 8;
    private static final int H_TOKEN = 9;
    private static final int H_TAB = 10;
    private static final int H_ORDER = 11;
    private static final int H_TOGGLE = 12;
    private static final int H_CHIME_OPEN = 13;
    private static final int H_CHIME_PICK = 14;
    private static final int H_TEMPLATES = 15;
    private static final int H_FIRE = 16;
    private static final int H_SAVE = 17;
    private static final int H_CANCEL = 18;
    private static final int H_ROW = 19;
    private static final int H_ROW_UNLINK = 20;
    private static final int H_APPLY_ALL = 21;
    private static final int H_UNLINK_ALL = 22;
    private static final int H_TPL_ADD = 23;
    private static final int H_TPL_REPLACE = 24;
    private static final int H_TPL_DELETE = 25;
    private static final int H_TPL_SAVE = 26;
    private static final int H_TPL_SCOPE = 27;
    private static final int H_TPL_RESTORE = 28;
    private static final int H_SWALLOW = 29;

    // slider keys
    private static final int S_DELAY = 1;
    private static final int S_AUTO_MIN = 2;
    private static final int S_AUTO_MAX = 3;
    private static final int S_VOLUME = 4;
    private static final int S_RADIUS = 5;
    private static final int S_ALL_VOLUME = 6;
    private static final int S_ALL_RADIUS = 7;
    private static final int S_ROW_VOLUME = 8;
    private static final int S_ROW_RADIUS = 9;

    private static final int TOGGLE_CHAT = 0;
    private static final int TOGGLE_CHIME = 1;
    private static final int TOGGLE_AUTO = 2;

    private enum Popup { NONE, TEMPLATES, CHIME }

    /** One card. */
    private static final class Msg {
        String text;
        boolean on;

        Msg(String text, boolean on) {
            this.text = text;
            this.on = on;
        }
    }

    /** One Network-tab row. */
    private static final class Row {
        final BlockPos pos;
        final boolean display;
        byte state;
        int volume;
        int radius;
        int origVolume;
        int origRadius;
        String name;
        boolean unlink;

        Row(BlockPos pos, boolean display, byte state, int volume, int radius, String name) {
            this.pos = pos;
            this.display = display;
            this.state = state;
            this.volume = this.origVolume = volume;
            this.radius = this.origRadius = radius;
            this.name = name;
        }

        boolean editable() {
            return !display && state == ControlBoxBlockEntity.MEMBER_OK && !unlink;
        }
    }

    private final AbstractPaBlockEntity source;
    @Nullable
    private final ControlBoxBlockEntity box;

    private final List<Msg> messages = new ArrayList<>();
    private int selected = -1;
    private final TextBox editor;
    private final TextBox tagBox;
    private final TextBox templateName;

    private int delaySeconds;
    private boolean showChat;
    private boolean playChime;
    private String chimeSound;
    private boolean randomOrder;
    private int autoMin;
    private int autoMax;
    private int volume;
    private int radius;

    private int tab; // 0 settings, 1 network
    private final List<Row> rows = new ArrayList<>();
    private boolean networkLoaded;
    private boolean networkDirty;
    private int selectedRow = -1;
    private int allVolume = 100;
    private int allRadius = 16;

    private Popup popup = Popup.NONE;
    private int templateScope; // 0 whole box, 1 selected message
    private String pendingDelete;

    private int msgScroll;
    private int msgContent;
    private int rightScroll;
    private int rightContent;
    private int popupScroll;
    private int popupContent;

    // pane rectangles, recomputed each frame (for scrolling)
    private int leftX;
    private int leftY;
    private int leftW;
    private int leftH;
    private int rightX;
    private int rightY;
    private int rightW;
    private int rightH;
    private int popX;
    private int popY;
    private int popW;
    private int popH;

    private static SoundInstance chimePreview;

    public PaSourceScreen(AbstractPaBlockEntity source) {
        super(Text.literal(source instanceof ControlBoxBlockEntity ? "PA Control Box" : "PA Station Announcer"));
        this.source = source;
        this.box = source instanceof ControlBoxBlockEntity b ? b : null;

        MinecraftClient client = MinecraftClient.getInstance();
        editor = new TextBox(client.textRenderer, AbstractPaBlockEntity.MAX_TEXT_LENGTH, true);
        editor.placeholder = "Type the announcement…";
        editor.onChange(value -> {
            if (selected >= 0 && selected < messages.size()) {
                messages.get(selected).text = value;
            }
        });
        tagBox = new TextBox(client.textRenderer, AbstractPaBlockEntity.MAX_TAG_LENGTH, false);
        tagBox.placeholder = "e.g. platform1";
        tagBox.load(source.getAnnouncerTag());
        templateName = new TextBox(client.textRenderer, PaTemplates.MAX_NAME, false);
        templateName.placeholder = "Template name";

        if (box != null) {
            for (String raw : ControlBoxBlockEntity.splitAll(source.getText())) {
                messages.add(new Msg(PaText.stripDisabled(raw), !PaText.isDisabled(raw)));
            }
            randomOrder = box.isRandomOrder();
            autoMin = box.getAutoMinSeconds();
            autoMax = box.getAutoMaxSeconds();
            for (BlockPos pos : box.getSpeakers()) {
                rows.add(new Row(pos, false, (byte) -1, 0, 0, ""));
            }
            for (BlockPos pos : box.getDisplays()) {
                rows.add(new Row(pos, true, (byte) -1, 0, 0, ""));
            }
        } else {
            messages.add(new Msg(source.getText().trim(), true));
            AnnouncerBlockEntity announcer = (AnnouncerBlockEntity) source;
            volume = announcer.getVolume();
            radius = announcer.getRadius();
        }
        delaySeconds = source.getDelaySeconds();
        showChat = source.shouldShowChat();
        playChime = source.shouldPlayChime();
        chimeSound = source.getChimeSound();
        if (box == null || messages.size() == 1) {
            select(0);
        }
    }

    @Override
    protected void init() {
        if (box != null && !networkLoaded) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(box.getPos());
            ClientPlayNetworking.send(AnnouncerNetworking.NETWORK_QUERY_C2S, buf);
        }
    }

    @Override
    public void removed() {
        StationAnnouncerClient.stopPreview();
    }

    /** The server's answer to NETWORK_QUERY (also after a Network Save elsewhere). */
    public static void receiveNetworkInfo(BlockPos boxPos, List<ControlBoxBlockEntity.Member> members) {
        if (MinecraftClient.getInstance().currentScreen instanceof PaSourceScreen screen
                && screen.box != null && screen.box.getPos().equals(boxPos) && !screen.networkDirty) {
            screen.rows.clear();
            for (ControlBoxBlockEntity.Member m : members) {
                screen.rows.add(new Row(m.pos(), m.display(), m.state(), m.volume(), m.radius(), m.name()));
            }
            screen.networkLoaded = true;
            screen.selectedRow = -1;
            for (Row row : screen.rows) {
                if (row.editable()) {
                    screen.allVolume = row.volume;
                    screen.allRadius = row.radius;
                    break;
                }
            }
        }
    }

    /** Dev rig only (StationAnnouncerClient's #pa-gui hook): show a state without clicking. */
    public void devShow(String what, String arg) {
        switch (what) {
            case "card" -> select(Integer.parseInt(arg));
            case "network" -> tab = 1;
            case "templates" -> openPopup(Popup.TEMPLATES);
            case "chime" -> openPopup(Popup.CHIME);
            default -> {
            }
        }
    }

    // ================================================================= drawing

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0xB0000000);
        int w = Math.min(width - 12, 600);
        int h = Math.min(height - 12, 340);
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        FlatUi.rect(c, x0, y0, w, h, FlatUi.GROUND);
        FlatUi.outline(c, x0, y0, w, h, FlatUi.BORDER_STRONG);

        // Top bar
        c.drawText(textRenderer, title, x0 + 8, y0 + 7, FlatUi.TEXT, false);
        String where = source.getPos().toShortString();
        c.drawText(textRenderer, where, x0 + 12 + textRenderer.getWidth(title), y0 + 7, FlatUi.TEXT_FAINT, false);
        String fireLabel = box == null ? "▶ Fire" : selected >= 0 ? "▶ Fire selected" : "▶ Fire next";
        int fireW = textWidth(fireLabel) + 12;
        int bx = x0 + w - 6 - fireW;
        button(c, mx, my, fireLabel, bx, y0 + 3, fireW, 16, FlatUi.ButtonStyle.FLAT, true, H_FIRE, 0);
        int tplW = textWidth("Templates") + 12;
        button(c, mx, my, "Templates", bx - 4 - tplW, y0 + 3, tplW, 16, FlatUi.ButtonStyle.FLAT, true, H_TEMPLATES, 0);

        // Panes
        int bodyTop = y0 + 22;
        int bodyBottom = y0 + h - 24;
        int inner = w - 18;
        leftW = Math.round(inner * 0.56f);
        rightW = inner - leftW;
        leftX = x0 + 6;
        rightX = leftX + leftW + 6;
        leftY = rightY = bodyTop;
        leftH = rightH = bodyBottom - bodyTop;
        FlatUi.pane(c, leftX, leftY, leftW, leftH);
        FlatUi.pane(c, rightX, rightY, rightW, rightH);
        drawMessages(c, mx, my);
        drawRight(c, mx, my);

        // Footer
        int fy = y0 + h - 20;
        c.drawText(textRenderer, footerSummary(), x0 + 8, fy + 4, poolTooLong() ? FlatUi.DANGER : FlatUi.TEXT_FAINT, false);
        button(c, mx, my, "Save", x0 + w - 6 - 56, fy, 56, 16, FlatUi.ButtonStyle.PRIMARY, true, H_SAVE, 0);
        button(c, mx, my, "Cancel", x0 + w - 6 - 56 - 4 - 56, fy, 56, 16, FlatUi.ButtonStyle.GHOST, true, H_CANCEL, 0);

        if (popup == Popup.TEMPLATES) {
            drawTemplates(c, mx, my);
        } else if (popup == Popup.CHIME) {
            drawChimes(c, mx, my);
        }
    }

    private String footerSummary() {
        if (box == null) {
            return messages.get(0).text.length() + " / " + AbstractPaBlockEntity.MAX_TEXT_LENGTH + " characters";
        }
        int off = 0;
        for (Msg m : messages) {
            if (!m.on) {
                off++;
            }
        }
        String s = messages.size() + (messages.size() == 1 ? " message" : " messages");
        if (off > 0) {
            s += " · " + off + " off";
        }
        return s + " · " + joined().length() + " / " + ControlBoxBlockEntity.MAX_POOL_LENGTH;
    }

    // ----------------------------------------------------------- messages pane

    private void drawMessages(DrawContext c, int mx, int my) {
        int x = leftX + 6;
        int w = leftW - 12;
        FlatUi.heading(c, textRenderer, box == null ? "Announcement" : "Messages", x, leftY + 6);
        if (box != null) {
            String hint = randomOrder ? "random order" : "in order";
            c.drawText(textRenderer, hint, leftX + leftW - 6 - textWidth(hint), leftY + 6, FlatUi.TEXT_FAINT, false);
        }
        int top = leftY + 18;
        int viewH = leftH - 20;
        msgScroll = MathHelper.clamp(msgScroll, 0, Math.max(0, msgContent - viewH));
        beginClip(c, leftX + 1, top, leftW - 2, viewH);
        int y = top - msgScroll;
        if (box != null && messages.isEmpty()) {
            c.drawText(textRenderer, "No messages yet.", x, y + 2, FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, "Add one, or pick a template.", x, y + 12, FlatUi.TEXT_FAINT, false);
            y += 26;
        }
        for (int i = 0; i < messages.size(); i++) {
            y = i == selected ? drawSelectedCard(c, mx, my, i, x, y, w) : drawCard(c, mx, my, i, x, y, w);
            y += 4;
        }
        if (box != null && messages.size() < MAX_MESSAGES) {
            boolean hovered = FlatUi.inside(mx, my, x, y, w, 16);
            FlatUi.rect(c, x, y, w, 16, hovered ? FlatUi.PANE_RAISED : FlatUi.PANE);
            FlatUi.outline(c, x, y, w, 16, hovered ? FlatUi.BORDER_STRONG : FlatUi.BORDER);
            String add = "+ Add message";
            c.drawText(textRenderer, add, x + (w - textWidth(add)) / 2, y + 4, hovered ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hit(x, y, w, 16, H_ADD, 0);
            y += 20;
        }
        msgContent = y + msgScroll - top;
        endClip(c);
        FlatUi.scrollThumb(c, leftX + leftW, top, viewH, msgContent, msgScroll);
    }

    private int drawCard(DrawContext c, int mx, int my, int i, int x, int y, int w) {
        Msg m = messages.get(i);
        int textX = x + 30;
        int textW = w - 30 - 18;
        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(m.text.isBlank() ? "(empty)" : m.text), textW);
        int shown = Math.min(2, lines.size());
        int h = Math.max(20, 8 + shown * 10);
        boolean hovered = FlatUi.inside(mx, my, x, y, w, h);
        FlatUi.rect(c, x, y, w, h, hovered ? FlatUi.PANE_RAISED : 0xFF1A1A1F);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER);
        hit(x, y, w, h, H_CARD, i);
        // switch dot + number
        drawSwitchDot(c, mx, my, i, x + 5, y + 5, m.on);
        c.drawText(textRenderer, String.valueOf(i + 1), x + 18, y + 6, FlatUi.TEXT_FAINT, false);
        int ink = m.on ? (m.text.isBlank() ? FlatUi.TEXT_FAINT : FlatUi.TEXT) : FlatUi.TEXT_FAINT;
        for (int l = 0; l < shown; l++) {
            OrderedText line = lines.get(l);
            if (l == shown - 1 && lines.size() > shown) {
                c.drawText(textRenderer, "…", textX + textRenderer.getWidth(line) + 1, y + 6 + l * 10, ink, false);
            }
            c.drawText(textRenderer, line, textX, y + 6 + l * 10, ink, false);
        }
        drawPlay(c, mx, my, i, x + w - 16, y + 4);
        return y + h;
    }

    private int drawSelectedCard(DrawContext c, int mx, int my, int i, int x, int y, int w) {
        Msg m = messages.get(i);
        int startY = y;
        boolean single = box == null;
        // measure the editor first so the card can wrap it
        int edX = x + 5;
        int edW = w - 10;
        editor.setBounds(edX, 0, edW, 100);
        int edH = MathHelper.clamp(editor.lineCount(), 2, 6) * 10 + 8;
        String filled = previewText(m.text);
        String reads = PaText.shown(filled);
        String says = PaText.spoken(filled);
        boolean showReads = m.text.indexOf('{') >= 0;
        boolean showSays = !says.equals(reads);
        int h = 18 + edH + 4 + 14 + (showReads ? 10 : 0) + (showSays ? 10 : 0) + 4;

        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.ACCENT_DIM);
        hit(x, y, w, h, H_SWALLOW, 0);
        // header
        if (!single) {
            drawSwitchDot(c, mx, my, i, x + 5, y + 5, m.on);
            c.drawText(textRenderer, "Message " + (i + 1) + (m.on ? "" : " (off)"), x + 18, y + 6, FlatUi.TEXT_DIM, false);
            int ix = x + w - 16;
            iconHit(c, mx, my, "×", ix, y + 3, FlatUi.DANGER);
            hit(ix, y + 3, 12, 12, H_CARD_DELETE, i);
            ix -= 14;
            iconHit(c, mx, my, "↓", ix, y + 3, i < messages.size() - 1 ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT);
            hit(ix, y + 3, 12, 12, H_CARD_DOWN, i);
            ix -= 14;
            iconHit(c, mx, my, "↑", ix, y + 3, i > 0 ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT);
            hit(ix, y + 3, 12, 12, H_CARD_UP, i);
            ix -= 16;
            drawPlay(c, mx, my, i, ix, y + 3);
        } else {
            c.drawText(textRenderer, "What the PA says", x + 6, y + 6, FlatUi.TEXT_DIM, false);
            drawPlay(c, mx, my, i, x + w - 16, y + 3);
        }
        y += 18;
        box(c, editor, edX, y, edW, edH, mx, my);
        y += edH + 4;
        // token row
        int tx = edX;
        tx += tokenButton(c, mx, my, "+ {station}", tx, y, 0) + 3;
        tx += tokenButton(c, mx, my, "+ {time}", tx, y, 1) + 3;
        tokenButton(c, mx, my, "Say as…", tx, y, 2);
        y += 14;
        if (showReads) {
            c.drawText(textRenderer, trim("Reads: " + reads, edW), edX, y + 1, FlatUi.TEXT_FAINT, false);
            y += 10;
        }
        if (showSays) {
            c.drawText(textRenderer, trim("Says: " + says, edW), edX, y + 1, 0xFF8FB4E8, false);
            y += 10;
        }
        return startY + h;
    }

    private String previewText(String raw) {
        String text = raw;
        if (text.contains(PaText.STATION)) {
            text = PaText.fill(text, PaText.clientStationAt(source.getPos()), null);
        }
        return text.contains(PaText.TIME) ? PaText.fill(text, null, PaText.clock()) : text;
    }

    private void drawSwitchDot(DrawContext c, int mx, int my, int i, int x, int y, boolean on) {
        if (box == null) {
            return;
        }
        boolean hovered = FlatUi.inside(mx, my, x - 2, y - 2, 12, 12);
        FlatUi.rect(c, x, y, 8, 8, on ? FlatUi.OK : FlatUi.INPUT);
        FlatUi.outline(c, x, y, 8, 8, hovered ? FlatUi.TEXT : on ? FlatUi.OK : FlatUi.BORDER_STRONG);
        hit(x - 2, y - 2, 12, 12, H_CARD_SWITCH, i);
    }

    private void drawPlay(DrawContext c, int mx, int my, int i, int x, int y) {
        iconHit(c, mx, my, "▶", x, y, FlatUi.OK);
        hit(x, y, 12, 12, H_CARD_PLAY, i);
    }

    private boolean iconHit(DrawContext c, int mx, int my, String glyph, int x, int y, int ink) {
        return FlatUi.iconButton(c, textRenderer, glyph, x, y, 12, mx, my, ink);
    }

    private int tokenButton(DrawContext c, int mx, int my, String label, int x, int y, int arg) {
        int w = textWidth(label) + 8;
        FlatUi.button(c, textRenderer, label, x, y, w, 12, mx, my, FlatUi.ButtonStyle.FLAT);
        hit(x, y, w, 12, H_TOKEN, arg);
        return w;
    }

    // -------------------------------------------------------------- right pane

    private void drawRight(DrawContext c, int mx, int my) {
        int x = rightX + 6;
        int w = rightW - 12;
        int top = rightY + 6;
        if (box != null) {
            String[] labels = {"Settings", "Network · " + rows.size()};
            FlatUi.segmented(c, textRenderer, labels, tab, x, top, w, 14, mx, my);
            int half = w / 2;
            hit(x, top, half, 14, H_TAB, 0);
            hit(x + half, top, w - half, 14, H_TAB, 1);
            top += 20;
        }
        int viewH = rightY + rightH - 2 - top;
        rightScroll = MathHelper.clamp(rightScroll, 0, Math.max(0, rightContent - viewH));
        beginClip(c, rightX + 1, top, rightW - 2, viewH);
        int y = top - rightScroll;
        y = tab == 0 || box == null ? drawSettings(c, mx, my, x, y, w) : drawNetwork(c, mx, my, x, y, w);
        rightContent = y + rightScroll - top;
        endClip(c);
        FlatUi.scrollThumb(c, rightX + rightW, top, viewH, rightContent, rightScroll);
    }

    private int drawSettings(DrawContext c, int mx, int my, int x, int y, int w) {
        FlatUi.heading(c, textRenderer, "Playback", x, y);
        y += 11;
        if (box != null) {
            c.drawText(textRenderer, "Order", x, y + 3, FlatUi.TEXT_DIM, false);
            int segX = x + 46;
            int segW = w - 46;
            FlatUi.segmented(c, textRenderer, new String[]{"Random", "In order"}, randomOrder ? 0 : 1,
                    segX, y, segW, 13, mx, my);
            hit(segX, y, segW / 2, 13, H_ORDER, 0);
            hit(segX + segW / 2, y, segW - segW / 2, 13, H_ORDER, 1);
            y += 17;
        }
        toggleRow(c, mx, my, "Show in chat", showChat, x, y, w, H_TOGGLE, TOGGLE_CHAT);
        y += 15;
        toggleRow(c, mx, my, "Chime first", playChime, x, y, w, H_TOGGLE, TOGGLE_CHIME);
        y += 15;
        if (playChime) {
            String label = chimeLabel(chimeSound) + "  ▾";
            boolean hovered = FlatUi.inside(mx, my, x, y, w, 14);
            FlatUi.rect(c, x, y, w, 14, FlatUi.INPUT);
            FlatUi.outline(c, x, y, w, 14, hovered ? FlatUi.BORDER_STRONG : FlatUi.BORDER);
            c.drawText(textRenderer, trim(label, w - 8), x + 4, y + 3, FlatUi.TEXT, false);
            hit(x, y, w, 14, H_CHIME_OPEN, 0);
            y += 18;
        }
        slider(c, mx, my, S_DELAY, "Delay", 46, delaySeconds, 0, AbstractPaBlockEntity.MAX_DELAY_SECONDS,
                delaySeconds == 0 ? "none" : delaySeconds + " s", x, y, w);
        y += 16;

        if (box == null) {
            y += 4;
            FlatUi.heading(c, textRenderer, "Sound", x, y);
            y += 11;
            slider(c, mx, my, S_VOLUME, "Volume", 46, volume, 0, 100, volume + "%", x, y, w);
            y += 15;
            slider(c, mx, my, S_RADIUS, "Radius", 46, radius, AnnouncerBlockEntity.MIN_RADIUS,
                    AnnouncerBlockEntity.MAX_RADIUS, radius + " m", x, y, w);
            y += 15;
            c.drawText(textRenderer, trim("Speaker Link in hand shows the reach", w), x, y + 1, FlatUi.TEXT_FAINT, false);
            y += 12;
        } else {
            y += 4;
            FlatUi.heading(c, textRenderer, "Automatic", x, y);
            y += 11;
            toggleRow(c, mx, my, "Play by itself", autoMin > 0, x, y, w, H_TOGGLE, TOGGLE_AUTO);
            y += 15;
            if (autoMin > 0) {
                slider(c, mx, my, S_AUTO_MIN, "Every", 46, autoMin, 5, MAX_AUTO, duration(autoMin), x, y, w);
                y += 15;
                slider(c, mx, my, S_AUTO_MAX, "up to", 46, Math.max(autoMin, autoMax), 5, MAX_AUTO,
                        duration(Math.max(autoMin, autoMax)), x, y, w);
                y += 15;
                String summary = autoMax > autoMin
                        ? "Plays every " + duration(autoMin) + "–" + duration(autoMax) + " at random"
                        : "Plays every " + duration(autoMin);
                c.drawText(textRenderer, trim(summary, w), x, y + 1, FlatUi.TEXT_FAINT, false);
                y += 12;
            }
        }

        y += 4;
        FlatUi.heading(c, textRenderer, "Trigger", x, y);
        y += 11;
        c.drawText(textRenderer, "Tag", x, y + 4, FlatUi.TEXT_DIM, false);
        box(c, tagBox, x + 46, y, w - 46, 15, mx, my);
        y += 19;
        String tag = tagBox.getText().trim();
        c.drawText(textRenderer, trim(tag.isEmpty() ? "Redstone pulse, or give it a tag" : "/announce tag=" + tag, w),
                x, y, FlatUi.TEXT_FAINT, false);
        y += 12;
        return y;
    }

    private int drawNetwork(DrawContext c, int mx, int my, int x, int y, int w) {
        int speakers = 0;
        int displays = 0;
        int unloaded = 0;
        int gone = 0;
        for (Row row : rows) {
            if (row.display) {
                displays++;
            } else {
                speakers++;
            }
            if (row.state == ControlBoxBlockEntity.MEMBER_NOT_LOADED) {
                unloaded++;
            } else if (row.state == ControlBoxBlockEntity.MEMBER_GONE) {
                gone++;
            }
        }
        c.drawText(textRenderer, speakers + " speakers · " + displays + " displays", x, y, FlatUi.TEXT, false);
        y += 10;
        if (!networkLoaded) {
            c.drawText(textRenderer, "Asking the server…", x, y, FlatUi.TEXT_FAINT, false);
            y += 10;
        } else if (unloaded + gone > 0) {
            String s = (unloaded > 0 ? unloaded + " not loaded" : "") + (unloaded > 0 && gone > 0 ? " · " : "")
                    + (gone > 0 ? gone + " missing" : "");
            c.drawText(textRenderer, s, x, y, gone > 0 ? FlatUi.DANGER : 0xFFE0B341, false);
            y += 10;
        }
        y += 4;

        boolean anyEditable = rows.stream().anyMatch(Row::editable);
        if (anyEditable) {
            FlatUi.rect(c, x, y, w, 50, 0xFF1A1A1F);
            FlatUi.outline(c, x, y, w, 50, FlatUi.BORDER);
            FlatUi.heading(c, textRenderer, "All speakers", x + 4, y + 4);
            slider(c, mx, my, S_ALL_VOLUME, "Volume", 44, allVolume, 0, 100, allVolume + "%", x + 4, y + 14, w - 8);
            slider(c, mx, my, S_ALL_RADIUS, "Radius", 44, allRadius, 1, 128, allRadius + " m", x + 4, y + 27, w - 8);
            int bw = textWidth("Apply to all") + 10;
            button(c, mx, my, "Apply to all", x + w - 4 - bw, y + 2, bw, 11, FlatUi.ButtonStyle.FLAT, true, H_APPLY_ALL, 0);
            y += 54;
        }

        if (rows.isEmpty()) {
            c.drawText(textRenderer, "Nothing linked yet.", x, y, FlatUi.TEXT_DIM, false);
            y += 12;
        }
        BlockPos me = MinecraftClient.getInstance().player == null ? source.getPos()
                : MinecraftClient.getInstance().player.getBlockPos();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean open = i == selectedRow && row.editable();
            int h = open ? 44 : 16;
            boolean hovered = FlatUi.inside(mx, my, x, y, w, 16);
            FlatUi.rect(c, x, y, w, h, open ? FlatUi.PANE_RAISED : hovered ? 0xFF24242A : 0xFF1A1A1F);
            FlatUi.outline(c, x, y, w, h, open ? FlatUi.ACCENT_DIM : FlatUi.BORDER);
            hit(x, y, w, 16, H_ROW, i);
            int dot = row.unlink ? FlatUi.TEXT_FAINT
                    : row.state == ControlBoxBlockEntity.MEMBER_OK ? FlatUi.OK
                    : row.state == ControlBoxBlockEntity.MEMBER_GONE ? FlatUi.DANGER
                    : row.state == ControlBoxBlockEntity.MEMBER_NOT_LOADED ? 0xFFE0B341 : FlatUi.TEXT_FAINT;
            FlatUi.rect(c, x + 4, y + 5, 6, 6, dot);
            String kind = row.display ? displayName(row) + "  " : "";
            int dist = (int) Math.round(Math.sqrt(me.getSquaredDistance(row.pos)));
            String value = row.unlink ? "unlink" : row.display || row.state != ControlBoxBlockEntity.MEMBER_OK
                    ? stateWord(row) : row.volume + "% · " + row.radius + " m";
            int valueW = textWidth(value);
            int ink = row.unlink ? FlatUi.TEXT_FAINT : FlatUi.TEXT;
            String label = trim(kind + row.pos.toShortString() + " · " + dist + " m", w - 34 - valueW);
            c.drawText(textRenderer, label, x + 14, y + 4, ink, false);
            if (row.unlink) {
                FlatUi.rect(c, x + 14, y + 8, textWidth(label), 1, FlatUi.TEXT_FAINT);
            }
            boolean changed = !row.display && (row.volume != row.origVolume || row.radius != row.origRadius);
            c.drawText(textRenderer, value, x + w - 18 - valueW, y + 4,
                    row.unlink ? FlatUi.DANGER : changed ? FlatUi.ACCENT : FlatUi.TEXT_DIM, false);
            iconHit(c, mx, my, row.unlink ? "↺" : "×", x + w - 14, y + 2, row.unlink ? FlatUi.TEXT_DIM : FlatUi.DANGER);
            hit(x + w - 14, y + 2, 12, 12, H_ROW_UNLINK, i);
            if (open) {
                slider(c, mx, my, S_ROW_VOLUME, "Volume", 44, row.volume, 0, 100, row.volume + "%", x + 4, y + 17, w - 8);
                slider(c, mx, my, S_ROW_RADIUS, "Radius", 44, row.radius, 1, 128, row.radius + " m", x + 4, y + 30, w - 8);
            }
            y += h + 2;
        }
        y += 4;
        if (!rows.isEmpty()) {
            int bw = textWidth("Unlink all") + 12;
            button(c, mx, my, "Unlink all", x, y, bw, 14, FlatUi.ButtonStyle.DANGER, true, H_UNLINK_ALL, 0);
            y += 18;
        }
        for (OrderedText line : textRenderer.wrapLines(Text.literal(
                "Link more with the Speaker Link: select this box, then click speakers — or sneak-click two blocks to link everything between them."), w)) {
            c.drawText(textRenderer, line, x, y, FlatUi.TEXT_FAINT, false);
            y += 10;
        }
        return y + 4;
    }

    private String stateWord(Row row) {
        return switch (row.state) {
            case ControlBoxBlockEntity.MEMBER_OK -> "OK";
            case ControlBoxBlockEntity.MEMBER_NOT_LOADED -> "not loaded";
            case ControlBoxBlockEntity.MEMBER_GONE -> "missing";
            default -> "…";
        };
    }

    private String displayName(Row row) {
        if (row.name == null || row.name.isEmpty()) {
            return "Display";
        }
        String name = Text.translatable(row.name).getString();
        return name.length() > 18 ? "Display" : name;
    }

    // ---------------------------------------------------------------- popups

    private void drawTemplates(DrawContext c, int mx, int my) {
        beginModal(c, H_BACKDROP);
        popW = Math.min(340, width - 20);
        popH = Math.min(240, height - 20);
        popX = (width - popW) / 2;
        popY = (height - popH) / 2;
        FlatUi.rect(c, popX, popY, popW, popH, FlatUi.GROUND);
        FlatUi.outline(c, popX, popY, popW, popH, FlatUi.BORDER_STRONG);
        hit(popX, popY, popW, popH, H_SWALLOW, 0);
        c.drawText(textRenderer, "Templates", popX + 8, popY + 7, FlatUi.TEXT, false);
        int hidden = PaTemplates.hiddenCount();
        if (hidden > 0) {
            String restore = "Restore " + hidden + " deleted";
            int rw = textWidth(restore) + 10;
            button(c, mx, my, restore, popX + popW - 6 - rw, popY + 4, rw, 13, FlatUi.ButtonStyle.GHOST, true, H_TPL_RESTORE, 0);
        }

        int listTop = popY + 22;
        int footerH = 40;
        int viewH = popH - 22 - footerH;
        List<PaTemplates.Template> all = PaTemplates.all();
        popupScroll = MathHelper.clamp(popupScroll, 0, Math.max(0, popupContent - viewH));
        beginClip(c, popX + 1, listTop, popW - 2, viewH);
        int y = listTop - popupScroll;
        int x = popX + 6;
        int w = popW - 12;
        for (int i = 0; i < all.size(); i++) {
            PaTemplates.Template t = all.get(i);
            boolean hovered = FlatUi.inside(mx, my, x, y, w, 24);
            FlatUi.rect(c, x, y, w, 24, hovered ? FlatUi.PANE_RAISED : FlatUi.PANE);
            FlatUi.outline(c, x, y, w, 24, FlatUi.BORDER);
            int buttonsW = box == null ? 36 + 16 : 32 + 50 + 16;
            String name = t.name() + (t.settings() != null ? "  · with settings" : "");
            c.drawText(textRenderer, trim(name, w - buttonsW - 10), x + 5, y + 3, FlatUi.TEXT, false);
            c.drawText(textRenderer, trim(t.summary(), w - buttonsW - 10), x + 5, y + 13,
                    t.builtIn() ? FlatUi.TEXT_FAINT : FlatUi.TEXT_DIM, false);
            int bx = x + w - 4;
            boolean confirming = t.name().equals(pendingDelete) && !t.builtIn();
            if (confirming) {
                int dw = textWidth("Delete?") + 8;
                bx -= dw;
                button(c, mx, my, "Delete?", bx, y + 6, dw, 12, FlatUi.ButtonStyle.DANGER, true, H_TPL_DELETE, i);
            } else {
                bx -= 12;
                iconHit(c, mx, my, "×", bx, y + 6, FlatUi.TEXT_FAINT);
                hit(bx, y + 6, 12, 12, H_TPL_DELETE, i);
            }
            if (box != null) {
                bx -= 50;
                button(c, mx, my, "Replace", bx, y + 6, 46, 12, FlatUi.ButtonStyle.FLAT, true, H_TPL_REPLACE, i);
                bx -= 32;
                button(c, mx, my, "Add", bx, y + 6, 28, 12, FlatUi.ButtonStyle.PRIMARY, true, H_TPL_ADD, i);
            } else {
                bx -= 36;
                button(c, mx, my, "Use", bx, y + 6, 32, 12, FlatUi.ButtonStyle.PRIMARY, true, H_TPL_REPLACE, i);
            }
            y += 27;
        }
        if (all.isEmpty()) {
            c.drawText(textRenderer, "No templates.", x, y + 4, FlatUi.TEXT_DIM, false);
            y += 16;
        }
        popupContent = y + popupScroll - listTop;
        endClip(c);
        FlatUi.scrollThumb(c, popX + popW, listTop, viewH, popupContent, popupScroll);

        // Save row
        int fy = popY + popH - footerH + 6;
        FlatUi.heading(c, textRenderer, "Save as template", x, fy);
        fy += 11;
        int saveW = 40;
        int scopeW = box != null ? 104 : 0;
        box(c, templateName, x, fy, w - saveW - scopeW - 8, 15, mx, my);
        if (box != null) {
            int sx = x + w - saveW - scopeW - 4;
            FlatUi.segmented(c, textRenderer, new String[]{"Whole box", "Message"}, templateScope, sx, fy + 1, scopeW, 13, mx, my);
            hit(sx, fy + 1, scopeW / 2, 13, H_TPL_SCOPE, 0);
            hit(sx + scopeW / 2, fy + 1, scopeW - scopeW / 2, 13, H_TPL_SCOPE, 1);
        }
        button(c, mx, my, "Save", x + w - saveW, fy + 1, saveW, 13, FlatUi.ButtonStyle.PRIMARY, true, H_TPL_SAVE, 0);
        endModal(c);
    }

    private void drawChimes(DrawContext c, int mx, int my) {
        beginModal(c, H_BACKDROP);
        List<ModContent.ChimeOption> options = chimeOptions();
        popW = Math.min(200, width - 20);
        popH = Math.min(22 + options.size() * 15 + 6, height - 20);
        popX = Math.min(rightX + 6, width - popW - 10);
        popY = (height - popH) / 2;
        FlatUi.rect(c, popX, popY, popW, popH, FlatUi.GROUND);
        FlatUi.outline(c, popX, popY, popW, popH, FlatUi.BORDER_STRONG);
        hit(popX, popY, popW, popH, H_SWALLOW, 0);
        c.drawText(textRenderer, "Chime  (click to hear)", popX + 8, popY + 7, FlatUi.TEXT_DIM, false);
        int listTop = popY + 20;
        int viewH = popH - 24;
        popupScroll = MathHelper.clamp(popupScroll, 0, Math.max(0, popupContent - viewH));
        beginClip(c, popX + 1, listTop, popW - 2, viewH);
        int y = listTop - popupScroll;
        for (int i = 0; i < options.size(); i++) {
            ModContent.ChimeOption option = options.get(i);
            boolean chosen = option.id().equals(chimeSound.trim());
            boolean hovered = FlatUi.inside(mx, my, popX + 4, y, popW - 8, 14);
            FlatUi.rect(c, popX + 4, y, popW - 8, 14, chosen ? FlatUi.SELECTED : hovered ? FlatUi.PANE_RAISED : 0);
            c.drawText(textRenderer, (chosen ? "● " : "   ") + option.label(), popX + 8, y + 3,
                    chosen ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hit(popX + 4, y, popW - 8, 14, H_CHIME_PICK, i);
            y += 15;
        }
        popupContent = y + popupScroll - listTop;
        endClip(c);
        endModal(c);
    }

    private List<ModContent.ChimeOption> chimeOptions() {
        List<ModContent.ChimeOption> options = new ArrayList<>(ModContent.CHIME_OPTIONS);
        String id = chimeSound.trim();
        if (options.stream().noneMatch(o -> o.id().equals(id))) {
            options.add(new ModContent.ChimeOption(id, "Custom: " + id, ModContent.chimeLeadTicks("")));
        }
        return options;
    }

    private static String chimeLabel(String id) {
        for (ModContent.ChimeOption option : ModContent.CHIME_OPTIONS) {
            if (option.id().equals(id.trim())) {
                return option.label();
            }
        }
        return "Custom: " + id;
    }

    private static String duration(int seconds) {
        return seconds < 60 ? seconds + " s" : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ================================================================= input

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        if (id != H_TPL_DELETE) {
            pendingDelete = null;
        }
        switch (id) {
            case 0, H_BACKDROP -> {
                if (popup != Popup.NONE) {
                    popup = Popup.NONE;
                } else if (id == 0 && FlatUi.inside(mx, my, leftX, leftY, leftW, leftH) && box != null) {
                    select(-1); // empty space in the list: deselect
                }
            }
            case H_CARD -> select(arg);
            case H_CARD_SWITCH -> messages.get(arg).on = !messages.get(arg).on;
            case H_CARD_PLAY -> {
                Msg m = messages.get(arg);
                if (m.text.isBlank()) {
                    toast("Nothing to say yet", true);
                } else {
                    StationAnnouncerClient.previewLocally(PaText.clean(m.text), source.getPos(), 1.0f, playChime, chimeSound);
                }
            }
            case H_CARD_UP -> move(arg, -1);
            case H_CARD_DOWN -> move(arg, 1);
            case H_CARD_DELETE -> {
                messages.remove(arg);
                select(-1);
            }
            case H_ADD -> addMessage(messages.size(), "");
            case H_TOKEN -> token(arg);
            case H_TAB -> {
                tab = arg;
                rightScroll = 0;
            }
            case H_ORDER -> randomOrder = arg == 0;
            case H_TOGGLE -> {
                switch (arg) {
                    case TOGGLE_CHAT -> showChat = !showChat;
                    case TOGGLE_CHIME -> playChime = !playChime;
                    default -> {
                        if (autoMin > 0) {
                            autoMin = 0;
                        } else {
                            autoMin = 120;
                            autoMax = Math.max(autoMax, 300);
                        }
                    }
                }
            }
            case H_CHIME_OPEN -> openPopup(Popup.CHIME);
            case H_CHIME_PICK -> {
                ModContent.ChimeOption option = chimeOptions().get(arg);
                chimeSound = option.id();
                playChimePreview(option.id());
            }
            case H_TEMPLATES -> openPopup(Popup.TEMPLATES);
            case H_FIRE -> fire();
            case H_SAVE -> save();
            case H_CANCEL -> close();
            case H_ROW -> selectedRow = selectedRow == arg ? -1 : arg;
            case H_ROW_UNLINK -> {
                rows.get(arg).unlink = !rows.get(arg).unlink;
                networkDirty = true;
            }
            case H_APPLY_ALL -> {
                int n = 0;
                for (Row row : rows) {
                    if (row.editable()) {
                        row.volume = allVolume;
                        row.radius = allRadius;
                        n++;
                    }
                }
                networkDirty = true;
                toast("Set " + n + " speakers to " + allVolume + "% · " + allRadius + " m (Save to keep)", false);
            }
            case H_UNLINK_ALL -> {
                boolean all = rows.stream().allMatch(r -> r.unlink);
                rows.forEach(r -> r.unlink = !all);
                networkDirty = true;
            }
            case H_TPL_ADD, H_TPL_REPLACE -> applyTemplate(PaTemplates.all().get(arg), id == H_TPL_REPLACE);
            case H_TPL_DELETE -> {
                PaTemplates.Template t = PaTemplates.all().get(arg);
                if (t.builtIn() || t.name().equals(pendingDelete)) {
                    PaTemplates.delete(t);
                    pendingDelete = null;
                    toast(t.builtIn() ? "Hidden \"" + t.name() + "\" (Restore brings it back)" : "Deleted \"" + t.name() + "\"", false);
                } else {
                    pendingDelete = t.name();
                }
            }
            case H_TPL_SCOPE -> templateScope = arg;
            case H_TPL_SAVE -> saveTemplate();
            case H_TPL_RESTORE -> PaTemplates.restoreBuiltIns();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        switch (key) {
            case S_DELAY -> delaySeconds = value;
            case S_AUTO_MIN -> {
                autoMin = Math.max(5, snap(value, 5));
                autoMax = Math.max(autoMax, autoMin);
            }
            case S_AUTO_MAX -> autoMax = Math.max(autoMin, snap(value, 5));
            case S_VOLUME -> volume = value;
            case S_RADIUS -> radius = value;
            case S_ALL_VOLUME -> allVolume = value;
            case S_ALL_RADIUS -> allRadius = value;
            case S_ROW_VOLUME, S_ROW_RADIUS -> {
                if (selectedRow >= 0 && selectedRow < rows.size()) {
                    Row row = rows.get(selectedRow);
                    if (key == S_ROW_VOLUME) {
                        row.volume = value;
                    } else {
                        row.radius = value;
                    }
                    networkDirty = true;
                }
            }
            default -> {
            }
        }
    }

    private static int snap(int value, int step) {
        return Math.round(value / (float) step) * step;
    }

    @Override
    protected boolean onEnter() {
        if (popup == Popup.TEMPLATES && focused == templateName) {
            saveTemplate();
            return true;
        }
        if (focused == editor && box != null && selected >= 0) {
            addMessage(selected + 1, ""); // Enter = next announcement
            return true;
        }
        if (focused == editor || focused == tagBox) {
            focus(null);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onEscape() {
        if (popup != Popup.NONE) {
            popup = Popup.NONE;
            return true;
        }
        close();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_S && Screen.hasControlDown()) {
            save();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        int step = (int) Math.signum(vertical) * 18;
        if (popup != Popup.NONE) {
            if (FlatUi.inside(mx, my, popX, popY, popW, popH)) {
                popupScroll -= step;
            }
            return true;
        }
        if (focused == editor && editor.mouseScrolled(mx, my, vertical)) {
            return true;
        }
        if (FlatUi.inside(mx, my, leftX, leftY, leftW, leftH)) {
            msgScroll -= step;
        } else if (FlatUi.inside(mx, my, rightX, rightY, rightW, rightH)) {
            rightScroll -= step;
        }
        return true;
    }

    // ---------------------------------------------------------------- actions

    private void openPopup(Popup which) {
        popup = which;
        popupScroll = 0;
        focus(null);
    }

    private void select(int index) {
        selected = index >= 0 && index < messages.size() ? index : -1;
        if (selected >= 0) {
            editor.load(messages.get(selected).text);
            focus(editor);
        } else if (focused == editor) {
            focus(null);
        }
    }

    private void addMessage(int at, String text) {
        if (messages.size() >= MAX_MESSAGES) {
            toast("At most " + MAX_MESSAGES + " messages", true);
            return;
        }
        int index = MathHelper.clamp(at, 0, messages.size());
        messages.add(index, new Msg(text, true));
        select(index);
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= messages.size()) {
            return;
        }
        Msg m = messages.remove(index);
        messages.add(to, m);
        selected = to;
    }

    private void token(int which) {
        if (selected < 0) {
            return;
        }
        focus(editor);
        switch (which) {
            case 0 -> editor.insertToken(PaText.STATION);
            case 1 -> editor.insertToken(PaText.TIME);
            default -> {
                String word = editor.getSelectedText().replaceAll("[{}|]", "").trim();
                if (word.isEmpty()) {
                    editor.insert("{|}");
                    editor.select(editor.getCursor() - 2, editor.getCursor() - 2);
                    toast("Type how it reads, then after | how it is said", false);
                } else {
                    editor.insert("{" + word + "|" + word + "}");
                    int end = editor.getCursor() - 1;
                    editor.select(end - word.length(), end); // type over the spoken half
                    toast("Now type how \"" + word + "\" should be said", false);
                }
            }
        }
    }

    private List<PaText.Entry> entries() {
        List<PaText.Entry> out = new ArrayList<>();
        for (Msg m : messages) {
            out.add(new PaText.Entry(m.text, m.on));
        }
        return out;
    }

    private String joined() {
        return PaText.join(entries(), ControlBoxBlockEntity.MESSAGE_SEPARATOR);
    }

    private boolean poolTooLong() {
        return box != null && joined().length() > ControlBoxBlockEntity.MAX_POOL_LENGTH;
    }

    private void fire() {
        String message;
        if (box == null) {
            message = PaText.clean(messages.get(0).text);
            if (message.isEmpty()) {
                toast("Nothing to say yet", true);
                return;
            }
        } else if (selected >= 0) {
            message = PaText.clean(messages.get(selected).text);
            if (message.isEmpty()) {
                toast("Nothing to say yet", true);
                return;
            }
        } else {
            if (ControlBoxBlockEntity.splitMessages(source.getText()).length == 0) {
                toast("No saved messages are switched on", true);
                return;
            }
            message = ""; // the next saved pool message
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(source.getPos());
        buf.writeString(message, AbstractPaBlockEntity.MAX_TEXT_LENGTH);
        ClientPlayNetworking.send(AnnouncerNetworking.FIRE_NOW_C2S, buf);
        toast(box != null && rows.isEmpty() ? "Fired, but no speakers are linked" : "Fired to everyone in range", false);
    }

    private void applyTemplate(PaTemplates.Template t, boolean replace) {
        if (box == null) {
            messages.get(0).text = t.messages().get(0).text();
            select(0);
        } else {
            if (replace) {
                messages.clear();
            }
            for (PaText.Entry entry : t.messages()) {
                if (messages.size() < MAX_MESSAGES) {
                    messages.add(new Msg(entry.text(), entry.enabled()));
                }
            }
            select(replace ? -1 : messages.size() - 1);
        }
        PaTemplates.Settings s = t.settings();
        if (replace && s != null) {
            delaySeconds = s.delaySeconds();
            showChat = s.showChat();
            playChime = s.playChime();
            chimeSound = s.chimeSound();
            if (box != null) {
                randomOrder = s.randomOrder();
                autoMin = s.autoMinSeconds();
                autoMax = s.autoMaxSeconds();
            }
        }
        popup = Popup.NONE;
        toast((replace ? "Loaded \"" : "Added \"") + t.name() + "\" — Save to keep", false);
    }

    private void saveTemplate() {
        String name = templateName.getText().trim();
        if (name.isEmpty()) {
            toast("Type a name for the template first", true);
            focus(templateName);
            return;
        }
        List<PaText.Entry> list;
        PaTemplates.Settings settings = null;
        if (box != null && templateScope == 1) {
            if (selected < 0 || PaText.clean(messages.get(selected).text).isEmpty()) {
                toast("Select a message first", true);
                return;
            }
            list = List.of(new PaText.Entry(PaText.clean(messages.get(selected).text), true));
        } else {
            list = new ArrayList<>();
            for (PaText.Entry e : entries()) {
                if (!PaText.clean(e.text()).isEmpty()) {
                    list.add(new PaText.Entry(PaText.clean(e.text()), e.enabled()));
                }
            }
            if (list.isEmpty()) {
                toast("Nothing to save yet", true);
                return;
            }
            settings = new PaTemplates.Settings(delaySeconds, showChat, playChime, chimeSound,
                    randomOrder, autoMin, autoMax);
        }
        boolean replacing = PaTemplates.exists(name);
        if (!PaTemplates.put(name, list, settings)) {
            toast("Template list is full — delete one first", true);
            return;
        }
        templateName.load("");
        toast((replacing ? "Replaced \"" : "Saved \"") + name + "\"", false);
    }

    private void save() {
        if (poolTooLong()) {
            toast("Too much text: remove or shorten a message", true);
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(source.getPos());
        String tag = tagBox.getText().trim();
        if (box != null) {
            buf.writeString(joined(), ControlBoxBlockEntity.MAX_POOL_LENGTH);
            buf.writeVarInt(delaySeconds);
            buf.writeString(tag, AbstractPaBlockEntity.MAX_TAG_LENGTH);
            buf.writeBoolean(showChat);
            buf.writeBoolean(playChime);
            buf.writeString(chimeSound.trim(), AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
            buf.writeBoolean(randomOrder);
            buf.writeVarInt(autoMin);
            buf.writeVarInt(autoMin > 0 ? Math.max(autoMin, autoMax) : autoMax);
            ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_CONTROL_BOX_C2S, buf);
            if (networkDirty) {
                sendNetworkEdit();
            }
        } else {
            buf.writeString(PaText.clean(messages.get(0).text), AbstractPaBlockEntity.MAX_TEXT_LENGTH);
            buf.writeVarInt(volume);
            buf.writeVarInt(delaySeconds);
            buf.writeVarInt(radius);
            buf.writeString(tag, AbstractPaBlockEntity.MAX_TAG_LENGTH);
            buf.writeBoolean(showChat);
            buf.writeBoolean(playChime);
            buf.writeString(chimeSound.trim(), AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
            ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_ANNOUNCER_C2S, buf);
        }
        close();
    }

    private void sendNetworkEdit() {
        List<Row> unlink = new ArrayList<>();
        List<Row> changed = new ArrayList<>();
        for (Row row : rows) {
            if (row.unlink) {
                unlink.add(row);
            } else if (row.editable() && (row.volume != row.origVolume || row.radius != row.origRadius)) {
                changed.add(row);
            }
        }
        if (unlink.isEmpty() && changed.isEmpty()) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(source.getPos());
        buf.writeVarInt(unlink.size());
        unlink.forEach(row -> buf.writeBlockPos(row.pos));
        buf.writeVarInt(changed.size());
        for (Row row : changed) {
            buf.writeBlockPos(row.pos);
            buf.writeVarInt(row.volume);
            buf.writeVarInt(row.radius);
        }
        ClientPlayNetworking.send(AnnouncerNetworking.NETWORK_EDIT_C2S, buf);
        LinkLineRenderer.invalidate();
    }

    /** Plays a chime by id on the master channel (audible regardless of the PA's own volume settings). */
    static void playChimePreview(String chimeId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (chimePreview != null) {
            client.getSoundManager().stop(chimePreview);
            chimePreview = null;
        }
        SoundEvent event = ModContent.CHIME;
        if (!chimeId.isBlank()) {
            Identifier id = Identifier.tryParse(chimeId);
            if (id == null || client.getSoundManager().get(id) == null) {
                return; // unknown custom id: no preview rather than a wrong sound
            }
            event = SoundEvent.of(id);
        }
        chimePreview = PositionedSoundInstance.master(event, 1.0f);
        client.getSoundManager().play(chimePreview);
    }
}
