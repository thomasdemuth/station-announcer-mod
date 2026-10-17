package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.StationAnnouncerConfigScreen;
import com.stationannouncer.client.mtraddon.nav.NavSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.DashboardScreen;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The dashboard's tool RAIL (Thomas, 2026-10-14): one slim column of square icon buttons
 * docked on the right edge of MTR's dashboard map, a tooltip on hover (name, what it does,
 * live info), replacing the buttons the addon used to squeeze into MTR's bottom rows
 * (Dispatch, Map+, Disruptions, Tools…). MTR's rarely used Transport System Map, Resource
 * Pack Creator and Options buttons fold into it too (hidden, pressed from here), so MTR's
 * bottom rows stop cutting labels off. Badges: active disruptions, dispatch alerts (held
 * platforms + obstructed doors). The last two tools used float to a "recent" group at the top.
 * A short window reflows the rail into two columns.
 *
 * <p>Pure Fabric screen events on MTR's {@code DashboardScreen}: AFTER_INIT finds MTR's three
 * buttons by their (translated) labels, afterRender draws, allowMouseClick swallows rail clicks.</p>
 */
@Environment(EnvType.CLIENT)
public final class DashboardRail {
    private static final int CELL = 18;
    private static final int GAP = 2;
    private static final int PAD = 3;
    private static final int TOP = 20;     // below MTR's coordinate readout
    private static final int BOTTOM = 46;  // above MTR's two bottom rows
    private static final int MAX_RECENT = 2;
    private static final int DIVIDER = 5;
    /** Dev rig only: a slot drawn as hovered without a mouse (-1 = off). */
    private static int devHover = -1;

    /** One rail entry. {@code badge} returns the count to show (0 = none), {@code live} an extra tooltip line. */
    private record Item(String id, String name, String description, String[] icon, int tint,
                        Supplier<Integer> badge, Supplier<String> live, Consumer<Screen> open) {
    }

    private record Slot(Item item, int x, int y) {
    }

    private final Screen screen;
    private final List<Item> items = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private int railX;
    private int railY;
    private int railW;
    private int railH;
    private int recentShown;
    private int dividerY = -1;

    private DashboardRail(Screen screen) {
        this.screen = screen;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DashboardScreen)) {
                return;
            }
            DashboardRail rail = new DashboardRail(screen);
            rail.collect(client);
            ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> rail.render(context, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) -> !rail.click(mouseX, mouseY, button));
        });
    }

    // ------------------------------------------------------------ items

    private void collect(MinecraftClient client) {
        boolean permission = MinecraftClientData.hasPermission();
        boolean dispatch = ClientDispatchInfo.isAvailable();
        if (dispatch) {
            items.add(new Item("dispatch", "Dispatch", "The live dispatch screen in your browser: trains, delays, holds.",
                    Icons.DISPATCH, 0xFF6FD08C, DashboardRail::alertCount, DashboardRail::alertText,
                    s -> Util.getOperatingSystem().open(URI.create(ClientDispatchInfo.url()))));
            items.add(new Item("mapplus", "Map+", "The system map and journey planner in your browser.",
                    Icons.MAP_PLUS, 0xFF5EA8FF, () -> 0, () -> "",
                    s -> Util.getOperatingSystem().open(URI.create(ClientDispatchInfo.url() + "map.html?player="
                            + MinecraftClient.getInstance().getSession().getUsername()))));
        }
        if (permission && AddonClientConfig.get().showDisruptionsButton) {
            items.add(new Item("disruptions", "Disruptions", "Service changes, posters and PA notices.",
                    Icons.WARNING, 0xFFF2B33D, DashboardRail::disruptionCount,
                    () -> disruptionCount() == 0 ? "No active disruptions" : disruptionCount() + " active now",
                    s -> MinecraftClient.getInstance().setScreen(new DisruptionsScreen(s))));
        }
        if (permission && AddonClientConfig.get().showToolsButton) {
            items.add(new Item("lines", "Lines & folders", "Group routes into folders; line style, overview and diagram.",
                    Icons.FOLDERS, 0xFFE8E8EE, () -> 0, () -> "",
                    s -> MinecraftClient.getInstance().setScreen(new LineFolderScreen(0, s))));
            items.add(new Item("interline", "Interlining", "Even out trains where lines share track.",
                    Icons.INTERLINE, 0xFFE8E8EE, () -> 0, () -> "",
                    s -> MinecraftClient.getInstance().setScreen(new InterlineScreen(s))));
            items.add(new Item("duplicate", "Duplicate line", "Copy a line's routes, e.g. for the other direction.",
                    Icons.DUPLICATE, 0xFFE8E8EE, () -> 0, () -> "",
                    s -> MinecraftClient.getInstance().setScreen(new DuplicateLineScreen(s))));
            items.add(new Item("nav", "Navigation", "Journey directions and the waypoint marker.",
                    Icons.NAV, 0xFFE8E8EE, () -> 0, () -> "",
                    s -> MinecraftClient.getInstance().setScreen(new NavSettingsScreen(s))));
        }
        // MTR's own, folded in: hidden from the bottom rows, pressed from here.
        PressableWidget map = hide(Text.translatable("gui.mtr.transport_system_map").getString());
        if (map != null) {
            items.add(new Item("mtr_map", "Transport System Map", "MTR's own system map in your browser.",
                    Icons.MAP, 0xFFB8B8C4, () -> 0, () -> "", s -> map.onPress()));
        }
        PressableWidget creator = hide(Text.translatable("gui.mtr.resource_pack_creator").getString());
        if (creator != null) {
            items.add(new Item("mtr_creator", "Resource Pack Creator", "MTR's custom vehicle / sign resource pack creator.",
                    Icons.PACK, 0xFFB8B8C4, () -> 0, () -> "", s -> creator.onPress()));
        }
        PressableWidget options = hide(Text.translatable("menu.options").getString());
        if (options != null) {
            items.add(new Item("mtr_options", "MTR options", "MTR's client options.",
                    Icons.SLIDERS, 0xFFB8B8C4, () -> 0, () -> "", s -> options.onPress()));
        }
        items.add(new Item("settings", "Station Announcer settings", "TTS, chimes, HUD and which screens the mod replaces.",
                Icons.GEAR, 0xFFB8B8C4, () -> 0, () -> "",
                s -> MinecraftClient.getInstance().setScreen(new StationAnnouncerConfigScreen(s))));
    }

    /** Hides one of MTR's bottom-row buttons (found by label) and returns it, or null. */
    private PressableWidget hide(String label) {
        for (ClickableWidget widget : Screens.getButtons(screen)) {
            if (widget instanceof PressableWidget pressable && label.equals(widget.getMessage().getString())) {
                widget.visible = false;
                widget.active = false;
                return pressable;
            }
        }
        return null;
    }

    private static int disruptionCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (ClientDisruptions.Entry entry : ClientDisruptions.all()) {
            if (entry.isActiveAt(now)) {
                count++;
            }
        }
        return count;
    }

    private static int alertCount() {
        return ClientHoldState.count() + ClientDoorObstructions.count();
    }

    private static String alertText() {
        int held = ClientHoldState.count();
        int doors = ClientDoorObstructions.count();
        if (held == 0 && doors == 0) {
            return "No alerts";
        }
        List<String> parts = new ArrayList<>();
        if (held > 0) {
            parts.add(held + (held == 1 ? " platform holding a train" : " platforms holding trains"));
        }
        if (doors > 0) {
            parts.add(doors + (doors == 1 ? " train with stuck doors" : " trains with stuck doors"));
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------ layout + drawing

    private List<Item> ordered(List<Item> recent) {
        List<String> ids = AddonClientConfig.get().recentDashboardTools;
        for (String id : ids) {
            for (Item item : items) {
                if (item.id().equals(id) && recent.size() < MAX_RECENT) {
                    recent.add(item);
                }
            }
        }
        return items;
    }

    /**
     * Fills top to bottom, then the next column: the recent group first, a small gap with a
     * divider, then every tool. A column that would run into MTR's bottom rows wraps.
     */
    private void layout() {
        slots.clear();
        List<Item> recent = new ArrayList<>();
        List<Item> all = ordered(recent);
        recentShown = recent.size();
        int top = TOP + PAD;
        int bottom = Math.max(top + CELL, screen.height - BOTTOM - PAD);
        List<int[]> cells = new ArrayList<>(); // column, y
        int col = 0;
        int y = top;
        int total = recent.size() + all.size();
        for (int i = 0; i < total; i++) {
            if (i == recent.size() && !recent.isEmpty()) {
                y += DIVIDER; // room for the divider under the recent group
            }
            if (y + CELL > bottom && y > top) {
                col++;
                y = top;
            }
            cells.add(new int[]{col, y});
            y += CELL + GAP;
        }
        int columns = col + 1;
        railW = columns * CELL + (columns - 1) * GAP + PAD * 2;
        railX = screen.width - railW - 4;
        railY = TOP;
        int maxBottom = top;
        for (int i = 0; i < total; i++) {
            Item item = i < recent.size() ? recent.get(i) : all.get(i - recent.size());
            int[] cell = cells.get(i);
            slots.add(new Slot(item, railX + PAD + cell[0] * (CELL + GAP), cell[1]));
            maxBottom = Math.max(maxBottom, cell[1] + CELL);
        }
        dividerY = recent.isEmpty() ? -1 : cells.get(recent.size() - 1)[1] + CELL + (DIVIDER + GAP) / 2;
        railH = maxBottom - railY + PAD;
    }

    private void render(DrawContext c, int mx, int my) {
        layout();
        var font = MinecraftClient.getInstance().textRenderer;
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 400);
        FlatUi.rect(c, railX, railY, railW, railH, 0xE0151518);
        FlatUi.outline(c, railX, railY, railW, railH, FlatUi.BORDER_STRONG);
        Slot hovered = null;
        int recentCount = recentShown;
        for (Slot slot : slots) {
            boolean over = FlatUi.inside(mx, my, slot.x(), slot.y(), CELL, CELL) || slots.indexOf(slot) == devHover;
            if (over) {
                hovered = slot;
            }
            FlatUi.rect(c, slot.x(), slot.y(), CELL, CELL, over ? 0xFF34343C : 0xFF222228);
            Icons.draw(c, slot.item().icon(), slot.x() + (CELL - 10) / 2, slot.y() + (CELL - 10) / 2,
                    over ? FlatUi.TEXT : slot.item().tint());
            int badge = slot.item().badge().get();
            if (badge > 0) {
                String text = badge > 9 ? "9+" : String.valueOf(badge);
                int bw = font.getWidth(text) / 2 + 4;
                int bx = slot.x() + CELL - bw + 1;
                int by = slot.y() - 1;
                FlatUi.rect(c, bx, by, bw, 7, FlatUi.DANGER);
                c.getMatrices().push();
                c.getMatrices().translate(bx + 2, by + 1, 0);
                c.getMatrices().scale(0.5f, 0.5f, 1);
                c.drawText(font, text, 0, 1, 0xFFFFFFFF, false);
                c.getMatrices().pop();
            }
        }
        // Divider under the recent group.
        if (recentCount > 0 && slots.size() > recentCount && dividerY >= 0) {
            Slot last = slots.get(recentCount - 1);
            FlatUi.rect(c, last.x(), dividerY, CELL, 1, FlatUi.BORDER_STRONG);
        }
        c.getMatrices().pop();
        if (hovered != null) {
            List<net.minecraft.text.OrderedText> lines = new ArrayList<>();
            int wrap = Math.max(80, Math.min(180, railX - 24));
            lines.add(Text.literal(hovered.item().name()).asOrderedText());
            lines.addAll(font.wrapLines(Text.literal(hovered.item().description()).styled(style -> style.withColor(0xA0A0AA)), wrap));
            String live = hovered.item().live().get();
            if (!live.isEmpty()) {
                int liveColor = hovered.item().badge().get() > 0 ? 0xFF7070 : 0x7FD99A;
                lines.addAll(font.wrapLines(Text.literal(live).styled(style -> style.withColor(liveColor)), wrap));
            }
            if (slots.indexOf(hovered) < recentCount) {
                lines.add(Text.literal("Recently used").styled(style -> style.withColor(0x707078)).asOrderedText());
            }
            int width = 0;
            for (net.minecraft.text.OrderedText line : lines) {
                width = Math.max(width, font.getWidth(line));
            }
            // Left of the rail (never off the right edge), above MTR's list layers.
            c.getMatrices().push();
            c.getMatrices().translate(0, 0, 600);
            c.drawOrderedTooltip(font, lines, Math.max(4, railX - width - 16), devHover >= 0 ? hovered.y() + 8 : my);
            c.getMatrices().pop();
        }
    }

    /** True when the click was the rail's (MTR must not see it). */
    private boolean click(double mx, double my, int button) {
        if (!FlatUi.inside(mx, my, railX, railY, railW, railH)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        for (Slot slot : slots) {
            if (FlatUi.inside(mx, my, slot.x(), slot.y(), CELL, CELL)) {
                remember(slot.item().id());
                MinecraftClient.getInstance().getSoundManager().play(
                        net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                slot.item().open().accept(screen);
                return true;
            }
        }
        return true;
    }

    /** Dev-only: {@code #rail hover N} (−1 = off), {@code #rail press N}. */
    public static void devHook(String cmd) {
        String[] a = cmd.trim().split("\\s+");
        if (a.length >= 3 && a[1].equals("hover")) {
            devHover = Integer.parseInt(a[2]);
        }
    }

    private static void remember(String id) {
        AddonClientConfig config = AddonClientConfig.get();
        List<String> recent = new ArrayList<>(config.recentDashboardTools);
        recent.remove(id);
        recent.add(0, id);
        while (recent.size() > 4) {
            recent.remove(recent.size() - 1);
        }
        config.recentDashboardTools = recent;
        AddonClientConfig.persist();
    }

    // ------------------------------------------------------------ icons

    /** 10×10 pixel icons ('#' = ink, '+' = half ink), drawn in GUI pixels in the item's tint. */
    static final class Icons {
        static final String[] DISPATCH = {
                "##########",
                "#........#",
                "#.##..#..#",
                "#....###.#",
                "#.#.#....#",
                "#..#.....#",
                "##########",
                "....##....",
                "...####...",
                ".........."};
        static final String[] MAP_PLUS = {
                "...####...",
                "..#....#..",
                ".#..##..#.",
                ".#.#..#.#.",
                ".#..##..#.",
                "..#....#..",
                "...#..#...",
                "....##....",
                "....##....",
                ".........."};
        static final String[] WARNING = {
                "....##....",
                "....##....",
                "...#..#...",
                "...#.##...",
                "..#..#.#..",
                "..#..#.#..",
                ".#......#.",
                ".#...#..#.",
                "#........#",
                "##########"};
        static final String[] FOLDERS = {
                "..........",
                "####......",
                "#..#####..",
                "#.......#.",
                "#.######+#",
                "#.#.....#.",
                "#.#.....#.",
                "#.#.....#.",
                "##########",
                ".........."};
        static final String[] INTERLINE = {
                "#.........",
                ".#........",
                "..#.......",
                "...######.",
                "..#.....##",
                ".#........",
                "#.........",
                "..........",
                "#########.",
                "........##"};
        static final String[] DUPLICATE = {
                "######....",
                "#....#....",
                "#....#....",
                "#..######.",
                "#..#....#.",
                "####....#.",
                "...#....#.",
                "...#....#.",
                "...######.",
                ".........."};
        static final String[] NAV = {
                ".........#",
                ".......##.",
                ".....##.#.",
                "...##...#.",
                ".##.....#.",
                "#####..#..",
                ".....#.#..",
                ".....#.#..",
                "......##..",
                "......#..."};
        static final String[] MAP = {
                "##..##..##",
                "#.##..##.#",
                "#.#...#..#",
                "#.#...#..#",
                "#.#...#..#",
                "#.#...#..#",
                "#.#...#..#",
                "#.##..##.#",
                "##..##..##",
                ".........."};
        static final String[] PACK = {
                "...####...",
                ".##....##.",
                "#..####..#",
                "#...##...#",
                "#...##...#",
                "#...##...#",
                "#...##...#",
                ".##.##.##.",
                "...####...",
                ".........."};
        static final String[] SLIDERS = {
                "..#.......",
                "##########",
                "..#.......",
                "..........",
                "......#...",
                "##########",
                "......#...",
                "..........",
                "...#......",
                "##########"};
        static final String[] GEAR = {
                "...#..#...",
                ".#.####.#.",
                "..#....#..",
                ".#..##..#.",
                "##.#..#.##",
                "##.#..#.##",
                ".#..##..#.",
                "..#....#..",
                ".#.####.#.",
                "...#..#..."};

        private Icons() {
        }

        static void draw(DrawContext c, String[] icon, int x, int y, int color) {
            int half = (color & 0x00FFFFFF) | 0x80000000;
            for (int row = 0; row < icon.length; row++) {
                String line = icon[row];
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    if (ch == '#') {
                        FlatUi.rect(c, x + col, y + row, 1, 1, color);
                    } else if (ch == '+') {
                        FlatUi.rect(c, x + col, y + row, 1, 1, half);
                    }
                }
            }
        }
    }
}
