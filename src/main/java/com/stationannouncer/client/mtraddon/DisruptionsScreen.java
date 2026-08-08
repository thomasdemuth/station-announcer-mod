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
import java.util.List;

/**
 * Feature 6's management hub, opened by the "Disruptions" button injected on MTR's
 * dashboard (beside the addon's "Dispatch" button, on the row below it — see
 * {@code AddonClientInit}). Lists every stored disruption with its severity, its
 * on/off state and the lines it affects, and offers the entry points to create one
 * and to manage the temporary stop changes of Feature 6a.
 *
 * <p>MTR's own {@code DashboardScreen} is untouched: this is our own vanilla
 * {@link Screen}, reached through a Fabric {@code ScreenEvents.AFTER_INIT} button,
 * exactly like every other addon GUI.</p>
 */
@Environment(EnvType.CLIENT)
public class DisruptionsScreen extends Screen {
    static final int PANEL_WIDTH = 380;
    static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int BUTTON_WIDTH = 52;
    static final int TEXT_DIM = 0xFF9A9AA5;
    static final int TEXT_FAINT = 0xFF6E6E78;
    private static final int SEVERE = 0xFFFF5555;
    private static final int MAJOR = 0xFFFFAA33;
    private static final int MINOR = 0xFFFFDD55;
    private static final int INFO = 0xFF88CCFF;

    private final Screen parent;
    private List<ClientDisruptions.Entry> rows = List.of();
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int footerY;

    public DisruptionsScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.disruptions.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = ClientDisruptions.all();
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        int bottomBlock = 12 + WIDGET_HEIGHT * 2 + GAP * 2;
        int available = height - 44 - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(rows.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows)));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            ClientDisruptions.Entry entry = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.disruptions.edit"),
                            button -> {
                                if (client != null) {
                                    client.setScreen(new DisruptionEditScreen(entry, this));
                                }
                            })
                    .dimensions(left + PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP, rowY + 3, BUTTON_WIDTH, WIDGET_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.disruptions.delete"),
                            button -> {
                                // The server rebroadcasts the list; the row disappears
                                // when that sync lands (usually the same tick).
                                sendDelete(entry.id());
                                clearAndInit();
                            })
                    .dimensions(left + PANEL_WIDTH - BUTTON_WIDTH, rowY + 3, BUTTON_WIDTH, WIDGET_HEIGHT).build());
        }

        if (rows.size() > visibleRows) {
            ButtonWidget up = ButtonWidget.builder(Text.literal("^"), button -> {
                        scrollOffset = Math.max(0, scrollOffset - 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop, 14, WIDGET_HEIGHT).build();
            up.active = scrollOffset > 0;
            addDrawableChild(up);
            ButtonWidget down = ButtonWidget.builder(Text.literal("v"), button -> {
                        scrollOffset = Math.min(rows.size() - visibleRows, scrollOffset + 1);
                        clearAndInit();
                    })
                    .dimensions(left + PANEL_WIDTH + GAP, listTop + visibleRows * ROW_HEIGHT - WIDGET_HEIGHT, 14, WIDGET_HEIGHT).build();
            down.active = scrollOffset < rows.size() - visibleRows;
            addDrawableChild(down);
        }

        footerY = listTop + visibleRows * ROW_HEIGHT + GAP;
        int half = (PANEL_WIDTH - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.disruptions.new"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new DisruptionEditScreen(null, this));
                            }
                        })
                .dimensions(left, footerY, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.stop_changes.button"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new StopChangeRoutesScreen(this));
                            }
                        })
                .dimensions(left + half + GAP, footerY, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(left, footerY + WIDGET_HEIGHT + GAP, PANEL_WIDTH, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rows.size() > visibleRows && mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH + 20
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int newOffset = Math.max(0, Math.min(rows.size() - visibleRows, scrollOffset - (int) Math.signum(verticalAmount)));
            if (newOffset != scrollOffset) {
                scrollOffset = newOffset;
                clearAndInit();
            }
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);

        long now = System.currentTimeMillis();
        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.disruptions.none"), listLeft, listTop + 6, TEXT_DIM);
        } else {
            int textLimit = PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP * 2;
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                ClientDisruptions.Entry entry = rows.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(entry.message(), textLimit), listLeft, rowY + 2,
                        entry.isActiveAt(now) ? severityColor(entry.severity()) : TEXT_FAINT);
                String subtitle = Text.translatable(severityKey(entry.severity())).getString()
                        + " - "
                        + Text.translatable(entry.isActiveAt(now)
                        ? "gui.station_announcer.disruptions.state_active"
                        : "gui.station_announcer.disruptions.state_inactive").getString()
                        + " - " + lineNames(entry);
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(subtitle, textLimit), listLeft, rowY + 13, TEXT_DIM);
            }
        }

        if (ClientStopChanges.count() > 0) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.stop_changes.marker", ClientStopChanges.count()),
                    listLeft, footerY - 11, MAJOR);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Line names of a disruption, or a hint when it names none. */
    private static String lineNames(ClientDisruptions.Entry entry) {
        if (entry.routeIds().isEmpty()) {
            return Text.translatable("gui.station_announcer.disruptions.no_lines").getString();
        }
        StringBuilder builder = new StringBuilder();
        for (long routeId : entry.routeIds()) {
            Route route = findRoute(routeId);
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(route == null ? "#" + routeId : PlatformGroupScreen.firstLang(route.getName()));
        }
        return builder.toString();
    }

    static Route findRoute(long routeId) {
        for (Route route : MinecraftClientData.getDashboardInstance().routes) {
            if (route.getId() == routeId) {
                return route;
            }
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

    private static void sendDelete(long id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true); // delete
        buf.writeLong(id);
        buf.writeVarInt(0);     // severity (unused)
        buf.writeBoolean(false);
        buf.writeLong(0);
        buf.writeLong(0);
        buf.writeString("", DisruptionNetworking.MAX_MESSAGE_LENGTH);
        buf.writeVarInt(0);
        ClientPlayNetworking.send(DisruptionNetworking.UPDATE_DISRUPTION_C2S, buf);
    }
}
