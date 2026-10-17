package com.stationannouncer.track;

/**
 * Identity of a rail = its two node positions in MTR's canonical order (the order
 * {@code Rail} hands to {@code RailMath}: {@code Position.compareTo} = x, then y, then z).
 */
public final class TrackKey {
    private TrackKey() {
    }

    public static String of(long ax, long ay, long az, long bx, long by, long bz) {
        return ax + "," + ay + "," + az + ";" + bx + "," + by + "," + bz;
    }

    /** True when (a) comes first in MTR's canonical order — i.e. MTR's {@code a.compareTo(b) <= 0}. */
    public static boolean canonical(long ax, long ay, long az, long bx, long by, long bz) {
        if (ax != bx) {
            return ax < bx;
        }
        if (ay != by) {
            return ay < by;
        }
        return az <= bz;
    }

    /** Key for two nodes given in ANY order. */
    public static String ofUnordered(long ax, long ay, long az, long bx, long by, long bz) {
        return canonical(ax, ay, az, bx, by, bz) ? of(ax, ay, az, bx, by, bz) : of(bx, by, bz, ax, ay, az);
    }
}
