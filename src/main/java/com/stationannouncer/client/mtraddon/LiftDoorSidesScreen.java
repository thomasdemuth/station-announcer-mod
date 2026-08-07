package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.LiftDoorSides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Lift;

/**
 * Feature 3's editor, opened by the "Door sides…" button on MTR's lift
 * customization screen: which of the cab's four sides (front = MTR's -Z in cab
 * space, back, left, right) have doors. Which sides actually open at a given
 * floor stays automatic — a side's doors only open when that floor's landing is
 * in front of them, so this simply declares where doors EXIST.
 *
 * <p>Saving all-off is prevented per spec: Done with nothing ticked falls back
 * to front-only. "Use MTR default" clears the config entirely (mask 0), giving
 * back stock behavior (front + back when double-sided). The server validates op
 * level like every other addon packet.</p>
 */
@Environment(EnvType.CLIENT)
public class LiftDoorSidesScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_FAINT = 0xFF9A9AA5;

    private final Lift lift;
    private final Screen parent;
    private final boolean hadConfig;
    private boolean front;
    private boolean back;
    private boolean left;
    private boolean right;

    private int titleY;
    private int hintY;

    public LiftDoorSidesScreen(Lift lift, Screen parent) {
        super(Text.translatable("gui.station_announcer.lift_doors.title"));
        this.lift = lift;
        this.parent = parent;

        LiftDoorSides current = ClientLiftDoors.get(lift.getId());
        this.hadConfig = current != null;
        if (current != null) {
            front = current.front();
            back = current.back();
            left = current.left();
            right = current.right();
        } else {
            // Stock MTR behavior for an unconfigured lift.
            front = true;
            back = lift.getIsDoubleSided();
            left = false;
            right = false;
        }
    }

    @Override
    protected void init() {
        int leftEdge = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        int content = 4 * (WIDGET_HEIGHT + GAP) + 8 + 24 + 2 * (WIDGET_HEIGHT + GAP);
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(front).build(leftEdge, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.lift_doors.front"), (button, value) -> front = value));
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(back).build(leftEdge, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.lift_doors.back"), (button, value) -> back = value));
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(left).build(leftEdge, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.lift_doors.left"), (button, value) -> left = value));
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(right).build(leftEdge, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.lift_doors.right"), (button, value) -> right = value));
        y += WIDGET_HEIGHT + GAP;

        hintY = y + 2;
        y += 24;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(leftEdge, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(leftEdge + half + GAP, y, half, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        ButtonWidget resetButton = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.lift_doors.reset"), button -> resetAndClose())
                .dimensions(leftEdge, y, PANEL_WIDTH, WIDGET_HEIGHT).build();
        resetButton.active = hadConfig;
        addDrawableChild(resetButton);
    }

    private void saveAndClose() {
        // Prevent all-off (an unenterable box): fall back to front, per spec.
        int mask = new LiftDoorSides(front, back, left, right).mask();
        if (mask == 0) {
            mask = LiftDoorSides.MASK_FRONT;
        }
        send(mask);
        close();
    }

    private void resetAndClose() {
        send(0); // mask 0 clears the config → stock MTR behavior
        close();
    }

    private void send(int mask) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(lift.getId());
        buf.writeByte(mask);
        ClientPlayNetworking.send(AddonNetworking.UPDATE_LIFT_DOORS_C2S, buf);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.lift_doors.hint"), width / 2, hintY, TEXT_FAINT);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.lift_doors.hint2"), width / 2, hintY + 11, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
