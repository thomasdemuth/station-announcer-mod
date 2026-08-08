package com.stationannouncer.mtraddon.dispatch;

import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

/**
 * Volatile holder for the live MTR webserver session: the per-dimension
 * {@link Simulator} list (index-aligned with MTR's own {@code dimension} query-param
 * convention) and the webserver port. Populated by the {@code Main}-constructor mixin
 * ({@link com.stationannouncer.mixin.MainMixin} via {@link DispatchWebSetup}) right
 * before MTR starts Jetty; cleared by the addon's SERVER_STOPPING hook
 * ({@link com.stationannouncer.mtraddon.AddonInit}).
 *
 * <p><b>Threading:</b> written on whatever thread constructs {@code org.mtr.core.Main}
 * (the server thread, inside MTR's server-started handler) and on the server thread at
 * shutdown; read from Jetty worker threads (servlets) and the dispatch streamer thread.
 * The state is a single volatile reference to an immutable record — readers either see
 * a complete session or {@code null} (servlets answer 503 in the latter case).</p>
 */
public final class DispatchRegistry {
    private static volatile State state;

    private DispatchRegistry() {
    }

    /** Immutable session snapshot. */
    public record State(ObjectImmutableList<Simulator> simulators, int port) {
    }

    static void set(ObjectImmutableList<Simulator> simulators, int port) {
        state = new State(simulators, port);
    }

    /** Called from AddonInit's SERVER_STOPPING hook ({@code Main.stop()} follows shortly after). */
    public static void clear() {
        state = null;
    }

    public static boolean isActive() {
        return state != null;
    }

    /** The current session, or null when no webserver is running (servlets must 503). */
    public static State get() {
        return state;
    }

    /** Simulator for a dimension index, or null when out of range / no session. */
    public static Simulator simulator(int dimensionIndex) {
        State current = state;
        if (current == null || dimensionIndex < 0 || dimensionIndex >= current.simulators().size()) {
            return null;
        }
        return current.simulators().get(dimensionIndex);
    }
}
