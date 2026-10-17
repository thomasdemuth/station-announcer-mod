package com.stationannouncer.client.mtraddon.cab;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the cab HUD draws, computed a few times a second by {@link CabData}
 * (or filled with sample values by {@link CabDevHooks} for headless screenshots).
 * Built once, then only read: the renderer never recomputes anything per frame
 * beyond blink phases and the departure countdown.
 *
 * <p>Distances are metres, speeds km/h, times epoch millis on the SERVER clock
 * (MTR's arrival times; {@link #now} is the matching "now").</p>
 */
@Environment(EnvType.CLIENT)
public final class CabSnapshot {
    public static final int ASPECT_NONE = -1;
    public static final int ASPECT_CLEAR = 0;
    public static final int ASPECT_CAUTION = 1;
    public static final int ASPECT_DANGER = 2;

    /** Server-clock "now" at compute time. */
    public long now;
    /** Guard view: no driving controls, doors and buzzes instead. */
    public boolean guardView;

    // ---- destination panel
    public int routeColor = 0xFF808080;
    public String routeLabel = "?";
    public String destination = "";
    public String nextName = "";
    /** Metres to the next stop's stopping point; ignored while {@link #atPlatform}. */
    public double nextDistance;
    public boolean atPlatform;
    public String afterName = "";
    /** Metres to the stop after next, or negative when unknown. */
    public double afterDistance = -1;

    // ---- drive panel
    public double speedKph;
    public int limitKph;
    /** The next different limit ahead, 0 = none within the lookahead. */
    public int nextLimitKph;
    public double nextLimitDistance;
    public int aspect = ASPECT_NONE;
    public double signalDistance;
    /** The stop-marker row is live (approaching or at a platform). */
    public boolean markerActive;
    /** Metres still to go to the stopping mark; negative = overrun. */
    public double markerDistance;
    /** MTR power level: 1..7 power, 0 neutral, -1..-7 brake, -8 emergency. */
    public int power;
    public boolean manual;
    public boolean doorsOpen;
    public boolean obstructed;
    public boolean hold;

    // ---- run panel
    /** False = no timetable for this vehicle (rows reserved, greyed). */
    public boolean hasTimetable;
    public String runLabel = "";
    /** Lateness at the next stop, positive = late. Only with {@link #deviationKnown}. */
    public long deviationMillis;
    public boolean deviationKnown;
    public final List<RunRow> run = new ArrayList<>();
    /** Expected departure from the current platform, 0 = not at a platform / unknown. */
    public long departAt;
    /** Dwell length for the departure bar (arrival → departure). */
    public long dwellMillis;
    /** A slot is claimed but the train has not left its siding yet; {@link #yardTime} = leave by. */
    public boolean slotPending;
    public long yardTime;

    /**
     * One stop of the run. {@code scheduled}/{@code expected} are arrival times
     * (departure for the stop the train stands at); 0 = unknown.
     */
    public record RunRow(String name, long scheduled, long expected, boolean past, boolean current) {
    }
}
