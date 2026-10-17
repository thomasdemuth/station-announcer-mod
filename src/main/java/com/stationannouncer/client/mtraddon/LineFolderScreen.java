package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.gui.LineStyleEditor;
import com.stationannouncer.client.mtr.RouteBullets;
import com.stationannouncer.mtraddon.LineFolders;
import com.stationannouncer.mtraddon.LineStyles;
import com.stationannouncer.mtraddon.PlatformOccupancy;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line folders, managed (FlatUi). Left: the whole tree — folders (automatic ones, from the
 * line name, in italics-grey "auto" until edited) and routes. Right, for the selected folder:
 * Contents (name, where it sits, order, its subfolders and routes with ↑↓ / Move…, bulk colour /
 * hide / show, + subfolder, delete), Style (the line's bullet through {@link LineStyleEditor}
 * for a line folder; shape / lettering for every route inside a hand-made folder), Overview
 * (each route: stops with overlap warnings, Interlining holds) and Diagram (the routes as
 * rows over the merged station order, MTA service-diagram style; red dots = overlaps).
 * A selected route offers Move… / ↑↓ / its route screen.
 *
 * <p>Edits build a working copy of {@link LineFolders.State}; Save sends it whole (plus
 * route colour / visibility through MTR's {@code PacketUpdateData} and style edits).
 * Automatic folders are stored ("materialised") the moment they are edited.</p>
 */
@Environment(EnvType.CLIENT)
public class LineFolderScreen extends FlatPaScreen {
    private static final int HIT_SAVE = 1;
    private static final int HIT_CANCEL = 2;
    private static final int HIT_TREE_FOLDER = 3;
    private static final int HIT_TREE_ROUTE = 4;
    private static final int HIT_TREE_TOGGLE = 5;
    private static final int HIT_TAB = 6;
    private static final int HIT_FOLDER_UP = 7;
    private static final int HIT_FOLDER_DOWN = 8;
    private static final int HIT_MOVE_FOLDER = 9;
    private static final int HIT_NEW_SUB = 10;
    private static final int HIT_DELETE = 11;
    private static final int HIT_ROUTE_UP = 12;
    private static final int HIT_ROUTE_DOWN = 13;
    private static final int HIT_MOVE_ROUTE = 14;
    private static final int HIT_OPEN_ROUTE = 15;
    private static final int HIT_SWATCH = 16;
    private static final int HIT_HIDE_ALL = 17;
    private static final int HIT_SHOW_ALL = 18;
    private static final int HIT_PICK_TARGET = 19;
    private static final int HIT_MODAL_BACKDROP = 20;
    private static final int HIT_MODAL_SWALLOW = 21;
    private static final int HIT_BULK_SHAPE = 22;
    private static final int HIT_BULK_INK = 23;
    private static final int HIT_OVERVIEW_ROUTE = 24;
    private static final int HIT_SUBFOLDER = 25;

    private static final String[] TABS = {"Contents", "Style", "Overview", "Diagram"};
    private static final String[] BULK_SHAPES = {"Keep", "Circle", "Diamond", "Square", "Hexagon"};
    private static final String[] BULK_INKS = {"Keep", "Auto", "White", "Black"};
    private static final int AMBER = 0xFFE5A23A;
    private static final long REQUEST_SPACING_MILLIS = 500;
    /** The order automatic folders sort at (alphabetically among themselves); ↑↓ numbers siblings 0..n. */
    private static final int UNORDERED = Integer.MAX_VALUE / 2;

    private final Screen parent;
    private final Map<Long, LineFolders.Folder> folders;
    private final Map<Long, LineFolders.Placement> placements;
    private boolean treeDirty;
    private ClientLineFolders.Node root;
    private final List<Route> routes;

    /** Selection: a folder id (0 = All lines) or, when {@code selectedRoute} ≠ 0, a route. */
    private long selectedFolder;
    private long selectedRoute;
    private int tab;
    private final Set<Long> collapsed = new HashSet<>();
    private final FlatUi.TextBox nameBox;
    private long nameBoxFolder = Long.MIN_VALUE;

    // Pending route edits applied on Save.
    private final Map<Long, Integer> newColors = new LinkedHashMap<>();
    private final Map<Long, Boolean> newHidden = new LinkedHashMap<>();
    private final Map<Long, LineStyles.RouteStyle> newRouteStyles = new LinkedHashMap<>();
    private LineStyles.Shape bulkShape;
    private LineStyles.TextColor bulkInk;
    private final Map<String, LineStyleEditor> lineEditors = new HashMap<>();

    // Schedules for Overview / Diagram.
    private final List<Long> pendingPlatforms = new ArrayList<>();
    private final Set<Long> requested = new HashSet<>();
    private long lastRequest;
    private final Map<Long, PlatformOccupancy.Result> results = new HashMap<>();
    private int seenVersion = -1;

    private int treeScroll;
    private int treeContent;
    private int paneScroll;
    private int paneContent;
    private int leftX, leftY, leftW, leftH, rightX, rightY, rightW, rightH;
    private final Map<Integer, int[]> segments = new HashMap<>();

    /** Move modal: the thing being moved (folder or route id) or null. */
    private long[] moving; // {kind 0 folder / 1 route, id}

    public LineFolderScreen(long folderId, Screen parent) {
        super(Text.literal("Lines"));
        this.parent = parent;
        LineFolders.State state = ClientLineFolders.state();
        this.folders = new LinkedHashMap<>(state.folders());
        this.placements = new LinkedHashMap<>(state.routes());
        this.routes = ClientLineFolders.allRoutes();
        this.selectedFolder = folderId;
        this.nameBox = new FlatUi.TextBox(MinecraftClient.getInstance().textRenderer, LineFolders.MAX_NAME, false);
        nameBox.onChange(value -> rename(value));
        rebuild();
    }

    private LineFolders.State working() {
        return new LineFolders.State(Map.copyOf(folders), Map.copyOf(placements));
    }

    private void rebuild() {
        root = ClientLineFolders.build(routes, working());
        if (root.find(selectedFolder) == null) {
            selectedFolder = 0;
        }
    }

    private ClientLineFolders.Node folder() {
        ClientLineFolders.Node node = root.find(selectedFolder);
        return node == null ? root : node;
    }

    private Route route(long id) {
        for (Route route : routes) {
            if (route.getId() == id) {
                return route;
            }
        }
        return null;
    }

    // ================================================================ edits

    /** Stores an automatic folder (and its automatic ancestors) so it can be edited; returns its real id. */
    private long materialise(ClientLineFolders.Node node) {
        if (node == null || node.isRoot()) {
            return 0;
        }
        if (!node.virtual) {
            return node.id;
        }
        long parentId = materialise(node.parent);
        long id = ClientLineFolders.newId();
        // Unordered (alphabetical among the automatic folders) until someone reorders by hand.
        folders.put(id, new LineFolders.Folder(id, node.name, parentId, UNORDERED, node.line));
        if (selectedFolder == node.id) {
            selectedFolder = id;
        }
        treeDirty = true;
        rebuild();
        return id;
    }

    private List<ClientLineFolders.Node> siblingsOf(long parentId) {
        ClientLineFolders.Node parentNode = parentId == 0 ? root : root.find(parentId);
        return parentNode == null ? List.of() : parentNode.folders;
    }

    private void rename(String value) {
        ClientLineFolders.Node node = folder();
        String name = LineFolders.clean(value);
        if (node.isRoot() || name.isEmpty()) {
            return;
        }
        long id = materialise(node);
        nameBoxFolder = id; // keep typing — the box must not reload for the stored copy
        LineFolders.Folder stored = folders.get(id);
        if (stored != null && !stored.name().equals(name)) {
            folders.put(id, stored.withName(name));
            treeDirty = true;
            rebuild();
        }
    }

    /** Re-numbers a folder's subfolders in their current order, after moving one by {@code delta}. */
    private void moveFolderInSiblings(ClientLineFolders.Node node, int delta) {
        if (node.isRoot()) {
            return;
        }
        List<ClientLineFolders.Node> siblings = new ArrayList<>(node.parent.folders);
        int index = siblings.indexOf(node);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        siblings.remove(index);
        siblings.add(target, node);
        // Automatic folders only ever sit at the top level, so the parent is the root or stored.
        long parentId = node.parent.isRoot() ? 0 : node.parent.id;
        for (int i = 0; i < siblings.size(); i++) {
            ClientLineFolders.Node sibling = siblings.get(i);
            long id = sibling.virtual ? ClientLineFolders.newId() : sibling.id;
            LineFolders.Folder stored = folders.get(id);
            folders.put(id, stored != null ? stored.withParent(parentId).withOrder(i)
                    : new LineFolders.Folder(id, sibling.name, parentId, i, sibling.line));
            if (sibling == node) {
                selectedFolder = id;
                nameBoxFolder = id;
            }
        }
        treeDirty = true;
        rebuild();
    }

    private void moveRouteInFolder(long routeId, int delta) {
        ClientLineFolders.Node node = root.folderOf(routeId);
        if (node == null) {
            return;
        }
        List<Route> list = new ArrayList<>(node.routes);
        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == routeId) {
                index = i;
            }
        }
        int target = index + delta;
        if (index < 0 || target < 0 || target >= list.size()) {
            return;
        }
        Route moved = list.remove(index);
        list.add(target, moved);
        long folderId = materialise(node);
        for (int i = 0; i < list.size(); i++) {
            placements.put(list.get(i).getId(), new LineFolders.Placement(folderId, i));
        }
        treeDirty = true;
        rebuild();
    }

    private void moveRouteTo(long routeId, ClientLineFolders.Node target) {
        long folderId = materialise(target);
        ClientLineFolders.Node node = folderId == 0 ? root : root.find(folderId);
        placements.put(routeId, new LineFolders.Placement(folderId, node == null ? 0 : node.routes.size()));
        treeDirty = true;
        rebuild();
    }

    private boolean moveFolderTo(ClientLineFolders.Node node, ClientLineFolders.Node target) {
        if (node.isRoot()) {
            return false;
        }
        for (ClientLineFolders.Node t = target; t != null; t = t.parent) {
            if (t == node) {
                toast("A folder cannot go inside itself", true);
                return false;
            }
        }
        long id = materialise(node);
        long targetId = materialise(root.find(target.id) != null ? root.find(target.id) : target);
        LineFolders.Folder stored = folders.get(id);
        Map<Long, LineFolders.Folder> trial = new LinkedHashMap<>(folders);
        trial.put(id, stored.withParent(targetId).withOrder(UNORDERED));
        if (LineFolders.depth(trial, id) < 0) {
            toast("Too deep — folders nest at most " + LineFolders.MAX_DEPTH + " levels", true);
            return false;
        }
        folders.put(id, trial.get(id));
        selectedFolder = id;
        treeDirty = true;
        rebuild();
        return true;
    }

    private void newSubfolder() {
        ClientLineFolders.Node node = folder();
        long parentId = materialise(node);
        if (parentId != 0 && LineFolders.depth(folders, parentId) >= LineFolders.MAX_DEPTH) {
            toast("Folders nest at most " + LineFolders.MAX_DEPTH + " levels", true);
            return;
        }
        long id = ClientLineFolders.newId();
        folders.put(id, new LineFolders.Folder(id, "New folder", parentId, UNORDERED, ""));
        selectedFolder = id;
        selectedRoute = 0;
        collapsed.remove(parentId);
        tab = 0;
        treeDirty = true;
        rebuild();
        nameBoxFolder = Long.MIN_VALUE; // reload the name box for the new folder
    }

    /** Deletes a stored folder: its subfolders and routes move up to its parent. */
    private void deleteFolder(ClientLineFolders.Node node) {
        if (node.isRoot() || node.virtual) {
            return;
        }
        long parentId = node.parent.isRoot() ? 0 : materialise(node.parent);
        for (LineFolders.Folder child : new ArrayList<>(folders.values())) {
            if (child.parent() == node.id) {
                folders.put(child.id(), child.withParent(parentId));
            }
        }
        for (Map.Entry<Long, LineFolders.Placement> entry : new ArrayList<>(placements.entrySet())) {
            if (entry.getValue().folder() == node.id) {
                placements.put(entry.getKey(), new LineFolders.Placement(parentId, entry.getValue().order()));
            }
        }
        folders.remove(node.id);
        selectedFolder = parentId;
        treeDirty = true;
        rebuild();
    }

    // ================================================================ schedules

    private void queueSchedules(List<Route> list) {
        for (Route route : list) {
            for (RoutePlatformData data : route.getRoutePlatforms()) {
                if (data.platform != null && requested.add(data.platform.getId())) {
                    pendingPlatforms.add(data.platform.getId());
                }
            }
        }
    }

    private void pumpSchedules() {
        long now = System.currentTimeMillis();
        if (!pendingPlatforms.isEmpty() && now - lastRequest > REQUEST_SPACING_MILLIS) {
            lastRequest = now;
            int count = Math.min(64, pendingPlatforms.size());
            List<Long> chunk = new ArrayList<>(pendingPlatforms.subList(0, count));
            pendingPlatforms.subList(0, count).clear();
            ClientPlatformSchedule.request(chunk);
        }
        if (seenVersion != ClientPlatformSchedule.version()) {
            seenVersion = ClientPlatformSchedule.version();
            results.clear();
        }
    }

    private PlatformOccupancy.Result result(Platform platform) {
        PlatformOccupancy.Result cached = results.get(platform.getId());
        if (cached != null) {
            return cached;
        }
        ClientPlatformSchedule.Data data = ClientPlatformSchedule.get(platform.getId());
        if (data == null) {
            return null;
        }
        Map<Long, Long> stored = ClientDwellOverrides.get(platform.getId());
        ClientHoldRules.Rule rule = ClientHoldRules.get(platform.getId());
        long extra = rule == null || rule.watched().isEmpty() ? 0 : (rule.seconds() + rule.transferSeconds()) * 1_000L;
        PlatformOccupancy.Result result = PlatformOccupancy.compute(data.sidings(), data.day(), new PlatformOccupancy.Plan() {
            @Override
            public long dwell(long routeId, long bakedDwell) {
                Long millis = stored == null ? null : stored.get(routeId);
                return millis != null ? millis : platform.getDwellTime();
            }

            @Override
            public long holdExtra() {
                return extra;
            }
        });
        results.put(platform.getId(), result);
        return result;
    }

    /** 0 = fine / unknown, 1 = maybe, 2 = hard overlap for this route at this platform. */
    private int problem(Route route, Platform platform) {
        PlatformOccupancy.Result result = result(platform);
        if (result == null) {
            return 0;
        }
        int worst = 0;
        for (PlatformOccupancy.Conflict conflict : result.conflicts()) {
            if (conflict.blocker().routeId() == route.getId() || conflict.blocked().routeId() == route.getId()) {
                worst = Math.max(worst, conflict.maybe() ? 1 : 2);
            }
        }
        return worst;
    }

    // ================================================================ drawing

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        segments.clear();
        idIndex.clear();
        pumpSchedules();
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        c.drawText(textRenderer, "Lines", 8, 9, FlatUi.TEXT, false);
        ClientLineFolders.Node node = folder();
        String crumb = selectedRoute != 0 ? routeName(selectedRoute) : node.isRoot() ? "All lines" : node.path();
        c.drawText(textRenderer, trim(crumb, Math.max(10, width - 160)), 8 + textWidth("Lines") + 8, 9, FlatUi.TEXT_DIM, false);
        button(c, mx, my, "Save", width - 56, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.PRIMARY, true, HIT_SAVE, 0);
        button(c, mx, my, "Cancel", width - 110, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.FLAT, true, HIT_CANCEL, 0);

        int top = 26;
        int bottom = height - 6;
        leftW = MathHelper.clamp(Math.round(width * 0.36f), 140, 240);
        leftX = 6;
        leftY = top;
        leftH = bottom - top;
        rightX = leftX + leftW + 6;
        rightY = top;
        rightW = width - rightX - 6;
        rightH = leftH;

        FlatUi.pane(c, leftX, leftY, leftW, leftH);
        beginClip(c, leftX + 1, leftY + 1, leftW - 2, leftH - 2);
        int end = drawTree(c, mx, my, root, leftX + 5, leftY + 4 - treeScroll, leftW - 10, 0);
        treeContent = end + treeScroll - leftY + 4;
        endClip(c);
        treeScroll = MathHelper.clamp(treeScroll, 0, Math.max(0, treeContent - leftH));
        FlatUi.scrollThumb(c, leftX + leftW - 1, leftY + 1, leftH - 2, treeContent, treeScroll);

        FlatUi.pane(c, rightX, rightY, rightW, rightH);
        int contentTop = rightY;
        boolean folderView = selectedRoute == 0;
        if (folderView && !node.isRoot()) {
            int sx = rightX + 6;
            int sw = rightW - 12;
            FlatUi.segmented(c, textRenderer, TABS, tab, sx, rightY + 5, sw, 15, mx, my);
            segments.put(HIT_TAB, new int[]{sx, sw});
            hit(sx, rightY + 5, sw, 15, HIT_TAB, 0);
            contentTop = rightY + 24;
        }
        int viewH = rightY + rightH - contentTop - 1;
        beginClip(c, rightX + 1, contentTop, rightW - 2, viewH);
        int x = rightX + 8;
        int w = rightW - 16;
        int y = contentTop + 4 - paneScroll;
        if (!folderView) {
            end = drawRoute(c, mx, my, x, y, w);
        } else if (node.isRoot()) {
            end = drawContents(c, mx, my, node, x, y, w);
        } else {
            end = switch (tab) {
                case 1 -> drawStyle(c, mx, my, node, x, y, w);
                case 2 -> drawOverview(c, mx, my, node, x, y, w);
                case 3 -> drawDiagram(c, mx, my, node, x, y, w);
                default -> drawContents(c, mx, my, node, x, y, w);
            };
        }
        paneContent = end + paneScroll - contentTop + 4;
        endClip(c);
        paneScroll = MathHelper.clamp(paneScroll, 0, Math.max(0, paneContent - viewH));
        FlatUi.scrollThumb(c, rightX + rightW - 1, contentTop, viewH, paneContent, paneScroll);

        if (moving != null) {
            drawMoveModal(c, mx, my);
        }
    }

    private int drawTree(DrawContext c, int mx, int my, ClientLineFolders.Node node, int x, int y, int w, int depth) {
        if (node.isRoot()) {
            boolean sel = selectedFolder == 0 && selectedRoute == 0;
            treeRow(c, mx, my, x, y, w, sel);
            c.drawText(textRenderer, "All lines", x + 2, y + 2, FlatUi.TEXT, false);
            hit(x - 2, y, w + 4, 12, HIT_TREE_FOLDER, 0);
            y += 13;
            depth = 1;
        }
        int indent = x + (depth - 1) * 9 + (node.isRoot() ? 0 : 9);
        for (ClientLineFolders.Node child : node.folders) {
            if (child.allRoutes().isEmpty() && child.folders.isEmpty() && child.virtual) {
                continue;
            }
            boolean sel = selectedRoute == 0 && selectedFolder == child.id;
            treeRow(c, mx, my, x, y, w, sel);
            boolean open = !collapsed.contains(child.id);
            int ix = x + depth * 9;
            c.drawText(textRenderer, open ? "−" : "+", ix - 8, y + 2, FlatUi.TEXT_DIM, false);
            hit(ix - 9, y, 8, 12, HIT_TREE_TOGGLE, index(child.id));
            FlatUi.rect(c, ix, y + 3, 6, 6, 0xFF000000 | child.color());
            String label = child.name + "  " + child.allRoutes().size();
            c.drawText(textRenderer, trim(label, w - (ix - x) - 10), ix + 9, y + 2, child.virtual ? FlatUi.TEXT_DIM : FlatUi.TEXT, false);
            hit(ix, y, w - (ix - x), 12, HIT_TREE_FOLDER, index(child.id));
            y += 13;
            if (open) {
                y = drawTree(c, mx, my, child, x, y, w, depth + 1);
            }
        }
        for (Route route : node.routes) {
            boolean sel = selectedRoute == route.getId();
            treeRow(c, mx, my, x, y, w, sel);
            int ix = x + depth * 9;
            FlatUi.rect(c, ix + 1, y + 2, 2, 8, 0xFF000000 | route.getColor());
            c.drawText(textRenderer, trim(IGui.formatStationName(route.getName()), w - (ix - x) - 8), ix + 6, y + 2,
                    route.getHidden() ? FlatUi.TEXT_FAINT : FlatUi.TEXT_DIM, false);
            hit(ix, y, w - (ix - x), 12, HIT_TREE_ROUTE, routeIndex(route.getId()));
            y += 13;
        }
        return y;
    }

    private void treeRow(DrawContext c, int mx, int my, int x, int y, int w, boolean selected) {
        if (selected) {
            FlatUi.rect(c, x - 2, y, w + 4, 12, FlatUi.SELECTED);
        } else if (moving == null && FlatUi.inside(mx, my, x - 2, y, w + 4, 12) && my >= leftY && my < leftY + leftH) {
            FlatUi.rect(c, x - 2, y, w + 4, 12, FlatUi.HOVER);
        }
    }

    // Folder ids and route ids are longs; hits carry ints, so index them per frame.
    private final List<Long> idIndex = new ArrayList<>();

    private int index(long id) {
        int i = idIndex.indexOf(id);
        if (i < 0) {
            idIndex.add(id);
            i = idIndex.size() - 1;
        }
        return i;
    }

    private int routeIndex(long id) {
        return index(id);
    }

    private long idAt(int index) {
        return index >= 0 && index < idIndex.size() ? idIndex.get(index) : 0;
    }

    // ------------------------------------------------------------ Contents

    private int drawContents(DrawContext c, int mx, int my, ClientLineFolders.Node node, int x, int y, int w) {
        if (!node.isRoot()) {
            if (nameBoxFolder != node.id) {
                nameBox.load(node.name);
                nameBoxFolder = node.id;
            }
            FlatUi.heading(c, textRenderer, "Name", x, y);
            y += 11;
            box(c, nameBox, x, y, Math.min(w, 160), 16, mx, my);
            y += 20;
            String where = node.parent.isRoot() ? "Top level" : "In " + node.parent.path();
            c.drawText(textRenderer, trim(where, w - 120), x, y + 3, FlatUi.TEXT_DIM, false);
            button(c, mx, my, "↑", x + w - 108, y, 14, 14, FlatUi.ButtonStyle.FLAT, true, HIT_FOLDER_UP, 0);
            button(c, mx, my, "↓", x + w - 92, y, 14, 14, FlatUi.ButtonStyle.FLAT, true, HIT_FOLDER_DOWN, 0);
            button(c, mx, my, "Move…", x + w - 74, y, 74, 14, FlatUi.ButtonStyle.FLAT, true, HIT_MOVE_FOLDER, 0);
            y += 18;
            y = para(c, node.line.isEmpty() ? "Hand-made folder: only the routes moved into it."
                    : "Collects every route named \"" + node.line + "||…\" automatically"
                    + (node.virtual ? " (not changed yet)." : "."), x, y, w, FlatUi.TEXT_FAINT);
        } else {
            y = para(c, "Routes sharing a line name (\"Line 4||North Limited\") group by themselves; move routes and make "
                    + "folders here. The dashboard's Routes list shows the same tree.", x, y, w, FlatUi.TEXT_FAINT);
        }

        FlatUi.heading(c, textRenderer, node.isRoot() ? "Folders" : "Subfolders", x, y);
        button(c, mx, my, node.isRoot() ? "+ New folder" : "+ Subfolder", x + w - 76, y - 3, 76, 13,
                FlatUi.ButtonStyle.FLAT, true, HIT_NEW_SUB, 0);
        y += 13;
        if (node.folders.isEmpty()) {
            y = para(c, "None", x, y, w, FlatUi.TEXT_FAINT);
        }
        for (ClientLineFolders.Node child : node.folders) {
            if (child.allRoutes().isEmpty() && child.folders.isEmpty() && child.virtual) {
                continue;
            }
            boolean hovered = moving == null && FlatUi.inside(mx, my, x - 2, y, w + 4, 13);
            if (hovered) {
                FlatUi.rect(c, x - 2, y, w + 4, 13, FlatUi.HOVER);
            }
            FlatUi.rect(c, x, y + 3, 6, 6, 0xFF000000 | child.color());
            c.drawText(textRenderer, trim(child.name + "  ·  " + child.allRoutes().size() + " routes", w - 10), x + 9, y + 2,
                    FlatUi.TEXT, false);
            hit(x - 2, y, w + 4, 13, HIT_SUBFOLDER, index(child.id));
            y += 14;
        }
        y += 4;

        FlatUi.heading(c, textRenderer, "Routes", x, y);
        y += 13;
        if (node.routes.isEmpty()) {
            y = para(c, node.isRoot() ? "Every route is in a folder." : "No routes directly in this folder.", x, y, w, FlatUi.TEXT_FAINT);
        }
        for (Route route : node.routes) {
            boolean hovered = moving == null && FlatUi.inside(mx, my, x - 2, y, w + 4, 15);
            if (hovered) {
                FlatUi.rect(c, x - 2, y, w + 4, 15, FlatUi.HOVER);
            }
            RouteBullets.Bullet bullet = RouteBullets.styled(route.getName(), RouteBullets.label(route.getName()),
                    0xFF000000 | displayColor(route));
            LineStyleEditor.drawBullet(c, bullet, x + 6, y + 7, 5.5f);
            int bw = 72;
            c.drawText(textRenderer, trim(IGui.formatStationName(route.getName()), w - 16 - bw), x + 15, y + 4,
                    hidden(route) ? FlatUi.TEXT_FAINT : FlatUi.TEXT, false);
            int bx = x + w - bw;
            button(c, mx, my, "↑", bx, y + 1, 13, 13, FlatUi.ButtonStyle.GHOST, true, HIT_ROUTE_UP, routeIndex(route.getId()));
            button(c, mx, my, "↓", bx + 14, y + 1, 13, 13, FlatUi.ButtonStyle.GHOST, true, HIT_ROUTE_DOWN, routeIndex(route.getId()));
            button(c, mx, my, "Move", bx + 29, y + 1, 43, 13, FlatUi.ButtonStyle.FLAT, true, HIT_MOVE_ROUTE, routeIndex(route.getId()));
            hit(x - 2, y, w - bw - 2, 15, HIT_OPEN_ROUTE, routeIndex(route.getId()));
            y += 16;
        }

        List<Route> all = node.allRoutes();
        if (!node.isRoot() && !all.isEmpty()) {
            y += 4;
            FlatUi.heading(c, textRenderer, "Every route inside (" + all.size() + ")", x, y);
            y += 12;
            c.drawText(textRenderer, "Colour all:", x, y + 2, FlatUi.TEXT_DIM, false);
            int sx = x + textWidth("Colour all:") + 4;
            int sw = 10;
            int perRow = Math.max(4, (x + w - sx) / (sw + 2));
            for (int i = 0; i < LineStyleEditor.PALETTE.length; i++) {
                int px = sx + (i % perRow) * (sw + 2);
                int py = y + (i / perRow) * (sw + 2);
                FlatUi.rect(c, px, py, sw, sw, 0xFF000000 | LineStyleEditor.PALETTE[i].rgb());
                if (FlatUi.inside(mx, my, px, py, sw, sw)) {
                    FlatUi.outline(c, px, py, sw, sw, FlatUi.TEXT);
                }
                hit(px, py, sw, sw, HIT_SWATCH, i);
            }
            y += ((LineStyleEditor.PALETTE.length + perRow - 1) / perRow) * (sw + 2) + 4;
            button(c, mx, my, "Hide all", x, y, 60, 14, FlatUi.ButtonStyle.FLAT, true, HIT_HIDE_ALL, 0);
            button(c, mx, my, "Show all", x + 64, y, 60, 14, FlatUi.ButtonStyle.FLAT, true, HIT_SHOW_ALL, 0);
            y += 20;
        }
        if (!node.isRoot() && !node.virtual) {
            y += 4;
            button(c, mx, my, "Delete folder", x, y, 84, 14, FlatUi.ButtonStyle.DANGER, true, HIT_DELETE, 0);
            y += 16;
            y = para(c, "Its subfolders and routes move up a level.", x, y, w, FlatUi.TEXT_FAINT);
        }
        return y;
    }

    private int displayColor(Route route) {
        Integer color = newColors.get(route.getId());
        return color != null ? color : route.getColor() & 0xFFFFFF;
    }

    private boolean hidden(Route route) {
        Boolean value = newHidden.get(route.getId());
        return value != null ? value : route.getHidden();
    }

    // ------------------------------------------------------------ Style

    private int drawStyle(DrawContext c, int mx, int my, ClientLineFolders.Node node, int x, int y, int w) {
        List<Route> all = node.allRoutes();
        if (all.isEmpty()) {
            return para(c, "No routes in this folder yet.", x, y, w, FlatUi.TEXT_FAINT);
        }
        if (!node.line.isEmpty()) {
            LineStyleEditor editor = lineEditor(node.line, all);
            if (editor != null) {
                y = para(c, "The " + node.line + " line's bullet — every route named \"" + node.line
                        + "||…\". A route's own style (its route screen, Style → This route) wins.", x, y, w, FlatUi.TEXT_FAINT);
                int previewH = MathHelper.clamp(rightH - 60, 150, 230);
                FlatUi.rect(c, x - 2, y, w + 4, previewH, FlatUi.GROUND);
                editor.drawPreview(c, x - 2, y, w + 4, previewH);
                y += previewH + 6;
                return editor.drawInspector(c, mx, my, x, y, w);
            }
        }
        y = para(c, "A hand-made folder has no line of its own: set the shape and lettering of every route inside "
                + "(saved as each route's own style).", x, y, w, FlatUi.TEXT_FAINT);
        FlatUi.heading(c, textRenderer, "Shape", x, y);
        y += 11;
        FlatUi.segmented(c, textRenderer, BULK_SHAPES, bulkShape == null ? 0 : bulkShape.ordinal() + 1, x, y, w, 15, mx, my);
        segments.put(HIT_BULK_SHAPE, new int[]{x, w});
        hit(x, y, w, 15, HIT_BULK_SHAPE, 0);
        y += 20;
        FlatUi.heading(c, textRenderer, "Lettering", x, y);
        y += 11;
        FlatUi.segmented(c, textRenderer, BULK_INKS, bulkInk == null ? 0 : bulkInk.ordinal() + 1, x, y, w, 15, mx, my);
        segments.put(HIT_BULK_INK, new int[]{x, w});
        hit(x, y, w, 15, HIT_BULK_INK, 0);
        y += 22;
        int bx = x;
        for (Route route : all) {
            RouteBullets.Bullet bullet = bulkBullet(route);
            if (bx + 18 > x + w) {
                bx = x;
                y += 18;
            }
            LineStyleEditor.drawBullet(c, bullet, bx + 8, y + 8, 7);
            bx += 18;
        }
        return y + 22;
    }

    private RouteBullets.Bullet bulkBullet(Route route) {
        int color = 0xFF000000 | displayColor(route);
        RouteBullets.Bullet current = RouteBullets.styled(route.getName(), RouteBullets.label(route.getName()), color);
        LineStyles.Shape shape = bulkShape != null ? bulkShape : current.shape();
        boolean dark = bulkInk == null ? current.darkText()
                : bulkInk == LineStyles.TextColor.BLACK || (bulkInk == LineStyles.TextColor.AUTO && LineStyles.needsDarkText(color));
        return new RouteBullets.Bullet(current.label(), color, shape, dark);
    }

    private LineStyleEditor lineEditor(String line, List<Route> all) {
        LineStyleEditor editor = lineEditors.get(line);
        if (editor == null) {
            // Seed from the route whose number the line uses most ("4", not the express's "4X").
            Map<String, Integer> numbers = new HashMap<>();
            for (Route route : all) {
                if (LineStyles.lineKey(route.getName()).equals(line)) {
                    numbers.merge(route.getRouteNumber() == null ? "" : route.getRouteNumber().trim(), 1, Integer::sum);
                }
            }
            String common = numbers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            for (Route route : all) {
                String number = route.getRouteNumber() == null ? "" : route.getRouteNumber().trim();
                if (LineStyles.lineKey(route.getName()).equals(line) && number.equals(common)) {
                    editor = new LineStyleEditor(this, route);
                    lineEditors.put(line, editor);
                    break;
                }
            }
        }
        return editor;
    }

    // ------------------------------------------------------------ Overview

    private int drawOverview(DrawContext c, int mx, int my, ClientLineFolders.Node node, int x, int y, int w) {
        List<Route> all = node.allRoutes();
        queueSchedules(all);
        y = para(c, "Each route's stops: red = a train waits for the platform there, amber = only if a connection "
                + "hold runs long. Click a route to open it.", x, y, w, FlatUi.TEXT_FAINT);
        for (Route route : all) {
            List<RoutePlatformData> stops = route.getRoutePlatforms();
            int hard = 0;
            int maybe = 0;
            int interline = 0;
            for (RoutePlatformData data : stops) {
                if (data.platform == null) {
                    continue;
                }
                int problem = problem(route, data.platform);
                if (problem == 2) {
                    hard++;
                } else if (problem == 1) {
                    maybe++;
                }
                if (ClientDwellOverrides.source(data.platform.getId(), route.getId()) == com.stationannouncer.mtraddon.AddonStore.DWELL_SOURCE_INTERLINE) {
                    interline++;
                }
            }
            boolean hovered = moving == null && FlatUi.inside(mx, my, x - 2, y, w + 4, 26);
            if (hovered) {
                FlatUi.rect(c, x - 2, y, w + 4, 26, FlatUi.HOVER);
            }
            RouteBullets.Bullet bullet = RouteBullets.styled(route.getName(), RouteBullets.label(route.getName()),
                    0xFF000000 | displayColor(route));
            LineStyleEditor.drawBullet(c, bullet, x + 6, y + 6, 5.5f);
            String summary = hard > 0 ? "⚠ " + hard + (hard == 1 ? " stop" : " stops") : maybe > 0 ? "? " + maybe : "✓";
            int summaryColor = hard > 0 ? FlatUi.DANGER : maybe > 0 ? AMBER : FlatUi.OK;
            int sw = textWidth(summary);
            c.drawText(textRenderer, trim(IGui.formatStationName(route.getName()), w - 20 - sw), x + 15, y + 2, FlatUi.TEXT, false);
            c.drawText(textRenderer, summary, x + w - sw, y + 2, summaryColor, false);
            // The stop strip.
            int sx = x + 15;
            int stripW = w - 15;
            int n = Math.max(1, stops.size());
            FlatUi.rect(c, sx, y + 17, stripW, 2, 0xFF000000 | displayColor(route));
            for (int i = 0; i < stops.size(); i++) {
                Platform platform = stops.get(i).platform;
                int px = sx + (n == 1 ? 0 : (stripW - 3) * i / (n - 1));
                int problem = platform == null ? 0 : problem(route, platform);
                int color = problem == 2 ? FlatUi.DANGER : problem == 1 ? AMBER : FlatUi.TEXT;
                FlatUi.rect(c, px, y + 15, 3, 6, color);
            }
            if (interline > 0) {
                String note = interline + " Interlining hold" + (interline == 1 ? "" : "s");
                c.drawText(textRenderer, note, x + w - textWidth(note), y + 10, AMBER, false);
            }
            hit(x - 2, y, w + 4, 26, HIT_OVERVIEW_ROUTE, routeIndex(route.getId()));
            y += 28;
        }
        return y;
    }

    // ------------------------------------------------------------ Diagram

    private int drawDiagram(DrawContext c, int mx, int my, ClientLineFolders.Node node, int x, int y, int w) {
        List<Route> all = node.allRoutes();
        queueSchedules(all);
        // Merged station order: the longest route first, then every other station after the
        // previous station of its route that is already placed.
        List<Long> order = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        List<Route> byLength = new ArrayList<>(all);
        byLength.sort((a, b) -> Integer.compare(b.getRoutePlatforms().size(), a.getRoutePlatforms().size()));
        for (Route route : byLength) {
            long previous = Long.MIN_VALUE;
            for (RoutePlatformData data : route.getRoutePlatforms()) {
                if (data.platform == null || data.platform.area == null) {
                    continue;
                }
                long station = data.platform.area.getId();
                names.putIfAbsent(station, IGui.formatStationName(data.platform.area.getName()));
                if (!order.contains(station)) {
                    int at = previous == Long.MIN_VALUE ? 0 : order.indexOf(previous) + 1;
                    order.add(Math.max(0, Math.min(order.size(), at)), station);
                }
                previous = station;
            }
        }
        if (order.isEmpty()) {
            return para(c, "No stops yet.", x, y, w, FlatUi.TEXT_FAINT);
        }
        int labelArea = 64;
        int bulletW = 18;
        int cols = order.size();
        int colW = Math.max(6, (w - bulletW - 6) / Math.max(1, cols));
        int gridX = x + bulletW + 4;
        // Station names, rotated, above the rows.
        int baseY = y + labelArea;
        for (int i = 0; i < cols; i++) {
            int cx = gridX + i * colW + colW / 2;
            c.getMatrices().push();
            c.getMatrices().translate(cx, baseY - 2, 0);
            c.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-60));
            c.getMatrices().scale(0.75f, 0.75f, 1);
            String name = names.getOrDefault(order.get(i), "?");
            c.drawText(textRenderer, trim(name, (int) (labelArea * 1.4)), 0, -4, FlatUi.TEXT_DIM, false);
            c.getMatrices().pop();
            FlatUi.rect(c, cx, baseY, 1, 2, FlatUi.BORDER_STRONG);
        }
        y = baseY + 6;
        int rowH = 16;
        for (Route route : all) {
            int color = 0xFF000000 | displayColor(route);
            RouteBullets.Bullet bullet = RouteBullets.styled(route.getName(), RouteBullets.label(route.getName()), color);
            LineStyleEditor.drawBullet(c, bullet, x + 7, y + rowH / 2f, 6);
            Map<Long, Platform> served = new LinkedHashMap<>();
            for (RoutePlatformData data : route.getRoutePlatforms()) {
                if (data.platform != null && data.platform.area != null) {
                    served.putIfAbsent(data.platform.area.getId(), data.platform);
                }
            }
            int first = Integer.MAX_VALUE;
            int last = -1;
            for (long station : served.keySet()) {
                int i = order.indexOf(station);
                first = Math.min(first, i);
                last = Math.max(last, i);
            }
            if (last >= 0) {
                int lineY = y + rowH / 2 - 1;
                FlatUi.rect(c, gridX + first * colW + colW / 2, lineY, (last - first) * colW + 1, 3, color);
                for (Map.Entry<Long, Platform> entry : served.entrySet()) {
                    int i = order.indexOf(entry.getKey());
                    int cx = gridX + i * colW + colW / 2;
                    int problem = problem(route, entry.getValue());
                    FlatUi.rect(c, cx - 3, lineY - 2, 7, 7, 0xFF101014);
                    FlatUi.rect(c, cx - 2, lineY - 1, 5, 5, problem == 2 ? FlatUi.DANGER : problem == 1 ? AMBER : FlatUi.TEXT);
                }
            }
            if (FlatUi.inside(mx, my, x, y, w, rowH) && moving == null) {
                c.drawText(textRenderer, trim(IGui.formatStationName(route.getName()), w), x + bulletW + 4, y + rowH, FlatUi.TEXT_FAINT, false);
            }
            hit(x, y, w, rowH, HIT_OVERVIEW_ROUTE, routeIndex(route.getId()));
            y += rowH + 2;
        }
        y += 10;
        return para(c, "White dot = a stop, red = trains wait for the platform there, a line without a dot = runs through.",
                x, y, w, FlatUi.TEXT_FAINT);
    }

    // ------------------------------------------------------------ route view

    private int drawRoute(DrawContext c, int mx, int my, int x, int y, int w) {
        Route route = route(selectedRoute);
        if (route == null) {
            return y;
        }
        RouteBullets.Bullet bullet = RouteBullets.styled(route.getName(), RouteBullets.label(route.getName()),
                0xFF000000 | displayColor(route));
        LineStyleEditor.drawBullet(c, bullet, x + 9, y + 9, 8);
        c.drawText(textRenderer, trim(IGui.formatStationName(route.getName()), w - 24), x + 22, y + 5, FlatUi.TEXT, false);
        y += 22;
        ClientLineFolders.Node in = root.folderOf(route.getId());
        String where = in == null || in.isRoot() ? "Top level" : "In " + in.path();
        y = para(c, where + (placements.containsKey(route.getId()) ? " (moved by hand)" : " (by its name)"), x, y, w, FlatUi.TEXT_DIM);
        button(c, mx, my, "↑", x, y, 16, 15, FlatUi.ButtonStyle.FLAT, true, HIT_ROUTE_UP, routeIndex(route.getId()));
        button(c, mx, my, "↓", x + 18, y, 16, 15, FlatUi.ButtonStyle.FLAT, true, HIT_ROUTE_DOWN, routeIndex(route.getId()));
        button(c, mx, my, "Move to…", x + 38, y, 60, 15, FlatUi.ButtonStyle.FLAT, true, HIT_MOVE_ROUTE, routeIndex(route.getId()));
        y += 21;
        button(c, mx, my, "Open route screen", x, y, Math.min(w, 110), 15, FlatUi.ButtonStyle.PRIMARY, true,
                HIT_OPEN_ROUTE, routeIndex(route.getId()));
        y += 21;
        return para(c, route.getRoutePlatforms().size() + " stops · " + (hidden(route) ? "hidden" : "shown"), x, y, w, FlatUi.TEXT_FAINT);
    }

    // ------------------------------------------------------------ move modal

    private void drawMoveModal(DrawContext c, int mx, int my) {
        beginModal(c, HIT_MODAL_BACKDROP);
        List<ClientLineFolders.Node> targets = new ArrayList<>();
        collectTargets(root, targets);
        int pw = Math.min(240, width - 40);
        int rows = Math.min(targets.size(), Math.max(3, (height - 80) / 13));
        int ph = 22 + rows * 13 + 8;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        FlatUi.rect(c, px, py, pw, ph, FlatUi.PANE);
        FlatUi.outline(c, px, py, pw, ph, FlatUi.BORDER_STRONG);
        hit(px, py, pw, ph, HIT_MODAL_SWALLOW, 0);
        String what = moving[0] == 1 ? routeName(moving[1]) : nodeName(moving[1]);
        c.drawText(textRenderer, trim("Move " + what + " to:", pw - 16), px + 8, py + 6, FlatUi.TEXT, false);
        int y = py + 20;
        for (int i = 0; i < rows; i++) {
            ClientLineFolders.Node target = targets.get(i);
            boolean hovered = FlatUi.inside(mx, my, px + 4, y, pw - 8, 12);
            if (hovered) {
                FlatUi.rect(c, px + 4, y, pw - 8, 12, FlatUi.SELECTED);
            }
            int indent = target.isRoot() ? 0 : target.depth() * 8;
            c.drawText(textRenderer, trim(target.isRoot() ? "Top level (no folder)" : target.name, pw - 20 - indent),
                    px + 8 + indent, y + 2, target.virtual ? FlatUi.TEXT_DIM : FlatUi.TEXT, false);
            hit(px + 4, y, pw - 8, 12, HIT_PICK_TARGET, index(target.id));
            y += 13;
        }
        endModal(c);
    }

    private void collectTargets(ClientLineFolders.Node node, List<ClientLineFolders.Node> out) {
        if (moving != null && moving[0] == 0 && !node.isRoot() && node.id == moving[1]) {
            return; // a folder cannot go into itself or its subfolders
        }
        out.add(node);
        node.folders.forEach(child -> collectTargets(child, out));
    }

    private String nodeName(long id) {
        ClientLineFolders.Node node = root.find(id);
        return node == null ? "?" : node.name;
    }

    private String routeName(long id) {
        Route route = route(id);
        return route == null ? "?" : IGui.formatStationName(route.getName());
    }

    // ================================================================ input

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        if (id >= LineStyleEditor.BASE) {
            ClientLineFolders.Node node = folder();
            LineStyleEditor editor = node.line.isEmpty() ? null : lineEditors.get(node.line);
            if (editor != null) {
                editor.onHit(id, arg, mx, my);
            }
            return;
        }
        ClientLineFolders.Node node = folder();
        switch (id) {
            case HIT_SAVE -> saveAndClose();
            case HIT_CANCEL -> close();
            case HIT_TREE_FOLDER, HIT_SUBFOLDER -> {
                selectedFolder = idAt(arg);
                selectedRoute = 0;
                paneScroll = 0;
                if (id == HIT_SUBFOLDER) {
                    collapsed.remove(node.id);
                }
            }
            case HIT_TREE_TOGGLE -> {
                long folderId = idAt(arg);
                if (!collapsed.remove(folderId)) {
                    collapsed.add(folderId);
                }
            }
            case HIT_TREE_ROUTE -> {
                selectedRoute = idAt(arg);
                paneScroll = 0;
            }
            case HIT_TAB -> {
                int[] s = segments.get(HIT_TAB);
                tab = s == null ? 0 : MathHelper.clamp((int) ((mx - s[0]) * TABS.length / Math.max(1, s[1])), 0, TABS.length - 1);
                paneScroll = 0;
            }
            case HIT_FOLDER_UP -> moveFolderInSiblings(node, -1);
            case HIT_FOLDER_DOWN -> moveFolderInSiblings(node, 1);
            case HIT_MOVE_FOLDER -> moving = new long[]{0, node.id};
            case HIT_NEW_SUB -> newSubfolder();
            case HIT_DELETE -> deleteFolder(node);
            case HIT_ROUTE_UP -> moveRouteInFolder(idAt(arg), -1);
            case HIT_ROUTE_DOWN -> moveRouteInFolder(idAt(arg), 1);
            case HIT_MOVE_ROUTE -> moving = new long[]{1, idAt(arg)};
            case HIT_OPEN_ROUTE, HIT_OVERVIEW_ROUTE -> {
                Route route = route(idAt(arg));
                if (route != null) {
                    if (selectedRoute != route.getId() && id == HIT_OPEN_ROUTE && selectedRoute == 0) {
                        selectedRoute = route.getId(); // first click on a contents row selects it
                        paneScroll = 0;
                    } else {
                        client.setScreen(new RouteSettingsScreen(route, this, null));
                    }
                }
            }
            case HIT_SWATCH -> {
                int rgb = LineStyleEditor.PALETTE[arg].rgb();
                node.allRoutes().forEach(route -> newColors.put(route.getId(), rgb));
            }
            case HIT_HIDE_ALL -> node.allRoutes().forEach(route -> newHidden.put(route.getId(), true));
            case HIT_SHOW_ALL -> node.allRoutes().forEach(route -> newHidden.put(route.getId(), false));
            case HIT_BULK_SHAPE -> {
                int[] s = segments.get(HIT_BULK_SHAPE);
                int index = s == null ? 0 : MathHelper.clamp((int) ((mx - s[0]) * BULK_SHAPES.length / Math.max(1, s[1])), 0, BULK_SHAPES.length - 1);
                bulkShape = index == 0 ? null : LineStyles.Shape.byOrdinal(index - 1);
                applyBulkStyle(node);
            }
            case HIT_BULK_INK -> {
                int[] s = segments.get(HIT_BULK_INK);
                int index = s == null ? 0 : MathHelper.clamp((int) ((mx - s[0]) * BULK_INKS.length / Math.max(1, s[1])), 0, BULK_INKS.length - 1);
                bulkInk = index == 0 ? null : LineStyles.TextColor.byOrdinal(index - 1);
                applyBulkStyle(node);
            }
            case HIT_PICK_TARGET -> {
                ClientLineFolders.Node target = root.find(idAt(arg));
                if (target != null && moving != null) {
                    if (moving[0] == 1) {
                        moveRouteTo(moving[1], target);
                    } else {
                        ClientLineFolders.Node folderNode = root.find(moving[1]);
                        if (folderNode != null) {
                            moveFolderTo(folderNode, target);
                        }
                    }
                }
                moving = null;
            }
            case HIT_MODAL_BACKDROP -> moving = null;
            default -> {
            }
        }
    }

    private void applyBulkStyle(ClientLineFolders.Node node) {
        for (Route route : node.allRoutes()) {
            LineStyles.RouteStyle current = newRouteStyles.getOrDefault(route.getId(), ClientLineStyles.routeStyle(route.getName()));
            newRouteStyles.put(route.getId(), new LineStyles.RouteStyle(bulkShape != null ? bulkShape : current.shape(),
                    current.label(), bulkInk != null ? bulkInk : current.text()));
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        if (key >= LineStyleEditor.BASE) {
            ClientLineFolders.Node node = folder();
            LineStyleEditor editor = node.line.isEmpty() ? null : lineEditors.get(node.line);
            if (editor != null) {
                editor.onSlider(key, value);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        int delta = (int) Math.round(-vertical * 14);
        if (FlatUi.inside(mx, my, leftX, leftY, leftW, leftH)) {
            treeScroll = MathHelper.clamp(treeScroll + delta, 0, Math.max(0, treeContent - leftH));
        } else if (FlatUi.inside(mx, my, rightX, rightY, rightW, rightH)) {
            paneScroll = Math.max(0, paneScroll + delta);
        }
        return true;
    }

    @Override
    protected boolean onEscape() {
        if (moving != null) {
            moving = null;
            return true;
        }
        close();
        return true;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    // ================================================================ save

    private void saveAndClose() {
        try {
            save();
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not save the line folders", e);
            toast("Save failed — see the log", true);
            return;
        }
        close();
    }

    private void save() {
        if (treeDirty) {
            ClientLineFolders.send(working());
        }
        MinecraftClientData dashboard = MinecraftClientData.getDashboardInstance();
        Set<Long> changed = new LinkedHashSet<>();
        newColors.forEach((routeId, rgb) -> {
            Route route = route(routeId);
            if (route != null && (route.getColor() & 0xFFFFFF) != rgb) {
                route.setColor(rgb);
                changed.add(routeId);
            }
        });
        newHidden.forEach((routeId, hide) -> {
            Route route = route(routeId);
            if (route != null && route.getHidden() != hide) {
                route.setHidden(hide);
                changed.add(routeId);
            }
        });
        for (LineStyleEditor editor : lineEditors.values()) {
            editor.save(); // writes the line's colour / number into its routes (sends the others itself)
        }
        for (Route route : routes) {
            for (LineStyleEditor editor : lineEditors.values()) {
                if (editor.changed() && LineStyles.lineKey(route.getName()).equals(editor.line())) {
                    changed.add(route.getId());
                }
            }
        }
        for (long routeId : changed) {
            Route route = route(routeId);
            if (route != null) {
                InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(dashboard).addRoute(route)));
            }
        }
        newRouteStyles.forEach((routeId, style) -> {
            Route route = route(routeId);
            if (route != null) {
                ClientLineStyles.sendRoute(route.getName(), style);
            }
        });
    }

    // ================================================================ helpers

    private int para(DrawContext c, String text, int x, int y, int w, int color) {
        for (var line : textRenderer.wrapLines(Text.literal(text), Math.max(20, w))) {
            c.drawText(textRenderer, line, x, y, color, false);
            y += 10;
        }
        return y + 2;
    }

    /**
     * Dev-only (run/commands.txt): {@code #lines open [folder name part]}, then {@code select <name
     * part>}, {@code tab N}, {@code new}, {@code route <name part>}, {@code move <folder name part>}
     * (moves the selected route), {@code save}.
     */
    public static void devHook(MinecraftClient client, String cmd) {
        String[] a = cmd.trim().split("\\s+", 3);
        if (a.length >= 2 && a[1].equals("open")) {
            Screen parent = client.currentScreen;
            LineFolderScreen screen = new LineFolderScreen(0, parent);
            if (a.length > 2) {
                ClientLineFolders.Node node = screen.findByName(screen.root, a[2]);
                if (node != null) {
                    screen.selectedFolder = node.id;
                }
            }
            client.setScreen(screen);
            return;
        }
        if (a.length >= 3 && a[1].equals("expand")) {
            LineFolderScreen probe = new LineFolderScreen(0, null);
            ClientLineFolders.Node node = probe.findByName(probe.root, a[2]);
            if (node != null) {
                ClientLineFolders.expand(node.id);
            }
            return;
        }
        if (!(client.currentScreen instanceof LineFolderScreen screen) || a.length < 2) {
            return;
        }
        try {
            switch (a[1]) {
                case "select" -> {
                    ClientLineFolders.Node node = screen.findByName(screen.root, a[2]);
                    if (node != null) {
                        screen.selectedFolder = node.id;
                        screen.selectedRoute = 0;
                    }
                }
                case "tab" -> screen.tab = Integer.parseInt(a[2]);
                case "new" -> screen.newSubfolder();
                case "route" -> {
                    for (Route route : screen.routes) {
                        if (route.getName().toLowerCase(java.util.Locale.ROOT).contains(a[2].toLowerCase(java.util.Locale.ROOT))) {
                            screen.selectedRoute = route.getId();
                            break;
                        }
                    }
                }
                case "move" -> {
                    ClientLineFolders.Node target = screen.findByName(screen.root, a[2]);
                    if (target != null && screen.selectedRoute != 0) {
                        screen.moveRouteTo(screen.selectedRoute, target);
                    }
                }
                case "name" -> screen.rename(a[2]);
                case "save" -> screen.saveAndClose();
                default -> StationAnnouncer.LOGGER.info("[lines dev] unknown '{}'", cmd);
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.info("[lines dev] bad command '{}': {}", cmd, e.toString());
        }
    }

    private ClientLineFolders.Node findByName(ClientLineFolders.Node node, String part) {
        String lower = part.toLowerCase(java.util.Locale.ROOT);
        if (!node.isRoot() && node.name.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
            return node;
        }
        for (ClientLineFolders.Node child : node.folders) {
            ClientLineFolders.Node found = findByName(child, part);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
