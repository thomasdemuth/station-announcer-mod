package com.stationannouncer.api;

import java.util.UUID;

/**
 * Wayfinding events, called on the SERVER THREAD. Every method has a default,
 * so implement only what you need. Exceptions thrown here are caught and logged
 * (one bad listener never stops the others).
 */
public interface WayfindingListener {
    /** A player walked into a place's arrival area ({@link PlaceView#contains}). Checked twice a second. */
    default void placeEntered(UUID player, PlaceView place) {
    }

    /** A player left a place's arrival area. */
    default void placeLeft(UUID player, PlaceView place) {
    }

    /**
     * A player walked through an Exit Marker: came within 2 blocks of it and went on
     * past it. {@code leavingStation} is true when they moved away from the station
     * (out to the street), false when they came in from the street.
     */
    default void exitUsed(UUID player, ExitView exit, boolean leavingStation) {
    }

    /** Places or exit pins were added, edited or removed (refresh any cached lists). */
    default void placesChanged() {
    }
}
