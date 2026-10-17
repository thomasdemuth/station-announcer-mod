package com.stationannouncer.client.mtraddon.crew;

import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The client's mirror of its own crew status: the last {@link CrewProtocol#STATUS_S2C}
 * frame, the buzz codes received from the other end of the train, and the local
 * vigilance countdown. Plain static fields, touched only on the client thread (the
 * packet receivers hop over with {@code client.execute}), cleared on disconnect.
 *
 * <p><b>Buzz codes</b>: the server forwards every press as its own
 * {@link CrewProtocol#BUZZ_S2C}; presses arriving within {@link #BUZZ_GROUP_MILLIS}
 * of each other form one code ("Guard ••" = two buzzes). The growing code shows live
 * while the group is open and stays on the panel for {@link #BUZZ_SHOW_MILLIS}
 * after it closes.</p>
 */
@Environment(EnvType.CLIENT)
final class CrewState {
    /** Presses closer together than this belong to the same code. */
    static final long BUZZ_GROUP_MILLIS = 900;
    /** How long a finished code stays on the panel. */
    static final long BUZZ_SHOW_MILLIS = 4000;
    /** More dots than this read as noise, not a code; the panel caps the count. */
    static final int MAX_BUZZ_DOTS = 9;

    // ---- last STATUS frame (vehicleId 0 = no crew role) ----
    static long vehicleId;
    static byte myRole = CrewProtocol.ROLE_NONE;
    static boolean hasDriver;
    static boolean hasGuard;
    static int flags;
    static byte vigilanceState = CrewProtocol.VIGIL_OFF;
    static int vigilanceMillisLeft;
    /** Wall-clock time the status arrived, the base of the local vigilance countdown. */
    static long statusReceivedAt;
    static byte horn = CrewProtocol.DEFAULT_HORN;
    /** Current incident on the train (see IncidentEngine) and the time left when the status arrived. */
    static byte incident;
    static int incidentMillisLeft;

    // ---- buzz codes ----
    static byte buzzRole = CrewProtocol.ROLE_NONE;
    static int buzzCount;
    static long buzzLastAt;
    /** True while more presses may still join {@link #buzzCount}. */
    static boolean buzzOpen;

    private CrewState() {
    }

    static boolean active() {
        return vehicleId != 0 && myRole != CrewProtocol.ROLE_NONE;
    }

    static boolean flag(int bit) {
        return (flags & bit) != 0;
    }

    /** Milliseconds to the vigilance trip, counted down locally from the last status. */
    static long vigilanceLeft(long now) {
        if (vigilanceState != CrewProtocol.VIGIL_WARNING) {
            return 0;
        }
        return Math.max(0, vigilanceMillisLeft - (now - statusReceivedAt));
    }

    /** One buzz from the other end; starts a new code when the role changes or the group has closed. */
    static void addBuzz(byte fromRole, long now) {
        if (!buzzOpen || fromRole != buzzRole || now - buzzLastAt > BUZZ_GROUP_MILLIS) {
            buzzRole = fromRole;
            buzzCount = 0;
        }
        buzzCount = Math.min(MAX_BUZZ_DOTS, buzzCount + 1);
        buzzLastAt = now;
        buzzOpen = true;
    }

    /** Closes the current code once no press has followed for {@link #BUZZ_GROUP_MILLIS}. */
    static void tickBuzz(long now) {
        if (buzzOpen && now - buzzLastAt > BUZZ_GROUP_MILLIS) {
            buzzOpen = false;
        }
        if (!buzzOpen && buzzCount > 0 && now - buzzLastAt > BUZZ_GROUP_MILLIS + BUZZ_SHOW_MILLIS) {
            buzzCount = 0;
        }
    }

    static void clear() {
        incident = 0;
        incidentMillisLeft = 0;
        vehicleId = 0;
        myRole = CrewProtocol.ROLE_NONE;
        hasDriver = false;
        hasGuard = false;
        flags = 0;
        vigilanceState = CrewProtocol.VIGIL_OFF;
        vigilanceMillisLeft = 0;
        statusReceivedAt = 0;
        horn = CrewProtocol.DEFAULT_HORN;
        buzzRole = CrewProtocol.ROLE_NONE;
        buzzCount = 0;
        buzzLastAt = 0;
        buzzOpen = false;
    }
}
