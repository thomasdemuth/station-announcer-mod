package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.RailingSignBlock;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;

/**
 * Entrance-sign settings (opened with the MTR brush).
 *
 * <p>The two faces of the panel are configured independently — a sign at the
 * top of the stairs faces the street on one side and the station on the other,
 * and those want different things — so the screen edits ONE face at a time:
 * pick the face, say whether it carries a sign at all, and tick the lines that
 * stop here. The faces are named after the compass direction they actually
 * look at, taken from the railing run.</p>
 */
@Environment(EnvType.CLIENT)
public class RailingSignScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int PREVIEW_HEIGHT = 22;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int PREVIEW_BG = 0xFF0C0C0E;
    private static final int PREVIEW_BORDER = 0xFF2A2A32;

    private final StationDecorBlockEntity decor;
    private final RoutePicker picker;
    private final Direction frontDirection;

    private String customName;
    private boolean signFront;
    private boolean signBack;
    private List<String> frontRoutes;
    private List<String> backRoutes;
    /** Which face the list below is editing. */
    private boolean editingFront = true;

    private int titleY;
    private int listLeft;
    private int listCaptionY;
    private int previewY;
    private int hintY;

    public RailingSignScreen(StationDecorBlockEntity decor) {
        super(Text.translatable("gui.station_announcer.railing_sign.title"));
        this.decor = decor;
        this.customName = decor.getCustomName();
        this.signFront = decor.isSignFront();
        this.signBack = decor.isSignBack();
        this.frontRoutes = new ArrayList<>(decor.getFrontRoutes());
        this.backRoutes = new ArrayList<>(decor.getBackRoutes());
        this.frontDirection = RailingSignBlock.frontOf(decor.getCachedState());
        this.picker = new RoutePicker(decor.getPos(), StationDecorBlockEntity.MAX_ROUTE_BULLETS, PANEL_WIDTH);
        this.picker.setSelected(frontRoutes);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        int fixed = WIDGET_HEIGHT + GAP + 4          // name field
                + WIDGET_HEIGHT + GAP                // face + on/off buttons
                + 14                                 // list caption
                + 3 + PREVIEW_HEIGHT + 3 + 11        // preview strip and its hint
                + 8 + WIDGET_HEIGHT;                 // Done / Cancel
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listLeft = left;

        TextFieldWidget nameField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.sign.name"));
        nameField.setMaxLength(StationDecorBlockEntity.MAX_NAME_LENGTH);
        nameField.setPlaceholder(Text.translatable("gui.station_announcer.sign.name.hint")
                .formatted(Formatting.DARK_GRAY));
        nameField.setText(customName);
        nameField.setChangedListener(value -> customName = value);
        addDrawableChild(nameField);
        y += WIDGET_HEIGHT + GAP + 4;

        addDrawableChild(CyclingButtonWidget.<Boolean>builder(this::faceLabel)
                .values(true, false)
                .initially(editingFront)
                .build(left, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.railing_sign.face"),
                        (button, value) -> switchFace(value)));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(editedSideOn())
                .build(left + half + GAP, y, half, WIDGET_HEIGHT,
                        Text.translatable("gui.station_announcer.railing_sign.show"),
                        (button, value) -> setEditedSideOn(value)));
        y += WIDGET_HEIGHT + GAP;

        listCaptionY = y;
        y += 14;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        previewY = y;
        y += PREVIEW_HEIGHT + 3;
        hintY = y;
        y += 11 + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
    }

    /** The face named after where it looks: "North side", "South side". */
    private Text faceLabel(boolean front) {
        Direction direction = front ? frontDirection : frontDirection.getOpposite();
        return Text.translatable("gui.station_announcer.railing_sign.face." + direction.asString());
    }

    /**
     * Switching face banks the ticked lines against the face being left, then
     * loads the other one's. The screen is rebuilt because the on/off button
     * belongs to the face too.
     */
    private void switchFace(boolean front) {
        if (front == editingFront) {
            return;
        }
        commitRoutes();
        editingFront = front;
        picker.setSelected(front ? frontRoutes : backRoutes);
        clearAndInit();
    }

    private void commitRoutes() {
        if (editingFront) {
            frontRoutes = picker.getSelected();
        } else {
            backRoutes = picker.getSelected();
        }
    }

    private boolean editedSideOn() {
        return editingFront ? signFront : signBack;
    }

    private void setEditedSideOn(boolean on) {
        if (editingFront) {
            signFront = on;
        } else {
            signBack = on;
        }
    }

    // --------------------------------------------------------------- behaviour

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return picker.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return picker.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void saveAndClose() {
        commitRoutes();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(decor.getPos());
        buf.writeString(customName.trim(), StationDecorBlockEntity.MAX_NAME_LENGTH);
        buf.writeBoolean(signFront);
        buf.writeBoolean(signBack);
        writeRoutes(buf, frontRoutes);
        writeRoutes(buf, backRoutes);
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_RAILING_SIGN_C2S, buf);
        close();
    }

    private static void writeRoutes(PacketByteBuf buf, List<String> routes) {
        int count = Math.min(routes.size(), StationDecorBlockEntity.MAX_ROUTE_BULLETS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeString(routes.get(i), StationDecorBlockEntity.MAX_ROUTE_NAME_LENGTH);
        }
    }

    // ----------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.railing_sign.lines", faceLabel(editingFront)),
                listLeft, listCaptionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        drawPreview(context);

        context.drawTextWithShadow(textRenderer, editedSideOn()
                        ? Text.translatable("gui.station_announcer.railing_sign.hint_on")
                        : Text.translatable("gui.station_announcer.railing_sign.hint_off"),
                listLeft, hintY, TEXT_FAINT);
    }

    /** The face as it will look: the black panel, the name, and the bullets beside it. */
    private void drawPreview(DrawContext context) {
        int right = listLeft + PANEL_WIDTH;
        int bottom = previewY + PREVIEW_HEIGHT;
        context.fill(listLeft, previewY, right, bottom, PREVIEW_BG);
        context.fill(listLeft, previewY, right, previewY + 1, PREVIEW_BORDER);
        context.fill(listLeft, bottom - 1, right, bottom, PREVIEW_BORDER);
        context.fill(listLeft, previewY, listLeft + 1, bottom, PREVIEW_BORDER);
        context.fill(right - 1, previewY, right, bottom, PREVIEW_BORDER);

        if (!editedSideOn()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.railing_sign.preview_off"),
                    listLeft + PANEL_WIDTH / 2, previewY + 7, TEXT_FAINT);
            return;
        }

        List<String> routes = picker.getSelected();
        String name = customName.trim().isEmpty()
                ? Text.translatable("gui.station_announcer.railing_sign.preview_auto").getString()
                : customName.trim();
        int bulletsWidth = routes.isEmpty() ? 0 : routes.size() * 13 + (routes.size() - 1) * 3 + 6;
        String shown = textRenderer.trimToWidth(name, PANEL_WIDTH - 12 - bulletsWidth);
        int groupWidth = textRenderer.getWidth(shown) + bulletsWidth;
        int x = listLeft + (PANEL_WIDTH - groupWidth) / 2;
        context.drawText(textRenderer, shown, x, previewY + 7, 0xFFF5F5F5, false);
        x += textRenderer.getWidth(shown) + 6;
        for (String routeName : routes) {
            RoutePicker.drawBullet(context, textRenderer, RoutePicker.bulletFor(routeName), x, previewY + 4);
            x += 16;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
