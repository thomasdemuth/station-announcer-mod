package com.stationannouncer.wayfinding;

import java.util.Locale;

/**
 * What kind of place a {@link Place} is. Drives the Map+ icon and colour, the
 * in-world pin colour, the place screen's chips and the {@code /place}
 * command's suggestions.
 *
 * <p><b>Stored by NAME</b> ({@link #id()}), never by ordinal, so the list can be
 * reordered or grown freely. An unknown id reads back as {@link #OTHER}.</p>
 *
 * <p>{@link #DISTRICT} is special on the map: no icon, the name is drawn as a
 * wide spaced neighbourhood label over its radius.</p>
 */
public enum PlaceCategory {
    LANDMARK("landmark", 0xE0A21B),
    ATTRACTION("attraction", 0xD6457A),
    PARK("park", 0x3F9A4E),
    SHOPPING("shopping", 0x8C5BD6),
    FOOD("food", 0xE0662E),
    CULTURE("culture", 0x7A5230),
    SPORTS("sports", 0x2E8FD0),
    CIVIC("civic", 0x4A6278),
    DISTRICT("district", 0x6B6B78),
    OTHER("other", 0x8A8A94);

    private static final PlaceCategory[] VALUES = values();

    private final String id;
    private final int color;

    PlaceCategory(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    /** 0xRRGGBB. */
    public int color() {
        return color;
    }

    public String translationKey() {
        return "place_category.station_announcer." + id;
    }

    public static PlaceCategory byId(String id) {
        if (id != null) {
            String key = id.trim().toLowerCase(Locale.ROOT);
            for (PlaceCategory category : VALUES) {
                if (category.id.equals(key)) {
                    return category;
                }
            }
        }
        return OTHER;
    }

    public static PlaceCategory byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : OTHER;
    }
}
