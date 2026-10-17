package com.stationannouncer.client.track;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.track.TrackEdit;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackProfile.Anchor;
import com.stationannouncer.track.TrackProfile.GradePoint;
import com.stationannouncer.track.TrackProfile.Piece;
import com.stationannouncer.track.TrackProfile.PieceType;
import com.stationannouncer.track.TrackProfile.TiltPoint;
import com.stationannouncer.track.TrackRegistry;
import com.stationannouncer.track.TrackSolver;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;
import org.mtr.mod.screen.RailStyleSelectorScreen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The track editor, round 2 (TRACK_GEOMETRY_PLAN.md "REDESIGN ROUND 2"): brush + right-click a rail
 * node opens it INSTEAD of MTR's Rail Shape Modifier ({@code RailModifierScreenOpenMixin}).
 *
 * <h2>Anatomy (FlatUi only — no vanilla widgets; works from ~427×240)</h2>
 * <ul>
 *   <li>Row 1: title · view TABS [Schematic | 3D | World] · Undo / Redo · Cancel / Save.</li>
 *   <li>Row 2: TOOLS [Select · Bézier · Tilt · Height] (keys 1–4) · [Auto tilt ▾] · the tracks at the
 *       node as chips (✓ / ● edited / ✗ does not reach its far node), then the selected track's
 *       parallel followers (⇄) and the "Follow" toggle.</li>
 *   <li>Main: the active view, full width. The editor draws EVERY handle itself as a 2D overlay through
 *       {@link TrackViewport} (project / groundAt), so the same tools work in all three views.</li>
 *   <li>One small floating INSPECTOR CARD (top right of the view) for the current selection only:
 *       a Bézier point, a tilt point, a height point, or — with nothing else selected — the track
 *       (stats, height mode, Reset, and disclosures: MTR settings, At this node, Advanced arcs &
 *       spirals).</li>
 *   <li>Profile strip (height + tilt lanes over distance; collapsible) and a status line with the
 *       current tool's hint.</li>
 * </ul>
 *
 * <h2>Editing model</h2>
 * The Bézier tool turns a track into SPLINE geometry ({@link TrackEdit#toSpline}, shape unchanged) on
 * the first click and inserts anchors exactly on the curve ({@link TrackEdit#insertAnchor}); a spline
 * always meets both nodes, so no solver is involved. The round-1 arcs/spirals model (TrackSolver,
 * locks) is still there for power users under "Advanced". Tilt / height points are added by clicking
 * the track (or a profile lane) and dragged up/down. PARALLEL TRACKS FOLLOW: the selected track's
 * companions ({@link TrackEditorParallels}) are rebuilt with {@link TrackEdit#parallel} after every
 * change and saved with it. Every change is undoable (snapshots of all drafts, 50 steps).
 *
 * Live preview: every changed draft that closes is pushed to {@link TrackRegistry#setPreview} at most
 * 5×/s; all previews are cleared when the screen goes away ({@link #removed}) unless a save holds them.
 */
@Environment(EnvType.CLIENT)
public class TrackEditorScreen extends Screen {
    enum ViewTab { SCHEMATIC, VIEW3D, WORLD }

    enum Tool { SELECT, BEZIER, TILT, HEIGHT }

    private enum SelKind { TRACK, ANCHOR, TILT, GRADE, PIECE }

    private enum Drag { NONE, VIEW, ANCHOR, HANDLE, TILT_VIEW, GRADE_VIEW, TILT_STRIP, GRADE_STRIP, SCRUB, SCRUB_PENDING }

    private static final int MARGIN = 4;
    private static final int ROW1_Y = 2;
    private static final int ROW2_Y = 20;
    private static final int MAIN_Y = 39;
    private static final int STATUS_H = 12;
    private static final int UNDO_LIMIT = 50;

    private static final int COLOR_OTHER = 0xFF6A6A76;
    private static final int COLOR_FOLLOW = 0xFF35C2B0;
    private static final int COLOR_GHOST = 0x44FFFFFF;
    private static final int COLOR_HEIGHT = 0xFF7FD0FF;
    private static final int COLOR_TILT = 0xFFF0A030;
    private static final int COLOR_ANCHOR = 0xFFEDEDF0;
    private static final int COLOR_ARC = 0xFF3D8BFF;
    private static final int COLOR_SPIRAL = 0xFFF0A030;
    private static final int COLOR_STRAIGHT = 0xFFD8D8E0;

    private static final double ANCHOR_R = 7;
    private static final double KNOB_R = 5;
    private static final double HIT_R = 10;
    private static final double TRACK_HIT = 8;

    // hit ids (rectangles recorded while drawing)
    private static final int HIT_TAB = 1, HIT_TOOL = 2, HIT_AUTO = 3, HIT_AUTO_MENU = 4, HIT_UNDO = 5, HIT_REDO = 6;
    private static final int HIT_SAVE = 7, HIT_CANCEL = 8, HIT_CHIP = 9, HIT_FOLLOW = 10, HIT_FOLLOWER = 11, HIT_BLOCK = 12;
    private static final int HIT_CARD_FOLD = 13, HIT_SCRUB = 14, HIT_DELETE_SEL = 15, HIT_SEG = 16, HIT_RESET = 17;
    private static final int HIT_DISCLOSE = 18, HIT_STYLES = 19, HIT_FLIP = 20, HIT_MATCH = 21, HIT_PIECE_ROW = 22;
    private static final int HIT_ADD_PIECE = 23, HIT_SPLIT = 24, HIT_EASE_ADD = 25, HIT_EASE_REMOVE = 26, HIT_LOCK = 27;
    private static final int HIT_LANES = 28, HIT_STRIP_FOLD = 29, HIT_AUTO_APPLY = 30, HIT_CLEAR_TILT = 31, HIT_FIT = 32, HIT_MTR = 33;
    private static final int HIT_MODAL_SAVE = 40, HIT_MODAL_DISCARD = 41, HIT_MODAL_BACK = 42, HIT_DISCARD = 43, HIT_KEEP = 44;

    private static final int SEG_VMODE = 1, SEG_SMOOTH = 2, SEG_SHAPE = 3, SEG_TYPE = 4, SEG_HAND = 5, SEG_FOLLOW = 6;
    private static final int DIS_NODE = 2, DIS_ADVANCED = 3;

    private static final int F_AX = 1, F_AZ = 2, F_AHEAD = 3, F_AIN = 4, F_AOUT = 5, F_TILT_S = 6, F_TILT_DEG = 7;
    private static final int F_GRADE_S = 8, F_GRADE_Y = 9, F_GRADE_CURVE = 10, F_VRADIUS = 11, F_SPEED = 12, F_CAP = 13;
    private static final int F_RADIUS = 14, F_LENGTH = 15, F_EASE = 16, F_NODE_TILT = 100;

    private static final int TILT_A = -2, TILT_B = -3;

    // overlay handle kinds
    private static final int K_ANCHOR = 1, K_IN = 2, K_OUT = 3, K_TILT = 4, K_GRADE = 5;

    private final long nodeX, nodeY, nodeZ;
    private final List<TrackEditorDraft> drafts = new ArrayList<>();
    private int sel;

    // ---- parallel followers
    /** Every follower draft loaded so far (by key) — they keep their edits when another track is selected. */
    private final Map<String, TrackEditorDraft> followerDrafts = new LinkedHashMap<>();
    /** Links of each draft index (computed lazily). */
    private final Map<Integer, List<Link>> links = new HashMap<>();
    private boolean followOn = true;

    private static final class Link {
        final TrackEditorDraft draft;
        final double lateral;
        final boolean reversed;
        /** Why this companion cannot follow (null = it follows). */
        String problem;

        Link(TrackEditorDraft draft, double lateral, boolean reversed, String problem) {
            this.draft = draft;
            this.lateral = lateral;
            this.reversed = reversed;
            this.problem = problem;
        }
    }

    // ---- selection / tools / views
    private SelKind selKind = SelKind.TRACK;
    private int selIndex = -1;
    private Tool tool = Tool.SELECT;
    private ViewTab tab = ViewTab.SCHEMATIC;
    private final TrackSchematicView schematic = new TrackSchematicView();
    private Pane pane3d;
    private Pane paneWorld;
    private boolean sceneDirty = true;
    private boolean previewDirty;
    private long lastPreview;

    // ---- undo
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Snapshot gestureSnap;

    // ---- layout
    private int mainX, mainY, mainW, mainH;
    private int stripX, stripY, stripW, stripH;
    private int cardX, cardY, cardW, cardH;
    private int laneX, laneW, lanesTop, lanesH, heightTop, heightH, tiltTop, tiltH;
    private double hMin, hMax, tRange;
    private double[] laneFreeze;
    private boolean stripCollapsed;
    /** 0 = height lane, 1 = tilt lane, 2 = both. */
    private int lanes = 2;

    // ---- card
    private boolean cardCollapsed;
    private int cardScroll, cardContent;
    private boolean showNode, showAdvanced;

    // ---- input
    private final List<int[]> hits = new ArrayList<>();
    private final Map<Integer, TextBox> numberBoxes = new HashMap<>();
    private final Map<Integer, double[]> scrubMeta = new HashMap<>();
    /** Number box pressed but not yet moved (a click focuses it, a drag scrubs it). */
    private TextBox scrubBox;
    /** Raw (unrounded) value being scrubbed and the mouse x it was last advanced at. */
    private double scrubValue, scrubLastX;
    /** Something under the mouse scrubs this frame: show the ↔ cursor. */
    private boolean wantResizeCursor;
    private boolean resizeCursorShown;
    private static long resizeCursor;
    private final List<TextBox> visibleBoxes = new ArrayList<>();
    private TextBox focused;
    private int clipTop, clipBottom = Integer.MAX_VALUE;
    private Drag drag = Drag.NONE;
    private int dragArg;
    private boolean dragOut;
    private double dragStartX, dragStartY, dragStartValue;
    private double grabDX, grabDZ, dragPlaneY;
    private int dragButton;
    /** Profile a geometry field edit (Advanced) started from — re-solved from it so values never drift. */
    private TrackProfile editBase;

    // overlay caches (from the last frame; click hit tests use them)
    private final List<double[]> overlay = new ArrayList<>();
    private final Map<TrackEditorDraft, double[][]> projected = new IdentityHashMap<>();
    private double hoverS = Double.NaN;
    private int hoverHandle = -1;
    private int hoverOther = -1;
    private String hoverText = "";

    // ---- popovers / modals
    private boolean autoMenu;
    private boolean stylesModal;
    private boolean discardModal;
    private double autoSpeed = Double.NaN;
    private double autoCap = 12;
    private double easeLength = 6;
    private String toast = "";
    private long toastUntil;

    // ================================================================= opening

    /** Called by {@code RailModifierScreenOpenMixin} in place of MTR's screen. Returns false to let MTR's open. */
    public static boolean openFromMtr(String railId) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen instanceof TrackEditorScreen) {
                return true;
            }
            final MinecraftClientData data = MinecraftClientData.getInstance();
            final Rail rail = data.railIdMap.get(railId);
            if (rail == null) {
                return false;
            }
            long x, y, z;
            final var facing = data.getFacingRailAndBlockPos(false);
            if (facing != null && facing.left().getHexId().equals(railId)) {
                x = facing.right().getX();
                y = facing.right().getY();
                z = facing.right().getZ();
            } else {
                final long[] nodes = com.stationannouncer.mtr.track.TrackRails.access(rail).stationannouncer$nodes();
                x = nodes[0];
                y = nodes[1];
                z = nodes[2];
            }
            return open(x, y, z, railId);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Opens the editor on the rails at node (x, y, z), selecting {@code preferredHexId} if given. */
    public static boolean open(long x, long y, long z, String preferredHexId) {
        final TrackEditorScreen screen = new TrackEditorScreen(x, y, z, preferredHexId);
        if (screen.drafts.isEmpty()) {
            return false;
        }
        MinecraftClient.getInstance().setScreen(screen);
        return true;
    }

    TrackEditorScreen(long x, long y, long z, String preferredHexId) {
        super(Text.literal("Track editor"));
        nodeX = x;
        nodeY = y;
        nodeZ = z;
        // the editor must start from the saved state, never a stale preview
        TrackRegistry.clearPreviews();
        final MinecraftClientData data = MinecraftClientData.getInstance();
        final List<Rail> rails = new ArrayList<>();
        final Object2ObjectOpenHashMap<Position, Rail> atNode = data.positionsToRail.get(new Position(x, y, z));
        if (atNode != null) {
            rails.addAll(atNode.values());
        }
        if (rails.isEmpty() && preferredHexId != null && data.railIdMap.get(preferredHexId) != null) {
            rails.add(data.railIdMap.get(preferredHexId));
        }
        for (final Rail rail : rails) {
            final TrackEditorDraft draft = TrackEditorDraft.of(rail, x, y, z);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        drafts.sort(Comparator.comparingDouble(d -> Math.floorMod(Math.round(Math.toDegrees(d.outgoingHeading()) * 10), 3600)));
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).hexId.equals(preferredHexId)) {
                sel = i;
            }
        }
        if (!drafts.isEmpty()) {
            autoCap = d().profile.cantCap > 0 ? d().profile.cantCap : 12;
        }
    }

    private TrackEditorDraft d() {
        return drafts.get(sel);
    }

    // ---------------------------------------------------------------- dev hooks

    void devTab(ViewTab view) {
        setTab(view);
    }

    private void setTab(ViewTab next) {
        if (next == tab) {
            return;
        }
        if (tab == ViewTab.WORLD && paneWorld != null) {
            paneWorld.close();
        }
        tab = next;
        sceneDirty = true;
        if (next == ViewTab.WORLD) {
            try {
                paneWorld().open();
            } catch (RuntimeException | LinkageError e) {
                showToast("World view unavailable: " + e.getMessage());
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        // back from a child screen (MTR's styles) on the World tab: take the camera again
        if (tab == ViewTab.WORLD && paneWorld != null) {
            paneWorld.open();
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // nothing: render() paints its own panels (and must leave the World pane open)
    }

    void devTool(Tool next) {
        setTool(next);
    }

    void devDisclose(String what) {
        switch (what) {
            case "node" -> showNode = true;
            case "mtr" -> {
                selKind = SelKind.TRACK;
                cardScroll = 0;
            }
            case "advanced" -> showAdvanced = true;
            default -> {
            }
        }
    }

    // ================================================================= view panes

    /** What the editor needs from the 3D and World views (both embed the same way). */
    private interface Pane extends TrackViewport {
        void scene(List<TrackPath> context, List<TrackPath> followers, TrackPath selected, double fx, double fy, double fz);

        default void open() {
        }

        default void close() {
        }

        void marker(double s);

        void draw(DrawContext c, int x, int y, int w, int h, int mx, int my, float delta);

        boolean press(double mx, double my, int button);

        boolean drag(double mx, double my, int button, double dx, double dy);

        boolean release(double mx, double my, int button);

        boolean scroll(double mx, double my, double amount);

        void reset();
    }

    private Pane pane3d() {
        if (pane3d == null) {
            final TrackView3D view = new TrackView3D();
            pane3d = new Pane() {
                @Override
                public boolean project(double x, double y, double z, double[] out) {
                    return view.project(x, y, z, out);
                }

                @Override
                public boolean groundAt(double sx, double sy, double planeY, double[] out) {
                    return view.groundAt(sx, sy, planeY, out);
                }

                @Override
                public void scene(List<TrackPath> context, List<TrackPath> followers, TrackPath selected, double fx, double fy, double fz) {
                    final List<TrackPath> all = new ArrayList<>(context);
                    all.addAll(followers);
                    view.setScene(all, selected, fx, fy, fz);
                }

                @Override
                public void marker(double s) {
                    view.setMarker(s);
                }

                @Override
                public void draw(DrawContext c, int x, int y, int w, int h, int mx, int my, float delta) {
                    view.render(c, x, y, w, h, mx, my, delta);
                }

                @Override
                public boolean press(double mx, double my, int button) {
                    return view.mouseClicked(mx, my, button);
                }

                @Override
                public boolean drag(double mx, double my, int button, double dx, double dy) {
                    return view.mouseDragged(mx, my, button, dx, dy);
                }

                @Override
                public boolean release(double mx, double my, int button) {
                    return view.mouseReleased(mx, my, button);
                }

                @Override
                public boolean scroll(double mx, double my, double amount) {
                    return view.mouseScrolled(mx, my, amount);
                }

                @Override
                public void reset() {
                    view.resetView();
                }
            };
        }
        return pane3d;
    }

    private Pane paneWorld() {
        if (paneWorld == null) {
            final TrackWorldView view = new TrackWorldView();
            paneWorld = new Pane() {
                @Override
                public boolean project(double x, double y, double z, double[] out) {
                    return view.project(x, y, z, out);
                }

                @Override
                public boolean groundAt(double sx, double sy, double planeY, double[] out) {
                    return view.groundAt(sx, sy, planeY, out);
                }

                @Override
                public void scene(List<TrackPath> context, List<TrackPath> followers, TrackPath selected, double fx, double fy, double fz) {
                    view.setScene(context, selected, followers, fx, fy, fz);
                }

                @Override
                public void open() {
                    view.open();
                }

                @Override
                public void close() {
                    view.close();
                }

                @Override
                public void marker(double s) {
                    view.setMarker(s);
                }

                @Override
                public void draw(DrawContext c, int x, int y, int w, int h, int mx, int my, float delta) {
                    view.render(c, x, y, w, h, mx, my, delta);
                }

                @Override
                public boolean press(double mx, double my, int button) {
                    return view.mouseClicked(mx, my, button);
                }

                @Override
                public boolean drag(double mx, double my, int button, double dx, double dy) {
                    return view.mouseDragged(mx, my, button, dx, dy);
                }

                @Override
                public boolean release(double mx, double my, int button) {
                    return view.mouseReleased(mx, my, button);
                }

                @Override
                public boolean scroll(double mx, double my, double amount) {
                    return view.mouseScrolled(mx, my, amount);
                }

                @Override
                public void reset() {
                    view.resetView();
                }
            };
        }
        return paneWorld;
    }

    /** The active view as a TrackViewport (null only if a view failed to build). */
    private TrackViewport viewport() {
        return switch (tab) {
            case SCHEMATIC -> schematic;
            case VIEW3D -> pane3d();
            case WORLD -> paneWorld();
        };
    }

    private Pane activePane() {
        return tab == ViewTab.VIEW3D ? pane3d() : tab == ViewTab.WORLD ? paneWorld() : null;
    }

    // ================================================================= undo

    private final class Snapshot {
        final Map<TrackEditorDraft, Object[]> states = new IdentityHashMap<>();
        final boolean follow;

        Snapshot() {
            for (final TrackEditorDraft draft : allDrafts()) {
                states.put(draft, draft.snapshot());
            }
            follow = followOn;
        }

        boolean isCurrent() {
            if (follow != followOn) {
                return false;
            }
            for (final Map.Entry<TrackEditorDraft, Object[]> entry : states.entrySet()) {
                if (!entry.getKey().sameAs(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        void restore() {
            states.forEach(TrackEditorDraft::restore);
            followOn = follow;
        }
    }

    /** Call BEFORE a discrete change (inside a gesture the gesture's own snapshot covers it). */
    private void edit() {
        if (gestureSnap != null) {
            return;
        }
        pushUndo(new Snapshot());
    }

    private void pushUndo(Snapshot snapshot) {
        undo.push(snapshot);
        while (undo.size() > UNDO_LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    /** A drag / typing session = one undo step (dropped if nothing changed). */
    private void beginGesture() {
        if (gestureSnap == null) {
            gestureSnap = new Snapshot();
        }
    }

    private void endGesture() {
        if (gestureSnap != null) {
            if (!gestureSnap.isCurrent()) {
                pushUndo(gestureSnap);
            }
            gestureSnap = null;
        }
        editBase = null;
    }

    private void undo() {
        endGesture();
        if (undo.isEmpty()) {
            showToast("Nothing to undo");
            return;
        }
        redo.push(new Snapshot());
        undo.pop().restore();
        afterRestore();
    }

    private void redo() {
        endGesture();
        if (redo.isEmpty()) {
            showToast("Nothing to redo");
            return;
        }
        undo.push(new Snapshot());
        redo.pop().restore();
        afterRestore();
    }

    private void afterRestore() {
        clampSelection();
        sceneDirty = true;
        previewDirty = true;
    }

    private void clampSelection() {
        final TrackProfile p = d().profile;
        final boolean ok = switch (selKind) {
            case TRACK -> true;
            case ANCHOR -> d().isSpline() && selIndex >= 0 && selIndex < p.anchors.size();
            case TILT -> selIndex == TILT_A || selIndex == TILT_B || selIndex >= 0 && selIndex < p.tilts.size();
            case GRADE -> selIndex >= 0 && selIndex < p.grades.size();
            case PIECE -> !d().isSpline() && selIndex >= 0 && selIndex < p.pieces.size();
        };
        if (!ok) {
            select(SelKind.TRACK, -1);
        }
    }

    private List<TrackEditorDraft> allDrafts() {
        final List<TrackEditorDraft> all = new ArrayList<>(drafts);
        all.addAll(followerDrafts.values());
        return all;
    }

    // ================================================================= followers

    private List<Link> links() {
        return links.computeIfAbsent(sel, i -> findLinks(drafts.get(i)));
    }

    private List<Link> findLinks(TrackEditorDraft source) {
        final List<Link> result = new ArrayList<>();
        try {
            final Set<String> skip = new HashSet<>();
            drafts.forEach(draft -> skip.add(draft.hexId));
            final TrackProfile id = source.identity();
            for (final TrackEditorParallels.Companion companion
                : TrackEditorParallels.find(source.nodes, id.headingA, id.headingB, skip)) {
                if (result.size() >= 4) {
                    break;
                }
                final String key = com.stationannouncer.mtr.track.TrackRails.key(companion.rail());
                TrackEditorDraft draft = followerDrafts.get(key);
                if (draft == null) {
                    final Position p = companion.nearA();
                    draft = TrackEditorDraft.of(companion.rail(), p.getX(), p.getY(), p.getZ());
                    if (draft == null) {
                        continue;
                    }
                    followerDrafts.put(key, draft);
                }
                final TrackProfile other = draft.identity();
                final String problem = TrackEditorParallels.headingsParallel(id.headingA, id.headingB, other.headingA, other.headingB,
                    companion.reversed()) ? null : "not parallel at its nodes";
                result.add(new Link(draft, companion.lateral(), companion.reversed(), problem));
            }
        } catch (RuntimeException | LinkageError e) {
            // followers are a convenience; never let them take the editor down
        }
        return result;
    }

    /** Rebuilds every follower of the selected track from its current draft. */
    private void updateFollowers() {
        if (!followOn) {
            return;
        }
        final TrackEditorDraft source = d();
        for (final Link link : links()) {
            if (link.problem != null && !link.problem.startsWith("~")) {
                continue;
            }
            final TrackEditorDraft follower = link.draft;
            try {
                if (source.resetRequested && source.isMtrShape()) {
                    if (follower.mtrImport != null) {
                        follower.setProfile(follower.mtrImport.copy(), "");
                        follower.resetRequested = true;
                    }
                    link.problem = null;
                } else if (!source.changed()) {
                    follower.revertGeometry();
                    link.problem = null;
                } else if (!source.closes()) {
                    link.problem = "~waits until the track reaches its node";
                } else {
                    final TrackProfile next = TrackEdit.parallel(follower.identity(), source.path, link.lateral, link.reversed);
                    if (next == null) {
                        follower.revertGeometry();
                        link.problem = "~can't stay parallel to this shape";
                    } else {
                        follower.resetRequested = false;
                        follower.setProfile(next, "");
                        link.problem = null;
                    }
                }
            } catch (RuntimeException e) {
                link.problem = "~" + (e.getMessage() == null ? "failed" : e.getMessage());
            }
        }
    }

    private void setFollow(boolean on) {
        if (on == followOn) {
            return;
        }
        edit();
        followOn = on;
        if (on) {
            updateFollowers();
        } else {
            for (final Link link : links()) {
                link.draft.revertGeometry();
            }
        }
        sceneDirty = true;
        previewDirty = true;
        showToast(on ? "Parallel tracks follow the selected one" : "Parallel tracks stay as they are");
    }

    private int followingCount() {
        int n = 0;
        if (followOn) {
            for (final Link link : links()) {
                if (link.problem == null) {
                    n++;
                }
            }
        }
        return n;
    }

    // ================================================================= layout

    private void layout() {
        stripH = stripCollapsed ? 13 : Math.max(44, Math.min(96, (int) (height * 0.26)));
        stripX = MARGIN;
        stripW = width - 2 * MARGIN;
        stripY = height - STATUS_H - stripH;
        mainX = MARGIN;
        mainY = MAIN_Y;
        mainW = width - 2 * MARGIN;
        mainH = Math.max(30, stripY - 2 - mainY);
        cardW = Math.min(width < 560 ? 140 : 164, mainW / 2);
        cardX = mainX + mainW - cardW - 4;
        cardY = mainY + 4;
    }

    private void hit(int x, int y, int w, int h, int id, int arg, int arg2) {
        final int top = Math.max(y, clipTop);
        final int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new int[]{x, top, w, bottom - top, id, arg, arg2});
        }
    }

    private void noClip() {
        clipTop = 0;
        clipBottom = Integer.MAX_VALUE;
    }

    private boolean overCard(double mx, double my) {
        return FlatUi.inside(mx, my, cardX, cardY, cardW, cardH);
    }

    // ================================================================= render

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        if (drafts.isEmpty()) {
            close();
            return;
        }
        layout();
        hits.clear();
        visibleBoxes.clear();
        noClip();
        final boolean modal = stylesModal || discardModal;
        final int hmx = modal ? -1 : mx;
        final int hmy = modal ? -1 : my;
        hoverText = "";
        wantResizeCursor = false;
        if (tab == ViewTab.WORLD) {
            // the World view shows the real world through the pane: paint only around it
            FlatUi.rect(c, 0, 0, width, mainY - 1, FlatUi.GROUND);
            FlatUi.rect(c, 0, mainY + mainH + 1, width, height - mainY - mainH - 1, FlatUi.GROUND);
            FlatUi.rect(c, 0, mainY - 1, mainX - 1, mainH + 2, FlatUi.GROUND);
            FlatUi.rect(c, mainX + mainW + 1, mainY - 1, width - mainX - mainW - 1, mainH + 2, FlatUi.GROUND);
        } else {
            FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        }
        drawMain(c, hmx, hmy, delta);
        drawRow1(c, hmx, hmy);
        drawRow2(c, hmx, hmy);
        drawCard(c, hmx, hmy);
        drawStrip(c, hmx, hmy);
        drawStatus(c);
        if (autoMenu) {
            drawAutoMenu(c, hmx, hmy);
        }
        if (stylesModal) {
            drawStylesModal(c, mx, my);
        }
        if (discardModal) {
            drawDiscardModal(c, mx, my);
        }
        updateCursor();
        pushPreviews();
    }

    // ----------------------------------------------------------------- row 1

    private void drawRow1(DrawContext c, int mx, int my) {
        int x = MARGIN;
        if (width >= 470) {
            c.drawText(textRenderer, "Track editor", x, ROW1_Y + 4, FlatUi.TEXT, false);
            x += textRenderer.getWidth("Track editor") + 8;
        }
        final String[] labels = {"Schematic", "3D", "World"};
        final int tw = width >= 470 ? 162 : 138;
        FlatUi.segmented(c, textRenderer, labels, tab.ordinal(), x, ROW1_Y, tw, 15, mx, my);
        for (int i = 0; i < labels.length; i++) {
            final int cx = x + tw * i / labels.length;
            hit(cx, ROW1_Y, x + tw * (i + 1) / labels.length - cx, 15, HIT_TAB, i, 0);
        }
        x += tw + 4;
        FlatUi.button(c, textRenderer, "Fit", x, ROW1_Y, 22, 15, mx, my, ButtonStyle.GHOST);
        hit(x, ROW1_Y, 22, 15, HIT_FIT, 0, 0);
        x += 26;

        int rx = width - MARGIN;
        final int saveW = 40;
        rx -= saveW;
        FlatUi.button(c, textRenderer, "Save", rx, ROW1_Y, saveW, 15, mx, my, ButtonStyle.PRIMARY, canSave());
        hit(rx, ROW1_Y, saveW, 15, HIT_SAVE, 0, 0);
        final int cancelW = 44;
        rx -= cancelW + 3;
        FlatUi.button(c, textRenderer, "Cancel", rx, ROW1_Y, cancelW, 15, mx, my, ButtonStyle.GHOST);
        hit(rx, ROW1_Y, cancelW, 15, HIT_CANCEL, 0, 0);
        rx -= 19;
        FlatUi.button(c, textRenderer, "↷", rx, ROW1_Y, 16, 15, mx, my, ButtonStyle.FLAT, !redo.isEmpty());
        hit(rx, ROW1_Y, 16, 15, HIT_REDO, 0, 0);
        rx -= 18;
        FlatUi.button(c, textRenderer, "↶", rx, ROW1_Y, 16, 15, mx, my, ButtonStyle.FLAT, !undo.isEmpty() || gestureSnap != null);
        hit(rx, ROW1_Y, 16, 15, HIT_UNDO, 0, 0);
        if (FlatUi.inside(mx, my, rx, ROW1_Y, 34, 15)) {
            hoverText = "Undo Ctrl+Z · Redo Ctrl+Shift+Z / Ctrl+Y";
        }
        final String sub = "node " + nodeX + " " + nodeY + " " + nodeZ;
        final int room = rx - 6 - x;
        if (room > 30) {
            c.drawText(textRenderer, textRenderer.trimToWidth(sub, room), x, ROW1_Y + 4, FlatUi.TEXT_FAINT, false);
        }
    }

    // ----------------------------------------------------------------- row 2

    private void drawRow2(DrawContext c, int mx, int my) {
        int x = MARGIN;
        final boolean compact = width < 470;
        final String[] names = {"Select", "Bézier", "Tilt", "Height"};
        for (final Tool t : Tool.values()) {
            final String label = names[t.ordinal()];
            final int w = textRenderer.getWidth(label) + (compact ? 16 : 22);
            final boolean on = t == tool;
            final boolean hot = FlatUi.inside(mx, my, x, ROW2_Y, w, 16);
            FlatUi.rect(c, x, ROW2_Y, w, 16, on ? FlatUi.ACCENT_DIM : hot ? 0xFF34343C : FlatUi.PANE_RAISED);
            FlatUi.outline(c, x, ROW2_Y, w, 16, on ? FlatUi.ACCENT : FlatUi.BORDER_STRONG);
            toolIcon(c, t, x + 3, ROW2_Y + 4, on || hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
            c.drawText(textRenderer, label, x + 13, ROW2_Y + 4, on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            if (!compact) {
                c.drawText(textRenderer, Integer.toString(t.ordinal() + 1), x + w - 7, ROW2_Y + 4, FlatUi.TEXT_FAINT, false);
            }
            hit(x, ROW2_Y, w, 16, HIT_TOOL, t.ordinal(), 0);
            if (hot) {
                hoverText = toolHint(t) + " (key " + (t.ordinal() + 1) + ")";
            }
            x += w + 2;
        }
        c.draw();
        x += 4;
        final String auto = "Auto tilt";
        final int aw = textRenderer.getWidth(auto) + 10;
        FlatUi.button(c, textRenderer, auto, x, ROW2_Y, aw, 16, mx, my, ButtonStyle.PRIMARY);
        hit(x, ROW2_Y, aw, 16, HIT_AUTO, 0, 0);
        if (FlatUi.inside(mx, my, x, ROW2_Y, aw, 16)) {
            hoverText = "Realistic cant from the curves at " + fmt(autoSpeed(), 0) + " km/h, max " + fmt(autoCap, 0) + "°"
                + (followingCount() > 0 ? " — parallel tracks too" : "");
        }
        x += aw;
        FlatUi.button(c, textRenderer, "▾", x, ROW2_Y, 11, 16, mx, my, ButtonStyle.PRIMARY);
        hit(x, ROW2_Y, 11, 16, HIT_AUTO_MENU, 0, 0);
        x += 11 + 3;
        final int mtrW = textRenderer.getWidth("MTR") + 8;
        FlatUi.button(c, textRenderer, "MTR", x, ROW2_Y, mtrW, 16, mx, my, ButtonStyle.FLAT);
        hit(x, ROW2_Y, mtrW, 16, HIT_MTR, 0, 0);
        if (FlatUi.inside(mx, my, x, ROW2_Y, mtrW, 16)) {
            hoverText = "MTR settings: shape " + ("TWO_RADII".equals(d().mtrShape) ? "Two radii" : "Quadratic")
                + ", vertical radius, styles — shown at the top of the track card";
        }
        x += mtrW + 8;

        // tracks at the node
        final int right = width - MARGIN;
        for (int i = 0; i < drafts.size(); i++) {
            final TrackEditorDraft draft = drafts.get(i);
            final String label = (i + 1) + " " + compass(draft.outgoingHeading());
            String flag;
            int flagColor;
            if (draft.blocksSave()) {
                flag = "✗";
                flagColor = FlatUi.DANGER;
            } else if (draft.dirty()) {
                flag = "●";
                flagColor = FlatUi.ACCENT;
            } else {
                flag = "✓";
                flagColor = draft.hadCustom ? FlatUi.OK : FlatUi.TEXT_FAINT;
            }
            final int w = textRenderer.getWidth(label) + textRenderer.getWidth(flag) + 12;
            if (x + w > right) {
                c.drawText(textRenderer, "…", x, ROW2_Y + 4, FlatUi.TEXT_FAINT, false);
                return;
            }
            final boolean on = i == sel;
            final boolean hot = FlatUi.inside(mx, my, x, ROW2_Y, w, 16);
            FlatUi.rect(c, x, ROW2_Y, w, 16, on ? FlatUi.SELECTED : hot ? FlatUi.HOVER : 0);
            FlatUi.outline(c, x, ROW2_Y, w, 16, on ? FlatUi.ACCENT : FlatUi.BORDER);
            c.drawText(textRenderer, label, x + 4, ROW2_Y + 4, on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            c.drawText(textRenderer, flag, x + w - 4 - textRenderer.getWidth(flag), ROW2_Y + 4, flagColor, false);
            hit(x, ROW2_Y, w, 16, HIT_CHIP, i, 0);
            if (hot) {
                hoverText = trackName(i) + " · " + fmt(draft.path.length(), 1) + " bl"
                    + (draft.blocksSave() ? " · does not reach its far node" : draft.dirty() ? " · edited" : draft.hadCustom ? " · custom shape" : " · MTR shape");
            }
            x += w + 2;
        }
        // parallel followers of the selected track
        final List<Link> list = links();
        if (list.isEmpty()) {
            return;
        }
        x += 4;
        final String toggle = followOn ? "⇄ Follow" : "⇄ Off";
        final int tw = textRenderer.getWidth(toggle) + 8;
        if (x + tw > right) {
            return;
        }
        final boolean toggleHot = FlatUi.inside(mx, my, x, ROW2_Y, tw, 16);
        FlatUi.rect(c, x, ROW2_Y, tw, 16, followOn ? 0x3035C2B0 : toggleHot ? FlatUi.HOVER : 0);
        FlatUi.outline(c, x, ROW2_Y, tw, 16, followOn ? COLOR_FOLLOW : FlatUi.BORDER_STRONG);
        c.drawText(textRenderer, toggle, x + 4, ROW2_Y + 4, followOn ? COLOR_FOLLOW : FlatUi.TEXT_DIM, false);
        hit(x, ROW2_Y, tw, 16, HIT_FOLLOW, 0, 0);
        if (toggleHot) {
            hoverText = "Parallel tracks follow: " + (followOn ? "ON — the tracks beside this one reshape to stay parallel" : "OFF");
        }
        x += tw + 2;
        for (int i = 0; i < list.size(); i++) {
            final Link link = list.get(i);
            final boolean ok = followOn && link.problem == null;
            final String label = (link.lateral > 0 ? "R " : "L ") + fmt(Math.abs(link.lateral), 1) + (ok ? "" : link.problem != null ? " ✗" : "");
            final int w = textRenderer.getWidth(label) + 8;
            if (x + w > right) {
                return;
            }
            final boolean hot = FlatUi.inside(mx, my, x, ROW2_Y, w, 16);
            FlatUi.outline(c, x, ROW2_Y, w, 16, ok ? COLOR_FOLLOW : FlatUi.BORDER);
            c.drawText(textRenderer, label, x + 4, ROW2_Y + 4, ok ? COLOR_FOLLOW : link.problem != null && followOn ? FlatUi.DANGER : FlatUi.TEXT_FAINT, false);
            hit(x, ROW2_Y, w, 16, HIT_FOLLOWER, i, 0);
            if (hot) {
                hoverText = "Parallel track " + fmt(Math.abs(link.lateral), 2) + " bl to the " + (link.lateral > 0 ? "right" : "left")
                    + (link.reversed ? " (runs the other way)" : "") + " · "
                    + (!followOn ? "not following (Follow is off)" : link.problem == null ? "⇄ follows" : "unlinked: " + problemText(link.problem));
            }
            x += w + 2;
        }
    }

    private static String problemText(String problem) {
        return problem.startsWith("~") ? problem.substring(1) : problem;
    }

    private void toolIcon(DrawContext c, Tool t, int x, int y, int ink) {
        switch (t) {
            case SELECT -> {
                TrackEditorGfx.quad(c, x + 1, y, x + 1, y + 8, x + 3.5f, y + 6, x + 7, y + 6, ink);
                TrackEditorGfx.line(c, x + 4, y + 6, x + 6, y + 9, 1.4, ink);
            }
            case BEZIER -> {
                TrackEditorGfx.line(c, x, y + 1, x + 8, y + 7, 1, ink);
                TrackEditorGfx.disc(c, x, y + 1, 1.4, ink);
                TrackEditorGfx.disc(c, x + 8, y + 7, 1.4, ink);
                TrackEditorGfx.diamond(c, x + 4, y + 4, 2.6, ink);
            }
            case TILT -> {
                TrackEditorGfx.line(c, x, y + 6, x + 8, y + 2, 2, ink);
                TrackEditorGfx.line(c, x, y + 8.5, x + 8, y + 8.5, 1, ink);
            }
            case HEIGHT -> {
                TrackEditorGfx.line(c, x, y + 7, x + 3, y + 7, 1.4, ink);
                TrackEditorGfx.line(c, x + 3, y + 7, x + 6, y + 2, 1.4, ink);
                TrackEditorGfx.line(c, x + 6, y + 2, x + 8.5, y + 2, 1.4, ink);
            }
        }
    }

    private static String toolHint(Tool t) {
        return switch (t) {
            case SELECT -> "Click a track or point to select it · drag points and handles · drag empty space to move the view";
            case BEZIER -> "Click the track to add a Bézier point · drag points/handles · right-click a point to delete";
            case TILT -> "Click the track to add a tilt point · drag it up/down to bank · right-click deletes · or press Auto tilt";
            case HEIGHT -> "Click the track to add a height point · drag it up/down · right-click deletes";
        };
    }

    private void setTool(Tool next) {
        tool = next;
        // keep only selections that make sense for the tool
        if (next == Tool.TILT && selKind != SelKind.TILT || next == Tool.HEIGHT && selKind != SelKind.GRADE
            || next == Tool.BEZIER && selKind != SelKind.ANCHOR) {
            select(SelKind.TRACK, -1);
        }
        if (next == Tool.TILT && lanes == 0 || next == Tool.HEIGHT && lanes == 1) {
            lanes = 2;
        }
    }

    // ----------------------------------------------------------------- main view

    private void drawMain(DrawContext c, int mx, int my, float delta) {
        FlatUi.outline(c, mainX - 1, mainY - 1, mainW + 2, mainH + 2, FlatUi.BORDER);
        final boolean mouseInMain = FlatUi.inside(mx, my, mainX, mainY, mainW, mainH) && !overCard(mx, my);
        final TrackViewport vp;
        try {
            vp = viewport();
        } catch (RuntimeException | LinkageError e) {
            FlatUi.rect(c, mainX, mainY, mainW, mainH, 0xFF17171B);
            c.drawText(textRenderer, "This view is not available", mainX + 6, mainY + 6, FlatUi.DANGER, false);
            return;
        }
        if (tab == ViewTab.SCHEMATIC) {
            schematic.setPane(mainX, mainY, mainW, mainH);
            if (!schematic.fitted()) {
                fitSchematic();
            }
            c.enableScissor(mainX, mainY, mainX + mainW, mainY + mainH);
            schematic.drawBackground(c);
            drawSchematicTracks(c);
            c.draw();
            c.disableScissor();
        } else {
            final Pane pane = activePane();
            if (sceneDirty) {
                updateScene(pane);
            }
            pane.marker(markerS());
            final boolean viewMouse = mouseInMain || drag == Drag.VIEW;
            pane.draw(c, mainX, mainY, mainW, mainH, viewMouse ? mx : -1, viewMouse ? my : -1, delta);
        }
        sceneDirty = false;

        // overlays (handles) — same code for every view
        c.enableScissor(mainX, mainY, mainX + mainW, mainY + mainH);
        projectDrafts(vp);
        overlay.clear();
        hoverHandle = -1;
        hoverOther = -1;
        hoverS = Double.NaN;
        buildOverlay(vp);
        if (mouseInMain && drag == Drag.NONE) {
            hoverHandle = nearestHandle(mx, my);
            if (hoverHandle < 0) {
                final double[] near = nearest(d(), mx, my);
                if (near[0] < TRACK_HIT) {
                    hoverS = near[1];
                } else {
                    hoverOther = otherTrackAt(mx, my);
                }
            }
        }
        drawOverlay(c, vp, mx, my);
        c.draw();
        c.disableScissor();
        if (tab == ViewTab.SCHEMATIC) {
            schematic.drawLabels(c, textRenderer);
        }
        if (!d().closes()) {
            final String miss = "✗ misses its far node by " + fmt(d().path.positionError(), 2) + " bl";
            final int w = textRenderer.getWidth(miss);
            FlatUi.rect(c, mainX + 3, mainY + mainH - 14, w + 6, 11, 0xC0101013);
            c.drawText(textRenderer, miss, mainX + 6, mainY + mainH - 12, FlatUi.DANGER, false);
        }
        if (mouseInMain && hoverText.isEmpty()) {
            if (hoverOther >= 0) {
                hoverText = "Click to edit " + trackName(hoverOther);
            } else if (hoverHandle >= 0) {
                hoverText = handleHint(overlay.get(hoverHandle));
            }
        }
    }

    private void fitSchematic() {
        double x0 = nodeX + 0.5, x1 = x0, z0 = nodeZ + 0.5, z1 = z0;
        for (final TrackEditorDraft draft : drafts) {
            for (int i = 0; i < draft.polyX.length; i++) {
                x0 = Math.min(x0, draft.polyX[i]);
                x1 = Math.max(x1, draft.polyX[i]);
                z0 = Math.min(z0, draft.polyZ[i]);
                z1 = Math.max(z1, draft.polyZ[i]);
            }
        }
        // leave room for the inspector card on the right
        final double extra = (x1 - x0) * cardW / Math.max(1.0, mainW - cardW);
        schematic.fit(x0, x1 + extra, z0, z1);
    }

    private void updateScene(Pane pane) {
        try {
            final List<TrackPath> context = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                if (i != sel) {
                    context.add(drafts.get(i).path);
                }
            }
            final List<TrackPath> followers = new ArrayList<>();
            for (final Link link : links()) {
                followers.add(link.draft.path);
            }
            pane.scene(context, followers, d().path, nodeX + 0.5, nodeY, nodeZ + 0.5);
        } catch (RuntimeException ignored) {
            // the views are another agent's; never let them take the editor down
        }
    }

    /** What the 3D / World views mark on the selected track. */
    private double markerS() {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        switch (selKind) {
            case ANCHOR -> {
                return cur.anchorS(selIndex);
            }
            case TILT -> {
                return selIndex == TILT_A ? 0 : selIndex == TILT_B ? cur.path.length() : p.tilts.get(selIndex).s;
            }
            case GRADE -> {
                return p.grades.get(selIndex).s;
            }
            case PIECE -> {
                return cur.path.pieceStart(selIndex) + cur.path.pieceLength(selIndex) / 2;
            }
            default -> {
                return hoverS;
            }
        }
    }

    private void drawSchematicTracks(DrawContext c) {
        final TrackEditorDraft cur = d();
        for (int i = 0; i < drafts.size(); i++) {
            if (i != sel) {
                drawPoly(c, drafts.get(i), i == hoverOther ? FlatUi.TEXT_DIM : COLOR_OTHER, i == hoverOther ? 2.2 : 1.5);
            }
        }
        for (final Link link : links()) {
            drawPoly(c, link.draft, followOn && link.problem == null ? COLOR_FOLLOW : COLOR_OTHER, 1.5);
        }
        if (cur.changed() || cur.resetRequested) {
            final double[] ghost = cur.ghost;
            final double[] a = new double[3];
            final double[] b = new double[3];
            for (int i = 0; i + 3 < ghost.length; i += 2) {
                schematic.project(ghost[i], 0, ghost[i + 1], a);
                schematic.project(ghost[i + 2], 0, ghost[i + 3], b);
                TrackEditorGfx.line(c, a[0], a[1], b[0], b[1], 1, COLOR_GHOST);
            }
        }
        if (showAdvanced && !cur.isSpline()) {
            drawPiecePoly(c, cur);
        } else {
            drawPoly(c, cur, cur.closes() ? FlatUi.ACCENT : FlatUi.DANGER, 2.4);
        }
        // far nodes + the clicked node
        final double[] p = new double[3];
        for (final TrackEditorDraft draft : drafts) {
            final long[] far = draft.farNode();
            schematic.project(far[0] + 0.5, 0, far[2] + 0.5, p);
            FlatUi.rect(c, (int) p[0] - 2, (int) p[1] - 2, 5, 5, FlatUi.BORDER_STRONG);
        }
        schematic.project(nodeX + 0.5, 0, nodeZ + 0.5, p);
        FlatUi.rect(c, (int) p[0] - 4, (int) p[1] - 4, 9, 9, FlatUi.TEXT);
        FlatUi.rect(c, (int) p[0] - 3, (int) p[1] - 3, 7, 7, FlatUi.GROUND);
        if (!cur.closes()) {
            final double[] e = new double[3];
            schematic.project(cur.path.endX(), 0, cur.path.endZ(), e);
            schematic.project(cur.nodes[3] + 0.5, 0, cur.nodes[5] + 0.5, p);
            TrackEditorGfx.dashed(c, e[0], e[1], p[0], p[1], 1.5, 3, FlatUi.DANGER);
        }
    }

    private void drawPoly(DrawContext c, TrackEditorDraft draft, int color, double width) {
        final double[] a = new double[3];
        final double[] b = new double[3];
        for (int i = 0; i + 1 < draft.polyX.length; i++) {
            schematic.project(draft.polyX[i], 0, draft.polyZ[i], a);
            schematic.project(draft.polyX[i + 1], 0, draft.polyZ[i + 1], b);
            TrackEditorGfx.line(c, a[0], a[1], b[0], b[1], width, color);
        }
    }

    private void drawPiecePoly(DrawContext c, TrackEditorDraft draft) {
        final double[] a = new double[3];
        final double[] b = new double[3];
        for (int i = 0; i + 1 < draft.polyX.length; i++) {
            final int piece = draft.path.pieceAt((draft.polyS[i] + draft.polyS[i + 1]) / 2);
            final PieceType type = piece < draft.profile.pieces.size() ? draft.profile.pieces.get(piece).type : PieceType.STRAIGHT;
            schematic.project(draft.polyX[i], 0, draft.polyZ[i], a);
            schematic.project(draft.polyX[i + 1], 0, draft.polyZ[i + 1], b);
            TrackEditorGfx.line(c, a[0], a[1], b[0], b[1], selKind == SelKind.PIECE && piece == selIndex ? 4 : 2.4, pieceColor(type));
        }
    }

    // ---- projection caches

    /** Projects every visible draft's polyline into {@link #projected}: {xs, ys, ok(0/1)}. */
    private void projectDrafts(TrackViewport vp) {
        projected.clear();
        final List<TrackEditorDraft> list = new ArrayList<>(drafts);
        final double[] out = new double[3];
        for (final TrackEditorDraft draft : list) {
            final int n = draft.polyX.length;
            final double[] xs = new double[n];
            final double[] ys = new double[n];
            final double[] ok = new double[n];
            for (int i = 0; i < n; i++) {
                if (vp.project(draft.polyX[i], draft.path.height(draft.polyS[i]), draft.polyZ[i], out)) {
                    xs[i] = out[0];
                    ys[i] = out[1];
                    ok[i] = 1;
                }
            }
            projected.put(draft, new double[][]{xs, ys, ok});
        }
    }

    /** {screen distance, s} of the closest point of a draft's projected polyline to the mouse. */
    private double[] nearest(TrackEditorDraft draft, double mx, double my) {
        double best = Double.MAX_VALUE;
        double bestS = 0;
        final double[][] pr = projected.get(draft);
        if (pr == null) {
            return new double[]{best, 0};
        }
        final double[] ss = draft.polyS;
        for (int i = 0; i + 1 < pr[0].length && i + 1 < ss.length; i++) {
            if (pr[2][i] == 0 || pr[2][i + 1] == 0) {
                continue;
            }
            final double x1 = pr[0][i], y1 = pr[1][i], x2 = pr[0][i + 1], y2 = pr[1][i + 1];
            final double dist = TrackEditorGfx.segmentDistance(mx, my, x1, y1, x2, y2);
            if (dist < best) {
                best = dist;
                final double dx = x2 - x1, dy = y2 - y1;
                final double l2 = dx * dx + dy * dy;
                final double t = l2 > 1e-9 ? Math.max(0, Math.min(1, ((mx - x1) * dx + (my - y1) * dy) / l2)) : 0;
                bestS = ss[i] + (ss[i + 1] - ss[i]) * t;
            }
        }
        return new double[]{best, bestS};
    }

    private int otherTrackAt(double mx, double my) {
        int best = -1;
        double bestDist = 6;
        for (int i = 0; i < drafts.size(); i++) {
            if (i != sel) {
                final double dist = nearest(drafts.get(i), mx, my)[0];
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
        }
        return best;
    }

    // ---- handles

    private static double[] point(TrackPath path, double s) {
        final double[] out = new double[5];
        path.pointAt(s, out);
        return out;
    }

    /** Builds the overlay handle list for the current tool: {kind, index, screenX, screenY, depth}. */
    private void buildOverlay(TrackViewport vp) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        final double[] out = new double[3];
        switch (tool) {
            case SELECT, BEZIER -> {
                if (!cur.isSpline()) {
                    return;
                }
                final int last = p.anchors.size() - 1;
                for (int i = 0; i <= last; i++) {
                    final Anchor a = p.anchors.get(i);
                    final double y = cur.path.height(cur.anchorS(i));
                    final double cx = Math.cos(a.heading);
                    final double cz = Math.sin(a.heading);
                    if (i > 0 && vp.project(a.x - cx * a.handleIn, y, a.z - cz * a.handleIn, out)) {
                        overlay.add(new double[]{K_IN, i, out[0], out[1], out[2]});
                    }
                    if (i < last && vp.project(a.x + cx * a.handleOut, y, a.z + cz * a.handleOut, out)) {
                        overlay.add(new double[]{K_OUT, i, out[0], out[1], out[2]});
                    }
                }
                for (int i = 0; i <= last; i++) {
                    final Anchor a = p.anchors.get(i);
                    if (vp.project(a.x, cur.path.height(cur.anchorS(i)), a.z, out)) {
                        overlay.add(new double[]{K_ANCHOR, i, out[0], out[1], out[2]});
                    }
                }
            }
            case TILT -> {
                addPointHandle(vp, cur, K_TILT, TILT_A, 0);
                addPointHandle(vp, cur, K_TILT, TILT_B, cur.path.length());
                for (int i = 0; i < p.tilts.size(); i++) {
                    addPointHandle(vp, cur, K_TILT, i, p.tilts.get(i).s);
                }
            }
            case HEIGHT -> {
                if (p.vertical == TrackProfile.VerticalMode.CUSTOM) {
                    for (int i = 0; i < p.grades.size(); i++) {
                        addPointHandle(vp, cur, K_GRADE, i, p.grades.get(i).s);
                    }
                }
            }
        }
    }

    private void addPointHandle(TrackViewport vp, TrackEditorDraft cur, int kind, int index, double s) {
        final double[] pt = point(cur.path, s);
        final double[] out = new double[3];
        if (vp.project(pt[0], pt[1], pt[2], out)) {
            overlay.add(new double[]{kind, index, out[0], out[1], out[2]});
        }
    }

    /** Index into {@link #overlay} of the handle under the mouse (nearest within the hit radius), or -1. */
    private int nearestHandle(double mx, double my) {
        int best = -1;
        double bestDist = HIT_R;
        for (int i = 0; i < overlay.size(); i++) {
            final double[] h = overlay.get(i);
            final double dist = Math.hypot(mx - h[2], my - h[3]) - (h[0] == K_ANCHOR ? 0.5 : 0);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private boolean isSelected(double[] h) {
        final int kind = (int) h[0];
        final int index = (int) h[1];
        return switch (kind) {
            case K_ANCHOR, K_IN, K_OUT -> selKind == SelKind.ANCHOR && selIndex == index;
            case K_TILT -> selKind == SelKind.TILT && selIndex == index;
            case K_GRADE -> selKind == SelKind.GRADE && selIndex == index;
            default -> false;
        };
    }

    private boolean isDragged(double[] h) {
        final int kind = (int) h[0];
        final int index = (int) h[1];
        return switch (drag) {
            case ANCHOR -> kind == K_ANCHOR && index == dragArg;
            case HANDLE -> (kind == (dragOut ? K_OUT : K_IN)) && index == dragArg;
            case TILT_VIEW, TILT_STRIP -> kind == K_TILT && index == dragArg;
            case GRADE_VIEW, GRADE_STRIP -> kind == K_GRADE && index == dragArg;
            default -> false;
        };
    }

    private void drawOverlay(DrawContext c, TrackViewport vp, int mx, int my) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        // handle lines first (anchor → knob)
        final Map<Integer, double[]> anchorScreen = new HashMap<>();
        for (final double[] h : overlay) {
            if (h[0] == K_ANCHOR) {
                anchorScreen.put((int) h[1], h);
            }
        }
        for (final double[] h : overlay) {
            if (h[0] == K_IN || h[0] == K_OUT) {
                final double[] a = anchorScreen.get((int) h[1]);
                if (a != null) {
                    final boolean on = isSelected(h);
                    TrackEditorGfx.line(c, a[2], a[3], h[2], h[3], on ? 1.4 : 1, on ? 0xCC3D8BFF : 0x66FFFFFF);
                }
            }
        }
        // knobs, then anchors / points on top (sorted far → near in 3D)
        final List<double[]> order = new ArrayList<>(overlay);
        if (!vp.isTopDown()) {
            order.sort((a, b) -> Double.compare(b[4], a[4]));
        }
        order.sort(Comparator.comparingInt(h -> h[0] == K_ANCHOR ? 1 : 0));
        final int last = p.anchors.size() - 1;
        for (final double[] h : order) {
            final int kind = (int) h[0];
            final int index = (int) h[1];
            final boolean hot = hoverHandle >= 0 && overlay.get(hoverHandle) == h || isDragged(h);
            final boolean on = isSelected(h);
            switch (kind) {
                case K_IN, K_OUT -> {
                    final double r = (on ? KNOB_R : KNOB_R - 1) + (hot ? 1.5 : 0);
                    TrackEditorGfx.handle(c, h[2], h[3], r, on || hot ? FlatUi.ACCENT : 0xFF8A8A96, FlatUi.TEXT);
                }
                case K_ANCHOR -> {
                    final boolean end = index == 0 || index == last;
                    final double r = ANCHOR_R + (hot ? 1.5 : 0);
                    if (end) {
                        // nodes are fixed: a square pin
                        final int s = (int) Math.round(r - 1);
                        FlatUi.rect(c, (int) h[2] - s - 1, (int) h[3] - s - 1, 2 * s + 3, 2 * s + 3, FlatUi.TEXT);
                        FlatUi.rect(c, (int) h[2] - s, (int) h[3] - s, 2 * s + 1, 2 * s + 1, on ? FlatUi.ACCENT : 0xFF2A2A31);
                    } else {
                        TrackEditorGfx.disc(c, h[2], h[3], r + 1.2, FlatUi.TEXT);
                        TrackEditorGfx.disc(c, h[2], h[3], r, on ? FlatUi.ACCENT : hot ? 0xFF5A5A66 : 0xFF2A2A31);
                        TrackEditorGfx.disc(c, h[2], h[3], 2, on ? FlatUi.TEXT : COLOR_ANCHOR);
                    }
                }
                case K_TILT, K_GRADE -> {
                    final int color = kind == K_TILT ? COLOR_TILT : COLOR_HEIGHT;
                    final boolean end = kind == K_TILT && index < 0;
                    final double r = 6 + (hot ? 1.5 : 0);
                    if (end) {
                        final int s = (int) Math.round(r - 1);
                        FlatUi.rect(c, (int) h[2] - s - 1, (int) h[3] - s - 1, 2 * s + 3, 2 * s + 3, FlatUi.TEXT);
                        FlatUi.rect(c, (int) h[2] - s, (int) h[3] - s, 2 * s + 1, 2 * s + 1, on ? FlatUi.ACCENT : color);
                    } else {
                        TrackEditorGfx.handle(c, h[2], h[3], r, on ? FlatUi.ACCENT : color, FlatUi.TEXT);
                    }
                }
                default -> {
                }
            }
        }
        // hover on the track: where a click would add something
        if (!Double.isNaN(hoverS)) {
            final double[] pt = point(cur.path, hoverS);
            final double[] out = new double[3];
            if (vp.project(pt[0], pt[1], pt[2], out)) {
                final boolean adds = tool != Tool.SELECT;
                final int color = switch (tool) {
                    case TILT -> COLOR_TILT;
                    case HEIGHT -> COLOR_HEIGHT;
                    default -> FlatUi.TEXT;
                };
                if (adds) {
                    TrackEditorGfx.disc(c, out[0], out[1], 5.5, 0x88000000);
                    TrackEditorGfx.line(c, out[0] - 3.5, out[1], out[0] + 3.5, out[1], 1.5, color);
                    TrackEditorGfx.line(c, out[0], out[1] - 3.5, out[0], out[1] + 3.5, 1.5, color);
                } else {
                    TrackEditorGfx.disc(c, out[0], out[1], 3, color);
                }
            }
        }
        c.draw();
        // value labels for the selected / hovered point
        for (final double[] h : overlay) {
            final int kind = (int) h[0];
            if (kind != K_TILT && kind != K_GRADE) {
                continue;
            }
            final boolean hot = hoverHandle >= 0 && overlay.get(hoverHandle) == h || isDragged(h);
            if (!hot && !isSelected(h)) {
                continue;
            }
            final int index = (int) h[1];
            final String text = kind == K_TILT
                ? signed(index == TILT_A ? p.tiltA : index == TILT_B ? p.tiltB : p.tilts.get(index).degrees, 1) + "°"
                : "y " + fmt(p.grades.get(index).y, 2);
            label(c, text, (int) h[2] + 9, (int) h[3] - 4, kind == K_TILT ? COLOR_TILT : COLOR_HEIGHT);
        }
        if (!Double.isNaN(hoverS) && tool != Tool.SELECT && tool != Tool.BEZIER) {
            final String text = tool == Tool.TILT ? signed(cur.path.tilt(hoverS), 1) + "°" : "y " + fmt(cur.path.height(hoverS), 2);
            final double[] pt = point(cur.path, hoverS);
            final double[] out = new double[3];
            if (vp.project(pt[0], pt[1], pt[2], out)) {
                label(c, text, (int) out[0] + 9, (int) out[1] - 4, FlatUi.TEXT_DIM);
            }
        }
    }

    private void label(DrawContext c, String text, int x, int y, int color) {
        final int w = textRenderer.getWidth(text);
        FlatUi.rect(c, x - 2, y - 2, w + 4, 11, 0xC0101013);
        c.drawText(textRenderer, text, x, y, color, false);
    }

    private String handleHint(double[] h) {
        final int kind = (int) h[0];
        final int index = (int) h[1];
        final int last = d().profile.anchors.size() - 1;
        return switch (kind) {
            case K_ANCHOR -> index == 0 || index == last ? "Node (fixed) — drag its handle to change how the curve leaves it"
                : "Bézier point — drag to move · right-click or Delete to remove";
            case K_IN, K_OUT -> "Handle — drag to bend the curve (longer = straighter for longer)";
            case K_TILT -> "Tilt point — drag up/down to bank · right-click deletes";
            case K_GRADE -> "Height point — drag up/down · right-click deletes";
            default -> "";
        };
    }

    // ----------------------------------------------------------------- inspector card

    private void drawCard(DrawContext c, int mx, int my) {
        final int maxH = mainH - 8;
        if (maxH < 24) {
            cardH = 0;
            return;
        }
        final String title = cardTitle();
        final int titleH = 14;
        final int bodyH = cardCollapsed ? 0 : Math.min(maxH - titleH, cardContent);
        cardH = titleH + bodyH + (cardCollapsed ? 0 : 2);
        FlatUi.rect(c, cardX, cardY, cardW, cardH, 0xF0222228);
        FlatUi.outline(c, cardX, cardY, cardW, cardH, FlatUi.BORDER_STRONG);
        hit(cardX, cardY, cardW, cardH, HIT_BLOCK, 0, 0);
        c.drawText(textRenderer, textRenderer.trimToWidth(title, cardW - 20), cardX + 5, cardY + 3, FlatUi.TEXT, false);
        FlatUi.iconButton(c, textRenderer, cardCollapsed ? "+" : "–", cardX + cardW - 13, cardY + 2, 11, mx, my, FlatUi.TEXT_DIM);
        hit(cardX + cardW - 13, cardY + 2, 11, 11, HIT_CARD_FOLD, 0, 0);
        if (cardCollapsed) {
            return;
        }
        FlatUi.rect(c, cardX + 1, cardY + titleH, cardW - 2, 1, FlatUi.BORDER);
        final int top = cardY + titleH + 1;
        cardScroll = Math.max(0, Math.min(Math.max(0, cardContent - bodyH), cardScroll));
        clipTop = top;
        clipBottom = top + bodyH;
        c.enableScissor(cardX + 1, top, cardX + cardW - 1, top + bodyH);
        final int x = cardX + 5;
        final int w = cardW - 10;
        int y = top + 3 - cardScroll;
        final int start = y;
        y = switch (selKind) {
            case ANCHOR -> cardAnchor(c, mx, my, x, y, w);
            case TILT -> cardTilt(c, mx, my, x, y, w);
            case GRADE -> cardGrade(c, mx, my, x, y, w);
            case PIECE, TRACK -> cardTrack(c, mx, my, x, y, w);
        };
        cardContent = y - start + 4;
        c.disableScissor();
        noClip();
        FlatUi.scrollThumb(c, cardX + cardW - 1, top, bodyH, cardContent, cardScroll);
    }

    private String cardTitle() {
        final TrackProfile p = d().profile;
        return switch (selKind) {
            case ANCHOR -> selIndex == 0 ? "Node A (fixed)" : selIndex == p.anchors.size() - 1 ? "Node B (fixed)"
                : "Bézier point " + selIndex + " of " + (p.anchors.size() - 2);
            case TILT -> selIndex == TILT_A ? "Tilt at node A" : selIndex == TILT_B ? "Tilt at node B" : "Tilt point";
            case GRADE -> "Height point";
            case PIECE -> "Piece " + (selIndex + 1) + " · " + trackName(sel);
            case TRACK -> trackName(sel);
        };
    }

    private int cardAnchor(DrawContext c, int mx, int my, int x, int y, int w) {
        final TrackProfile p = d().profile;
        final boolean end = selIndex == 0 || selIndex == p.anchors.size() - 1;
        y = field(c, mx, my, "X", F_AX, 2, 0.1, !end, x, y, w);
        y = field(c, mx, my, "Z", F_AZ, 2, 0.1, !end, x, y, w);
        y = field(c, mx, my, "Heading °", F_AHEAD, 1, 0.5, !end, x, y, w);
        if (selIndex > 0) {
            y = field(c, mx, my, "Handle in", F_AIN, 2, 0.1, true, x, y, w);
        }
        if (selIndex < p.anchors.size() - 1) {
            y = field(c, mx, my, "Handle out", F_AOUT, 2, 0.1, true, x, y, w);
        }
        if (!end) {
            FlatUi.button(c, textRenderer, "Delete point", x, y + 1, w, 14, mx, my, ButtonStyle.DANGER);
            hit(x, y + 1, w, 14, HIT_DELETE_SEL, 0, 0);
            y += 18;
        } else {
            y = note(c, "Nodes stay where MTR put them; drag the handle to shape how the curve leaves.", x, y + 1, w);
        }
        return y;
    }

    private int cardTilt(DrawContext c, int mx, int my, int x, int y, int w) {
        final boolean end = selIndex < 0;
        if (!end) {
            y = field(c, mx, my, "At distance", F_TILT_S, 2, 0.1, true, x, y, w);
        }
        y = field(c, mx, my, "Tilt °", F_TILT_DEG, 1, 0.1, true, x, y, w);
        y = note(c, "+ leans right (travelling A→B), pivot on the low rail.", x, y + 1, w);
        if (!end) {
            FlatUi.button(c, textRenderer, "Delete point", x, y, w, 14, mx, my, ButtonStyle.DANGER);
            hit(x, y, w, 14, HIT_DELETE_SEL, 0, 0);
            y += 18;
        }
        return y;
    }

    private int cardGrade(DrawContext c, int mx, int my, int x, int y, int w) {
        y = field(c, mx, my, "At distance", F_GRADE_S, 2, 0.1, true, x, y, w);
        y = field(c, mx, my, "Height y", F_GRADE_Y, 2, 0.05, true, x, y, w);
        y = field(c, mx, my, "Vertical curve", F_GRADE_CURVE, 1, 0.5, true, x, y, w);
        FlatUi.button(c, textRenderer, "Delete point", x, y + 1, w, 14, mx, my, ButtonStyle.DANGER);
        hit(x, y + 1, w, 14, HIT_DELETE_SEL, 0, 0);
        return y + 19;
    }

    private int cardTrack(DrawContext c, int mx, int my, int x, int y, int w) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        final int half = (w - 3) / 2;
        y = readout(c, "Length", fmt(cur.path.length(), 2) + " bl", x, y, w);
        y = readout(c, "Tightest radius", cur.minRadius > 0 && cur.minRadius < 1e5 ? fmt(cur.minRadius, 1) : "straight", x, y, w);

        // MTR's own rail settings: always shown (Thomas: easy to find and see)
        y += 1;
        FlatUi.rect(c, x - 2, y, w + 4, 1, FlatUi.BORDER);
        c.drawText(textRenderer, "MTR", x, y + 3, FlatUi.ACCENT, false);
        final boolean twoRadii = "TWO_RADII".equals(cur.mtrShape);
        final int shapeX = x + textRenderer.getWidth("MTR") + 4;
        final int shapeW = x + w - shapeX;
        FlatUi.segmented(c, textRenderer, new String[]{shapeW >= 110 ? "Quadratic" : "Quad", "Two radii"}, twoRadii ? 1 : 0, shapeX, y + 1, shapeW, 13, mx, my);
        for (int i = 0; i < 2; i++) {
            final int cx = shapeX + shapeW * i / 2;
            hit(cx, y + 1, shapeX + shapeW * (i + 1) / 2 - cx, 13, HIT_SEG, SEG_SHAPE, i);
        }
        if (FlatUi.inside(mx, my, shapeX, y + 1, shapeW, 13)) {
            hoverText = "MTR's rail shape — sets the height rule between the nodes (Height = MTR), saved with MTR's own update";
        }
        y += 16;
        y = field(c, mx, my, twoRadii ? "Vertical radius" : "Vert. radius (two radii)", F_VRADIUS, 2, 0, twoRadii, x, y, w);
        FlatUi.button(c, textRenderer, "Styles…", x, y, half, 14, mx, my, ButtonStyle.FLAT);
        hit(x, y, half, 14, HIT_STYLES, 0, 0);
        FlatUi.button(c, textRenderer, "Flip styles", x + half + 3, y, w - half - 3, 14, mx, my, ButtonStyle.FLAT);
        hit(x + half + 3, y, w - half - 3, 14, HIT_FLIP, 0, 0);
        y += 17;
        FlatUi.rect(c, x - 2, y, w + 4, 1, FlatUi.BORDER);
        y += 3;

        y = readout(c, "Speed limit", cur.speedKmh > 0 ? fmt(cur.speedKmh, 0) + " km/h" : "—", x, y, w);
        y = readout(c, "Max tilt · grade", fmt(cur.maxTilt, 1) + "° · " + fmt(cur.maxGradePercent, 1) + "%", x, y, w);
        y = readout(c, "Shape", cur.isSpline() ? "Bézier (" + (p.anchors.size() - 2) + " pts)" : cur.isMtrShape() ? "MTR" : "arcs & spirals", x, y, w);
        if (!cur.closes()) {
            y = note(c, "✗ " + cur.solveMessage, x, y, w);
        }
        y += 2;
        y = segment(c, mx, my, "Height", SEG_VMODE, new String[]{"MTR", "Custom"}, p.vertical == TrackProfile.VerticalMode.CUSTOM ? 1 : 0, x, y, w);
        y = segment(c, mx, my, "Tilt between points", SEG_SMOOTH, new String[]{"Linear", "Smooth"}, p.smoothTilt ? 1 : 0, x, y, w);
        FlatUi.button(c, textRenderer, "Clear tilt", x, y, half, 14, mx, my, ButtonStyle.FLAT, p.hasTilt() || !p.tilts.isEmpty());
        hit(x, y, half, 14, HIT_CLEAR_TILT, 0, 0);
        FlatUi.button(c, textRenderer, "Reset", x + half + 3, y, w - half - 3, 14, mx, my, ButtonStyle.DANGER, cur.mtrImport != null);
        hit(x + half + 3, y, w - half - 3, 14, HIT_RESET, 0, 0);
        if (FlatUi.inside(mx, my, x + half + 3, y, w - half - 3, 14)) {
            hoverText = "Reset to MTR's own shape (Save removes the custom shape)";
        }
        y += 18;

        final List<Link> list = links();
        if (!list.isEmpty()) {
            y = segment(c, mx, my, "Parallel ⇄", SEG_FOLLOW, new String[]{"Follow", "Off"}, followOn ? 0 : 1, x, y, w);
            for (final Link link : list) {
                final String side = fmt(Math.abs(link.lateral), 1) + " bl " + (link.lateral > 0 ? "right" : "left");
                final String state = !followOn ? "stays" : link.problem == null ? "⇄ follows" : "✗ " + problemText(link.problem);
                y = readout(c, side, state, x, y, w);
            }
            y += 2;
        }
        y = disclosure(c, mx, my, "At this node", DIS_NODE, showNode, x, y, w);
        if (showNode) {
            y = readout(c, "Axis", axisLabel(cur.headingAtNode()), x, y, w);
            for (int i = 0; i < drafts.size(); i++) {
                y = field(c, mx, my, "Tilt " + (i + 1) + " " + compass(drafts.get(i).outgoingHeading()), F_NODE_TILT + i, 1, 0.1, true, x, y, w);
            }
            FlatUi.button(c, textRenderer, "Match tilt to " + (sel + 1), x, y, w, 14, mx, my, ButtonStyle.FLAT, drafts.size() > 1);
            hit(x, y, w, 14, HIT_MATCH, 0, 0);
            y += 16;
            y = note(c, "Each track's tilt where it meets this node. Match makes the others lean the same way as track " + (sel + 1) + ".", x, y, w);
        }
        y = disclosure(c, mx, my, "Advanced: arcs & spirals", DIS_ADVANCED, showAdvanced, x, y, w);
        if (showAdvanced) {
            y = advanced(c, mx, my, x, y, w);
        }
        return y;
    }

    private int advanced(DrawContext c, int mx, int my, int x, int y, int w) {
        final TrackEditorDraft cur = d();
        if (cur.isSpline()) {
            return note(c, "This track is a Bézier curve. Reset it to MTR's shape to edit it as arcs, straights and spirals.", x, y, w);
        }
        final List<Piece> pieces = cur.profile.pieces;
        for (int i = 0; i < pieces.size(); i++) {
            final Piece piece = pieces.get(i);
            final boolean on = selKind == SelKind.PIECE && selIndex == i;
            final boolean hot = FlatUi.inside(mx, my, x, y, w, 11) && my >= clipTop && my < clipBottom;
            FlatUi.rect(c, x, y, w, 11, on ? FlatUi.SELECTED : hot ? FlatUi.HOVER : 0);
            FlatUi.rect(c, x + 1, y + 2, 2, 7, pieceColor(piece.type));
            final String text = switch (piece.type) {
                case ARC -> "Arc R " + fmt(piece.radius, 1) + " · " + fmt(piece.length, 1) + (piece.turn > 0 ? " R" : " L");
                case SPIRAL -> "Spiral · " + fmt(piece.length, 1);
                case STRAIGHT -> "Straight · " + fmt(piece.length, 1);
            };
            c.drawText(textRenderer, textRenderer.trimToWidth(text, w - 8), x + 6, y + 2, on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hit(x, y, w, 11, HIT_PIECE_ROW, i, 0);
            y += 12;
        }
        if (selKind == SelKind.PIECE && selIndex >= 0 && selIndex < pieces.size()) {
            final Piece piece = pieces.get(selIndex);
            y += 2;
            y = segment(c, mx, my, null, SEG_TYPE, new String[]{"Str", "Arc", "Spi"}, piece.type.ordinal(), x, y, w);
            if (piece.type == PieceType.ARC) {
                y = segment(c, mx, my, null, SEG_HAND, new String[]{"Left", "Right"}, piece.turn > 0 ? 1 : 0, x, y, w);
                y = lockedField(c, mx, my, "Radius", F_RADIUS, piece.lockRadius, x, y, w);
            }
            y = lockedField(c, mx, my, "Length", F_LENGTH, piece.lockLength, x, y, w);
            final int half = (w - 3) / 2;
            FlatUi.button(c, textRenderer, "Split", x, y, half, 14, mx, my, ButtonStyle.FLAT, pieces.size() < TrackProfile.MAX_PIECES);
            hit(x, y, half, 14, HIT_SPLIT, 0, 0);
            FlatUi.button(c, textRenderer, "Delete", x + half + 3, y, w - half - 3, 14, mx, my, ButtonStyle.DANGER, pieces.size() > 1);
            hit(x + half + 3, y, w - half - 3, 14, HIT_DELETE_SEL, 0, 0);
            y += 17;
        }
        final String[] add = {"+Str", "+Arc", "+Spi"};
        final int third = (w - 4) / 3;
        for (int i = 0; i < 3; i++) {
            final int bx = x + i * (third + 2);
            FlatUi.button(c, textRenderer, add[i], bx, y, i == 2 ? w - 2 * (third + 2) : third, 14, mx, my, ButtonStyle.FLAT, pieces.size() < TrackProfile.MAX_PIECES);
            hit(bx, y, i == 2 ? w - 2 * (third + 2) : third, 14, HIT_ADD_PIECE, i, 0);
        }
        y += 17;
        y = field(c, mx, my, "Easement length", F_EASE, 1, 0.5, true, x, y, w);
        final int half = (w - 3) / 2;
        FlatUi.button(c, textRenderer, "Ease +", x, y, half, 14, mx, my, ButtonStyle.FLAT);
        hit(x, y, half, 14, HIT_EASE_ADD, 0, 0);
        FlatUi.button(c, textRenderer, "Ease −", x + half + 3, y, w - half - 3, 14, mx, my, ButtonStyle.FLAT, hasSpiral());
        hit(x + half + 3, y, w - half - 3, 14, HIT_EASE_REMOVE, 0, 0);
        y += 17;
        return note(c, "Padlocked values never change when the solver makes the chain meet the far node.", x, y, w);
    }

    // ---- card controls

    private int readout(DrawContext c, String label, String value, int x, int y, int w) {
        c.drawText(textRenderer, textRenderer.trimToWidth(label, w / 2), x, y, FlatUi.TEXT_FAINT, false);
        final String v = textRenderer.trimToWidth(value, w - Math.min(w / 2, textRenderer.getWidth(label)) - 4);
        c.drawText(textRenderer, v, x + w - textRenderer.getWidth(v), y, FlatUi.TEXT_DIM, false);
        return y + 10;
    }

    private int note(DrawContext c, String text, int x, int y, int w) {
        for (final OrderedText line : textRenderer.wrapLines(Text.literal(text), w)) {
            c.drawText(textRenderer, line, x, y, FlatUi.TEXT_FAINT, false);
            y += 9;
        }
        return y + 3;
    }

    private int segment(DrawContext c, int mx, int my, String label, int id, String[] labels, int chosen, int x, int y, int w) {
        int sx = x;
        int sw = w;
        if (label != null) {
            final int lw = Math.min(w / 2, textRenderer.getWidth(label) + 4);
            c.drawText(textRenderer, textRenderer.trimToWidth(label, lw), x, y + 3, FlatUi.TEXT_DIM, false);
            sx = x + lw;
            sw = w - lw;
        }
        FlatUi.segmented(c, textRenderer, labels, chosen, sx, y, sw, 13, mx, my);
        for (int i = 0; i < labels.length; i++) {
            final int cx = sx + sw * i / labels.length;
            hit(cx, y, sx + sw * (i + 1) / labels.length - cx, 13, HIT_SEG, id, i);
        }
        return y + 16;
    }

    private int disclosure(DrawContext c, int mx, int my, String text, int id, boolean open, int x, int y, int w) {
        final boolean hot = FlatUi.inside(mx, my, x - 2, y, w + 4, 12) && my >= clipTop && my < clipBottom;
        FlatUi.rect(c, x - 2, y, w + 4, 12, hot ? FlatUi.HOVER : 0);
        FlatUi.rect(c, x - 2, y, w + 4, 1, FlatUi.BORDER);
        c.drawText(textRenderer, (open ? "▾ " : "▸ ") + text, x, y + 3, open ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
        hit(x - 2, y, w + 4, 12, HIT_DISCLOSE, id, 0);
        return y + 15;
    }

    private int lockedField(DrawContext c, int mx, int my, String label, int id, boolean locked, int x, int y, int w) {
        final int ny = field(c, mx, my, label, id, 2, id == F_RADIUS ? 0.5 : 0.1, true, x + 11, y, w - 11);
        TrackEditorGfx.padlock(c, x, y + 3, locked, locked ? FlatUi.ACCENT : FlatUi.TEXT_FAINT);
        hit(x - 1, y, 10, 14, HIT_LOCK, id, 0);
        return ny;
    }

    /**
     * One compact row: label + number box. After Effects style: dragging sideways on the NUMBER (or the
     * label) scrubs the value live ({@link #scrubSpec}: per-field step, Shift 10× coarser, Ctrl/Cmd 10×
     * finer) under a ↔ cursor; a click without moving (&lt; 3 px) focuses the box for typing. A scrub or a
     * typing session is one undo step. {@code step} is unused (kept for call-site readability).
     */
    private int field(DrawContext c, int mx, int my, String label, int id, int decimals, double step, boolean enabled,
                      int x, int y, int w) {
        final int boxW = Math.min(52, w / 2);
        final int lw = w - boxW - 2;
        final boolean scrubbing = drag == Drag.SCRUB && dragArg == id;
        final boolean visible = my >= clipTop && my < clipBottom;
        final boolean labelHot = enabled && FlatUi.inside(mx, my, x, y, lw, 14) && visible;
        final TextBox existing = numberBoxes.get(id);
        final boolean boxHot = enabled && FlatUi.inside(mx, my, x + w - boxW, y, boxW, 14) && visible
            && (existing == null || !existing.isFocused());
        final boolean hot = labelHot || boxHot;
        c.drawText(textRenderer, textRenderer.trimToWidth(label, lw - 2), x, y + 3,
            scrubbing || hot ? FlatUi.TEXT : enabled ? FlatUi.TEXT_DIM : FlatUi.TEXT_FAINT, false);
        if (hot) {
            wantResizeCursor = true;
            hoverText = "Drag sideways to change " + label.toLowerCase(Locale.ROOT) + " (Shift coarse, Ctrl fine) · click to type";
        }
        final double value = fieldValue(id);
        if (!enabled) {
            final String text = fmt(value, decimals);
            c.drawText(textRenderer, text, x + w - textRenderer.getWidth(text) - 3, y + 3, FlatUi.TEXT_FAINT, false);
            return y + 16;
        }
        scrubMeta.put(id, new double[]{decimals});
        hit(x, y, lw, 14, HIT_SCRUB, id, 0);
        TextBox box = numberBoxes.get(id);
        if (box == null) {
            box = new TextBox(textRenderer, 12, false);
            final int fieldId = id;
            box.onChange(v -> {
                try {
                    final double parsed = Double.parseDouble(v.trim().replace(',', '.'));
                    if (Double.isFinite(parsed)) {
                        fieldApply(fieldId, parsed);
                    }
                } catch (NumberFormatException ignored) {
                    // half-typed number
                }
            });
            numberBoxes.put(id, box);
        }
        if (!box.isFocused()) {
            box.load(fmt(value, decimals));
        }
        box.setBounds(x + w - boxW, y, boxW, 14);
        box.render(c, mx, my);
        if (y >= clipTop && y + 14 <= clipBottom) {
            visibleBoxes.add(box);
        }
        return y + 16;
    }

    /**
     * How a field scrubs: {amount per pixel, 1 = logarithmic (the amount is then a relative change
     * per pixel)}. Shift = 10× coarser, Ctrl/Cmd = 10× finer (applied by the caller).
     */
    private static double[] scrubSpec(int id) {
        if (id >= F_NODE_TILT) {
            return new double[]{0.1, 0};
        }
        return switch (id) {
            case F_AHEAD -> new double[]{0.5, 0};
            case F_TILT_DEG, F_CAP, F_GRADE_CURVE -> new double[]{0.1, 0};
            case F_SPEED -> new double[]{1, 0};
            case F_RADIUS, F_VRADIUS -> new double[]{0.006, 1};
            default -> new double[]{0.05, 0}; // positions, distances, lengths, heights
        };
    }

    private void startScrub(int id, double mx) {
        drag = Drag.SCRUB;
        dragArg = id;
        scrubValue = fieldValue(id);
        scrubLastX = mx;
        beginGesture();
    }

    private void scrubTo(double mx) {
        final double[] meta = scrubMeta.get(dragArg);
        final int decimals = meta == null ? 2 : (int) meta[0];
        final double[] spec = scrubSpec(dragArg);
        final double pixels = mx - scrubLastX;
        scrubLastX = mx;
        if (pixels == 0) {
            return;
        }
        final double mult = Screen.hasShiftDown() ? 10 : Screen.hasControlDown() ? 0.1 : 1;
        if (spec[1] == 1) {
            scrubValue = Math.max(0.5, scrubValue) * Math.exp(pixels * spec[0] * mult);
        } else {
            scrubValue += pixels * spec[0] * mult;
        }
        final double scale = Math.pow(10, decimals);
        final double value = Math.round(scrubValue * scale) / scale;
        fieldApply(dragArg, value);
        // clamped / wrapped by the field: continue from what it really is, so dragging back responds at once
        final double actual = fieldValue(dragArg);
        if (Math.abs(actual - value) > 0.5 / scale + 1e-9) {
            scrubValue = actual;
        }
    }

    private void updateCursor() {
        final boolean want = wantResizeCursor || drag == Drag.SCRUB;
        if (want == resizeCursorShown) {
            return;
        }
        try {
            if (want && resizeCursor == 0) {
                resizeCursor = org.lwjgl.glfw.GLFW.glfwCreateStandardCursor(org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR);
            }
            org.lwjgl.glfw.GLFW.glfwSetCursor(MinecraftClient.getInstance().getWindow().getHandle(), want ? resizeCursor : 0);
            resizeCursorShown = want;
        } catch (RuntimeException | LinkageError ignored) {
            // cursor shape is cosmetic
        }
    }

    private double fieldValue(int id) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        if (id >= F_NODE_TILT) {
            final int index = id - F_NODE_TILT;
            return index < drafts.size() ? drafts.get(index).tiltAtNode() : 0;
        }
        final Anchor anchor = selKind == SelKind.ANCHOR && selIndex >= 0 && selIndex < p.anchors.size() ? p.anchors.get(selIndex) : null;
        final boolean tiltOk = selKind == SelKind.TILT && (selIndex < 0 || selIndex < p.tilts.size());
        final GradePoint grade = selKind == SelKind.GRADE && selIndex >= 0 && selIndex < p.grades.size() ? p.grades.get(selIndex) : null;
        final Piece piece = selKind == SelKind.PIECE && selIndex >= 0 && selIndex < p.pieces.size() ? p.pieces.get(selIndex) : null;
        return switch (id) {
            case F_AX -> anchor != null ? anchor.x : 0;
            case F_AZ -> anchor != null ? anchor.z : 0;
            case F_AHEAD -> anchor != null ? Math.floorMod(Math.round(Math.toDegrees(anchor.heading) * 10), 3600) / 10.0 : 0;
            case F_AIN -> anchor != null ? anchor.handleIn : 0;
            case F_AOUT -> anchor != null ? anchor.handleOut : 0;
            case F_TILT_S -> tiltOk && selIndex >= 0 ? p.tilts.get(selIndex).s : 0;
            case F_TILT_DEG -> !tiltOk ? 0 : selIndex == TILT_A ? p.tiltA : selIndex == TILT_B ? p.tiltB : p.tilts.get(selIndex).degrees;
            case F_GRADE_S -> grade != null ? grade.s : 0;
            case F_GRADE_Y -> grade != null ? grade.y : 0;
            case F_GRADE_CURVE -> grade != null ? grade.curve : 0;
            case F_VRADIUS -> cur.verticalRadius;
            case F_SPEED -> autoSpeed();
            case F_CAP -> autoCap;
            case F_RADIUS -> piece != null ? piece.radius : 0;
            case F_LENGTH -> piece != null ? piece.length : 0;
            case F_EASE -> easeLength;
            default -> 0;
        };
    }

    /** Applies a field value (live, from typing or scrubbing). Callers have begun a gesture. */
    private void fieldApply(int id, double v) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        beginGesture();
        if (id >= F_NODE_TILT) {
            final int index = id - F_NODE_TILT;
            if (index < drafts.size()) {
                drafts.get(index).setTiltAtNode(v);
                changed();
            }
            return;
        }
        switch (id) {
            case F_AX, F_AZ, F_AHEAD, F_AIN, F_AOUT -> {
                if (selKind != SelKind.ANCHOR || selIndex < 0 || selIndex >= p.anchors.size()) {
                    return;
                }
                final Anchor a = p.anchors.get(selIndex);
                final double c = Math.cos(a.heading);
                final double s = Math.sin(a.heading);
                final TrackProfile next = switch (id) {
                    case F_AX -> TrackEdit.moveAnchor(p, selIndex, v, a.z);
                    case F_AZ -> TrackEdit.moveAnchor(p, selIndex, a.x, v);
                    case F_AHEAD -> {
                        final double h = Math.toRadians(v);
                        final double len = Math.max(0.05, a.handleOut);
                        yield TrackEdit.setHandle(p, selIndex, true, a.x + Math.cos(h) * len, a.z + Math.sin(h) * len);
                    }
                    case F_AIN -> {
                        final double len = Math.max(0.05, Math.min(TrackProfile.MAX_CHAIN_LENGTH, v));
                        yield TrackEdit.setHandle(p, selIndex, false, a.x - c * len, a.z - s * len);
                    }
                    default -> {
                        final double len = Math.max(0.05, Math.min(TrackProfile.MAX_CHAIN_LENGTH, v));
                        yield TrackEdit.setHandle(p, selIndex, true, a.x + c * len, a.z + s * len);
                    }
                };
                cur.setProfile(next, "");
            }
            case F_TILT_S -> {
                if (selKind == SelKind.TILT && selIndex >= 0 && selIndex < p.tilts.size()) {
                    final TiltPoint point = p.tilts.get(selIndex);
                    point.s = Math.max(0.02, Math.min(cur.path.length() - 0.02, v));
                    p.tilts.sort(Comparator.comparingDouble(t -> t.s));
                    selIndex = p.tilts.indexOf(point);
                }
                cur.rebuild();
            }
            case F_TILT_DEG -> {
                if (selKind == SelKind.TILT) {
                    setTilt(p, selIndex, TrackProfile.clampTilt(v));
                }
                cur.rebuild();
            }
            case F_GRADE_S, F_GRADE_Y, F_GRADE_CURVE -> {
                if (selKind == SelKind.GRADE && selIndex >= 0 && selIndex < p.grades.size()) {
                    final GradePoint point = p.grades.get(selIndex);
                    if (id == F_GRADE_S) {
                        point.s = Math.max(0.05, Math.min(cur.path.length() - 0.05, v));
                        p.grades.sort(Comparator.comparingDouble(g -> g.s));
                        selIndex = p.grades.indexOf(point);
                    } else if (id == F_GRADE_Y) {
                        point.y = Math.max(-2048, Math.min(4096, v));
                    } else {
                        point.curve = Math.max(0, Math.min(1024, v));
                    }
                }
                cur.rebuild();
            }
            case F_VRADIUS -> {
                cur.verticalRadius = Math.max(0, Math.min(maxVerticalRadius(), Math.round(v * 100) / 100.0));
                cur.rebuild();
            }
            case F_SPEED -> {
                autoSpeed = Math.max(5, Math.min(1000, v));
                return;
            }
            case F_CAP -> {
                autoCap = Math.max(0, Math.min(TrackProfile.MAX_TILT_DEGREES, v));
                return;
            }
            case F_EASE -> {
                easeLength = Math.max(0.5, Math.min(500, v));
                return;
            }
            case F_RADIUS, F_LENGTH -> {
                if (selKind != SelKind.PIECE || selIndex < 0 || selIndex >= p.pieces.size()) {
                    return;
                }
                if (editBase == null) {
                    editBase = p.copy();
                }
                final int piece = selIndex;
                final TrackProfile start = editBase.copy();
                final int param;
                if (id == F_RADIUS) {
                    start.pieces.get(piece).radius = Math.max(TrackProfile.MIN_RADIUS, Math.min(100000, v));
                    param = new TrackSolver.Param(piece, TrackSolver.Param.RADIUS).id();
                } else {
                    start.pieces.get(piece).length = Math.max(0, Math.min(TrackProfile.MAX_CHAIN_LENGTH, v));
                    param = new TrackSolver.Param(piece, TrackSolver.Param.LENGTH).id();
                }
                final TrackSolver.Result result = TrackSolver.solve(start, Set.of(param));
                cur.setProfile(result.profile(), result.message());
            }
            default -> {
                return;
            }
        }
        changed();
    }

    private static void setTilt(TrackProfile p, int index, double degrees) {
        if (index == TILT_A) {
            p.tiltA = degrees;
        } else if (index == TILT_B) {
            p.tiltB = degrees;
        } else if (index >= 0 && index < p.tilts.size()) {
            p.tilts.get(index).degrees = degrees;
        }
    }

    private static double tiltOf(TrackProfile p, int index) {
        return index == TILT_A ? p.tiltA : index == TILT_B ? p.tiltB : index >= 0 && index < p.tilts.size() ? p.tilts.get(index).degrees : 0;
    }

    private double maxVerticalRadius() {
        final double max = d().path.getMaxVerticalRadius();
        return Double.isFinite(max) ? Math.max(0, Math.min(2000, max)) : 2000;
    }

    private double autoSpeed() {
        if (Double.isFinite(autoSpeed) && autoSpeed > 0) {
            return autoSpeed;
        }
        return d().speedKmh > 0 ? d().speedKmh : 80;
    }

    // ----------------------------------------------------------------- profile strip

    private void drawStrip(DrawContext c, int mx, int my) {
        FlatUi.rect(c, stripX, stripY, stripW, stripH, 0xFF19191E);
        FlatUi.rect(c, stripX, stripY, stripW, 1, FlatUi.BORDER);
        final int headerY = stripY + 2;
        FlatUi.iconButton(c, textRenderer, stripCollapsed ? "▸" : "▾", stripX + 1, headerY, 10, mx, my, FlatUi.TEXT_DIM);
        hit(stripX + 1, headerY, 10, 10, HIT_STRIP_FOLD, 0, 0);
        final TrackEditorDraft cur = d();
        final TrackPath path = cur.path;
        final double length = Math.max(0.01, path.length());
        final int sw = 96;
        final int sx = stripX + stripW - sw - 2;
        String head = "Profile · " + fmt(length, 1) + " bl";
        if (stripCollapsed) {
            c.drawText(textRenderer, head + " (click ▸ for height + tilt)", stripX + 14, headerY + 1, FlatUi.TEXT_FAINT, false);
            return;
        }
        FlatUi.segmented(c, textRenderer, new String[]{"Height", "Tilt", "Both"}, lanes, sx, headerY, sw, 10, mx, my);
        for (int i = 0; i < 3; i++) {
            hit(sx + sw * i / 3, headerY, sw * (i + 1) / 3 - sw * i / 3, 10, HIT_LANES, i, 0);
        }
        laneX = stripX + 26;
        laneW = Math.max(10, stripW - 26 - 6);
        lanesTop = headerY + 13;
        lanesH = Math.max(10, stripY + stripH - lanesTop - 3);
        if (lanes == 2) {
            heightTop = lanesTop;
            heightH = (lanesH - 3) / 2;
            tiltTop = heightTop + heightH + 3;
            tiltH = lanesH - heightH - 3;
        } else {
            heightTop = tiltTop = lanesTop;
            heightH = tiltH = lanesH;
        }
        if (laneFreeze != null) {
            hMin = laneFreeze[0];
            hMax = laneFreeze[1];
            tRange = laneFreeze[2];
        } else {
            double lo = Math.min(cur.nodes[1], cur.nodes[4]);
            double hi = Math.max(cur.nodes[1], cur.nodes[4]);
            for (int i = 0; i <= 64; i++) {
                final double y = path.height(length * i / 64);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            for (final GradePoint point : cur.profile.grades) {
                lo = Math.min(lo, point.y);
                hi = Math.max(hi, point.y);
            }
            final double span = Math.max(2, hi - lo);
            hMin = lo - span * 0.2;
            hMax = hi + span * 0.2;
            tRange = Math.min(TrackProfile.MAX_TILT_DEGREES, Math.max(5, Math.ceil((cur.maxTilt + 1) / 5) * 5));
        }
        double stripHover = Double.NaN;
        if (FlatUi.inside(mx, my, laneX, lanesTop, laneW, lanesH) && drag == Drag.NONE) {
            stripHover = sFromX(mx, length);
        }
        final double markS = !Double.isNaN(stripHover) ? stripHover : !Double.isNaN(hoverS) ? hoverS : Double.NaN;
        if (lanes != 1) {
            drawHeightLane(c, mx, my, cur, length);
        }
        if (lanes != 0) {
            drawTiltLane(c, mx, my, cur, length);
        }
        c.enableScissor(laneX, lanesTop, laneX + laneW, lanesTop + lanesH);
        for (int i = 1; i < path.pieceCount(); i++) {
            FlatUi.rect(c, (int) Math.round(sx(path.pieceStart(i), length)), lanesTop, 1, lanesH, 0x1AFFFFFF);
        }
        if (!Double.isNaN(markS)) {
            FlatUi.rect(c, (int) Math.round(sx(markS, length)), lanesTop, 1, lanesH, 0x66FFFFFF);
        }
        c.disableScissor();
        final int room = sx - stripX - 18;
        if (!Double.isNaN(markS)) {
            final String readout = "s " + fmt(markS, 1) + " · y " + fmt(path.height(markS), 2) + " · tilt " + signed(path.tilt(markS), 1) + "°";
            if (textRenderer.getWidth(readout) <= room) {
                head = readout;
            }
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(head, room), stripX + 14, headerY + 1, FlatUi.TEXT_FAINT, false);
        if (FlatUi.inside(mx, my, laneX, lanesTop, laneW, lanesH) && hoverText.isEmpty()) {
            hoverText = "Click a lane to add a point · drag points · right-click deletes";
        }
    }

    private double sx(double s, double length) {
        return laneX + s / length * laneW;
    }

    private double sFromX(double x, double length) {
        return Math.max(0, Math.min(length, (x - laneX) / laneW * length));
    }

    private double hy(double y) {
        return heightTop + (hMax - y) / (hMax - hMin) * heightH;
    }

    private double yFromHy(double screenY) {
        return hMax - (screenY - heightTop) / heightH * (hMax - hMin);
    }

    private double ty(double degrees) {
        return tiltTop + (tRange - degrees) / (2 * tRange) * tiltH;
    }

    private double tFromTy(double screenY) {
        return tRange - (screenY - tiltTop) / tiltH * 2 * tRange;
    }

    private void drawHeightLane(DrawContext c, int mx, int my, TrackEditorDraft cur, double length) {
        FlatUi.rect(c, laneX, heightTop, laneW, heightH, 0xFF141418);
        c.drawText(textRenderer, "y", stripX + 4, heightTop + heightH / 2 - 4, COLOR_HEIGHT, false);
        if (heightH >= 18) {
            c.drawText(textRenderer, textRenderer.trimToWidth(fmt(hMax, 0), 22), stripX + 2, heightTop, FlatUi.TEXT_FAINT, false);
            c.drawText(textRenderer, textRenderer.trimToWidth(fmt(hMin, 0), 22), stripX + 2, heightTop + heightH - 8, FlatUi.TEXT_FAINT, false);
        }
        c.enableScissor(laneX, heightTop, laneX + laneW, heightTop + heightH);
        final TrackPath path = cur.path;
        final int n = Math.max(16, Math.min(400, laneW / 2));
        double px = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            final double s = length * i / n;
            final double x = sx(s, length);
            final double y = hy(path.height(s));
            if (i > 0) {
                TrackEditorGfx.line(c, px, py, x, y, 1.5, COLOR_HEIGHT);
            }
            px = x;
            py = y;
        }
        if (cur.profile.vertical == TrackProfile.VerticalMode.CUSTOM) {
            final List<GradePoint> grades = cur.profile.grades;
            for (int i = 0; i < grades.size(); i++) {
                final GradePoint point = grades.get(i);
                final double x = sx(point.s, length);
                final double y = hy(point.y);
                if (point.curve > 0) {
                    final double half = point.curve / 2 / length * laneW;
                    TrackEditorGfx.line(c, x - half, y + 5, x + half, y + 5, 1, 0x887FD0FF);
                }
                final boolean on = selKind == SelKind.GRADE && i == selIndex;
                final boolean hot = Math.hypot(mx - x, my - y) < 6;
                TrackEditorGfx.handle(c, x, y, on || hot ? 3.5 : 2.5, on ? FlatUi.ACCENT : COLOR_HEIGHT, FlatUi.TEXT);
            }
        }
        c.draw();
        c.disableScissor();
        if (cur.profile.vertical != TrackProfile.VerticalMode.CUSTOM && FlatUi.inside(mx, my, laneX, heightTop, laneW, heightH)) {
            hoverText = "Height follows MTR — click to switch it to Custom and add a height point";
        }
    }

    private void drawTiltLane(DrawContext c, int mx, int my, TrackEditorDraft cur, double length) {
        FlatUi.rect(c, laneX, tiltTop, laneW, tiltH, 0xFF141418);
        c.drawText(textRenderer, "°", stripX + 5, tiltTop + tiltH / 2 - 4, COLOR_TILT, false);
        if (tiltH >= 18) {
            c.drawText(textRenderer, "+" + (int) tRange, stripX + 2, tiltTop, FlatUi.TEXT_FAINT, false);
            c.drawText(textRenderer, "-" + (int) tRange, stripX + 2, tiltTop + tiltH - 8, FlatUi.TEXT_FAINT, false);
        }
        c.enableScissor(laneX, tiltTop, laneX + laneW, tiltTop + tiltH);
        FlatUi.rect(c, laneX, (int) Math.round(ty(0)), laneW, 1, 0x30FFFFFF);
        final TrackPath path = cur.path;
        final int n = Math.max(16, Math.min(400, laneW / 2));
        double px = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            final double s = length * i / n;
            final double x = sx(s, length);
            final double y = ty(path.tilt(s));
            if (i > 0) {
                TrackEditorGfx.line(c, px, py, x, y, 1.5, COLOR_TILT);
            }
            px = x;
            py = y;
        }
        final List<TiltPoint> tilts = cur.profile.tilts;
        for (int i = 0; i < tilts.size(); i++) {
            final TiltPoint point = tilts.get(i);
            final double x = sx(point.s, length);
            final double y = ty(point.degrees);
            final boolean on = selKind == SelKind.TILT && i == selIndex;
            final boolean hot = Math.hypot(mx - x, my - y) < 6;
            TrackEditorGfx.handle(c, x, y, on || hot ? 3.5 : 2.5, on ? FlatUi.ACCENT : COLOR_TILT, FlatUi.TEXT);
        }
        endHandle(c, sx(0, length) + 2, ty(cur.profile.tiltA), selKind == SelKind.TILT && selIndex == TILT_A);
        endHandle(c, sx(length, length) - 2, ty(cur.profile.tiltB), selKind == SelKind.TILT && selIndex == TILT_B);
        c.draw();
        c.disableScissor();
    }

    private void endHandle(DrawContext c, double x, double y, boolean on) {
        FlatUi.rect(c, (int) x - 3, (int) y - 3, 7, 7, FlatUi.TEXT);
        FlatUi.rect(c, (int) x - 2, (int) y - 2, 5, 5, on ? FlatUi.ACCENT : COLOR_TILT);
    }

    // ----------------------------------------------------------------- status line

    private void drawStatus(DrawContext c) {
        final int y = height - STATUS_H + 2;
        final TrackEditorDraft cur = d();
        String right = (cur.closes() ? "✓ " : "✗ ") + fmt(cur.path.length(), 1) + " bl · R "
            + (cur.minRadius > 0 && cur.minRadius < 1e5 ? fmt(cur.minRadius, 0) : "—") + " · " + fmt(cur.maxTilt, 1) + "°";
        final int following = followingCount();
        if (following > 0) {
            right = "⇄" + following + " · " + right;
        }
        final int rw = textRenderer.getWidth(right);
        c.drawText(textRenderer, right, width - MARGIN - rw, y, cur.closes() ? FlatUi.TEXT_DIM : FlatUi.DANGER, false);
        String left;
        int color = FlatUi.TEXT_FAINT;
        if (!toast.isEmpty() && System.currentTimeMillis() < toastUntil) {
            left = toast;
            color = FlatUi.TEXT;
        } else if (!hoverText.isEmpty()) {
            left = hoverText;
            color = FlatUi.TEXT_DIM;
        } else {
            left = toolHint(tool);
            if (tool == Tool.BEZIER && !cur.isSpline()) {
                left = "Click the track to add a Bézier point (the track becomes a smooth curve, same shape) · drag it";
            } else if (tool == Tool.HEIGHT && cur.profile.vertical != TrackProfile.VerticalMode.CUSTOM) {
                left = "Height follows MTR — click the track to add a height point (switches to Custom, same line)";
            }
        }
        c.drawText(textRenderer, textRenderer.trimToWidth(left, width - 2 * MARGIN - rw - 8), MARGIN, y, color, false);
    }

    private void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 3500;
    }

    // ----------------------------------------------------------------- popovers / modals

    private int autoMenuX() {
        int x = MARGIN;
        final boolean compact = width < 470;
        final String[] names = {"Select", "Bézier", "Tilt", "Height"};
        for (final String name : names) {
            x += textRenderer.getWidth(name) + (compact ? 16 : 22) + 2;
        }
        return x + 4;
    }

    private void drawAutoMenu(DrawContext c, int mx, int my) {
        final int w = 150;
        final int h = 72;
        final int x = Math.min(autoMenuX(), width - w - 2);
        final int y = ROW2_Y + 18;
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        hit(x, y, w, h, HIT_BLOCK, 0, 0);
        c.drawText(textRenderer, "Auto tilt", x + 6, y + 4, FlatUi.TEXT, false);
        int fy = y + 16;
        fy = field(c, mx, my, "Speed km/h", F_SPEED, 0, 5, true, x + 6, fy, w - 12);
        fy = field(c, mx, my, "Max tilt °", F_CAP, 1, 0.5, true, x + 6, fy, w - 12);
        FlatUi.button(c, textRenderer, "Apply", x + 6, fy + 1, w - 12, 14, mx, my, ButtonStyle.PRIMARY);
        hit(x + 6, fy + 1, w - 12, 14, HIT_AUTO_APPLY, 0, 0);
    }

    private void drawStylesModal(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        final int w = Math.min(260, width - 20);
        final int h = 84;
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        c.drawText(textRenderer, "Open MTR's style screen?", x + 8, y + 7, FlatUi.TEXT, false);
        int ty = y + 20;
        for (final OrderedText line : textRenderer.wrapLines(Text.literal("It replaces this editor. Your unsaved track edits would be lost."), w - 16)) {
            c.drawText(textRenderer, line, x + 8, ty, FlatUi.TEXT_DIM, false);
            ty += 10;
        }
        final int by = y + h - 22;
        final int bw = (w - 24) / 3;
        FlatUi.button(c, textRenderer, "Save & open", x + 8, by, bw, 16, mx, my, ButtonStyle.PRIMARY, canSave());
        hit(x + 8, by, bw, 16, HIT_MODAL_SAVE, 0, 0);
        FlatUi.button(c, textRenderer, "Discard & open", x + 12 + bw, by, bw, 16, mx, my, ButtonStyle.DANGER);
        hit(x + 12 + bw, by, bw, 16, HIT_MODAL_DISCARD, 0, 0);
        FlatUi.button(c, textRenderer, "Back", x + 16 + bw * 2, by, w - 24 - bw * 2, 16, mx, my, ButtonStyle.GHOST);
        hit(x + 16 + bw * 2, by, w - 24 - bw * 2, 16, HIT_MODAL_BACK, 0, 0);
    }

    private void drawDiscardModal(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0x99000000);
        final int n = dirtyCount();
        final int w = Math.min(230, width - 20);
        final int h = 62;
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        FlatUi.rect(c, x, y, w, h, FlatUi.PANE_RAISED);
        FlatUi.outline(c, x, y, w, h, FlatUi.BORDER_STRONG);
        c.drawText(textRenderer, "Discard changes to " + n + (n == 1 ? " track?" : " tracks?"), x + 8, y + 8, FlatUi.TEXT, false);
        c.drawText(textRenderer, textRenderer.trimToWidth("Unsaved edits are lost; the rails stay as they were.", w - 16), x + 8, y + 20, FlatUi.TEXT_DIM, false);
        final int by = y + h - 22;
        final int bw = (w - 20) / 2;
        FlatUi.button(c, textRenderer, "Keep editing", x + 8, by, bw, 16, mx, my, ButtonStyle.FLAT);
        hit(x + 8, by, bw, 16, HIT_KEEP, 0, 0);
        FlatUi.button(c, textRenderer, "Discard", x + 12 + bw, by, w - 20 - bw, 16, mx, my, ButtonStyle.DANGER);
        hit(x + 12 + bw, by, w - 20 - bw, 16, HIT_DISCARD, 0, 0);
    }

    private int dirtyCount() {
        int count = 0;
        for (final TrackEditorDraft draft : allDrafts()) {
            if (draft.dirty()) {
                count++;
            }
        }
        return count;
    }

    /** Esc / Cancel: close at once when nothing changed, else ask first. */
    private void requestCancel() {
        if (dirtyCount() == 0) {
            close();
        } else {
            discardModal = true;
        }
    }

    // ================================================================= preview

    private void changed() {
        sceneDirty = true;
        previewDirty = true;
        updateFollowers();
    }

    private void pushPreviews() {
        if (!previewDirty) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastPreview < 200) {
            return;
        }
        lastPreview = now;
        previewDirty = false;
        for (final TrackEditorDraft draft : allDrafts()) {
            try {
                if (draft.changed() && draft.closes()) {
                    final TrackProfile sane = draft.profile.sanitized();
                    if (sane != null) {
                        TrackRegistry.setPreview(sane);
                    }
                } else if (draft.resetRequested && draft.isMtrShape() && draft.hadCustom) {
                    // show MTR's own shape: a preview of the import itself
                    TrackRegistry.setPreview(draft.profile.copy());
                } else {
                    TrackRegistry.clearPreview(draft.key);
                }
            } catch (RuntimeException ignored) {
                // preview is best effort
            }
        }
    }

    @Override
    public void removed() {
        wantResizeCursor = false;
        drag = Drag.NONE;
        updateCursor();
        if (paneWorld != null) {
            paneWorld.close();
        }
        // saved tracks keep their preview until the server's sync lands (no flash back)
        for (final TrackEditorDraft draft : allDrafts()) {
            if (!ClientTrackNetworking.isPreviewHeld(draft.key)) {
                TrackRegistry.clearPreview(draft.key);
            }
        }
        super.removed();
    }

    // ================================================================= actions

    private boolean canSave() {
        boolean any = false;
        for (final TrackEditorDraft draft : allDrafts()) {
            if (draft.blocksSave()) {
                return false;
            }
            any |= draft.saveAction() != 0 || draft.mtrChanged();
        }
        return any;
    }

    private boolean save() {
        endGesture();
        if (!canSave()) {
            boolean blocked = false;
            for (final TrackEditorDraft draft : allDrafts()) {
                blocked |= draft.blocksSave();
            }
            showToast(blocked ? "Can't save: a changed track does not reach its far node (✗)" : "Nothing to save");
            return false;
        }
        for (final TrackEditorDraft draft : allDrafts()) {
            if (draft.saveAction() == 1 && !ClientTrackNetworking.canSave(draft.profile)) {
                showToast("Can't save: track too complex to send, or the server lacks this mod");
                return false;
            }
        }
        try {
            for (final TrackEditorDraft draft : allDrafts()) {
                if (draft.mtrChanged()) {
                    final Rail fresh = draft.freshRail();
                    if (fresh != null) {
                        // exactly what MTR's RailModifierScreen sends for shape / vertical radius
                        InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance())
                            .addRail(Rail.copy(fresh, Rail.Shape.valueOf(draft.mtrShape), Math.round(draft.verticalRadius * 100) / 100.0))));
                    }
                }
                switch (draft.saveAction()) {
                    case 1 -> {
                        if (ClientTrackNetworking.sendSave(draft.profile.copy())) {
                            ClientTrackNetworking.holdPreviewUntilSync(draft.key);
                        }
                    }
                    case 2 -> {
                        ClientTrackNetworking.sendReset(draft.key);
                        ClientTrackNetworking.holdPreviewUntilSync(draft.key);
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            showToast("Could not save: " + e.getMessage());
            return false;
        }
        close();
        return true;
    }

    private void openStyles() {
        final Rail fresh = d().freshRail();
        if (fresh == null) {
            showToast("This track no longer exists");
            return;
        }
        // exactly how MTR's RailModifierScreen opens it
        org.mtr.mapping.holder.MinecraftClient.getInstance().openScreen(
            new org.mtr.mapping.holder.Screen(RailStyleSelectorScreen.create(fresh)));
    }

    /** MTR's RailModifierScreen "Flip styles", verbatim (sent at once, like MTR does). */
    private void flipStyles() {
        final Rail fresh = d().freshRail();
        if (fresh == null) {
            showToast("This track no longer exists");
            return;
        }
        final ObjectArrayList<String> styles = fresh.getStyles().stream().map(style -> {
            final boolean isForwards = style.endsWith("_1");
            final boolean isBackwards = style.endsWith("_2");
            return !isForwards && !isBackwards ? style : style.substring(0, style.length() - 1) + (isForwards ? "2" : "1");
        }).collect(Collectors.toCollection(ObjectArrayList::new));
        InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance())
            .addRail(Rail.copy(fresh, styles))));
        final org.mtr.mapping.holder.ClientPlayerEntity player = org.mtr.mapping.holder.MinecraftClient.getInstance().getPlayerMapped();
        if (player != null) {
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateLastRailStyles(player.getUuid(), fresh.getTransportMode(), styles));
        }
        showToast("Styles flipped");
    }

    /**
     * Same world bank for every track at the node. Each rail stores tilt in its OWN A→B frame
     * (+ = right low); every track at an MTR node runs along the node's axis, so another track's
     * heading there is the same (cos = +1) or opposite (cos = −1), and the end tilt giving the same
     * world bank is T′ = T · sign(cos(h′ − h)).
     */
    private void matchTiltAcrossNode() {
        edit();
        final TrackEditorDraft ref = d();
        final double tilt = ref.tiltAtNode();
        final double heading = ref.headingAtNode();
        for (final TrackEditorDraft draft : drafts) {
            if (draft != ref) {
                final double cos = Math.cos(draft.headingAtNode() - heading);
                if (Math.abs(cos) > 0.5) {
                    draft.setTiltAtNode(tilt * Math.signum(cos));
                }
            }
        }
        changed();
        showToast("Tilt at the node now matches " + trackName(sel));
    }

    private void autoTilt() {
        final TrackEditorDraft cur = d();
        final double speed = autoSpeed();
        try {
            edit();
            final TrackProfile base = cur.profile.copy();
            base.cantCap = autoCap;
            cur.setProfile(TrackEdit.autoTilt(base, cur.path, speed), cur.solveMessage);
            select(SelKind.TRACK, -1);
            changed();
            final int following = followingCount();
            showToast("Auto tilt for " + fmt(speed, 0) + " km/h · max " + fmt(cur.maxTilt, 1) + "° (cap " + fmt(autoCap, 0) + "°)"
                + (following > 0 ? " · " + following + " parallel track" + (following == 1 ? "" : "s") + " too" : ""));
        } catch (RuntimeException e) {
            showToast("Auto tilt failed: " + e.getMessage());
        }
    }

    private void resetToMtr() {
        final TrackEditorDraft cur = d();
        if (cur.mtrImport == null) {
            return;
        }
        edit();
        cur.setProfile(cur.mtrImport.copy(), "");
        cur.resetRequested = true;
        select(SelKind.TRACK, -1);
        changed();
        showToast(cur.hadCustom ? "Back to MTR's shape — Save removes the custom shape" : "Back to MTR's shape");
    }

    private void deleteSelected() {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        switch (selKind) {
            case ANCHOR -> {
                if (selIndex > 0 && selIndex < p.anchors.size() - 1) {
                    edit();
                    cur.setProfile(TrackEdit.removeAnchor(p, cur.path, selIndex), "");
                    select(SelKind.TRACK, -1);
                    changed();
                }
            }
            case TILT -> {
                if (selIndex >= 0 && selIndex < p.tilts.size()) {
                    edit();
                    p.tilts.remove(selIndex);
                    select(SelKind.TRACK, -1);
                    cur.rebuild();
                    changed();
                }
            }
            case GRADE -> {
                if (selIndex >= 0 && selIndex < p.grades.size()) {
                    edit();
                    p.grades.remove(selIndex);
                    select(SelKind.TRACK, -1);
                    cur.rebuild();
                    changed();
                }
            }
            case PIECE -> {
                if (p.pieces.size() > 1 && selIndex >= 0 && selIndex < p.pieces.size()) {
                    edit();
                    final TrackProfile next = p.copy();
                    next.pieces.remove(selIndex);
                    selIndex = Math.min(selIndex, next.pieces.size() - 1);
                    applyStructure(next);
                }
            }
            default -> {
            }
        }
    }

    private void applyStructure(TrackProfile next) {
        final TrackSolver.Result result = TrackSolver.solve(next);
        d().setProfile(result.profile(), result.message());
        changed();
    }

    private void addPiece(int kind) {
        final TrackProfile next = d().profile.copy();
        if (next.pieces.size() >= TrackProfile.MAX_PIECES) {
            return;
        }
        edit();
        final int at = selKind == SelKind.PIECE && selIndex >= 0 ? selIndex + 1 : next.pieces.size();
        final Piece piece = switch (kind) {
            case 1 -> {
                double radius = 0;
                for (final Piece p : next.pieces) {
                    if (p.type == PieceType.ARC) {
                        radius = Math.max(radius, p.radius);
                    }
                }
                final int hand = TrackPath.wrap(next.headingB - next.headingA) < 0 ? -1 : 1;
                yield Piece.arc(radius > 0 ? radius : Math.max(20, next.totalLength()), 4, hand);
            }
            case 2 -> Piece.spiral(4);
            default -> Piece.straight(4);
        };
        next.pieces.add(at, piece);
        select(SelKind.PIECE, at);
        applyStructure(next);
    }

    private boolean hasSpiral() {
        for (final Piece piece : d().profile.pieces) {
            if (piece.type == PieceType.SPIRAL) {
                return true;
            }
        }
        return false;
    }

    private int insertGrade(TrackEditorDraft cur, double s) {
        final GradePoint point = new GradePoint(s, Math.round(cur.path.height(s) * 100) / 100.0, 0);
        cur.profile.grades.add(point);
        cur.profile.grades.sort(Comparator.comparingDouble(g -> g.s));
        return cur.profile.grades.indexOf(point);
    }

    private int insertTilt(TrackEditorDraft cur, double s) {
        final TiltPoint point = new TiltPoint(s, Math.round(cur.path.tilt(s) * 10) / 10.0);
        cur.profile.tilts.add(point);
        cur.profile.tilts.sort(Comparator.comparingDouble(t -> t.s));
        return cur.profile.tilts.indexOf(point);
    }

    /**
     * Switches a draft from MTR's height rule to CUSTOM without moving the height line: grade points
     * (straight grades, no vertical curves) are added greedily at the worst-fitting sample until the
     * custom line is within 0.05 blocks of MTR's everywhere, keeping one slot free for the point the
     * user is about to add. A level rail needs none.
     */
    private void convertToCustomHeight(TrackEditorDraft cur) {
        final TrackPath mtr = cur.path;
        final double length = mtr.length();
        final int samples = Math.max(32, Math.min(512, (int) Math.ceil(length / 0.25)));
        final double[] target = new double[samples + 1];
        for (int i = 0; i <= samples; i++) {
            target[i] = mtr.height(length * i / samples);
        }
        final TrackProfile next = cur.profile.copy();
        next.vertical = TrackProfile.VerticalMode.CUSTOM;
        next.grades.clear();
        while (next.grades.size() < TrackProfile.MAX_GRADE_POINTS - 1) {
            final TrackPath trial = TrackPath.build(next, cur.mtrShape, cur.verticalRadius);
            double worst = 0.05;
            int worstIndex = -1;
            for (int i = 1; i < samples; i++) {
                final double s = length * i / samples;
                final double error = Math.abs(trial.height(s) - target[i]);
                if (error > worst && s > 0.15 && s < length - 0.15 && !nearGrade(next, s)) {
                    worst = error;
                    worstIndex = i;
                }
            }
            if (worstIndex < 0) {
                break;
            }
            next.grades.add(new GradePoint(length * worstIndex / samples, target[worstIndex], 0));
            next.grades.sort(Comparator.comparingDouble(g -> g.s));
        }
        cur.profile.vertical = TrackProfile.VerticalMode.CUSTOM;
        cur.profile.grades = next.grades;
        cur.rebuild();
    }

    private static boolean nearGrade(TrackProfile profile, double s) {
        for (final GradePoint point : profile.grades) {
            if (Math.abs(point.s - s) < 0.15) {
                return true;
            }
        }
        return false;
    }

    private void select(SelKind kind, int index) {
        if (kind != selKind || index != selIndex) {
            if (focused != null) {
                focus(null);
            }
            cardScroll = kind == selKind ? cardScroll : 0;
        }
        selKind = kind;
        selIndex = index;
    }

    private void selectRail(int index) {
        if (index == sel) {
            select(SelKind.TRACK, -1);
            return;
        }
        endGesture();
        sel = Math.max(0, Math.min(drafts.size() - 1, index));
        select(SelKind.TRACK, -1);
        sceneDirty = true;
        cardScroll = 0;
    }

    // ================================================================= input

    private void focus(TextBox box) {
        if (focused != null && focused != box) {
            focused.setFocused(false);
            endGesture();
        }
        focused = box;
        if (box != null) {
            box.setFocused(true);
            beginGesture();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (stylesModal || discardModal) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                final int[] r = hits.get(i);
                final boolean modalHit = discardModal ? r[4] == HIT_DISCARD || r[4] == HIT_KEEP : r[4] >= HIT_MODAL_SAVE && r[4] <= HIT_MODAL_BACK;
                if (modalHit && FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    click(r[4], r[5], r[6], r[0], r[2], mx);
                    return true;
                }
            }
            return true;
        }
        for (final TextBox box : visibleBoxes) {
            if (box.contains(mx, my)) {
                if (box.isFocused() || button != 0) {
                    focus(box);
                    box.mouseClicked(mx, my, button);
                    return true;
                }
                // After Effects style: drag sideways scrubs, a click without moving types
                focus(null);
                final Integer id = fieldOf(box);
                if (id == null) {
                    focus(box);
                    box.mouseClicked(mx, my, button);
                    return true;
                }
                drag = Drag.SCRUB_PENDING;
                dragArg = id;
                dragStartX = mx;
                dragStartY = my;
                scrubBox = box;
                return true;
            }
        }
        focus(null);
        if (autoMenu) {
            final int x = Math.min(autoMenuX(), width - 150 - 2);
            if (!FlatUi.inside(mx, my, x, ROW2_Y + 18, 150, 72)) {
                autoMenu = false; // any click outside closes it (including the ▾ that opened it)
                return true;
            }
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                final int[] r = hits.get(i);
                if (FlatUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    click(r[4], r[5], r[6], r[0], r[2], mx);
                    return true;
                }
            }
        } else if (overCard(mx, my) || my < MAIN_Y) {
            return true;
        }
        if (FlatUi.inside(mx, my, mainX, mainY, mainW, mainH)) {
            mainClick(mx, my, button);
            return true;
        }
        if (!stripCollapsed && FlatUi.inside(mx, my, laneX - 4, lanesTop - 2, laneW + 8, lanesH + 4)) {
            stripClick(mx, my, button);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private Integer fieldOf(TextBox box) {
        for (final Map.Entry<Integer, TextBox> entry : numberBoxes.entrySet()) {
            if (entry.getValue() == box) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void click(int id, int arg, int arg2, int x, int w, double mx) {
        final TrackEditorDraft cur = d();
        switch (id) {
            case HIT_TAB -> setTab(ViewTab.values()[arg]);
            case HIT_FIT -> {
                if (tab == ViewTab.SCHEMATIC) {
                    fitSchematic();
                } else if (activePane() != null) {
                    activePane().reset();
                }
            }
            case HIT_TOOL -> setTool(Tool.values()[arg]);
            case HIT_AUTO -> autoTilt();
            case HIT_AUTO_MENU -> autoMenu = !autoMenu;
            case HIT_AUTO_APPLY -> {
                autoMenu = false;
                autoTilt();
            }
            case HIT_UNDO -> undo();
            case HIT_REDO -> redo();
            case HIT_SAVE -> save();
            case HIT_CANCEL -> requestCancel();
            case HIT_CHIP -> selectRail(arg);
            case HIT_FOLLOW -> setFollow(!followOn);
            case HIT_FOLLOWER -> {
                final List<Link> list = links();
                if (arg < list.size()) {
                    final Link link = list.get(arg);
                    showToast(link.problem == null || !followOn ? "⇄ " + fmt(Math.abs(link.lateral), 2) + " bl " + (link.lateral > 0 ? "right" : "left")
                        + (followOn ? " — follows this track (toggle Follow to stop)" : " — Follow is off")
                        : "Not following: " + problemText(link.problem));
                }
            }
            case HIT_CARD_FOLD -> cardCollapsed = !cardCollapsed;
            case HIT_SCRUB -> startScrub(arg, mx);
            case HIT_MTR -> {
                select(SelKind.TRACK, -1);
                cardCollapsed = false;
                cardScroll = 0;
                showToast("MTR settings are at the top of the track card");
            }
            case HIT_DELETE_SEL -> deleteSelected();
            case HIT_SEG -> segmentClick(arg, arg2);
            case HIT_RESET -> resetToMtr();
            case HIT_DISCLOSE -> {
                switch (arg) {
                    case DIS_NODE -> showNode = !showNode;
                    default -> {
                        showAdvanced = !showAdvanced;
                        if (!showAdvanced && selKind == SelKind.PIECE) {
                            select(SelKind.TRACK, -1);
                        }
                    }
                }
            }
            case HIT_STYLES -> {
                if (dirtyCount() > 0) {
                    stylesModal = true;
                } else {
                    openStyles();
                }
            }
            case HIT_FLIP -> flipStyles();
            case HIT_MATCH -> {
                if (drafts.size() > 1) {
                    matchTiltAcrossNode();
                }
            }
            case HIT_CLEAR_TILT -> {
                edit();
                cur.profile.tiltA = 0;
                cur.profile.tiltB = 0;
                cur.profile.tilts.clear();
                if (selKind == SelKind.TILT) {
                    select(SelKind.TRACK, -1);
                }
                cur.rebuild();
                changed();
            }
            case HIT_PIECE_ROW -> select(SelKind.PIECE, arg);
            case HIT_ADD_PIECE -> addPiece(arg);
            case HIT_SPLIT -> {
                if (selKind == SelKind.PIECE && cur.profile.pieces.size() < TrackProfile.MAX_PIECES) {
                    edit();
                    applyStructure(TrackEdit.split(cur.profile, selIndex));
                }
            }
            case HIT_EASE_ADD -> {
                final TrackProfile next = TrackEdit.addEasements(cur.profile, easeLength);
                if (next.pieces.size() > TrackProfile.MAX_PIECES) {
                    showToast("Too many pieces for easements");
                } else if (next.pieces.size() == cur.profile.pieces.size()) {
                    showToast("No curvature jumps to ease");
                } else {
                    edit();
                    select(SelKind.TRACK, -1);
                    applyStructure(next);
                }
            }
            case HIT_EASE_REMOVE -> {
                if (hasSpiral()) {
                    edit();
                    select(SelKind.TRACK, -1);
                    applyStructure(TrackEdit.removeEasements(cur.profile));
                }
            }
            case HIT_LOCK -> {
                if (selKind == SelKind.PIECE && selIndex >= 0 && selIndex < cur.profile.pieces.size()) {
                    edit();
                    final Piece piece = cur.profile.pieces.get(selIndex);
                    if (arg == F_RADIUS) {
                        piece.lockRadius = !piece.lockRadius;
                    } else {
                        piece.lockLength = !piece.lockLength;
                    }
                    cur.rebuild();
                    changed();
                }
            }
            case HIT_LANES -> lanes = arg;
            case HIT_STRIP_FOLD -> stripCollapsed = !stripCollapsed;
            case HIT_DISCARD -> {
                discardModal = false;
                close();
            }
            case HIT_KEEP -> discardModal = false;
            case HIT_MODAL_SAVE -> {
                stylesModal = false;
                if (save()) {
                    openStyles();
                }
            }
            case HIT_MODAL_DISCARD -> {
                stylesModal = false;
                openStyles();
            }
            case HIT_MODAL_BACK -> stylesModal = false;
            default -> {
            }
        }
    }

    private void segmentClick(int id, int index) {
        final TrackEditorDraft cur = d();
        switch (id) {
            case SEG_VMODE -> {
                final boolean custom = index == 1;
                if (custom == (cur.profile.vertical == TrackProfile.VerticalMode.CUSTOM)) {
                    return;
                }
                edit();
                if (custom) {
                    convertToCustomHeight(cur); // keeps today's height line
                } else {
                    cur.profile.vertical = TrackProfile.VerticalMode.MTR;
                    cur.rebuild();
                }
                if (selKind == SelKind.GRADE) {
                    select(SelKind.TRACK, -1);
                }
                changed();
            }
            case SEG_FOLLOW -> setFollow(index == 0);
            case SEG_SMOOTH -> {
                if (cur.profile.smoothTilt != (index == 1)) {
                    edit();
                    cur.profile.smoothTilt = index == 1;
                    cur.rebuild();
                    changed();
                }
            }
            case SEG_SHAPE -> {
                final String shape = index == 1 ? "TWO_RADII" : "QUADRATIC";
                if (!shape.equals(cur.mtrShape)) {
                    edit();
                    cur.mtrShape = shape;
                    cur.verticalRadius = Math.min(cur.verticalRadius, maxVerticalRadius());
                    cur.rebuild();
                    changed();
                }
            }
            case SEG_TYPE, SEG_HAND -> {
                if (selKind != SelKind.PIECE || selIndex < 0 || selIndex >= cur.profile.pieces.size()) {
                    return;
                }
                final TrackProfile next = cur.profile.copy();
                final Piece piece = next.pieces.get(selIndex);
                if (id == SEG_TYPE) {
                    final PieceType type = PieceType.values()[index];
                    if (piece.type == type) {
                        return;
                    }
                    piece.type = type;
                    if (type == PieceType.ARC && piece.radius < TrackProfile.MIN_RADIUS) {
                        piece.radius = 20;
                    }
                } else {
                    final int turn = index == 1 ? 1 : -1;
                    if (piece.turn == turn) {
                        return;
                    }
                    piece.turn = turn;
                }
                edit();
                applyStructure(next);
            }
            default -> {
            }
        }
    }

    // ---- main view input

    private void mainClick(double mx, double my, int button) {
        final TrackEditorDraft cur = d();
        final TrackViewport vp = viewport();
        final int handle = nearestHandle(mx, my);
        if (button == 1) {
            if (handle >= 0) {
                final double[] h = overlay.get(handle);
                final int kind = (int) h[0];
                final int index = (int) h[1];
                if (kind == K_ANCHOR || kind == K_TILT || kind == K_GRADE) {
                    select(kind == K_ANCHOR ? SelKind.ANCHOR : kind == K_TILT ? SelKind.TILT : SelKind.GRADE, index);
                    deleteSelected();
                    return;
                }
            }
            forwardToView(mx, my, button);
            return;
        }
        if (button != 0) {
            forwardToView(mx, my, button);
            return;
        }
        if (handle >= 0) {
            final double[] h = overlay.get(handle);
            final int kind = (int) h[0];
            final int index = (int) h[1];
            switch (kind) {
                case K_ANCHOR -> {
                    select(SelKind.ANCHOR, index);
                    if (index > 0 && index < cur.profile.anchors.size() - 1) {
                        startAnchorDrag(vp, index, mx, my);
                    }
                }
                case K_IN, K_OUT -> {
                    select(SelKind.ANCHOR, index);
                    startHandleDrag(vp, index, kind == K_OUT, mx, my);
                }
                case K_TILT -> {
                    select(SelKind.TILT, index);
                    startValueDrag(Drag.TILT_VIEW, index, my, tiltOf(cur.profile, index));
                }
                case K_GRADE -> {
                    select(SelKind.GRADE, index);
                    startValueDrag(Drag.GRADE_VIEW, index, my, cur.profile.grades.get(index).y);
                }
                default -> {
                }
            }
            return;
        }
        final double[] near = nearest(cur, mx, my);
        if (near[0] < TRACK_HIT) {
            final double s = near[1];
            final double length = cur.path.length();
            switch (tool) {
                case SELECT -> {
                    if (showAdvanced && !cur.isSpline()) {
                        select(SelKind.PIECE, cur.path.pieceAt(s));
                    } else {
                        select(SelKind.TRACK, -1);
                    }
                }
                case BEZIER -> addBezierPoint(vp, s, mx, my);
                case TILT -> {
                    if (s > 0.05 && s < length - 0.05 && cur.profile.tilts.size() < TrackProfile.MAX_TILT_POINTS) {
                        beginGesture();
                        final int index = insertTilt(cur, s);
                        cur.rebuild();
                        changed();
                        select(SelKind.TILT, index);
                        startValueDrag(Drag.TILT_VIEW, index, my, cur.profile.tilts.get(index).degrees);
                    } else if (cur.profile.tilts.size() >= TrackProfile.MAX_TILT_POINTS) {
                        showToast("Too many tilt points (" + TrackProfile.MAX_TILT_POINTS + ")");
                    }
                }
                case HEIGHT -> {
                    if (s > 0.1 && s < length - 0.1) {
                        beginGesture();
                        if (cur.profile.vertical != TrackProfile.VerticalMode.CUSTOM) {
                            convertToCustomHeight(cur);
                            showToast("Height switched to Custom (same line)");
                        }
                        if (cur.profile.grades.size() >= TrackProfile.MAX_GRADE_POINTS) {
                            showToast("Too many height points (" + TrackProfile.MAX_GRADE_POINTS + ")");
                            changed();
                            return;
                        }
                        final int index = insertGrade(cur, s);
                        cur.rebuild();
                        changed();
                        select(SelKind.GRADE, index);
                        startValueDrag(Drag.GRADE_VIEW, index, my, cur.profile.grades.get(index).y);
                    }
                }
            }
            return;
        }
        final int other = otherTrackAt(mx, my);
        if (other >= 0) {
            selectRail(other);
            return;
        }
        // empty space: drop the point selection, the view moves
        if (selKind != SelKind.TRACK && selKind != SelKind.PIECE) {
            select(SelKind.TRACK, -1);
        }
        forwardToView(mx, my, button);
    }

    private void forwardToView(double mx, double my, int button) {
        final boolean taken = tab == ViewTab.SCHEMATIC ? schematic.mouseClicked(mx, my, button) : activePane().press(mx, my, button);
        if (taken) {
            drag = Drag.VIEW;
            dragButton = button;
        }
    }

    private void addBezierPoint(TrackViewport vp, double s, double mx, double my) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        if (cur.isSpline() && p.anchors.size() >= TrackProfile.MAX_ANCHORS) {
            showToast("Too many Bézier points (" + TrackProfile.MAX_ANCHORS + ")");
            return;
        }
        try {
            beginGesture();
            double at = s;
            if (!cur.isSpline()) {
                final double fraction = s / Math.max(1e-6, cur.path.length());
                cur.setProfile(TrackEdit.toSpline(p, cur.path), "");
                at = fraction * cur.path.length();
                showToast("Bézier editing on — the shape is unchanged");
            }
            final double length = cur.path.length();
            if (at < 0.05 || at > length - 0.05) {
                changed();
                return;
            }
            cur.setProfile(TrackEdit.insertAnchor(cur.profile, cur.path, at), "");
            final int index = TrackEdit.lastInsertedAnchor();
            changed();
            if (index > 0 && index < cur.profile.anchors.size() - 1) {
                select(SelKind.ANCHOR, index);
                startAnchorDrag(vp, index, mx, my);
            }
        } catch (RuntimeException e) {
            showToast("Could not add a point: " + e.getMessage());
        }
    }

    private void startAnchorDrag(TrackViewport vp, int index, double mx, double my) {
        final TrackEditorDraft cur = d();
        final Anchor a = cur.profile.anchors.get(index);
        dragPlaneY = cur.path.height(cur.anchorS(index));
        final double[] g = new double[2];
        if (vp.groundAt(mx, my, dragPlaneY, g)) {
            grabDX = a.x - g[0];
            grabDZ = a.z - g[1];
        } else {
            grabDX = grabDZ = 0;
        }
        beginGesture();
        drag = Drag.ANCHOR;
        dragArg = index;
    }

    private void startHandleDrag(TrackViewport vp, int index, boolean out, double mx, double my) {
        final TrackEditorDraft cur = d();
        final Anchor a = cur.profile.anchors.get(index);
        dragPlaneY = cur.path.height(cur.anchorS(index));
        final double len = out ? a.handleOut : -a.handleIn;
        final double kx = a.x + Math.cos(a.heading) * len;
        final double kz = a.z + Math.sin(a.heading) * len;
        final double[] g = new double[2];
        if (vp.groundAt(mx, my, dragPlaneY, g)) {
            grabDX = kx - g[0];
            grabDZ = kz - g[1];
        } else {
            grabDX = grabDZ = 0;
        }
        beginGesture();
        drag = Drag.HANDLE;
        dragArg = index;
        dragOut = out;
    }

    private void startValueDrag(Drag kind, int index, double my, double value) {
        beginGesture();
        drag = kind;
        dragArg = index;
        dragStartY = my;
        dragStartValue = value;
        laneFreeze = new double[]{hMin, hMax, tRange};
    }

    private void viewDrag(double mx, double my) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        switch (drag) {
            case ANCHOR, HANDLE -> {
                final double[] g = new double[2];
                if (dragArg < 0 || dragArg >= p.anchors.size() || !viewport().groundAt(mx, my, dragPlaneY, g)) {
                    return;
                }
                final double x = g[0] + grabDX;
                final double z = g[1] + grabDZ;
                try {
                    cur.setProfile(drag == Drag.ANCHOR ? TrackEdit.moveAnchor(p, dragArg, x, z) : TrackEdit.setHandle(p, dragArg, dragOut, x, z), "");
                    changed();
                } catch (RuntimeException e) {
                    showToast(e.getMessage() == null ? "Can't move it there" : e.getMessage());
                }
            }
            case TILT_VIEW -> {
                final double degrees = TrackProfile.clampTilt(Math.round((dragStartValue + (dragStartY - my) * 0.2) * 10) / 10.0);
                setTilt(p, dragArg, degrees);
                cur.rebuild();
                changed();
            }
            case GRADE_VIEW -> {
                if (dragArg >= 0 && dragArg < p.grades.size()) {
                    p.grades.get(dragArg).y = Math.round((dragStartValue + (dragStartY - my) * 0.05) * 20) / 20.0;
                    cur.rebuild();
                    changed();
                }
            }
            default -> {
            }
        }
    }

    // ---- strip input

    private void stripClick(double mx, double my, int button) {
        final TrackEditorDraft cur = d();
        final double length = Math.max(0.01, cur.path.length());
        final boolean inHeight = lanes != 1 && FlatUi.inside(mx, my, laneX - 4, heightTop - 4, laneW + 8, heightH + 8);
        final boolean inTilt = lanes != 0 && FlatUi.inside(mx, my, laneX - 4, tiltTop - 4, laneW + 8, tiltH + 8);
        if (inTilt && (!inHeight || lanes == 1 || my >= tiltTop - 1)) {
            if (Math.hypot(mx - (sx(0, length) + 2), my - ty(cur.profile.tiltA)) < 7) {
                select(SelKind.TILT, TILT_A);
                startStripDrag(Drag.TILT_STRIP, TILT_A);
                return;
            }
            if (Math.hypot(mx - (sx(length, length) - 2), my - ty(cur.profile.tiltB)) < 7) {
                select(SelKind.TILT, TILT_B);
                startStripDrag(Drag.TILT_STRIP, TILT_B);
                return;
            }
            final List<TiltPoint> tilts = cur.profile.tilts;
            for (int i = 0; i < tilts.size(); i++) {
                if (Math.hypot(mx - sx(tilts.get(i).s, length), my - ty(tilts.get(i).degrees)) < 7) {
                    select(SelKind.TILT, i);
                    if (button == 1) {
                        deleteSelected();
                    } else {
                        startStripDrag(Drag.TILT_STRIP, i);
                    }
                    return;
                }
            }
            if (button == 0 && tilts.size() < TrackProfile.MAX_TILT_POINTS) {
                final double s = sFromX(mx, length);
                if (s > 0.05 && s < length - 0.05) {
                    beginGesture();
                    final int index = insertTilt(cur, s);
                    cur.rebuild();
                    changed();
                    select(SelKind.TILT, index);
                    startStripDrag(Drag.TILT_STRIP, index);
                }
            }
            return;
        }
        if (inHeight) {
            final List<GradePoint> grades = cur.profile.grades;
            if (cur.profile.vertical == TrackProfile.VerticalMode.CUSTOM) {
                for (int i = 0; i < grades.size(); i++) {
                    if (Math.hypot(mx - sx(grades.get(i).s, length), my - hy(grades.get(i).y)) < 7) {
                        select(SelKind.GRADE, i);
                        if (button == 1) {
                            deleteSelected();
                        } else {
                            startStripDrag(Drag.GRADE_STRIP, i);
                        }
                        return;
                    }
                }
            }
            if (button != 0) {
                return;
            }
            final double s = sFromX(mx, length);
            if (s <= 0.1 || s >= length - 0.1) {
                return;
            }
            beginGesture();
            if (cur.profile.vertical != TrackProfile.VerticalMode.CUSTOM) {
                convertToCustomHeight(cur);
                showToast("Height switched to Custom (same line)");
            }
            if (cur.profile.grades.size() < TrackProfile.MAX_GRADE_POINTS) {
                final int index = insertGrade(cur, s);
                cur.rebuild();
                select(SelKind.GRADE, index);
                startStripDrag(Drag.GRADE_STRIP, index);
            }
            changed();
        }
    }

    private void startStripDrag(Drag kind, int index) {
        beginGesture();
        drag = kind;
        dragArg = index;
        laneFreeze = new double[]{hMin, hMax, tRange};
    }

    private void stripDrag(double mx, double my) {
        final TrackEditorDraft cur = d();
        final TrackProfile p = cur.profile;
        final double length = Math.max(0.01, cur.path.length());
        if (drag == Drag.TILT_STRIP) {
            final double degrees = TrackProfile.clampTilt(Math.round(tFromTy(my) * 10) / 10.0);
            if (dragArg >= 0 && dragArg < p.tilts.size()) {
                final double lo = dragArg > 0 ? p.tilts.get(dragArg - 1).s + 0.05 : 0.05;
                final double hi = dragArg < p.tilts.size() - 1 ? p.tilts.get(dragArg + 1).s - 0.05 : length - 0.05;
                p.tilts.get(dragArg).s = Math.max(lo, Math.min(hi, Math.round(sFromX(mx, length) * 10) / 10.0));
            }
            setTilt(p, dragArg, degrees);
        } else if (drag == Drag.GRADE_STRIP && dragArg >= 0 && dragArg < p.grades.size()) {
            final double lo = dragArg > 0 ? p.grades.get(dragArg - 1).s + 0.1 : 0.1;
            final double hi = dragArg < p.grades.size() - 1 ? p.grades.get(dragArg + 1).s - 0.1 : length - 0.1;
            final GradePoint point = p.grades.get(dragArg);
            point.s = Math.max(lo, Math.min(hi, Math.round(sFromX(mx, length) * 10) / 10.0));
            point.y = Math.round(yFromHy(my) * 20) / 20.0;
        }
        cur.rebuild();
        changed();
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        switch (drag) {
            case VIEW -> {
                if (tab == ViewTab.SCHEMATIC) {
                    schematic.mouseDragged(mx, my, button);
                } else {
                    activePane().drag(mx, my, button, dx, dy);
                }
            }
            case ANCHOR, HANDLE, TILT_VIEW, GRADE_VIEW -> viewDrag(mx, my);
            case TILT_STRIP, GRADE_STRIP -> stripDrag(mx, my);
            case SCRUB -> scrubTo(mx);
            case SCRUB_PENDING -> {
                if (Math.abs(mx - dragStartX) >= 3) {
                    scrubBox = null;
                    startScrub(dragArg, dragStartX);
                    scrubTo(mx);
                }
            }
            default -> {
                if (focused != null) {
                    focused.mouseDragged(mx, my);
                    return true;
                }
                return super.mouseDragged(mx, my, button, dx, dy);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (drag == Drag.SCRUB_PENDING) {
            // a plain click on a number: type into it
            drag = Drag.NONE;
            final TextBox box = scrubBox;
            scrubBox = null;
            if (box != null) {
                focus(box);
                box.mouseClicked(dragStartX, dragStartY, 0);
                box.mouseReleased();
            }
            return true;
        }
        if (drag == Drag.VIEW) {
            if (tab == ViewTab.SCHEMATIC) {
                schematic.mouseReleased(mx, my, button);
            } else {
                activePane().release(mx, my, button);
            }
        } else if (focused == null) {
            // every click-started edit (insert + drag, scrub, …) is one undo step
            endGesture();
        }
        drag = Drag.NONE;
        laneFreeze = null;
        if (focused != null) {
            focused.mouseReleased();
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (stylesModal || discardModal) {
            return true;
        }
        if (overCard(mx, my) && !cardCollapsed) {
            cardScroll = Math.max(0, cardScroll - (int) (verticalAmount * 12));
            return true;
        }
        if (FlatUi.inside(mx, my, mainX, mainY, mainW, mainH)) {
            if (tab == ViewTab.SCHEMATIC) {
                schematic.mouseScrolled(mx, my, verticalAmount);
            } else {
                activePane().scroll(mx, my, verticalAmount);
            }
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Esc
            if (discardModal) {
                discardModal = false; // Esc in the modal = keep editing
                return true;
            }
            if (stylesModal || autoMenu) {
                stylesModal = false;
                autoMenu = false;
                return true;
            }
            if (focused != null) {
                focus(null);
                return true;
            }
            requestCancel();
            return true;
        }
        if (discardModal || stylesModal) {
            return true;
        }
        final boolean ctrl = Screen.hasControlDown();
        if (ctrl && keyCode == 83) { // Ctrl+S
            save();
            return true;
        }
        if (focused != null && focused.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (ctrl && keyCode == 90) { // Ctrl+Z / Ctrl+Shift+Z
            if (Screen.hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (ctrl && keyCode == 89) { // Ctrl+Y
            redo();
            return true;
        }
        if (focused == null && !ctrl) {
            if (keyCode >= 49 && keyCode <= 52) { // 1-4
                setTool(Tool.values()[keyCode - 49]);
                return true;
            }
            if (keyCode == 261 || keyCode == 259) { // Delete / Backspace
                deleteSelected();
                return true;
            }
            if (keyCode == 70) { // F = fit
                click(HIT_FIT, 0, 0, 0, 0, 0);
                return true;
            }
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

    // ================================================================= helpers

    private String trackName(int index) {
        final TrackEditorDraft draft = drafts.get(index);
        return "Track " + (index + 1) + " → " + compass(draft.outgoingHeading());
    }

    private static int pieceColor(PieceType type) {
        return switch (type) {
            case STRAIGHT -> COLOR_STRAIGHT;
            case ARC -> COLOR_ARC;
            case SPIRAL -> COLOR_SPIRAL;
        };
    }

    private static final String[] COMPASS = {"E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N", "NNE", "NE", "ENE"};

    /** Compass name of a heading θ = atan2(dz, dx) (0 = east, 90° = south — Minecraft's z points south). */
    static String compass(double heading) {
        return COMPASS[Math.floorMod((int) Math.round(Math.toDegrees(heading) / 22.5), 16)];
    }

    /** MTR's 16 node directions as an undirected axis, e.g. "E–W (0°)". */
    private static String axisLabel(double heading) {
        final int index = Math.floorMod((int) Math.round(Math.toDegrees(heading) / 22.5), 8);
        return COMPASS[index] + "–" + COMPASS[index + 8] + " (" + fmt(index * 22.5, 1) + "°)";
    }

    static String fmt(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    private static String signed(double value, int decimals) {
        return (value > 0 ? "+" : "") + fmt(value, decimals);
    }
}
