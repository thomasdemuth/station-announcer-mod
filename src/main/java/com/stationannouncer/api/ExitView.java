package com.stationannouncer.api;

import java.util.List;

/**
 * Where one MTR station exit really is: an Exit Marker block pinned to it.
 * A station exit may have several markers (two staircases, one exit name).
 * PURE JAVA snapshot.
 *
 * @param stationId    MTR station id
 * @param stationName  first language of the MTR station name ("" if MTR has not been asked yet)
 * @param name         MTR's exit name, e.g. "A" or "B2"
 * @param destinations the exit's street signs from MTR, in order (may be empty)
 * @param dimension    world registry id, e.g. {@code minecraft:overworld}
 */
public record ExitView(long stationId, String stationName, String name, List<String> destinations,
                       String dimension, int x, int y, int z) {
}
