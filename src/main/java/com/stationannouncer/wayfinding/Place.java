package com.stationannouncer.wayfinding;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * A named location on the map: a landmark, a park, a district. Immutable; the
 * store swaps whole records.
 *
 * <p>Two kinds share this record: <b>block places</b> ({@link #blockKey} set)
 * live where a Place Marker block stands and move with it; <b>command places</b>
 * ({@code /place add}) have no block. Either way the store is the source of
 * truth — a marker block only carries a copy so structure pastes keep their
 * data.</p>
 *
 * @param id        opaque stable id (8 hex), what the API and other mods key on
 * @param dimension world registry key, e.g. {@code minecraft:overworld}
 * @param mtrDim    MTR's id for the same world ({@code minecraft/overworld}) —
 *                  what Map+ filters on; blank until resolved on the server
 * @param radius    arrival radius in blocks (0 = the point itself); districts
 *                  also draw it as their extent
 * @param hidden    kept, but not drawn on Map+ or offered in its search
 * @param blockKey  {@code dimension|x|y|z} of the marker block, null for a
 *                  command place
 */
public record Place(String id, String name, PlaceCategory category, String description,
                    String dimension, String mtrDim, int x, int y, int z, int radius, boolean hidden,
                    @Nullable String blockKey, String createdBy, long updated) {
    public static final int MAX_NAME = 48;
    public static final int MAX_DESCRIPTION = 240;
    public static final int MAX_RADIUS = 128;

    public Place {
        name = clean(name, MAX_NAME);
        description = description == null ? "" : description.length() > MAX_DESCRIPTION
                ? description.substring(0, MAX_DESCRIPTION) : description;
        category = category == null ? PlaceCategory.OTHER : category;
        dimension = dimension == null ? "minecraft:overworld" : dimension;
        mtrDim = mtrDim == null ? "" : mtrDim;
        radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        createdBy = createdBy == null ? "" : createdBy;
    }

    public static String clean(String text, int max) {
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > max ? value.substring(0, max).trim() : value;
    }

    public boolean isBlock() {
        return blockKey != null;
    }

    public Place withName(String value) {
        return new Place(id, value, category, description, dimension, mtrDim, x, y, z, radius, hidden, blockKey, createdBy, System.currentTimeMillis());
    }

    public Place withCategory(PlaceCategory value) {
        return new Place(id, name, value, description, dimension, mtrDim, x, y, z, radius, hidden, blockKey, createdBy, System.currentTimeMillis());
    }

    public Place withDescription(String value) {
        return new Place(id, name, category, value, dimension, mtrDim, x, y, z, radius, hidden, blockKey, createdBy, System.currentTimeMillis());
    }

    public Place withRadius(int value) {
        return new Place(id, name, category, description, dimension, mtrDim, x, y, z, value, hidden, blockKey, createdBy, System.currentTimeMillis());
    }

    public Place withHidden(boolean value) {
        return new Place(id, name, category, description, dimension, mtrDim, x, y, z, radius, value, blockKey, createdBy, System.currentTimeMillis());
    }

    public Place withPosition(String dim, String mtrDimension, int px, int py, int pz, @Nullable String key) {
        return new Place(id, name, category, description, dim, mtrDimension, px, py, pz, radius, hidden, key, createdBy, System.currentTimeMillis());
    }

    public Place withId(String value) {
        return new Place(value, name, category, description, dimension, mtrDim, x, y, z, radius, hidden, blockKey, createdBy, updated);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("category", category.id());
        json.addProperty("description", description);
        json.addProperty("dimension", dimension);
        json.addProperty("mtrDim", mtrDim);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("radius", radius);
        json.addProperty("hidden", hidden);
        if (blockKey != null) {
            json.addProperty("block", blockKey);
        }
        json.addProperty("createdBy", createdBy);
        json.addProperty("updated", updated);
        return json;
    }

    public static Place fromJson(JsonObject json) {
        return new Place(
                json.get("id").getAsString(),
                json.has("name") ? json.get("name").getAsString() : "",
                PlaceCategory.byId(json.has("category") ? json.get("category").getAsString() : ""),
                json.has("description") ? json.get("description").getAsString() : "",
                json.has("dimension") ? json.get("dimension").getAsString() : "minecraft:overworld",
                json.has("mtrDim") ? json.get("mtrDim").getAsString() : "",
                json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt(),
                json.has("radius") ? json.get("radius").getAsInt() : 0,
                json.has("hidden") && json.get("hidden").getAsBoolean(),
                json.has("block") ? json.get("block").getAsString() : null,
                json.has("createdBy") ? json.get("createdBy").getAsString() : "",
                json.has("updated") ? json.get("updated").getAsLong() : 0L);
    }
}
