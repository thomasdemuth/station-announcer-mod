package com.stationannouncer.mtraddon.disruption;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.PacketByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * One "service change poster" — the MTA-style planned-work / service-change
 * notice — linked to a {@link Disruption}. A disruption may own any number of
 * posters; each is edited in game and can be hung in the world on a
 * {@code service_poster} block, which keeps its own copy so the sign survives the
 * disruption being deleted.
 *
 * <p>The layout is fixed (it is what makes the family read as one set): a black
 * title bar with a logo, a grey band with the big timing line, two small date
 * lines and the affected-line bullets, a category rule, then a column of body
 * blocks, and a black footer. Only the body is free-form: an ordered list of
 * {@link Block}s, each a headline / paragraph / subhead (text with inline tokens),
 * a big arrow, a rule or a spacer.</p>
 *
 * <p><b>Inline tokens</b> inside any text: {@code {b:NAME}} a route bullet for the
 * line called NAME (its line name, before MTR's {@code ||} direction separator),
 * {@code {d:NAME}} the same as an express diamond, {@code {wc}} the wheelchair
 * symbol, {@code {<} {>} {^} {v}} small arrows. The renderer resolves colours by
 * name at draw time, so a repainted line follows along (the entrance-sign rule).</p>
 *
 * <p>Plain data, no MTR types — shared verbatim by the server store, the packets,
 * the client mirror and the block entity snapshot. Immutable; the editor works on
 * a {@link Builder}.</p>
 */
public record ServicePoster(long id, long disruptionId, String kind, String logo, String timing,
                            String date1, String date2, List<String> headerLines, String category,
                            List<Block> blocks, String footerLeft, String footerRight) {

    public enum BlockType {
        HEADLINE,
        TEXT,
        SUBHEAD,
        ARROW,
        RULE,
        SPACER;

        public static BlockType byOrdinal(int ordinal) {
            BlockType[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TEXT;
        }

        public static BlockType byName(String name) {
            if (name != null) {
                for (BlockType type : values()) {
                    if (type.name().equalsIgnoreCase(name)) {
                        return type;
                    }
                }
            }
            return TEXT;
        }

        public boolean hasText() {
            return this == HEADLINE || this == TEXT || this == SUBHEAD;
        }
    }

    /**
     * One body block. {@code text} is the (token-bearing) copy for text types;
     * {@code arg} is the direction for an arrow (0 = right, counting
     * anticlockwise in 45° steps: 1 up-right, 2 up, 3 up-left, 4 left, 5
     * down-left, 6 down, 7 down-right) and unused otherwise.
     */
    public record Block(BlockType type, String text, int arg) {
        public Block {
            text = text == null ? "" : text;
            arg = ((arg % 8) + 8) % 8;
        }

        public static Block text(BlockType type, String text) {
            return new Block(type, text, 0);
        }

        public static Block arrow(int direction) {
            return new Block(BlockType.ARROW, "", direction);
        }
    }

    // ---------------------------------------------------------------- caps

    /** Hard wire caps (the config may lower the poster count). */
    public static final int MAX_BLOCKS = 32;
    public static final int MAX_BLOCK_TEXT = 300;
    public static final int MAX_FIELD = 96;
    public static final int MAX_LOGO = 8;
    public static final int MAX_HEADER_LINES = 8;
    public static final int MAX_LINE_NAME = 64;
    public static final int MAX_SYNC_POSTERS = 256;

    public ServicePoster {
        kind = clip(kind, MAX_FIELD);
        logo = clip(logo, MAX_LOGO);
        timing = clip(timing, MAX_FIELD);
        date1 = clip(date1, MAX_FIELD);
        date2 = clip(date2, MAX_FIELD);
        category = clip(category, MAX_FIELD);
        footerLeft = clip(footerLeft, MAX_FIELD);
        footerRight = clip(footerRight, MAX_FIELD);
        List<String> lines = new ArrayList<>();
        if (headerLines != null) {
            for (String line : headerLines) {
                if (line != null && !line.isBlank() && lines.size() < MAX_HEADER_LINES) {
                    lines.add(clip(line, MAX_LINE_NAME));
                }
            }
        }
        headerLines = List.copyOf(lines);
        List<Block> body = new ArrayList<>();
        if (blocks != null) {
            for (Block block : blocks) {
                if (block != null && body.size() < MAX_BLOCKS) {
                    body.add(new Block(block.type(), clip(block.text(), MAX_BLOCK_TEXT), block.arg()));
                }
            }
        }
        blocks = List.copyOf(body);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The first headline's text, or the first text block, for list rows. */
    public String preview() {
        for (Block block : blocks) {
            if (block.type() == BlockType.HEADLINE && !block.text().isBlank()) {
                return block.text();
            }
        }
        for (Block block : blocks) {
            if (block.type().hasText() && !block.text().isBlank()) {
                return block.text();
            }
        }
        return timing.isBlank() ? kind : timing;
    }

    public ServicePoster withId(long newId) {
        return new ServicePoster(newId, disruptionId, kind, logo, timing, date1, date2, headerLines,
                category, blocks, footerLeft, footerRight);
    }

    public ServicePoster withDisruption(long newDisruptionId) {
        return new ServicePoster(id, newDisruptionId, kind, logo, timing, date1, date2, headerLines,
                category, blocks, footerLeft, footerRight);
    }

    // ---------------------------------------------------------------- JSON

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("disruptionId", disruptionId);
        json.addProperty("kind", kind);
        json.addProperty("logo", logo);
        json.addProperty("timing", timing);
        json.addProperty("date1", date1);
        json.addProperty("date2", date2);
        JsonArray lines = new JsonArray(headerLines.size());
        headerLines.forEach(lines::add);
        json.add("headerLines", lines);
        json.addProperty("category", category);
        JsonArray body = new JsonArray(blocks.size());
        for (Block block : blocks) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", block.type().name());
            entry.addProperty("text", block.text());
            entry.addProperty("arg", block.arg());
            body.add(entry);
        }
        json.add("blocks", body);
        json.addProperty("footerLeft", footerLeft);
        json.addProperty("footerRight", footerRight);
        return json;
    }

    public static ServicePoster fromJson(JsonObject json) {
        List<String> lines = new ArrayList<>();
        if (json.has("headerLines")) {
            for (JsonElement element : json.getAsJsonArray("headerLines")) {
                lines.add(element.getAsString());
            }
        }
        List<Block> body = new ArrayList<>();
        if (json.has("blocks")) {
            for (JsonElement element : json.getAsJsonArray("blocks")) {
                JsonObject entry = element.getAsJsonObject();
                body.add(new Block(BlockType.byName(str(entry, "type")), str(entry, "text"),
                        entry.has("arg") ? entry.get("arg").getAsInt() : 0));
            }
        }
        return new ServicePoster(
                json.has("id") ? json.get("id").getAsLong() : 0,
                json.has("disruptionId") ? json.get("disruptionId").getAsLong() : 0,
                str(json, "kind"), str(json, "logo"), str(json, "timing"),
                str(json, "date1"), str(json, "date2"), lines, str(json, "category"), body,
                str(json, "footerLeft"), str(json, "footerRight"));
    }

    private static String str(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    // -------------------------------------------------------------- packets

    public void write(PacketByteBuf buf) {
        buf.writeLong(id);
        buf.writeLong(disruptionId);
        buf.writeString(kind, MAX_FIELD);
        buf.writeString(logo, MAX_LOGO);
        buf.writeString(timing, MAX_FIELD);
        buf.writeString(date1, MAX_FIELD);
        buf.writeString(date2, MAX_FIELD);
        buf.writeVarInt(headerLines.size());
        for (String line : headerLines) {
            buf.writeString(line, MAX_LINE_NAME);
        }
        buf.writeString(category, MAX_FIELD);
        buf.writeVarInt(blocks.size());
        for (Block block : blocks) {
            buf.writeByte(block.type().ordinal());
            buf.writeString(block.text(), MAX_BLOCK_TEXT);
            buf.writeByte(block.arg());
        }
        buf.writeString(footerLeft, MAX_FIELD);
        buf.writeString(footerRight, MAX_FIELD);
    }

    /** Reads one poster; returns null when a count is out of range (malformed packet). */
    public static ServicePoster read(PacketByteBuf buf) {
        long id = buf.readLong();
        long disruptionId = buf.readLong();
        String kind = buf.readString(MAX_FIELD);
        String logo = buf.readString(MAX_LOGO);
        String timing = buf.readString(MAX_FIELD);
        String date1 = buf.readString(MAX_FIELD);
        String date2 = buf.readString(MAX_FIELD);
        int lineCount = buf.readVarInt();
        if (lineCount < 0 || lineCount > MAX_HEADER_LINES) {
            return null;
        }
        List<String> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            lines.add(buf.readString(MAX_LINE_NAME));
        }
        String category = buf.readString(MAX_FIELD);
        int blockCount = buf.readVarInt();
        if (blockCount < 0 || blockCount > MAX_BLOCKS) {
            return null;
        }
        List<Block> body = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            BlockType type = BlockType.byOrdinal(buf.readByte());
            String text = buf.readString(MAX_BLOCK_TEXT);
            int arg = buf.readByte();
            body.add(new Block(type, text, arg));
        }
        String footerLeft = buf.readString(MAX_FIELD);
        String footerRight = buf.readString(MAX_FIELD);
        return new ServicePoster(id, disruptionId, kind, logo, timing, date1, date2, lines, category, body,
                footerLeft, footerRight);
    }

    // -------------------------------------------------------------- builder

    /** Mutable working copy for the editor. */
    public static final class Builder {
        public long id;
        public long disruptionId;
        public String kind = "Planned Work";
        public String logo = "BT";
        public String timing = "All Times";
        public String date1 = "";
        public String date2 = "";
        public final List<String> headerLines = new ArrayList<>();
        public String category = "SERVICE CHANGE";
        public final List<Block> blocks = new ArrayList<>();
        public String footerLeft = "Subway";
        public String footerRight = "";

        public Builder() {
        }

        public Builder(ServicePoster poster) {
            id = poster.id();
            disruptionId = poster.disruptionId();
            kind = poster.kind();
            logo = poster.logo();
            timing = poster.timing();
            date1 = poster.date1();
            date2 = poster.date2();
            headerLines.addAll(poster.headerLines());
            category = poster.category();
            blocks.addAll(poster.blocks());
            footerLeft = poster.footerLeft();
            footerRight = poster.footerRight();
        }

        public ServicePoster build() {
            return new ServicePoster(id, disruptionId, kind, logo, timing, date1, date2, headerLines,
                    category, blocks, footerLeft, footerRight);
        }
    }
}
