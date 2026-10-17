package com.stationannouncer.mtraddon.crew;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.util.Identifier;

/**
 * Wire format and shared constants of the train-crew feature (MANUAL_DRIVING_PLAN.md
 * phase 1: driver / guard roles, OPTO, buzzer, horn, vigilance). Shared by the
 * server ({@link CrewService}) and the client ({@code client.mtraddon.crew.CrewClient}),
 * so this class must stay free of client-only and simulator-only types.
 *
 * <h2>C2S</h2>
 * <ul>
 *   <li>{@link #ROLE_C2S} {@code crew_role}: {@code long vehicleId} (0 = not riding
 *       anything), {@code byte role} ({@link #ROLE_NONE} / {@link #ROLE_DRIVER} /
 *       {@link #ROLE_GUARD} — what the held MTR key allows), {@code varint
 *       vigilanceSeconds} (the player's vigilance preference, 0 = off), {@code String
 *       trainType} (≤ {@link #MAX_TRAIN_TYPE}: car 0's vehicle id, picks the horn).
 *       Sent when any field changes and as a heartbeat every
 *       {@link #HEARTBEAT_MILLIS} while riding with a role.</li>
 *   <li>{@link #BUZZ_C2S} {@code crew_buzz}: empty. One press of the buzzer.</li>
 *   <li>{@link #HORN_C2S} {@code crew_horn}: {@code boolean on}. Driver only; the
 *       server picks the horn from the train type.</li>
 *   <li>{@link #VIGIL_ACK_C2S} {@code crew_vigil_ack}: empty. Vigilance acknowledge.</li>
 * </ul>
 *
 * <h2>S2C</h2>
 * <ul>
 *   <li>{@link #STATUS_S2C} {@code crew_status}: {@code long vehicleId} (0 = no crew
 *       role), {@code byte myRole}, {@code boolean hasDriver}, {@code boolean hasGuard},
 *       {@code byte flags} ({@link #FLAG_GUARD_READY} | {@link #FLAG_DRIVER_READY} |
 *       {@link #FLAG_DRIVER_WAITS} | {@link #FLAG_GUARD_WAITS} | {@link #FLAG_GUARD_OWNS_DOORS}
 *       | {@link #FLAG_DOORS_THIS_STOP} | {@link #FLAG_DOORS_OPEN}), {@code byte vigilanceState}, {@code varint
 *       vigilanceMillisLeft} (time to the trip while {@link #VIGIL_WARNING}, else 0),
 *       {@code byte horn}, {@code byte incident} (0 none, 1 door fault, 2 held, 3 sick passenger,
 *       4 passenger alarm), {@code varint incidentMillisLeft} (0 = open-ended). Sent to each crew
 *       member whenever any of it changes.</li>
 *   <li>{@link #BUZZ_S2C} {@code crew_buzz_in}: {@code byte fromRole}. One buzz from
 *       the other end of the train; the client groups presses into codes.</li>
 *   <li>{@link #HORN_S2C} {@code crew_horn_play}: {@code varint entityId} (the driver),
 *       {@code byte horn}, {@code boolean on}. To every player within the horn range.</li>
 *   <li>{@link #MESSAGE_S2C} {@code crew_message}: {@code byte code} ({@link #MSG_DRIVER_TAKEN}
 *       …). Short one-off notices the client turns into translated action-bar text.</li>
 * </ul>
 */
public final class CrewProtocol {
    public static final Identifier ROLE_C2S = StationAnnouncer.id("crew_role");
    public static final Identifier BUZZ_C2S = StationAnnouncer.id("crew_buzz");
    public static final Identifier HORN_C2S = StationAnnouncer.id("crew_horn");
    public static final Identifier VIGIL_ACK_C2S = StationAnnouncer.id("crew_vigil_ack");
    public static final Identifier STATUS_S2C = StationAnnouncer.id("crew_status");
    public static final Identifier BUZZ_S2C = StationAnnouncer.id("crew_buzz_in");
    public static final Identifier HORN_S2C = StationAnnouncer.id("crew_horn_play");
    public static final Identifier MESSAGE_S2C = StationAnnouncer.id("crew_message");
    /** {@link CabFeed}: the line ahead + the claimed slot, twice a second to each crew member. */
    public static final Identifier FEED_S2C = StationAnnouncer.id("crew_feed");
    /** {@code long manualSidingId}: list the slots this manual siding's driver may take. */
    public static final Identifier SLOT_LIST_C2S = StationAnnouncer.id("slot_list");
    /** {@code long manualSidingId, long now, varint n, n × option} — see {@link CrewService}. */
    public static final Identifier SLOT_LIST_S2C = StationAnnouncer.id("slot_options");
    /** {@code long manualSidingId, long autoSidingId, varint index, long slotYard}. */
    public static final Identifier SLOT_CLAIM_C2S = StationAnnouncer.id("slot_claim");
    /** {@code long manualSidingId}: give a not-yet-started slot back. */
    public static final Identifier SLOT_RELEASE_C2S = StationAnnouncer.id("slot_release");
    /** {@code byte horn}: use this horn for the crewed train's type (op {@code editPermissionLevel}). */
    public static final Identifier HORN_SET_C2S = StationAnnouncer.id("crew_horn_set");
    /** Empty: the driver finished (or skipped) the startup ritual; the train may take power. */
    public static final Identifier STARTUP_C2S = StationAnnouncer.id("crew_startup");

    public static final byte ROLE_NONE = 0;
    public static final byte ROLE_DRIVER = 1;
    public static final byte ROLE_GUARD = 2;

    public static final byte VIGIL_OFF = 0;
    public static final byte VIGIL_OK = 1;
    public static final byte VIGIL_WARNING = 2;
    public static final byte VIGIL_TRIPPED = 3;

    /** The guard buzzed "ready" after closing the doors at this stop. */
    public static final int FLAG_GUARD_READY = 1;
    /** The driver buzzed since the train came to a stand. */
    public static final int FLAG_DRIVER_READY = 1 << 1;
    /** Server setting: the driver may not take power until the guard is ready. */
    public static final int FLAG_DRIVER_WAITS = 1 << 2;
    /** Server setting: the guard may not close the doors until the driver buzzed. */
    public static final int FLAG_GUARD_WAITS = 1 << 3;
    /** Server setting: with a guard aboard, only the guard works the doors. */
    public static final int FLAG_GUARD_OWNS_DOORS = 1 << 4;
    /** The doors were opened at this stop (the interlocks only apply then). */
    public static final int FLAG_DOORS_THIS_STOP = 1 << 5;
    /** The doors are open (or opening) right now. */
    public static final int FLAG_DOORS_OPEN = 1 << 6;
    /** The driver has started the train up (or startup is not required). */
    public static final int FLAG_STARTED_UP = 1 << 7;

    public static final byte MSG_DRIVER_TAKEN = 1;
    public static final byte MSG_GUARD_TAKEN = 2;
    public static final byte MSG_WAIT_FOR_GUARD = 3;
    public static final byte MSG_WAIT_FOR_DRIVER = 4;
    public static final byte MSG_GUARD_HAS_DOORS = 5;
    public static final byte MSG_VIGIL_TRIPPED = 6;
    public static final byte MSG_VIGIL_RESET = 7;
    public static final byte MSG_NO_PARTNER = 8;
    public static final byte MSG_SLOT_CLAIMED = 9;
    public static final byte MSG_SLOT_TAKEN = 10;
    public static final byte MSG_SLOT_GONE = 11;
    public static final byte MSG_SLOT_FROM_SIDING = 12;
    public static final byte MSG_SLOT_NO_SHOW = 13;
    public static final byte MSG_SLOT_RELEASED = 14;
    public static final byte MSG_STARTUP_NEEDED = 15;
    public static final byte MSG_STARTED_UP = 16;
    public static final byte MSG_INCIDENT_DOOR_FAULT = 17;
    public static final byte MSG_INCIDENT_HELD = 18;
    public static final byte MSG_INCIDENT_SICK = 19;
    public static final byte MSG_INCIDENT_ALARM = 20;
    public static final byte MSG_INCIDENT_CLEARED = 21;
    public static final byte MSG_HORN_SET = 22;
    public static final byte MSG_HORN_NOT_ALLOWED = 23;
    /** The manual siding has no generated path (no stop times): its train would go straight back into the depot. */
    public static final byte MSG_SLOT_NO_PATH = 24;

    /** Horn options, index = the {@code byte horn} on the wire; sound id {@code station_announcer:horn_<name>}. */
    public static final String[] HORNS = {"nyc", "lirr", "euro", "steam"};
    public static final byte DEFAULT_HORN = 0;

    public static final String SOUND_BUZZER = "crew_buzzer";
    public static final String SOUND_VIGIL_WARN = "crew_vigilance_warn";
    public static final String SOUND_VIGIL_TRIP = "crew_vigilance_trip";

    public static final int MAX_TRAIN_TYPE = 128;
    public static final long HEARTBEAT_MILLIS = 5_000;
    /** A role claim not refreshed for this long is dropped (3 missed heartbeats). */
    public static final long ROLE_TIMEOUT_MILLIS = 16_000;
    public static final int MIN_VIGIL_SECONDS = 10;
    public static final int MAX_VIGIL_SECONDS = 300;

    private CrewProtocol() {
    }

    public static byte clampHorn(int horn) {
        return horn >= 0 && horn < HORNS.length ? (byte) horn : DEFAULT_HORN;
    }

    public static Identifier hornSound(int horn) {
        return StationAnnouncer.id("horn_" + HORNS[clampHorn(horn)]);
    }
}
