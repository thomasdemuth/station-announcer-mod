package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.DepotGroupEngine;
import com.stationannouncer.mtraddon.DepotGroupNetworking;
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
import org.mtr.core.data.Depot;
import org.mtr.mod.client.MinecraftClientData;
import java.util.List;

/**
 * The depot-group hub: every group Thomas has created, with its members and the phase
 * offset each one departs with, plus New / Edit / Delete.
 *
 * <p>Depots in one group never dispatch together — member {@code i} of {@code N} shifts
 * its whole departure timetable by {@code i/N} of its own headway. The list shows the
 * computed shift ("+2m 30s") when the server has one, and "at the next generation"
 * otherwise.</p>
 */
@Environment(EnvType.CLIENT)
public class DepotGroupsScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int GAP = 4;
    private static final int BUTTON_WIDTH = 52;
    static final int OFFSET_COLOR = 0xFF66DD88;

    private final Screen parent;
    private List<ClientDepotGroups.Group> rows = List.of();
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public DepotGroupsScreen(Screen parent) {
        super(Text.translatable("gui.station_announcer.depot_groups.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = ClientDepotGroups.all();
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        int bottomBlock = 24 + WIDGET_HEIGHT * 2 + GAP * 2;
        int available = height - 44 - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(rows.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows)));

        int content = visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;
        listTop = y;

        for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
            ClientDepotGroups.Group group = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.depot_groups.edit"),
                            button -> {
                                if (client != null) {
                                    client.setScreen(new DepotGroupEditScreen(group, this));
                                }
                            })
                    .dimensions(left + PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP, rowY + 3, BUTTON_WIDTH, WIDGET_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.station_announcer.depot_groups.delete"),
                            button -> {
                                sendDelete(group.id());
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

        hintY = listTop + visibleRows * ROW_HEIGHT + 2;
        int footerY = hintY + 24;
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.station_announcer.depot_groups.new"),
                        button -> {
                            if (client != null) {
                                client.setScreen(new DepotGroupEditScreen(null, this));
                            }
                        })
                .dimensions(left, footerY, PANEL_WIDTH, WIDGET_HEIGHT).build());
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

        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.depot_groups.none"), listLeft, listTop + 6,
                    DisruptionsScreen.TEXT_DIM);
        } else {
            int textLimit = PANEL_WIDTH - BUTTON_WIDTH * 2 - GAP * 2;
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                ClientDepotGroups.Group group = rows.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(group.name(), textLimit), listLeft, rowY + 2, 0xFFFFFF);
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(memberSummary(group), textLimit), listLeft, rowY + 13,
                        DisruptionsScreen.TEXT_DIM);
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.depot_groups.hint_stagger"),
                listLeft, hintY, DisruptionsScreen.TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.depot_groups.hint_generate"),
                listLeft, hintY + 11, DisruptionsScreen.TEXT_FAINT);
    }

    /** "Main Depot +0s, North Yard +2m 30s". */
    private static String memberSummary(ClientDepotGroups.Group group) {
        StringBuilder builder = new StringBuilder();
        for (ClientDepotGroups.Member member : group.members()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(depotName(member.depotId())).append(' ').append(offsetLabel(member));
        }
        return builder.length() == 0
                ? Text.translatable("gui.station_announcer.depot_groups.no_members").getString()
                : builder.toString();
    }

    /** "+2m 30s", or a hint when the railway has not computed it yet. */
    static String offsetLabel(ClientDepotGroups.Member member) {
        return member.hasOffset()
                ? DepotGroupEngine.formatOffset(member.offsetMillis())
                : Text.translatable("gui.station_announcer.depot_groups.offset_pending").getString();
    }

    /** The depot's name from the dashboard data, or its id when it is gone. */
    static String depotName(long depotId) {
        Depot depot = findDepot(depotId);
        return depot == null ? "#" + depotId : PlatformGroupScreen.firstLang(depot.getName());
    }

    static Depot findDepot(long depotId) {
        try {
            return MinecraftClientData.getDashboardInstance().depotIdMap.get(depotId);
        } catch (Exception e) {
            return null; // dashboard data mid-sync
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static void sendDelete(long id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(true); // delete
        buf.writeLong(id);
        buf.writeString("", DepotGroupNetworking.MAX_NAME_LENGTH);
        buf.writeVarInt(0);
        ClientPlayNetworking.send(DepotGroupNetworking.UPDATE_DEPOT_GROUP_C2S, buf);
    }
}
