package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.disruption.DisruptionNetworking;
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
import org.mtr.core.data.Route;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 6's management hub, opened by the "Disruptions" button injected on MTR's
 * dashboard. Rewritten (2026-08-08) from a stack of vanilla widgets into a
 * hand-drawn board in this project's own GUI language ({@code PlatformPicker}, the
 * PIDS screens): one scannable row per disruption carrying its severity badge,
 * message, affected-line chips, state and time window, with the three actions
 * (toggle / edit / delete) inline on the row instead of select-then-act.
 *
 * <p>Presentation only — the packets, the data model and every server behaviour
 * are exactly as before. The toggle sends the same upsert packet the editor does,
 * with only {@code active} flipped.</p>
 */
@Environment(EnvType.CLIENT)
public class DisruptionsScreen extends Screen {
    static final int PANEL_WIDTH = 360;
    static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 30;
    private static final int GAP = 4;

    private static final int TOGGLE_WIDTH = 30;
    private static final int EDIT_WIDTH = 32;
    private static final int POSTERS_WIDTH = 44;
    private static final int DELETE_WIDTH = 14;
    private static final int ACTIONS_WIDTH = TOGGLE_WIDTH + EDIT_WIDTH + POSTERS_WIDTH + DELETE_WIDTH + 12;

    private static final int SEVERE = 0xFFD23B3B;
    private static final int MAJOR = 0xFFD9822B;
    private static final int MINOR = 0xFFD9C22B;
    private static final int INFO = 0xFF3C7DD9;

    /** Kept for the sibling screens that still read the palette from here. */
    static final int TEXT_DIM = AddonUi.TEXT_DIM;
    static final int TEXT_FAINT = AddonUi.TEXT_FAINT;

    private final Screen parent;
    private List<ClientDisruptions.Entry> rows = List.of();
    private int scroll;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int summaryY;

    public DisruptionsScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.disruptions.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = ClientDisruptions.all();
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        // Fixed chrome: title, the summary line under the board, then two button rows.
        int bottomBlock = 12 + (WIDGET_HEIGHT + GAP) * 2;
        int available = height - 40 - bottomBlock;
        visibleRows = Math.max(2, Math.min(Math.max(rows.size(), 2), available / ROW_HEIGHT));
        int listHeight = visibleRows * ROW_HEIGHT;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW_HEIGHT - listHeight)));

        int content = listHeight + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;
        y += listHeight + 2;
        summaryY = y;
        y += 12;

        int half = (PANEL_WIDTH - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.disruptions.new"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new DisruptionEditScreen(null, this));
                            }
                        })
                .dimensions(left, y, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.stop_changes.button"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new StopChangeRoutesScreen(this));
                            }
                        })
                .dimensions(left + half + GAP, y, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, y, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    // ------------------------------------------------------------ behaviour

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listLeft || mouseX >= listLeft + PANEL_WIDTH
                || mouseY < listTop || mouseY >= listTop + visibleRows * ROW_HEIGHT) {
            return -1;
        }
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    private int actionsLeft() {
        return listLeft + PANEL_WIDTH - ACTIONS_WIDTH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            ClientDisruptions.Entry entry = rows.get(index);
            int x = actionsLeft();
            if (mouseX >= x && mouseX < x + TOGGLE_WIDTH) {
                sendToggle(entry);
                return true;
            }
            x += TOGGLE_WIDTH + 4;
            if (mouseX >= x && mouseX < x + EDIT_WIDTH) {
                if (client != null) {
                    client.setScreen(new DisruptionEditScreen(entry, this));
                }
                return true;
            }
            x += EDIT_WIDTH + 4;
            if (mouseX >= x && mouseX < x + POSTERS_WIDTH) {
                if (client != null) {
                    client.setScreen(new PosterListScreen(entry.id(), this));
                }
                return true;
            }
            x += POSTERS_WIDTH + 4;
            if (mouseX >= x && mouseX < x + DELETE_WIDTH) {
                sendDelete(entry.id());
                return true;
            }
            // Clicking the body of a row opens it — the obvious expectation.
            if (client != null) {
                client.setScreen(new DisruptionEditScreen(entry, this));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listHeight = visibleRows * ROW_HEIGHT;
        int max = Math.max(0, rows.size() * ROW_HEIGHT - listHeight);
        if (max > 0 && mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                && mouseY >= listTop && mouseY < listTop + listHeight) {
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * ROW_HEIGHT / 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // -------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        // Keep the freshly synced list in hand without a full re-init per frame.
        rows = ClientDisruptions.all();
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        int right = listLeft + PANEL_WIDTH;
        int listHeight = visibleRows * ROW_HEIGHT;
        int bottom = listTop + listHeight;
        AddonUi.panel(context, listLeft, listTop, right, bottom);

        if (rows.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.disruptions.none"),
                    listLeft + PANEL_WIDTH / 2, listTop + listHeight / 2 - 4, AddonUi.TEXT_FAINT);
        } else {
            long now = System.currentTimeMillis();
            int hovered = rowAt(mouseX, mouseY);
            context.enableScissor(listLeft + 1, listTop + 1, right - 1, bottom - 1);
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listTop + i * ROW_HEIGHT - scroll;
                if (rowY + ROW_HEIGHT < listTop || rowY > bottom) {
                    continue;
                }
                drawRow(context, rows.get(i), rowY, i == hovered, mouseX, mouseY, now);
            }
            context.disableScissor();
            AddonUi.scrollIndicator(context, right, listTop, listHeight, rows.size() * ROW_HEIGHT, scroll);
        }

        int changes = ClientStopChanges.count();
        context.drawTextWithShadow(textRenderer,
                changes > 0
                        ? Text.translatable("gui.station_announcer.stop_changes.marker", changes)
                        : Text.translatable("gui.station_announcer.disruptions.summary", rows.size()),
                listLeft, summaryY, changes > 0 ? MAJOR : AddonUi.TEXT_FAINT);
    }

    private void drawRow(DrawContext context, ClientDisruptions.Entry entry, int rowY, boolean hovered,
                         int mouseX, int mouseY, long now) {
        int right = listLeft + PANEL_WIDTH;
        boolean live = entry.isActiveAt(now);
        if (hovered) {
            context.fill(listLeft + 1, rowY, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_HOVER);
        }
        context.fill(listLeft + 1, rowY + ROW_HEIGHT - 1, right - 1, rowY + ROW_HEIGHT, AddonUi.ROW_DIVIDER);
        // A severity spine down the left edge, so the board reads at a glance.
        context.fill(listLeft + 1, rowY, listLeft + 4, rowY + ROW_HEIGHT - 1,
                live ? severityColor(entry.severity()) : 0xFF3A3A45);

        int textLeft = listLeft + 8;
        int textRight = actionsLeft() - 6;

        // Line 1: severity badge + message preview.
        int x = textLeft;
        x += AddonUi.chip(context, textRenderer,
                Text.translatable(severityKey(entry.severity())).getString(),
                x, rowY + 3, live ? severityColor(entry.severity()) : 0xFF4A4A55) + 5;
        context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(entry.message(), Math.max(10, textRight - x)),
                x, rowY + 4, live ? AddonUi.TEXT : AddonUi.TEXT_FAINT);

        // Line 2: the affected lines as colour chips, then the time window.
        String window = window(entry, now);
        int windowWidth = window.isEmpty() ? 0 : textRenderer.getWidth(window) + 4;
        int chipLimit = textRight - windowWidth;
        int chipX = textLeft;
        List<LineChip> chips = lineChips(entry);
        if (chips.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.disruptions.no_lines"),
                    chipX, rowY + 18, AddonUi.TEXT_FAINT);
        } else {
            for (int i = 0; i < chips.size(); i++) {
                LineChip chip = chips.get(i);
                int chipWidth = textRenderer.getWidth(chip.label()) + 8;
                if (chipX + chipWidth > chipLimit) {
                    context.drawTextWithShadow(textRenderer, "+" + (chips.size() - i), chipX, rowY + 18, AddonUi.TEXT_FAINT);
                    break;
                }
                chipX += AddonUi.chip(context, textRenderer, chip.label(), chipX, rowY + 16, chip.color()) + 3;
            }
        }
        if (!window.isEmpty()) {
            context.drawTextWithShadow(textRenderer, window, textRight - windowWidth + 4, rowY + 18, AddonUi.TEXT_FAINT);
        }

        // Inline actions.
        int actionX = actionsLeft();
        int actionY = rowY + (ROW_HEIGHT - 14) / 2;
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable(entry.active()
                        ? "gui.station_announcer.disruptions.on"
                        : "gui.station_announcer.disruptions.off").getString(),
                actionX, actionY, TOGGLE_WIDTH, 14, mouseX, mouseY,
                entry.active() ? AddonUi.OK : AddonUi.TEXT_FAINT);
        actionX += TOGGLE_WIDTH + 4;
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable("gui.station_announcer.disruptions.edit").getString(),
                actionX, actionY, EDIT_WIDTH, 14, mouseX, mouseY, AddonUi.TEXT);
        actionX += EDIT_WIDTH + 4;
        int posterCount = ClientPosters.forDisruption(entry.id()).size();
        AddonUi.inlineButton(context, textRenderer,
                Text.translatable("gui.station_announcer.disruptions.posters").getString()
                        + (posterCount > 0 ? " " + posterCount : ""),
                actionX, actionY, POSTERS_WIDTH, 14, mouseX, mouseY, posterCount > 0 ? AddonUi.TEXT : AddonUi.TEXT_DIM);
        actionX += POSTERS_WIDTH + 4;
        AddonUi.inlineButton(context, textRenderer, "x",
                actionX, actionY, DELETE_WIDTH, 14, mouseX, mouseY, AddonUi.DANGER);
    }

    /** One affected line as it appears on a row. */
    private record LineChip(String label, int color) {
    }

    /**
     * The affected lines, collapsed to one chip per LINE (colour + the name before
     * {@code ||}) so a disruption covering both directions shows a single chip —
     * the same grouping rule {@link LinePicker} uses.
     */
    private static List<LineChip> lineChips(ClientDisruptions.Entry entry) {
        List<LineChip> chips = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (long routeId : entry.routeIds()) {
            Route route = findRoute(routeId);
            if (route == null) {
                if (seen.add("#" + routeId)) {
                    chips.add(new LineChip("#" + routeId, 0xFF4A4A55));
                }
                continue;
            }
            String[] split = AddonUi.splitLineAndDirection(route.getName());
            int color = 0xFF000000 | route.getColor();
            String label = split[0].isEmpty() ? "#" + routeId : split[0];
            if (seen.add(color + " " + label)) {
                chips.add(new LineChip(label, color));
            }
        }
        return chips;
    }

    /** "starts in 12 min" / "ends in 45 min" / "" when the disruption has no window. */
    private static String window(ClientDisruptions.Entry entry, long now) {
        if (entry.startMillis() > now) {
            return Text.translatable("gui.station_announcer.disruptions.window_starts",
                    Math.max(1, (entry.startMillis() - now) / 60_000L)).getString();
        }
        if (entry.endMillis() > now) {
            return Text.translatable("gui.station_announcer.disruptions.window_ends",
                    Math.max(1, (entry.endMillis() - now) / 60_000L)).getString();
        }
        return "";
    }

    static Route findRoute(long routeId) {
        try {
            for (Route route : MinecraftClientData.getDashboardInstance().routes) {
                if (route.getId() == routeId) {
                    return route;
                }
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync
        }
        return null;
    }

    static int severityColor(int severity) {
        return switch (severity) {
            case 3 -> SEVERE;
            case 2 -> MAJOR;
            case 1 -> MINOR;
            default -> INFO;
        };
    }

    static String severityKey(int severity) {
        return switch (severity) {
            case 3 -> "gui.station_announcer.disruptions.severity.severe";
            case 2 -> "gui.station_announcer.disruptions.severity.major";
            case 1 -> "gui.station_announcer.disruptions.severity.minor";
            default -> "gui.station_announcer.disruptions.severity.info";
        };
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------- packets

    /** Same upsert packet the editor sends, with only {@code active} flipped. */
    private static void sendToggle(ClientDisruptions.Entry entry) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false); // upsert
        buf.writeLong(entry.id());
        buf.writeVarInt(entry.severity());
        buf.writeBoolean(!entry.active());
        buf.writeLong(entry.startMillis());
        buf.writeLong(entry.endMillis());
        buf.writeString(entry.message(), DisruptionNetworking.MAX_MESSAGE_LENGTH);
        int count = Math.min(entry.routeIds().size(), DisruptionNetworking.MAX_ROUTES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(entry.routeIds().get(i));
        }
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_DISRUPTION_C2S, buf);
    }

    private static void sendDelete(long id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true); // delete
        buf.writeLong(id);
        buf.writeVarInt(0);
        buf.writeBoolean(false);
        buf.writeLong(0);
        buf.writeLong(0);
        buf.writeString("", DisruptionNetworking.MAX_MESSAGE_LENGTH);
        buf.writeVarInt(0);
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_DISRUPTION_C2S, buf);
    }
}
