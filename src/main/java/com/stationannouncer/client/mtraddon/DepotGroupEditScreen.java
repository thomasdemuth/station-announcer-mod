package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.DepotGroupNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.mtr.core.data.Depot;
import org.mtr.mod.client.MinecraftClientData;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates or edits one depot group: a name and the member depots, ticked from the
 * dashboard's depot list.
 *
 * <p><b>Order is meaning here.</b> The tick order is the offset order: the first depot
 * ticked is the reference and keeps MTR's own departure times, the second departs
 * {@code 1/N} of a headway later, and so on. Each row therefore shows its slot and, once
 * the server has computed it against the depot's real frequency, the actual shift
 * ("+2m 30s"). Untick and re-tick to move a depot to the end of the order.</p>
 */
@Environment(EnvType.CLIENT)
public class DepotGroupEditScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int GAP = 4;
    private static final int CHECKBOX = 11;
    private static final int MAX_ROWS_TOTAL = 512;

    private static final int PANEL_BG = 0xF0111116;
    private static final int PANEL_BORDER = 0xFF3A3A45;
    private static final int ROW_HOVER = 0x18FFFFFF;
    private static final int ROW_SELECTED = 0x403C7DD9;
    private static final int CHECK_BORDER = 0xFF8A8A95;
    private static final int CHECK_FILL = 0xFF3C7DD9;
    private static final int BLOCKED = 0xFFFF5555;

    private record Row(long depotId, String name) {
    }

    private final Screen parent;
    private final long id;
    /** Member depot ids IN OFFSET ORDER — exactly what the save packet sends. */
    private final List<Long> selected = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private TextFieldWidget nameField;
    private String name;
    private int scrollOffset;

    private int listLeft;
    private int listTop;
    private int visibleRows;
    private int titleY;
    private int hintY;

    public DepotGroupEditScreen(ClientDepotGroups.Group existing, Screen parent) {
        super(Text.translatable(existing == null
                ? "gui.station_announcer.depot_groups.new_title"
                : "gui.station_announcer.depot_groups.edit_title"));
        this.parent = parent;
        if (existing == null) {
            this.id = 0;
            this.name = "";
        } else {
            this.id = existing.id();
            this.name = existing.name();
            for (ClientDepotGroups.Member member : existing.members()) {
                selected.add(member.depotId());
            }
        }
        try {
            for (Depot depot : MinecraftClientData.getDashboardInstance().depots) {
                if (rows.size() >= MAX_ROWS_TOTAL) {
                    break;
                }
                rows.add(new Row(depot.getId(), PlatformGroupScreen.firstLang(depot.getName())));
            }
        } catch (Exception ignored) {
            // dashboard data mid-sync — an empty list is handled below
        }
        // Members first (in their offset order), then everything else alphabetically.
        rows.sort((a, b) -> {
            int aIndex = selected.indexOf(a.depotId());
            int bIndex = selected.indexOf(b.depotId());
            if (aIndex >= 0 || bIndex >= 0) {
                if (aIndex < 0) {
                    return 1;
                }
                if (bIndex < 0) {
                    return -1;
                }
                return Integer.compare(aIndex, bIndex);
            }
            return a.name().compareToIgnoreCase(b.name());
        });
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        listLeft = left;

        int topBlock = WIDGET_HEIGHT + GAP;
        int bottomBlock = 24 + WIDGET_HEIGHT + GAP * 2;
        int available = height - 44 - topBlock - bottomBlock;
        visibleRows = Math.max(1, Math.min(Math.max(rows.size(), 1), available / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows)));

        int content = topBlock + visibleRows * ROW_HEIGHT + bottomBlock;
        int y = Math.max(28, (height - content) / 2);
        titleY = y - 16;

        nameField = new TextFieldWidget(textRenderer, left, y, PANEL_WIDTH, WIDGET_HEIGHT,
                Text.translatable("gui.station_announcer.depot_groups.name"));
        nameField.setMaxLength(DepotGroupNetworking.MAX_NAME_LENGTH);
        nameField.setText(name);
        nameField.setChangedListener(value -> name = value);
        addDrawableChild(nameField);
        y += topBlock;
        listTop = y;

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
        int half = (PANEL_WIDTH - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> {
                    save();
                    close();
                })
                .dimensions(left, footerY, half, WIDGET_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> close())
                .dimensions(left + half + GAP, footerY, PANEL_WIDTH - half - GAP, WIDGET_HEIGHT).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int index = scrollOffset + (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < rows.size()) {
                long depotId = rows.get(index).depotId();
                if (!selected.remove(Long.valueOf(depotId))
                        && !ClientDepotGroups.claimedByAnother(depotId, id)
                        && selected.size() < DepotGroupNetworking.MAX_DEPOTS) {
                    // Ticking appends: the tick order IS the offset order.
                    selected.add(depotId);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rows.size() > visibleRows && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_HEIGHT) {
            int newOffset = Math.max(0, Math.min(rows.size() - visibleRows, scrollOffset - (int) Math.signum(verticalAmount)));
            if (newOffset != scrollOffset) {
                scrollOffset = newOffset;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void save() {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty()) {
            // Name it here so the label is localized; the server has its own literal
            // fallback because a dedicated server does not load the mod's lang file.
            label = Text.translatable("gui.station_announcer.depot_groups.untitled").getString();
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(false); // upsert
        buf.writeLong(id);
        buf.writeString(label, DepotGroupNetworking.MAX_NAME_LENGTH);
        int count = Math.min(selected.size(), DepotGroupNetworking.MAX_DEPOTS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(selected.get(i));
        }
        ClientPlayNetworking.send(DepotGroupNetworking.UPDATE_DEPOT_GROUP_C2S, buf);
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

        int listHeight = visibleRows * ROW_HEIGHT;
        context.fill(listLeft - 1, listTop - 1, listLeft + PANEL_WIDTH + 1, listTop + listHeight + 1, PANEL_BORDER);
        context.fill(listLeft, listTop, listLeft + PANEL_WIDTH, listTop + listHeight, PANEL_BG);

        if (rows.isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.translatable("gui.station_announcer.depot_groups.no_depots"),
                    listLeft + 4, listTop + 6, DisruptionsScreen.TEXT_DIM);
        } else {
            ClientDepotGroups.Group stored = id == 0 ? null : findStored();
            for (int i = scrollOffset; i < scrollOffset + visibleRows && i < rows.size(); i++) {
                Row row = rows.get(i);
                int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;
                int slot = selected.indexOf(row.depotId());
                boolean claimed = ClientDepotGroups.claimedByAnother(row.depotId(), id);
                boolean hovered = mouseX >= listLeft && mouseX < listLeft + PANEL_WIDTH
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (slot >= 0) {
                    context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_SELECTED);
                } else if (hovered) {
                    context.fill(listLeft, rowY, listLeft + PANEL_WIDTH, rowY + ROW_HEIGHT, ROW_HOVER);
                }
                int boxY = rowY + (ROW_HEIGHT - CHECKBOX) / 2;
                context.fill(listLeft + 4, boxY, listLeft + 4 + CHECKBOX, boxY + CHECKBOX, CHECK_BORDER);
                context.fill(listLeft + 5, boxY + 1, listLeft + 3 + CHECKBOX, boxY + CHECKBOX - 1,
                        slot >= 0 ? CHECK_FILL : PANEL_BG);
                context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(row.name(), PANEL_WIDTH - CHECKBOX - 150),
                        listLeft + CHECKBOX + 10, rowY + 2, claimed ? BLOCKED : 0xFFFFFF);

                String detail;
                int color;
                if (claimed) {
                    detail = Text.translatable("gui.station_announcer.depot_groups.claimed").getString();
                    color = BLOCKED;
                } else if (slot < 0) {
                    detail = "";
                    color = DisruptionsScreen.TEXT_DIM;
                } else {
                    // The fraction is always knowable client-side; the absolute shift only
                    // once the server has measured this depot's headway.
                    detail = Text.translatable("gui.station_announcer.depot_groups.slot",
                            slot + 1, selected.size()).getString();
                    String absolute = storedOffsetLabel(stored, row.depotId(), slot);
                    if (!absolute.isEmpty()) {
                        detail = detail + " - " + absolute;
                    }
                    color = slot == 0 ? DisruptionsScreen.TEXT_DIM : DepotGroupsScreen.OFFSET_COLOR;
                }
                if (!detail.isEmpty()) {
                    context.drawTextWithShadow(textRenderer,
                            textRenderer.trimToWidth(detail, 150), listLeft + PANEL_WIDTH - 152, rowY + 7, color);
                }
            }
        }

        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.depot_groups.hint_order"),
                listLeft, hintY, DisruptionsScreen.TEXT_FAINT);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("gui.station_announcer.depot_groups.hint_generate"),
                listLeft, hintY + 11, DisruptionsScreen.TEXT_FAINT);
    }

    /**
     * The server-computed shift for a member, but only while the pending selection still
     * matches what the server stored — a half-edited list would otherwise show offsets
     * belonging to a different slot count.
     */
    private String storedOffsetLabel(ClientDepotGroups.Group stored, long depotId, int slot) {
        if (stored == null || stored.members().size() != selected.size()) {
            return "";
        }
        int storedIndex = stored.indexOf(depotId);
        if (storedIndex != slot) {
            return "";
        }
        ClientDepotGroups.Member member = stored.members().get(storedIndex);
        return member.hasOffset() ? DepotGroupsScreen.offsetLabel(member) : "";
    }

    private ClientDepotGroups.Group findStored() {
        for (ClientDepotGroups.Group group : ClientDepotGroups.all()) {
            if (group.id() == id) {
                return group;
            }
        }
        return null;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
