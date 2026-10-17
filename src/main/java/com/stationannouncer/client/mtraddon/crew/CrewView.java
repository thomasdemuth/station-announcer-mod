package com.stationannouncer.client.mtraddon.crew;

import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * Read-only view of the crew status for the cab HUD (the state itself stays
 * package-private in {@link CrewState}). Client thread only.
 */
@Environment(EnvType.CLIENT)
public final class CrewView {
    private CrewView() {
    }

    /** This player holds a crew role on some vehicle. */
    public static boolean active() {
        return CrewState.active();
    }

    public static long vehicleId() {
        return CrewState.vehicleId;
    }

    public static boolean isDriver() {
        return CrewState.active() && CrewState.myRole == CrewProtocol.ROLE_DRIVER;
    }

    public static boolean isGuard() {
        return CrewState.active() && CrewState.myRole == CrewProtocol.ROLE_GUARD;
    }

    public static boolean hasDriver() {
        return CrewState.active() && CrewState.hasDriver;
    }

    public static boolean hasGuard() {
        return CrewState.active() && CrewState.hasGuard;
    }

    public static boolean flag(int bit) {
        return CrewState.active() && CrewState.flag(bit);
    }

    public static byte vigilanceState() {
        return CrewState.active() ? CrewState.vigilanceState : CrewProtocol.VIGIL_OFF;
    }

    public static long vigilanceLeft(long now) {
        return CrewState.vigilanceLeft(now);
    }

    /** Presses in the last buzz code from the other cab (0 = none showing). */
    public static int buzzCount() {
        return CrewState.buzzCount;
    }

    public static byte buzzRole() {
        return CrewState.buzzRole;
    }

    /** 0 none, 1 door fault, 2 held by control, 3 sick passenger, 4 passenger alarm. */
    public static byte incident() {
        return CrewState.active() ? CrewState.incident : 0;
    }

    /** Seconds left on the incident (0 = open-ended / none). */
    public static long incidentSecondsLeft(long now) {
        if (CrewState.incidentMillisLeft <= 0) {
            return 0;
        }
        return Math.max(0, (CrewState.incidentMillisLeft - (now - CrewState.statusReceivedAt) + 999) / 1000);
    }

    /** The horn this train uses (index into CrewProtocol.HORNS). */
    public static byte horn() {
        return CrewState.horn;
    }

    public static Text buzzerKeyName() {
        return CrewClient.buzzerKeyName();
    }

    public static Text vigilanceKeyName() {
        return CrewClient.vigilanceKeyName();
    }
}
