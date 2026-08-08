package com.stationannouncer.client.mtraddon;

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
import org.mtr.core.data.Route;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 5's member picker for one (route, stopIndex) group: rotate this
 * route's stop across the ticked platforms. Uses the shared
 * {@link PlatformPicker} seeded with THIS platform's mid position — inside a
 * station the picker lists exactly that station's platforms, which is the
 * same-station constraint the group needs; the save additionally drops any
 * selection that is not at this platform's station with its transport mode
 * (the radius-sweep fallback outside stations can offer neighbours), and the
 * server re-validates authoritatively at generation time.
 *
 * <p>Ticking this platform itself keeps it in the rotation; a group without it
 * means the stop ALWAYS moves to a member. An empty selection (or Clear)
 * removes the group and the stop returns to the route's own platform on the
 * next depot regeneration.</p>
 */
@Environment(EnvType.CLIENT)
public class PlatformGroupEditScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int TEXT_DIM = 0xFF9A9AA5;
    private static final int TEXT_FAINT = 0xFF6E6E78;

    private final Route route;
    private final int stopIndex;
    private final String routeDisplayName;
    private final Platform platform;
    private final Screen parent;
    private final PlatformPicker picker;
    private final boolean hadGroup;

    private int listLeft;
    private int titleY;
    private int captionY;
    private int hintY;

    public PlatformGroupEditScreen(Route route, int stopIndex, String routeDisplayName, Platform platform, Screen parent) {
        super(Text.translatable("gui.station_announcer.platform_groups.edit_title"));
        this.route = route;
        this.stopIndex = stopIndex;
        this.routeDisplayName = routeDisplayName;
        this.platform = platform;
        this.parent = parent;

        List<Long> existing = ClientPlatformGroups.get(route.getId(), stopIndex);
        this.hadGroup = existing != null && !existing.isEmpty();

        Position mid = platform.getMidPosition();
        BlockPos origin = new BlockPos((int) mid.getX(), (int) mid.getY(), (int) mid.getZ());
        this.picker = new PlatformPicker(origin, existing == null ? new ArrayList<>() : existing,
                AddonNetworking.MAX_GROUP_SIZE, PANEL_WIDTH);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int half = (PANEL_WIDTH - GAP) / 2;

        // caption + three hint lines + Done/Cancel + Clear
        int fixed = 14 + 14 + 3 * 11 + 3 + (WIDGET_HEIGHT + GAP) * 2;
        picker.fitTo(height - fixed - 44);
        int content = fixed + picker.getHeight();
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        captionY = y + 2;
        y += 14;
        listLeft = left;
        picker.setPosition(left, y);
        y += picker.getHeight() + 3;
        hintY = y;
        y += 3 * 11 + 3;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, y, half, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        ButtonWidget clearButton = ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.platform_groups.clear"), button -> clearAndClose())
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build();
        clearButton.active = hadGroup;
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
        List<Long> members = new ArrayList<>();
        for (long id : picker.getSelected()) {
            if (members.size() < AddonNetworking.MAX_GROUP_SIZE && sameStationAndMode(id)) {
                members.add(id);
            }
        }
        // Saving with nothing ticked means "no group" — same packet as Clear.
        send(members);
        close();
    }

    private void clearAndClose() {
        send(List.of());
        close();
    }

    /**
     * The client-side same-station + same-transport-mode constraint. The picker
     * seeded inside a station only offers that station's platforms anyway; this
     * filter covers the radius-sweep fallback and preselected ids from stale
     * data. The server-side generation-time validation remains authoritative.
     */
    private boolean sameStationAndMode(long id) {
        if (id == platform.getId()) {
            return true;
        }
        try {
            Platform other = MinecraftClientData.getDashboardInstance().platformIdMap.get(id);
            return other != null && other.area != null && platform.area != null
                    && other.area.getId() == platform.area.getId()
                    && other.getTransportMode() == platform.getTransportMode();
        } catch (Exception e) {
            return false;
        }
    }

    private void send(List<Long> members) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(route.getId());
        buf.writeVarInt(stopIndex);
        buf.writeVarInt(members.size());
        for (long id : members) {
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(AddonNetworking.UPDATE_PLATFORM_GROUP_C2S, buf);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        Text caption = Text.literal(routeDisplayName + " — ")
                .append(Text.translatable("gui.station_announcer.platform_groups.members"));
        context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(caption.getString(), PANEL_WIDTH),
                listLeft, captionY, TEXT_DIM);
        picker.render(context, textRenderer, mouseX, mouseY);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.platform_groups.hint_queue"),
                listLeft, hintY, TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.platform_groups.hint_include"),
                listLeft, hintY + 11, TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.platform_groups.hint_queue2"),
                listLeft, hintY + 22, TEXT_FAINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
