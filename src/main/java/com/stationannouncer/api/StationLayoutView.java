package com.stationannouncer.api;

import java.util.List;
import java.util.Map;

/**
 * A station's scanned walking layout (only exists after someone pressed Scan in
 * the Exit Marker's Layout tab or ran {@code /stationlayout scan}). PURE JAVA.
 *
 * @param platformStepFree platform id → reachable from the street without stairs/escalators
 * @param walks            scanned walks: exit → platform, platform → platform, opening → platform
 */
public record StationLayoutView(long stationId, String stationName, long scannedAtMillis,
                                Map<Long, Boolean> platformStepFree, List<Walk> walks, List<String> warnings) {

    /**
     * One scanned walk. Anchor ids: {@code exit:<name>} (a second marker of the same
     * exit is {@code exit:<name>#2}), {@code platform:<platformId>}, {@code opening:<n>}
     * (a street entrance the scan found without an Exit Marker).
     *
     * @param extraSeconds time on top of walking (lift rides, fare gates)
     * @param stepFree     no stairs or escalators on this walk
     * @param stepFreeAlternative when this walk needs stairs: the step-free way round, else null
     */
    public record Walk(String from, String to, double meters, double extraSeconds, boolean stepFree,
                       List<Leg> legs, Walk stepFreeAlternative) {
    }

    /** kind: walk | stairs | escalator | lift | fare | emergency; dy in blocks (+ up). */
    public record Leg(String kind, double meters, double dy) {
    }
}
