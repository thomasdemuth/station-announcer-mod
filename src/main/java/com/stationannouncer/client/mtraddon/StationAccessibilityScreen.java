package com.stationannouncer.client.mtraddon;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step-free accessibility editor for one station, opened from the
 * "Accessibility…" button on MTR's Edit Station screen (beside the zone
 * fields). One master toggle marks the station step-free; the per-platform
 * toggles narrow it to specific platforms — with NONE ticked, every platform
 * counts as step-free (the hint says so). Saving sends the whole selection to
 * the server (op-gated there), which syncs every client and the dispatch map.
 */
@Environment(EnvType.CLIENT)
public class StationAccessibilityScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_PLATFORMS = 8;

    /** name+id pairs, gathered by the caller from the station's saved rails. */
    public record PlatformEntry(long id, String name) {
    }

    private final long stationId;
    private final String stationName;
    private final List<PlatformEntry> platforms;
    private final Screen parent;

    private boolean accessible;
    private final Set<Long> selected = new LinkedHashSet<>();
    private ButtonWidget masterButton;
    private final List<ButtonWidget> platformButtons = new ArrayList<>();
    private int scroll;

    public StationAccessibilityScreen(long stationId, String stationName,
                                      List<PlatformEntry> platforms, Screen parent) {
        super(Text.translatable("gui.station_announcer.accessibility.title"));
        this.stationId = stationId;
        this.stationName = stationName;
        this.platforms = platforms;
        this.parent = parent;
        this.accessible = ClientAccessibility.isAccessibleStation(stationId);
        long[] configured = ClientAccessibility.platforms(stationId);
        if (configured != null) {
            for (long platform : configured) {
                selected.add(platform);
            }
        }
    }

    @Override
    protected void init() {
        platformButtons.clear();
        int left = (width - PANEL_WIDTH) / 2;
        masterButton = ButtonWidget.builder(masterLabel(), button -> {
            accessible = !accessible;
            button.setMessage(masterLabel());
            updatePlatformButtons();
        }).dimensions(left, 34, PANEL_WIDTH, 20).build();
        addDrawableChild(masterButton);

        int y = 74;
        int visible = Math.min(platforms.size(), MAX_VISIBLE_PLATFORMS);
        for (int i = 0; i < visible; i++) {
            final int slot = i;
            ButtonWidget row = ButtonWidget.builder(Text.empty(), button -> {
                PlatformEntry entry = entryAt(slot);
                if (entry == null) {
                    return;
                }
                if (!selected.remove(entry.id())) {
                    selected.add(entry.id());
                }
                updatePlatformButtons();
            }).dimensions(left, y, PANEL_WIDTH, 18).build();
            platformButtons.add(row);
            addDrawableChild(row);
            y += ROW_HEIGHT;
        }
        updatePlatformButtons();

        int buttonY = height - 26;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> saveAndClose())
                .dimensions(left, buttonY, 146, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + 154, buttonY, PANEL_WIDTH - 154, 20).build());
    }

    private Text masterLabel() {
        return Text.translatable(accessible
                ? "gui.station_announcer.accessibility.station_on"
                : "gui.station_announcer.accessibility.station_off");
    }

    private PlatformEntry entryAt(int slot) {
        int index = scroll + slot;
        return index >= 0 && index < platforms.size() ? platforms.get(index) : null;
    }

    private void updatePlatformButtons() {
        for (int i = 0; i < platformButtons.size(); i++) {
            ButtonWidget row = platformButtons.get(i);
            PlatformEntry entry = entryAt(i);
            row.visible = accessible && entry != null;
            if (entry != null) {
                boolean ticked = selected.contains(entry.id());
                row.setMessage(Text.literal((ticked ? "☑ ♿ " : "☐ ") + entry.name()));
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (platforms.size() > MAX_VISIBLE_PLATFORMS) {
            scroll = Math.max(0, Math.min(platforms.size() - MAX_VISIBLE_PLATFORMS,
                    scroll - (int) Math.signum(verticalAmount)));
            updatePlatformButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void saveAndClose() {
        // Ticking every platform is the same as ticking none — normalize to the
        // compact "all platforms" form so the icon logic stays simple everywhere.
        Set<Long> toSend = new LinkedHashSet<>(selected);
        toSend.removeIf(id -> platforms.stream().noneMatch(entry -> entry.id() == id));
        if (toSend.size() == platforms.size()) {
            toSend.clear();
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(stationId);
        buf.writeBoolean(accessible);
        buf.writeVarInt(Math.min(toSend.size(), AddonNetworking.MAX_ACCESSIBLE_PLATFORMS));
        int written = 0;
        for (long id : toSend) {
            if (written++ >= AddonNetworking.MAX_ACCESSIBLE_PLATFORMS) {
                break;
            }
            buf.writeLong(id);
        }
        ClientPlayNetworking.send(AddonNetworking.UPDATE_ACCESSIBILITY_C2S, buf);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int left = (width - PANEL_WIDTH) / 2;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.accessibility.heading", stationName), left, 16, 0xFFFFFF);
        if (accessible) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable(selected.isEmpty()
                            ? "gui.station_announcer.accessibility.hint_all"
                            : "gui.station_announcer.accessibility.hint_some", selected.size()),
                    left, 60, 0x8FA3C4);
            if (platforms.isEmpty()) {
                context.drawTextWithShadow(textRenderer,
                        Text.translatable("gui.station_announcer.accessibility.no_platforms"), left, 78, 0x8FA3C4);
            }
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
