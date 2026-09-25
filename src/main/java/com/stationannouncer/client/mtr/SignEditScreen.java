package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.client.mtraddon.PosterLayout;
import com.stationannouncer.mtr.MtaSignBlock;
import com.stationannouncer.mtr.MtrStationDecor;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import com.stationannouncer.mtr.sign.SignFaces;
import com.stationannouncer.mtr.sign.SignSpec;
import com.stationannouncer.mtr.sign.SignSpec.Align;
import com.stationannouncer.mtr.sign.SignSpec.Draft;
import com.stationannouncer.mtr.sign.SignSpec.Tile;
import com.stationannouncer.mtr.sign.SignSpec.TileType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * The MTA sign editor — the poster editor's design-tool layout applied to a
 * sign panel: STRUCTURE (the sign, its rows, their tiles), a live PREVIEW you
 * can click, and an INSPECTOR for the selection. Everything is {@link FlatUi};
 * nothing vanilla. Hit-testing works off rectangles recorded while drawing.
 *
 * <p>Double-sided mounts get a Front / Back switch in the top bar; the back
 * can mirror the front, carry its own sign, or stay blank. Templates fill a
 * face with the standard MTA wordings in one click; every tile is then
 * editable. Ctrl+S saves, Escape drops focus then cancels, Tab cycles boxes.</p>
 */
@Environment(EnvType.CLIENT)
public class SignEditScreen extends Screen {
    private static final int PAD = 4;
    private static final int TOP_BAR = 22;
    private static final int CARD = 20;
    private static final int FIELD = 16;

    private static final String[] DIRECTION_GLYPHS = {"↖", "↑", "↗", "←", "", "→", "↙", "↓", "↘"};
    /** Direction pad cell → arrow direction (0 = right, anticlockwise). */
    private static final int[] DIRECTION_OF_CELL = {3, 2, 1, 4, -1, 0, 5, 6, 7};
    private static final String[] TILE_LABELS = {"Bullets", "Text", "Symbol", "Station", "Exit", "Line + dest.", "Space",
            "Divider", "Badge"};
    private static final String[] TILE_TAGS = {"●", "T", "→", "St", "Ex", "◐", "·", "|", "Bg"};

    private final StationDecorBlockEntity entity;
    private final SignContext ctx;
    private final boolean doubleSided;
    private final float canvasWidth;
    private final float canvasHeight;

    // Working state.
    private final Draft frontDraft;
    private final Draft backDraft;
    private boolean frontOn;
    private SignFaces.BackMode backMode;
    private boolean editingBack;

    // The sign in the world: the centre of its run on the block-centre plane and
    // the way its FRONT face looks, for the "in world" view's camera.
    private net.minecraft.util.math.Vec3d signAnchor = net.minecraft.util.math.Vec3d.ZERO;
    private net.minecraft.util.math.Direction frontNormal = net.minecraft.util.math.Direction.NORTH;
    /** Centre view: the flat editor canvas, or the real block through a detached camera. */
    private boolean worldView;
    private final SignWorldView worldCamera = new SignWorldView();
    private boolean orbiting;
    /** The draft last pushed onto the block, so the world shows what is being edited. */
    private SignFaces pushedPreview;

    // Dragging a tile on the canvas: where it started, and the guides it snapped to.
    private boolean draggingTile;
    private double dragStartMx;
    private double dragStartMy;
    private float dragStartDx;
    private float dragStartDy;
    private boolean guideX;
    private boolean guideY;

    /** Selection: row -1 = the sign itself; tile -1 = the row. */
    private int selRow = -1;
    private int selTile = -1;

    private final TextBox textBox;
    private final TextBox nameBox;
    private final TextBox cornerBox;
    private final TextBox customBox;
    private final TextBox badgeBox;
    private final TextBox templateBox;
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    /** Inspector scrolling: the pane is shorter than its content on a small window. */
    private int inspectorScroll;
    private int inspectorContent;
    private int inspectorView;
    /** Vertical clip for hit rectangles and text boxes recorded while a scrolled pane draws. */
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;
    private TextBox focused;
    /** Plate-to-preview mapping, recomputed each frame from the plate and the panel box. */
    private float previewMinX;
    private float previewMinY;

    // Geometry.
    private int leftWidth = 148;
    private int rightWidth = 214;
    private int leftX;
    private int leftY;
    private int leftH;
    private int centreX;
    private int centreW;
    private int rightX;
    private int rightY;
    private int rightH;
    private int paneBottom;
    private int previewX;
    private int previewY;
    private float previewScale;
    private SignLayout.Metrics lastMetrics;
    private int structureScroll;
    private int structureContent;
    private boolean addMenuOpen;
    private boolean templateMenuOpen;

    /** {x, y, w, h, id, arg, arg2}. */
    private final List<int[]> hits = new ArrayList<>();
    private static final int HIT_CARD = 1;
    private static final int HIT_MOVE_UP = 2;
    private static final int HIT_MOVE_DOWN = 3;
    private static final int HIT_DELETE = 4;
    private static final int HIT_ADD = 5;
    private static final int HIT_ADD_TYPE = 6;
    private static final int HIT_SAVE = 7;
    private static final int HIT_CANCEL = 8;
    private static final int HIT_CHIP = 9;
    private static final int HIT_ADD_LINE = 10;
    private static final int HIT_SEGMENT = 11;
    private static final int HIT_DIRECTION = 12;
    private static final int HIT_TOKEN = 13;
    private static final int HIT_DELETE_SEL = 14;
    private static final int HIT_PREVIEW = 15;
    private static final int HIT_POPUP_ROW = 16;
    private static final int HIT_POPUP = 17;
    private static final int HIT_ADD_ROW = 18;
    private static final int HIT_FACE = 19;
    private static final int HIT_TEMPLATES = 20;
    private static final int HIT_TEMPLATE = 21;
    private static final int HIT_TOGGLE = 22;
    private static final int HIT_PICK = 23;
    private static final int HIT_SLIDER = 24;
    private static final int HIT_PANEL_RESET = 25;
    private static final int HIT_VIEW = 26;
    private static final int HIT_WORLD = 27;
    private static final int HIT_TILE_RESET = 28;
    private static final int HIT_WORLD_RESET = 29;
    private static final int HIT_COLOR = 30;
    private static final int HIT_COPY = 31;
    private static final int HIT_PASTE = 32;
    private static final int HIT_USER_TEMPLATE = 33;
    private static final int HIT_USER_TEMPLATE_DELETE = 34;
    private static final int HIT_TEMPLATE_SAVE = 35;

    /** The copied face: survives closing the editor, so a sign can be pasted onto another block. */
    private static SignSpec clipboard;

    // Numeric fields (slider + number box), by id.
    private static final int NUM_PANEL_W = 1;
    private static final int NUM_PANEL_H = 2;
    private static final int NUM_PANEL_X = 3;
    private static final int NUM_PANEL_Y = 4;
    private static final int NUM_PANEL_SCALE = 5;
    private static final int NUM_TILE_SCALE = 6;
    private static final int NUM_TILE_DX = 7;
    private static final int NUM_TILE_DY = 8;
    private static final int NUM_SPACER = 9;

    /** One number box per numeric field, created on first use. */
    private final java.util.Map<Integer, TextBox> numberBoxes = new java.util.HashMap<>();
    /** The slider being dragged: {id, trackX, trackW} plus its range, or null. */
    private int[] dragSlider;
    private float dragMin;
    private float dragMax;
    private float dragStep;

    // Segmented controls / toggles, by arg.
    private static final int SEG_STYLE = 1;
    private static final int SEG_BACK = 2;
    private static final int SEG_ALIGN = 3;
    private static final int SEG_TEXT_SIZE = 4;
    private static final int SEG_ARROW_SIDE = 5;
    private static final int SEG_DEST_MODE = 6;
    private static final int SEG_SPACER = 7;
    private static final int SEG_WHEELCHAIR = 8;
    private static final int SEG_PLACE = 9;
    private static final int TOGGLE_FRONT = 1;
    private static final int TOGGLE_AUTO = 2;
    private static final int TOGGLE_UPPER = 3;
    private static final int TOGGLE_EXIT_WORD = 4;
    private static final int TOGGLE_EXIT_STREETS = 5;
    private static final int TOGGLE_EXIT_NAME = 6;
    private static final int TOGGLE_STACKED = 7;

    private enum Popup { LINE_BULLET, LINE_DIAMOND, LINE_TOKEN, LINE_TOKEN_DIAMOND, ROUTE, EXIT }

    private Popup popup;
    private int popupScroll;
    private List<PosterLayout.LineOption> popupLines = List.of();
    private List<SignContext.RouteOption> popupRoutes = List.of();
    private List<SignContext.Exit> popupExits = List.of();
    private String toast;
    private long toastUntil;

    public SignEditScreen(StationDecorBlockEntity clicked) {
        super(Text.translatable("gui.station_announcer.mta_sign.title"));
        // A merged run is one sign: edit the segment the run actually draws.
        StationDecorBlockEntity entity = MtaSignPainter.signOwner(clicked);
        this.entity = entity;
        this.ctx = MtaSignPainter.context(entity.getPos());
        var state = entity.getCachedState();
        var world = MinecraftClient.getInstance().world;
        net.minecraft.block.Block block = state.getBlock();
        if (block instanceof MtaSignBlock sign) {
            doubleSided = MtaSignBlock.doubleSided(state);
            canvasWidth = 64 * MtaSignPainter.runOf(entity);
            canvasHeight = sign.plateHeight() * 4;
        } else if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            net.minecraft.util.math.Direction dir = com.stationannouncer.mtr.RailingSignBlock.frontOf(state).rotateYClockwise();
            int run = world == null ? 1 : MtaSignPainter.legacyRun(world, entity.getPos(), dir,
                    st -> st.getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock);
            doubleSided = true;
            canvasWidth = 64 * run - 10;
            canvasHeight = 46;
        } else if (block instanceof com.stationannouncer.mtr.ElEntranceSignBlock) {
            net.minecraft.util.math.Direction facing = state.get(com.stationannouncer.block.FacingDecorBlock.FACING);
            int run = world == null ? 1 : MtaSignPainter.legacyRun(world, entity.getPos(), facing.rotateYClockwise(),
                    st -> st.getBlock() instanceof com.stationannouncer.mtr.ElEntranceSignBlock
                            && st.get(com.stationannouncer.block.FacingDecorBlock.FACING) == facing);
            doubleSided = true;
            canvasWidth = 64 * run - 6;
            canvasHeight = 38;
        } else if (block instanceof com.stationannouncer.mtr.ElNameBoardBlock) {
            doubleSided = state.get(com.stationannouncer.mtr.ElNameBoardBlock.MOUNT)
                    != com.stationannouncer.mtr.ElNameBoardBlock.Mount.WALL;
            // merged el_sign boards are one sign as wide as the run
            int run = world == null ? 1 : MtaSignPainter.legacyRun(world, entity.getPos(),
                    state.get(com.stationannouncer.block.FacingDecorBlock.FACING).rotateYClockwise(),
                    st -> com.stationannouncer.mtr.ElNameBoardBlock.sameSign(state, st));
            canvasWidth = 64 * run - 8;
            canvasHeight = 28;
        } else if (block instanceof com.stationannouncer.mtr.StationColumnBlock column) {
            doubleSided = true;
            canvasWidth = column.boardWidth();
            canvasHeight = column.boardHeight();
        } else if (block instanceof com.stationannouncer.mtr.ElWallSignBlock
                || block instanceof com.stationannouncer.mtr.ElRailingSignBlock) {
            net.minecraft.util.math.Direction facing = state.get(com.stationannouncer.block.FacingDecorBlock.FACING);
            int run = world == null ? 1 : MtaSignPainter.legacyRun(world, entity.getPos(), facing.rotateYClockwise(),
                    st -> st.getBlock() == block
                            && st.get(com.stationannouncer.block.FacingDecorBlock.FACING) == facing);
            doubleSided = false;
            canvasWidth = 64 * run - 8;
            canvasHeight = block instanceof com.stationannouncer.mtr.ElWallSignBlock ? 40 : 52;
        } else {
            doubleSided = true;
            canvasWidth = 64;
            canvasHeight = 32;
        }
        SignFaces faces = entity.getSign() != null ? entity.getSign()
                : block instanceof MtaSignBlock ? SignFaces.EMPTY
                : com.stationannouncer.mtr.sign.LegacySigns.of(entity);
        locate(state, world);
        frontDraft = new Draft(faces.front());
        backDraft = new Draft(faces.back());
        frontOn = faces.frontOn();
        backMode = faces.backMode();

        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        textBox = new TextBox(font, SignSpec.MAX_TEXT, true);
        textBox.placeholder = "Sign text. Enter = next line (up to 3).";
        textBox.onChange(v -> updateTile(t -> t.withText(v)));
        badgeBox = new TextBox(font, 16, false);
        badgeBox.placeholder = "LIRR, M15 SBS, PATH…";
        badgeBox.onChange(v -> updateTile(t -> t.withText(v)));
        templateBox = new TextBox(font, SignUserTemplates.MAX_NAME, false);
        templateBox.placeholder = "Name…";
        nameBox = new TextBox(font, SignSpec.MAX_TEXT, false);
        nameBox.placeholder = "(station name)";
        nameBox.onChange(v -> updateTile(t -> t.withText(v)));
        cornerBox = new TextBox(font, SignSpec.MAX_ARG, false);
        cornerBox.placeholder = "e.g. NE corner";
        cornerBox.onChange(v -> updateTile(t -> t.withArg(v)));
        customBox = new TextBox(font, SignSpec.MAX_TEXT, false);
        customBox.placeholder = "Uptown & The Bronx";
        customBox.onChange(v -> updateTile(t -> t.withText(v)));
        if (!frontDraft.rows.isEmpty() && !frontDraft.rows.get(0).tiles.isEmpty()) {
            select(0, 0);
        }
    }

    // ------------------------------------------------------------ in world

    /**
     * Where this sign is in the world: walks the merged run the block belongs
     * to and takes its middle, at the height the plate hangs. Depth is the
     * block's centre plane — close enough to aim a camera at.
     */
    private void locate(net.minecraft.block.BlockState state, net.minecraft.client.world.ClientWorld world) {
        net.minecraft.block.Block block = state.getBlock();
        net.minecraft.util.math.Direction normal = state.contains(com.stationannouncer.block.FacingDecorBlock.FACING)
                ? state.get(com.stationannouncer.block.FacingDecorBlock.FACING) : net.minecraft.util.math.Direction.NORTH;
        java.util.function.Predicate<net.minecraft.block.BlockState> family = null;
        float yFrac = 0.5f;
        if (block instanceof MtaSignBlock sign) {
            net.minecraft.util.math.Direction facing = normal;
            family = st -> st.getBlock() == block
                    && st.get(com.stationannouncer.block.FacingDecorBlock.FACING) == facing
                    && st.get(MtaSignBlock.MOUNT) == state.get(MtaSignBlock.MOUNT);
            yFrac = sign.plateHeight() < 16 && state.get(MtaSignBlock.MOUNT).asString().equals("standing") ? 0.75f : 0.5f;
        } else if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            normal = com.stationannouncer.mtr.RailingSignBlock.frontOf(state);
            family = st -> st.getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock;
            yFrac = 0.8f;
        } else if (block instanceof com.stationannouncer.mtr.ElNameBoardBlock) {
            family = st -> com.stationannouncer.mtr.ElNameBoardBlock.sameSign(state, st);
            yFrac = state.get(com.stationannouncer.mtr.ElNameBoardBlock.MOUNT)
                    == com.stationannouncer.mtr.ElNameBoardBlock.Mount.STANDING ? 0.6f : 0.53f;
        } else if (block instanceof com.stationannouncer.mtr.StationColumnBlock) {
            yFrac = 0.625f;
        } else if (block instanceof com.stationannouncer.mtr.ElEntranceSignBlock
                || block instanceof com.stationannouncer.mtr.ElWallSignBlock
                || block instanceof com.stationannouncer.mtr.ElRailingSignBlock) {
            net.minecraft.util.math.Direction facing = normal;
            family = st -> st.getBlock() == block
                    && st.get(com.stationannouncer.block.FacingDecorBlock.FACING) == facing;
            yFrac = block instanceof com.stationannouncer.mtr.ElRailingSignBlock ? 0.66f : 0.5f;
        }
        net.minecraft.util.math.BlockPos pos = entity.getPos();
        double along = 0;
        if (family != null && world != null) {
            net.minecraft.util.math.Direction posDir = normal.rotateYClockwise();
            int before = 0;
            while (before < 16 && family.test(world.getBlockState(pos.offset(posDir.getOpposite(), before + 1)))) {
                before++;
            }
            int after = 0;
            while (after < 16 && family.test(world.getBlockState(pos.offset(posDir, after + 1)))) {
                after++;
            }
            along = (after - before) / 2.0;
            signAnchor = net.minecraft.util.math.Vec3d.ofBottomCenter(pos)
                    .add(posDir.getOffsetX() * along, yFrac, posDir.getOffsetZ() * along);
        } else {
            signAnchor = net.minecraft.util.math.Vec3d.ofBottomCenter(pos).add(0, yFrac, 0);
        }
        frontNormal = normal;
    }

    /** The face being edited looks this way. */
    private net.minecraft.util.math.Vec3d viewNormal() {
        net.minecraft.util.math.Direction n = editingBack ? frontNormal.getOpposite() : frontNormal;
        return new net.minecraft.util.math.Vec3d(n.getOffsetX(), 0, n.getOffsetZ());
    }

    /** The centre of the painted panel in the world (the plate may be shifted off its block). */
    private net.minecraft.util.math.Vec3d viewTarget() {
        Draft d = draft();
        // Canvas x grows toward the viewer's right, which is the normal turned counter-clockwise.
        net.minecraft.util.math.Direction n = editingBack ? frontNormal.getOpposite() : frontNormal;
        net.minecraft.util.math.Direction right = n.rotateYCounterclockwise();
        double dx = d.panelDx / 64.0;
        return signAnchor.add(right.getOffsetX() * dx, -d.panelDy / 64.0, right.getOffsetZ() * dx);
    }

    private float tanHalfV() {
        double fov = client == null ? 70 : client.options.getFov().getValue();
        return (float) Math.tan(Math.toRadians(fov) / 2.0);
    }

    /** Camera distance at which the whole panel, with some of its surroundings, fits the centre pane. */
    private float fitDistance() {
        Draft d = draft();
        float w = Math.max(canvasWidth, d.panelWidth) / 64.0f;
        float h = Math.max(canvasHeight, d.panelHeight) / 64.0f;
        float tanV = tanHalfV();
        float tanH = tanV * width / Math.max(1, height);
        float fracX = Math.max(0.15f, centreW / (float) Math.max(1, width));
        float fracY = Math.max(0.15f, (leftH - 30) / (float) Math.max(1, height));
        float byWidth = (w / 2.0f * 1.7f) / (tanH * fracX);
        float byHeight = (h / 2.0f * 2.2f) / (tanV * fracY);
        return Math.max(1.4f, Math.max(byWidth, byHeight));
    }

    private void setWorldView(boolean on) {
        if (on == worldView) {
            return;
        }
        worldView = on;
        draggingTile = false;
        if (on) {
            focus(null);
            worldCamera.enter(viewTarget(), viewNormal(), fitDistance());
            worldView = worldCamera.active();
        } else {
            worldCamera.exit();
        }
    }

    /** Dev rig: flip the centre view headlessly. */
    void viewForDev(String mode) {
        setWorldView("world".equals(mode));
    }

    @Override
    public void removed() {
        worldCamera.exit();
        entity.setPreviewSign(null);
        MtaSignPainter.invalidateRuns();
        super.removed();
    }

    // ------------------------------------------------------------- drafts

    private Draft draft() {
        return editingBack ? backDraft : frontDraft;
    }

    private Draft.RowDraft selectedRow() {
        Draft d = draft();
        return selRow >= 0 && selRow < d.rows.size() ? d.rows.get(selRow) : null;
    }

    private Tile selectedTile() {
        Draft.RowDraft row = selectedRow();
        return row != null && selTile >= 0 && selTile < row.tiles.size() ? row.tiles.get(selTile) : null;
    }

    private void updateTile(java.util.function.UnaryOperator<Tile> change) {
        Draft.RowDraft row = selectedRow();
        Tile tile = selectedTile();
        if (row != null && tile != null) {
            row.tiles.set(selTile, change.apply(tile));
        }
    }

    private void select(int row, int tile) {
        Draft d = draft();
        if (row >= d.rows.size()) {
            row = d.rows.size() - 1;
        }
        if (row != selRow || tile != selTile) {
            inspectorScroll = 0;
        }
        selRow = row;
        selTile = row < 0 ? -1 : Math.min(tile, d.rows.get(row).tiles.size() - 1);
        Tile selected = selectedTile();
        if (selected != null) {
            switch (selected.type()) {
                case TEXT -> textBox.load(selected.text());
                case STATION_NAME -> nameBox.load(selected.text());
                case EXIT -> cornerBox.load(selected.arg());
                case DESTINATION -> customBox.load(selected.text());
                case BADGE -> badgeBox.load(selected.text());
                default -> {
                }
            }
        }
        addMenuOpen = false;
        templateMenuOpen = false;
        focus(null);
    }

    /** Dev rig only: "sign" / "row:N" / "tile:R:T" / "templates" / "add". */
    void selectForDev(String what) {
        String[] p = what.split(":");
        switch (p[0]) {
            case "row" -> select(Integer.parseInt(p[1]), -1);
            case "tile" -> select(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            case "templates" -> templateMenuOpen = true;
            case "add" -> addMenuOpen = true;
            case "back" -> editingBack = true;
            default -> select(-1, -1);
        }
    }

    private void focus(TextBox box) {
        if (focused != null && focused != box) {
            focused.setFocused(false);
        }
        focused = box;
        if (box != null) {
            box.setFocused(true);
        }
    }

    private void toast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2_500;
    }

    // -------------------------------------------------------------- edits

    private void addRow() {
        Draft d = draft();
        if (d.rows.size() >= SignSpec.MAX_ROWS) {
            toast("At most " + SignSpec.MAX_ROWS + " rows fit on a sign");
            return;
        }
        int at = selRow >= 0 ? selRow + 1 : d.rows.size();
        d.rows.add(at, new Draft.RowDraft());
        select(at, -1);
        addMenuOpen = true;
    }

    private void addTile(TileType type) {
        Draft d = draft();
        if (d.rows.isEmpty()) {
            d.rows.add(new Draft.RowDraft());
            selRow = 0;
        }
        if (selRow < 0) {
            selRow = d.rows.size() - 1;
        }
        Draft.RowDraft row = d.rows.get(selRow);
        if (row.tiles.size() >= SignSpec.MAX_TILES) {
            toast("At most " + SignSpec.MAX_TILES + " tiles fit in a row");
            return;
        }
        int at = selTile >= 0 ? selTile + 1 : row.tiles.size();
        row.tiles.add(at, Tile.of(type));
        select(selRow, at);
        if (type == TileType.TEXT) {
            focus(textBox);
        }
    }

    private void moveSelection(int delta) {
        Draft d = draft();
        if (selRow < 0) {
            return;
        }
        if (selTile < 0) {
            int target = selRow + delta;
            if (target < 0 || target >= d.rows.size()) {
                return;
            }
            Draft.RowDraft moved = d.rows.remove(selRow);
            d.rows.add(target, moved);
            select(target, -1);
        } else {
            Draft.RowDraft row = d.rows.get(selRow);
            int target = selTile + delta;
            if (target < 0 || target >= row.tiles.size()) {
                return;
            }
            Tile moved = row.tiles.remove(selTile);
            row.tiles.add(target, moved);
            select(selRow, target);
        }
    }

    private void deleteSelection() {
        Draft d = draft();
        if (selRow < 0) {
            return;
        }
        if (selTile < 0) {
            d.rows.remove(selRow);
            select(Math.min(selRow, d.rows.size() - 1), -1);
        } else {
            Draft.RowDraft row = d.rows.get(selRow);
            row.tiles.remove(selTile);
            select(selRow, row.tiles.isEmpty() ? -1 : Math.min(selTile, row.tiles.size() - 1));
        }
    }

    private void applyTemplate(int index) {
        Draft d = draft();
        d.rows.clear();
        d.rows.addAll(SignTemplates.rows(index, canvasHeight >= 64));
        templateMenuOpen = false;
        select(0, 0);
    }

    private void openPopup(Popup mode) {
        popup = mode;
        popupScroll = 0;
        switch (mode) {
            case ROUTE -> popupRoutes = SignContext.routes();
            case EXIT -> popupExits = ctx.exits();
            default -> popupLines = PosterLayout.lines();
        }
    }

    private void choosePopup(int index) {
        Popup mode = popup;
        popup = null;
        switch (mode) {
            case LINE_BULLET, LINE_DIAMOND -> {
                if (index < popupLines.size()) {
                    String name = (mode == Popup.LINE_DIAMOND ? "d:" : "") + popupLines.get(index).name();
                    updateTile(t -> {
                        List<String> routes = new ArrayList<>(t.routes());
                        if (routes.size() >= SignSpec.MAX_ROUTES) {
                            toast("At most " + SignSpec.MAX_ROUTES + " bullets per tile");
                            return t;
                        }
                        routes.add(name);
                        return t.withRoutes(routes).withArg("");
                    });
                }
            }
            case LINE_TOKEN, LINE_TOKEN_DIAMOND -> {
                if (index < popupLines.size()) {
                    insertToken((mode == Popup.LINE_TOKEN_DIAMOND ? "{d:" : "{b:") + popupLines.get(index).name() + "}");
                }
            }
            case ROUTE -> {
                if (index < popupRoutes.size()) {
                    String name = popupRoutes.get(index).routeName();
                    updateTile(t -> t.withRoutes(List.of(name)));
                }
            }
            case EXIT -> {
                if (index < popupExits.size()) {
                    String name = popupExits.get(index).name();
                    updateTile(t -> t.withText(name));
                }
            }
        }
    }

    /** Replaces the face being edited with {@code spec}: rows, style and its own plate. */
    private void loadFace(SignSpec spec) {
        Draft source = new Draft(spec);
        Draft d = draft();
        d.style = source.style;
        d.rows.clear();
        d.rows.addAll(source.rows);
        d.panelWidth = source.panelWidth;
        d.panelHeight = source.panelHeight;
        d.panelDx = source.panelDx;
        d.panelDy = source.panelDy;
        d.panelScale = source.panelScale;
        templateMenuOpen = false;
        select(-1, -1);
    }

    private void copyFace() {
        clipboard = draft().build();
        if (client != null) {
            client.keyboard.setClipboard(clipboard.toJson().toString());
        }
        toast("Copied this face — open another sign and Paste");
    }

    private void pasteFace() {
        SignSpec spec = clipboard;
        if (spec == null && client != null) {
            // A sign copied as JSON in another session (or shared as text) pastes too.
            try {
                String text = client.keyboard.getClipboard();
                if (text != null && text.startsWith("{") && text.length() <= SignSpec.MAX_JSON) {
                    spec = SignSpec.fromJson(com.google.gson.JsonParser.parseString(text).getAsJsonObject());
                }
            } catch (Exception ignored) {
                spec = null;
            }
        }
        if (spec == null) {
            toast("Nothing copied yet");
            return;
        }
        loadFace(spec);
    }

    private void saveTemplate() {
        String name = templateBox.getText().trim();
        if (name.isEmpty()) {
            toast("Type a name for the template first");
            focus(templateBox);
            return;
        }
        if (SignUserTemplates.put(name, draft().build())) {
            templateBox.load("");
            focus(null);
        } else {
            toast("Template list is full — delete one first");
        }
    }

    private void insertToken(String token) {
        Tile tile = selectedTile();
        if (tile == null || tile.type() != TileType.TEXT) {
            toast("Select a text tile first");
            return;
        }
        textBox.insertToken(token);
        focus(textBox);
    }

    private SignFaces build() {
        return new SignFaces(frontOn, frontDraft.build(), doubleSided ? backMode : SignFaces.BackMode.SAME,
                backDraft.build());
    }

    /** Sends the sign; false (and a toast) when it is too big for the wire, so nothing is lost by closing. */
    private boolean save() {
        SignFaces faces = build();
        if (faces.toJson().toString().length() > SignSpec.MAX_JSON * 2) {
            toast("Too much on this sign to save — remove a few tiles");
            return false;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(entity.getPos());
        faces.write(buf);
        ClientPlayNetworking.send(MtrStationDecor.UPDATE_SIGN_C2S, buf);
        return true;
    }

    // ------------------------------------------------------------- layout

    @Override
    protected void init() {
        boolean compact = width < 560 || height < 320;
        leftWidth = compact ? 108 : 150;
        rightWidth = compact ? 176 : 220;
        leftX = PAD;
        leftY = TOP_BAR + PAD;
        paneBottom = height - PAD;
        leftH = paneBottom - leftY;
        rightX = width - PAD - rightWidth;
        rightY = leftY;
        rightH = leftH;
        centreX = leftX + leftWidth + PAD;
        centreW = Math.max(40, rightX - PAD - centreX);
        previewScale = Math.max(0.3f, Math.min((centreW - 12) / canvasWidth, (leftH - 40) / canvasHeight));
        previewX = centreX + (centreW - Math.round(canvasWidth * previewScale)) / 2;
        previewY = leftY + 10;
    }

    // -------------------------------------------------------------- input

    private void hit(int x, int y, int w, int h, int id, int arg, int arg2) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom <= top) {
            return;
        }
        hits.add(new int[]{x, top, w, bottom - top, id, arg, arg2});
    }

    /** Registers a text box for clicks and tabbing unless it is scrolled out of its pane. */
    private void showBox(TextBox box, int y, int h) {
        if (y + h > clipTop && y < clipBottom) {
            visibleBoxes.add(box);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (popup != null) {
            for (int[] r : hits) {
                if (r[4] == HIT_POPUP_ROW && FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    choosePopup(r[5]);
                    return true;
                }
            }
            for (int[] r : hits) {
                if (r[4] == HIT_POPUP && FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    return true;
                }
            }
            popup = null;
            return true;
        }
        for (TextBox box : visibleBoxes) {
            if (box.contains(mx, my)) {
                focus(box);
                box.mouseClicked(mx, my, button);
                return true;
            }
        }
        for (int[] r : hits) {
            if (!FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                continue;
            }
            handle(r[4], r[5], r[6], mx, my);
            return true;
        }
        focus(null);
        return super.mouseClicked(mx, my, button);
    }

    private void handle(int id, int arg, int arg2, double mx, double my) {
        switch (id) {
            case HIT_CARD -> select(arg, arg2);
            case HIT_MOVE_UP -> moveSelection(-1);
            case HIT_MOVE_DOWN -> moveSelection(1);
            case HIT_DELETE, HIT_DELETE_SEL -> deleteSelection();
            case HIT_ADD -> {
                addMenuOpen = !addMenuOpen;
                templateMenuOpen = false;
            }
            case HIT_ADD_TYPE -> {
                addMenuOpen = false;
                addTile(TileType.byOrdinal(arg));
            }
            case HIT_ADD_ROW -> addRow();
            case HIT_TEMPLATES -> {
                templateMenuOpen = !templateMenuOpen;
                addMenuOpen = false;
            }
            case HIT_TEMPLATE -> applyTemplate(arg);
            case HIT_USER_TEMPLATE -> {
                List<String> names = SignUserTemplates.names();
                if (arg >= 0 && arg < names.size()) {
                    loadFace(SignUserTemplates.all().get(names.get(arg)));
                }
            }
            case HIT_USER_TEMPLATE_DELETE -> {
                List<String> names = SignUserTemplates.names();
                if (arg >= 0 && arg < names.size()) {
                    SignUserTemplates.remove(names.get(arg));
                }
            }
            case HIT_TEMPLATE_SAVE -> saveTemplate();
            case HIT_COPY -> copyFace();
            case HIT_PASTE -> pasteFace();
            case HIT_COLOR -> updateTile(t -> t.withArg(arg <= 0 ? "" : SignLayout.COLOR_NAMES[arg - 1]));
            case HIT_SAVE -> {
                if (save()) {
                    close();
                }
            }
            case HIT_CANCEL -> close();
            case HIT_FACE -> {
                editingBack = arg == 1;
                if (editingBack && backMode != SignFaces.BackMode.OWN) {
                    backMode = SignFaces.BackMode.OWN;
                    if (backDraft.rows.isEmpty()) {
                        backDraft.rows.addAll(new Draft(frontDraft.build()).rows);
                    }
                }
                select(-1, -1);
            }
            case HIT_CHIP -> updateTile(t -> {
                List<String> routes = new ArrayList<>(t.routes());
                if (arg < routes.size()) {
                    routes.remove(arg);
                }
                return t.withRoutes(routes);
            });
            case HIT_ADD_LINE -> openPopup(arg == 1 ? Popup.LINE_DIAMOND : Popup.LINE_BULLET);
            case HIT_PICK -> openPopup(arg == 1 ? Popup.EXIT : Popup.ROUTE);
            case HIT_SEGMENT -> segment(arg, arg2);
            case HIT_TOGGLE -> toggle(arg);
            case HIT_DIRECTION -> updateTile(t -> t.withNum(arg));
            case HIT_TOKEN -> {
                switch (arg) {
                    case 0 -> openPopup(Popup.LINE_TOKEN);
                    case 1 -> openPopup(Popup.LINE_TOKEN_DIAMOND);
                    case 2 -> insertToken("{wc}");
                    case 3 -> insertToken("{<}");
                    case 4 -> insertToken("{^}");
                    case 5 -> insertToken("{v}");
                    case 6 -> insertToken("{>}");
                    default -> {
                        if (arg >= 100 && arg - 100 < SignSymbols.KEYS.length) {
                            insertToken("{s:" + SignSymbols.KEYS[arg - 100] + "}");
                        }
                    }
                }
            }
            case HIT_PREVIEW -> {
                selectFromPreview(mx, my);
                Tile grabbed = selectedTile();
                if (grabbed != null) {
                    focus(null);
                    draggingTile = true;
                    dragStartMx = mx;
                    dragStartMy = my;
                    dragStartDx = grabbed.dx();
                    dragStartDy = grabbed.dy();
                }
            }
            case HIT_VIEW -> setWorldView(arg == 1);
            case HIT_WORLD -> orbiting = true;
            case HIT_WORLD_RESET -> worldCamera.reset(fitDistance());
            case HIT_TILE_RESET -> updateTile(t -> t.withOffset(0, 0));
            case HIT_SLIDER -> {
                dragSlider = new int[]{arg, arg2, 0};
                for (int[] r : hits) {
                    if (r[4] == HIT_SLIDER && r[5] == arg) {
                        dragSlider[1] = r[0];
                        dragSlider[2] = r[2];
                    }
                }
                sliderDrag(mx);
            }
            case HIT_PANEL_RESET -> {
                Draft d = draft();
                d.panelWidth = 0;
                d.panelHeight = 0;
                d.panelDx = 0;
                d.panelDy = 0;
                d.panelScale = 1;
            }
            default -> {
            }
        }
    }

    private void segment(int which, int chosen) {
        switch (which) {
            case SEG_STYLE -> draft().style = SignSpec.Style.byOrdinal(chosen);
            case SEG_BACK -> {
                backMode = SignFaces.BackMode.byOrdinal(chosen);
                if (backMode != SignFaces.BackMode.OWN && editingBack) {
                    editingBack = false;
                    select(-1, -1);
                }
            }
            case SEG_ALIGN -> {
                Draft.RowDraft row = selectedRow();
                if (row != null) {
                    row.align = chosen == 1 ? Align.CENTER : chosen == 2 ? Align.RIGHT : Align.LEFT;
                }
            }
            case SEG_TEXT_SIZE -> updateTile(t -> t.withNum(chosen == 0 ? 1 : chosen == 2 ? 2 : 0));
            case SEG_ARROW_SIDE -> updateTile(t -> t.withArg(chosen == 0 ? "left" : chosen == 2 ? "right" : ""));
            case SEG_DEST_MODE -> updateTile(t -> t.withNum(chosen));
            // An explicit place replaces an edge arrow's older left/right pin.
            case SEG_PLACE -> updateTile(t -> (t.type() == TileType.ARROW ? t.withArg("") : t)
                    .withPlace(SignSpec.Place.values()[Math.max(0, Math.min(3, chosen))]));
            case SEG_WHEELCHAIR -> updateTile(t -> t.withArg(chosen == 1 ? "wc" : chosen == 2 ? "nowc" : ""));
            default -> {
            }
        }
    }

    private void toggle(int which) {
        switch (which) {
            case TOGGLE_FRONT -> frontOn = !frontOn;
            case TOGGLE_AUTO -> updateTile(t -> t.withArg("auto".equals(t.arg()) ? "" : "auto"));
            case TOGGLE_UPPER -> updateTile(t -> t.withNum(t.num() ^ SignSpec.NAME_UPPER));
            case TOGGLE_STACKED -> updateTile(t -> t.withNum(t.num() ^ SignSpec.NAME_STACKED));
            case TOGGLE_EXIT_WORD -> updateTile(t -> t.withNum(t.num() ^ SignSpec.EXIT_WORD));
            case TOGGLE_EXIT_STREETS -> updateTile(t -> t.withNum(t.num() ^ SignSpec.EXIT_STREETS));
            case TOGGLE_EXIT_NAME -> updateTile(t -> t.withNum(t.num() ^ SignSpec.EXIT_NAME));
            default -> {
            }
        }
    }

    private void selectFromPreview(double mx, double my) {
        if (lastMetrics == null) {
            return;
        }
        float cx = (float) ((mx - previewX) / previewScale);
        float cy = (float) ((my - previewY) / previewScale);
        SignLayout.TileBounds tile = lastMetrics.at(cx, cy);
        if (tile != null) {
            select(tile.row(), tile.tile());
            return;
        }
        int row = lastMetrics.rowAt(cy);
        select(row, -1);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSlider != null) {
            sliderDrag(mx);
            return true;
        }
        if (orbiting) {
            worldCamera.orbit(dx, dy);
            return true;
        }
        if (draggingTile) {
            dragTile(mx, my);
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
        orbiting = false;
        draggingTile = false;
        guideX = false;
        guideY = false;
        if (focused != null) {
            focused.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    /**
     * Moves the grabbed tile with the mouse: its offset from where the row
     * put it, in half canvas units. Within a couple of pixels it snaps back
     * onto its own row line (dy 0) and its own slot (dx 0) — or onto the
     * panel's centre line — and the guide that caught it is drawn. Alt = free.
     */
    private void dragTile(double mx, double my) {
        Tile tile = selectedTile();
        if (tile == null || previewScale <= 0) {
            return;
        }
        if (Math.abs(mx - dragStartMx) < 2 && Math.abs(my - dragStartMy) < 2 && tile.dx() == dragStartDx
                && tile.dy() == dragStartDy) {
            return; // a click, not a drag
        }
        float dx = snap(dragStartDx + (float) ((mx - dragStartMx) / previewScale), 0.5f);
        float dy = snap(dragStartDy + (float) ((my - dragStartMy) / previewScale), 0.5f);
        guideX = false;
        guideY = false;
        if (!Screen.hasAltDown()) {
            float reach = 3.0f / previewScale;
            if (Math.abs(dy) <= reach) {
                dy = 0;
                guideY = true;
            }
            if (Math.abs(dx) <= reach) {
                dx = 0;
            } else if (lastMetrics != null) {
                for (SignLayout.TileBounds b : lastMetrics.tiles()) {
                    if (b.row() == selRow && b.tile() == selTile) {
                        float[] panel = SignLayout.panelBox(draft().build(), canvasWidth, canvasHeight);
                        float home = (b.x1() + b.x2()) / 2.0f - tile.dx();
                        float toCentre = panel[0] + panel[2] / 2.0f - home;
                        if (Math.abs(dx - toCentre) <= reach) {
                            dx = toCentre;
                            guideX = true;
                        }
                    }
                }
            }
        }
        float fx = Math.max(-SignSpec.MAX_PANEL, Math.min(SignSpec.MAX_PANEL, dx));
        float fy = Math.max(-SignSpec.MAX_PANEL, Math.min(SignSpec.MAX_PANEL, dy));
        updateTile(t -> t.withOffset(fx, fy));
    }

    // ---------------------------------------------------------- numbers

    private void sliderDrag(double mx) {
        if (dragSlider == null || dragSlider[2] <= 0) {
            return;
        }
        float t = (float) Math.max(0, Math.min(1, (mx - dragSlider[1]) / dragSlider[2]));
        float value = dragMin + t * (dragMax - dragMin);
        applyNumber(dragSlider[0], snap(value, dragStep));
    }

    private static float snap(float value, float step) {
        return step > 0 ? Math.round(value / step) * step : value;
    }

    /** The current value of a numeric field. */
    private float numberValue(int id) {
        Draft d = draft();
        Tile tile = selectedTile();
        return switch (id) {
            case NUM_PANEL_W -> d.panelWidth > 0 ? d.panelWidth : canvasWidth;
            case NUM_PANEL_H -> d.panelHeight > 0 ? d.panelHeight : canvasHeight;
            case NUM_PANEL_X -> d.panelDx;
            case NUM_PANEL_Y -> d.panelDy;
            case NUM_PANEL_SCALE -> d.panelScale;
            case NUM_TILE_SCALE -> tile == null ? 1 : tile.scale();
            case NUM_TILE_DX -> tile == null ? 0 : tile.dx();
            case NUM_TILE_DY -> tile == null ? 0 : tile.dy();
            case NUM_SPACER -> tile == null ? 0 : tile.num();
            default -> 0;
        };
    }

    private void applyNumber(int id, float value) {
        Draft d = draft();
        switch (id) {
            case NUM_PANEL_W -> d.panelWidth = Math.max(2, value);
            case NUM_PANEL_H -> d.panelHeight = Math.max(2, value);
            case NUM_PANEL_X -> d.panelDx = value;
            case NUM_PANEL_Y -> d.panelDy = value;
            case NUM_PANEL_SCALE -> d.panelScale = Math.max(SignSpec.MIN_SCALE, value);
            case NUM_TILE_SCALE -> updateTile(t -> t.withScale(Math.max(SignSpec.MIN_SCALE, value)));
            case NUM_TILE_DX -> updateTile(t -> t.withOffset(value, t.dy()));
            case NUM_TILE_DY -> updateTile(t -> t.withOffset(t.dx(), value));
            case NUM_SPACER -> updateTile(t -> t.withNum(Math.max(0, Math.round(value))));
            default -> {
            }
        }
        TextBox box = numberBoxes.get(id);
        if (box != null && !box.isFocused()) {
            box.load(format(numberValue(id), id == NUM_PANEL_SCALE || id == NUM_TILE_SCALE ? 2 : 1));
        }
    }

    private static String format(float value, int decimals) {
        if (decimals == 0 || value == Math.round(value)) {
            return Integer.toString(Math.round(value));
        }
        String text = String.format(java.util.Locale.ROOT, decimals == 2 ? "%.2f" : "%.1f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }

    /**
     * Label, a draggable slider and a number box side by side — the same
     * control for every size, offset and scale, so nothing is stuck on
     * "small / medium / large". Returns the y below it.
     */
    private int numberField(DrawContext c, int mx, int my, String label, int id, float min, float max, float step,
                            int decimals, int x, int y, int w) {
        float value = numberValue(id);
        c.drawText(textRenderer, label, x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        int boxW = 44;
        int trackX = x;
        int trackW = w - boxW - 8;
        int trackY = y + FIELD / 2 - 2;
        FlatUi.rect(c, trackX, trackY, trackW, 4, FlatUi.INPUT);
        float t = Math.max(0, Math.min(1, (value - min) / (max - min)));
        int knob = trackX + Math.round(t * (trackW - 6));
        boolean hot = (dragSlider != null && dragSlider[0] == id) || FlatUi.inside(mx, my, trackX, y, trackW, FIELD);
        FlatUi.rect(c, trackX, trackY, knob - trackX + 3, 4, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        FlatUi.rect(c, knob, y + 2, 6, FIELD - 4, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        hit(trackX, y, trackW, FIELD, HIT_SLIDER, id, 0);
        // Remember the range this slider maps onto for the drag that may start (or is running) here.
        if (FlatUi.inside(mx, my, trackX, y, trackW, FIELD) || (dragSlider != null && dragSlider[0] == id)) {
            dragMin = min;
            dragMax = max;
            dragStep = step;
        }
        TextBox box = numberBoxes.get(id);
        if (box == null) {
            box = new TextBox(textRenderer, 8, false);
            int fieldId = id;
            box.onChange(v -> {
                try {
                    applyNumber(fieldId, Float.parseFloat(v.trim()));
                } catch (NumberFormatException ignored) {
                    // half-typed number: leave the value alone until it parses
                }
            });
            numberBoxes.put(id, box);
        }
        if (!box.isFocused()) {
            box.load(format(value, decimals));
        }
        box.setBounds(x + w - boxW, y, boxW, FIELD);
        box.render(c, mx, my);
        showBox(box, y, FIELD);
        return y + FIELD + 5;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (popup != null) {
            int max = Math.max(0, popupCount() - popupVisibleRows());
            popupScroll = Math.max(0, Math.min(max, popupScroll - (int) Math.signum(verticalAmount)));
            return true;
        }
        for (TextBox box : visibleBoxes) {
            if (box.mouseScrolled(mx, my, verticalAmount)) {
                return true;
            }
        }
        if (worldView && FlatUi.inside(mx, my, centreX, leftY, centreW, leftH)) {
            worldCamera.zoom(verticalAmount);
            return true;
        }
        if (FlatUi.inside(mx, my, rightX, rightY, rightWidth, rightH)) {
            int max = Math.max(0, inspectorContent - inspectorView);
            inspectorScroll = Math.max(0, Math.min(max, inspectorScroll - (int) (verticalAmount * CARD)));
            return true;
        }
        if (FlatUi.inside(mx, my, leftX, leftY, leftWidth, leftH)) {
            int max = Math.max(0, structureContent - (leftH - 16));
            structureScroll = Math.max(0, Math.min(max, structureScroll - (int) (verticalAmount * CARD)));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // escape
            if (popup != null) {
                popup = null;
                return true;
            }
            if (focused != null) {
                focus(null);
                return true;
            }
            close();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == 83) { // ctrl+S
            if (save()) {
                close();
            }
            return true;
        }
        if (focused == null && Screen.hasControlDown() && keyCode == 67) { // ctrl+C: the whole face
            copyFace();
            return true;
        }
        if (focused == null && Screen.hasControlDown() && keyCode == 86) { // ctrl+V
            pasteFace();
            return true;
        }
        if (focused == templateBox && (keyCode == 257 || keyCode == 335)) { // enter saves the template
            saveTemplate();
            return true;
        }
        if (keyCode == 258 && !visibleBoxes.isEmpty()) { // tab
            int index = focused == null ? -1 : visibleBoxes.indexOf(focused);
            int next = Screen.hasShiftDown()
                    ? (index <= 0 ? visibleBoxes.size() - 1 : index - 1)
                    : (index + 1) % visibleBoxes.size();
            focus(visibleBoxes.get(next));
            return true;
        }
        if (focused != null && focused.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (focused == null && selectedTile() != null && keyCode >= 262 && keyCode <= 265) {
            // arrows nudge the selected tile: 1 unit, 5 with shift, a half with alt
            float step = Screen.hasShiftDown() ? 5 : Screen.hasAltDown() ? 0.5f : 1;
            float nx = keyCode == 262 ? step : keyCode == 263 ? -step : 0;
            float ny = keyCode == 264 ? step : keyCode == 265 ? -step : 0;
            updateTile(t -> t.withOffset(t.dx() + nx, t.dy() + ny));
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
    public void close() {
        if (client != null) {
            client.setScreen(null);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        visibleBoxes.clear();
        // The block in the world always shows the draft, in either view.
        SignFaces current = build();
        if (!current.equals(pushedPreview)) {
            pushedPreview = current;
            entity.setPreviewSign(current);
            MtaSignPainter.invalidateRuns();
        }
        if (worldView) {
            // Everything but the centre pane is ground; the centre is the world itself.
            FlatUi.rect(c, 0, 0, width, leftY, FlatUi.GROUND);
            FlatUi.rect(c, 0, leftY, centreX, height - leftY, FlatUi.GROUND);
            FlatUi.rect(c, centreX + centreW, leftY, width - centreX - centreW, height - leftY, FlatUi.GROUND);
            FlatUi.rect(c, centreX, paneBottom, centreW, height - paneBottom, FlatUi.GROUND);
        } else {
            FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        }
        int hoverX = popup == null ? mx : -1;
        int hoverY = popup == null ? my : -1;
        drawTopBar(c, hoverX, hoverY);
        drawStructure(c, hoverX, hoverY);
        drawPreview(c, hoverX, hoverY);
        drawInspector(c, hoverX, hoverY);
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = textRenderer.getWidth(toast) + 12;
            int tx = centreX + (centreW - tw) / 2;
            FlatUi.rect(c, tx, paneBottom - 30, tw, 14, 0xEE3A2224);
            c.drawText(textRenderer, toast, tx + 6, paneBottom - 27, FlatUi.DANGER, false);
        }
        if (popup != null) {
            drawPopup(c, mx, my);
        }
    }

    private void drawTopBar(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, TOP_BAR, FlatUi.PANE);
        FlatUi.rect(c, 0, TOP_BAR - 1, width, 1, FlatUi.BORDER);
        c.drawText(textRenderer, title, PAD + 4, 7, FlatUi.TEXT, false);
        int x = PAD + 12 + textRenderer.getWidth(title);
        if (doubleSided) {
            int segW = 90;
            FlatUi.segmented(c, textRenderer, new String[]{"Front", "Back"}, editingBack ? 1 : 0, x, 2, segW,
                    FlatUi.BUTTON_HEIGHT, mx, my);
            hit(x, 2, segW / 2, FlatUi.BUTTON_HEIGHT, HIT_FACE, 0, 0);
            hit(x + segW / 2, 2, segW / 2, FlatUi.BUTTON_HEIGHT, HIT_FACE, 1, 0);
        }
        int saveW = 54;
        int cancelW = 50;
        int sx = width - PAD - saveW;
        int cx = sx - 4 - cancelW;
        int copyW = 40;
        int pasteX = cx - 8 - copyW;
        int copyX = pasteX - 3 - copyW;
        FlatUi.button(c, textRenderer, "Copy", copyX, 2, copyW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        hit(copyX, 2, copyW, FlatUi.BUTTON_HEIGHT, HIT_COPY, 0, 0);
        FlatUi.button(c, textRenderer, "Paste", pasteX, 2, copyW, FlatUi.BUTTON_HEIGHT, mx, my,
                clipboard != null ? ButtonStyle.FLAT : ButtonStyle.GHOST);
        hit(pasteX, 2, copyW, FlatUi.BUTTON_HEIGHT, HIT_PASTE, 0, 0);
        FlatUi.button(c, textRenderer, "Cancel", cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
        hit(cx, 2, cancelW, FlatUi.BUTTON_HEIGHT, HIT_CANCEL, 0, 0);
        FlatUi.button(c, textRenderer, "Save", sx, 2, saveW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY);
        hit(sx, 2, saveW, FlatUi.BUTTON_HEIGHT, HIT_SAVE, 0, 0);
    }

    private void drawStructure(DrawContext c, int mx, int my) {
        FlatUi.pane(c, leftX, leftY, leftWidth, leftH);
        FlatUi.heading(c, textRenderer, editingBack ? "Structure · back" : "Structure", leftX + 6, leftY + 5);
        int top = leftY + 16;
        int viewH = leftH - 16;
        c.enableScissor(leftX + 1, top, leftX + leftWidth - 1, leftY + leftH - 1);
        int y = top - structureScroll;
        Draft d = draft();
        y = card(c, mx, my, y, -1, -1, "Sign", format(d.panelWidth > 0 ? d.panelWidth : canvasWidth, 0)
                + " x " + format(d.panelHeight > 0 ? d.panelHeight : canvasHeight, 0), 0, 0);
        for (int r = 0; r < d.rows.size(); r++) {
            Draft.RowDraft row = d.rows.get(r);
            y = card(c, mx, my, y, r, -1, "Row " + (r + 1), row.align == Align.CENTER ? "centred" : row.align == Align.RIGHT ? "right" : "left", 0, 0);
            for (int t = 0; t < row.tiles.size(); t++) {
                Tile tile = row.tiles.get(t);
                y = card(c, mx, my, y, r, t, TILE_TAGS[tile.type().ordinal()], snippet(tile),
                        tagColor(tile.type()), 12);
            }
        }
        y += 4;
        int bx = leftX + 6;
        int bw = leftWidth - 12;
        FlatUi.button(c, textRenderer, addMenuOpen ? "Close" : "+ Add tile", bx, y, bw, FlatUi.BUTTON_HEIGHT, mx, my,
                addMenuOpen ? ButtonStyle.FLAT : ButtonStyle.PRIMARY);
        hit(bx, y, bw, FlatUi.BUTTON_HEIGHT, HIT_ADD, 0, 0);
        y += FlatUi.BUTTON_HEIGHT + 3;
        if (addMenuOpen) {
            TileType[] types = TileType.values();
            int tileW = (bw - 4) / 2;
            for (int i = 0; i < types.length; i++) {
                int tx = bx + (i % 2) * (tileW + 4);
                int ty = y + (i / 2) * (FlatUi.BUTTON_HEIGHT + 3);
                FlatUi.button(c, textRenderer, TILE_LABELS[i], tx, ty, tileW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
                hit(tx, ty, tileW, FlatUi.BUTTON_HEIGHT, HIT_ADD_TYPE, i, 0);
            }
            y += ((types.length + 1) / 2) * (FlatUi.BUTTON_HEIGHT + 3);
        }
        FlatUi.button(c, textRenderer, "+ Add row", bx, y, bw, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        hit(bx, y, bw, FlatUi.BUTTON_HEIGHT, HIT_ADD_ROW, 0, 0);
        y += FlatUi.BUTTON_HEIGHT + 3;
        FlatUi.button(c, textRenderer, templateMenuOpen ? "Close" : "Templates…", bx, y, bw, FlatUi.BUTTON_HEIGHT, mx, my,
                ButtonStyle.FLAT);
        hit(bx, y, bw, FlatUi.BUTTON_HEIGHT, HIT_TEMPLATES, 0, 0);
        y += FlatUi.BUTTON_HEIGHT + 3;
        if (templateMenuOpen) {
            // Yours first: saving and reusing your own designs is the common case.
            FlatUi.heading(c, textRenderer, "Your templates", bx, y);
            y += 12;
            List<String> mine = SignUserTemplates.names();
            for (int i = 0; i < mine.size(); i++) {
                FlatUi.button(c, textRenderer, textRenderer.trimToWidth(mine.get(i), bw - FIELD - 10), bx, y, bw - FIELD - 2,
                        FIELD, mx, my, ButtonStyle.FLAT);
                hit(bx, y, bw - FIELD - 2, FIELD, HIT_USER_TEMPLATE, i, 0);
                FlatUi.button(c, textRenderer, "×", bx + bw - FIELD, y, FIELD, FIELD, mx, my, ButtonStyle.DANGER);
                hit(bx + bw - FIELD, y, FIELD, FIELD, HIT_USER_TEMPLATE_DELETE, i, 0);
                y += FIELD + 2;
            }
            int saveW = 34;
            templateBox.setBounds(bx, y, bw - saveW - 2, FIELD);
            templateBox.render(c, mx, my);
            showBox(templateBox, y, FIELD);
            FlatUi.button(c, textRenderer, "Save", bx + bw - saveW, y, saveW, FIELD, mx, my, ButtonStyle.PRIMARY);
            hit(bx + bw - saveW, y, saveW, FIELD, HIT_TEMPLATE_SAVE, 0, 0);
            y += FIELD + 6;
            FlatUi.heading(c, textRenderer, "Built in", bx, y);
            y += 12;
            String[] names = SignTemplates.NAMES;
            for (int i = 0; i < names.length; i++) {
                FlatUi.button(c, textRenderer, textRenderer.trimToWidth(names[i], bw - 8), bx, y, bw, FIELD, mx, my, ButtonStyle.FLAT);
                hit(bx, y, bw, FIELD, HIT_TEMPLATE, i, 0);
                y += FIELD + 2;
            }
            c.drawText(textRenderer, textRenderer.trimToWidth("Replaces this face's rows", bw), bx, y + 1, FlatUi.TEXT_FAINT, false);
            y += 14;
        }
        c.disableScissor();
        structureContent = y + structureScroll - top + 4;
        FlatUi.scrollThumb(c, leftX + leftWidth, top, viewH, structureContent, structureScroll);
    }

    private static int tagColor(TileType type) {
        return switch (type) {
            case BULLETS -> 0xFF8A2F8A;
            case TEXT -> 0xFF2F7A5A;
            case ARROW -> 0xFF9A5A2F;
            case STATION_NAME -> 0xFF2F6E9A;
            case EXIT -> 0xFFB03A3A;
            case DESTINATION -> 0xFF5B4FCF;
            case BADGE -> 0xFF2F4FA0;
            default -> 0xFF4A4A55;
        };
    }

    private String snippet(Tile tile) {
        return switch (tile.type()) {
            case BULLETS -> "auto".equals(tile.arg()) ? "all lines here"
                    : tile.routes().isEmpty() ? "(no lines)" : String.join(" ", tile.routes()).replace("d:", "◆");
            case TEXT -> tile.text().isBlank() ? "(empty)" : tile.text().replace('\n', ' ');
            case ARROW -> (SignSymbols.isSymbol(tile.num()) ? SignSymbols.name(tile.num())
                    : "Arrow " + DIRECTION_GLYPHS[cellOfDirection(tile.num())])
                    + ("left".equals(tile.arg()) ? " · left edge" : "right".equals(tile.arg()) ? " · right edge" : "");
            case STATION_NAME -> tile.text().isEmpty() ? "Station name (auto)" : tile.text();
            case EXIT -> "Exit" + (tile.text().isEmpty() ? "" : " " + tile.text());
            case DESTINATION -> tile.routes().isEmpty() ? "(pick a route)" : tile.routes().get(0).replace("||", " · ");
            case RULE -> "Divider line";
            case BADGE -> tile.text().isBlank() ? "(empty badge)" : tile.text();
            default -> "Space";
        };
    }

    private static int cellOfDirection(int dir) {
        for (int i = 0; i < DIRECTION_OF_CELL.length; i++) {
            if (DIRECTION_OF_CELL[i] == dir) {
                return i;
            }
        }
        return 5;
    }

    private int card(DrawContext c, int mx, int my, int y, int row, int tile, String tag, String snippet,
                     int tagColor, int indent) {
        int x = leftX + 1;
        int w = leftWidth - 2;
        int clipTop = leftY + 16;
        boolean isSelected = row == selRow && tile == selTile;
        boolean hovered = FlatUi.inside(mx, my, x, y, w, CARD) && my >= clipTop && my < leftY + leftH;
        if (isSelected) {
            FlatUi.rect(c, x, y, w, CARD, FlatUi.SELECTED);
            FlatUi.rect(c, x, y, 2, CARD, FlatUi.ACCENT);
        } else if (hovered) {
            FlatUi.rect(c, x, y, w, CARD, FlatUi.HOVER);
        }
        FlatUi.rect(c, x, y + CARD - 1, w, 1, FlatUi.BORDER);
        int tx = x + 6 + indent;
        if (tagColor == 0) {
            c.drawText(textRenderer, tag, tx, y + 3, FlatUi.TEXT, false);
            c.drawText(textRenderer, textRenderer.trimToWidth(snippet, w - 60 - indent), tx + textRenderer.getWidth(tag) + 6,
                    y + 3, FlatUi.TEXT_FAINT, false);
        } else {
            int tagW = Math.max(12, textRenderer.getWidth(tag) + 6);
            FlatUi.rect(c, tx, y + 4, tagW, 12, tagColor);
            c.drawText(textRenderer, tag, tx + (tagW - textRenderer.getWidth(tag)) / 2, y + 6, FlatUi.TEXT, false);
            int textX = tx + tagW + 5;
            int textW = w - (textX - x) - (isSelected ? 46 : 6);
            c.drawText(textRenderer, textRenderer.trimToWidth(snippet, Math.max(8, textW)), textX, y + 6, FlatUi.TEXT, false);
        }
        if (isSelected && row >= 0) {
            int bx = x + w - 44;
            FlatUi.iconButton(c, textRenderer, "↑", bx, y + 3, 13, mx, my, FlatUi.TEXT_DIM);
            hit(bx, Math.max(y + 3, clipTop), 13, 13, HIT_MOVE_UP, 0, 0);
            FlatUi.iconButton(c, textRenderer, "↓", bx + 14, y + 3, 13, mx, my, FlatUi.TEXT_DIM);
            hit(bx + 14, Math.max(y + 3, clipTop), 13, 13, HIT_MOVE_DOWN, 0, 0);
            FlatUi.iconButton(c, textRenderer, "×", bx + 28, y + 3, 13, mx, my, FlatUi.DANGER);
            hit(bx + 28, Math.max(y + 3, clipTop), 13, 13, HIT_DELETE, 0, 0);
        }
        int visibleTop = Math.max(y, clipTop);
        int visibleBottom = Math.min(y + CARD, leftY + leftH - 1);
        if (visibleBottom > visibleTop) {
            hit(x, visibleTop, w, visibleBottom - visibleTop, HIT_CARD, row, tile);
        }
        return y + CARD;
    }

    /** The Editor | In world switch along the top of the centre pane. */
    private void drawViewSwitch(DrawContext c, int mx, int my) {
        int segW = Math.min(150, centreW - 8);
        int sx = centreX + (centreW - segW) / 2;
        int sy = leftY + 4;
        if (worldView) {
            FlatUi.rect(c, sx - 3, sy - 3, segW + 6, FlatUi.BUTTON_HEIGHT + 6, 0xCC0F0F12);
        }
        FlatUi.segmented(c, textRenderer, new String[]{"Editor", "In world"}, worldView ? 1 : 0, sx, sy, segW,
                FlatUi.BUTTON_HEIGHT, mx, my);
        hit(sx, sy, segW / 2, FlatUi.BUTTON_HEIGHT, HIT_VIEW, 0, 0);
        hit(sx + segW / 2, sy, segW - segW / 2, FlatUi.BUTTON_HEIGHT, HIT_VIEW, 1, 0);
    }

    /**
     * The centre pane as a window onto the real block: nothing is painted
     * over the frame but the switch, a frame line and the hints. The camera
     * is re-aimed every frame so the sign sits in the middle of this pane.
     */
    private void drawWorldView(DrawContext c, int mx, int my) {
        float tanV = tanHalfV();
        float tanH = tanV * width / Math.max(1, height);
        float paneCx = centreX + centreW / 2.0f;
        float paneCy = leftY + leftH / 2.0f + 6;
        worldCamera.update(viewTarget(), viewNormal(), (paneCx - width / 2.0f) / (width / 2.0f),
                -(paneCy - height / 2.0f) / (height / 2.0f), tanH, tanV);
        FlatUi.outline(c, centreX, leftY, centreW, leftH, FlatUi.BORDER);
        drawViewSwitch(c, mx, my);
        int resetW = 62;
        int ry = paneBottom - FlatUi.BUTTON_HEIGHT - 5;
        FlatUi.button(c, textRenderer, "Reset view", centreX + centreW - resetW - 5, ry, resetW, FlatUi.BUTTON_HEIGHT,
                mx, my, ButtonStyle.FLAT);
        hit(centreX + centreW - resetW - 5, ry, resetW, FlatUi.BUTTON_HEIGHT, HIT_WORLD_RESET, 0, 0);
        String hint = (editingBack ? "Back face · " : "") + "Drag to look around · scroll to zoom";
        hint = textRenderer.trimToWidth(hint, Math.max(10, centreW - resetW - 22));
        int hw = textRenderer.getWidth(hint);
        FlatUi.rect(c, centreX + 5, ry, hw + 10, FlatUi.BUTTON_HEIGHT, 0xCC0F0F12);
        c.drawText(textRenderer, hint, centreX + 10, ry + (FlatUi.BUTTON_HEIGHT - 8) / 2, FlatUi.TEXT_DIM, false);
        hit(centreX, leftY, centreW, leftH, HIT_WORLD, 0, 0);
    }

    private void drawPreview(DrawContext c, int mx, int my) {
        if (worldView) {
            drawWorldView(c, mx, my);
            return;
        }
        FlatUi.rect(c, centreX, leftY, centreW, leftH, 0xFF0F0F12);
        drawViewSwitch(c, mx, my);
        SignSpec spec = draft().build();
        // Fit the plate AND the panel (which may be bigger or elsewhere) into the pane.
        float[] panel = SignLayout.panelBox(spec, canvasWidth, canvasHeight);
        previewMinX = Math.min(0, panel[0]);
        previewMinY = Math.min(0, panel[1]);
        float maxX = Math.max(canvasWidth, panel[0] + panel[2]);
        float maxY = Math.max(canvasHeight, panel[1] + panel[3]);
        float bw = maxX - previewMinX;
        float bh = maxY - previewMinY;
        previewScale = Math.max(0.1f, Math.min((centreW - 12) / bw, (leftH - 64) / bh));
        int pw = Math.round(bw * previewScale);
        int ph = Math.round(bh * previewScale);
        int originX = centreX + (centreW - pw) / 2;
        int originY = leftY + 10 + FlatUi.BUTTON_HEIGHT + 6;
        previewX = originX - Math.round(previewMinX * previewScale);
        previewY = originY - Math.round(previewMinY * previewScale);
        // The block's plate, as the reference frame the panel is placed against.
        FlatUi.rect(c, previewX - 1, previewY - 1, Math.round(canvasWidth * previewScale) + 2,
                Math.round(canvasHeight * previewScale) + 2, 0xFF3A3A40);
        FlatUi.rect(c, previewX, previewY, Math.round(canvasWidth * previewScale),
                Math.round(canvasHeight * previewScale), 0xFF1A1A1E);
        lastMetrics = SignLayout.paint(new PosterLayout.GuiSurface(c, previewX, previewY, previewScale, SignLayout.FONT),
                spec, canvasWidth, canvasHeight, ctx);
        // Selection highlight.
        float[] box = null;
        if (selRow >= 0 && selTile >= 0) {
            for (SignLayout.TileBounds b : lastMetrics.tiles()) {
                if (b.row() == selRow && b.tile() == selTile) {
                    box = new float[]{b.x1(), b.y1(), b.x2(), b.y2()};
                }
            }
        } else if (selRow >= 0 && selRow < lastMetrics.rowBounds().size()) {
            float[] r = lastMetrics.rowBounds().get(selRow);
            float[] pb = SignLayout.panelBox(spec, canvasWidth, canvasHeight);
            box = new float[]{pb[0], r[0], pb[0] + pb[2], r[1]};
        }
        if (box != null) {
            int sx = previewX + Math.round(box[0] * previewScale);
            int sy = previewY + Math.round(box[1] * previewScale);
            int sw = Math.max(2, Math.round((box[2] - box[0]) * previewScale));
            int sh = Math.max(2, Math.round((box[3] - box[1]) * previewScale));
            FlatUi.rect(c, sx, sy, sw, sh, 0x223D8BFF);
            FlatUi.outline(c, sx - 1, sy - 1, sw + 2, sh + 2, FlatUi.ACCENT);
            // Guides the dragged tile snapped to: the panel's centre line, its own row line.
            if (draggingTile && guideX) {
                FlatUi.rect(c, previewX + Math.round((panel[0] + panel[2] / 2.0f) * previewScale), originY, 1, ph, 0xFFFF4FA3);
            }
            if (draggingTile && guideY) {
                FlatUi.rect(c, originX, sy + sh / 2, pw, 1, 0xFFFF4FA3);
            }
        }
        hit(originX, originY, pw, ph, HIT_PREVIEW, 0, 0);
        String hint = (editingBack ? "Back face · " : "") + (spec.isEmpty() ? "Add a tile or pick a template"
                : selectedTile() != null ? "Drag to move · arrow keys nudge" : "Click to select · drag a tile to move it");
        if (textRenderer.getWidth(hint) <= centreW - 8) {
            c.drawText(textRenderer, hint, centreX + (centreW - textRenderer.getWidth(hint)) / 2, originY + ph + 8,
                    FlatUi.TEXT_FAINT, false);
        }
        String where = ctx.stationName().isEmpty() ? "Not inside an MTR station" : "Station: " + ctx.stationName();
        c.drawText(textRenderer, textRenderer.trimToWidth(where, centreW - 8), centreX + 4, paneBottom - 12, FlatUi.TEXT_FAINT, false);
    }

    /**
     * The inspector scrolls: on a small window (a Mac at auto GUI scale is
     * ~266 px tall) the sign's plate controls or a tile's size and position
     * would otherwise fall off the bottom. The delete button stays put below.
     */
    private void drawInspector(DrawContext c, int mx, int my) {
        FlatUi.pane(c, rightX, rightY, rightWidth, rightH);
        int footer = selRow < 0 ? 0 : FlatUi.BUTTON_HEIGHT + 10;
        int viewTop = rightY + 2;
        int viewBottom = rightY + rightH - 2 - footer;
        inspectorView = viewBottom - viewTop;
        inspectorScroll = Math.max(0, Math.min(Math.max(0, inspectorContent - inspectorView), inspectorScroll));
        clipTop = viewTop;
        clipBottom = viewBottom;
        c.enableScissor(rightX + 1, viewTop, rightX + rightWidth - 1, viewBottom);
        int end = inspectorBody(c, mx, my, rightX + 8, rightY + 5 - inspectorScroll, rightWidth - 16);
        c.disableScissor();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        inspectorContent = end + inspectorScroll - viewTop + 4;
        FlatUi.scrollThumb(c, rightX + rightWidth, viewTop, inspectorView, inspectorContent, inspectorScroll);
        if (selRow >= 0) {
            deleteButton(c, mx, my, selectedTile() == null ? "Delete row" : "Delete tile", rightX + 8, rightWidth - 16);
        }
    }

    /** Draws the inspector's fields from {@code y} down; returns the y below the last one. */
    private int inspectorBody(DrawContext c, int mx, int my, int x, int y, int w) {
        Tile tile = selectedTile();
        Draft.RowDraft row = selectedRow();
        if (selRow < 0) {
            // The plate comes first: it is the one thing people look for here.
            FlatUi.heading(c, textRenderer, "Plate size", x, y);
            y += 12;
            y = numberField(c, mx, my, "Width (64 = one block)", NUM_PANEL_W, 4, 512, 1, 0, x, y, w);
            y = numberField(c, mx, my, "Height", NUM_PANEL_H, 4, 256, 1, 0, x, y, w);
            FlatUi.heading(c, textRenderer, "Sign", x, y);
            y += 12;
            y = segmentedField(c, mx, my, "Style", new String[]{"Black", "1970 white", "Plain"}, draft().style.ordinal(), x, y, w, SEG_STYLE);
            y = toggleField(c, mx, my, "Front face", frontOn ? "Shown" : "Hidden", frontOn, x, y, w, TOGGLE_FRONT);
            if (doubleSided) {
                y = segmentedField(c, mx, my, "Back face", new String[]{"Same", "Own", "Blank"}, backMode.ordinal(), x, y, w, SEG_BACK);
                c.drawText(textRenderer, textRenderer.trimToWidth("Own: edit it via Back, top bar", w), x, y, FlatUi.TEXT_FAINT, false);
                y += 12;
            }
            FlatUi.heading(c, textRenderer, "Plate position & contents", x, y);
            y += 12;
            y = numberField(c, mx, my, "Shift right / left", NUM_PANEL_X, -256, 256, 1, 0, x, y, w);
            y = numberField(c, mx, my, "Shift down / up", NUM_PANEL_Y, -256, 256, 1, 0, x, y, w);
            y = numberField(c, mx, my, "Content scale", NUM_PANEL_SCALE, 0.25f, 4, 0.05f, 2, x, y, w);
            FlatUi.button(c, textRenderer, "Reset plate to the block's own", x, y, w, FIELD, mx, my, ButtonStyle.FLAT);
            hit(x, y, w, FIELD, HIT_PANEL_RESET, 0, 0);
            y += FIELD + 5;
        } else if (tile == null) {
            FlatUi.heading(c, textRenderer, "Row " + (selRow + 1), x, y);
            y += 14;
            if (row != null) {
                y = segmentedField(c, mx, my, "Alignment", new String[]{"Left", "Centre", "Right"},
                        row.align == Align.CENTER ? 1 : row.align == Align.RIGHT ? 2 : 0, x, y, w, SEG_ALIGN);
            }
            c.drawText(textRenderer, textRenderer.trimToWidth("Each tile can override this.", w), x, y, FlatUi.TEXT_FAINT, false);
            y += 12;
        } else {
            switch (tile.type()) {
                case TEXT -> {
                    FlatUi.heading(c, textRenderer, "Text", x, y);
                    y += 14;
                    y = segmentedField(c, mx, my, "Size", new String[]{"Small", "Normal", "Large"},
                            tile.num() == 1 ? 0 : tile.num() == 2 ? 2 : 1, x, y, w, SEG_TEXT_SIZE);
                    int deleteY = rightY + rightH - 6 - FlatUi.BUTTON_HEIGHT;
                    y = colorField(c, mx, my, "Colour field", tile.arg(), true, x, y, w);
                    int toolbarH = 12 + FIELD + 4 + FIELD + 4 + 2 * 20 + 8;
                    int geometryH = 3 * (FIELD + 15) + 14 + (FIELD + 15) + (FIELD + 5);
                    int boxH = Math.max(24, Math.min(60, deleteY - 6 - toolbarH - geometryH - (y + inspectorScroll)));
                    textBox.setBounds(x, y, w, boxH);
                    textBox.render(c, mx, my);
                    showBox(textBox, y, boxH);
                    y += boxH + 6;
                    FlatUi.heading(c, textRenderer, "Insert at cursor", x, y);
                    y += 12;
                    int half = (w - 4) / 2;
                    FlatUi.button(c, textRenderer, "● Line bullet", x, y, half, FIELD, mx, my, ButtonStyle.FLAT);
                    hit(x, y, half, FIELD, HIT_TOKEN, 0, 0);
                    FlatUi.button(c, textRenderer, "◆ Express", x + half + 4, y, w - half - 4, FIELD, mx, my, ButtonStyle.FLAT);
                    hit(x + half + 4, y, w - half - 4, FIELD, HIT_TOKEN, 1, 0);
                    y += FIELD + 4;
                    String[] glyphs = {"♿", "←", "↑", "↓", "→"};
                    int gw = (w - 4 * 4) / 5;
                    for (int i = 0; i < glyphs.length; i++) {
                        int gx = x + i * (gw + 4);
                        FlatUi.button(c, textRenderer, glyphs[i], gx, y, gw, FIELD, mx, my, ButtonStyle.FLAT);
                        hit(gx, y, gw, FIELD, HIT_TOKEN, 2 + i, 0);
                    }
                    y += FIELD + 4;
                    // every pictogram as an inline token
                    int cell = 18;
                    int perRow = Math.max(1, (w + 2) / (cell + 2));
                    for (int i = 0; i < SignSymbols.KEYS.length; i++) {
                        int gx = x + (i % perRow) * (cell + 2);
                        int gy = y + (i / perRow) * (cell + 2);
                        int fill = symbolCell(c, mx, my, gx, gy, cell, false);
                        SignSymbols.draw(new PosterLayout.GuiSurface(c, gx + 2, gy + 2, 1.0f), SignSymbols.FIRST + i, 0, 0,
                                cell - 4, FlatUi.TEXT_DIM, fill, 0);
                        hit(gx, gy, cell, cell, HIT_TOKEN, 100 + i, 0);
                    }
                    y += ((SignSymbols.KEYS.length + perRow - 1) / perRow) * (cell + 2) + 4;
                }
                case RULE -> {
                    FlatUi.heading(c, textRenderer, "Divider", x, y);
                    y += 14;
                    c.drawText(textRenderer, textRenderer.trimToWidth("A thin line between groups of modules.", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case BADGE -> {
                    FlatUi.heading(c, textRenderer, "Badge", x, y);
                    y += 14;
                    y = field(c, mx, my, "Label", badgeBox, x, y, w);
                    y = colorField(c, mx, my, "Colour", tile.arg(), false, x, y, w);
                    c.drawText(textRenderer, textRenderer.trimToWidth("Other operators and bus routes.", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case BULLETS -> {
                    FlatUi.heading(c, textRenderer, "Route bullets", x, y);
                    y += 14;
                    boolean auto = "auto".equals(tile.arg());
                    y = toggleField(c, mx, my, "Lines", auto ? "All lines at this station" : "Chosen below", auto, x, y, w, TOGGLE_AUTO);
                    if (!auto) {
                        y = chips(c, mx, my, tile.routes(), x, y, w);
                        int half = (w - 4) / 2;
                        FlatUi.button(c, textRenderer, "+ Bullet", x, y, half, FIELD, mx, my, ButtonStyle.FLAT);
                        hit(x, y, half, FIELD, HIT_ADD_LINE, 0, 0);
                        FlatUi.button(c, textRenderer, "+ Express ◆", x + half + 4, y, w - half - 4, FIELD, mx, my, ButtonStyle.FLAT);
                        hit(x + half + 4, y, w - half - 4, FIELD, HIT_ADD_LINE, 1, 0);
                        y += FIELD + 6;
                    }
                    c.drawText(textRenderer, textRenderer.trimToWidth("Click a chip to remove it.", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case ARROW -> {
                    FlatUi.heading(c, textRenderer, "Symbol", x, y);
                    y += 14;
                    c.drawText(textRenderer, "Arrows", x, y, FlatUi.TEXT_DIM, false);
                    y += 11;
                    int cell = 24;
                    int padX = x + (w - cell * 3 - 8) / 2;
                    for (int i = 0; i < 9; i++) {
                        int dir = DIRECTION_OF_CELL[i];
                        if (dir < 0) {
                            continue;
                        }
                        int gx = padX + (i % 3) * (cell + 4);
                        int gy = y + (i / 3) * (cell + 4);
                        boolean on = tile.num() == dir;
                        symbolCell(c, mx, my, gx, gy, cell, on);
                        String g = DIRECTION_GLYPHS[i];
                        c.drawText(textRenderer, g, gx + (cell - textRenderer.getWidth(g)) / 2, gy + (cell - 8) / 2,
                                on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
                        hit(gx, gy, cell, cell, HIT_DIRECTION, dir, 0);
                    }
                    y += 3 * (cell + 4) + 2;
                    c.drawText(textRenderer, "Symbols", x, y, FlatUi.TEXT_DIM, false);
                    y += 11;
                    int perRow = Math.max(1, (w + 4) / (cell + 4));
                    String hovered = null;
                    for (int i = 0; i < SignSymbols.NAMES.length; i++) {
                        int num = SignSymbols.FIRST + i;
                        int gx = x + (i % perRow) * (cell + 4);
                        int gy = y + (i / perRow) * (cell + 4);
                        boolean on = tile.num() == num;
                        int fill = symbolCell(c, mx, my, gx, gy, cell, on);
                        SignSymbols.draw(new PosterLayout.GuiSurface(c, gx + 3, gy + 3, 1.0f), num, 0, 0, cell - 6,
                                on ? FlatUi.TEXT : FlatUi.TEXT_DIM, fill, 0);
                        hit(gx, gy, cell, cell, HIT_DIRECTION, num, 0);
                        if (FlatUi.inside(mx, my, gx, gy, cell, cell)) {
                            hovered = SignSymbols.NAMES[i];
                        }
                    }
                    y += ((SignSymbols.NAMES.length + perRow - 1) / perRow) * (cell + 4) + 2;
                    c.drawText(textRenderer, textRenderer.trimToWidth(hovered != null ? hovered
                            : SignSymbols.isSymbol(tile.num()) ? SignSymbols.name(tile.num())
                            : "Real signs pin the arrow to the edge it points at.", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case STATION_NAME -> {
                    FlatUi.heading(c, textRenderer, "Station name", x, y);
                    y += 14;
                    y = field(c, mx, my, "Override (empty = MTR station)", nameBox, x, y, w);
                    y = toggleField(c, mx, my, "Case", tile.hasFlag(SignSpec.NAME_UPPER) ? "UPPER CASE" : "As written", tile.hasFlag(SignSpec.NAME_UPPER), x, y, w, TOGGLE_UPPER);
                    y = toggleField(c, mx, my, "Lines", tile.hasFlag(SignSpec.NAME_STACKED) ? "Stacked (14 / Street)" : "One line if it fits", tile.hasFlag(SignSpec.NAME_STACKED), x, y, w, TOGGLE_STACKED);
                    y = segmentedField(c, mx, my, "Wheelchair symbol", new String[]{"Auto", "Always", "Never"},
                            "wc".equals(tile.arg()) ? 1 : "nowc".equals(tile.arg()) ? 2 : 0, x, y, w, SEG_WHEELCHAIR);
                    c.drawText(textRenderer, textRenderer.trimToWidth("Auto: shows when MTR's station screen marks it step-free", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 11;
                    c.drawText(textRenderer, textRenderer.trimToWidth("Here: " + (ctx.stationName().isEmpty() ? "(no station)" : ctx.stationName()
                            + (ctx.stationAccessible() ? " (step-free)" : " (not marked step-free)")), w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case EXIT -> {
                    FlatUi.heading(c, textRenderer, "Exit", x, y);
                    y += 14;
                    c.drawText(textRenderer, "MTR exit", x, y, FlatUi.TEXT_DIM, false);
                    y += 10;
                    SignContext.Exit exit = ctx.exit(tile.text());
                    String label = tile.text().isEmpty()
                            ? (exit == null ? "(first exit — none here)" : "First exit: " + exit.name())
                            : tile.text() + (exit == null ? " (not found here)" : "");
                    FlatUi.button(c, textRenderer, textRenderer.trimToWidth(label, w - 10), x, y, w, FIELD, mx, my, ButtonStyle.FLAT);
                    hit(x, y, w, FIELD, HIT_PICK, 1, 0);
                    y += FIELD + 3;
                    String streets = exit == null || exit.destinations().isEmpty() ? "no street names set in MTR"
                            : String.join(" / ", exit.destinations());
                    c.drawText(textRenderer, textRenderer.trimToWidth("Lines: " + streets, w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                    y = toggleField(c, mx, my, "\"Exit\"", tile.hasFlag(SignSpec.EXIT_WORD) ? "Shown" : "Hidden", tile.hasFlag(SignSpec.EXIT_WORD), x, y, w, TOGGLE_EXIT_WORD);
                    y = toggleField(c, mx, my, "Street names", tile.hasFlag(SignSpec.EXIT_STREETS) ? "Shown" : "Hidden", tile.hasFlag(SignSpec.EXIT_STREETS), x, y, w, TOGGLE_EXIT_STREETS);
                    y = toggleField(c, mx, my, "Exit name box", tile.hasFlag(SignSpec.EXIT_NAME) ? "Shown" : "Hidden", tile.hasFlag(SignSpec.EXIT_NAME), x, y, w, TOGGLE_EXIT_NAME);
                    y = field(c, mx, my, "Corner / note (last line)", cornerBox, x, y, w);
                    c.drawText(textRenderer, textRenderer.trimToWidth("One MTR destination per line, up to three.", w), x, y, FlatUi.TEXT_FAINT, false);
                    y += 12;
                }
                case DESTINATION -> {
                    FlatUi.heading(c, textRenderer, "Line + destination", x, y);
                    y += 14;
                    c.drawText(textRenderer, "Route", x, y, FlatUi.TEXT_DIM, false);
                    y += 10;
                    String routeName = tile.routes().isEmpty() ? "" : tile.routes().get(0);
                    String label = routeName.isEmpty() ? "Pick a route…" : routeName.replace("||", " · ");
                    FlatUi.button(c, textRenderer, textRenderer.trimToWidth(label, w - 10), x, y, w, FIELD, mx, my, ButtonStyle.FLAT);
                    hit(x, y, w, FIELD, HIT_PICK, 0, 0);
                    y += FIELD + 5;
                    y = segmentedField(c, mx, my, "Wording", new String[]{"Terminus", "Direction", "Custom"}, Math.min(2, tile.num()), x, y, w, SEG_DEST_MODE);
                    if (tile.num() == 2) {
                        y = field(c, mx, my, "Custom wording", customBox, x, y, w);
                    } else {
                        String preview = tile.num() == 1 ? SignContext.direction(routeName) : SignContext.destination(routeName);
                        c.drawText(textRenderer, textRenderer.trimToWidth("Now: " + (preview.isEmpty() ? "(unknown route)" : preview), w), x, y, FlatUi.TEXT_FAINT, false);
                        y += 12;
                    }
                }
                case SPACER -> {
                    FlatUi.heading(c, textRenderer, "Space", x, y);
                    y += 14;
                    y = numberField(c, mx, my, "Width (units, 64 per block)", NUM_SPACER, 0, 128, 1, 0, x, y, w);
                }
                default -> {
                }
            }
            if (tile.type() != TileType.SPACER) {
                // Bottom-anchored while everything fits; once the pane scrolls it simply follows the fields.
                int geomTop = rightY + rightH - 6 - FlatUi.BUTTON_HEIGHT - 3 * (FIELD + 15) - 14 - (FIELD + 15)
                        - (FIELD + 5) - inspectorScroll;
                if (geomTop > y) {
                    y = geomTop;
                }
                FlatUi.heading(c, textRenderer, "Size & position", x, y);
                y += 12;
                // Where in the row: follow the row's alignment, or sit at an edge / the centre
                // on its own. An older edge arrow reads as the side it is pinned to.
                int zone = row == null ? -1 : SignLayout.zoneOf(tile, row.align);
                int placeShown = tile.place() != SignSpec.Place.AUTO ? tile.place().ordinal()
                        : tile.type() == TileType.ARROW && !tile.arg().isEmpty() ? zone + 2 : 0;
                y = segmentedField(c, mx, my, "Position in row", new String[]{"Row", "Left", "Centre", "Right"},
                        placeShown, x, y, w, SEG_PLACE);
                y = numberField(c, mx, my, "Scale", NUM_TILE_SCALE, 0.25f, 4, 0.05f, 2, x, y, w);
                y = numberField(c, mx, my, "Move right / left", NUM_TILE_DX, -256, 256, 0.5f, 1, x, y, w);
                y = numberField(c, mx, my, "Move down / up", NUM_TILE_DY, -256, 256, 0.5f, 1, x, y, w);
                boolean moved = tile.dx() != 0 || tile.dy() != 0;
                FlatUi.button(c, textRenderer, moved ? "Put back in its row slot" : "Drag it on the canvas to move",
                        x, y, w, FIELD, moved ? mx : -1, moved ? my : -1, ButtonStyle.FLAT);
                if (moved) {
                    hit(x, y, w, FIELD, HIT_TILE_RESET, 0, 0);
                }
                y += FIELD + 5;
            }
        }
        return y;
    }

    /** One cell of the arrow pad / symbol grid; returns its fill colour (what a symbol's cut-outs show). */
    private int symbolCell(DrawContext c, int mx, int my, int gx, int gy, int cell, boolean on) {
        boolean hovered = FlatUi.inside(mx, my, gx, gy, cell, cell) && my >= clipTop && my < clipBottom;
        int fill = on ? FlatUi.ACCENT_DIM : hovered ? 0xFF34343C : FlatUi.PANE_RAISED;
        FlatUi.rect(c, gx, gy, cell, cell, fill);
        FlatUi.outline(c, gx, gy, cell, cell, on ? FlatUi.ACCENT : FlatUi.BORDER_STRONG);
        return fill;
    }

    /** A row of colour swatches (with a "none" cell when {@code allowNone}); the choice lands in the tile's arg. */
    private int colorField(DrawContext c, int mx, int my, String label, String chosen, boolean allowNone, int x, int y, int w) {
        c.drawText(textRenderer, label, x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        int count = SignLayout.COLOR_NAMES.length + (allowNone ? 1 : 0);
        int cell = Math.max(10, Math.min(16, (w - (count - 1) * 2) / count));
        for (int i = 0; i < count; i++) {
            int index = allowNone ? i : i + 1; // 0 = none
            int gx = x + i * (cell + 2);
            boolean on = index == 0 ? SignLayout.namedColor(chosen) == 0
                    : SignLayout.COLOR_NAMES[index - 1].equalsIgnoreCase(chosen);
            if (index == 0) {
                FlatUi.rect(c, gx, y, cell, cell, FlatUi.INPUT);
                c.drawText(textRenderer, "×", gx + (cell - textRenderer.getWidth("×")) / 2, y + (cell - 8) / 2, FlatUi.TEXT_DIM, false);
            } else {
                FlatUi.rect(c, gx, y, cell, cell, SignLayout.COLOR_VALUES[index - 1]);
            }
            FlatUi.outline(c, gx, y, cell, cell, on ? FlatUi.ACCENT : FlatUi.BORDER_STRONG);
            if (on) {
                FlatUi.outline(c, gx + 1, y + 1, cell - 2, cell - 2, FlatUi.ACCENT);
            }
            hit(gx, y, cell, cell, HIT_COLOR, index, 0);
        }
        return y + cell + 6;
    }

    private void deleteButton(DrawContext c, int mx, int my, String label, int x, int w) {
        int deleteY = rightY + rightH - 6 - FlatUi.BUTTON_HEIGHT;
        FlatUi.button(c, textRenderer, label, x, deleteY, w, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.DANGER);
        hit(x, deleteY, w, FlatUi.BUTTON_HEIGHT, HIT_DELETE_SEL, 0, 0);
    }

    private int field(DrawContext c, int mx, int my, String label, TextBox box, int x, int y, int w) {
        c.drawText(textRenderer, textRenderer.trimToWidth(label, w), x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        box.setBounds(x, y, w, FIELD);
        box.render(c, mx, my);
        showBox(box, y, FIELD);
        return y + FIELD + 5;
    }

    private int segmentedField(DrawContext c, int mx, int my, String label, String[] options, int chosen, int x, int y,
                               int w, int which) {
        c.drawText(textRenderer, label, x, y, FlatUi.TEXT_DIM, false);
        y += 10;
        FlatUi.segmented(c, textRenderer, options, chosen, x, y, w, FIELD, mx, my);
        for (int i = 0; i < options.length; i++) {
            hit(x + w * i / options.length, y, w / options.length, FIELD, HIT_SEGMENT, which, i);
        }
        return y + FIELD + 5;
    }

    private int toggleField(DrawContext c, int mx, int my, String label, String value, boolean on, int x, int y, int w,
                            int which) {
        c.drawText(textRenderer, label, x, y + 4, FlatUi.TEXT_DIM, false);
        int bw = Math.min(w - textRenderer.getWidth(label) - 6, Math.max(60, textRenderer.getWidth(value) + 14));
        int bx = x + w - bw;
        FlatUi.button(c, textRenderer, value, bx, y, bw, FIELD, mx, my, on ? ButtonStyle.PRIMARY : ButtonStyle.FLAT);
        hit(bx, y, bw, FIELD, HIT_TOGGLE, which, 0);
        return y + FIELD + 5;
    }

    private int chips(DrawContext c, int mx, int my, List<String> routes, int x, int y, int w) {
        int cx = x;
        for (int i = 0; i < routes.size(); i++) {
            String route = routes.get(i);
            boolean diamond = route.startsWith("d:");
            var bullet = PosterLayout.lineBullet(diamond ? route.substring(2) : route);
            String label = (diamond ? "◆ " : "") + bullet.label() + "  ×";
            int cw = textRenderer.getWidth(label) + 8;
            if (cx + cw > x + w && cx > x) {
                cx = x;
                y += 15;
            }
            boolean hovered = FlatUi.inside(mx, my, cx, y, cw, 12);
            FlatUi.chip(c, textRenderer, label, cx, y, hovered ? FlatUi.DANGER : bullet.color(), hovered);
            hit(cx, y, cw, 12, HIT_CHIP, i, 0);
            cx += cw + 3;
        }
        if (routes.isEmpty()) {
            c.drawText(textRenderer, "no bullets yet", x, y + 2, FlatUi.TEXT_FAINT, false);
        }
        return y + 17;
    }

    // -------------------------------------------------------------- popup

    private int popupCount() {
        return switch (popup) {
            case ROUTE -> popupRoutes.size();
            case EXIT -> popupExits.size();
            default -> popupLines.size();
        };
    }

    private int popupVisibleRows() {
        return Math.max(3, Math.min(12, (height - 70) / 16));
    }

    private void drawPopup(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        int pw = 220;
        int rows = popupVisibleRows();
        int ph = 24 + rows * 16 + 8;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        FlatUi.rect(c, px, py, pw, ph, FlatUi.PANE);
        FlatUi.outline(c, px, py, pw, ph, FlatUi.BORDER_STRONG);
        hit(px, py, pw, ph, HIT_POPUP, 0, 0);
        String heading = switch (popup) {
            case ROUTE -> "Pick a route";
            case EXIT -> "Pick an exit (from MTR's station settings)";
            case LINE_DIAMOND, LINE_TOKEN_DIAMOND -> "Pick a line (express diamond)";
            default -> "Pick a line";
        };
        c.drawText(textRenderer, textRenderer.trimToWidth(heading, pw - 16), px + 8, py + 8, FlatUi.TEXT, false);
        int listTop = py + 24;
        int count = popupCount();
        if (count == 0) {
            String none = switch (popup) {
                case ROUTE -> "No routes with generated paths yet";
                case EXIT -> "This station has no exits yet";
                default -> "No lines in this world yet";
            };
            c.drawText(textRenderer, textRenderer.trimToWidth(none, pw - 16), px + 8, listTop + 4, FlatUi.TEXT_FAINT, false);
            return;
        }
        for (int i = 0; i < rows; i++) {
            int index = i + popupScroll;
            if (index >= count) {
                break;
            }
            int ry = listTop + i * 16;
            boolean hovered = FlatUi.inside(mx, my, px + 1, ry, pw - 2, 16);
            if (hovered) {
                FlatUi.rect(c, px + 1, ry, pw - 2, 16, FlatUi.HOVER);
            }
            switch (popup) {
                case ROUTE -> {
                    SignContext.RouteOption option = popupRoutes.get(index);
                    int cw = FlatUi.chip(c, textRenderer, RouteBullets.label(option.routeName()), px + 8, ry + 2, option.color(), false);
                    String label = option.line() + (option.direction().isEmpty() ? "" : " · " + option.direction());
                    c.drawText(textRenderer, textRenderer.trimToWidth(label, pw - cw - 24), px + 8 + cw + 6, ry + 4, FlatUi.TEXT, false);
                }
                case EXIT -> {
                    SignContext.Exit exit = popupExits.get(index);
                    String label = exit.name() + (exit.destinations().isEmpty() ? "" : "  ·  " + String.join(" & ", exit.destinations()));
                    c.drawText(textRenderer, textRenderer.trimToWidth(label, pw - 16), px + 8, ry + 4, FlatUi.TEXT, false);
                }
                default -> {
                    PosterLayout.LineOption option = popupLines.get(index);
                    int cw = FlatUi.chip(c, textRenderer, option.bullet().label(), px + 8, ry + 2, option.bullet().color(), false);
                    c.drawText(textRenderer, textRenderer.trimToWidth(option.name(), pw - cw - 24), px + 8 + cw + 6, ry + 4, FlatUi.TEXT, false);
                }
            }
            hit(px + 1, ry, pw - 2, 16, HIT_POPUP_ROW, index, 0);
        }
        FlatUi.scrollThumb(c, px + pw, listTop, rows * 16, count * 16, popupScroll * 16);
    }
}
