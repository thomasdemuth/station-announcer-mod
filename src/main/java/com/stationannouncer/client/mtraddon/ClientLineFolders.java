package com.stationannouncer.client.mtraddon;

import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.LineFolders;
import com.stationannouncer.mtraddon.LineStyles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import org.mtr.core.data.Route;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSet;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client side of {@link LineFolders}: the synced state, the folder TREE built over the
 * routes the client knows ({@link #build}), and the folder view inside MTR's dashboard
 * Routes list (driven by {@code DashboardScreenFoldersMixin}): folder rows ("▸ Line 4 (5)")
 * whose draw-area icon expands / collapses them and whose edit icon opens the
 * {@link LineFolderScreen}; routes inside an open folder are indented by
 * {@code DashboardListItemMixin}. A search in the list shows the plain flat list.
 */
@Environment(EnvType.CLIENT)
public final class ClientLineFolders {
    private static volatile LineFolders.State state = LineFolders.State.EMPTY;
    private static int version;
    /** Open folders in the dashboard (this session). */
    private static final Set<Long> EXPANDED = new HashSet<>();
    /** Route id → indent depth while the dashboard shows the folder view. */
    private static Map<Long, Integer> indents = Map.of();
    /** Folder row id → folder, for the rows on screen. */
    private static Map<Long, Node> rows = Map.of();

    private ClientLineFolders() {
    }

    /** A folder of the tree (or the root). {@code virtual} = an automatic folder not stored yet. */
    public static final class Node {
        public final long id;
        public String name;
        public final Node parent;
        public final String line;
        public final int order;
        public final boolean virtual;
        public final List<Node> folders = new ArrayList<>();
        public final List<Route> routes = new ArrayList<>();

        Node(long id, String name, Node parent, String line, int order, boolean virtual) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.line = line;
            this.order = order;
            this.virtual = virtual;
        }

        public boolean isRoot() {
            return parent == null;
        }

        /** Every route in this folder and its subfolders. */
        public List<Route> allRoutes() {
            List<Route> all = new ArrayList<>(routes);
            folders.forEach(folder -> all.addAll(folder.allRoutes()));
            return all;
        }

        /** The most common route colour inside (RGB), grey when empty. */
        public int color() {
            Map<Integer, Integer> counts = new HashMap<>();
            for (Route route : allRoutes()) {
                counts.merge(route.getColor() & 0xFFFFFF, 1, Integer::sum);
            }
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0x808080);
        }

        public int depth() {
            int depth = 0;
            for (Node node = parent; node != null; node = node.parent) {
                depth++;
            }
            return depth;
        }

        public String path() {
            return parent == null || parent.isRoot() ? name : parent.path() + " › " + name;
        }

        public Node find(long folderId) {
            if (id == folderId) {
                return this;
            }
            for (Node folder : folders) {
                Node found = folder.find(folderId);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        /** The folder holding a route, or null. */
        public Node folderOf(long routeId) {
            for (Route route : routes) {
                if (route.getId() == routeId) {
                    return this;
                }
            }
            for (Node folder : folders) {
                Node found = folder.folderOf(routeId);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    public static LineFolders.State state() {
        return state;
    }

    public static int version() {
        return version;
    }

    /** The id an automatic (not yet stored) folder of a line gets: stable, negative. */
    public static long virtualId(String line) {
        return -1L - (line.hashCode() & 0xFFFFFFFFL);
    }

    public static long newId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    /** The tree over these routes with the current state. */
    public static Node build(Collection<Route> routes) {
        return build(routes, state);
    }

    public static Node build(Collection<Route> routes, LineFolders.State current) {
        Node root = new Node(0, "All lines", null, "", 0, false);
        Map<Long, Node> nodes = new LinkedHashMap<>();
        // Stored folders, parents first (sorted by depth so a parent always exists).
        List<LineFolders.Folder> folders = new ArrayList<>(current.folders().values());
        folders.sort((a, b) -> Integer.compare(LineFolders.depth(current.folders(), a.id()),
                LineFolders.depth(current.folders(), b.id())));
        for (LineFolders.Folder folder : folders) {
            Node parent = folder.parent() == 0 ? root : nodes.getOrDefault(folder.parent(), root);
            Node node = new Node(folder.id(), folder.name(), parent, folder.line(), folder.order(), false);
            nodes.put(folder.id(), node);
            parent.folders.add(node);
        }
        Map<String, Node> byLine = new HashMap<>();
        for (Node node : nodes.values()) {
            if (!node.line.isEmpty()) {
                byLine.putIfAbsent(node.line, node);
            }
        }
        // Lines that two or more otherwise-unplaced routes share become automatic folders.
        Map<String, Integer> lineCounts = new HashMap<>();
        for (Route route : routes) {
            if (!current.routes().containsKey(route.getId())) {
                lineCounts.merge(LineStyles.lineKey(route.getName()), 1, Integer::sum);
            }
        }
        Map<Long, Integer> orders = new HashMap<>();
        for (Route route : routes) {
            LineFolders.Placement placement = current.routes().get(route.getId());
            Node target;
            if (placement != null) {
                target = placement.folder() == 0 ? root : nodes.getOrDefault(placement.folder(), root);
                orders.put(route.getId(), placement.order());
            } else {
                String line = LineStyles.lineKey(route.getName());
                target = byLine.get(line);
                if (target == null && !line.isEmpty() && lineCounts.getOrDefault(line, 0) >= 2) {
                    target = new Node(virtualId(line), line, root, line, Integer.MAX_VALUE / 2, true);
                    root.folders.add(target);
                    byLine.put(line, target);
                }
                if (target == null) {
                    target = root;
                }
            }
            target.routes.add(route);
        }
        sort(root, orders);
        return root;
    }

    private static void sort(Node node, Map<Long, Integer> orders) {
        node.folders.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : a.name.compareToIgnoreCase(b.name));
        node.routes.sort((a, b) -> {
            int oa = orders.getOrDefault(a.getId(), Integer.MAX_VALUE);
            int ob = orders.getOrDefault(b.getId(), Integer.MAX_VALUE);
            return oa != ob ? Integer.compare(oa, ob) : a.getName().compareToIgnoreCase(b.getName());
        });
        node.folders.forEach(child -> sort(child, orders));
    }

    /** Every route on the dashboard (all transport modes). */
    public static List<Route> allRoutes() {
        try {
            return new ArrayList<>(MinecraftClientData.getDashboardInstance().routes);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Sends a whole new state (the server validates, saves and resyncs everyone). */
    public static void send(LineFolders.State next) {
        String json = next.toJson().toString();
        if (json.length() > LineFolders.MAX_JSON) {
            StationAnnouncer.LOGGER.warn("Line folders too large to send ({} chars)", json.length());
            return;
        }
        state = next; // optimistic: the resync confirms it
        version++;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(json, LineFolders.MAX_JSON);
        ClientPlayNetworking.send(LineFolders.UPDATE_C2S, buf);
    }

    // ------------------------------------------------------------ dashboard

    /**
     * Called for every {@code DashboardList.setData(ObjectSet…)} MTR's dashboard makes each
     * tick. For the Routes list (every item a route) and no search, hands MTR an ordered list
     * with folder rows instead and returns true; otherwise false (MTR's own call proceeds).
     */
    public static boolean dashboardList(DashboardList list, ObjectSet<DashboardListItem> data, String search,
                                        boolean find, boolean drawArea, boolean edit, boolean sort, boolean add, boolean delete) {
        if (!AddonClientConfig.get().dashboardFolders || data.isEmpty() || (search != null && !search.isEmpty())) {
            indents = Map.of();
            return false;
        }
        List<Route> routes = new ArrayList<>();
        Map<Long, DashboardListItem> items = new HashMap<>();
        for (DashboardListItem item : data) {
            if (!(item.data instanceof Route route)) {
                indents = Map.of();
                return false;
            }
            routes.add(route);
            items.put(route.getId(), item);
        }
        Node root = build(routes);
        ObjectArrayList<DashboardListItem> out = new ObjectArrayList<>();
        Map<Long, Integer> nextIndents = new HashMap<>();
        Map<Long, Node> nextRows = new HashMap<>();
        emit(root, out, items, nextIndents, nextRows, 0);
        indents = nextIndents;
        rows = nextRows;
        list.setData(out, find, drawArea, edit, sort, add, delete);
        return true;
    }

    private static void emit(Node node, ObjectArrayList<DashboardListItem> out, Map<Long, DashboardListItem> items,
                             Map<Long, Integer> indents, Map<Long, Node> rows, int depth) {
        String pad = "  ".repeat(depth);
        for (Node folder : node.folders) {
            int count = folder.allRoutes().size();
            if (count == 0 && folder.folders.isEmpty()) {
                continue;
            }
            boolean open = EXPANDED.contains(folder.id);
            out.add(new DashboardListItem(folder.id, pad + (open ? "− " : "+ ") + folder.name + "  (" + count + ")", folder.color()));
            rows.put(folder.id, folder);
            if (open) {
                emit(folder, out, items, indents, rows, depth + 1);
            }
        }
        for (Route route : node.routes) {
            DashboardListItem item = items.get(route.getId());
            if (item != null) {
                out.add(item);
                if (depth > 0) {
                    indents.put(route.getId(), depth);
                }
            }
        }
    }

    /** The indent prefix for a route row in the folder view ("" outside it). */
    public static String indent(DashboardListItem item) {
        if (!(item.data instanceof Route route)) {
            return "";
        }
        Integer depth = indents.get(route.getId());
        return depth == null ? "" : "  ".repeat(depth - 1) + "  └ ";
    }

    /** A dashboard row's draw-area icon: on a folder row, open / close it. */
    public static boolean onToggle(DashboardListItem item) {
        Node folder = item.data == null ? rows.get(item.id) : null;
        if (folder == null) {
            return false;
        }
        if (!EXPANDED.remove(folder.id)) {
            EXPANDED.add(folder.id);
        }
        return true;
    }

    /** A dashboard row's edit icon: on a folder row, open the folder screen. */
    public static boolean onEdit(net.minecraft.client.gui.screen.Screen dashboard, DashboardListItem item) {
        Node folder = item.data == null ? rows.get(item.id) : null;
        if (folder == null) {
            return false;
        }
        MinecraftClient.getInstance().setScreen(new LineFolderScreen(folder.id, dashboard));
        return true;
    }

    /** Any other icon on a folder row (delete, find): swallowed. */
    public static boolean isFolderRow(DashboardListItem item) {
        return item.data == null && rows.containsKey(item.id);
    }

    public static void expand(long folderId) {
        EXPANDED.add(folderId);
    }

    // ------------------------------------------------------------ sync

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LineFolders.SYNC_S2C, (client, handler, buf, sender) -> {
            String json = buf.readString(LineFolders.MAX_JSON);
            LineFolders.State next;
            try {
                next = LineFolders.State.fromJson(JsonParser.parseString(json).getAsJsonObject());
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                state = next;
                version++;
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            state = LineFolders.State.EMPTY;
            indents = Map.of();
            rows = Map.of();
            version++;
        });
    }
}
