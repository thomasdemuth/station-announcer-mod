package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
import com.stationannouncer.mtraddon.disruption.ServicePoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The service change poster editor: a scrolling form on the left (header fields,
 * the affected-line bullets, the body as an ordered list of blocks, footer) and
 * a live preview on the right painted by {@link PosterLayout} — exactly what the
 * hung frame will show.
 *
 * <p>Body blocks are added from the "Add block" row (headline / text / subhead /
 * arrow / rule / space), reordered with the arrows and removed with x. The fixed
 * "Insert" toolbar above the form drops a symbol token into whichever text field
 * was last focused: a line bullet or express diamond (from a popup of every line
 * in the world), the wheelchair symbol, or a small arrow.</p>
 *
 * <p>The form is a column of vanilla widgets moved under a scissor; a widget
 * scrolled fully out of view is made invisible, which is what stops it taking
 * clicks (ClickableWidget only reacts while visible).</p>
 */
@Environment(EnvType.CLIENT)
public class PosterEditScreen extends Screen {
    private static final int FORM_WIDTH = 210;
    private static final int LABEL_WIDTH = 56;
    private static final int ROW = 18;
    private static final int ROW_GAP = 4;
    private static final int CAPTION = 13;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TOOLBAR_HEIGHT = 16;

    private final Screen parent;
    private final ServicePoster.Builder draft;

    private int panelLeft;
    private int titleY;
    private int formLeft;
    private int formTop;
    private int formBottom;
    private int toolbarY;
    private int previewLeft;
    private int previewTop;
    private float previewScale;
    private int scroll;
    private int formHeight;

    /** Form widgets, their unscrolled y, and the text fields among them. */
    private final List<ClickableWidget> formWidgets = new ArrayList<>();
    private final List<Integer> formBaseY = new ArrayList<>();
    private final List<TextFieldWidget> textFields = new ArrayList<>();
    private TextFieldWidget lastFocused;

    /** Static form decorations (captions and labels) at unscrolled y. */
    private record Label(int x, int y, String text, int color) {
    }

    private final List<Label> labels = new ArrayList<>();
    /** The header-line chips row: chip x extents for click-to-remove. */
    private int linesRowY;
    private final List<int[]> lineChipBounds = new ArrayList<>();

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
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        previewScale = Math.max(0.8f, Math.min(1.5f, (height - 60) / (float) PosterLayout.MAX_HEIGHT));
        int previewWidth = Math.round(PosterLayout.WIDTH * previewScale);
        int total = FORM_WIDTH + 10 + previewWidth;
        panelLeft = Math.max(4, (width - total) / 2);
        titleY = 8;
        formLeft = panelLeft;
        toolbarY = 22;
        formTop = toolbarY + TOOLBAR_HEIGHT + 4;
        formBottom = height - 30;
        previewLeft = panelLeft + FORM_WIDTH + 10;
        previewTop = Math.max(formTop, (height - Math.round(PosterLayout.MAX_HEIGHT * previewScale)) / 2);

        // Fixed: the insert toolbar.
        int x = formLeft;
        String insertLabel = Text.translatable("gui.station_announcer.posters.insert").getString();
        x += textRenderer.getWidth(insertLabel) + 4;
        x = toolbarButton(x, Text.translatable("gui.station_announcer.posters.add_line").getString(), 36,
                b -> openPopup(PopupMode.BULLET));
        x = toolbarButton(x, "◆", 16, b -> openPopup(PopupMode.DIAMOND));
        x = toolbarButton(x, "♿", 16, b -> insertToken("{wc}"));
        x = toolbarButton(x, "←", 16, b -> insertToken("{<}"));
        x = toolbarButton(x, "↑", 16, b -> insertToken("{^}"));
        x = toolbarButton(x, "↓", 16, b -> insertToken("{v}"));
        toolbarButton(x, "→", 16, b -> insertToken("{>}"));

        // Fixed: footer buttons.
        int half = (FORM_WIDTH - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> {
                    save();
                    close();
                })
                .dimensions(formLeft, height - 26, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(formLeft + half + GAP, height - 26, FORM_WIDTH - half - GAP, WIDGET_HEIGHT).build());

        rebuildForm();
    }

    private int toolbarButton(int x, String label, int buttonWidth, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), action)
                .dimensions(x, toolbarY, buttonWidth, TOOLBAR_HEIGHT).build());
        return x + buttonWidth + 2;
    }

    /** (Re)creates every form widget from the draft, keeping the scroll position. */
    private void rebuildForm() {
        for (ClickableWidget widget : formWidgets) {
            remove(widget);
        }
        formWidgets.clear();
        formBaseY.clear();
        textFields.clear();
        labels.clear();
        lineChipBounds.clear();
        lastFocused = null;

        int y = 0;
        y = caption(y, "gui.station_announcer.posters.section_header");
        y = fieldRow(y, "gui.station_announcer.posters.kind", draft.kind, ServicePoster.MAX_FIELD, v -> draft.kind = v,
                "gui.station_announcer.posters.logo", draft.logo, ServicePoster.MAX_LOGO, v -> draft.logo = v);
        y = fieldRow(y, "gui.station_announcer.posters.timing", draft.timing, ServicePoster.MAX_FIELD, v -> draft.timing = v);
        y = fieldRow(y, "gui.station_announcer.posters.date1", draft.date1, ServicePoster.MAX_FIELD, v -> draft.date1 = v);
        y = fieldRow(y, "gui.station_announcer.posters.date2", draft.date2, ServicePoster.MAX_FIELD, v -> draft.date2 = v);
        // Header lines: chips (drawn in render) + an add button.
        labels.add(new Label(-1, y + 5, Text.translatable("gui.station_announcer.posters.header_lines").getString(), AddonUi.TEXT_DIM));
        linesRowY = y;
        addForm(ButtonWidget.builder(Text.translatable("gui.station_announcer.posters.add_line"),
                        b -> openPopup(PopupMode.HEADER))
                .dimensions(formLeft + FORM_WIDTH - 44, 0, 44, ROW).build(), y);
        y += ROW + ROW_GAP;
        y = fieldRow(y, "gui.station_announcer.posters.category", draft.category, ServicePoster.MAX_FIELD, v -> draft.category = v);

        y = caption(y, "gui.station_announcer.posters.section_body");
        for (int i = 0; i < draft.blocks.size(); i++) {
            y = blockRow(y, i);
        }
        // Add-block row.
        labels.add(new Label(-1, y + 5, Text.translatable("gui.station_announcer.posters.add_block").getString(), AddonUi.TEXT_DIM));
        int bx = formLeft + LABEL_WIDTH;
        int addWidth = (FORM_WIDTH - LABEL_WIDTH - 5 * 2) / 6;
        for (ServicePoster.BlockType type : ServicePoster.BlockType.values()) {
            ServicePoster.BlockType chosen = type;
            addForm(ButtonWidget.builder(Text.translatable("gui.station_announcer.posters.block." + type.name().toLowerCase()),
                            b -> addBlock(chosen))
                    .dimensions(bx, 0, addWidth, ROW).build(), y);
            bx += addWidth + 2;
        }
        y += ROW + ROW_GAP;

        y = caption(y, "gui.station_announcer.posters.section_footer");
        y = fieldRow(y, "gui.station_announcer.posters.footer_left", draft.footerLeft, ServicePoster.MAX_FIELD, v -> draft.footerLeft = v);
        y = fieldRow(y, "gui.station_announcer.posters.footer_right", draft.footerRight, ServicePoster.MAX_FIELD, v -> draft.footerRight = v);
        formHeight = y;
        layoutForm();
    }

    private int caption(int y, String key) {
        labels.add(new Label(-1, y + 2, Text.translatable(key).getString(), AddonUi.TEXT));
        return y + CAPTION;
    }

    private int fieldRow(int y, String key, String value, int max, Consumer<String> sink) {
        labels.add(new Label(-1, y + 5, Text.translatable(key).getString(), AddonUi.TEXT_DIM));
        addField(formLeft + LABEL_WIDTH, y, FORM_WIDTH - LABEL_WIDTH, value, max, sink);
        return y + ROW + ROW_GAP;
    }

    /** Two fields on one row: a wide one and a narrow one (title bar + logo). */
    private int fieldRow(int y, String key, String value, int max, Consumer<String> sink,
                         String key2, String value2, int max2, Consumer<String> sink2) {
        labels.add(new Label(-1, y + 5, Text.translatable(key).getString(), AddonUi.TEXT_DIM));
        int narrow = 34;
        int label2 = textRenderer.getWidth(Text.translatable(key2).getString()) + 4;
        int wide = FORM_WIDTH - LABEL_WIDTH - narrow - label2 - 4;
        addField(formLeft + LABEL_WIDTH, y, wide, value, max, sink);
        labels.add(new Label(formLeft + LABEL_WIDTH + wide + 4, y + 5, Text.translatable(key2).getString(), AddonUi.TEXT_DIM));
        addField(formLeft + FORM_WIDTH - narrow, y, narrow, value2, max2, sink2);
        return y + ROW + ROW_GAP;
    }

    private void addField(int x, int y, int fieldWidth, String value, int max, Consumer<String> sink) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, 0, fieldWidth, ROW, Text.empty());
        field.setMaxLength(max);
        field.setText(value == null ? "" : value);
        field.setChangedListener(sink);
        textFields.add(field);
        addForm(field, y);
    }

    private int blockRow(int y, int index) {
        ServicePoster.Block block = draft.blocks.get(index);
        String typeName = Text.translatable("gui.station_announcer.posters.block." + block.type().name().toLowerCase()).getString();
        labels.add(new Label(-1, y + 5, typeName, AddonUi.TEXT_DIM));
        int controls = 14 * 3 + 2 * 2;
        int fieldX = formLeft + LABEL_WIDTH - 12;
        int fieldWidth = FORM_WIDTH - (LABEL_WIDTH - 12) - controls - 4;
        if (block.type().hasText()) {
            addField(fieldX, y, fieldWidth, block.text(), ServicePoster.MAX_BLOCK_TEXT,
                    v -> draft.blocks.set(index, new ServicePoster.Block(block.type(), v, block.arg())));
        } else if (block.type() == ServicePoster.BlockType.ARROW) {
            addForm(ButtonWidget.builder(arrowLabel(block.arg()), b -> {
                        ServicePoster.Block current = draft.blocks.get(index);
                        int next = (current.arg() + 1) % 8;
                        draft.blocks.set(index, ServicePoster.Block.arrow(next));
                        b.setMessage(arrowLabel(next));
                    })
                    .dimensions(fieldX, 0, fieldWidth, ROW).build(), y);
        }
        int cx = formLeft + FORM_WIDTH - controls;
        addForm(ButtonWidget.builder(Text.literal("↑"), b -> moveBlock(index, -1))
                .dimensions(cx, 0, 14, ROW).build(), y);
        addForm(ButtonWidget.builder(Text.literal("↓"), b -> moveBlock(index, 1))
                .dimensions(cx + 16, 0, 14, ROW).build(), y);
        addForm(ButtonWidget.builder(Text.literal("x"), b -> {
                    draft.blocks.remove(index);
                    rebuildForm();
                })
                .dimensions(cx + 32, 0, 14, ROW).build(), y);
        return y + ROW + ROW_GAP;
    }

    private static Text arrowLabel(int dir) {
        return Text.translatable("gui.station_announcer.posters.arrow_dir",
                Text.translatable("gui.station_announcer.posters.dir." + dir).getString());
    }

    private void addForm(ClickableWidget widget, int baseY) {
        formWidgets.add(widget);
        formBaseY.add(baseY);
        addSelectableChild(widget);
    }

    /** Applies the scroll offset: moves every form widget and hides the ones out of view. */
    private void layoutForm() {
        int max = Math.max(0, formHeight - (formBottom - formTop));
        scroll = Math.max(0, Math.min(scroll, max));
        for (int i = 0; i < formWidgets.size(); i++) {
            ClickableWidget widget = formWidgets.get(i);
            int y = formTop + formBaseY.get(i) - scroll;
            widget.setY(y);
            widget.visible = y + widget.getHeight() > formTop && y < formBottom;
        }
    }

    // --------------------------------------------------------------- editing

    private void addBlock(ServicePoster.BlockType type) {
        if (draft.blocks.size() >= ServicePoster.MAX_BLOCKS) {
            return;
        }
        draft.blocks.add(type == ServicePoster.BlockType.ARROW
                ? ServicePoster.Block.arrow(0)
                : ServicePoster.Block.text(type, ""));
        rebuildForm();
        // Show the new row.
        scroll = Math.max(0, formHeight - (formBottom - formTop) - (ROW + ROW_GAP) * 3 - CAPTION);
        layoutForm();
    }

    private void moveBlock(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= draft.blocks.size()) {
            return;
        }
        ServicePoster.Block moved = draft.blocks.remove(index);
        draft.blocks.add(target, moved);
        rebuildForm();
    }

    private void openPopup(PopupMode mode) {
        if (mode != PopupMode.HEADER && lastFocused == null) {
            toast(Text.translatable("gui.station_announcer.posters.no_focus").getString());
            return;
        }
        popupLines = PosterLayout.lines();
        popup = mode;
        popupScroll = 0;
    }

    private void insertToken(String token) {
        if (lastFocused == null) {
            toast(Text.translatable("gui.station_announcer.posters.no_focus").getString());
            return;
        }
        String text = lastFocused.getText();
        int cursor = Math.max(0, Math.min(lastFocused.getCursor(), text.length()));
        // Pad with a space on the side that has a letter, so tokens read as words.
        String before = text.substring(0, cursor);
        String after = text.substring(cursor);
        String insert = token;
        if (!before.isEmpty() && !before.endsWith(" ")) {
            insert = " " + insert;
        }
        if (!after.isEmpty() && !after.startsWith(" ")) {
            insert = insert + " ";
        }
        String result = before + insert + after;
        int max = lastFocused == textFields.get(0) ? ServicePoster.MAX_FIELD : ServicePoster.MAX_BLOCK_TEXT;
        if (result.length() > max) {
            return;
        }
        // setText respects the field's own max length, so a header field cannot overflow.
        lastFocused.setText(result);
        if (!lastFocused.getText().equals(result)) {
            lastFocused.setText(text);
            lastFocused.setCursor(cursor, false);
            setFocused(lastFocused);
            return;
        }
        lastFocused.setCursor(cursor + insert.length(), false);
        setFocused(lastFocused);
    }

    private void toast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2_500;
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

    // ------------------------------------------------------------- behaviour

    private boolean inForm(double mouseX, double mouseY) {
        return mouseX >= formLeft && mouseX < formLeft + FORM_WIDTH && mouseY >= formTop && mouseY < formBottom;
    }

    private int popupLeft() {
        return width / 2 - 90;
    }

    private int popupTop() {
        return Math.max(20, height / 2 - popupHeight() / 2);
    }

    private int popupVisibleRows() {
        return Math.max(3, Math.min(12, (height - 60) / LinePicker.ROW_HEIGHT));
    }

    private int popupHeight() {
        return 18 + popupVisibleRows() * LinePicker.ROW_HEIGHT + 4;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (popup != null) {
            int left = popupLeft();
            int top = popupTop();
            int listTop = top + 18;
            if (mouseX >= left && mouseX < left + 180 && mouseY >= top && mouseY < top + popupHeight()) {
                if (mouseY >= listTop) {
                    int index = (int) ((mouseY - listTop) / LinePicker.ROW_HEIGHT) + popupScroll;
                    if (index >= 0 && index < popupLines.size()) {
                        choose(popupLines.get(index));
                    }
                }
                return true;
            }
            popup = null; // clicked outside: dismiss
            return true;
        }
        // Header-line chips: click removes.
        int chipY = formTop + linesRowY - scroll;
        if (mouseY >= chipY && mouseY < chipY + ROW && inForm(mouseX, mouseY)) {
            for (int i = 0; i < lineChipBounds.size(); i++) {
                int[] bounds = lineChipBounds.get(i);
                if (mouseX >= bounds[0] && mouseX < bounds[1] && i < draft.headerLines.size()) {
                    draft.headerLines.remove(i);
                    return true;
                }
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        rememberFocus();
        return handled;
    }

    private void choose(PosterLayout.LineOption line) {
        PopupMode mode = popup;
        popup = null;
        if (mode == PopupMode.HEADER) {
            if (draft.headerLines.size() < ServicePoster.MAX_HEADER_LINES && !draft.headerLines.contains(line.name())) {
                draft.headerLines.add(line.name());
            }
        } else {
            insertToken((mode == PopupMode.DIAMOND ? "{d:" : "{b:") + line.name() + "}");
        }
    }

    private void rememberFocus() {
        for (TextFieldWidget field : textFields) {
            if (field.isFocused()) {
                lastFocused = field;
                return;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (popup != null) {
            int max = Math.max(0, popupLines.size() - popupVisibleRows());
            popupScroll = Math.max(0, Math.min(max, popupScroll - (int) Math.signum(verticalAmount)));
            return true;
        }
        if (inForm(mouseX, mouseY)) {
            scroll -= (int) (verticalAmount * (ROW + ROW_GAP));
            layoutForm();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (popup != null && keyCode == 256) { // escape closes the popup first
            popup = null;
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        rememberFocus();
        return handled;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        boolean handled = super.charTyped(chr, modifiers);
        rememberFocus();
        return handled;
    }

    // --------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        rememberFocus();
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("gui.station_announcer.posters.insert"),
                formLeft, toolbarY + 4, AddonUi.TEXT_DIM);

        // Form panel with its scrolled contents.
        AddonUi.panel(context, formLeft - 2, formTop - 2, formLeft + FORM_WIDTH + 2, formBottom + 2);
        context.enableScissor(formLeft - 1, formTop - 1, formLeft + FORM_WIDTH + 1, formBottom + 1);
        for (Label label : labels) {
            int y = formTop + label.y() - scroll;
            if (y > formBottom || y + 9 < formTop) {
                continue;
            }
            int x = label.x() >= 0 ? label.x() : formLeft + 2;
            context.drawTextWithShadow(textRenderer,
                    label.x() >= 0 ? label.text() : textRenderer.trimToWidth(label.text(), LABEL_WIDTH - 4),
                    x, y, label.color());
        }
        drawLineChips(context, mouseX, mouseY);
        for (ClickableWidget widget : formWidgets) {
            if (widget.visible) {
                widget.render(context, mouseX, mouseY, delta);
            }
        }
        context.disableScissor();
        AddonUi.scrollIndicator(context, formLeft + FORM_WIDTH + 2, formTop, formBottom - formTop, formHeight, scroll);

        // Live preview.
        int previewWidth = Math.round(PosterLayout.WIDTH * previewScale);
        int previewHeight = Math.round(PosterLayout.MAX_HEIGHT * previewScale);
        context.fill(previewLeft - 3, previewTop - 3, previewLeft + previewWidth + 3, previewTop + previewHeight + 3, 0xFF2A2A30);
        PosterLayout.paint(new PosterLayout.GuiSurface(context, previewLeft, previewTop, previewScale), draft.build());

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            context.drawCenteredTextWithShadow(textRenderer, toast, width / 2, height - 40, AddonUi.DANGER);
        }
        context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(Text.translatable("gui.station_announcer.posters.insert_hint").getString(), width - 8),
                4, height - 8, AddonUi.TEXT_FAINT);

        if (popup != null) {
            drawPopup(context, mouseX, mouseY);
        }
    }

    private void drawLineChips(DrawContext context, int mouseX, int mouseY) {
        lineChipBounds.clear();
        int y = formTop + linesRowY - scroll;
        int x = formLeft + LABEL_WIDTH;
        int limit = formLeft + FORM_WIDTH - 48;
        for (String line : draft.headerLines) {
            var bullet = PosterLayout.lineBullet(line);
            String label = bullet.label() + " x";
            int chipWidth = textRenderer.getWidth(label) + 8;
            if (x + chipWidth > limit) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX < x + chipWidth && mouseY >= y + 3 && mouseY < y + 14;
            AddonUi.chip(context, textRenderer, label, x, y + 3, hovered ? 0xFFFF5555 : bullet.color());
            lineChipBounds.add(new int[]{x, x + chipWidth});
            x += chipWidth + 3;
        }
        if (draft.headerLines.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "—", x, y + 5, AddonUi.TEXT_FAINT);
        }
    }

    private void drawPopup(DrawContext context, int mouseX, int mouseY) {
        int left = popupLeft();
        int top = popupTop();
        int popupWidth = 180;
        int rows = popupVisibleRows();
        context.fill(0, 0, width, height, 0x80000000);
        AddonUi.panel(context, left, top, left + popupWidth, top + popupHeight());
        context.drawTextWithShadow(textRenderer, Text.translatable("gui.station_announcer.posters.pick_line"),
                left + 6, top + 5, AddonUi.TEXT);
        int listTop = top + 18;
        if (popupLines.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.translatable("gui.station_announcer.posters.pick_line_none"),
                    left + 6, listTop + 4, AddonUi.TEXT_FAINT);
            return;
        }
        for (int i = 0; i < rows; i++) {
            int index = i + popupScroll;
            if (index >= popupLines.size()) {
                break;
            }
            PosterLayout.LineOption option = popupLines.get(index);
            int rowY = listTop + i * LinePicker.ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < left + popupWidth
                    && mouseY >= rowY && mouseY < rowY + LinePicker.ROW_HEIGHT;
            if (hovered) {
                context.fill(left + 1, rowY, left + popupWidth - 1, rowY + LinePicker.ROW_HEIGHT, AddonUi.ROW_HOVER);
            }
            int chipWidth = AddonUi.chip(context, textRenderer, option.bullet().label(), left + 6, rowY + 2, option.bullet().color());
            context.drawTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(option.name(), popupWidth - chipWidth - 16),
                    left + 6 + chipWidth + 5, rowY + 4, AddonUi.TEXT);
        }
        AddonUi.scrollIndicator(context, left + popupWidth, listTop, rows * LinePicker.ROW_HEIGHT,
                popupLines.size() * LinePicker.ROW_HEIGHT, popupScroll * LinePicker.ROW_HEIGHT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
