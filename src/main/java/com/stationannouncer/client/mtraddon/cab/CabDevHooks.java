package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Dev-rig hooks (lines in {@code run/commands.txt}, dispatched by StationAnnouncerClient):
 * <ul>
 *   <li>{@code #cab-demo run|appr|plat|held|guard} — draw the cab HUD with the mock-up's
 *       sample situations (line 4 to Baker City Central on the 13:42 slot) so the headless
 *       rig can screenshot it without riding a train;</li>
 *   <li>{@code #cab-demo off} — back to live data;</li>
 *   <li>{@code #cab-report [last]} — the end-of-run report card with a sample (or the newest saved) run;</li>
 *   <li>{@code #cab-detail} — the HUD detail key (default → all expanded → all collapsed);</li>
 *   <li>{@code #cab-key <name> <ticks>} — hold a key binding for N ticks, as if the player
 *       pressed it: {@code <name>} matches the binding's translation key (substring, e.g.
 *       {@code train_accelerate}, {@code crew_buzz}, {@code crew_horn}) so the rig can drive.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class CabDevHooks {
    private CabDevHooks() {
    }

    public static void handle(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        if (parts[0].equals("#cab-key") && parts.length >= 2) {
            holdKey(parts[1], parts.length > 2 ? Integer.parseInt(parts[2]) : 2);
            return;
        }
        if (parts[0].equals("#cab-menu")) {
            CabScreen.open(net.minecraft.client.MinecraftClient.getInstance(),
                    parts.length > 1 ? Integer.parseInt(parts[1]) : -1);
            return;
        }
        if (parts[0].equals("#cab-claim")) {
            CabScreen.devClaimFirst();
            return;
        }
        if (parts[0].equals("#cab-cinematic")) {
            CabCinematic.devHook(parts);
            return;
        }
        if (parts[0].equals("#cab-report")) {
            // The report card with a sample run (or the newest saved run: "#cab-report last").
            CabScore.Run run = null;
            if (parts.length > 1 && parts[1].equals("last") && !CabScore.history().isEmpty()) {
                run = CabScore.history().get(0);
            }
            if (run == null) {
                run = new CabScore.Run("12:57 · 1 to Armory", System.currentTimeMillis(), 42, 6, 7, 41_000, 1, 3, 1, 4,
                        java.util.List.of("Perfect stop at Woodyville  +2", "Woodyville  +0:12  +10", "Valley Lake  skipped  −10",
                                "Patagonia  +0:41  −2", "Overran Morgan by 3 m  −3", "Emergency brake  −5",
                                "Morgan  +0:20  +10", "4 left on the platform  −4", "Stanley Falls  +0:05  +10",
                                "Perfect stop at Albany  +2", "Albany  -0:03  +10", "Perfect stop at Armory  +2",
                                "Armory  +0:30  +10", "Sweetwater  +0:15  +10"));
            }
            net.minecraft.client.MinecraftClient.getInstance().setScreen(new CabReportScreen(run));
            return;
        }
        if (parts[0].equals("#cab-close")) {
            net.minecraft.client.MinecraftClient.getInstance().setScreen(null);
            return;
        }
        if (parts[0].equals("#cab-near")) {
            StringBuilder sb = new StringBuilder("[cab-near]");
            for (org.mtr.mod.data.VehicleExtension vehicle : org.mtr.mod.client.MinecraftClientData.getInstance().vehicles) {
                var extra = vehicle.vehicleExtraData;
                sb.append(String.format(" | v%d siding=%d manual=%b route='%s' speed=%.1f riding=%b", vehicle.getId(),
                        extra.getSidingId(), extra.getIsManualAllowed(), extra.getThisRouteName(), vehicle.getSpeed() * 3600,
                        org.mtr.mod.client.VehicleRidingMovement.isRiding(vehicle.getId())));
                try {
                    var cars = vehicle.getSmoothedVehicleCarsAndPositions(0);
                    if (!cars.isEmpty() && !cars.get(0).right().isEmpty()) {
                        var bogie = cars.get(0).right().get(0);
                        sb.append(String.format(" car0 %.1f %.1f %.1f / %.1f %.1f %.1f", bogie.left().x, bogie.left().y, bogie.left().z,
                                bogie.right().x, bogie.right().y, bogie.right().z));
                    }
                } catch (RuntimeException ignored) {
                    // positions not ready
                }
            }
            com.stationannouncer.StationAnnouncer.LOGGER.info(sb.toString());
            return;
        }
        if (parts[0].equals("#cab-dump")) {
            dump();
            return;
        }
        if (parts[0].equals("#cab-detail")) {
            CabHud.cycleDetail(AddonClientConfig.get());
            return;
        }
        if (!parts[0].equals("#cab-demo")) {
            return;
        }
        String which = parts.length > 1 ? parts[1] : "run";
        if (which.equals("off")) {
            CabHud.demo = null;
            CabHud.demoEvent = null;
            return;
        }
        CabHud.demo = sample(which);
        CabHud.demoEvent = switch (which) {
            case "plat" -> "Waiting for the guard's buzz";
            case "held" -> "Held at signal · ~20s";
            case "guard" -> "Close doors, then buzz [B]";
            default -> null;
        };
        CabHud.demoEventColor = which.equals("held") ? CabHud.RED : CabHud.BLUE;
    }

    /** Logs the driven vehicle's raw state and the cab snapshot (rig debugging). */
    private static void dump() {
        org.mtr.mod.data.VehicleExtension vehicle = com.stationannouncer.client.mtraddon.DrivingHud.findDrivenVehicle();
        if (vehicle == null) {
            com.stationannouncer.StationAnnouncer.LOGGER.info("[cab-dump] not driving anything");
            return;
        }
        org.mtr.core.data.VehicleExtraData extra = vehicle.vehicleExtraData;
        double progress = com.stationannouncer.client.mtraddon.DrivingHud.readRailProgress(vehicle);
        var path = extra.immutablePath;
        int head = org.mtr.core.tool.Utilities.getIndexFromConditionalList(path, progress);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[cab-dump] id=%d progress=%.2f head=%d/%d speed=%.2fkm/h power=%d manual=%b manualAllowed=%b doors=%d depIdx=%d route=%d '%s' next='%s'",
                vehicle.getId(), progress, head, path.size(), vehicle.getSpeed() * 3600, extra.getPowerLevel(), extra.getIsCurrentlyManual(),
                extra.getIsManualAllowed(), extra.getDoorMultiplier(), vehicle.getDepartureIndex(), extra.getThisRouteId(),
                extra.getThisRouteName(), extra.getNextStationName()));
        for (int i = Math.max(0, head - 1); i < Math.min(path.size(), head + 4); i++) {
            org.mtr.core.data.PathData p = path.get(i);
            sb.append(String.format(" | #%d %.1f-%.1f dwell=%d base=%d lim=%d", i, p.getStartDistance(), p.getEndDistance(),
                    p.getDwellTime(), p.getSavedRailBaseId(), p.getSpeedLimitKilometersPerHour()));
        }
        CabSnapshot snap = CabData.compute(System.currentTimeMillis());
        if (snap != null) {
            sb.append(String.format(" || snap atPlatform=%b next='%s' %.0fm after='%s' marker=%b %.1f aspect=%d %.0fm run=%d timetable=%b",
                    snap.atPlatform, snap.nextName, snap.nextDistance, snap.afterName, snap.markerActive, snap.markerDistance,
                    snap.aspect, snap.signalDistance, snap.run.size(), snap.hasTimetable));
        }
        com.stationannouncer.StationAnnouncer.LOGGER.info(sb.toString());
    }

    private static net.minecraft.client.option.KeyBinding heldKey;
    private static int heldTicks;
    private static boolean tickRegistered;

    private static void holdKey(String name, int ticks) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        for (net.minecraft.client.option.KeyBinding binding : client.options.allKeys) {
            if (binding.getTranslationKey().contains(name)) {
                release();
                heldKey = binding;
                heldTicks = Math.max(1, ticks);
                net.minecraft.client.util.InputUtil.Key key = net.minecraft.client.util.InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
                net.minecraft.client.option.KeyBinding.setKeyPressed(key, true);
                net.minecraft.client.option.KeyBinding.onKeyPressed(key);
                com.stationannouncer.StationAnnouncer.LOGGER.info("[cab-key] holding {} for {} ticks", binding.getTranslationKey(), heldTicks);
                if (!tickRegistered) {
                    tickRegistered = true;
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> {
                        if (heldKey != null && --heldTicks <= 0) {
                            release();
                        }
                    });
                }
                return;
            }
        }
        com.stationannouncer.StationAnnouncer.LOGGER.warn("[cab-key] no key binding matches {}", name);
    }

    private static void release() {
        if (heldKey != null) {
            net.minecraft.client.option.KeyBinding.setKeyPressed(
                    net.minecraft.client.util.InputUtil.fromTranslationKey(heldKey.getBoundKeyTranslationKey()), false);
            heldKey = null;
        }
    }

    private static long at(String hhmmss) {
        LocalTime time = LocalTime.parse(hhmmss);
        return time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static CabSnapshot sample(String which) {
        CabSnapshot s = new CabSnapshot();
        s.routeColor = 0xFF00933C;
        s.routeLabel = "4";
        s.destination = "Baker City Central";
        s.hasTimetable = true;
        s.deviationKnown = true;
        String[][] run = {{"Kingsbridge", "13:36:00"}, {"Cherry Bridge", "13:39:00"}, {"Albany", "13:42:00"},
                {"Reed St", "13:45:00"}, {"14 St", "13:48:00"}, {"Baker City Central", "13:53:00"}};
        long[] actual = {at("13:36:40"), at("13:39:52"), 0, 0, 0, 0};
        int current = 2;
        switch (which) {
            case "appr" -> {
                s.now = at("13:41:57");
                s.speedKph = 34;
                s.limitKph = 50;
                s.aspect = CabSnapshot.ASPECT_CLEAR;
                s.signalDistance = 160;
                s.nextName = "Albany";
                s.nextDistance = 85;
                s.afterName = "Reed St";
                s.afterDistance = 2385;
                s.markerActive = true;
                s.markerDistance = 85;
                s.power = -3;
                s.manual = true;
                s.deviationMillis = 39_000;
            }
            case "plat", "guard" -> {
                s.now = at("13:42:14");
                s.atPlatform = true;
                s.speedKph = 0;
                s.limitKph = 50;
                s.aspect = CabSnapshot.ASPECT_CLEAR;
                s.signalDistance = 40;
                s.nextName = "Albany";
                s.afterName = "Reed St";
                s.afterDistance = 2300;
                s.markerActive = true;
                s.markerDistance = 0;
                s.power = -4;
                s.manual = true;
                s.doorsOpen = true;
                s.deviationMillis = 38_000;
                s.departAt = at("13:42:30");
                s.dwellMillis = 30_000;
                s.guardView = which.equals("guard");
            }
            case "held" -> {
                s.now = at("13:44:20");
                s.speedKph = 0;
                s.limitKph = 80;
                s.aspect = CabSnapshot.ASPECT_DANGER;
                s.signalDistance = 30;
                s.nextName = "Reed St";
                s.nextDistance = 2100;
                s.afterName = "14 St";
                s.afterDistance = 4600;
                s.power = -7;
                s.manual = true;
                s.deviationMillis = 65_000;
                actual[2] = at("13:42:44");
                current = 3;
            }
            default -> {
                s.now = at("13:40:58");
                s.speedKph = 62;
                s.limitKph = 80;
                s.nextLimitKph = 50;
                s.nextLimitDistance = 410;
                s.aspect = CabSnapshot.ASPECT_CAUTION;
                s.signalDistance = 380;
                s.nextName = "Albany";
                s.nextDistance = 1200;
                s.afterName = "Reed St";
                s.afterDistance = 3500;
                s.power = 3;
                s.manual = true;
                s.deviationMillis = 41_000;
            }
        }
        for (int i = 0; i < run.length; i++) {
            long scheduled = at(run[i][1]);
            boolean past = i < current;
            long expected = past ? actual[i] : scheduled + s.deviationMillis;
            if (i == current && s.atPlatform) {
                scheduled = at("13:42:30");
                expected = s.departAt;
            }
            s.run.add(new CabSnapshot.RunRow(run[i][0], scheduled, expected, past, i == current));
        }
        return s;
    }
}
