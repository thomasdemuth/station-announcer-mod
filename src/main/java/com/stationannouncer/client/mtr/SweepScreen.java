package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.mtr.EarthworksBuilder;
import com.stationannouncer.mtr.Sweep;
import com.stationannouncer.mtr.SweepGeometry;
import com.stationannouncer.mtr.SweepMapper;
import com.stationannouncer.mtr.SweepSpec;
import com.stationannouncer.mtr.SweepTemplate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RailBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Structure Sweeper's screen: a side panel over the live world.
 *
 * <p>With a TRACK picked (two rail nodes clicked) the world stays visible on
 * the left with the sweep drawn on the real rails as a ghost
 * ({@link SweepGhostRenderer}); the panel fits the template to the track —
 * turn, which column / level of the template the rail runs through
 * (suggested automatically), offsets, flip, repeat mode, legs, what empty
 * cells dig — and Build places exactly the ghost. "Look around" closes the
 * panel and keeps the ghost (right-click the air with the sweeper to come
 * back); Cancel drops the track.</p>
 *
 * <p>Without a track the left side shows the template on a test track
 * (straight / curve / hill). Closing always keeps the settings.</p>
 */
@Environment(EnvType.CLIENT)
public class SweepScreen extends Screen {
    private static final int RAIL_Y = 20;
    private static final int ROW = 17;
    private static final int LIST_ROW = 13;
    private static final int MAX_PREVIEW_CELLS = 16000;

    private static final int HIT_TEMPLATE = 1, HIT_ROW = 2, HIT_DELETE = 3, HIT_TURN = 4, HIT_MINUS = 5, HIT_PLUS = 6;
    private static final int HIT_AUTO = 7, HIT_FLIP = 8, HIT_MODE = 9, HIT_LEGS = 10, HIT_AIR = 11, HIT_BUILD = 12;
    private static final int HIT_CANCEL = 13, HIT_LOOK = 14, HIT_UNDO = 15, HIT_DONE = 16, HIT_VIEW = 17, HIT_PREVIEW = 18;
    private static final int HIT_REFRESH = 19;
    private static final int NUM_RAIL_W = 0, NUM_RAIL_H = 1, NUM_SIDE = 2, NUM_HEIGHT = 3, NUM_PHASE = 4;

    private static final String[] TURNS = {"0°", "90°", "180°", "270°"};
    private static final String[] MODES = {"Repeat", "Stretch", "Once"};
    private static final String[] OFF_ON = {"Off", "On"};
    private static final String[] AIR = {"Above rail", "All", "Never"};
    private static final String[] VIEWS = {"Straight", "Curve", "Hill"};

    private final Hand hand;
    private final SweepSpec spec;
    private final List<int[]> hits = new ArrayList<>();
    private boolean listOpen;
    private String confirmDelete;
    private int scroll;
    private int contentHeight;
    private String status = "";
    private boolean sent;

    private int view = 1;
    private float yaw = 35;
    private boolean draggingPreview;
    private boolean autoTurn = true;
    private List<CreatorPreview.Cell> scene = List.of();
    private String sceneInfo = "";
    private String sceneKey = "";
    private int seenList = -1;

    private int panelX, panelY, panelW, panelH, bodyTop, bodyBottom;

    public SweepScreen(Hand hand, ItemStack stack) {
        super(Text.translatable("item.station_announcer.structure_sweeper"));
        this.hand = hand;
        SweepSpec held = SweepSpec.read(stack);
        SweepSpec last = SweepPreview.spec();
        // the panel's last edits win while a track is being fitted (the item's copy may lag a tick)
        this.spec = SweepPreview.track() != null && last != null ? last.copy() : held;
        SweepPreview.setSpec(spec);
    }

    /** Dev hook: press Build (headless rig). */
    public void devBuild() {
        click(new int[]{0, 0, 1, 1, HIT_BUILD, 0}, 0);
    }

    /** Dev hook: settings by name (turn N, railw N, railh N, side N, height N). */
    public void devSet(String key, int value) {
        switch (key) {
            case "turn" -> {
                spec.turn = value & 3;
                spec.railW = -1;
                spec.railH = -1;
            }
            case "railw" -> spec.railW = value;
            case "railh" -> spec.railH = value;
            case "side" -> spec.sideOffset = value;
            case "height" -> spec.heightOffset = value;
            default -> {
            }
        }
        spec.clamp();
    }

    /** Dev hook: which test track the preview uses (0 straight, 1 curve, 2 hill). */
    public void devView(int v) {
        view = Math.max(0, Math.min(2, v));
        autoTurn = false;
    }

    @Override
    protected void init() {
        SweepClient.requestList();
    }

    private boolean hasTrack() {
        return SweepPreview.track() != null;
    }

    private void hit(int x, int y, int w, int h, int id, int arg) {
        hits.add(new int[]{x, y, w, h, id, arg});
    }

    /** A hit inside the scrolling body: only the visible part counts. */
    private void bodyHit(int x, int y, int w, int h, int id, int arg) {
        int top = Math.max(y, bodyTop), bottom = Math.min(y + h, bodyBottom);
        if (bottom > top) {
            hit(x, top, w, bottom - top, id, arg);
        }
    }

    // =================================================================== render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        panelW = Math.max(160, Math.min(200, width * 42 / 100));
        panelX = width - panelW - 4;
        panelY = 4;
        panelH = height - 8;
        if (seenList != SweepClient.listVersion()) {
            seenList = SweepClient.listVersion();
            List<SweepClient.Entry> list = SweepClient.list();
            boolean known = false;
            for (SweepClient.Entry e : list) {
                known |= e.name().equals(spec.template);
            }
            if (!known && !list.isEmpty() && spec.template.isEmpty()) {
                spec.template = list.get(0).name();
            }
        }
        SweepPreview.setSpec(spec);
        if (hasTrack()) {
            SweepPreview.update();
            String hint = "Ghost = what Build places · Esc: look around";
            int hw = textRenderer.getWidth(hint) + 8;
            FlatUi.rect(c, 4, height - 18, hw, 14, 0xAA000000);
            c.drawText(textRenderer, hint, 8, height - 15, FlatUi.TEXT_DIM, false);
        } else {
            drawTestPreview(c, mx, my, delta);
        }
        drawPanel(c, mx, my);
    }

    private void drawPanel(DrawContext c, int mx, int my) {
        FlatUi.pane(c, panelX, panelY, panelW, panelH);
        int x = panelX + 6, w = panelW - 12;
        c.drawText(textRenderer, getTitle(), x, panelY + 6, FlatUi.TEXT, false);
        String track = hasTrack()
                ? String.format(Locale.ROOT, "Track picked: %.0f blocks", SweepPreview.track().length())
                : "No track — click two rail nodes";
        c.drawText(textRenderer, textRenderer.trimToWidth(track, w), x, panelY + 17, hasTrack() ? FlatUi.OK : FlatUi.TEXT_FAINT, false);
        bodyTop = panelY + 29;
        bodyBottom = panelY + panelH - 26;
        FlatUi.rect(c, panelX + 1, bodyTop - 1, panelW - 2, 1, FlatUi.BORDER);

        SweepTemplate t = SweepClient.template(spec.template);
        SweepMapper.Fit fit = t == null ? null : SweepMapper.fit(t, spec);

        c.enableScissor(panelX + 1, bodyTop, panelX + panelW - 1, bodyBottom);
        int y = bodyTop + 4 - scroll;
        int hmx = my >= bodyTop && my < bodyBottom ? mx : -1;
        int hmy = hmx < 0 ? -1 : my;

        // ---------------------------------------------------------- template
        FlatUi.heading(c, textRenderer, "Template", x, y);
        FlatUi.iconButton(c, textRenderer, "⟳", x + w - 12, y - 3, 12, hmx, hmy, FlatUi.TEXT_DIM);
        bodyHit(x + w - 12, y - 3, 12, 12, HIT_REFRESH, 0);
        y += 11;
        SweepClient.Entry current = null;
        for (SweepClient.Entry e : SweepClient.list()) {
            if (e.name().equals(spec.template)) {
                current = e;
            }
        }
        String label = current == null ? (SweepClient.list().isEmpty() ? "None yet — use the Template Wand" : "Pick a template")
                : current.name() + "  " + current.length() + "×" + current.width() + "×" + current.height();
        boolean hov = FlatUi.inside(hmx, hmy, x, y, w, 16);
        FlatUi.rect(c, x, y, w, 16, hov ? 0xFF34343C : FlatUi.INPUT);
        FlatUi.outline(c, x, y, w, 16, listOpen ? FlatUi.ACCENT : FlatUi.BORDER_STRONG);
        c.drawText(textRenderer, textRenderer.trimToWidth(label, w - 16), x + 4, y + 4, current == null ? FlatUi.TEXT_FAINT : FlatUi.TEXT, false);
        c.drawText(textRenderer, listOpen ? "▴" : "▾", x + w - 10, y + 4, FlatUi.TEXT_DIM, false);
        bodyHit(x, y, w, 16, HIT_TEMPLATE, 0);
        y += 18;
        if (listOpen) {
            List<SweepClient.Entry> list = SweepClient.list();
            for (int i = 0; i < list.size(); i++) {
                SweepClient.Entry e = list.get(i);
                boolean sel = e.name().equals(spec.template);
                boolean rowHov = FlatUi.inside(hmx, hmy, x, y, w, LIST_ROW);
                if (sel || rowHov) {
                    FlatUi.rect(c, x, y, w, LIST_ROW, sel ? FlatUi.SELECTED : FlatUi.HOVER);
                }
                String dims = e.length() + "×" + e.width() + "×" + e.height();
                int dw = textRenderer.getWidth(dims);
                c.drawText(textRenderer, textRenderer.trimToWidth(e.name(), w - dw - 26), x + 3, y + 3, FlatUi.TEXT, false);
                c.drawText(textRenderer, dims, x + w - dw - 16, y + 3, FlatUi.TEXT_FAINT, false);
                bodyHit(x, y, w - 14, LIST_ROW, HIT_ROW, i);
                if (e.name().equals(confirmDelete)) {
                    FlatUi.button(c, textRenderer, "Delete?", x + w - 46, y, 46, LIST_ROW, hmx, hmy, ButtonStyle.DANGER);
                    bodyHit(x + w - 46, y, 46, LIST_ROW, HIT_DELETE, i);
                } else {
                    FlatUi.iconButton(c, textRenderer, "×", x + w - 13, y, LIST_ROW, hmx, hmy, FlatUi.TEXT_FAINT);
                    bodyHit(x + w - 13, y, 13, LIST_ROW, HIT_DELETE, i);
                }
                y += LIST_ROW;
            }
            y += 4;
        }

        // ------------------------------------------------------------- fit
        y += 4;
        FlatUi.heading(c, textRenderer, "Fit to the track", x, y);
        y += 11;
        FlatUi.label(c, textRenderer, "Turn", x, y + 4);
        int segX = x + 46;
        FlatUi.segmented(c, textRenderer, TURNS, spec.turn, segX, y, w - 46, 15, hmx, hmy);
        bodyHit(segX, y, w - 46, 15, HIT_TURN, 0);
        y += ROW;
        y = number(c, hmx, hmy, x, y, w, "Rail column", spec.railW, fit == null ? -1 : fit.railW(), NUM_RAIL_W);
        y = number(c, hmx, hmy, x, y, w, "Rail level", spec.railH, fit == null ? -1 : fit.railH(), NUM_RAIL_H);
        y = number(c, hmx, hmy, x, y, w, "Side offset", spec.sideOffset, Integer.MIN_VALUE, NUM_SIDE);
        y = number(c, hmx, hmy, x, y, w, "Height offset", spec.heightOffset, Integer.MIN_VALUE, NUM_HEIGHT);
        y = toggle(c, hmx, hmy, x, y, w, "Flip sides", spec.flip, HIT_FLIP);

        // ---------------------------------------------------------- length
        y += 4;
        FlatUi.heading(c, textRenderer, "Length on the track", x, y);
        y += 11;
        FlatUi.segmented(c, textRenderer, MODES, spec.mode.ordinal(), x, y, w, 15, hmx, hmy);
        bodyHit(x, y, w, 15, HIT_MODE, 0);
        y += ROW;
        if (spec.mode == SweepGeometry.Mode.REPEAT) {
            y = number(c, hmx, hmy, x, y, w, "Start phase", spec.phase, Integer.MIN_VALUE, NUM_PHASE);
        }

        // ----------------------------------------------------------- world
        y += 4;
        FlatUi.heading(c, textRenderer, "In the world", x, y);
        y += 11;
        y = toggle(c, hmx, hmy, x, y, w, "Legs to ground", spec.legs, HIT_LEGS);
        FlatUi.label(c, textRenderer, "Empty cells dig", x, y + 1);
        y += 11;
        FlatUi.segmented(c, textRenderer, AIR, spec.air.ordinal(), x, y, w, 15, hmx, hmy);
        bodyHit(x, y, w, 15, HIT_AIR, 0);
        y += ROW + 2;

        String info = hasTrack() ? SweepPreview.summary() : sceneInfo;
        for (String line : wrap(info, w)) {
            c.drawText(textRenderer, line, x, y, FlatUi.TEXT_DIM, false);
            y += 10;
        }
        y += 4;
        contentHeight = y + scroll - bodyTop;
        c.disableScissor();
        int viewH = bodyBottom - bodyTop;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewH)));
        FlatUi.scrollThumb(c, panelX + panelW - 2, bodyTop, viewH, contentHeight, scroll);

        // ---------------------------------------------------------- footer
        FlatUi.rect(c, panelX + 1, bodyBottom, panelW - 2, 1, FlatUi.BORDER);
        int by = panelY + panelH - 22;
        if (hasTrack()) {
            int bw = (w - 8) / 3;
            boolean canBuild = t != null && !SweepPreview.ghost().isEmpty();
            FlatUi.button(c, textRenderer, "Look", x, by, bw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
            hit(x, by, bw, FlatUi.BUTTON_HEIGHT, HIT_LOOK, 0);
            FlatUi.button(c, textRenderer, "Cancel", x + bw + 4, by, bw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
            hit(x + bw + 4, by, bw, FlatUi.BUTTON_HEIGHT, HIT_CANCEL, 0);
            FlatUi.button(c, textRenderer, "Build", x + 2 * (bw + 4), by, w - 2 * (bw + 4), FlatUi.BUTTON_HEIGHT, mx, my,
                    ButtonStyle.PRIMARY, canBuild);
            if (canBuild) {
                hit(x + 2 * (bw + 4), by, w - 2 * (bw + 4), FlatUi.BUTTON_HEIGHT, HIT_BUILD, 0);
            }
        } else {
            int bw = (w - 4) / 2;
            FlatUi.button(c, textRenderer, "Undo last build", x, by, bw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
            hit(x, by, bw, FlatUi.BUTTON_HEIGHT, HIT_UNDO, 0);
            FlatUi.button(c, textRenderer, "Done", x + bw + 4, by, w - bw - 4, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY);
            hit(x + bw + 4, by, w - bw - 4, FlatUi.BUTTON_HEIGHT, HIT_DONE, 0);
        }
        if (!status.isEmpty()) {
            int sw = textRenderer.getWidth(status) + 8;
            FlatUi.rect(c, panelX - sw - 4, panelY + panelH - 18, sw, 14, 0xCC000000);
            c.drawText(textRenderer, status, panelX - sw, panelY + panelH - 15, FlatUi.TEXT, false);
        }
    }

    /** A "label  [−] value [+] [A]" row; {@code auto} ≥ 0 means the value can be Auto (spec −1). */
    private int number(DrawContext c, int mx, int my, int x, int y, int w, String label, int value, int auto, int which) {
        boolean autoable = auto != Integer.MIN_VALUE;
        FlatUi.label(c, textRenderer, label, x, y + 4);
        int bx = x + w - (autoable ? 80 : 62);
        FlatUi.button(c, textRenderer, "−", bx, y, 15, 15, mx, my, ButtonStyle.FLAT);
        bodyHit(bx, y, 15, 15, HIT_MINUS, which);
        String v = autoable && value < 0 ? (auto >= 0 ? String.valueOf(auto) : "–") : String.valueOf(value);
        int vw = textRenderer.getWidth(v);
        c.drawText(textRenderer, v, bx + 15 + (32 - vw) / 2, y + 4, autoable && value < 0 ? FlatUi.TEXT_DIM : FlatUi.TEXT, false);
        FlatUi.button(c, textRenderer, "+", bx + 47, y, 15, 15, mx, my, ButtonStyle.FLAT);
        bodyHit(bx + 47, y, 15, 15, HIT_PLUS, which);
        if (autoable) {
            FlatUi.button(c, textRenderer, "A", bx + 65, y, 15, 15, mx, my, value < 0 ? ButtonStyle.PRIMARY : ButtonStyle.GHOST);
            bodyHit(bx + 65, y, 15, 15, HIT_AUTO, which);
        }
        return y + ROW;
    }

    private int toggle(DrawContext c, int mx, int my, int x, int y, int w, String label, boolean on, int id) {
        FlatUi.label(c, textRenderer, label, x, y + 4);
        int sw = 54;
        FlatUi.segmented(c, textRenderer, OFF_ON, on ? 1 : 0, x + w - sw, y, sw, 15, mx, my);
        bodyHit(x + w - sw, y, sw, 15, id, 0);
        return y + ROW;
    }

    private List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (textRenderer.getWidth(next) > w && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    // ============================================================= test preview

    private void drawTestPreview(DrawContext c, int mx, int my, float delta) {
        int ax = 4, ay = 4, aw = panelX - 8, ah = height - 8;
        FlatUi.rect(c, ax, ay, aw, ah, 0xE6151518);
        FlatUi.outline(c, ax, ay, aw, ah, FlatUi.BORDER);
        int segW = Math.min(180, aw - 12);
        int segX = ax + (aw - segW) / 2;
        FlatUi.segmented(c, textRenderer, VIEWS, view, segX, ay + 5, segW, 15, mx, my);
        hit(segX, ay + 5, segW, 15, HIT_VIEW, 0);
        int px = ax + 1, py = ay + 24, pw = aw - 2, ph = ah - 40;
        hit(px, py, pw, ph, HIT_PREVIEW, 0);
        rebuildScene();
        if (autoTurn && !draggingPreview) {
            yaw += delta * 0.4f;
        }
        if (!scene.isEmpty()) {
            CreatorPreview.render(c, px, py, pw, ph, scene, yaw);
        } else {
            String msg = spec.template.isEmpty() ? "No template picked"
                    : SweepClient.unavailable(spec.template) ? "Too big to preview" : "Loading template…";
            c.drawText(textRenderer, msg, ax + (aw - textRenderer.getWidth(msg)) / 2, py + ph / 2 - 4, FlatUi.TEXT_FAINT, false);
        }
        String hint = "Test track · click two rail nodes with the sweeper to fit it to a real one";
        c.drawText(textRenderer, textRenderer.trimToWidth(hint, aw - 12), ax + 6, ay + ah - 12, FlatUi.TEXT_FAINT, false);
    }

    /** Generated ground under the test track. */
    private record PreviewGround(int groundY) implements EarthworksBuilder.View {
        @Override
        public BlockState get(BlockPos pos) {
            if (pos.getY() > groundY) {
                return Blocks.AIR.getDefaultState();
            }
            return pos.getY() == groundY ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
        }

        @Override
        public int bottomY() {
            return 0;
        }

        @Override
        public int topY() {
            return 200;
        }

        @Override
        public int surface(int x, int z) {
            return groundY;
        }
    }

    private SweepGeometry.PathFn testPath(SweepTemplate t) {
        switch (view) {
            case 0 -> {
                double len = Math.max(16, Math.min(48, t.length * 2.0));
                return new SweepGeometry.Line(0.5, RAIL_Y, 0.5, 0.5 + len, RAIL_Y, 0.5);
            }
            case 2 -> {
                return new SweepGeometry.Line(0.5, RAIL_Y, 0.5, 40.5, RAIL_Y + 6, 0.5);
            }
            default -> {
                double r = Math.max(14, t.width + 8);
                double cx = 0.5, cz = 0.5 + r;
                return new SweepGeometry.PathFn() {
                    @Override
                    public double length() {
                        return r * Math.PI / 2;
                    }

                    @Override
                    public double[] at(double d) {
                        double a = -Math.PI / 2 + Math.max(0, Math.min(length(), d)) / r;
                        return new double[]{cx + r * Math.cos(a), RAIL_Y, cz + r * Math.sin(a)};
                    }
                };
            }
        }
    }

    private void rebuildScene() {
        SweepTemplate t = SweepClient.template(spec.template);
        String key = spec.toNbt() + "|" + view + "|" + SweepClient.templateVersion() + "|" + (t != null);
        if (key.equals(sceneKey)) {
            return;
        }
        sceneKey = key;
        scene = List.of();
        sceneInfo = "";
        if (t == null) {
            return;
        }
        SweepGeometry.PathFn path = testPath(t);
        SweepMapper.Fit fit = SweepMapper.fit(t, spec);
        SweepMapper.Result r;
        try {
            r = SweepMapper.plan(new PreviewGround(RAIL_Y - fit.railH() + spec.heightOffset - 4), path, t, spec.copy());
        } catch (RuntimeException e) {
            sceneInfo = "Preview failed";
            return;
        }
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : r.changes().entrySet()) {
            if (!e.getValue().isAir()) {
                cells.put(e.getKey(), e.getValue());
            }
        }
        for (double d = 0; d <= path.length(); d += 0.5) {
            double[] p = path.at(d);
            double[] q = path.at(Math.min(path.length(), d + 0.5));
            BlockPos pos = BlockPos.ofFloored(p[0], p[1], p[2]);
            if (!cells.containsKey(pos)) {
                boolean alongX = Math.abs(q[0] - p[0]) >= Math.abs(q[2] - p[2]);
                cells.put(pos, Blocks.RAIL.getDefaultState().with(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH));
            }
        }
        List<CreatorPreview.Cell> list = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
            if (!buried(cells, e.getKey())) {
                list.add(new CreatorPreview.Cell(e.getKey(), e.getValue()));
                if (list.size() >= MAX_PREVIEW_CELLS) {
                    break;
                }
            }
        }
        scene = list;
        StringBuilder info = new StringBuilder();
        info.append(r.copies()).append(r.copies() == 1 ? " copy" : " copies");
        if (spec.mode != SweepGeometry.Mode.REPEAT) {
            info.append(" · ").append(Math.round(r.stretch() * 100)).append("% long");
        }
        info.append(" · ").append(r.placed() + r.legs()).append(" blocks on the test track");
        sceneInfo = info.toString();
    }

    private static boolean buried(Map<BlockPos, BlockState> cells, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState n = cells.get(p.offset(d));
            if (n == null || !n.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                return false;
            }
        }
        return true;
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] h = hits.get(i);
            if (FlatUi.inside(mx, my, h[0], h[1], h[2], h[3])) {
                click(h, mx);
                return true;
            }
        }
        confirmDelete = null;
        return super.mouseClicked(mx, my, button);
    }

    private void click(int[] h, double mx) {
        int id = h[4], arg = h[5];
        boolean shift = hasShiftDown();
        if (id != HIT_DELETE) {
            confirmDelete = null;
        }
        SweepTemplate t = SweepClient.template(spec.template);
        SweepMapper.Fit fit = t == null ? null : SweepMapper.fit(t, spec);
        switch (id) {
            case HIT_TEMPLATE -> listOpen = !listOpen;
            case HIT_REFRESH -> {
                SweepClient.forget(spec.template);
                SweepClient.requestList();
            }
            case HIT_ROW -> {
                List<SweepClient.Entry> list = SweepClient.list();
                if (arg < list.size()) {
                    if (!list.get(arg).name().equals(spec.template)) {
                        spec.template = list.get(arg).name();
                        spec.resetFit();
                    }
                    listOpen = false;
                }
            }
            case HIT_DELETE -> {
                List<SweepClient.Entry> list = SweepClient.list();
                if (arg < list.size()) {
                    String name = list.get(arg).name();
                    if (name.equals(confirmDelete)) {
                        PacketByteBuf buf = PacketByteBufs.create();
                        buf.writeByte(Sweep.ACTION_DELETE);
                        buf.writeString(name);
                        ClientPlayNetworking.send(Sweep.ACTION_C2S, buf);
                        SweepClient.forget(name);
                        if (name.equals(spec.template)) {
                            spec.template = "";
                        }
                        confirmDelete = null;
                        status = "Deleted " + name;
                    } else {
                        confirmDelete = name;
                    }
                }
            }
            case HIT_TURN -> {
                int turn = segment(h, mx, TURNS.length);
                if (turn != spec.turn) {
                    spec.turn = turn;
                    // the rail column / level belong to one orientation: suggest again
                    spec.railW = -1;
                    spec.railH = -1;
                }
            }
            case HIT_MODE -> spec.mode = SweepGeometry.Mode.values()[segment(h, mx, MODES.length)];
            case HIT_FLIP -> spec.flip = segment(h, mx, 2) == 1;
            case HIT_LEGS -> spec.legs = segment(h, mx, 2) == 1;
            case HIT_AIR -> spec.air = SweepSpec.AirMode.values()[segment(h, mx, AIR.length)];
            case HIT_AUTO -> {
                if (arg == NUM_RAIL_W) {
                    spec.railW = -1;
                } else {
                    spec.railH = -1;
                }
            }
            case HIT_MINUS, HIT_PLUS -> {
                int step = (shift ? 5 : 1) * (id == HIT_MINUS ? -1 : 1);
                switch (arg) {
                    case NUM_RAIL_W -> {
                        int base = spec.railW >= 0 ? spec.railW : fit == null ? 0 : fit.railW();
                        int max = fit == null ? SweepTemplate.MAX_WIDTH - 1 : fit.template().width - 1;
                        spec.railW = Math.max(0, Math.min(max, base + step));
                    }
                    case NUM_RAIL_H -> {
                        int base = spec.railH >= 0 ? spec.railH : fit == null ? 0 : fit.railH();
                        int max = fit == null ? SweepTemplate.MAX_HEIGHT : fit.template().height;
                        spec.railH = Math.max(0, Math.min(max, base + step));
                    }
                    case NUM_SIDE -> spec.sideOffset += step;
                    case NUM_HEIGHT -> spec.heightOffset += step;
                    default -> {
                        int len = fit == null ? SweepTemplate.MAX_LENGTH : fit.template().length;
                        spec.phase = Math.floorMod(spec.phase + step, len);
                    }
                }
                spec.clamp();
            }
            case HIT_BUILD -> {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBoolean(hand == Hand.OFF_HAND);
                spec.clamp();
                buf.writeNbt(spec.toNbt());
                ClientPlayNetworking.send(Sweep.BUILD_C2S, buf);
                SweepPreview.clearTrack();
                sent = true;
                close();
            }
            case HIT_CANCEL -> {
                ClientPlayNetworking.send(Sweep.CANCEL_C2S, PacketByteBufs.create());
                SweepPreview.clearTrack();
                status = "Track dropped";
            }
            case HIT_LOOK, HIT_DONE -> close();
            case HIT_UNDO -> {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeByte(Sweep.ACTION_UNDO);
                buf.writeString("");
                ClientPlayNetworking.send(Sweep.ACTION_C2S, buf);
                status = "Undoing your last build…";
            }
            case HIT_VIEW -> view = segment(h, mx, VIEWS.length);
            case HIT_PREVIEW -> {
                draggingPreview = true;
                autoTurn = false;
            }
            default -> {
            }
        }
    }

    private static int segment(int[] h, double mx, int n) {
        return Math.max(0, Math.min(n - 1, (int) ((mx - h[0]) * n / h[2])));
    }

    /** Settings always go back into the held tool when the panel closes. */
    private void save() {
        if (sent) {
            return;
        }
        sent = true;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        spec.clamp();
        buf.writeNbt(spec.toNbt());
        ClientPlayNetworking.send(Sweep.UPDATE_C2S, buf);
    }

    @Override
    public void close() {
        save();
        super.close();
    }

    @Override
    public void removed() {
        save();
        super.removed();
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingPreview) {
            yaw += (float) dx * 1.5f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingPreview = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (FlatUi.inside(mx, my, panelX, bodyTop, panelW, bodyBottom - bodyTop)) {
            scroll -= (int) (vertical * 14);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
