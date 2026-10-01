package com.stationannouncer.api;

/**
 * A named place (landmark, park, district…) made with a Place Marker block or
 * {@code /place}. Immutable snapshot; PURE JAVA (no Minecraft types), so
 * mods built with any mappings can use it.
 *
 * @param id          stable 8-hex id — store THIS in quests, names can be edited
 * @param category    landmark, attraction, park, shopping, food, culture, sports,
 *                    civic, district or other
 * @param dimension   world registry id, e.g. {@code minecraft:overworld}
 * @param radius      the place's arrival radius in blocks (0 = just the spot)
 * @param hidden      kept off Map+ (still a valid quest target — secret hunts)
 * @param blockMarker true for a Place Marker block, false for a {@code /place} place
 */
public record PlaceView(String id, String name, String category, String description, String dimension,
                        int x, int y, int z, int radius, boolean hidden, boolean blockMarker) {

    /** The smallest arrival radius a player is held to, so a 0-radius point is reachable. */
    public static final int MIN_ARRIVAL_RADIUS = 3;

    /** Horizontal arrival radius in blocks: {@code max(radius, 3)}. */
    public int arrivalRadius() {
        return Math.max(radius, MIN_ARRIVAL_RADIUS);
    }

    /**
     * Is a position (e.g. a player's feet) inside this place's arrival area? Same
     * dimension, horizontally within {@link #arrivalRadius()} of the block centre,
     * and vertically within {@code max(radius, 4)} blocks.
     */
    public boolean contains(String dimension, double px, double py, double pz) {
        if (!this.dimension.equals(dimension)) {
            return false;
        }
        double dx = px - (x + 0.5);
        double dz = pz - (z + 0.5);
        double r = arrivalRadius();
        return dx * dx + dz * dz <= r * r && Math.abs(py - y) <= Math.max(radius, 4);
    }
}
