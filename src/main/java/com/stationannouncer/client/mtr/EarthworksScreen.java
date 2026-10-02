package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.mtr.BridgeSpec;
import com.stationannouncer.mtr.Earthworks;
import com.stationannouncer.mtr.EarthworksPalette;
import com.stationannouncer.mtr.EarthworksPresets;
import com.stationannouncer.mtr.EarthworksSpec;
import com.stationannouncer.mtr.EarthworksSpec.Kind;
import com.stationannouncer.mtr.EarthworksSpec.Layer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings screen of the Embankment Creator, Trench Creator and ROW Clearer,
 * on {@link FlatUi} and laid out like the Bridge Creator's: the parts of the
 * selected tool on the left, a live 3D preview in the middle (the real
 * {@link com.stationannouncer.mtr.EarthworksBuilder} over generated terrain —
 * drag to turn, After / Before), and the inspector on the right. Every
 * layer is a weighted block mix: blocks are added from the block grid or by
 * sneak-clicking the world (replace or add), weights set with − / +.
 */
@Environment(EnvType.CLIENT)
public class EarthworksScreen extends Screen {
    private enum Part {
        TRACKS("Tracks"), BED("Track bed"), SLOPES("Slopes"), WALLS("Retaining walls"),
        BENCHES("Benches & blending"), CORRIDOR("Corridor"), REMOVE("What to remove");

        final String label;

        Part(String label) {
            this.label = label;
        }

        boolean usedBy(Kind kind) {
            return switch (this) {
                case TRACKS -> true;
                case BED, SLOPES, WALLS, BENCHES -> kind != Kind.CLEARER;
                case CORRIDOR, REMOVE -> kind == Kind.CLEARER;
            };
        }
    }

    private static final int MARGIN = 6;
    private static final int TOP = 24;
    private static final int BOTTOM = 26;
    private static final int FIELD = 14;

    private static final int HIT_PART = 1, HIT_SEGMENT = 2, HIT_SLIDER = 3, HIT_ADD = 4, HIT_PICK = 5;
    private static final int HIT_W_MINUS = 6, HIT_W_PLUS = 7, HIT_W_REMOVE = 8, HIT_CLEAR_LAYER = 9;
    private static final int HIT_PRESET_MENU = 10, HIT_PRESET_ROW = 11, HIT_PRESET_DELETE = 12, HIT_PRESET_SAVE = 13;
    private static final int HIT_SAVE = 14, HIT_CANCEL = 15, HIT_UNDO = 16, HIT_POPUP_ITEM = 17, HIT_POPUP = 18;
    private static final int HIT_PROMPT_OK = 19, HIT_PROMPT_CANCEL = 20, HIT_PREVIEW = 21, HIT_MENU = 22, HIT_VIEW = 23;

    private static final int SEG_TRACK_MODE = 1, SEG_SLOPE = 2, SEG_WALL = 3, SEG_BENCHES = 4, SEG_PROTECT = 5;
    private static final int SEG_FELL = 6, SEG_TREES = 7, SEG_PLANTS = 8, SEG_SNOW = 9, SEG_TERRAIN = 10, SEG_PICK_MODE = 11;

    private static final int NUM_REACH = 1, NUM_PREVIEW_TRACKS = 2, NUM_SHOULDER = 3, NUM_TOP = 4, NUM_MAX_HEIGHT = 5;
    private static final int NUM_MAX_DEPTH = 6, NUM_DITCH_W = 7, NUM_DITCH_D = 8, NUM_RUN = 9, NUM_SURFACE = 10;
    private static final int NUM_TRIGGER = 11, NUM_WALL_T = 12, NUM_PARAPET = 13, NUM_BENCH_EVERY = 14, NUM_BENCH_W = 15;
    private static final int NUM_BLEND = 16, NUM_CORRIDOR = 17, NUM_CLEAR_H = 18, NUM_CLEAR_BELOW = 19;
    private static final int NUM_TERRAIN_W = 20, NUM_TERRAIN_H = 21;

    private final Hand hand;
    private final EarthworksSpec spec;
    private final List<Part> parts = new ArrayList<>();
    private Part selected;

    private final List<int[]> hits = new ArrayList<>();
    private final Map<Integer, TextBox> numberBoxes = new HashMap<>();
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    private TextBox focused;
    private int[] dragSlider;
    private float dragMin, dragMax;

    private boolean presetMenuOpen;
    private Layer popupLayer;
    private TextBox searchBox;
    private int popupScroll;
    private List<Block> candidates;
    private List<Block> filtered = List.of();
    private String lastFilter;
    private boolean promptOpen;
    private TextBox nameBox;
    private String hoverName;
    private int inspectorScroll;
    private int inspectorContent;
    private String status = "";

    private List<CreatorPreview.Cell> scene = List.of();
    private boolean sceneDirty = true;
    private boolean showAfter = true;
    private float yaw = 35;
    private boolean draggingPreview;

    private int leftX, leftW, midX, midW, rightX, rightW, paneY, paneH;

    public EarthworksScreen(Hand hand, ItemStack stack, Kind kind) {
        super(Text.translatable("item.station_announcer." + com.stationannouncer.mtr.EarthworksService.itemId(kind)));
        this.hand = hand;
        this.spec = EarthworksSpec.read(kind, stack);
        for (Part part : Part.values()) {
            if (part.usedBy(kind)) {
                parts.add(part);
            }
        }
        selected = parts.size() > 1 ? parts.get(1) : parts.get(0);
    }

    /** Dev hook: show the terrain before the build. */
    public void showBefore() {
        showAfter = false;
        sceneDirty = true;
    }

    /** Dev hook: select a part by name (headless screenshots). */
    public void select(String name) {
        for (Part part : parts) {
            if (part.name().equalsIgnoreCase(name) || part.label.equalsIgnoreCase(name)) {
                selected = part;
            }
        }
    }

    // --------------------------------------------------------------- layout

    private void layout() {
        boolean narrow = width < 560;
        leftW = narrow ? 92 : 116;
        rightW = narrow ? 166 : 200;
        leftX = MARGIN;
        rightX = width - MARGIN - rightW;
        midX = leftX + leftW + MARGIN;
        midW = rightX - MARGIN - midX;
        paneY = TOP;
        paneH = height - TOP - BOTTOM;
    }

    private void hit(int x, int y, int w, int h, int id, int arg, int arg2) {
        hits.add(new int[]{x, y, w, h, id, arg, arg2});
    }

    // --------------------------------------------------------------- render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        layout();
        hits.clear();
        visibleBoxes.clear();
        hoverName = null;
        int hmx = (presetMenuOpen || popupLayer != null || promptOpen) ? -1 : mx;
        int hmy = hmx < 0 ? -1 : my;
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        drawTopBar(c, hmx, hmy);
        drawParts(c, hmx, hmy);
        drawPreview(c, hmx, hmy, delta);
        drawInspector(c, hmx, hmy);
        drawBottomBar(c, hmx, hmy);
        if (presetMenuOpen) {
            drawPresetMenu(c, mx, my);
        }
        if (popupLayer != null) {
            drawMaterialPopup(c, mx, my);
        }
        if (promptOpen) {
            drawPrompt(c, mx, my);
        }
        if (hoverName != null) {
            c.drawTooltip(textRenderer, Text.literal(hoverName), mx, my);
        }
    }

    private String subtitle() {
        return switch (spec.kind) {
            case EMBANKMENT -> "fills under the track bed and slopes down to the ground";
            case TRENCH -> "cuts the track bed out and slopes up to the surface";
            case CLEARER -> "clears trees, plants and snow along the right-of-way";
        };
    }

    private void drawTopBar(DrawContext c, int mx, int my) {
        String title = getTitle().getString();
        c.drawText(textRenderer, title, MARGIN, 8, FlatUi.TEXT, false);
        int titleW = textRenderer.getWidth(title);
        int bw = 82;
        int x = width - MARGIN - bw;
        int room = x - 66 - 8 - (MARGIN + titleW + 8);
        if (room > 40) {
            c.drawText(textRenderer, textRenderer.trimToWidth(subtitle(), room), MARGIN + titleW + 8, 8, FlatUi.TEXT_FAINT, false);
        }
        FlatUi.button(c, textRenderer, "Presets  ▾", x, 3, bw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        hit(x, 3, bw, FlatUi.BUTTON_HEIGHT, HIT_PRESET_MENU, 0, 0);
        int sw = 62;
        x -= sw + 4;
        FlatUi.button(c, textRenderer, "Save as…", x, 3, sw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(x, 3, sw, FlatUi.BUTTON_HEIGHT, HIT_PRESET_SAVE, 0, 0);
    }

    private String partSummary(Part part) {
        return switch (part) {
            case TRACKS -> spec.trackMode == EarthworksSpec.TrackMode.AUTO ? "auto, reach " + spec.trackReach : "single";
            case BED -> spec.kind == Kind.EMBANKMENT
                    ? "shoulder " + spec.shoulder + ", max " + spec.maxHeight + " high"
                    : "shoulder " + spec.shoulder + (spec.ditchWidth > 0 ? ", ditch " + spec.ditchWidth : "") + ", max " + spec.maxDepth;
            case SLOPES -> spec.slopeLabel() + " · " + shortMix(spec.kind == Kind.EMBANKMENT ? Layer.SLOPE : Layer.FACE);
            case WALLS -> switch (spec.wallMode) {
                case NEVER -> "never";
                case AUTO -> "wider than " + spec.wallTrigger;
                case ALWAYS -> "always";
            };
            case BENCHES -> (spec.benches ? "every " + spec.benchEvery : "no benches") + ", blend " + spec.blend;
            case CORRIDOR -> "±" + spec.corridor + ", " + spec.clearHeight + " high";
            case REMOVE -> {
                List<String> on = new ArrayList<>();
                if (spec.trees) on.add("trees");
                if (spec.plants) on.add("plants");
                if (spec.snow) on.add("snow");
                if (spec.terrain) on.add("terrain");
                yield on.isEmpty() ? "nothing" : String.join(", ", on);
            }
        };
    }

    private void drawParts(DrawContext c, int mx, int my) {
        FlatUi.pane(c, leftX, paneY, leftW, paneH);
        FlatUi.heading(c, textRenderer, "Parts", leftX + 6, paneY + 5);
        int y = paneY + 16;
        int cardH = 24;
        for (Part part : parts) {
            boolean sel = part == selected;
            if (sel) {
                FlatUi.rect(c, leftX + 1, y, leftW - 2, cardH, FlatUi.SELECTED);
            } else if (FlatUi.inside(mx, my, leftX + 1, y, leftW - 2, cardH)) {
                FlatUi.rect(c, leftX + 1, y, leftW - 2, cardH, FlatUi.HOVER);
            }
            c.drawText(textRenderer, textRenderer.trimToWidth(part.label, leftW - 12), leftX + 6, y + 3, sel ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, textRenderer.trimToWidth(partSummary(part), leftW - 12), leftX + 6, y + 13, FlatUi.TEXT_FAINT, false);
            hit(leftX + 1, y, leftW - 2, cardH, HIT_PART, part.ordinal(), 0);
            y += cardH + 1;
        }
    }

    private void drawPreview(DrawContext c, int mx, int my, float delta) {
        FlatUi.pane(c, midX, paneY, midW, paneH);
        FlatUi.heading(c, textRenderer, "Preview", midX + 6, paneY + 5);
        int sw = 80;
        int sx = midX + midW - sw - 4;
        FlatUi.segmented(c, textRenderer, new String[]{"After", "Before"}, showAfter ? 0 : 1, sx, paneY + 2, sw, 11, mx, my);
        hit(sx, paneY + 2, sw / 2, 11, HIT_VIEW, 1, 0);
        hit(sx + sw / 2, paneY + 2, sw - sw / 2, 11, HIT_VIEW, 0, 0);
        if (sceneDirty) {
            sceneDirty = false;
            scene = EarthworksPreview.scene(spec, showAfter);
        }
        if (!draggingPreview) {
            yaw += delta * 0.3f;
        }
        int px = midX + 2, py = paneY + 15, pw = midW - 4, ph = paneH - 27;
        if (pw > 10 && ph > 10) {
            CreatorPreview.render(c, px, py, pw, ph, scene, yaw);
        }
        hit(px, py, pw, ph, HIT_PREVIEW, 0, 0);
        String hint = switch (spec.kind) {
            case EMBANKMENT -> "a valley";
            case TRENCH -> "a wooded hill";
            case CLEARER -> "a meadow with trees";
        } + " · drag to turn";
        c.drawText(textRenderer, hint, midX + 6, paneY + paneH - 10, FlatUi.TEXT_FAINT, false);
    }

    private void drawBottomBar(DrawContext c, int mx, int my) {
        int y = height - BOTTOM + 4;
        int x = MARGIN;
        FlatUi.button(c, textRenderer, "Undo last build", x, y, 84, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(x, y, 84, FlatUi.BUTTON_HEIGHT, HIT_UNDO, 0, 0);
        x += 90;
        String hint = status.isEmpty() ? "Save, then click two rail nodes to build" : status;
        c.drawText(textRenderer, textRenderer.trimToWidth(hint, rightX - x - 20), x, y + 5, FlatUi.TEXT_DIM, false);
        int saveW = 54, cancelW = 50;
        int sx = width - MARGIN - saveW;
        int cx = sx - 4 - cancelW;
        FlatUi.button(c, textRenderer, "Cancel", cx, y, cancelW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(cx, y, cancelW, FlatUi.BUTTON_HEIGHT, HIT_CANCEL, 0, 0);
        FlatUi.button(c, textRenderer, "Save", sx, y, saveW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY);
        hit(sx, y, saveW, FlatUi.BUTTON_HEIGHT, HIT_SAVE, 0, 0);
    }

    // ------------------------------------------------------------ inspector

    private void drawInspector(DrawContext c, int mx, int my) {
        FlatUi.pane(c, rightX, paneY, rightW, paneH);
        FlatUi.heading(c, textRenderer, selected.label, rightX + 6, paneY + 5);
        int x = rightX + 6;
        int w = rightW - 12;
        int top = paneY + 16;
        int viewH = paneH - 18;
        int maxScroll = Math.max(0, inspectorContent - viewH);
        inspectorScroll = Math.max(0, Math.min(maxScroll, inspectorScroll));
        c.enableScissor(rightX + 1, top, rightX + rightW - 1, top + viewH);
        int y = top - inspectorScroll;
        int start = y;
        y = switch (selected) {
            case TRACKS -> inspectTracks(c, mx, my, x, y, w);
            case BED -> inspectBed(c, mx, my, x, y, w);
            case SLOPES -> inspectSlopes(c, mx, my, x, y, w);
            case WALLS -> inspectWalls(c, mx, my, x, y, w);
            case BENCHES -> inspectBenches(c, mx, my, x, y, w);
            case CORRIDOR -> inspectCorridor(c, mx, my, x, y, w);
            case REMOVE -> inspectRemove(c, mx, my, x, y, w);
        };
        inspectorContent = y - start + 4;
        c.disableScissor();
        FlatUi.scrollThumb(c, rightX + rightW, top, viewH, inspectorContent, inspectorScroll);
    }

    private int inspectTracks(DrawContext c, int mx, int my, int x, int y, int w) {
        y = segment(c, mx, my, "Which tracks", SEG_TRACK_MODE, new String[]{"Auto", "Single"}, spec.trackMode.ordinal(), x, y, w);
        if (spec.trackMode == EarthworksSpec.TrackMode.AUTO) {
            y = note(c, "Click two nodes of one track; every parallel track of the line within reach is included.", x, y, w);
            y = numberField(c, mx, my, "Reach (blocks sideways)", NUM_REACH, BridgeSpec.MIN_REACH, BridgeSpec.MAX_REACH, x, y, w);
        } else {
            y = note(c, "Click two nodes; only that track.", x, y, w);
        }
        y = numberField(c, mx, my, "Tracks in the preview", NUM_PREVIEW_TRACKS, 1, EarthworksSpec.MAX_PREVIEW_TRACKS, x, y, w);
        y = note(c, "Also: /earthworks line <from> <to> works along a straight line with no rail.", x, y, w);
        return y;
    }

    private int inspectBed(DrawContext c, int mx, int my, int x, int y, int w) {
        y = numberField(c, mx, my, "Shoulder past the outer tracks", NUM_SHOULDER, 0, EarthworksSpec.MAX_SHOULDER, x, y, w);
        if (spec.kind == Kind.EMBANKMENT) {
            y = paletteEditor(c, mx, my, Layer.TOP, x, y, w);
            y = numberField(c, mx, my, "Top layer thickness", NUM_TOP, 1, EarthworksSpec.MAX_TOP, x, y, w);
            y = paletteEditor(c, mx, my, Layer.CORE, x, y, w);
            y = numberField(c, mx, my, "Highest fill (deeper = left for a bridge)", NUM_MAX_HEIGHT, 2, EarthworksSpec.MAX_FILL, x, y, w);
        } else {
            y = paletteEditor(c, mx, my, Layer.FLOOR, x, y, w);
            y = numberField(c, mx, my, "Ditch width (each side)", NUM_DITCH_W, 0, EarthworksSpec.MAX_DITCH_W, x, y, w);
            if (spec.ditchWidth > 0) {
                y = numberField(c, mx, my, "Ditch depth", NUM_DITCH_D, 1, EarthworksSpec.MAX_DITCH_D, x, y, w);
                y = paletteEditor(c, mx, my, Layer.DITCH, x, y, w);
            }
            y = numberField(c, mx, my, "Deepest cut (deeper = left for a tunnel)", NUM_MAX_DEPTH, 2, EarthworksSpec.MAX_FILL, x, y, w);
            y = onOff(c, mx, my, "Only cut natural terrain", SEG_PROTECT, spec.protectBuilds, x, y, w);
            y = onOff(c, mx, my, "Fell trees on the cut", SEG_FELL, spec.fellTrees, x, y, w);
        }
        return y;
    }

    private int inspectSlopes(DrawContext c, int mx, int my, int x, int y, int w) {
        int preset = -1;
        for (int i = 0; i < EarthworksSpec.SLOPE_PRESETS.length; i++) {
            if (EarthworksSpec.SLOPE_PRESETS[i] == spec.slopeRun) {
                preset = i;
            }
        }
        y = segment(c, mx, my, "Slope (rise : run)", SEG_SLOPE, EarthworksSpec.SLOPE_LABELS, preset, x, y, w);
        y = numberField(c, mx, my, "Custom: blocks across per block up ×10 (now " + spec.slopeLabel() + ")",
                NUM_RUN, EarthworksSpec.MIN_RUN, EarthworksSpec.MAX_RUN, x, y, w);
        Layer surface = spec.kind == Kind.EMBANKMENT ? Layer.SLOPE : Layer.FACE;
        y = paletteEditor(c, mx, my, surface, x, y, w);
        y = numberField(c, mx, my, spec.kind == Kind.EMBANKMENT ? "Surface thickness" : "Face thickness",
                NUM_SURFACE, 1, EarthworksSpec.MAX_SURFACE, x, y, w);
        if (spec.kind == Kind.EMBANKMENT) {
            y = paletteEditor(c, mx, my, Layer.TOE, x, y, w);
            y = note(c, "Toe: the bottom row along the foot of the slope (riprap at a riverbank). Empty = none.", x, y, w);
        } else {
            y = note(c, "The face also seals water and caves the cut opens up.", x, y, w);
        }
        return y;
    }

    private int inspectWalls(DrawContext c, int mx, int my, int x, int y, int w) {
        y = segment(c, mx, my, "Use a wall instead of a slope", SEG_WALL, new String[]{"Never", "Auto", "Always"},
                spec.wallMode.ordinal(), x, y, w);
        if (spec.wallMode == EarthworksSpec.WallMode.NEVER) {
            return note(c, "Auto: wherever the slope would be wider than a limit (tight corridors, deep cuts).", x, y, w);
        }
        if (spec.wallMode == EarthworksSpec.WallMode.AUTO) {
            y = numberField(c, mx, my, "Wall when the slope is wider than", NUM_TRIGGER, EarthworksSpec.MIN_TRIGGER, EarthworksSpec.MAX_TRIGGER, x, y, w);
        }
        y = paletteEditor(c, mx, my, Layer.WALL, x, y, w);
        y = numberField(c, mx, my, "Wall thickness", NUM_WALL_T, 1, EarthworksSpec.MAX_WALL, x, y, w);
        y = numberField(c, mx, my, "Parapet above the top", NUM_PARAPET, 0, EarthworksSpec.MAX_PARAPET, x, y, w);
        return y;
    }

    private int inspectBenches(DrawContext c, int mx, int my, int x, int y, int w) {
        y = onOff(c, mx, my, "Benches on tall slopes", SEG_BENCHES, spec.benches, x, y, w);
        if (spec.benches) {
            y = numberField(c, mx, my, "A bench every … blocks of height", NUM_BENCH_EVERY, EarthworksSpec.MIN_BENCH_EVERY, EarthworksSpec.MAX_BENCH_EVERY, x, y, w);
            y = numberField(c, mx, my, "Bench width", NUM_BENCH_W, 1, EarthworksSpec.MAX_BENCH_W, x, y, w);
        }
        y = numberField(c, mx, my, "Natural edge blending", NUM_BLEND, 0, EarthworksSpec.MAX_BLEND, x, y, w);
        y = note(c, "Blending roughens the slope by up to that many blocks away from the track bed, so long earthworks don't look ruled.", x, y, w);
        return y;
    }

    private int inspectCorridor(DrawContext c, int mx, int my, int x, int y, int w) {
        y = numberField(c, mx, my, "Corridor past the outer tracks", NUM_CORRIDOR, 0, EarthworksSpec.MAX_CORRIDOR, x, y, w);
        y = numberField(c, mx, my, "Height above the rail", NUM_CLEAR_H, 2, EarthworksSpec.MAX_CLEAR_H, x, y, w);
        y = numberField(c, mx, my, "Depth below the rail (lower ground)", NUM_CLEAR_BELOW, 0, EarthworksSpec.MAX_CLEAR_BELOW, x, y, w);
        y = note(c, "A tree with any log in the corridor comes out whole; leaves of trees outside it are trimmed back to the corridor.", x, y, w);
        return y;
    }

    private int inspectRemove(DrawContext c, int mx, int my, int x, int y, int w) {
        y = onOff(c, mx, my, "Whole trees", SEG_TREES, spec.trees, x, y, w);
        y = onOff(c, mx, my, "Ground plants (grass, flowers, bushes…)", SEG_PLANTS, spec.plants, x, y, w);
        y = onOff(c, mx, my, "Snow and ice", SEG_SNOW, spec.snow, x, y, w);
        y = onOff(c, mx, my, "Terrain in the clearance envelope", SEG_TERRAIN, spec.terrain, x, y, w);
        if (spec.terrain) {
            y = numberField(c, mx, my, "Envelope past the outer tracks", NUM_TERRAIN_W, 0, EarthworksSpec.MAX_TERRAIN_W, x, y, w);
            y = numberField(c, mx, my, "Envelope height", NUM_TERRAIN_H, 2, EarthworksSpec.MAX_TERRAIN_H, x, y, w);
            y = note(c, "Digs natural terrain out of the envelope (no slopes — use the Trench Creator for real cuttings).", x, y, w);
        }
        y = note(c, "Crops, player-placed leaves, log buildings and anything with a block entity are never touched.", x, y, w);
        return y;
    }

    // ------------------------------------------------------------- palettes

    /** A layer's block mix: one row per block (icon, name, share, − weight +, ×), then Add / Pick. */
    private int paletteEditor(DrawContext c, int mx, int my, Layer layer, int x, int y, int w) {
        c.drawText(textRenderer, textRenderer.trimToWidth(layer.label, w), x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        List<EarthworksPalette.Entry> entries = EarthworksPalette.parse(spec.palette(layer));
        int total = 0;
        for (EarthworksPalette.Entry e : entries) {
            total += e.weight();
        }
        int rowH = 18;
        FlatUi.rect(c, x, y, w, Math.max(1, entries.size()) * rowH + 2, FlatUi.INPUT);
        FlatUi.outline(c, x, y, w, Math.max(1, entries.size()) * rowH + 2, FlatUi.BORDER);
        if (entries.isEmpty()) {
            c.drawText(textRenderer, "empty — " + (layer == Layer.TOE || layer == Layer.DITCH || layer == Layer.FLOOR ? "off" : "nothing placed"),
                    x + 6, y + 6, FlatUi.TEXT_FAINT, false);
        }
        for (int i = 0; i < entries.size(); i++) {
            EarthworksPalette.Entry e = entries.get(i);
            int ry = y + 1 + i * rowH;
            BlockState state = BridgeSpec.parseMaterial(e.material());
            String name;
            if (state != null) {
                ItemStack icon = new ItemStack(state.getBlock());
                if (icon.getItem() != Items.AIR) {
                    c.drawItem(icon, x + 2, ry + 1);
                }
                name = Text.translatable(state.getBlock().getTranslationKey()).getString();
            } else {
                name = "? " + e.material();
            }
            int controlsW = 62;
            int nameW = w - 22 - controlsW;
            c.drawText(textRenderer, textRenderer.trimToWidth(name, nameW), x + 21, ry + 5, state == null ? FlatUi.DANGER : FlatUi.TEXT, false);
            if (FlatUi.inside(mx, my, x, ry, w - controlsW, rowH)) {
                hoverName = name + " · " + e.material();
            }
            int bx = x + w - controlsW;
            String pct = total > 0 ? Math.round(e.weight() * 100f / total) + "%" : "";
            FlatUi.iconButton(c, textRenderer, "−", bx, ry + 2, 12, mx, my, FlatUi.TEXT_DIM);
            hit(bx, ry + 2, 12, 12, HIT_W_MINUS, layer.ordinal(), i);
            c.drawText(textRenderer, pct, bx + 13 + (24 - textRenderer.getWidth(pct)) / 2, ry + 5, FlatUi.TEXT_DIM, false);
            FlatUi.iconButton(c, textRenderer, "+", bx + 37, ry + 2, 12, mx, my, FlatUi.TEXT_DIM);
            hit(bx + 37, ry + 2, 12, 12, HIT_W_PLUS, layer.ordinal(), i);
            FlatUi.iconButton(c, textRenderer, "×", bx + 50, ry + 2, 12, mx, my, FlatUi.DANGER);
            hit(bx + 50, ry + 2, 12, 12, HIT_W_REMOVE, layer.ordinal(), i);
            if (FlatUi.inside(mx, my, bx, ry + 2, 50, 12)) {
                hoverName = "Weight " + e.weight() + " (shift: ±5)";
            }
        }
        y += Math.max(1, entries.size()) * rowH + 4;
        int third = (w - 8) / 3;
        boolean full = entries.size() >= EarthworksPalette.MAX_ENTRIES;
        FlatUi.button(c, textRenderer, "+ Add", x, y, third, 14, mx, my, ButtonStyle.FLAT, !full);
        if (!full) {
            hit(x, y, third, 14, HIT_ADD, layer.ordinal(), 0);
        }
        boolean picking = spec.pickTarget == layer;
        FlatUi.button(c, textRenderer, "Pick", x + third + 4, y, third, 14, mx, my, picking ? ButtonStyle.PRIMARY : ButtonStyle.FLAT);
        hit(x + third + 4, y, third, 14, HIT_PICK, layer.ordinal(), 0);
        if (FlatUi.inside(mx, my, x + third + 4, y, third, 14)) {
            hoverName = "Sneak-click a block in the world: " + (spec.pickAdd ? "adds it to this mix" : "replaces this mix");
        }
        FlatUi.button(c, textRenderer, "Clear", x + 2 * (third + 4), y, w - 2 * (third + 4), 14, mx, my, ButtonStyle.GHOST, !entries.isEmpty());
        if (!entries.isEmpty()) {
            hit(x + 2 * (third + 4), y, w - 2 * (third + 4), 14, HIT_CLEAR_LAYER, layer.ordinal(), 0);
        }
        y += 16;
        if (picking) {
            y = segment(c, mx, my, null, SEG_PICK_MODE, new String[]{"Pick replaces", "Pick adds"}, spec.pickAdd ? 1 : 0, x, y, w);
        } else {
            y += 4;
        }
        return y;
    }

    private void editWeight(Layer layer, int index, int delta) {
        List<EarthworksPalette.Entry> entries = new ArrayList<>(EarthworksPalette.parse(spec.palette(layer)));
        if (index < 0 || index >= entries.size()) {
            return;
        }
        if (delta == 0) {
            entries.remove(index);
        } else {
            EarthworksPalette.Entry e = entries.get(index);
            entries.set(index, new EarthworksPalette.Entry(e.material(),
                    Math.max(1, Math.min(EarthworksPalette.MAX_WEIGHT, e.weight() + delta))));
        }
        spec.setPalette(layer, EarthworksPalette.format(entries));
        sceneDirty = true;
    }

    // ------------------------------------------------------------- controls

    private int note(DrawContext c, String text, int x, int y, int w) {
        for (net.minecraft.text.OrderedText line : textRenderer.wrapLines(Text.literal(text), w)) {
            c.drawText(textRenderer, line, x, y, FlatUi.TEXT_FAINT, false);
            y += 10;
        }
        return y + 4;
    }

    private int segment(DrawContext c, int mx, int my, String label, int id, String[] labels, int chosen, int x, int y, int w) {
        if (label != null) {
            c.drawText(textRenderer, textRenderer.trimToWidth(label, w), x, y, FlatUi.TEXT_DIM, false);
            y += 10;
        }
        int h = FIELD + 2;
        FlatUi.segmented(c, textRenderer, labels, chosen, x, y, w, h, mx, my);
        for (int i = 0; i < labels.length; i++) {
            int cx = x + w * i / labels.length;
            int cw = x + w * (i + 1) / labels.length - cx;
            hit(cx, y, cw, h, HIT_SEGMENT, id, i);
        }
        return y + h + 6;
    }

    private int onOff(DrawContext c, int mx, int my, String label, int id, boolean on, int x, int y, int w) {
        return segment(c, mx, my, label, id, new String[]{"Off", "On"}, on ? 1 : 0, x, y, w);
    }

    private int numberValue(int id) {
        return switch (id) {
            case NUM_REACH -> spec.trackReach;
            case NUM_PREVIEW_TRACKS -> spec.previewTracks;
            case NUM_SHOULDER -> spec.shoulder;
            case NUM_TOP -> spec.topThickness;
            case NUM_MAX_HEIGHT -> spec.maxHeight;
            case NUM_MAX_DEPTH -> spec.maxDepth;
            case NUM_DITCH_W -> spec.ditchWidth;
            case NUM_DITCH_D -> spec.ditchDepth;
            case NUM_RUN -> spec.slopeRun;
            case NUM_SURFACE -> spec.surfaceThickness;
            case NUM_TRIGGER -> spec.wallTrigger;
            case NUM_WALL_T -> spec.wallThickness;
            case NUM_PARAPET -> spec.wallParapet;
            case NUM_BENCH_EVERY -> spec.benchEvery;
            case NUM_BENCH_W -> spec.benchWidth;
            case NUM_BLEND -> spec.blend;
            case NUM_CORRIDOR -> spec.corridor;
            case NUM_CLEAR_H -> spec.clearHeight;
            case NUM_CLEAR_BELOW -> spec.clearBelow;
            case NUM_TERRAIN_W -> spec.terrainWidth;
            case NUM_TERRAIN_H -> spec.terrainHeight;
            default -> 0;
        };
    }

    private void applyNumber(int id, int v) {
        switch (id) {
            case NUM_REACH -> spec.trackReach = v;
            case NUM_PREVIEW_TRACKS -> spec.previewTracks = v;
            case NUM_SHOULDER -> spec.shoulder = v;
            case NUM_TOP -> spec.topThickness = v;
            case NUM_MAX_HEIGHT -> spec.maxHeight = v;
            case NUM_MAX_DEPTH -> spec.maxDepth = v;
            case NUM_DITCH_W -> spec.ditchWidth = v;
            case NUM_DITCH_D -> spec.ditchDepth = v;
            case NUM_RUN -> spec.slopeRun = v;
            case NUM_SURFACE -> spec.surfaceThickness = v;
            case NUM_TRIGGER -> spec.wallTrigger = v;
            case NUM_WALL_T -> spec.wallThickness = v;
            case NUM_PARAPET -> spec.wallParapet = v;
            case NUM_BENCH_EVERY -> spec.benchEvery = v;
            case NUM_BENCH_W -> spec.benchWidth = v;
            case NUM_BLEND -> spec.blend = v;
            case NUM_CORRIDOR -> spec.corridor = v;
            case NUM_CLEAR_H -> spec.clearHeight = v;
            case NUM_CLEAR_BELOW -> spec.clearBelow = v;
            case NUM_TERRAIN_W -> spec.terrainWidth = v;
            case NUM_TERRAIN_H -> spec.terrainHeight = v;
            default -> {
            }
        }
        spec.clamp();
        sceneDirty = true;
        TextBox box = numberBoxes.get(id);
        if (box != null && !box.isFocused()) {
            box.load(Integer.toString(numberValue(id)));
        }
    }

    private int numberField(DrawContext c, int mx, int my, String label, int id, int min, int max, int x, int y, int w) {
        int value = numberValue(id);
        for (net.minecraft.text.OrderedText line : textRenderer.wrapLines(Text.literal(label), w)) {
            c.drawText(textRenderer, line, x, y, FlatUi.TEXT_DIM, false);
            y += 10;
        }
        int boxW = 32;
        int trackW = w - boxW - 8;
        int trackY = y + FIELD / 2 - 2;
        FlatUi.rect(c, x, trackY, trackW, 4, FlatUi.INPUT);
        float t = max > min ? Math.max(0, Math.min(1, (value - min) / (float) (max - min))) : 0;
        int knob = x + Math.round(t * (trackW - 6));
        boolean hot = (dragSlider != null && dragSlider[0] == id) || FlatUi.inside(mx, my, x, y, trackW, FIELD);
        FlatUi.rect(c, x, trackY, knob - x + 3, 4, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        FlatUi.rect(c, knob, y + 2, 6, FIELD - 4, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        hit(x, y, trackW, FIELD, HIT_SLIDER, id, min * 1000 + max);
        TextBox box = numberBoxes.get(id);
        if (box == null) {
            box = new TextBox(textRenderer, 4, false);
            int fieldId = id;
            box.onChange(v -> {
                try {
                    applyNumber(fieldId, Integer.parseInt(v.trim()));
                } catch (NumberFormatException ignored) {
                    // half-typed number
                }
            });
            numberBoxes.put(id, box);
        }
        if (!box.isFocused()) {
            box.load(Integer.toString(value));
        }
        box.setBounds(x + w - boxW, y, boxW, FIELD);
        box.render(c, mx, my);
        visibleBoxes.add(box);
        return y + FIELD + 6;
    }

    private void sliderDrag(double mx) {
        if (dragSlider == null || dragSlider[2] <= 0) {
            return;
        }
        float t = (float) Math.max(0, Math.min(1, (mx - dragSlider[1]) / dragSlider[2]));
        applyNumber(dragSlider[0], Math.round(dragMin + t * (dragMax - dragMin)));
    }

    // -------------------------------------------------------------- overlays

    private void drawPresetMenu(DrawContext c, int mx, int my) {
        int w = 180;
        int x = width - MARGIN - w;
        int y = 3 + FlatUi.BUTTON_HEIGHT + 2;
        List<EarthworksPresets.Preset> builtin = EarthworksPresets.forKind(spec.kind);
        List<String> user = new ArrayList<>(EarthworksUserPresets.forKind(spec.kind).keySet());
        int rows = builtin.size() + user.size() + 2 + (user.isEmpty() ? 0 : 1);
        int h = rows * 13 + 8;
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        hit(x, y, w, h, HIT_MENU, 0, 0);
        int ry = y + 4;
        FlatUi.heading(c, textRenderer, "Built-in", x + 6, ry + 2);
        ry += 13;
        for (int i = 0; i < builtin.size(); i++) {
            ry = menuRow(c, mx, my, builtin.get(i).name(), x, ry, w, HIT_PRESET_ROW, i, 0, false);
        }
        if (!user.isEmpty()) {
            FlatUi.heading(c, textRenderer, "Yours", x + 6, ry + 2);
            ry += 13;
            for (int i = 0; i < user.size(); i++) {
                ry = menuRow(c, mx, my, user.get(i), x, ry, w, HIT_PRESET_ROW, i, 1, true);
            }
        }
        menuRow(c, mx, my, "Save current as…", x, ry, w, HIT_PRESET_SAVE, 0, 0, false);
    }

    private int menuRow(DrawContext c, int mx, int my, String label, int x, int y, int w, int id, int arg, int arg2, boolean deletable) {
        int rowW = deletable ? w - 18 : w;
        boolean hovered = FlatUi.inside(mx, my, x, y, rowW, 13);
        if (hovered) {
            FlatUi.rect(c, x + 1, y, rowW - 2, 13, FlatUi.HOVER);
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(label, rowW - 12), x + 6, y + 2, hovered ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
        hit(x, y, rowW, 13, id, arg, arg2);
        if (deletable) {
            FlatUi.iconButton(c, textRenderer, "×", x + w - 16, y, 13, mx, my, FlatUi.DANGER);
            hit(x + w - 16, y, 13, 13, HIT_PRESET_DELETE, arg, 0);
        }
        return y + 13;
    }

    private List<Block> candidates() {
        if (candidates == null) {
            List<Block> list = new ArrayList<>();
            for (Block block : Registries.BLOCK) {
                if (block.asItem() != Items.AIR && !block.getDefaultState().isAir()) {
                    list.add(block);
                }
            }
            list.sort((a, b) -> blockName(a).compareToIgnoreCase(blockName(b)));
            candidates = list;
        }
        return candidates;
    }

    private static String blockName(Block block) {
        return Text.translatable(block.getTranslationKey()).getString();
    }

    private void filterCandidates() {
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        if (q.equals(lastFilter)) {
            return;
        }
        lastFilter = q;
        popupScroll = 0;
        List<Block> out = new ArrayList<>();
        for (Block block : candidates()) {
            if (q.isEmpty() || blockName(block).toLowerCase(Locale.ROOT).contains(q)
                    || Registries.BLOCK.getId(block).toString().contains(q)) {
                out.add(block);
            }
        }
        filtered = out;
    }

    private void drawMaterialPopup(DrawContext c, int mx, int my) {
        filterCandidates();
        int w = Math.min(width - 20, 262);
        int h = Math.min(height - 20, 210);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        hit(x, y, w, h, HIT_POPUP, 0, 0);
        c.drawText(textRenderer, "Add to " + popupLayer.label.toLowerCase(Locale.ROOT), x + 8, y + 6, FlatUi.TEXT, false);
        FlatUi.iconButton(c, textRenderer, "×", x + w - 20, y + 3, 14, mx, my, FlatUi.TEXT_DIM);
        hit(x + w - 20, y + 3, 14, 14, HIT_PROMPT_CANCEL, 0, 0);
        searchBox.setBounds(x + 8, y + 18, w - 16, FIELD);
        searchBox.render(c, mx, my);
        visibleBoxes.add(searchBox);
        int gridX = x + 8, gridY = y + 38;
        int cell = 18;
        int cols = Math.max(1, (w - 16) / cell);
        int rowsVisible = Math.max(1, (h - 52) / cell);
        int totalRows = (filtered.size() + cols - 1) / cols;
        popupScroll = Math.max(0, Math.min(Math.max(0, totalRows - rowsVisible), popupScroll));
        c.enableScissor(gridX, gridY, gridX + cols * cell, gridY + rowsVisible * cell);
        for (int r = 0; r < rowsVisible; r++) {
            int row = r + popupScroll;
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= filtered.size()) {
                    break;
                }
                Block block = filtered.get(i);
                int ix = gridX + col * cell, iy = gridY + r * cell;
                if (FlatUi.inside(mx, my, ix, iy, cell, cell)) {
                    FlatUi.rect(c, ix, iy, cell, cell, FlatUi.HOVER);
                    hoverName = blockName(block);
                }
                c.drawItem(new ItemStack(block), ix + 1, iy + 1);
                hit(ix, iy, cell, cell, HIT_POPUP_ITEM, i, 0);
            }
        }
        c.disableScissor();
        FlatUi.scrollThumb(c, x + w - 2, gridY, rowsVisible * cell, totalRows * cell, popupScroll * cell);
        String foot = filtered.size() + " blocks · or sneak-click one in the world";
        c.drawText(textRenderer, textRenderer.trimToWidth(foot, w - 16), x + 8, y + h - 11, FlatUi.TEXT_FAINT, false);
    }

    private void drawPrompt(DrawContext c, int mx, int my) {
        int w = 200, h = 62;
        int x = (width - w) / 2, y = (height - h) / 2;
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        hit(x, y, w, h, HIT_POPUP, 0, 0);
        c.drawText(textRenderer, "Save preset as", x + 8, y + 6, FlatUi.TEXT, false);
        nameBox.setBounds(x + 8, y + 18, w - 16, FIELD);
        nameBox.render(c, mx, my);
        visibleBoxes.add(nameBox);
        boolean can = !nameBox.getText().isBlank();
        FlatUi.button(c, textRenderer, "Cancel", x + w - 8 - 50 - 4 - 46, y + 38, 46, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(x + w - 8 - 50 - 4 - 46, y + 38, 46, FlatUi.BUTTON_HEIGHT, HIT_PROMPT_CANCEL, 0, 0);
        FlatUi.button(c, textRenderer, "Save", x + w - 8 - 50, y + 38, 50, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY, can);
        if (can) {
            hit(x + w - 8 - 50, y + 38, 50, FlatUi.BUTTON_HEIGHT, HIT_PROMPT_OK, 0, 0);
        }
    }

    // ---------------------------------------------------------------- input

    private void focus(TextBox box) {
        if (focused != null && focused != box) {
            focused.setFocused(false);
        }
        focused = box;
        if (box != null) {
            box.setFocused(true);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        for (TextBox box : visibleBoxes) {
            if (box.contains(mx, my)) {
                focus(box);
                box.mouseClicked(mx, my, button);
                return true;
            }
        }
        focus(null);
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] r = hits.get(i);
            if (FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                click(r[4], r[5], r[6], r[0], r[2]);
                return true;
            }
        }
        presetMenuOpen = false;
        popupLayer = null;
        promptOpen = false;
        return true;
    }

    private void click(int id, int arg, int arg2, int x, int w) {
        boolean shift = hasShiftDown();
        switch (id) {
            case HIT_PART -> {
                selected = Part.values()[arg];
                inspectorScroll = 0;
            }
            case HIT_SEGMENT -> segment(arg, arg2);
            case HIT_SLIDER -> {
                dragSlider = new int[]{arg, x, w};
                dragMin = arg2 / 1000;
                dragMax = arg2 % 1000;
            }
            case HIT_ADD -> {
                popupLayer = Layer.values()[arg];
                if (searchBox == null) {
                    searchBox = new TextBox(textRenderer, 32, false);
                    searchBox.placeholder = "Search blocks";
                }
                searchBox.load("");
                lastFilter = null;
                focus(searchBox);
            }
            case HIT_PICK -> {
                spec.pickTarget = Layer.values()[arg];
                status = "Save, then sneak-click a block in the world (" + (spec.pickAdd ? "adds" : "replaces") + ")";
            }
            case HIT_W_MINUS -> editWeight(Layer.values()[arg], arg2, shift ? -5 : -1);
            case HIT_W_PLUS -> editWeight(Layer.values()[arg], arg2, shift ? 5 : 1);
            case HIT_W_REMOVE -> editWeight(Layer.values()[arg], arg2, 0);
            case HIT_CLEAR_LAYER -> {
                spec.setPalette(Layer.values()[arg], "");
                sceneDirty = true;
            }
            case HIT_VIEW -> {
                showAfter = arg == 1;
                sceneDirty = true;
            }
            case HIT_PRESET_MENU -> presetMenuOpen = !presetMenuOpen;
            case HIT_PRESET_ROW -> {
                EarthworksSpec loaded = null;
                if (arg2 == 0) {
                    List<EarthworksPresets.Preset> builtin = EarthworksPresets.forKind(spec.kind);
                    if (arg < builtin.size()) {
                        loaded = builtin.get(arg).spec().copy();
                    }
                } else {
                    List<String> names = new ArrayList<>(EarthworksUserPresets.forKind(spec.kind).keySet());
                    if (arg < names.size()) {
                        loaded = EarthworksUserPresets.get(spec.kind, names.get(arg));
                    }
                }
                if (loaded != null) {
                    applyPreset(loaded);
                }
                presetMenuOpen = false;
            }
            case HIT_PRESET_DELETE -> {
                List<String> names = new ArrayList<>(EarthworksUserPresets.forKind(spec.kind).keySet());
                if (arg < names.size()) {
                    EarthworksUserPresets.remove(spec.kind, names.get(arg));
                }
            }
            case HIT_PRESET_SAVE -> {
                presetMenuOpen = false;
                promptOpen = true;
                if (nameBox == null) {
                    nameBox = new TextBox(textRenderer, 40, false);
                    nameBox.placeholder = "Preset name";
                }
                nameBox.load("");
                focus(nameBox);
            }
            case HIT_PROMPT_OK -> {
                String name = nameBox.getText().trim();
                if (!name.isEmpty()) {
                    EarthworksUserPresets.put(spec, name);
                    status = "Saved preset \"" + name + "\"";
                }
                promptOpen = false;
                focus(null);
            }
            case HIT_PROMPT_CANCEL -> {
                promptOpen = false;
                popupLayer = null;
                focus(null);
            }
            case HIT_POPUP_ITEM -> {
                if (arg < filtered.size() && popupLayer != null) {
                    String material = Registries.BLOCK.getId(filtered.get(arg)).toString();
                    spec.setPalette(popupLayer, EarthworksPalette.add(spec.palette(popupLayer), material, 1));
                    sceneDirty = true;
                }
                popupLayer = null;
                focus(null);
            }
            case HIT_SAVE -> {
                send();
                close();
            }
            case HIT_CANCEL -> close();
            case HIT_UNDO -> {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBoolean(hand == Hand.OFF_HAND);
                buf.writeByte(Earthworks.ACTION_UNDO);
                ClientPlayNetworking.send(Earthworks.ACTION_C2S, buf);
                close();
            }
            case HIT_PREVIEW -> draggingPreview = true;
            default -> {
            }
        }
    }

    private void segment(int control, int index) {
        switch (control) {
            case SEG_TRACK_MODE -> spec.trackMode = EarthworksSpec.TrackMode.values()[index];
            case SEG_SLOPE -> spec.slopeRun = EarthworksSpec.SLOPE_PRESETS[index];
            case SEG_WALL -> spec.wallMode = EarthworksSpec.WallMode.values()[index];
            case SEG_BENCHES -> spec.benches = index == 1;
            case SEG_PROTECT -> spec.protectBuilds = index == 1;
            case SEG_FELL -> spec.fellTrees = index == 1;
            case SEG_TREES -> spec.trees = index == 1;
            case SEG_PLANTS -> spec.plants = index == 1;
            case SEG_SNOW -> spec.snow = index == 1;
            case SEG_TERRAIN -> spec.terrain = index == 1;
            case SEG_PICK_MODE -> spec.pickAdd = index == 1;
            default -> {
            }
        }
        numberBoxes.clear();
        sceneDirty = true;
    }

    private void applyPreset(EarthworksSpec loaded) {
        net.minecraft.nbt.NbtCompound n = loaded.toNbt();
        // presets describe the earthworks, not how tracks are chosen
        n.putString("TrackMode", spec.trackMode.name());
        n.putInt("TrackReach", spec.trackReach);
        n.putInt("PreviewTracks", spec.previewTracks);
        EarthworksSpec fresh = EarthworksSpec.fromNbt(spec.kind, n);
        copyInto(fresh);
        numberBoxes.clear();
        sceneDirty = true;
    }

    private void copyInto(EarthworksSpec t) {
        spec.trackMode = t.trackMode;
        spec.trackReach = t.trackReach;
        spec.previewTracks = t.previewTracks;
        spec.shoulder = t.shoulder;
        spec.topThickness = t.topThickness;
        spec.maxHeight = t.maxHeight;
        spec.maxDepth = t.maxDepth;
        spec.ditchWidth = t.ditchWidth;
        spec.ditchDepth = t.ditchDepth;
        spec.protectBuilds = t.protectBuilds;
        spec.fellTrees = t.fellTrees;
        spec.slopeRun = t.slopeRun;
        spec.surfaceThickness = t.surfaceThickness;
        spec.wallMode = t.wallMode;
        spec.wallTrigger = t.wallTrigger;
        spec.wallThickness = t.wallThickness;
        spec.wallParapet = t.wallParapet;
        spec.benches = t.benches;
        spec.benchEvery = t.benchEvery;
        spec.benchWidth = t.benchWidth;
        spec.blend = t.blend;
        spec.corridor = t.corridor;
        spec.clearHeight = t.clearHeight;
        spec.clearBelow = t.clearBelow;
        spec.trees = t.trees;
        spec.plants = t.plants;
        spec.snow = t.snow;
        spec.terrain = t.terrain;
        spec.terrainWidth = t.terrainWidth;
        spec.terrainHeight = t.terrainHeight;
        spec.palettes.clear();
        spec.palettes.putAll(t.palettes);
    }

    private void send() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        buf.writeNbt(spec.clamp().toNbt());
        ClientPlayNetworking.send(Earthworks.UPDATE_C2S, buf);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSlider != null) {
            sliderDrag(mx);
            return true;
        }
        if (draggingPreview) {
            yaw += (float) dx * 1.5f;
            return true;
        }
        if (focused != null) {
            focused.mouseDragged(mx, my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragSlider = null;
        draggingPreview = false;
        if (focused != null) {
            focused.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (popupLayer != null) {
            popupScroll = Math.max(0, popupScroll - (int) Math.signum(verticalAmount));
            return true;
        }
        if (FlatUi.inside(mx, my, rightX, paneY, rightW, paneH)) {
            inspectorScroll = Math.max(0, inspectorScroll - (int) (verticalAmount * 12));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            if (popupLayer != null || promptOpen || presetMenuOpen) {
                popupLayer = null;
                promptOpen = false;
                presetMenuOpen = false;
                focus(null);
                return true;
            }
            if (focused != null) {
                focus(null);
                return true;
            }
            close();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            if (promptOpen && nameBox != null && !nameBox.getText().isBlank()) {
                click(HIT_PROMPT_OK, 0, 0, 0, 0);
                return true;
            }
            if (popupLayer != null && !filtered.isEmpty()) {
                click(HIT_POPUP_ITEM, 0, 0, 0, 0);
                return true;
            }
        }
        if (keyCode == 83 && (modifiers & 2) != 0) {
            send();
            close();
            return true;
        }
        if (focused != null && focused.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focused != null && focused.charTyped(chr)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private String shortMix(Layer layer) {
        List<EarthworksPalette.Entry> entries = EarthworksPalette.parse(spec.palette(layer));
        if (entries.isEmpty()) {
            return "none";
        }
        BlockState state = BridgeSpec.parseMaterial(entries.get(0).material());
        String name = state == null ? "?" : blockName(state.getBlock());
        return entries.size() > 1 ? name + " +" + (entries.size() - 1) : name;
    }
}
