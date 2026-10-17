package com.stationannouncer.client.mtraddon.cab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import com.stationannouncer.mtraddon.crew.CabFeed;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The driver's score for a timetable run (Thomas: "end of run report and live score on the
 * HUD"; penalised for leaving people on the platform). Client-side and cosmetic — nothing gates
 * on it — built from the server's slot feed (scheduled vs actual at every stop) plus what the
 * cab sees (emergency brake, stopping position) and the passenger crowd ({@link #leftBehind}).
 *
 * <p>Scoring: +10 per on-time departure (within 30 s), −1 per further 10 s late (to −10 a stop),
 * −5 for leaving more than 15 s early; +2 for a stop within half a metre of the mark, −3 for an
 * overrun of more than 2 m; −5 per emergency brake; −1 per passenger left behind. The report card
 * opens when the run ends; runs are kept in {@code config/station_announcer/run_history.json}.</p>
 */
@Environment(EnvType.CLIENT)
public final class CabScore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HISTORY = 50;

    /** One finished (or abandoned) run, as saved in the history. */
    public record Run(String label, long finishedAt, int score, int onTime, int stops, long worstLateMillis,
                      int emergencyBrakes, int perfectStops, int overruns, int leftBehind, List<String> lines) {
    }

    @Nullable
    private static String runLabel;
    private static int score;
    private static int onTime;
    private static int stops;
    private static long worstLate;
    private static int emergencyBrakes;
    private static int perfectStops;
    private static int overruns;
    private static int leftBehind;
    private static final List<String> LINES = new ArrayList<>();
    private static boolean[] departureScored = new boolean[0];
    private static boolean wasEmergency;
    /** An emergency stop was already counted since the train last stood still. */
    private static boolean ebThisStop;
    private static final double EB_MIN_KPH = 5;
    /** MTR applied EB on its own since the last stand (an automatic stop, not the driver's). */
    private static boolean mtrForcedStop;
    @Nullable
    private static net.minecraft.client.option.KeyBinding brakeKey;

    /** The driver is holding MTR's brake key (an EB they applied themselves). */
    private static boolean brakeKeyHeld() {
        if (brakeKey == null) {
            for (net.minecraft.client.option.KeyBinding binding : MinecraftClient.getInstance().options.allKeys) {
                if (binding.getTranslationKey().equals("key.mtr.train_brake")) {
                    brakeKey = binding;
                }
            }
        }
        return brakeKey != null && brakeKey.isPressed();
    }
    private static boolean wasAtPlatform;
    private static boolean lastRunning;

    private CabScore() {
    }

    /** The live score while a run is scored, else null (the HUD hides the score then). */
    @Nullable
    public static Integer live() {
        return runLabel == null ? null : score;
    }

    /** Passengers still on the platform when the doors closed (reported by the crowd). */
    public static void leftBehind(int count) {
        if (runLabel != null && count > 0) {
            leftBehind += count;
            score -= count;
            LINES.add(count + " left on the platform  −" + count);
        }
    }

    /** Called with each new snapshot (client thread, a few times a second). */
    static void update(CabSnapshot snap, @Nullable CabFeed.Slot slot) {
        boolean running = slot != null && slot.state() == CabFeed.SLOT_RUNNING && CrewView.isDriver();
        if (running && runLabel == null) {
            start(slot);
        }
        if (runLabel == null) {
            lastRunning = false;
            return;
        }
        if (running) {
            scoreDepartures(slot);
        }
        // Emergency stops (the driver's own EB, a vigilance trip or a passenger alarm): one per
        // stop, and only when the train was moving. EB held at a stand (the usual way to park, and
        // what the alarm/vigilance clamp leaves applied until released) and pumping the brake handle
        // during one stop are not separate emergencies — the first run on the rig counted 12.
        boolean emergency = snap.power <= -8;
        boolean vigilanceOrAlarm = CrewView.vigilanceState() == com.stationannouncer.mtraddon.crew.CrewProtocol.VIGIL_TRIPPED
                || CrewView.incident() == 4;
        if (emergency && !brakeKeyHeld() && !vigilanceOrAlarm && snap.speedKph > EB_MIN_KPH) {
            // MTR itself applies EB (−8) to a manual train that would run past a reversing point and
            // stops it exactly there (Vehicle.simulateMoving, 4.0.1): the driver did not brake.
            mtrForcedStop = true;
        }
        if (emergency && !wasEmergency && !ebThisStop && snap.speedKph > EB_MIN_KPH && (brakeKeyHeld() || vigilanceOrAlarm)) {
            ebThisStop = true;
            emergencyBrakes++;
            score -= 5;
            String why = CrewView.vigilanceState() == com.stationannouncer.mtraddon.crew.CrewProtocol.VIGIL_TRIPPED ? " (vigilance)"
                    : CrewView.incident() == 4 ? " (passenger alarm)" : "";
            LINES.add("Emergency brake" + why + "  −5");
            StationAnnouncer.LOGGER.info("[score] emergency brake{} at {} km/h", why, String.format("%.1f", snap.speedKph));
        }
        boolean standing = snap.speedKph < 0.5;
        wasEmergency = emergency;
        // Stopping position at each platform arrival.
        if (snap.atPlatform && !wasAtPlatform && snap.markerActive) {
            double off = snap.markerDistance;
            boolean automatic = mtrForcedStop || !snap.manual;
            StationAnnouncer.LOGGER.info("[score] stop at {}: {} m from the mark{}", snap.nextName, String.format("%.2f", off),
                    automatic ? (mtrForcedStop ? " (MTR forced the stop)" : " (ATO)") : "");
            if (automatic) {
                // MTR (forced stop / ATO) put it on the mark: no points either way.
            } else if (Math.abs(off) <= 0.5) {
                perfectStops++;
                score += 2;
                LINES.add("Perfect stop at " + snap.nextName + "  +2");
            } else if (off < -2) {
                overruns++;
                score -= 3;
                LINES.add("Overran " + snap.nextName + " by " + Math.round(-off) + " m  −3");
            }
        }
        wasAtPlatform = snap.atPlatform;
        if (standing) {
            ebThisStop = false; // at a stand: the next EB application is a new emergency
            mtrForcedStop = false;
        }
        boolean ended = slot == null || slot.state() == CabFeed.SLOT_ENDED || !CrewView.isDriver();
        if (lastRunning && ended) {
            finish();
        }
        lastRunning = running;
    }

    private static void start(CabFeed.Slot slot) {
        runLabel = slot.label();
        score = 0;
        onTime = 0;
        stops = 0;
        worstLate = 0;
        emergencyBrakes = 0;
        perfectStops = 0;
        overruns = 0;
        leftBehind = 0;
        LINES.clear();
        departureScored = new boolean[slot.rows().size()];
        wasEmergency = false;
        ebThisStop = false;
        mtrForcedStop = false;
        wasAtPlatform = false;
    }

    private static void scoreDepartures(CabFeed.Slot slot) {
        List<CabFeed.Row> rows = slot.rows();
        if (departureScored.length != rows.size()) {
            departureScored = new boolean[rows.size()];
        }
        for (int i = 0; i < rows.size(); i++) {
            CabFeed.Row row = rows.get(i);
            if (departureScored[i] || row.actualDeparture() == 0) {
                continue;
            }
            departureScored[i] = true;
            if (row.actualDeparture() == -2) {
                // Slid past the platform while trying to stop (the server's slow run-through).
                overruns++;
                score -= 3;
                LINES.add("Overran " + row.name() + ", did not stop  −3");
                continue;
            }
            if (row.actualDeparture() < 0) {
                score -= 10;
                LINES.add(row.name() + "  skipped  −10");
                continue;
            }
            stops++;
            long late = row.actualDeparture() - row.scheduledDeparture();
            worstLate = Math.max(worstLate, late);
            int points;
            if (late < -15_000) {
                points = -5;
            } else if (late <= 30_000) {
                points = 10;
                onTime++;
            } else {
                points = (int) -Math.min(10, (late - 30_000) / 10_000 + 1);
            }
            score += points;
            LINES.add(String.format("%s  %s  %s%d", row.name(), CabHud.deviation(late), points >= 0 ? "+" : "", points));
        }
    }

    private static void finish() {
        Run run = new Run(runLabel, System.currentTimeMillis(), score, onTime, stops, worstLate, emergencyBrakes,
                perfectStops, overruns, leftBehind, List.copyOf(LINES));
        runLabel = null;
        lastRunning = false;
        save(run);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) {
            client.setScreen(new CabReportScreen(run));
        }
    }

    private static Path historyFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("run_history.json");
    }

    static List<Run> history() {
        try {
            Path file = historyFile();
            if (Files.isRegularFile(file)) {
                List<Run> runs = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Run>>() { }.getType());
                return runs == null ? new ArrayList<>() : new ArrayList<>(runs);
            }
        } catch (IOException | RuntimeException e) {
            StationAnnouncer.LOGGER.warn("Could not read run history: {}", e.toString());
        }
        return new ArrayList<>();
    }

    private static void save(Run run) {
        List<Run> runs = history();
        runs.add(0, run);
        while (runs.size() > MAX_HISTORY) {
            runs.remove(runs.size() - 1);
        }
        try {
            Path file = historyFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(runs), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not save run history: {}", e.toString());
        }
    }

    static void clear() {
        runLabel = null;
        lastRunning = false;
    }
}
