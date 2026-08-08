package com.stationannouncer.mtraddon.disruption;

import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

/**
 * Volatile holder for MTR's per-dimension {@link Simulator} list, captured at the
 * end of the {@code org.mtr.core.Main} constructor by
 * {@link com.stationannouncer.mixin.MainSimulatorsMixin}.
 *
 * <p>Why not reuse {@code DispatchRegistry}: that one is populated from the
 * webserver-start redirect, which only fires when MTR's webserver port is &gt; 0
 * <em>and</em> {@code dispatch.enabled} is set. Disruption broadcasting needs the
 * simulators regardless of any webserver, so it gets its own unconditional
 * capture.</p>
 *
 * <p><b>Threading:</b> written on the thread that constructs {@code Main} (the
 * server thread, inside MTR's server-started handler) and cleared on the server
 * thread at shutdown; read from the server thread. One volatile reference to an
 * immutable list — readers see either a complete list or {@code null}.</p>
 */
public final class MtrSimulators {
    private static volatile ObjectImmutableList<Simulator> simulators;

    private MtrSimulators() {
    }

    /** Called from the Main-constructor mixin. */
    public static void set(ObjectImmutableList<Simulator> value) {
        simulators = value;
    }

    /** Called from AddonInit's SERVER_STOPPING hook, before {@code Main.stop()}. */
    public static void clear() {
        simulators = null;
    }

    /** The live simulator list, or {@code null} when MTR has no running simulation. */
    public static ObjectImmutableList<Simulator> get() {
        return simulators;
    }

    public static boolean isAvailable() {
        ObjectImmutableList<Simulator> current = simulators;
        return current != null && !current.isEmpty();
    }
}
