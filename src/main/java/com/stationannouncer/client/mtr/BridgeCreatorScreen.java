package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.mtr.BridgeBuilder;
import com.stationannouncer.mtr.BridgePresets;
import com.stationannouncer.mtr.BridgeService;
import com.stationannouncer.mtr.BridgeSpec;
import com.stationannouncer.mtr.MtrPillars;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.RailShape;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Bridge Creator's settings screen, on {@link FlatUi}: a STRUCTURE list
 * of the bridge's parts on the left (tracks, deck, girders, railing, piers,
 * arches), a live 3D PREVIEW in the middle built by the very same
 * {@link BridgeBuilder} that builds in the world (over straight preview
 * tracks — drag to turn), and an INSPECTOR on the right with the selected
 * part's material, sizes and style. Presets — built-in and the player's own
 * — load into the working copy; Save writes it into the held item on the
 * server. Manual track mode builds from here too.
 */
@Environment(EnvType.CLIENT)
public class BridgeCreatorScreen extends Screen {
    private enum Part {
        TRACKS("Tracks"), DECK("Deck"), GIRDERS("Girders"), RAILING("Railing"), PIERS("Piers"), ARCH("Arches");

        final String label;

        Part(String label) {
            this.label = label;
        }
    }

    private static final int MARGIN = 6;
    private static final int TOP = 24;
    private static final int BOTTOM = 26;
    private static final int ROW = 16;
    private static final int FIELD = 14;

    private static final int HIT_PART = 1;
    private static final int HIT_SEGMENT = 2;
    private static final int HIT_SLIDER = 3;
    private static final int HIT_CHOOSE = 4;
    private static final int HIT_PICK = 5;
    private static final int HIT_CLEAR_MATERIAL = 6;
    private static final int HIT_PRESET_MENU = 7;
    private static final int HIT_PRESET_ROW = 8;
    private static final int HIT_PRESET_DELETE = 9;
    private static final int HIT_PRESET_SAVE = 10;
    private static final int HIT_SAVE = 11;
    private static final int HIT_CANCEL = 12;
    private static final int HIT_BUILD = 13;
    private static final int HIT_CLEAR_TRACKS = 14;
    private static final int HIT_UNDO = 15;
    private static final int HIT_POPUP_ITEM = 16;
    private static final int HIT_POPUP = 17;
    private static final int HIT_PROMPT_OK = 18;
    private static final int HIT_PROMPT_CANCEL = 19;
    private static final int HIT_PREVIEW = 20;
    private static final int HIT_MENU = 21;

    // segmented controls
    private static final int SEG_TRACK_MODE = 1;
    private static final int SEG_DECK = 2;
    private static final int SEG_GIRDER = 3;
    private static final int SEG_RAILING = 4;
    private static final int SEG_PIER = 5;
    private static final int SEG_PIER_B = 6;
    private static final int SEG_ARCH = 7;
    private static final int SEG_CAP = 8;
    private static final int SEG_FOOTING = 9;

    // numeric fields
    private static final int NUM_REACH = 1;
    private static final int NUM_PREVIEW_TRACKS = 2;
    private static final int NUM_THICKNESS = 3;
    private static final int NUM_OVERHANG = 4;
    private static final int NUM_EDGE_WIDTH = 5;
    private static final int NUM_GIRDER_DEPTH = 6;
    private static final int NUM_RAIL_HEIGHT = 7;
    private static final int NUM_RAIL_INSET = 8;
    private static final int NUM_SPACING = 9;
    private static final int NUM_PIER_THICK = 10;
    private static final int NUM_PIER_INSET = 11;
    private static final int NUM_RISE = 12;

    private final Hand hand;
    private final ItemStack stack;
    private final BridgeSpec spec;
    private Part selected = Part.DECK;

    private final List<int[]> hits = new ArrayList<>();
    private final Map<Integer, TextBox> numberBoxes = new HashMap<>();
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    private TextBox focused;
    private int[] dragSlider;
    private float dragMin, dragMax;

    private boolean presetMenuOpen;
    private BridgeSpec.Slot popupSlot;
    private TextBox searchBox;
    private int popupScroll;
    private List<Block> candidates;
    private List<Block> filtered = List.of();
    private String lastFilter = null;
    private boolean promptOpen;
    private TextBox nameBox;
    private String hoverName;
    private int inspectorScroll;
    private int inspectorContent;
    private String status = "";

    private List<CreatorPreview.Cell> scene = List.of();
    private boolean sceneDirty = true;
    private float yaw = 35;
    private boolean draggingPreview;

    // layout (recomputed each frame)
    private int leftX, leftW, midX, midW, rightX, rightW, paneY, paneH;

    public BridgeCreatorScreen(Hand hand, ItemStack stack) {
        super(Text.literal("Bridge Creator"));
        this.hand = hand;
        this.stack = stack;
        this.spec = BridgeSpec.read(stack);
    }

    // --------------------------------------------------------------- layout

    private void layout() {
        boolean narrow = width < 560;
        leftW = narrow ? 88 : 110;
        rightW = narrow ? 156 : 190;
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
        int hmx = (presetMenuOpen || popupSlot != null || promptOpen) ? -1 : mx;
        int hmy = hmx < 0 ? -1 : my;
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        drawTopBar(c, hmx, hmy);
        drawStructure(c, hmx, hmy);
        drawPreview(c, hmx, hmy, delta);
        drawInspector(c, hmx, hmy);
        drawBottomBar(c, hmx, hmy);
        if (presetMenuOpen) {
            drawPresetMenu(c, mx, my);
        }
        if (popupSlot != null) {
            drawMaterialPopup(c, mx, my);
        }
        if (promptOpen) {
            drawPrompt(c, mx, my);
        }
        if (hoverName != null) {
            c.drawTooltip(textRenderer, Text.literal(hoverName), mx, my);
        }
    }

    private void drawTopBar(DrawContext c, int mx, int my) {
        c.drawText(textRenderer, "Bridge Creator", MARGIN, 8, FlatUi.TEXT, false);
        int titleW = textRenderer.getWidth("Bridge Creator");
        int bw = 82;
        int x = width - MARGIN - bw;
        int subtitleRoom = x - 66 - 8 - (MARGIN + titleW + 8);
        if (subtitleRoom > 40) {
            c.drawText(textRenderer, textRenderer.trimToWidth("same section on curves and grades, sized to the tracks it finds", subtitleRoom),
                    MARGIN + titleW + 8, 8, FlatUi.TEXT_FAINT, false);
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
            case TRACKS -> switch (spec.trackMode) {
                case AUTO -> "auto, reach " + spec.trackReach;
                case SINGLE -> "single";
                case MANUAL -> BridgeService.manualTrackCount(stack) + " selected";
            };
            case DECK -> spec.deck ? shortName(spec.deckMaterial) : "off";
            case GIRDERS -> spec.girderStyle == BridgeSpec.GirderStyle.NONE ? "none"
                    : spec.girderStyle.name().toLowerCase(Locale.ROOT) + " ×" + spec.girderDepth;
            case RAILING -> spec.railing ? shortName(spec.railingMaterial) : "off";
            case PIERS -> spec.pierStyle == BridgeSpec.PierStyle.NONE ? "none"
                    : spec.pierStyle.name().toLowerCase(Locale.ROOT) + " every " + spec.pierSpacing;
            case ARCH -> spec.archStyle == BridgeSpec.ArchStyle.NONE ? "none"
                    : spec.archStyle.name().toLowerCase(Locale.ROOT) + ", rise " + spec.archRise;
        };
    }

    private boolean partOn(Part part) {
        return switch (part) {
            case TRACKS -> true;
            case DECK -> spec.deck;
            case GIRDERS -> spec.girderStyle != BridgeSpec.GirderStyle.NONE;
            case RAILING -> spec.railing;
            case PIERS -> spec.pierStyle != BridgeSpec.PierStyle.NONE;
            case ARCH -> spec.archStyle != BridgeSpec.ArchStyle.NONE;
        };
    }

    private void drawStructure(DrawContext c, int mx, int my) {
        FlatUi.pane(c, leftX, paneY, leftW, paneH);
        FlatUi.heading(c, textRenderer, "Structure", leftX + 6, paneY + 5);
        int y = paneY + 16;
        int cardH = 24;
        for (Part part : Part.values()) {
            boolean sel = part == selected;
            boolean hovered = FlatUi.inside(mx, my, leftX + 1, y, leftW - 2, cardH);
            if (sel) {
                FlatUi.rect(c, leftX + 1, y, leftW - 2, cardH, FlatUi.SELECTED);
            } else if (hovered) {
                FlatUi.rect(c, leftX + 1, y, leftW - 2, cardH, FlatUi.HOVER);
            }
            int dot = partOn(part) ? FlatUi.OK : FlatUi.TEXT_FAINT;
            FlatUi.rect(c, leftX + 6, y + 6, 4, 4, dot);
            c.drawText(textRenderer, part.label, leftX + 14, y + 3, sel ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            String summary = textRenderer.trimToWidth(partSummary(part), leftW - 20);
            c.drawText(textRenderer, summary, leftX + 14, y + 13, FlatUi.TEXT_FAINT, false);
            hit(leftX + 1, y, leftW - 2, cardH, HIT_PART, part.ordinal(), 0);
            y += cardH + 1;
        }
    }

    private void drawPreview(DrawContext c, int mx, int my, float delta) {
        FlatUi.pane(c, midX, paneY, midW, paneH);
        FlatUi.heading(c, textRenderer, "Preview", midX + 6, paneY + 5);
        if (sceneDirty) {
            rebuildScene();
        }
        if (!draggingPreview) {
            yaw += delta * 0.3f;
        }
        int px = midX + 2, py = paneY + 14, pw = midW - 4, ph = paneH - 26;
        if (pw > 10 && ph > 10) {
            CreatorPreview.render(c, px, py, pw, ph, scene, yaw);
        }
        hit(px, py, pw, ph, HIT_PREVIEW, 0, 0);
        String hint = spec.previewTracks + (spec.previewTracks == 1 ? " track" : " tracks") + " · drag to turn";
        c.drawText(textRenderer, hint, midX + 6, paneY + paneH - 10, FlatUi.TEXT_FAINT, false);
    }

    private void drawBottomBar(DrawContext c, int mx, int my) {
        int y = height - BOTTOM + 4;
        int x = MARGIN;
        int manual = BridgeService.manualTrackCount(stack);
        if (spec.trackMode == BridgeSpec.TrackMode.MANUAL) {
            int bw = 70;
            boolean can = manual > 0;
            FlatUi.button(c, textRenderer, "Build " + manual + (manual == 1 ? " track" : " tracks"), x, y, bw, FlatUi.BUTTON_HEIGHT, mx, my,
                    ButtonStyle.PRIMARY, can);
            if (can) {
                hit(x, y, bw, FlatUi.BUTTON_HEIGHT, HIT_BUILD, 0, 0);
            }
            x += bw + 4;
            FlatUi.button(c, textRenderer, "Clear", x, y, 40, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST, can);
            if (can) {
                hit(x, y, 40, FlatUi.BUTTON_HEIGHT, HIT_CLEAR_TRACKS, 0, 0);
            }
            x += 44;
        }
        FlatUi.button(c, textRenderer, "Undo last build", x, y, 84, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(x, y, 84, FlatUi.BUTTON_HEIGHT, HIT_UNDO, 0, 0);
        x += 90;
        if (!status.isEmpty()) {
            c.drawText(textRenderer, textRenderer.trimToWidth(status, rightX - x - 100), x, y + 5, FlatUi.TEXT_DIM, false);
        }
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
            case DECK -> inspectDeck(c, mx, my, x, y, w);
            case GIRDERS -> inspectGirders(c, mx, my, x, y, w);
            case RAILING -> inspectRailing(c, mx, my, x, y, w);
            case PIERS -> inspectPiers(c, mx, my, x, y, w);
            case ARCH -> inspectArch(c, mx, my, x, y, w);
        };
        inspectorContent = y - start + 4;
        c.disableScissor();
        FlatUi.scrollThumb(c, rightX + rightW, top, viewH, inspectorContent, inspectorScroll);
    }

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

    private int inspectTracks(DrawContext c, int mx, int my, int x, int y, int w) {
        y = segment(c, mx, my, "Which tracks", SEG_TRACK_MODE, new String[]{"Auto", "Single", "Manual"},
                spec.trackMode.ordinal(), x, y, w);
        switch (spec.trackMode) {
            case AUTO -> {
                y = note(c, "Click two nodes of one track; every parallel track of the line within reach is bridged too.", x, y, w);
                y = numberField(c, mx, my, "Reach (blocks sideways)", NUM_REACH, BridgeSpec.MIN_REACH, BridgeSpec.MAX_REACH, x, y, w);
            }
            case SINGLE -> y = note(c, "Click two nodes; only that track is bridged.", x, y, w);
            case MANUAL -> {
                int n = BridgeService.manualTrackCount(stack);
                y = note(c, "Click two nodes of each track to add it (again to remove). " + n
                        + (n == 1 ? " track" : " tracks") + " selected; the first is the reference. Build from the bar below.", x, y, w);
            }
        }
        y = numberField(c, mx, my, "Tracks in the preview", NUM_PREVIEW_TRACKS, 1, BridgeSpec.MAX_PREVIEW_TRACKS, x, y, w);
        y = note(c, "Deck width follows the outermost tracks found, plus the overhang.", x, y, w);
        return y;
    }

    private int inspectDeck(DrawContext c, int mx, int my, int x, int y, int w) {
        y = onOff(c, mx, my, "Build the deck", SEG_DECK, spec.deck, x, y, w);
        if (!spec.deck) {
            return note(c, "No deck: girders, railing and piers still go where the deck would be.", x, y, w);
        }
        y = materialRow(c, mx, my, "Material", BridgeSpec.Slot.DECK, x, y, w, false);
        y = numberField(c, mx, my, "Thickness", NUM_THICKNESS, 1, BridgeSpec.MAX_THICKNESS, x, y, w);
        y = numberField(c, mx, my, "Overhang past outer tracks", NUM_OVERHANG, 0, BridgeSpec.MAX_OVERHANG, x, y, w);
        y = materialRow(c, mx, my, "Edge material (optional)", BridgeSpec.Slot.EDGE, x, y, w, true);
        if (!spec.edgeMaterial.isBlank()) {
            y = numberField(c, mx, my, "Edge width", NUM_EDGE_WIDTH, 1, BridgeSpec.MAX_EDGE_WIDTH, x, y, w);
        }
        return y;
    }

    private int inspectGirders(DrawContext c, int mx, int my, int x, int y, int w) {
        y = segment(c, mx, my, "Under the deck", SEG_GIRDER, new String[]{"None", "Edges", "Tracks", "Both", "Full"},
                spec.girderStyle.ordinal(), x, y, w);
        if (spec.girderStyle == BridgeSpec.GirderStyle.NONE) {
            return note(c, "Edges: beams under the deck edges. Tracks: under each track. Full: a solid box the whole width.", x, y, w);
        }
        y = materialRow(c, mx, my, "Material", BridgeSpec.Slot.GIRDER, x, y, w, false);
        y = numberField(c, mx, my, "Depth", NUM_GIRDER_DEPTH, 1, BridgeSpec.MAX_GIRDER_DEPTH, x, y, w);
        return y;
    }

    private int inspectRailing(DrawContext c, int mx, int my, int x, int y, int w) {
        y = onOff(c, mx, my, "Build a railing", SEG_RAILING, spec.railing, x, y, w);
        if (!spec.railing) {
            return y;
        }
        y = materialRow(c, mx, my, "Material (fence, wall, pane…)", BridgeSpec.Slot.RAILING, x, y, w, false);
        y = numberField(c, mx, my, "Height", NUM_RAIL_HEIGHT, 1, BridgeSpec.MAX_RAILING_HEIGHT, x, y, w);
        y = numberField(c, mx, my, "Inset from the deck edge", NUM_RAIL_INSET, 0, BridgeSpec.MAX_RAILING_INSET, x, y, w);
        return y;
    }

    private int inspectPiers(DrawContext c, int mx, int my, int x, int y, int w) {
        int style = spec.pierStyle.ordinal();
        y = segment(c, mx, my, "Piers", SEG_PIER, new String[]{"None", "Edges", "Centre"}, style < 3 ? style : -1, x, y, w);
        y = segment(c, mx, my, null, SEG_PIER_B, new String[]{"Tracks", "Twin", "Wall"}, style >= 3 ? style - 3 : -1, x, y - 4, w);
        if (spec.pierStyle == BridgeSpec.PierStyle.NONE) {
            return note(c, "Edges: a leg under each deck edge. Centre: one under the middle. Tracks / Twin: under (beside) every track. Wall: a solid pier the full width.", x, y, w);
        }
        y = materialRow(c, mx, my, "Material", BridgeSpec.Slot.PIER, x, y, w, false);
        y = numberField(c, mx, my, "Spacing along the track", NUM_SPACING, BridgeSpec.MIN_SPACING, BridgeSpec.MAX_SPACING, x, y, w);
        y = numberField(c, mx, my, "Thickness along the track", NUM_PIER_THICK, 1, BridgeSpec.MAX_PIER_THICKNESS, x, y, w);
        if (spec.pierStyle == BridgeSpec.PierStyle.EDGES || spec.pierStyle == BridgeSpec.PierStyle.WALL) {
            y = numberField(c, mx, my, "Inset from the deck edge", NUM_PIER_INSET, 0, BridgeSpec.MAX_PIER_INSET, x, y, w);
        }
        y = onOff(c, mx, my, "Cap beam across the deck", SEG_CAP, spec.pierCap, x, y, w);
        if (spec.pierCap) {
            y = materialRow(c, mx, my, "Cap material", BridgeSpec.Slot.CAP, x, y, w, false);
        }
        y = onOff(c, mx, my, "Footing at the ground", SEG_FOOTING, spec.footing, x, y, w);
        if (spec.footing) {
            y = materialRow(c, mx, my, "Footing material", BridgeSpec.Slot.FOOTING, x, y, w, false);
        }
        return y;
    }

    private int inspectArch(DrawContext c, int mx, int my, int x, int y, int w) {
        y = segment(c, mx, my, "Arches between piers", SEG_ARCH, new String[]{"None", "Filled", "Open"},
                spec.archStyle.ordinal(), x, y, w);
        if (spec.archStyle == BridgeSpec.ArchStyle.NONE) {
            return note(c, "Filled: solid spandrel walls the full width (stone viaduct). Open: arch ribs under the deck edges only.", x, y, w);
        }
        y = materialRow(c, mx, my, "Material", BridgeSpec.Slot.ARCH, x, y, w, false);
        y = numberField(c, mx, my, "Rise (depth at the piers)", NUM_RISE, 1, BridgeSpec.MAX_RISE, x, y, w);
        y = note(c, "Arches span between pier positions, so the spacing above sets their width.", x, y, w);
        return y;
    }

    /** Label, a block icon + name, and Choose / Pick (+ clear) buttons. */
    private int materialRow(DrawContext c, int mx, int my, String label, BridgeSpec.Slot slot, int x, int y, int w, boolean clearable) {
        c.drawText(textRenderer, textRenderer.trimToWidth(label, w), x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        int h = 20;
        int pickW = 30, chooseW = 18, clearW = clearable ? 18 : 0;
        int buttonsW = pickW + 4 + chooseW + (clearable ? clearW + 4 : 0);
        FlatUi.rect(c, x, y, w - buttonsW - 4, h, FlatUi.INPUT);
        FlatUi.outline(c, x, y, w - buttonsW - 4, h, FlatUi.BORDER);
        String material = spec.material(slot);
        String name;
        if (material.isBlank()) {
            name = clearable ? "same as deck" : "none";
        } else {
            BlockState state = BridgeSpec.parseMaterial(material);
            if (state != null) {
                ItemStack icon = new ItemStack(state.getBlock());
                if (icon.getItem() != Items.AIR) {
                    c.drawItem(icon, x + 2, y + 2);
                }
                name = Text.translatable(state.getBlock().getTranslationKey()).getString();
                if (material.contains("[")) {
                    name += material.substring(material.indexOf('['));
                }
            } else {
                name = "? " + material;
            }
        }
        int nameX = x + 22;
        int nameW = w - buttonsW - 4 - 24;
        c.drawText(textRenderer, textRenderer.trimToWidth(name, nameW), nameX, y + 6, material.isBlank() ? FlatUi.TEXT_FAINT : FlatUi.TEXT, false);
        if (FlatUi.inside(mx, my, x, y, w - buttonsW - 4, h) && textRenderer.getWidth(name) > nameW) {
            hoverName = name;
        }
        int bx = x + w - buttonsW;
        FlatUi.button(c, textRenderer, "…", bx, y + 1, chooseW, h - 2, mx, my, ButtonStyle.FLAT);
        hit(bx, y + 1, chooseW, h - 2, HIT_CHOOSE, slot.ordinal(), 0);
        bx += chooseW + 4;
        boolean picking = spec.pickTarget == slot;
        FlatUi.button(c, textRenderer, "Pick", bx, y + 1, pickW, h - 2, mx, my, picking ? ButtonStyle.PRIMARY : ButtonStyle.FLAT);
        hit(bx, y + 1, pickW, h - 2, HIT_PICK, slot.ordinal(), 0);
        if (FlatUi.inside(mx, my, bx, y + 1, pickW, h - 2)) {
            hoverName = "Sneak-click a block in the world to use it here";
        }
        if (clearable) {
            bx += pickW + 4;
            FlatUi.button(c, textRenderer, "×", bx, y + 1, clearW, h - 2, mx, my, ButtonStyle.GHOST, !material.isBlank());
            if (!material.isBlank()) {
                hit(bx, y + 1, clearW, h - 2, HIT_CLEAR_MATERIAL, slot.ordinal(), 0);
            }
        }
        return y + h + 6;
    }

    // -------------------------------------------------------------- numbers

    private int numberValue(int id) {
        return switch (id) {
            case NUM_REACH -> spec.trackReach;
            case NUM_PREVIEW_TRACKS -> spec.previewTracks;
            case NUM_THICKNESS -> spec.deckThickness;
            case NUM_OVERHANG -> spec.overhang;
            case NUM_EDGE_WIDTH -> spec.edgeWidth;
            case NUM_GIRDER_DEPTH -> spec.girderDepth;
            case NUM_RAIL_HEIGHT -> spec.railingHeight;
            case NUM_RAIL_INSET -> spec.railingInset;
            case NUM_SPACING -> spec.pierSpacing;
            case NUM_PIER_THICK -> spec.pierThickness;
            case NUM_PIER_INSET -> spec.pierInset;
            case NUM_RISE -> spec.archRise;
            default -> 0;
        };
    }

    private void applyNumber(int id, int value) {
        switch (id) {
            case NUM_REACH -> spec.trackReach = value;
            case NUM_PREVIEW_TRACKS -> spec.previewTracks = value;
            case NUM_THICKNESS -> spec.deckThickness = value;
            case NUM_OVERHANG -> spec.overhang = value;
            case NUM_EDGE_WIDTH -> spec.edgeWidth = value;
            case NUM_GIRDER_DEPTH -> spec.girderDepth = value;
            case NUM_RAIL_HEIGHT -> spec.railingHeight = value;
            case NUM_RAIL_INSET -> spec.railingInset = value;
            case NUM_SPACING -> spec.pierSpacing = value;
            case NUM_PIER_THICK -> spec.pierThickness = value;
            case NUM_PIER_INSET -> spec.pierInset = value;
            case NUM_RISE -> spec.archRise = value;
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

    /** Label, a draggable slider and a number box side by side. Returns the y below it. */
    private int numberField(DrawContext c, int mx, int my, String label, int id, int min, int max, int x, int y, int w) {
        int value = numberValue(id);
        c.drawText(textRenderer, textRenderer.trimToWidth(label, w), x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        int boxW = 32;
        int trackX = x;
        int trackW = w - boxW - 8;
        int trackY = y + FIELD / 2 - 2;
        FlatUi.rect(c, trackX, trackY, trackW, 4, FlatUi.INPUT);
        float t = max > min ? Math.max(0, Math.min(1, (value - min) / (float) (max - min))) : 0;
        int knob = trackX + Math.round(t * (trackW - 6));
        boolean hot = (dragSlider != null && dragSlider[0] == id) || FlatUi.inside(mx, my, trackX, y, trackW, FIELD);
        FlatUi.rect(c, trackX, trackY, knob - trackX + 3, 4, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        FlatUi.rect(c, knob, y + 2, 6, FIELD - 4, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        hit(trackX, y, trackW, FIELD, HIT_SLIDER, id, 0);
        if (FlatUi.inside(mx, my, trackX, y, trackW, FIELD) || (dragSlider != null && dragSlider[0] == id)) {
            dragMin = min;
            dragMax = max;
        }
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
        int w = 170;
        int x = width - MARGIN - w;
        int y = 3 + FlatUi.BUTTON_HEIGHT + 2;
        List<BridgePresets.Preset> builtin = BridgePresets.BUILTIN;
        List<String> user = new ArrayList<>(BridgeUserPresets.all().keySet());
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
        String title = "Material for " + popupSlot.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        c.drawText(textRenderer, title, x + 8, y + 6, FlatUi.TEXT, false);
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
        String current = spec.material(popupSlot);
        BlockState currentState = BridgeSpec.parseMaterial(current);
        Block currentBlock = currentState == null ? null : currentState.getBlock();
        for (int r = 0; r < rowsVisible; r++) {
            int row = r + popupScroll;
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= filtered.size()) {
                    break;
                }
                Block block = filtered.get(i);
                int ix = gridX + col * cell, iy = gridY + r * cell;
                boolean hovered = FlatUi.inside(mx, my, ix, iy, cell, cell);
                if (block == currentBlock) {
                    FlatUi.rect(c, ix, iy, cell, cell, FlatUi.SELECTED);
                }
                if (hovered) {
                    FlatUi.rect(c, ix, iy, cell, cell, FlatUi.HOVER);
                    hoverName = blockName(block);
                }
                c.drawItem(new ItemStack(block), ix + 1, iy + 1);
                hit(ix, iy, cell, cell, HIT_POPUP_ITEM, i, 0);
            }
        }
        c.disableScissor();
        FlatUi.scrollThumb(c, x + w - 2, gridY, rowsVisible * cell, totalRows * cell, popupScroll * cell);
        String foot = filtered.size() + " blocks · type to search · sneak-click a block in the world for its exact state";
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

    // ---------------------------------------------------------------- scene

    /** The preview: the real builder over straight tracks on a small patch of ground. */
    private void rebuildScene() {
        sceneDirty = false;
        Map<BlockPos, BlockState> cells = new HashMap<>();
        int railY = Math.min(12, Math.max(6, Math.max(spec.archRise + 3,
                (spec.girderStyle == BridgeSpec.GirderStyle.NONE ? 0 : spec.girderDepth) + spec.deckThickness + 4)));
        int length = Math.max(12, Math.min(28, spec.pierSpacing * 2 + 2));
        BridgeBuilder.Sink sink = new BridgeBuilder.Sink() {
            @Override
            public boolean replaceable(BlockPos pos) {
                return pos.getY() >= 0 && !cells.containsKey(pos);
            }

            @Override
            public void set(BlockPos pos, BlockState state) {
                cells.put(pos.toImmutable(), state);
            }

            @Override
            public int bottomY() {
                return 0;
            }
        };
        BridgeBuilder.Path reference = new BridgeBuilder.LinePath(new Vector(0, railY, 0.5), new Vector(length, railY, 0.5));
        List<BridgeBuilder.Path> companions = new ArrayList<>();
        for (int j = 1; j < spec.previewTracks; j++) {
            companions.add(new BridgeBuilder.LinePath(new Vector(0, railY, 0.5 + 3 * j), new Vector(length, railY, 0.5 + 3 * j)));
        }
        try {
            BridgeBuilder.build(sink, reference, companions, spec.copy());
        } catch (Exception e) {
            com.stationannouncer.StationAnnouncer.LOGGER.warn("Bridge preview failed", e);
        }
        BlockState rail = Blocks.RAIL.getDefaultState().with(net.minecraft.block.RailBlock.SHAPE, RailShape.EAST_WEST);
        for (int j = 0; j < spec.previewTracks; j++) {
            for (int x = 0; x < length; x++) {
                cells.putIfAbsent(new BlockPos(x, railY, 3 * j), rail);
            }
        }
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells.keySet()) {
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        List<CreatorPreview.Cell> out = new ArrayList<>();
        BlockState ground = Blocks.GRASS_BLOCK.getDefaultState();
        for (int x = -1; x <= length; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                out.add(new CreatorPreview.Cell(new BlockPos(x, -1, z), ground));
            }
        }
        for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
            out.add(new CreatorPreview.Cell(e.getKey(), e.getValue()));
        }
        scene = out;
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
        // click outside any overlay closes it
        if (presetMenuOpen) {
            presetMenuOpen = false;
        } else if (popupSlot != null) {
            popupSlot = null;
        } else if (promptOpen) {
            promptOpen = false;
        }
        return true;
    }

    private void click(int id, int arg, int arg2, int x, int w) {
        switch (id) {
            case HIT_PART -> {
                selected = Part.values()[arg];
                inspectorScroll = 0;
            }
            case HIT_SEGMENT -> segment(arg, arg2);
            case HIT_SLIDER -> dragSlider = new int[]{arg, x, w};
            case HIT_CHOOSE -> {
                popupSlot = BridgeSpec.Slot.values()[arg];
                if (searchBox == null) {
                    searchBox = new TextBox(textRenderer, 32, false);
                    searchBox.placeholder = "Search blocks";
                }
                searchBox.load("");
                lastFilter = null;
                focus(searchBox);
            }
            case HIT_PICK -> {
                spec.pickTarget = BridgeSpec.Slot.values()[arg];
                send();
                status = "Sneak-click a block in the world";
                close();
            }
            case HIT_CLEAR_MATERIAL -> {
                spec.setMaterial(BridgeSpec.Slot.values()[arg], "");
                sceneDirty = true;
            }
            case HIT_PRESET_MENU -> presetMenuOpen = !presetMenuOpen;
            case HIT_PRESET_ROW -> {
                BridgeSpec loaded = null;
                if (arg2 == 0 && arg < BridgePresets.BUILTIN.size()) {
                    loaded = BridgePresets.BUILTIN.get(arg).spec().copy();
                } else if (arg2 == 1) {
                    List<String> names = new ArrayList<>(BridgeUserPresets.all().keySet());
                    if (arg < names.size()) {
                        loaded = BridgeUserPresets.all().get(names.get(arg)).copy();
                    }
                }
                if (loaded != null) {
                    applyPreset(loaded);
                }
                presetMenuOpen = false;
            }
            case HIT_PRESET_DELETE -> {
                List<String> names = new ArrayList<>(BridgeUserPresets.all().keySet());
                if (arg < names.size()) {
                    BridgeUserPresets.remove(names.get(arg));
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
                    BridgeUserPresets.put(name, spec);
                    status = "Saved preset \"" + name + "\"";
                }
                promptOpen = false;
                focus(null);
            }
            case HIT_PROMPT_CANCEL -> {
                promptOpen = false;
                popupSlot = null;
                focus(null);
            }
            case HIT_POPUP_ITEM -> {
                if (arg < filtered.size() && popupSlot != null) {
                    spec.setMaterial(popupSlot, Registries.BLOCK.getId(filtered.get(arg)).toString());
                    sceneDirty = true;
                }
                popupSlot = null;
                focus(null);
            }
            case HIT_SAVE -> {
                send();
                close();
            }
            case HIT_CANCEL -> close();
            case HIT_BUILD -> {
                send();
                action(MtrPillars.BRIDGE_BUILD);
                close();
            }
            case HIT_CLEAR_TRACKS -> {
                action(MtrPillars.BRIDGE_CLEAR_TRACKS);
                status = "Track selection cleared";
                BridgeService.clearManualTracks(stack); // local mirror until the server's NBT arrives
            }
            case HIT_UNDO -> {
                action(MtrPillars.BRIDGE_UNDO);
                close();
            }
            case HIT_PREVIEW -> draggingPreview = true;
            default -> {
            }
        }
    }

    private void segment(int control, int index) {
        switch (control) {
            case SEG_TRACK_MODE -> spec.trackMode = BridgeSpec.TrackMode.values()[index];
            case SEG_DECK -> spec.deck = index == 1;
            case SEG_GIRDER -> spec.girderStyle = BridgeSpec.GirderStyle.values()[index];
            case SEG_RAILING -> spec.railing = index == 1;
            case SEG_PIER -> spec.pierStyle = BridgeSpec.PierStyle.values()[index];
            case SEG_PIER_B -> spec.pierStyle = BridgeSpec.PierStyle.values()[index + 3];
            case SEG_ARCH -> spec.archStyle = BridgeSpec.ArchStyle.values()[index];
            case SEG_CAP -> spec.pierCap = index == 1;
            case SEG_FOOTING -> spec.footing = index == 1;
            default -> {
            }
        }
        sceneDirty = true;
    }

    private void applyPreset(BridgeSpec loaded) {
        BridgeSpec.TrackMode mode = spec.trackMode;
        int reach = spec.trackReach;
        int previewTracks = spec.previewTracks;
        net.minecraft.nbt.NbtCompound n = loaded.toNbt();
        BridgeSpec fresh = BridgeSpec.fromNbt(n);
        // presets describe the structure, not how tracks are chosen
        fresh.trackMode = mode;
        fresh.trackReach = reach;
        fresh.previewTracks = previewTracks;
        copyInto(fresh, spec);
        numberBoxes.clear();
        sceneDirty = true;
    }

    private static void copyInto(BridgeSpec from, BridgeSpec to) {
        BridgeSpec f = BridgeSpec.fromNbt(from.toNbt());
        to.trackMode = f.trackMode;
        to.trackReach = f.trackReach;
        to.previewTracks = f.previewTracks;
        to.deck = f.deck;
        to.deckMaterial = f.deckMaterial;
        to.deckThickness = f.deckThickness;
        to.overhang = f.overhang;
        to.edgeMaterial = f.edgeMaterial;
        to.edgeWidth = f.edgeWidth;
        to.girderStyle = f.girderStyle;
        to.girderMaterial = f.girderMaterial;
        to.girderDepth = f.girderDepth;
        to.railing = f.railing;
        to.railingMaterial = f.railingMaterial;
        to.railingHeight = f.railingHeight;
        to.railingInset = f.railingInset;
        to.pierStyle = f.pierStyle;
        to.pierMaterial = f.pierMaterial;
        to.pierSpacing = f.pierSpacing;
        to.pierThickness = f.pierThickness;
        to.pierInset = f.pierInset;
        to.pierCap = f.pierCap;
        to.capMaterial = f.capMaterial;
        to.footing = f.footing;
        to.footingMaterial = f.footingMaterial;
        to.archStyle = f.archStyle;
        to.archMaterial = f.archMaterial;
        to.archRise = f.archRise;
        to.pickTarget = f.pickTarget;
    }

    private void send() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        buf.writeNbt(spec.clamp().toNbt());
        ClientPlayNetworking.send(MtrPillars.UPDATE_BRIDGE_C2S, buf);
    }

    private void action(byte action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        buf.writeByte(action);
        ClientPlayNetworking.send(MtrPillars.BRIDGE_ACTION_C2S, buf);
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
        if (popupSlot != null) {
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
        if (keyCode == 256) { // escape
            if (popupSlot != null || promptOpen || presetMenuOpen) {
                popupSlot = null;
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
        if (keyCode == 257 || keyCode == 335) { // enter
            if (promptOpen && nameBox != null && !nameBox.getText().isBlank()) {
                click(HIT_PROMPT_OK, 0, 0, 0, 0);
                return true;
            }
            if (popupSlot != null && !filtered.isEmpty()) {
                click(HIT_POPUP_ITEM, 0, 0, 0, 0);
                return true;
            }
        }
        if (keyCode == 83 && (modifiers & 2) != 0) { // ctrl+S
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

    private String shortName(String material) {
        BlockState state = BridgeSpec.parseMaterial(material);
        return state == null ? "?" : blockName(state.getBlock());
    }
}
