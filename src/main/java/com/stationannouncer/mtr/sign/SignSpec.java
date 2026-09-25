package com.stationannouncer.mtr.sign;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.PacketByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * One face of an MTA-style sign: a black (or 1970-style white-with-black-band)
 * panel holding one to four ROWS, each an ordered run of TILES — the modules
 * the real NYCTA sign system is assembled from: route bullets, text, an arrow,
 * a pictogram, and the live ones the station data fills in (station name,
 * an exit, a line with its destination).
 *
 * <p>Plain data, no MTR types: the block entity stores it as JSON, the editor
 * works on a {@link Draft}, and the client resolves every live tile at draw
 * time (station names, exits and route colours are looked up by NAME, so a
 * renamed or repainted line follows along — the entrance-sign rule).</p>
 *
 * <p>Tile fields by type (unused fields stay empty / 0):</p>
 * <ul>
 *   <li>{@code BULLETS} — {@code routes}: line names, drawn as bullets in order;
 *       a {@code d:} prefix draws that line as an express diamond. {@code arg}
 *       {@code "auto"} = every line calling at this station.</li>
 *   <li>{@code TEXT} — {@code text}: copy with inline tokens ({@code {b:LINE}}
 *       bullet, {@code {d:LINE}} diamond, {@code {wc}}, {@code {<} {^} {v} {>}});
 *       a newline makes a second line. {@code num}: 0 normal, 1 small, 2 large.</li>
 *   <li>{@code TEXT} {@code arg}: a colour FIELD behind the copy ("red", "yellow",
 *       "green", "blue", "white", "black", "orange" or {@code #RRGGBB}; empty = none) —
 *       the red "No exit", the yellow part-time note. Inline {@code {s:KEY}} draws a
 *       pictogram (keys in the client's SignSymbols).</li>
 *   <li>{@code RULE} — a thin vertical divider between groups of modules.</li>
 *   <li>{@code BADGE} — {@code text}: a short label in a rounded box, {@code arg}: its
 *       colour (same names) — other operators and bus routes.</li>
 *   <li>{@code ARROW} — {@code num}: direction, 0 = right counting anticlockwise
 *       in 45° steps; {@code arg}: {@code "left"} / {@code "right"} pins it to
 *       that panel edge (the Vignelli rule), empty = inline.</li>
 *   <li>{@code STATION_NAME} — {@code text}: override, empty = the MTR station
 *       here; {@code num} bit flags: 1 upper-case, 2 stacked on two lines (the
 *       column plates: "14 / Street"); {@code arg}: wheelchair "" auto / "wc" / "nowc".</li>
 *   <li>{@code EXIT} — {@code text}: the MTR exit name (as typed in the station's
 *       exit list; empty = the station's first exit); {@code arg}: corner note
 *       ("NE corner") — MTR has no corner concept; {@code num} bit flags:
 *       1 the word "Exit", 2 the street names (MTR exit destinations),
 *       4 the exit name in a box.</li>
 *   <li>{@code DESTINATION} — {@code routes[0]}: a full MTR route name (line
 *       and direction); {@code num}: 0 "to &lt;terminus&gt;", 1 the route's
 *       direction wording (after MTR's {@code ||}), 2 custom {@code text}.</li>
 *   <li>{@code SPACER} — {@code num}: width in canvas units.</li>
 * </ul>
 */
public record SignSpec(Style style, List<Row> rows, Panel panel) {

    public SignSpec(Style style, List<Row> rows) {
        this(style, rows, Panel.DEFAULT);
    }

    /** Largest panel size / offset in canvas units (64 per block). */
    public static final float MAX_PANEL = 1024;
    public static final float MIN_SCALE = 0.1f;
    public static final float MAX_SCALE = 8;

    /**
     * Per-face panel overrides: the painted panel's size in canvas units (0 =
     * the block's own plate), its offset from the plate centre, and a scale
     * applied to every tile's content. What lets a sign be bigger, smaller or
     * elsewhere than the block it sits on.
     */
    public record Panel(float width, float height, float dx, float dy, float scale) {
        public static final Panel DEFAULT = new Panel(0, 0, 0, 0, 1);

        public Panel {
            width = clampF(width, 0, MAX_PANEL);
            height = clampF(height, 0, MAX_PANEL);
            dx = clampF(dx, -MAX_PANEL, MAX_PANEL);
            dy = clampF(dy, -MAX_PANEL, MAX_PANEL);
            scale = scale <= 0 ? 1 : clampF(scale, MIN_SCALE, MAX_SCALE);
        }

        public boolean isDefault() {
            return width == 0 && height == 0 && dx == 0 && dy == 0 && scale == 1;
        }
    }

    private static float clampF(float value, float min, float max) {
        return Float.isNaN(value) ? 0 : Math.max(min, Math.min(max, value));
    }

    public enum Style {
        /** 1989 standard: white on black, with the thin white line under the top edge. */
        BLACK,
        /** 1970 Unimark original: white panel with a black band along the top. */
        WHITE_BAND,
        /** Plain black board, no stripe — the elevated-station name boards. */
        PLAIN;

        public static Style byName(String name) {
            for (Style style : values()) {
                if (style.name().equalsIgnoreCase(name)) {
                    return style;
                }
            }
            return BLACK;
        }

        public static Style byOrdinal(int ordinal) {
            Style[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BLACK;
        }
    }

    public enum Align {
        LEFT,
        CENTER,
        RIGHT;

        public static Align byName(String name) {
            return "CENTER".equalsIgnoreCase(name) ? CENTER : "RIGHT".equalsIgnoreCase(name) ? RIGHT : LEFT;
        }
    }

    /**
     * Where a tile sits in its row. A row is three zones — tiles flush to the
     * left edge, tiles centred on the panel, tiles flush to the right edge —
     * and {@code AUTO} sends the tile to the zone the ROW's alignment names
     * (an arrow with the older {@code arg} "left"/"right" pin still goes to
     * that edge). Multi-line tiles align their lines the same way.
     */
    public enum Place {
        AUTO,
        LEFT,
        CENTER,
        RIGHT;

        public static Place byName(String name) {
            if (name != null) {
                for (Place place : values()) {
                    if (place.name().equalsIgnoreCase(name)) {
                        return place;
                    }
                }
            }
            return AUTO;
        }
    }

    public enum TileType {
        BULLETS,
        TEXT,
        ARROW,
        STATION_NAME,
        EXIT,
        DESTINATION,
        SPACER,
        /** A thin vertical line between groups of modules (new types go at the END: the editor indexes by ordinal). */
        RULE,
        /** Short text in a rounded colour box: another operator or a bus route ("LIRR", "M15 SBS"). */
        BADGE;

        public static TileType byOrdinal(int ordinal) {
            TileType[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TEXT;
        }

        public static TileType byName(String name) {
            if (name != null) {
                for (TileType type : values()) {
                    if (type.name().equalsIgnoreCase(name)) {
                        return type;
                    }
                }
            }
            return TEXT;
        }
    }

    /** Station-name tile flags. */
    public static final int NAME_UPPER = 1;
    public static final int NAME_STACKED = 2;

    /** Exit tile flags. */
    public static final int EXIT_WORD = 1;
    public static final int EXIT_STREETS = 2;
    public static final int EXIT_NAME = 4;

    /**
     * {@code scale} multiplies this tile's module size (1 = the row's),
     * {@code dx}/{@code dy} move it in canvas units from where the row put it
     * (dragging a tile in the editor writes these), and {@code place} picks
     * its zone in the row.
     */
    public record Tile(TileType type, String text, String arg, int num, List<String> routes,
                       float scale, float dx, float dy, Place place) {
        public Tile(TileType type, String text, String arg, int num, List<String> routes) {
            this(type, text, arg, num, routes, 1, 0, 0, Place.AUTO);
        }

        public Tile(TileType type, String text, String arg, int num, List<String> routes,
                    float scale, float dx, float dy) {
            this(type, text, arg, num, routes, scale, dx, dy, Place.AUTO);
        }

        public Tile {
            place = place == null ? Place.AUTO : place;
            text = clip(text, MAX_TEXT);
            arg = clip(arg, MAX_ARG);
            scale = scale <= 0 ? 1 : clampF(scale, MIN_SCALE, MAX_SCALE);
            dx = clampF(dx, -MAX_PANEL, MAX_PANEL);
            dy = clampF(dy, -MAX_PANEL, MAX_PANEL);
            List<String> list = new ArrayList<>();
            if (routes != null) {
                for (String route : routes) {
                    if (route != null && !route.isBlank() && list.size() < MAX_ROUTES) {
                        list.add(clip(route, MAX_ROUTE_NAME));
                    }
                }
            }
            routes = List.copyOf(list);
        }

        public static Tile of(TileType type) {
            return switch (type) {
                case EXIT -> new Tile(type, "", "", EXIT_WORD | EXIT_STREETS, List.of());
                case SPACER -> new Tile(type, "", "", 8, List.of());
                case ARROW -> new Tile(type, "", "right", 0, List.of());
                case BADGE -> new Tile(type, "LIRR", "blue", 0, List.of());
                default -> new Tile(type, "", "", 0, List.of());
            };
        }

        public static Tile text(String text) {
            return new Tile(TileType.TEXT, text, "", 0, List.of());
        }

        public static Tile bullets(List<String> lines) {
            return new Tile(TileType.BULLETS, "", "", 0, lines);
        }

        public static Tile arrow(int direction, String side) {
            return new Tile(TileType.ARROW, "", side, direction, List.of());
        }

        public Tile withText(String newText) {
            return new Tile(type, newText, arg, num, routes, scale, dx, dy, place);
        }

        public Tile withArg(String newArg) {
            return new Tile(type, text, newArg, num, routes, scale, dx, dy, place);
        }

        public Tile withNum(int newNum) {
            return new Tile(type, text, arg, newNum, routes, scale, dx, dy, place);
        }

        public Tile withRoutes(List<String> newRoutes) {
            return new Tile(type, text, arg, num, newRoutes, scale, dx, dy, place);
        }

        public Tile withScale(float newScale) {
            return new Tile(type, text, arg, num, routes, newScale, dx, dy, place);
        }

        public Tile withOffset(float newDx, float newDy) {
            return new Tile(type, text, arg, num, routes, scale, newDx, newDy, place);
        }

        public Tile withPlace(Place newPlace) {
            return new Tile(type, text, arg, num, routes, scale, dx, dy, newPlace);
        }

        public boolean hasFlag(int flag) {
            return (num & flag) != 0;
        }
    }

    public record Row(Align align, List<Tile> tiles) {
        public Row {
            List<Tile> list = new ArrayList<>();
            if (tiles != null) {
                for (Tile tile : tiles) {
                    if (tile != null && list.size() < MAX_TILES) {
                        list.add(tile);
                    }
                }
            }
            tiles = List.copyOf(list);
            align = align == null ? Align.LEFT : align;
        }

        public static Row of(Tile... tiles) {
            return new Row(Align.LEFT, List.of(tiles));
        }

        public static Row centered(Tile... tiles) {
            return new Row(Align.CENTER, List.of(tiles));
        }
    }

    // ---------------------------------------------------------------- caps

    public static final int MAX_ROWS = 4;
    public static final int MAX_TILES = 12;
    public static final int MAX_TEXT = 96;
    public static final int MAX_ARG = 64;
    public static final int MAX_ROUTES = 8;
    public static final int MAX_ROUTE_NAME = 64;
    /** Hard cap on the JSON string on the wire and in NBT. */
    public static final int MAX_JSON = 9000;

    public static final SignSpec EMPTY = new SignSpec(Style.BLACK, List.of());

    public SignSpec {
        style = style == null ? Style.BLACK : style;
        panel = panel == null ? Panel.DEFAULT : panel;
        List<Row> list = new ArrayList<>();
        if (rows != null) {
            for (Row row : rows) {
                if (row != null && list.size() < MAX_ROWS) {
                    list.add(row);
                }
            }
        }
        rows = List.copyOf(list);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public boolean isEmpty() {
        for (Row row : rows) {
            if (!row.tiles().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- JSON

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("style", style.name());
        JsonArray rowArray = new JsonArray(rows.size());
        for (Row row : rows) {
            JsonObject entry = new JsonObject();
            if (row.align() != Align.LEFT) {
                entry.addProperty("align", row.align().name());
            }
            JsonArray tileArray = new JsonArray(row.tiles().size());
            for (Tile tile : row.tiles()) {
                JsonObject t = new JsonObject();
                t.addProperty("type", tile.type().name());
                if (!tile.text().isEmpty()) {
                    t.addProperty("text", tile.text());
                }
                if (!tile.arg().isEmpty()) {
                    t.addProperty("arg", tile.arg());
                }
                if (tile.num() != 0) {
                    t.addProperty("num", tile.num());
                }
                if (tile.scale() != 1) {
                    t.addProperty("scale", tile.scale());
                }
                if (tile.dx() != 0) {
                    t.addProperty("dx", tile.dx());
                }
                if (tile.dy() != 0) {
                    t.addProperty("dy", tile.dy());
                }
                if (tile.place() != Place.AUTO) {
                    t.addProperty("place", tile.place().name());
                }
                if (!tile.routes().isEmpty()) {
                    JsonArray routes = new JsonArray(tile.routes().size());
                    tile.routes().forEach(routes::add);
                    t.add("routes", routes);
                }
                tileArray.add(t);
            }
            entry.add("tiles", tileArray);
            rowArray.add(entry);
        }
        json.add("rows", rowArray);
        if (!panel.isDefault()) {
            JsonObject p = new JsonObject();
            p.addProperty("w", panel.width());
            p.addProperty("h", panel.height());
            p.addProperty("x", panel.dx());
            p.addProperty("y", panel.dy());
            p.addProperty("s", panel.scale());
            json.add("panel", p);
        }
        return json;
    }

    private static float num(JsonObject json, String key, float fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsFloat() : fallback;
    }

    public static SignSpec fromJson(JsonObject json) {
        List<Row> rows = new ArrayList<>();
        if (json.has("rows")) {
            for (JsonElement rowElement : json.getAsJsonArray("rows")) {
                JsonObject entry = rowElement.getAsJsonObject();
                List<Tile> tiles = new ArrayList<>();
                if (entry.has("tiles")) {
                    for (JsonElement tileElement : entry.getAsJsonArray("tiles")) {
                        JsonObject t = tileElement.getAsJsonObject();
                        List<String> routes = new ArrayList<>();
                        if (t.has("routes")) {
                            for (JsonElement route : t.getAsJsonArray("routes")) {
                                routes.add(route.getAsString());
                            }
                        }
                        tiles.add(new Tile(TileType.byName(str(t, "type")), str(t, "text"), str(t, "arg"),
                                t.has("num") ? t.get("num").getAsInt() : 0, routes,
                                num(t, "scale", 1), num(t, "dx", 0), num(t, "dy", 0), Place.byName(str(t, "place"))));
                    }
                }
                rows.add(new Row(Align.byName(str(entry, "align")), tiles));
            }
        }
        Panel panel = Panel.DEFAULT;
        if (json.has("panel") && json.get("panel").isJsonObject()) {
            JsonObject p = json.getAsJsonObject("panel");
            panel = new Panel(num(p, "w", 0), num(p, "h", 0), num(p, "x", 0), num(p, "y", 0), num(p, "s", 1));
        }
        return new SignSpec(Style.byName(str(json, "style")), rows, panel);
    }

    private static String str(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    // -------------------------------------------------------------- packets

    public void write(PacketByteBuf buf) {
        buf.writeString(toJson().toString(), MAX_JSON);
    }

    /** Reads one spec; malformed JSON yields an empty sign rather than an exception. */
    public static SignSpec read(PacketByteBuf buf) {
        String json = buf.readString(MAX_JSON);
        try {
            return fromJson(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return EMPTY;
        }
    }

    // --------------------------------------------------------------- draft

    /** Mutable working copy for the editor: rows of mutable tile lists. */
    public static final class Draft {
        public Style style = Style.BLACK;
        public final List<RowDraft> rows = new ArrayList<>();
        public float panelWidth;
        public float panelHeight;
        public float panelDx;
        public float panelDy;
        public float panelScale = 1;

        public static final class RowDraft {
            public Align align = Align.LEFT;
            public final List<Tile> tiles = new ArrayList<>();

            public RowDraft() {
            }

            public RowDraft(Row row) {
                align = row.align();
                tiles.addAll(row.tiles());
            }
        }

        public Draft() {
        }

        public Draft(SignSpec spec) {
            style = spec.style();
            for (Row row : spec.rows()) {
                rows.add(new RowDraft(row));
            }
            panelWidth = spec.panel().width();
            panelHeight = spec.panel().height();
            panelDx = spec.panel().dx();
            panelDy = spec.panel().dy();
            panelScale = spec.panel().scale();
        }

        public SignSpec build() {
            List<Row> built = new ArrayList<>(rows.size());
            for (RowDraft row : rows) {
                built.add(new Row(row.align, row.tiles));
            }
            return new SignSpec(style, built, new Panel(panelWidth, panelHeight, panelDx, panelDy, panelScale));
        }
    }
}
