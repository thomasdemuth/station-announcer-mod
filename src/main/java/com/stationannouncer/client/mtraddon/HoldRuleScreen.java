package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.client.mtr.PlatformPicker;
import com.stationannouncer.mtraddon.AddonNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 1's editor, opened by the "Hold rules…" button on MTR's platform
 * screen: hold trains at THIS platform while a train is less than N seconds away
 * from any watched platform.
 *
 * <p>The watched platforms are chosen with the shared {@link PlatformPicker},
 * seeded with the edited platform's mid position so it lists that station's
 * platforms nearest-first — exactly the connection-protection cases the feature
 * exists for. The picker also lists the edited platform itself; ticking it is
 * harmless, because the save silently drops it (a platform watching itself is
 * meaningless).</p>
 */
@Environment(EnvType.CLIENT)
public class HoldRuleScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    private final Platform platform;
    private final Screen parent;
    private final PlatformPicker picker;
    private final boolean hadRule;
    private int seconds;
    private int transferSeconds;

    private int listLeft;
    private int titleY;
    private int watchedCaptionY;
    private int hintY;

    public HoldRuleScreen(Platform platform, Screen parent) {
        super(Text.translatable("gui.station_announcer.hold_rules.title"));
        this.platform = platform;
        this.parent = parent;

        ClientHoldRules.Rule rule = ClientHoldRules.get(platform.getId());
        this.hadRule = rule != null;
        this.seconds = rule == null ? 30 : rule.seconds();
        this.transferSeconds = rule == null ? AddonNetworking.DEFAULT_TRANSFER_SECONDS : rule.transferSeconds();
        List<Long> preselected = rule == null ? new ArrayList<>() : rule.watched();

        Position mid = platform.getMidPosition();
        BlockPos origin = new BlockPos((int) mid.getX(), (int) mid.getY(), (int) mid.getZ());
        this.picker = new PlatformPicker(origin, preselected, AddonNetworking.MAX_WATCHED, PANEL_WIDTH);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        int fixed = 14 + 14 + 2 * (WIDGET_HEIGHT + GAP) + 8 + 2 * WIDGET_HEIGHT + GAP;
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        watchedCaptionY = y + 2;
        y += 14;
        listLeft = left;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        hintY = y;
        y += 11;

        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                AddonNetworking.MIN_HOLD_WINDOW_SECONDS, AddonNetworking.MAX_HOLD_WINDOW_SECONDS, seconds,
                value -> Text.translatable("gui.station_announcer.hold_rules.seconds", value),
                value -> seconds = value));
        y += WIDGET_HEIGHT + GAP;

        // Transfer time: once a watched train lands, both trains sit with doors
        // open for this long so passengers can actually make the connection.
        addDrawableChild(new IntSlider(left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                0, AddonNetworking.MAX_TRANSFER_SECONDS, transferSeconds,
                value -> value == 0
                        ? Text.translatable("gui.station_announcer.hold_rules.transfer_seconds.off")
                        : Text.translatable("gui.station_announcer.hold_rules.transfer_seconds", value),
                value -> transferSeconds = value));
        y += WIDGET_HEIGHT + GAP + 8;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        ButtonWidget clearButton = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.hold_rules.clear"), button -> clearAndClose())
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build();
        clearButton.active = hadRule;
        addDrawableChild(clearButton);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return picker.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return picker.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ------------------------------------------------------------- lifecycle

    private void saveAndClose() {
        List<Long> watched = new ArrayList<>();
        for (long id : picker.getSelected()) {
            if (id != platform.getId() && watched.size() < AddonNetworking.MAX_WATCHED) {
                watched.add(id);
            }
        }
        // Saving with nothing watched means "no rule" — same packet as Clear.
        send(watched.isEmpty() ? 0 : seconds, watched);
        close();
    }

    private void clearAndClose() {
        send(0, List.of());
        close();
    }

    private void send(int sendSeconds, List<Long> watched) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(platform.getId());
        buf.writeVarInt(sendSeconds);
        buf.writeVarInt(transferSeconds);
        buf.writeVarInt(watched.size());
        for (long id : watched) {
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(AddonNetworking.UPDATE_HOLD_RULE_C2S, buf);
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
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.hold_rules.watched"),
                listLeft, watchedCaptionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        int selected = picker.getSelected().size();
        context.drawTextWithShadow(textRenderer, selected == 0
                        ? Text.translatable("gui.station_announcer.hold_rules.hint_none")
                        : Text.translatable("gui.station_announcer.hold_rules.hint_active", selected),
                listLeft, hintY, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
