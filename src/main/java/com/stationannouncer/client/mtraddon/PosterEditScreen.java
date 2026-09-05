package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * The service change poster editor, laid out like a small design tool rather
 * than a Minecraft screen (every control is {@link FlatUi}, nothing vanilla):
 *
 * <ul>
 *   <li><b>Structure</b> (left) — the poster as a list of cards: Header, one card
 *       per body block, Footer. Click to select; the selected block card carries
 *       move-up / move-down / delete; "+ Add block" opens a tile picker.</li>
 *   <li><b>Preview</b> (centre) — the live poster at the largest scale that fits.
 *       Clicking the poster selects whatever is under the cursor (header band,
 *       a body block, the footer) using the block bounds the layout reports.</li>
 *   <li><b>Inspector</b> (right) — the fields of the selection. Header: title bar,
 *       logo, timing, dates, line bullets, category. Text blocks: a type switch,
 *       a wrapping multi-line text box, and the symbol toolbar that inserts
 *       line bullets / express diamonds / wheelchair / arrows at the caret.
 *       Arrow blocks: a 3x3 direction pad. Footer: two fields.</li>
 * </ul>
 *
 * <p>Ctrl+S saves; Escape drops focus first, then cancels. Hit-testing works
 * off rectangles recorded while drawing, so the click targets are always what
 * was last on screen.</p>
 */
@Environment(EnvType.CLIENT)
public class PosterEditScreen extends Screen {
    private static final int PAD = 4;
    private static final int TOP_BAR = 22;
    /** Pane widths, narrowed on small logical screens (a Mac at auto GUI scale is ~430 x 270). */
    private int LEFT_WIDTH = 148;
    private int RIGHT_WIDTH = 214;
    private static final int CARD = 24;
    private static final int FIELD = 16;

    private static final String[] TEXT_TYPES = {"Headline", "Text", "Subhead"};
    private static final String[] DIRECTION_GLYPHS = {"↖", "↑", "↗", "←", "", "→", "↙", "↓", "↘"};
    /** Direction pad cell → ServicePoster arrow direction (0 = right, anticlockwise). */
    private static final int[] DIRECTION_OF_CELL = {3, 2, 1, 4, -1, 0, 5, 6, 7};

    private final Screen parent;
    private final ServicePoster.Builder draft;

    /** What the inspector shows: -2 header, -1 footer, otherwise a block index. */
    private static final int HEADER = -2;
    private static final int FOOTER = -1;
    private int selected = HEADER;

    // Header / footer boxes live for the whole screen; the block box is reloaded on selection.
    private final TextBox kindBox;
    private final TextBox logoBox;
    private final TextBox timingBox;
    private final TextBox date1Box;
    private final TextBox date2Box;
    private final TextBox categoryBox;
    private final TextBox footerLeftBox;
    private final TextBox footerRightBox;
    private final TextBox blockBox;
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    private TextBox focused;

    // Geometry.
    private int leftX;
    private int leftY;
    private int leftH;
    private int centreX;
    private int centreW;
    private int rightX;
    private int rightY;
    private int rightH;
    private int paneBottom;
    private int previewX;
    private int previewY;
    private float previewScale;
    private PosterLayout.Metrics lastMetrics;
    private int structureScroll;
    private int structureContent;
    private boolean addMenuOpen;

    /** Rectangles computed while drawing, hit-tested on click: {x, y, w, h, id, arg}. */
    private final List<int[]> hits = new ArrayList<>();
    private static final int HIT_CARD = 1;
    private static final int HIT_MOVE_UP = 2;
    private static final int HIT_MOVE_DOWN = 3;
    private static final int HIT_DELETE = 4;
    private static final int HIT_ADD = 5;
    private static final int HIT_ADD_TYPE = 6;
    private static final int HIT_SAVE = 7;
    private static final int HIT_CANCEL = 8;
    private static final int HIT_LINE_CHIP = 9;
    private static final int HIT_ADD_LINE = 10;
    private static final int HIT_TYPE = 11;
    private static final int HIT_DIRECTION = 12;
    private static final int HIT_TOKEN = 13;
    private static final int HIT_DELETE_BLOCK = 14;
    private static final int HIT_PREVIEW = 15;
    private static final int HIT_POPUP_ROW = 16;
    private static final int HIT_POPUP = 17;

    /** The line popup, when open: which token it inserts, or adds a header line. */
    private enum PopupMode { BULLET, DIAMOND, HEADER }

    private PopupMode popup;
    private int popupScroll;
    private List<PosterLayout.LineOption> popupLines = List.of();
    private String toast;
    private long toastUntil;

    public PosterEditScreen(ServicePoster.Builder draft, Screen parent) {
        super(Text.translatable(draft.id == 0
                ? "gui.station_announcer.posters.new_title"
                : "gui.station_announcer.posters.edit_title"));
        this.draft = draft;
        this.parent = parent;
        var font = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        kindBox = box(font, ServicePoster.MAX_FIELD, draft.kind, v -> draft.kind = v, "Planned Work");
        logoBox = box(font, ServicePoster.MAX_LOGO, draft.logo, v -> draft.logo = v, "BT");
        timingBox = box(font, ServicePoster.MAX_FIELD, draft.timing, v -> draft.timing = v, "All Times");
        date1Box = box(font, ServicePoster.MAX_FIELD, draft.date1, v -> draft.date1 = v, "Through Q1 2027");
        date2Box = box(font, ServicePoster.MAX_FIELD, draft.date2, v -> draft.date2 = v, "Sun to Sat, 4 AM to 1 AM");
        categoryBox = box(font, ServicePoster.MAX_FIELD, draft.category, v -> draft.category = v, "STATION IMPROVEMENTS");
        footerLeftBox = box(font, ServicePoster.MAX_FIELD, draft.footerLeft, v -> draft.footerLeft = v, "Subway");
        footerRightBox = box(font, ServicePoster.MAX_FIELD, draft.footerRight, v -> draft.footerRight = v, "Post: ...");
        blockBox = new TextBox(font, ServicePoster.MAX_BLOCK_TEXT, true);
        blockBox.placeholder = "Type here. Enter starts a new line.";
        blockBox.onChange(v -> {
            if (selected >= 0 && selected < draft.blocks.size()) {
                ServicePoster.Block block = draft.blocks.get(selected);
                draft.blocks.set(selected, new ServicePoster.Block(block.type(), v, block.arg()));
            }
        });
        if (!draft.blocks.isEmpty()) {
            select(0);
        }
    }

    private static TextBox box(net.minecraft.client.font.TextRenderer font, int max, String value,
                               java.util.function.Consumer<String> sink, String placeholder) {
        TextBox box = new TextBox(font, max, false);
        box.load(value);
        box.onChange(sink);
        box.placeholder = placeholder;
        return box;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        boolean compact = width < 560 || height < 320;
        LEFT_WIDTH = compact ? 104 : 148;
        RIGHT_WIDTH = compact ? 172 : 214;
        leftX = PAD;
        leftY = TOP_BAR + PAD;
        paneBottom = height - PAD;
        leftH = paneBottom - leftY;
        rightX = width - PAD - RIGHT_WIDTH;
        rightY = leftY;
        rightH = leftH;
        centreX = leftX + LEFT_WIDTH + PAD;
        centreW = Math.max(40, rightX - PAD - centreX);
        int centreH = leftH;
        previewScale = Math.max(0.4f, Math.min((centreH - 24) / (float) PosterLayout.HEIGHT,
                (centreW - 12) / (float) PosterLayout.WIDTH));
        previewX = centreX + (centreW - Math.round(PosterLayout.WIDTH * previewScale)) / 2;
        previewY = leftY + 6;
    }

    // --------------------------------------------------------------- selection

    private void select(int target) {
        if (target >= draft.blocks.size()) {
            target = draft.blocks.isEmpty() ? HEADER : draft.blocks.size() - 1;
        }
        selected = target;
        if (selected >= 0) {
            blockBox.load(draft.blocks.get(selected).text());
        }
        focus(null);
    }

    /** Dev rig only: preselect a card before the screen opens. */
    void selectForDev(int target) {
        select(target);
    }

    private void focus(TextBox box) {
        if (focused != null && focused != box) {
            focused.setFocused(false);
        }
        focused = box;
        if (box != null) {
            box.setFocused(true);
        }
    }

    private ServicePoster.Block selectedBlock() {
        return selected >= 0 && selected < draft.blocks.size() ? draft.blocks.get(selected) : null;
    }

    private void addBlock(ServicePoster.BlockType type) {
        if (draft.blocks.size() >= ServicePoster.MAX_BLOCKS) {
            toast("This poster already has the maximum of " + ServicePoster.MAX_BLOCKS + " blocks");
            return;
        }
        ServicePoster.Block block = type == ServicePoster.BlockType.ARROW
                ? ServicePoster.Block.arrow(0)
                : ServicePoster.Block.text(type, "");
        int at = selected >= 0 ? selected + 1 : draft.blocks.size();
        draft.blocks.add(at, block);
        addMenuOpen = false;
        select(at);
        if (type.hasText()) {
            focus(blockBox);
        }
    }

    private void moveBlock(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= draft.blocks.size() || target < 0 || target >= draft.blocks.size()) {
            return;
        }
        ServicePoster.Block moved = draft.blocks.remove(index);
        draft.blocks.add(target, moved);
        select(target);
    }

    private void deleteBlock(int index) {
        if (index < 0 || index >= draft.blocks.size()) {
            return;
        }
        draft.blocks.remove(index);
        select(draft.blocks.isEmpty() ? HEADER : Math.min(index, draft.blocks.size() - 1));
    }

    private void toast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2_500;
    }

    private void openPopup(PopupMode mode) {
        popupLines = PosterLayout.lines();
        popup = mode;
        popupScroll = 0;
    }

    private void choose(PosterLayout.LineOption line) {
        PopupMode mode = popup;
        popup = null;
        if (mode == PopupMode.HEADER) {
            if (draft.headerLines.size() >= ServicePoster.MAX_HEADER_LINES) {
                toast("At most " + ServicePoster.MAX_HEADER_LINES + " lines fit in the header");
            } else if (!draft.headerLines.contains(line.name())) {
                draft.headerLines.add(line.name());
            }
        } else {
            insertToken((mode == PopupMode.DIAMOND ? "{d:" : "{b:") + line.name() + "}");
        }
    }

    private void insertToken(String token) {
        ServicePoster.Block block = selectedBlock();
        if (block == null || !block.type().hasText()) {
            toast("Select a text block first");
            return;
        }
        blockBox.insertToken(token);
        focus(blockBox);
    }

    private void save() {
        ServicePoster poster = draft.build();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false);
        buf.writeLong(poster.id());
        poster.write(buf);
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_POSTER_C2S, buf);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ------------------------------------------------------------------- input

    private void hit(int x, int y, int w, int h, int id, int arg) {
        hits.add(new int[]{x, y, w, h, id, arg});
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Popup first: it owns the screen while open.
        if (popup != null) {
            for (int[] r : hits) {
                if (r[4] == HIT_POPUP_ROW && FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    if (r[5] >= 0 && r[5] < popupLines.size()) {
                        choose(popupLines.get(r[5]));
                    }
                    return true;
                }
            }
            for (int[] r : hits) {
                if (r[4] == HIT_POPUP && FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    return true;
                }
            }
            popup = null;
            return true;
        }
        // Text boxes.
        for (TextBox box : visibleBoxes) {
            if (box.contains(mx, my)) {
                focus(box);
                box.mouseClicked(mx, my, button);
                return true;
            }
        }
        // Everything else, in drawing order (icons are recorded before their card).
        for (int[] r : hits) {
            if (!FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                continue;
            }
            switch (r[4]) {
                case HIT_CARD -> {
                    addMenuOpen = false;
                    select(r[5]);
                }
                case HIT_MOVE_UP -> moveBlock(r[5], -1);
                case HIT_MOVE_DOWN -> moveBlock(r[5], 1);
                case HIT_DELETE, HIT_DELETE_BLOCK -> deleteBlock(r[5]);
                case HIT_ADD -> addMenuOpen = !addMenuOpen;
                case HIT_ADD_TYPE -> addBlock(ServicePoster.BlockType.byOrdinal(r[5]));
                case HIT_SAVE -> {
                    save();
                    close();
                }
                case HIT_CANCEL -> close();
                case HIT_LINE_CHIP -> {
                    if (r[5] < draft.headerLines.size()) {
                        draft.headerLines.remove(r[5]);
                    }
                }
                case HIT_ADD_LINE -> openPopup(PopupMode.HEADER);
                case HIT_TYPE -> {
                    ServicePoster.Block block = selectedBlock();
                    if (block != null) {
                        ServicePoster.BlockType type = ServicePoster.BlockType.byOrdinal(r[5]);
                        draft.blocks.set(selected, new ServicePoster.Block(type, block.text(), block.arg()));
                    }
                }
                case HIT_DIRECTION -> {
                    if (selectedBlock() != null) {
                        draft.blocks.set(selected, ServicePoster.Block.arrow(r[5]));
                    }
                }
                case HIT_TOKEN -> {
                    switch (r[5]) {
                        case 0 -> openPopup(PopupMode.BULLET);
                        case 1 -> openPopup(PopupMode.DIAMOND);
                        case 2 -> insertToken("{wc}");
                        case 3 -> insertToken("{<}");
                        case 4 -> insertToken("{^}");
                        case 5 -> insertToken("{v}");
                        case 6 -> insertToken("{>}");
                        default -> {
                        }
                    }
                }
                case HIT_PREVIEW -> selectFromPreview(my);
                default -> {
                }
            }
            return true;
        }
        focus(null);
        return super.mouseClicked(mx, my, button);
    }

    private void selectFromPreview(double my) {
        if (lastMetrics == null) {
            return;
        }
        float cy = (float) ((my - previewY) / previewScale);
        if (cy < PosterLayout.headerBottom()) {
            select(HEADER);
            return;
        }
        if (cy >= PosterLayout.footerTop(lastMetrics.height())) {
            select(FOOTER);
            return;
        }
        List<float[]> bounds = lastMetrics.blockBounds();
        for (int i = 0; i < bounds.size(); i++) {
            if (cy >= bounds.get(i)[0] && cy < bounds.get(i)[1]) {
                select(i);
                return;
            }
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (focused != null) {
            focused.mouseDragged(mx, my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (focused != null) {
            focused.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (popup != null) {
            int max = Math.max(0, popupLines.size() - popupVisibleRows());
            popupScroll = Math.max(0, Math.min(max, popupScroll - (int) Math.signum(verticalAmount)));
            return true;
        }
        for (TextBox box : visibleBoxes) {
            if (box.mouseScrolled(mx, my, verticalAmount)) {
                return true;
            }
        }
        if (FlatUi.inside(mx, my, leftX, leftY, LEFT_WIDTH, leftH)) {
            int max = Math.max(0, structureContent - (leftH - 16));
            structureScroll = Math.max(0, Math.min(max, structureScroll - (int) (verticalAmount * CARD)));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // escape
            if (popup != null) {
                popup = null;
                return true;
            }
            if (focused != null) {
                focus(null);
                return true;
            }
            close();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == 83) { // ctrl+S
            save();
            close();
            return true;
        }
        if (keyCode == 258 && !visibleBoxes.isEmpty()) { // tab cycles the inspector's boxes
            int index = focused == null ? -1 : visibleBoxes.indexOf(focused);
            int next = Screen.hasShiftDown()
                    ? (index <= 0 ? visibleBoxes.size() - 1 : index - 1)
                    : (index + 1) % visibleBoxes.size();
            focus(visibleBoxes.get(next));
            return true;
        }
        if (focused != null && focused.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focused != null && focused.charTyped(chr)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    // ----------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        visibleBoxes.clear();
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        int hoverX = popup == null ? mx : -1;
        int hoverY = popup == null ? my : -1;

        drawTopBar(c, hoverX, hoverY);
        drawStructure(c, hoverX, hoverY);
        drawPreview(c);
        drawInspector(c, hoverX, hoverY);

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = textRenderer.getWidth(toast) + 12;
            int tx = centreX + (centreW - tw) / 2;
            FlatUi.rect(c, tx, paneBottom - 30, tw, 14, 0xEE3A2224);
            c.drawText(textRenderer, toast, tx + 6, paneBottom - 27, FlatUi.DANGER, false);
        }
        if (popup != null) {
            drawPopup(c, mx, my);
        }
    }

    private void drawTopBar(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, TOP_BAR, FlatUi.PANE);
        FlatUi.rect(c, 0, TOP_BAR - 1, width, 1, FlatUi.BORDER);
        c.drawText(textRenderer, title, PAD + 4, 7, FlatUi.TEXT, false);
        ClientDisruptions.Entry disruption = ClientDisruptions.byId(draft.disruptionId);
        if (disruption != null) {
            int x = PAD + 10 + textRenderer.getWidth(title);
            c.drawText(textRenderer, textRenderer.trimToWidth("· " + disruption.message(), Math.max(0, width - x - 130)),
                    x, 7, FlatUi.TEXT_DIM, false);
        }
        int saveW = 54;
        int cancelW = 50;
        int sx = width - PAD - saveW;
        int cx = sx - 4 - cancelW;
        FlatUi.button(c, textRenderer, "Cancel", cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, HIT_CANCEL, 0);
        FlatUi.button(c, textRenderer, "Save", sx, 2, saveW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY);
        hit(sx, 2, saveW, FlatUi.BUTTON_HEIGHT, HIT_SAVE, 0);
    }

    private void drawStructure(DrawContext c, int mx, int my) {
        FlatUi.pane(c, leftX, leftY, LEFT_WIDTH, leftH);
        FlatUi.heading(c, textRenderer, "Structure", leftX + 6, leftY + 5);
        int top = leftY + 16;
        int viewH = leftH - 16;
        c.enableScissor(leftX + 1, top, leftX + LEFT_WIDTH - 1, leftY + leftH - 1);
        int y = top - structureScroll;
        y = card(c, mx, my, y, HEADER, "Header", draft.timing.isEmpty() ? draft.kind : draft.timing, 0);
        for (int i = 0; i < draft.blocks.size(); i++) {
            ServicePoster.Block block = draft.blocks.get(i);
            String tag = switch (block.type()) {
                case HEADLINE -> "H";
                case TEXT -> "T";
                case SUBHEAD -> "S";
                case ARROW -> "→";
                case RULE -> "—";
                default -> "·";
            };
            String snippet = switch (block.type()) {
                case ARROW -> "Arrow " + Text.translatable("gui.station_announcer.posters.dir." + block.arg()).getString();
                case RULE -> "Rule";
                case SPACER -> "Space";
                default -> block.text().isBlank() ? "(empty)" : PosterListScreen.stripTokens(block.text()).replace('\n', ' ');
            };
            int tagColor = switch (block.type()) {
                case HEADLINE -> 0xFF5B4FCF;
                case TEXT -> 0xFF2F7A5A;
                case SUBHEAD -> 0xFF2F6E9A;
                case ARROW -> 0xFF9A5A2F;
                default -> 0xFF4A4A55;
            };
            y = card(c, mx, my, y, i, tag, snippet, tagColor);
        }
        y = card(c, mx, my, y, FOOTER, "Footer", draft.footerLeft.isEmpty() ? "(empty)" : draft.footerLeft, 0);
        y += 4;
        // Add block.
        int bx = leftX + 6;
        int bw = LEFT_WIDTH - 12;
        FlatUi.button(c, textRenderer, addMenuOpen ? "Close" : "+ Add block", bx, y, bw, FlatUi.BUTTON_HEIGHT, mx, my,
                addMenuOpen ? ButtonStyle.FLAT : ButtonStyle.PRIMARY);
        hit(bx, y, bw, FlatUi.BUTTON_HEIGHT, HIT_ADD, 0);
        y += FlatUi.BUTTON_HEIGHT + 4;
        if (addMenuOpen) {
            ServicePoster.BlockType[] types = ServicePoster.BlockType.values();
            int tileW = (bw - 4) / 2;
            for (int i = 0; i < types.length; i++) {
                int tx = bx + (i % 2) * (tileW + 4);
                int ty = y + (i / 2) * (FlatUi.BUTTON_HEIGHT + 3);
                String label = Text.translatable("gui.station_announcer.posters.block." + types[i].name().toLowerCase()).getString();
                FlatUi.button(c, textRenderer, label, tx, ty, tileW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
                hit(tx, ty, tileW, FlatUi.BUTTON_HEIGHT, HIT_ADD_TYPE, types[i].ordinal());
            }
            y += ((types.length + 1) / 2) * (FlatUi.BUTTON_HEIGHT + 3);
        }
        c.disableScissor();
        structureContent = y + structureScroll - top + 4;
        FlatUi.scrollThumb(c, leftX + LEFT_WIDTH, top, viewH, structureContent, structureScroll);
    }

    /** One structure card; returns the y below it. */
    private int card(DrawContext c, int mx, int my, int y, int id, String tag, String snippet, int tagColor) {
        int x = leftX + 1;
        int w = LEFT_WIDTH - 2;
        int clipTop = leftY + 16;
        boolean isSelected = id == selected;
        boolean hovered = FlatUi.inside(mx, my, x, y, w, CARD) && my >= clipTop && my < leftY + leftH;
        if (isSelected) {
            FlatUi.rect(c, x, y, w, CARD, FlatUi.SELECTED);
            FlatUi.rect(c, x, y, 2, CARD, FlatUi.ACCENT);
        } else if (hovered) {
            FlatUi.rect(c, x, y, w, CARD, FlatUi.HOVER);
        }
        FlatUi.rect(c, x, y + CARD - 1, w, 1, FlatUi.BORDER);
        if (id < 0) {
            c.drawText(textRenderer, tag, x + 8, y + 4, FlatUi.TEXT, false);
            c.drawText(textRenderer, textRenderer.trimToWidth(snippet, w - 16), x + 8, y + 13, FlatUi.TEXT_FAINT, false);
        } else {
            int tagW = Math.max(12, textRenderer.getWidth(tag) + 6);
            FlatUi.rect(c, x + 6, y + 6, tagW, 12, tagColor);
            c.drawText(textRenderer, tag, x + 6 + (tagW - textRenderer.getWidth(tag)) / 2, y + 8, FlatUi.TEXT, false);
            int textX = x + 6 + tagW + 5;
            int textW = w - (textX - x) - (isSelected ? 46 : 6);
            c.drawText(textRenderer, textRenderer.trimToWidth(snippet, Math.max(8, textW)), textX, y + 8, FlatUi.TEXT, false);
            if (isSelected) {
                int bx = x + w - 44;
                FlatUi.iconButton(c, textRenderer, "↑", bx, y + 5, 13, mx, my, FlatUi.TEXT_DIM);
                hit(bx, Math.max(y + 5, clipTop), 13, 13, HIT_MOVE_UP, id);
                FlatUi.iconButton(c, textRenderer, "↓", bx + 14, y + 5, 13, mx, my, FlatUi.TEXT_DIM);
                hit(bx + 14, Math.max(y + 5, clipTop), 13, 13, HIT_MOVE_DOWN, id);
                FlatUi.iconButton(c, textRenderer, "×", bx + 28, y + 5, 13, mx, my, FlatUi.DANGER);
                hit(bx + 28, Math.max(y + 5, clipTop), 13, 13, HIT_DELETE, id);
            }
        }
        // The card's own hit box comes AFTER its icons so the icons win.
        int visibleTop = Math.max(y, clipTop);
        int visibleBottom = Math.min(y + CARD, leftY + leftH - 1);
        if (visibleBottom > visibleTop) {
            hit(x, visibleTop, w, visibleBottom - visibleTop, HIT_CARD, id);
        }
        return y + CARD;
    }

    private void drawPreview(DrawContext c) {
        FlatUi.rect(c, centreX, leftY, centreW, leftH, 0xFF0F0F12);
        int pw = Math.round(PosterLayout.WIDTH * previewScale);
        int ph = Math.round(PosterLayout.HEIGHT * previewScale);
        FlatUi.rect(c, previewX - 2, previewY - 2, pw + 4, ph + 4, 0xFF000000);
        lastMetrics = PosterLayout.paint(new PosterLayout.GuiSurface(c, previewX, previewY, previewScale), draft.build());
        // Selection highlight over the poster.
        float top;
        float bottom;
        if (selected == HEADER) {
            top = 0;
            bottom = PosterLayout.headerBottom();
        } else if (selected == FOOTER) {
            top = PosterLayout.footerTop(lastMetrics.height());
            bottom = lastMetrics.height();
        } else if (selected >= 0 && selected < lastMetrics.blockBounds().size()) {
            float[] b = lastMetrics.blockBounds().get(selected);
            top = b[0];
            bottom = b[1];
        } else {
            top = 0;
            bottom = 0;
        }
        if (bottom > top) {
            int sy = previewY + Math.round(top * previewScale);
            int sh = Math.max(2, Math.round((bottom - top) * previewScale));
            FlatUi.rect(c, previewX, sy, pw, sh, 0x223D8BFF);
            FlatUi.outline(c, previewX - 1, sy - 1, pw + 2, sh + 2, FlatUi.ACCENT);
        }
        hit(previewX, previewY, pw, ph, HIT_PREVIEW, 0);
        String hint = lastMetrics.bodyScale() < 1.0f
                ? "Body text at " + Math.round(lastMetrics.bodyScale() * 100) + "% to fit"
                : "Click to select";
        if (textRenderer.getWidth(hint) <= centreW - 8) {
            c.drawText(textRenderer, hint, centreX + (centreW - textRenderer.getWidth(hint)) / 2, paneBottom - 12,
                    FlatUi.TEXT_FAINT, false);
        }
    }

    private void drawInspector(DrawContext c, int mx, int my) {
        FlatUi.pane(c, rightX, rightY, RIGHT_WIDTH, rightH);
        int x = rightX + 8;
        int w = RIGHT_WIDTH - 16;
        int y = rightY + 5;
        ServicePoster.Block block = selectedBlock();
        if (selected == HEADER) {
            FlatUi.heading(c, textRenderer, "Header", x, y);
            y += 14;
            int logoW = 44;
            c.drawText(textRenderer, "Title bar", x, y, FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, "Logo", x + w - logoW, y, FlatUi.TEXT_DIM, false);
            y += 10;
            kindBox.setBounds(x, y, w - logoW - 6, FIELD);
            kindBox.render(c, mx, my);
            visibleBoxes.add(kindBox);
            logoBox.setBounds(x + w - logoW, y, logoW, FIELD);
            logoBox.render(c, mx, my);
            visibleBoxes.add(logoBox);
            y += FIELD + 5;
            y = field(c, mx, my, "When (large)", timingBox, x, y, w);
            y = field(c, mx, my, "Date line 1", date1Box, x, y, w);
            y = field(c, mx, my, "Date line 2", date2Box, x, y, w);
            // Lines.
            c.drawText(textRenderer, "Lines", x, y, FlatUi.TEXT_DIM, false);
            y += 10;
            int addW = 50;
            int cx = x;
            for (int i = 0; i < draft.headerLines.size(); i++) {
                var bullet = PosterLayout.lineBullet(draft.headerLines.get(i));
                String label = bullet.label() + "  ×";
                int cw = textRenderer.getWidth(label) + 8;
                if (cx + cw > x + w - addW - 4 && cx > x) {
                    cx = x;
                    y += 15;
                }
                boolean hovered = FlatUi.inside(mx, my, cx, y, cw, 12);
                FlatUi.chip(c, textRenderer, label, cx, y, hovered ? FlatUi.DANGER : bullet.color(), hovered);
                hit(cx, y, cw, 12, HIT_LINE_CHIP, i);
                cx += cw + 3;
            }
            if (draft.headerLines.isEmpty()) {
                c.drawText(textRenderer, "none yet", x, y + 2, FlatUi.TEXT_FAINT, false);
            }
            FlatUi.button(c, textRenderer, "+ Line", x + w - addW, y - 2, addW, FIELD, mx, my, ButtonStyle.FLAT);
            hit(x + w - addW, y - 2, addW, FIELD, HIT_ADD_LINE, 0);
            y += 21;
            y = field(c, mx, my, "Category", categoryBox, x, y, w);
            c.drawText(textRenderer, textRenderer.trimToWidth("Empty category = no rule.", w), x, y, FlatUi.TEXT_FAINT, false);
        } else if (selected == FOOTER) {
            FlatUi.heading(c, textRenderer, "Footer", x, y);
            y += 14;
            y = field(c, mx, my, "Left", footerLeftBox, x, y, w);
            y = field(c, mx, my, "Right", footerRightBox, x, y, w);
            c.drawText(textRenderer, textRenderer.trimToWidth("Overlong text is trimmed with …", w), x, y, FlatUi.TEXT_FAINT, false);
        } else if (block != null) {
            int deleteY = rightY + rightH - 6 - FlatUi.BUTTON_HEIGHT;
            if (block.type().hasText()) {
                FlatUi.heading(c, textRenderer, "Text block", x, y);
                y += 14;
                FlatUi.segmented(c, textRenderer, TEXT_TYPES, block.type().ordinal(), x, y, w, FIELD, mx, my);
                for (int i = 0; i < TEXT_TYPES.length; i++) {
                    hit(x + w * i / 3, y, w / 3, FIELD, HIT_TYPE, i);
                }
                y += FIELD + 6;
                int toolbarH = 12 + FIELD + 4 + FIELD + 8;
                int boxH = Math.max(36, deleteY - 6 - toolbarH - y);
                blockBox.setBounds(x, y, w, boxH);
                blockBox.render(c, mx, my);
                visibleBoxes.add(blockBox);
                y += boxH + 6;
                FlatUi.heading(c, textRenderer, "Insert at cursor", x, y);
                y += 12;
                int half = (w - 4) / 2;
                FlatUi.button(c, textRenderer, "● Line bullet", x, y, half, FIELD, mx, my, ButtonStyle.FLAT);
                hit(x, y, half, FIELD, HIT_TOKEN, 0);
                FlatUi.button(c, textRenderer, "◆ Express", x + half + 4, y, w - half - 4, FIELD, mx, my, ButtonStyle.FLAT);
                hit(x + half + 4, y, w - half - 4, FIELD, HIT_TOKEN, 1);
                y += FIELD + 4;
                String[] glyphs = {"♿", "←", "↑", "↓", "→"};
                int gw = (w - 4 * 4) / 5;
                for (int i = 0; i < glyphs.length; i++) {
                    int gx = x + i * (gw + 4);
                    FlatUi.button(c, textRenderer, glyphs[i], gx, y, gw, FIELD, mx, my, ButtonStyle.FLAT);
                    hit(gx, y, gw, FIELD, HIT_TOKEN, 2 + i);
                }
            } else if (block.type() == ServicePoster.BlockType.ARROW) {
                FlatUi.heading(c, textRenderer, "Arrow", x, y);
                y += 14;
                c.drawText(textRenderer, "Direction", x, y, FlatUi.TEXT_DIM, false);
                y += 11;
                int cell = 26;
                int padX = x + (w - cell * 3 - 8) / 2;
                for (int i = 0; i < 9; i++) {
                    int dir = DIRECTION_OF_CELL[i];
                    if (dir < 0) {
                        continue;
                    }
                    int gx = padX + (i % 3) * (cell + 4);
                    int gy = y + (i / 3) * (cell + 4);
                    boolean on = block.arg() == dir;
                    boolean hovered = FlatUi.inside(mx, my, gx, gy, cell, cell);
                    FlatUi.rect(c, gx, gy, cell, cell, on ? FlatUi.ACCENT_DIM : hovered ? 0xFF34343C : FlatUi.PANE_RAISED);
                    FlatUi.outline(c, gx, gy, cell, cell, on ? FlatUi.ACCENT : FlatUi.BORDER_STRONG);
                    String g = DIRECTION_GLYPHS[i];
                    c.drawText(textRenderer, g, gx + (cell - textRenderer.getWidth(g)) / 2, gy + (cell - 8) / 2,
                            on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
                    hit(gx, gy, cell, cell, HIT_DIRECTION, dir);
                }
                y += 3 * (cell + 4) + 4;
                c.drawText(textRenderer, "A big block arrow, centred.", x, y, FlatUi.TEXT_FAINT, false);
            } else {
                FlatUi.heading(c, textRenderer, block.type() == ServicePoster.BlockType.RULE ? "Rule" : "Space", x, y);
                y += 14;
                c.drawText(textRenderer, block.type() == ServicePoster.BlockType.RULE
                        ? "A thin horizontal line." : "A small vertical gap.", x, y, FlatUi.TEXT_FAINT, false);
            }
            FlatUi.button(c, textRenderer, "Delete block", x, deleteY, w, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.DANGER);
            hit(x, deleteY, w, FlatUi.BUTTON_HEIGHT, HIT_DELETE_BLOCK, selected);
        } else {
            FlatUi.heading(c, textRenderer, "Nothing selected", x, y);
        }
    }

    /** Label + single-line box; returns the y below. */
    private int field(DrawContext c, int mx, int my, String label, TextBox box, int x, int y, int w) {
        c.drawText(textRenderer, label, x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        box.setBounds(x, y, w, FIELD);
        box.render(c, mx, my);
        visibleBoxes.add(box);
        return y + FIELD + 5;
    }

    private int popupVisibleRows() {
        return Math.max(3, Math.min(12, (height - 70) / 16));
    }

    private void drawPopup(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        int pw = 200;
        int rows = popupVisibleRows();
        int ph = 24 + rows * 16 + 8;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        FlatUi.rect(c, px, py, pw, ph, FlatUi.PANE);
        FlatUi.outline(c, px, py, pw, ph, FlatUi.BORDER_STRONG);
        hit(px, py, pw, ph, HIT_POPUP, 0);
        String heading = popup == PopupMode.HEADER ? "Add a line to the header"
                : popup == PopupMode.DIAMOND ? "Insert an express diamond" : "Insert a line bullet";
        c.drawText(textRenderer, heading, px + 8, py + 8, FlatUi.TEXT, false);
        int listTop = py + 24;
        if (popupLines.isEmpty()) {
            c.drawText(textRenderer, Text.translatable("gui.station_announcer.posters.pick_line_none"),
                    px + 8, listTop + 4, FlatUi.TEXT_FAINT, false);
            return;
        }
        for (int i = 0; i < rows; i++) {
            int index = i + popupScroll;
            if (index >= popupLines.size()) {
                break;
            }
            PosterLayout.LineOption option = popupLines.get(index);
            int ry = listTop + i * 16;
            boolean hovered = FlatUi.inside(mx, my, px + 1, ry, pw - 2, 16);
            if (hovered) {
                FlatUi.rect(c, px + 1, ry, pw - 2, 16, FlatUi.HOVER);
            }
            int cw = FlatUi.chip(c, textRenderer, option.bullet().label(), px + 8, ry + 2, option.bullet().color(), false);
            c.drawText(textRenderer, textRenderer.trimToWidth(option.name(), pw - cw - 24),
                    px + 8 + cw + 6, ry + 4, FlatUi.TEXT, false);
            hit(px + 1, ry, pw - 2, 16, HIT_POPUP_ROW, index);
        }
        FlatUi.scrollThumb(c, px + pw, listTop, rows * 16, popupLines.size() * 16, popupScroll * 16);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
