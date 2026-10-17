package com.stationannouncer.client.track;

/**
 * What every editor view (Schematic, 3D, World) exposes so the editor can draw its handles as 2D
 * overlays on top of ANY view and turn mouse positions back into world positions — one set of tools
 * works in all three views.
 *
 * Coordinates: world x/y/z exactly like {@code TrackPath.pointAt} (node centres at +0.5, y = rail
 * height); screen = GUI (scaled) pixels of the current screen.
 */
public interface TrackViewport {
    /**
     * World → screen. Writes {screenX, screenY, depth} into {@code out}; returns false when the point is
     * behind the camera / outside the view frustum's near plane (don't draw or hit-test it then).
     * depth only orders handles (smaller = nearer).
     */
    boolean project(double x, double y, double z, double[] out);

    /**
     * Screen → world: where the ray under the mouse hits the horizontal plane {@code y = planeY}.
     * Writes {x, z} into {@code out}; false if the ray misses (parallel / behind).
     */
    boolean groundAt(double screenX, double screenY, double planeY, double[] out);

    /** True when this view is drawn top-down (Schematic): handles never need depth sorting there. */
    default boolean isTopDown() {
        return false;
    }
}
