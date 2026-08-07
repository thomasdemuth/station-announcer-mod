package com.stationannouncer.mtr;

/**
 * The five NYC PIDS variants. Wall and standing units are vertical two-block
 * multiblocks (half=lower/upper, screen spanning 0.5–2.5 blocks above the
 * floor); the hanging clock is a horizontal two-block multiblock
 * (side=left/right, two blocks wide). Shapes are given as if facing north.
 */
public enum PidsStyle {
    /** Wall-mounted route map of the next arriving train (1 px deep). */
    ROUTE_MAP_WALL(1, false),
    /** Wall-mounted list of next departures + custom "Happening now" text. */
    DEPARTURES_WALL(4, false),
    /** Free-standing, double-sided route map (legs + 3 px panel). */
    ROUTE_MAP_STANDING(1, false),
    /** Free-standing, double-sided departure list. */
    DEPARTURES_STANDING(4, false),
    /** Ceiling-hung, double-sided B-Division countdown clock, two blocks wide. */
    HANGING(3, true),
    /** Shorter hanging clock showing only the next departure. */
    HANGING_MINI(1, true);

    public final int maxArrivals;
    /** true = horizontal (left/right) multiblock; false = vertical (lower/upper). */
    public final boolean horizontal;

    PidsStyle(int maxArrivals, boolean horizontal) {
        this.maxArrivals = maxArrivals;
        this.horizontal = horizontal;
    }

    public boolean isRouteMap() {
        return this == ROUTE_MAP_WALL || this == ROUTE_MAP_STANDING;
    }

    public boolean isHanging() {
        return this == HANGING || this == HANGING_MINI;
    }

    /** Only the departure lists carry the "Happening now" section. */
    public boolean isDepartures() {
        return this == DEPARTURES_WALL || this == DEPARTURES_STANDING;
    }

    /** Standing and hanging units render their screen on both faces. */
    public boolean isDoubleSided() {
        return this != ROUTE_MAP_WALL && this != DEPARTURES_WALL;
    }

    private boolean isWall() {
        return this == ROUTE_MAP_WALL || this == DEPARTURES_WALL;
    }

    /** Outline box for the given part, {x1,y1,z1,x2,y2,z2} in 16ths, facing north. */
    public double[] shape(boolean primary) {
        if (horizontal) {
            return new double[]{0, 0, 6.5, 16, 16, 9.5};
        }
        double z1 = isWall() ? 15 : 6.5;
        double z2 = isWall() ? 16 : 9.5;
        return primary
                ? new double[]{0, isWall() ? 8 : 0, z1, 16, 16, z2}   // lower: panel starts 8 px up (legs for standing)
                : new double[]{0, 0, z1, 16, 24, z2};                  // upper: panel + 8 px overhang
    }
}
