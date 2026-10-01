package com.stationannouncer.client.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.material.MaterialBlocks;
import com.stationannouncer.material.MaterialPalette;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The texture picker for material ramps and stairs (FlatUi, no vanilla
 * widgets). Left: search, category chips and a grid of every full block.
 * Right: a large live preview of the ramp / stairs in the hovered (else the
 * chosen) material, its name, the recently used materials, and — when a placed
 * block is being retextured — whether to do its whole connected run.
 *
 * <p>Opened by right-clicking the air with a ramp / stairs item (sets the
 * item), or by sneak-right-clicking a placed one (retextures the block). Any
 * block in the world can also be copied directly: sneak-right-click it with
 * the item.</p>
 */
public class MaterialPickerScreen extends Screen {
    private static final int CELL = 18;
    private static final String[] CATEGORIES = {"All", "Stone", "Wood", "Concrete", "Terracotta", "Wool", "Glass", "Station", "Other"};
    private static final int MAX_RECENT = 10;
    private static List<Block> allCandidates;

    private final @Nullable Hand hand;
    private final @Nullable BlockPos pos;
    private final Item previewItem;
    private String chosen;
    private @Nullable String hovered;
    private boolean wholeRun = true;
    private int category;
    private int scroll;
    private FlatUi.TextBox search;
    private List<Block> filtered = List.of();
    private String lastFilter = null;
    private int lastCategory = -1;
    private final List<String> recent = loadRecent();

    // hit rectangles recorded while drawing
    private final List<int[]> hits = new ArrayList<>();
    private static final int HIT_CELL = 1, HIT_CHIP = 2, HIT_RECENT = 3, HIT_RUN = 4, HIT_APPLY = 5, HIT_CANCEL = 6;

    private MaterialPickerScreen(@Nullable Hand hand, @Nullable BlockPos pos, Item previewItem, String current) {
        super(Text.translatable("gui.station_announcer.material.title"));
        this.hand = hand;
        this.pos = pos;
        this.previewItem = previewItem;
        this.chosen = current;
    }

    /** For the item in {@code hand}. */
    public static MaterialPickerScreen forHand(Hand hand, ItemStack stack) {
        return new MaterialPickerScreen(hand, null, stack.getItem(), MaterialPalette.materialOf(stack));
    }

    /** For a placed ramp / stair at {@code pos}. */
    public static @Nullable MaterialPickerScreen forBlock(BlockPos pos, BlockState state) {
        Item item = state.getBlock().asItem();
        String current = MaterialPalette.materialAt(MaterialPalette.slot(state), true);
        return new MaterialPickerScreen(null, pos.toImmutable(), item, current);
    }

    @Override
    protected void init() {
        search = new FlatUi.TextBox(textRenderer, 32, false);
        search.placeholder = Text.translatable("gui.station_announcer.material.search").getString();
        search.setFocused(true);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ data

    private static List<Block> candidates() {
        if (allCandidates == null) {
            List<Block> list = new ArrayList<>();
            for (Block block : Registries.BLOCK) {
                if (block.asItem() != Items.AIR && MaterialPalette.isUsable(block.getDefaultState())) {
                    list.add(block);
                }
            }
            list.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));
            allCandidates = list;
        }
        return allCandidates;
    }

    private static int categoryOf(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();
        if (id.getNamespace().equals(StationAnnouncer.MOD_ID)) {
            return 7;
        }
        if (path.contains("glass")) {
            return 6;
        }
        if (path.contains("wool") || path.contains("carpet")) {
            return 5;
        }
        if (path.contains("terracotta")) {
            return 4;
        }
        if (path.contains("concrete")) {
            return 3;
        }
        if (path.contains("plank") || path.contains("log") || path.contains("wood") || path.contains("stem")
                || path.contains("hyphae") || path.contains("bamboo")) {
            return 2;
        }
        for (String stone : new String[]{"stone", "brick", "deepslate", "andesite", "diorite", "granite", "tuff",
                "basalt", "blackstone", "sandstone", "quartz", "prismarine", "purpur", "calcite", "slate", "tile",
                "cobble", "mud", "obsidian", "end_stone", "netherrack"}) {
            if (path.contains(stone)) {
                return 1;
            }
        }
        return 8;
    }

    private void filter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.equals(lastFilter) && category == lastCategory) {
            return;
        }
        lastFilter = q;
        lastCategory = category;
        scroll = 0;
        List<Block> out = new ArrayList<>();
        for (Block block : candidates()) {
            if (category != 0 && categoryOf(block) != category) {
                continue;
            }
            if (q.isEmpty() || block.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || Registries.BLOCK.getId(block).toString().contains(q)) {
                out.add(block);
            }
        }
        filtered = out;
    }

    private static String materialFor(Block block) {
        return MaterialPalette.stringify(block.getDefaultState());
    }

    private static Block blockOf(String material) {
        BlockState state = MaterialPalette.parse(material);
        return state == null ? net.minecraft.block.Blocks.SMOOTH_STONE : state.getBlock();
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        filter();
        hits.clear();
        hovered = null;
        c.fill(0, 0, width, height, 0xB0000000);
        int w = Math.min(width - 12, 372);
        int h = Math.min(height - 12, 232);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.rect(c, x, y, w, h, FlatUi.GROUND);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);

        int previewW = Math.min(124, w / 3);
        int leftW = w - previewW - 18;
        int lx = x + 8;
        int top = y + 7;
        String title = Text.translatable(pos != null ? "gui.station_announcer.material.title_block"
                : "gui.station_announcer.material.title", previewItem.getName().getString()).getString();
        c.drawText(textRenderer, textRenderer.trimToWidth(title, leftW), lx, top, FlatUi.TEXT, false);

        // search
        search.setBounds(lx, top + 12, leftW, 14);
        search.render(c, mx, my);

        // category chips (wrap)
        int cx = lx;
        int cy = top + 30;
        for (int i = 0; i < CATEGORIES.length; i++) {
            String label = Text.translatable("gui.station_announcer.material.cat." + CATEGORIES[i].toLowerCase(Locale.ROOT)).getString();
            int cw = textRenderer.getWidth(label) + 8;
            if (cx + cw > lx + leftW) {
                cx = lx;
                cy += 14;
            }
            boolean on = i == category;
            boolean hover = FlatUi.inside(mx, my, cx, cy, cw, 12);
            FlatUi.rect(c, cx, cy, cw, 12, on ? FlatUi.ACCENT_DIM : hover ? 0xFF34343C : FlatUi.PANE_RAISED);
            c.drawText(textRenderer, label, cx + 4, cy + 2, on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hits.add(new int[]{cx, cy, cw, 12, HIT_CHIP, i});
            cx += cw + 3;
        }

        // grid
        int gridY = cy + 16;
        int cols = Math.max(1, leftW / CELL);
        int rowsVisible = Math.max(1, (y + h - 18 - gridY) / CELL);
        int totalRows = (filtered.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(Math.max(0, totalRows - rowsVisible), scroll));
        FlatUi.rect(c, lx - 1, gridY - 1, cols * CELL + 2, rowsVisible * CELL + 2, FlatUi.INPUT);
        Block chosenBlock = blockOf(chosen);
        c.enableScissor(lx, gridY, lx + cols * CELL, gridY + rowsVisible * CELL);
        for (int r = 0; r < rowsVisible; r++) {
            for (int col = 0; col < cols; col++) {
                int i = (r + scroll) * cols + col;
                if (i >= filtered.size()) {
                    break;
                }
                Block block = filtered.get(i);
                int ix = lx + col * CELL, iy = gridY + r * CELL;
                if (block == chosenBlock) {
                    FlatUi.rect(c, ix, iy, CELL, CELL, FlatUi.SELECTED);
                    FlatUi.outline(c, ix, iy, CELL, CELL, FlatUi.ACCENT);
                }
                if (FlatUi.inside(mx, my, ix, iy, CELL, CELL)) {
                    FlatUi.rect(c, ix, iy, CELL, CELL, FlatUi.HOVER);
                    hovered = materialFor(block);
                }
                c.drawItem(new ItemStack(block), ix + 1, iy + 1);
                hits.add(new int[]{ix, iy, CELL, CELL, HIT_CELL, i});
            }
        }
        c.disableScissor();
        FlatUi.scrollThumb(c, lx + cols * CELL + 3, gridY, rowsVisible * CELL, totalRows * CELL, scroll * CELL);
        String foot = Text.translatable("gui.station_announcer.material.footer", filtered.size()).getString();
        c.drawText(textRenderer, textRenderer.trimToWidth(foot, leftW), lx, y + h - 12, FlatUi.TEXT_FAINT, false);

        // preview pane
        int px = x + w - previewW - 8;
        FlatUi.pane(c, px, top, previewW, h - 14);
        String shown = hovered != null ? hovered : chosen;
        ItemStack preview = MaterialPalette.stackWith(Block.getBlockFromItem(previewItem), shown);
        int size = Math.min(previewW - 20, 84);
        float scale = size / 16f;
        c.getMatrices().push();
        c.getMatrices().translate(px + (previewW - size) / 2f, top + 8, 150);
        c.getMatrices().scale(scale, scale, scale); // uniform, or the GUI item lighting's normals skew and darken
        c.drawItem(preview, 0, 0);
        c.getMatrices().pop();
        int ty = top + 12 + size;
        Text name = MaterialPalette.displayName(shown);
        for (var line : textRenderer.wrapLines(name, previewW - 10)) {
            c.drawText(textRenderer, line, px + 5, ty, hovered != null ? FlatUi.TEXT_DIM : FlatUi.TEXT, false);
            ty += 10;
        }
        ty += 2;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.material.recent").getString(), px + 5, ty);
        ty += 10;
        int rx = px + 5;
        for (int i = 0; i < recent.size(); i++) {
            if (rx + CELL > px + previewW - 4) {
                rx = px + 5;
                ty += CELL;
            }
            if (ty + CELL > top + h - 14 - (pos != null ? 44 : 26)) {
                break;
            }
            String material = recent.get(i);
            if (FlatUi.inside(mx, my, rx, ty, CELL, CELL)) {
                FlatUi.rect(c, rx, ty, CELL, CELL, FlatUi.HOVER);
                hovered = material;
            }
            c.drawItem(new ItemStack(blockOf(material)), rx + 1, ty + 1);
            hits.add(new int[]{rx, ty, CELL, CELL, HIT_RECENT, i});
            rx += CELL;
        }

        // footer buttons
        int by = top + h - 14 - 20;
        int bw = (previewW - 14) / 2;
        if (pos != null) {
            int ry = by - 16;
            FlatUi.rect(c, px + 5, ry + 1, 9, 9, wholeRun ? FlatUi.ACCENT : FlatUi.INPUT);
            FlatUi.outline(c, px + 5, ry + 1, 9, 9, FlatUi.BORDER_STRONG);
            c.drawText(textRenderer, textRenderer.trimToWidth(
                    Text.translatable("gui.station_announcer.material.whole_run").getString(), previewW - 22),
                    px + 18, ry + 2, FlatUi.TEXT_DIM, false);
            hits.add(new int[]{px + 5, ry, previewW - 10, 11, HIT_RUN, 0});
        }
        FlatUi.button(c, textRenderer, Text.translatable("gui.cancel").getString(), px + 5, by, bw, 16, mx, my,
                FlatUi.ButtonStyle.GHOST);
        hits.add(new int[]{px + 5, by, bw, 16, HIT_CANCEL, 0});
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.material.apply").getString(),
                px + 9 + bw, by, bw, 16, mx, my, FlatUi.ButtonStyle.PRIMARY);
        hits.add(new int[]{px + 9 + bw, by, bw, 16, HIT_APPLY, 0});
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (search.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] hit = hits.get(i);
            if (!FlatUi.inside(mx, my, hit[0], hit[1], hit[2], hit[3])) {
                continue;
            }
            switch (hit[4]) {
                case HIT_CELL -> {
                    if (hit[5] < filtered.size()) {
                        String material = materialFor(filtered.get(hit[5]));
                        if (material.equals(chosen) && button == 0) {
                            apply(); // clicking the chosen block again = apply
                        } else {
                            chosen = material;
                        }
                    }
                }
                case HIT_RECENT -> chosen = recent.get(hit[5]);
                case HIT_CHIP -> category = hit[5];
                case HIT_RUN -> wholeRun = !wholeRun;
                case HIT_APPLY -> apply();
                case HIT_CANCEL -> close();
                default -> {
                }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = Math.max(0, scroll - (int) Math.signum(vertical));
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return search.charTyped(chr) || super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!search.getText().isBlank() && filtered.size() == 1) {
                chosen = materialFor(filtered.get(0));
            }
            apply();
            return true;
        }
        return search.keyPressed(key, modifiers) || super.keyPressed(key, scancode, modifiers);
    }

    private void apply() {
        if (MaterialPalette.parse(chosen) == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        if (pos != null) {
            buf.writeBlockPos(pos);
            buf.writeString(chosen, MaterialPalette.MAX_MATERIAL_LENGTH);
            buf.writeBoolean(wholeRun);
            ClientPlayNetworking.send(MaterialBlocks.SET_BLOCK_MATERIAL_C2S, buf);
        } else if (hand != null && client != null && client.player != null) {
            buf.writeByte(hand == Hand.OFF_HAND ? 1 : 0);
            buf.writeString(chosen, MaterialPalette.MAX_MATERIAL_LENGTH);
            ClientPlayNetworking.send(MaterialBlocks.SET_ITEM_MATERIAL_C2S, buf);
            MaterialPalette.setMaterial(client.player.getStackInHand(hand), chosen); // instant feedback
        }
        remember(chosen);
        close();
    }

    // ---------------------------------------------------------------- recent

    private static Path recentPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("material_recent.json");
    }

    private static List<String> loadRecent() {
        List<String> out = new ArrayList<>();
        try {
            Path path = recentPath();
            if (Files.exists(path)) {
                JsonArray array = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
                array.forEach(e -> {
                    String material = e.getAsString();
                    if (MaterialPalette.parse(material) != null && out.size() < MAX_RECENT) {
                        out.add(material);
                    }
                });
            }
        } catch (Exception ignored) {
            // a broken recent list just starts empty
        }
        return out;
    }

    private void remember(String material) {
        recent.remove(material);
        recent.add(0, material);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        try {
            JsonArray array = new JsonArray();
            recent.forEach(array::add);
            Files.createDirectories(recentPath().getParent());
            Files.writeString(recentPath(), array.toString());
        } catch (Exception ignored) {
            // recents are a convenience
        }
    }
}
