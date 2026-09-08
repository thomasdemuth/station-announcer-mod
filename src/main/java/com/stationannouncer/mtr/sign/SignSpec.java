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
 *   <li>{@code ARROW} — {@code num}: direction, 0 = right counting anticlockwise
 *       in 45° steps; {@code arg}: {@code "left"} / {@code "right"} pins it to
 *       that panel edge (the Vignelli rule), empty = inline.</li>
 *   <li>{@code STATION_NAME} — {@code text}: override, empty = the MTR station
 *       here; {@code num} 1 = upper-case.</li>
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
public record SignSpec(Style style, List<Row> rows) {

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
        CENTER;

        public static Align byName(String name) {
            return "CENTER".equalsIgnoreCase(name) ? CENTER : LEFT;
        }
    }

    public enum TileType {
        BULLETS,
        TEXT,
        ARROW,
        STATION_NAME,
        EXIT,
        DESTINATION,
        SPACER;

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

    /** Exit tile flags. */
    public static final int EXIT_WORD = 1;
    public static final int EXIT_STREETS = 2;
    public static final int EXIT_NAME = 4;

    public record Tile(TileType type, String text, String arg, int num, List<String> routes) {
        public Tile {
            text = clip(text, MAX_TEXT);
            arg = clip(arg, MAX_ARG);
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
            return new Tile(type, newText, arg, num, routes);
        }

        public Tile withArg(String newArg) {
            return new Tile(type, text, newArg, num, routes);
        }

        public Tile withNum(int newNum) {
            return new Tile(type, text, arg, newNum, routes);
        }

        public Tile withRoutes(List<String> newRoutes) {
            return new Tile(type, text, arg, num, newRoutes);
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
    public static final int MAX_TILES = 8;
    public static final int MAX_TEXT = 96;
    public static final int MAX_ARG = 64;
    public static final int MAX_ROUTES = 8;
    public static final int MAX_ROUTE_NAME = 64;
    /** Hard cap on the JSON string on the wire and in NBT. */
    public static final int MAX_JSON = 6000;

    public static final SignSpec EMPTY = new SignSpec(Style.BLACK, List.of());

    public SignSpec {
        style = style == null ? Style.BLACK : style;
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
        return json;
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
                                t.has("num") ? t.get("num").getAsInt() : 0, routes));
                    }
                }
                rows.add(new Row(Align.byName(str(entry, "align")), tiles));
            }
        }
        return new SignSpec(Style.byName(str(json, "style")), rows);
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
        }

        public SignSpec build() {
            List<Row> built = new ArrayList<>(rows.size());
            for (RowDraft row : rows) {
                built.add(new Row(row.align, row.tiles));
            }
            return new SignSpec(style, built);
        }
    }
}
