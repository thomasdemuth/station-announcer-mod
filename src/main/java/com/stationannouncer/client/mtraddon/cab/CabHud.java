package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The cab HUD (MANUAL_DRIVING_PLAN.md §9, the layout Thomas approved after three
 * mock-up rounds; mock-up at {@code tools/hud_mock/index.html}). Three corners, the
 * middle of the screen stays clear for the track:
 *
 * <ul>
 *   <li><b>Top left — destination:</b> line bullet, destination; ▸ next stop + distance
 *       (a stop mark while standing at a platform). Expanded adds the stop after.</li>
 *   <li><b>Bottom left — drive:</b> signal head + distance beside the speed, current and
 *       next limit. Expanded adds three FIXED rows that grey out instead of disappearing:
 *       stop marker, power/brake ladder, lamps (doors, guard ready, hold). The guard sees a
 *       doors-and-buzzer panel here instead.</li>
 *   <li><b>Bottom right — run:</b> lateness, clock, the next two stops (scheduled → expected)
 *       and the departure timer row, always present, grey between stations. Expanded lists
 *       the whole run.</li>
 *   <li><b>Top centre — events:</b> one line, only while something needs the player.</li>
 * </ul>
 *
 * <p>Everything is laid out in GUI pixels (times {@code cabScale}) with the vanilla font,
 * flat fills and tiny pixel sprites, so it reads like the rest of the Minecraft HUD. Bottom
 * panels grow upward from a fixed bottom edge and every panel keeps its width when it
 * collapses, so toggling never moves what the player is reading.</p>
 *
 * <p>Data comes from {@link CabData} at the classic HUD's update rate; per frame this only
 * draws the cached snapshot (plus blink phases and the departure countdown).</p>
 */
@Environment(EnvType.CLIENT)
public final class CabHud {
    static final int TEXT = 0xFFFFFFFF;
    static final int DIM = 0xFFA0A8B0;
    static final int FAINT = 0xFF5A636C;
    static final int OFF = 0xFF2C3238;
    static final int PANEL = 0xB80E1014;
    static final int EDGE = 0x1AFFFFFF;
    static final int BLUE = 0xFF8FD3FF;
    static final int GREEN = 0xFF6CE38F;
    static final int AMBER = 0xFFFFC04D;
    static final int RED = 0xFFFF5B4A;
    static final int ORANGE = 0xFFFFAD5C;

    private static final int DEST_W = 148;
    private static final int DRIVE_W = 164;
    private static final int RUN_W = 168;
    private static final int MARGIN = 4;
    private static final long BLINK_MILLIS = 250;

    private static KeyBinding detailKey;
    private static KeyBinding menuKey;
    @Nullable
    private static CabSnapshot snapshot;
    /** Set by {@link CabDevHooks}: drawn instead of live data (headless screenshots). */
    @Nullable
    static CabSnapshot demo;
    @Nullable
    static String demoEvent;
    static int demoEventColor;
    private static long nextUpdate;
    /** The driven vehicle, so the speed can be read every frame (Thomas: as live as MTR's own readout). */
    @Nullable
    private static org.mtr.mod.data.VehicleExtension liveVehicle;
    /** Wall time the snapshot was computed, to run the departure countdown between updates. */
    private static long snapshotWall;
    /** A crew/cab notice shown in the event line instead of the action bar (which sits on the panels). */
    @Nullable
    private static String notice;
    private static long noticeUntil;
    private static final long NOTICE_MILLIS = 3_000;

    private CabHud() {
    }

    public static void register() {
        detailKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.cab_detail", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J,
                "key.station_announcer.category"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.cab_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
                "key.station_announcer.category"));
        CabFeedClient.register();
        CabScreen.registerReceiver();
        ClientTickEvents.END_CLIENT_TICK.register(CabHud::tick);
        HudRenderCallback.EVENT.register(CabHud::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void clear() {
        snapshot = null;
        liveVehicle = null;
        nextUpdate = 0;
        demo = null;
        demoEvent = null;
        CabData.clear();
    }

    private static void tick(MinecraftClient client) {
        AddonClientConfig config = AddonClientConfig.get();
        while (detailKey.wasPressed()) {
            cycleDetail(config);
        }
        while (menuKey.wasPressed()) {
            CabScreen.open(client);
        }
        CabStartup.tick(client);
        if (!config.hudEnabled || config.isClassicHud() || client.player == null) {
            snapshot = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextUpdate) {
            return;
        }
        nextUpdate = now + 1000L / MathHelper.clamp(config.hudUpdateHz, 1, 20);
        try {
            snapshot = CabData.compute(now);
            snapshotWall = now;
            liveVehicle = snapshot == null ? null : com.stationannouncer.client.mtraddon.DrivingHud.findDrivenVehicle();
        } catch (Exception e) {
            snapshot = null; // MTR data mid-sync: blink off for one update rather than crash
        }
    }

    private static Text doorKeyName() {
        for (KeyBinding binding : MinecraftClient.getInstance().options.allKeys) {
            if (binding.getTranslationKey().equals("key.mtr.train_toggle_doors")) {
                return binding.getBoundKeyLocalizedText();
            }
        }
        return Text.literal("?");
    }

    public static Text menuKeyName() {
        return menuKey == null ? Text.literal("?") : menuKey.getBoundKeyLocalizedText();
    }

    /**
     * Shows one of our crew/cab notices in the top-centre event line for a few seconds when the cab
     * HUD is drawing (the vanilla action bar sits on top of the drive and run panels on narrow GUIs).
     * Returns false when the caller should use the action bar as before.
     */
    public static boolean notice(Text text) {
        if (!replacesMtrOverlay()) {
            return false;
        }
        notice = text.getString();
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
        return true;
    }

    /** True while the cab HUD draws live data: MTR's own driving overlay then stays off (DrivingGuiRendererMixin). */
    public static boolean replacesMtrOverlay() {
        return snapshot != null && !AddonClientConfig.get().isClassicHud();
    }

    /**
     * How far the bottom panels rise so they never sit on the hotbar: only when they would
     * overlap it sideways (narrow GUIs — a Mac at auto GUI scale is ~426 wide), and higher
     * in survival, where hearts, food and the XP bar stack above the hotbar.
     */
    private static int hotbarLift(MinecraftClient client, int screenW, int panelLeft, int panelRight) {
        int hotbarLeft = screenW / 2 - 91 - 2;
        int hotbarRight = screenW / 2 + 91 + 2;
        if (panelRight <= hotbarLeft || panelLeft >= hotbarRight) {
            return 0;
        }
        boolean statusBars = client.interactionManager != null && client.interactionManager.hasStatusBars();
        return statusBars ? 50 : 24;
    }

    /** Default (run collapsed) → everything expanded → everything collapsed → default. */
    static void cycleDetail(AddonClientConfig config) {
        boolean isDefault = !config.cabDestOpen && config.cabDriveOpen && !config.cabRunOpen;
        boolean allOpen = config.cabDestOpen && config.cabDriveOpen && config.cabRunOpen;
        if (isDefault) {
            config.cabDestOpen = config.cabDriveOpen = config.cabRunOpen = true;
        } else if (allOpen) {
            config.cabDestOpen = config.cabDriveOpen = config.cabRunOpen = false;
        } else {
            config.cabDestOpen = false;
            config.cabDriveOpen = true;
            config.cabRunOpen = false;
        }
        AddonClientConfig.persist();
    }

    // ================================================================ render

    private static void render(DrawContext ctx, float tickDelta) {
        CabSnapshot snap = demo != null ? demo : snapshot;
        if (snap == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen))) {
            return;
        }
        AddonClientConfig config = AddonClientConfig.get();
        int scale = MathHelper.clamp(config.cabScale, 1, 4);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1);
        int w = ctx.getScaledWindowWidth() / scale;
        int h = ctx.getScaledWindowHeight() / scale;
        long wallNow = System.currentTimeMillis();
        long now = snap.now + (demo != null ? 0 : wallNow - snapshotWall);
        Draw d = new Draw(ctx, client.textRenderer);
        destPanel(d, snap, config.cabDestOpen);
        eventLine(d, snap, w, wallNow);
        int leftH = h - hotbarLift(client, w, MARGIN, MARGIN + DRIVE_W);
        int rightH = h - hotbarLift(client, w, w - MARGIN - RUN_W, w - MARGIN);
        if (snap.guardView) {
            guardPanel(d, snap, leftH, config.cabDriveOpen);
        } else {
            double liveKph = snap.speedKph;
            org.mtr.mod.data.VehicleExtension vehicle = liveVehicle;
            if (demo == null && vehicle != null) {
                try {
                    liveKph = vehicle.getSpeed() * 3600; // m/ms → km/h, read every frame
                } catch (RuntimeException ignored) {
                    // vehicle disposed between ticks
                }
            }
            drivePanel(d, snap, leftH, config.cabDriveOpen, config.cabMph, liveKph);
        }
        runPanel(d, snap, w, rightH, config.cabRunOpen, now);
        ctx.getMatrices().pop();
    }

    // ----------------------------------------------------------- destination

    private static void destPanel(Draw d, CabSnapshot s, boolean open) {
        int x = MARGIN;
        int y = MARGIN;
        int ph = open ? 40 : 30;
        d.panel(x, y, DEST_W, ph, open);
        d.disc(x + 11, y + 11, 7, s.routeColor);
        String label = s.routeLabel.length() > 2 ? s.routeLabel.substring(0, 2) : s.routeLabel;
        int labelColor = needsDarkText(s.routeColor) ? 0xFF111111 : TEXT;
        d.textCentered(label, x + 12, y + 7, labelColor, false);
        d.text(d.fit(s.destination.isEmpty() ? "—" : s.destination, DEST_W - 34), x + 22, y + 5, TEXT);
        if (s.atPlatform) {
            d.sprite(Icons.STOP_MARK, x + 22, y + 17, BLUE);
            d.text(d.fit(s.nextName, DEST_W - 40), x + 30, y + 17, TEXT);
        } else if (!s.nextName.isEmpty()) {
            d.sprite(Icons.ARROW, x + 22, y + 17, BLUE);
            String dist = s.nextDistance >= 0 ? distance(s.nextDistance) : "";
            String name = d.fit(s.nextName, DEST_W - 40 - d.width(dist));
            int nw = d.text(name, x + 29, y + 17, TEXT);
            d.text(dist, x + 33 + nw, y + 17, DIM);
        } else {
            // Nothing ahead known yet: keep the row (elements move as little as possible).
            d.sprite(Icons.ARROW, x + 22, y + 17, FAINT);
            d.text("--", x + 29, y + 17, FAINT);
        }
        if (open) {
            d.sprite(Icons.ARROW, x + 22, y + 28, FAINT);
            d.sprite(Icons.ARROW, x + 25, y + 28, FAINT);
            if (!s.afterName.isEmpty()) {
                String dist = s.afterDistance >= 0 ? distance(s.afterDistance) : "";
                int nw = d.text(d.fit(s.afterName, DEST_W - 44 - d.width(dist)), x + 32, y + 28, DIM);
                d.text(dist, x + 36 + nw, y + 28, FAINT);
            } else {
                d.text("--", x + 32, y + 28, FAINT);
            }
        }
    }

    // ----------------------------------------------------------------- drive

    private static void drivePanel(Draw d, CabSnapshot s, int screenH, boolean open, boolean mph, double speedKph) {
        int coreH = 44;
        int extraH = open ? 38 : 0;
        int ph = coreH + extraH;
        int x = MARGIN;
        int y = screenH - MARGIN - ph;
        d.panel(x, y, DRIVE_W, ph, open);
        int cy = y + extraH;

        // Signal head and the distance to it.
        int aspectColor = aspectColor(s.aspect);
        d.fill(x + 10, cy + 5, 11, 25, 0xFF050607);
        d.fill(x + 10, cy + 5, 11, 1, 0xFF3D4650);
        int[] lampAspects = {CabSnapshot.ASPECT_DANGER, CabSnapshot.ASPECT_CAUTION, CabSnapshot.ASPECT_CLEAR};
        for (int i = 0; i < 3; i++) {
            d.disc(x + 15, cy + 10 + i * 7, 2, s.aspect == lampAspects[i] ? aspectColor(lampAspects[i]) : 0xFF1D2226);
        }
        String sigText = s.aspect == CabSnapshot.ASPECT_NONE ? "--" : distance(s.signalDistance);
        d.textCentered(sigText, x + 16, cy + 33, s.aspect == CabSnapshot.ASPECT_NONE ? FAINT : aspectColor, true);

        // Speed, every frame, to one decimal: the whole number at 3×, ".1" at 2× on the same
        // baseline, the unit tucked above the decimal so three-digit speeds clear the signs.
        double shown = Math.max(0, mph ? speedKph / 1.609344 : speedKph);
        long tenths = Math.round(shown * 10);
        int whole = d.textScaled(Long.toString(tenths / 10), x + 32, cy + 5, TEXT, 3);
        d.textScaled("." + tenths % 10, x + 33 + whole, cy + 13, TEXT, 2);
        d.text(mph ? "mph" : "km/h", x + 34 + whole, cy + 4, DIM);

        // Speed bar with the current (red) and next (amber) limit ticks.
        int top = barTop(s);
        int bx = x + 32;
        int bw = 74;
        int by = cy + 34;
        d.fill(bx, by, bw, 3, OFF);
        d.fill(bx, by, (int) Math.round(bw * Math.min(1, speedKph / top)), 3, s.limitKph > 0 && speedKph > s.limitKph + 1 ? RED : BLUE);
        if (s.limitKph > 0) {
            d.fill(bx + (int) Math.round(bw * Math.min(1, s.limitKph / (double) top)) - 1, by - 2, 1, 7, RED);
        }
        if (s.nextLimitKph > 0) {
            d.fill(bx + (int) Math.round(bw * Math.min(1, s.nextLimitKph / (double) top)) - 1, by - 2, 1, 7, AMBER);
        }

        // Limit signs: current, then the next one (grey placeholder when none ahead).
        int lx = x + DRIVE_W - 50;
        d.disc(lx + 9, cy + 13, 9, 0xFFD7261E);
        d.disc(lx + 9, cy + 13, 7, 0xFFFFFFFF);
        d.signNumber(s.limitKph > 0 ? Integer.toString(limitShown(s.limitKph, mph)) : "--", lx + 10, cy + 9);
        if (s.nextLimitKph > 0) {
            d.disc(lx + 32, cy + 13, 9, 0xFFD7261E);
            d.disc(lx + 32, cy + 13, 7, 0xFFFFFFFF);
            d.signNumber(Integer.toString(limitShown(s.nextLimitKph, mph)), lx + 33, cy + 9);
            d.textCentered(distance(s.nextLimitDistance), lx + 32, cy + 26, AMBER, true);
        } else {
            d.disc(lx + 32, cy + 13, 9, 0xFF3A4148);
            d.disc(lx + 32, cy + 13, 7, 0xFF22282D);
        }

        if (!open) {
            return;
        }
        int ey = y + 4;
        // Row 1: stop marker — reserved, grey until braking for a platform.
        int markerColor = !s.markerActive ? FAINT : s.markerDistance < -0.5 ? RED : s.markerDistance <= 0.5 ? GREEN : AMBER;
        d.sprite(Icons.MARKER, x + 6, ey, s.markerActive ? markerColor : FAINT);
        d.fill(x + 16, ey + 3, 100, 3, OFF);
        if (s.markerActive) {
            double frac = Math.min(1, Math.abs(s.markerDistance) / 300);
            d.fill(x + 16, ey + 3, Math.max(1, (int) Math.round(100 * frac)), 3, markerColor);
        }
        String marker = !s.markerActive ? "--" : Math.round(s.markerDistance) + "m";
        d.textRight(marker, x + DRIVE_W - 13, ey, s.markerActive ? markerColor : FAINT);

        // Row 2: power / brake ladder.
        int ry = ey + 12;
        d.text("EB", x + 6, ry, s.power <= -8 ? RED : FAINT);
        for (int i = -7; i <= 7; i++) {
            boolean on = (s.power < 0 && i < 0 && i >= s.power) || (s.power > 0 && i > 0 && i <= s.power) || (s.power == 0 && i == 0);
            int color = i < 0 ? ORANGE : i > 0 ? GREEN : TEXT;
            d.fill(x + 20 + (i + 7) * 6, ry + 1, 5, 5, on ? color : OFF);
        }
        String notch = s.power <= -8 ? "EB" : s.power > 0 ? "P" + s.power : s.power < 0 ? "B" + (-s.power) : "N";
        d.textRight(notch, x + DRIVE_W - 6, ry, s.power > 0 ? GREEN : s.power < 0 ? (s.power <= -8 ? RED : ORANGE) : TEXT);

        // Row 3: lamps.
        int ly = ry + 12;
        boolean guardReady = CrewView.flag(CrewProtocol.FLAG_GUARD_READY);
        d.sprite(Icons.DOOR, x + 6, ly, s.doorsOpen ? AMBER : FAINT);
        d.sprite(Icons.GUARD, x + 22, ly, guardReady ? GREEN : FAINT);
        d.sprite(Icons.HOLD, x + 38, ly, s.hold ? RED : FAINT);
        d.fill(x + 4, y + extraH - 1, DRIVE_W - 8, 1, EDGE);
    }

    // ---------------------------------------------------------------- guard

    /** The guard's bottom-left panel: doors, the two ready lamps, and the speed for reference. */
    private static void guardPanel(Draw d, CabSnapshot s, int screenH, boolean open) {
        int coreH = 44;
        int extraH = open ? 14 : 0;
        int ph = coreH + extraH;
        int x = MARGIN;
        int y = screenH - MARGIN - ph;
        d.panel(x, y, DRIVE_W, ph, open);
        int cy = y + extraH;
        d.sprite(Icons.DOOR, x + 6, cy + 6, s.doorsOpen ? AMBER : GREEN, 2);
        d.text(tr(s.obstructed ? "gui.station_announcer.cab.doors_obstructed"
                : s.doorsOpen ? "gui.station_announcer.cab.doors_open" : "gui.station_announcer.cab.doors_closed"),
                x + 24, cy + 6, s.obstructed ? RED : s.doorsOpen ? AMBER : TEXT);
        boolean guardReady = CrewView.flag(CrewProtocol.FLAG_GUARD_READY);
        boolean driverReady = CrewView.flag(CrewProtocol.FLAG_DRIVER_READY);
        // Ready lamps, named so it is clear whose ready each one is.
        d.fill(x + 24, cy + 20, 5, 5, guardReady ? GREEN : OFF);
        d.text(tr("gui.station_announcer.cab.guard"), x + 32, cy + 19, guardReady ? TEXT : FAINT);
        d.fill(x + 84, cy + 20, 5, 5, driverReady ? GREEN : OFF);
        d.text(tr("gui.station_announcer.cab.driver"), x + 92, cy + 19, driverReady ? TEXT : FAINT);
        String speed = Math.round(s.speedKph) + " km/h";
        d.text(speed, x + 24, cy + 32, DIM);
        d.text(CrewView.hasDriver() ? tr("gui.station_announcer.cab.driver_aboard") : tr("gui.station_announcer.cab.no_driver"),
                x + 72, cy + 32, CrewView.hasDriver() ? DIM : FAINT);
        if (open) {
            d.text(Text.translatable("gui.station_announcer.cab.buzz_key", CrewView.buzzerKeyName()).getString(),
                    x + 6, y + 4, FAINT);
            d.fill(x + 4, y + extraH - 1, DRIVE_W - 8, 1, EDGE);
        }
    }

    // ------------------------------------------------------------------- run

    private static void runPanel(Draw d, CabSnapshot s, int screenW, int screenH, boolean open, long now) {
        List<CabSnapshot.RunRow> rows = visibleRows(s, open);
        int rowH = 10;
        int ph = 4 + 18 + Math.max(2, rows.size()) * rowH + 4 + 14;
        int x = screenW - MARGIN - RUN_W;
        int y = screenH - MARGIN - ph;
        d.panel(x, y, RUN_W, ph, open);

        d.sprite(Icons.CLOCK, x + 6, y + 6, DIM);
        if (s.deviationKnown) {
            d.textScaled(deviation(s.deviationMillis), x + 14, y + 4, deviationColor(s.deviationMillis), 2);
        } else {
            d.textScaled("--:--", x + 14, y + 4, FAINT, 2);
        }
        d.textRight(clock(now), x + RUN_W - 12, y + 5, DIM);
        Integer score = demo == null ? CabScore.live() : null;
        if (score != null) {
            d.textRight(score + " pts", x + RUN_W - 12, y + 14, score >= 0 ? GREEN : RED);
        }

        int ry = y + 22;
        if (rows.isEmpty()) {
            d.text("--", x + 9, ry, FAINT);
        }
        for (CabSnapshot.RunRow row : rows) {
            if (row.current()) {
                d.fill(x + 4, ry, 2, 7, BLUE);
            }
            int nameColor = row.past() ? FAINT : TEXT;
            d.text(d.fit(row.name(), 74), x + 9, ry, nameColor);
            String scheduled = row.scheduled() > 0 ? hhmm(row.scheduled()) : "--";
            d.textRight(scheduled, x + RUN_W - 50, ry, row.past() ? FAINT : DIM);
            long dev = row.expected() - row.scheduled();
            String expected = row.expected() > 0 ? hhmmss(row.expected()) : "--";
            int expectedColor = row.past() ? FAINT : row.expected() > 0 && row.scheduled() > 0 ? deviationColor(dev) : FAINT;
            d.textRight(expected, x + RUN_W - 6, ry, expectedColor);
            ry += rowH;
        }

        // Departure timer: always in place, live only while standing at a platform.
        int dy = y + ph - 14;
        d.fill(x + 4, dy - 3, RUN_W - 8, 1, EDGE);
        boolean live = s.atPlatform && s.departAt > 0;
        d.text("Dep", x + 6, dy, live ? TEXT : FAINT);
        int bx = x + 26;
        int bw = RUN_W - 64;
        d.fill(bx, dy + 2, bw, 3, OFF);
        String value = "--";
        int valueColor = FAINT;
        if (live) {
            long left = s.departAt - now;
            double done = s.dwellMillis <= 0 ? 1 : 1 - Math.max(0, left) / (double) s.dwellMillis;
            d.fill(bx, dy + 2, (int) Math.round(bw * MathHelper.clamp(done, 0, 1)), 3, left < 0 ? AMBER : BLUE);
            value = left >= 0 ? ((left + 999) / 1000) + "s" : "+" + (-left / 1000) + "s";
            valueColor = left >= 0 ? TEXT : AMBER;
        }
        d.textRight(value, x + RUN_W - 6, dy, valueColor);
    }

    /** Collapsed: the next two stops (the current one counts). Expanded: the whole run we know. */
    private static List<CabSnapshot.RunRow> visibleRows(CabSnapshot s, boolean open) {
        if (open) {
            return s.run;
        }
        List<CabSnapshot.RunRow> rows = new ArrayList<>(2);
        for (CabSnapshot.RunRow row : s.run) {
            if (!row.past()) {
                rows.add(row);
                if (rows.size() == 2) {
                    break;
                }
            }
        }
        return rows;
    }

    // ----------------------------------------------------------------- events

    private static String boardingText(int boarding) {
        return Text.translatable(boarding == 1 ? "gui.station_announcer.cab.event_boarding_one"
                : "gui.station_announcer.cab.event_boarding", boarding).getString();
    }

    private static void eventLine(Draw d, CabSnapshot s, int screenW, long wallNow) {
        String text = null;
        int color = AMBER;
        int[][] icon = Icons.WARN;
        boolean blinkOn = (wallNow / BLINK_MILLIS) % 2 == 0;
        byte vigilance = CrewView.vigilanceState();
        boolean bothAboard = CrewView.hasDriver() && CrewView.hasGuard();
        if (demo != null && demoEvent != null) {
            text = demoEvent;
            color = demoEventColor;
        } else if (!s.guardView && vigilance == CrewProtocol.VIGIL_TRIPPED) {
            text = Text.translatable("gui.station_announcer.cab.event_vigil_tripped", CrewView.vigilanceKeyName()).getString();
            color = RED;
        } else if (!s.guardView && vigilance == CrewProtocol.VIGIL_WARNING) {
            long left = (CrewView.vigilanceLeft(wallNow) + 999) / 1000;
            text = Text.translatable("gui.station_announcer.cab.event_vigil_warning", CrewView.vigilanceKeyName(), left).getString();
            color = blinkOn ? AMBER : DIM;
        } else if (notice != null && wallNow < noticeUntil) {
            text = notice; // a notice / trackside announcement: briefly, after the vigilance lines
            color = BLUE;
            icon = Icons.DRIVER;
        } else if (s.obstructed) {
            text = tr("gui.station_announcer.cab.event_obstructed");
            color = RED;
        } else if (demo == null && CrewView.incident() != 0) {
            byte incident = CrewView.incident();
            long left = CrewView.incidentSecondsLeft(wallNow);
            String key = switch (incident) {
                case 1 -> "gui.station_announcer.cab.event_door_fault";
                case 2 -> "gui.station_announcer.cab.event_held";
                case 3 -> "gui.station_announcer.cab.event_sick";
                default -> "gui.station_announcer.cab.event_alarm";
            };
            text = incident == 1 ? Text.translatable(key, doorKeyName()).getString()
                    : incident == 4 ? tr(key) : Text.translatable(key, left).getString();
            color = incident == 4 || incident == 1 ? RED : AMBER;
        } else if (bothAboard && CrewView.flag(CrewProtocol.FLAG_DOORS_THIS_STOP) && !CrewView.flag(CrewProtocol.FLAG_GUARD_READY)) {
            boolean doorsOpen = CrewView.flag(CrewProtocol.FLAG_DOORS_OPEN);
            if (!s.guardView && CrewView.flag(CrewProtocol.FLAG_DRIVER_WAITS)) {
                text = tr("gui.station_announcer.cab.event_wait_guard");
                color = BLUE;
                icon = Icons.GUARD;
            } else if (s.guardView) {
                int boarding = doorsOpen ? CabPassengers.boardingCount() : 0;
                if (boarding > 0) {
                    text = boardingText(boarding); // the guard looks back along the train before closing
                } else if (CrewView.flag(CrewProtocol.FLAG_GUARD_WAITS) && !CrewView.flag(CrewProtocol.FLAG_DRIVER_READY) && doorsOpen) {
                    text = tr("gui.station_announcer.cab.event_wait_driver");
                } else {
                    text = Text.translatable(doorsOpen ? "gui.station_announcer.cab.event_close_then_buzz"
                            : "gui.station_announcer.cab.event_buzz_ready", CrewView.buzzerKeyName()).getString();
                }
                color = BLUE;
                icon = Icons.GUARD;
            }
        }
        if (text == null && demo == null && s.doorsOpen && (s.guardView || !CrewView.hasGuard())) {
            // Whoever works the doors (the guard, or the driver alone) sees who is still boarding.
            int boarding = CabPassengers.boardingCount();
            if (boarding > 0) {
                text = boardingText(boarding);
                color = BLUE;
                icon = s.guardView ? Icons.GUARD : Icons.DRIVER;
            }
        }
        if (text == null && !s.guardView && demo == null && CabStartup.pending()) {
            text = Text.translatable("gui.station_announcer.cab.event_start_up", menuKeyName()).getString();
            color = BLUE;
            icon = Icons.DRIVER;
        }
        if (text == null && s.slotPending && s.yardTime > 0) {
            long left = (s.yardTime - s.now) / 1000;
            text = Text.translatable(left >= 0 ? "gui.station_announcer.cab.event_leave_yard"
                    : "gui.station_announcer.cab.event_leave_yard_late", hhmmss(s.yardTime)).getString();
            color = left >= 0 ? BLUE : AMBER;
            icon = Icons.CLOCK;
        }
        if (text == null && CrewView.buzzCount() > 0) {
            String from = CrewView.buzzRole() == CrewProtocol.ROLE_DRIVER
                    ? tr("gui.station_announcer.cab.driver") : tr("gui.station_announcer.cab.guard");
            text = from + " " + "•".repeat(CrewView.buzzCount());
            color = BLUE;
            icon = CrewView.buzzRole() == CrewProtocol.ROLE_DRIVER ? Icons.DRIVER : Icons.GUARD;
        }
        if (text == null) {
            return;
        }
        // Centred, but never over the destination panel; on narrow GUIs it starts right of it
        // and the text is shortened to the space left.
        int minX = MARGIN + DEST_W + 4;
        int maxW = screenW - MARGIN - minX;
        text = d.fit(text, Math.max(20, maxW - 24));
        int ew = d.width(text) + 24;
        int x = Math.max(minX, screenW / 2 - ew / 2);
        int y = MARGIN;
        d.fill(x, y, ew, 15, PANEL);
        d.fill(x, y, 2, 15, color | 0xFF000000);
        d.sprite(icon, x + 6, y + 4, color);
        d.text(text, x + 16, y + 4, TEXT);
    }

    // ------------------------------------------------------------- formatting

    static String distance(double meters) {
        return meters >= 1000 ? String.format("%.1fkm", meters / 1000) : Math.round(Math.max(0, meters)) + "m";
    }

    private static int limitShown(int kph, boolean mph) {
        return mph ? (int) Math.round(kph / 1.609344 / 5) * 5 : kph;
    }

    /** Bar scale: the faster of the limits / the speed, plus headroom, rounded up to 20 km/h. */
    private static int barTop(CabSnapshot s) {
        double top = Math.max(Math.max(s.limitKph, s.nextLimitKph) * 1.2, s.speedKph * 1.05);
        return Math.max(40, (int) Math.ceil(top / 20) * 20);
    }

    static String deviation(long millis) {
        long seconds = Math.round(millis / 1000.0);
        long abs = Math.abs(seconds);
        return (seconds >= 0 ? "+" : "-") + abs / 60 + ":" + String.format("%02d", abs % 60);
    }

    static int deviationColor(long millis) {
        long abs = Math.abs(millis);
        return abs <= 30_000 ? GREEN : millis > 120_000 ? RED : AMBER;
    }

    private static int aspectColor(int aspect) {
        return switch (aspect) {
            case CabSnapshot.ASPECT_CLEAR -> GREEN;
            case CabSnapshot.ASPECT_CAUTION -> AMBER;
            case CabSnapshot.ASPECT_DANGER -> RED;
            default -> FAINT;
        };
    }

    private static LocalTime local(long epochMillis) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    static String clock(long epochMillis) {
        LocalTime t = local(epochMillis);
        return String.format("%d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }

    static String hhmm(long epochMillis) {
        LocalTime t = local(epochMillis);
        return String.format("%d:%02d", t.getHour(), t.getMinute());
    }

    static String hhmmss(long epochMillis) {
        LocalTime t = local(epochMillis);
        return String.format("%d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }

    private static boolean needsDarkText(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 160;
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    // ================================================================= drawing

    /** Thin drawing helpers over DrawContext in GUI pixels. */
    private record Draw(DrawContext ctx, TextRenderer font) {
        void fill(int x, int y, int w, int h, int color) {
            ctx.fill(x, y, x + w, y + h, color);
        }

        void panel(int x, int y, int w, int h, boolean open) {
            fill(x, y, w, h, PANEL);
            fill(x, y, w, 1, EDGE);
            fill(x, y + h - 1, w, 1, EDGE);
            fill(x, y + 1, 1, h - 2, EDGE);
            fill(x + w - 1, y + 1, 1, h - 2, EDGE);
            sprite(open ? Icons.CHEVRON_DOWN : Icons.CHEVRON_UP, x + w - 8, y + 3, FAINT);
        }

        /** Filled pixel disc, row by row (the mock-up's limit signs and signal lamps). */
        void disc(int cx, int cy, int r, int color) {
            for (int dy = -r; dy <= r; dy++) {
                int hw = (int) Math.floor(Math.sqrt(r * r - dy * dy + r * 0.8));
                fill(cx - hw, cy + dy, hw * 2 + 1, 1, color);
            }
        }

        void sprite(int[][] rows, int x, int y, int color) {
            sprite(rows, x, y, color, 1);
        }

        void sprite(int[][] rows, int x, int y, int color, int scale) {
            for (int j = 0; j < rows.length; j++) {
                for (int i = 0; i < rows[j].length; i++) {
                    if (rows[j][i] != 0) {
                        fill(x + i * scale, y + j * scale, scale, scale, color);
                    }
                }
            }
        }

        int width(String s) {
            return font.getWidth(s);
        }

        int text(String s, int x, int y, int color) {
            ctx.drawTextWithShadow(font, s, x, y, color);
            return font.getWidth(s);
        }

        void textNoShadow(String s, int x, int y, int color) {
            ctx.drawText(font, s, x, y, color, false);
        }

        void textRight(String s, int right, int y, int color) {
            text(s, right - font.getWidth(s), y, color);
        }

        void textCentered(String s, int cx, int y, int color, boolean shadow) {
            int x = cx - font.getWidth(s) / 2;
            if (shadow) {
                text(s, x, y, color);
            } else {
                textNoShadow(s, x, y, color);
            }
        }

        /** Vanilla text at an integer scale (the big speed, the lateness); returns the drawn width. */
        int textScaled(String s, int x, int y, int color, int scale) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x, y, 0);
            ctx.getMatrices().scale(scale, scale, 1);
            ctx.drawTextWithShadow(font, s, 0, 0, color);
            ctx.getMatrices().pop();
            return font.getWidth(s) * scale;
        }

        /** A limit-sign number: centred, dark, no shadow; three digits are set tight (no letter gap) to fit the disc. */
        void signNumber(String s, int cx, int y) {
            if (s.length() < 3) {
                textCentered(s, cx, y, 0xFF111111, false);
                return;
            }
            int total = 0;
            for (int i = 0; i < s.length(); i++) {
                total += font.getWidth(String.valueOf(s.charAt(i))) - 1;
            }
            int x = cx - total / 2;
            for (int i = 0; i < s.length(); i++) {
                String c = String.valueOf(s.charAt(i));
                textNoShadow(c, x, y, 0xFF111111);
                x += font.getWidth(c) - 1;
            }
        }

        String fit(String s, int max) {
            if (font.getWidth(s) <= max) {
                return s;
            }
            String cut = s;
            while (cut.length() > 1 && font.getWidth(cut + ".") > max) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + ".";
        }
    }
}
