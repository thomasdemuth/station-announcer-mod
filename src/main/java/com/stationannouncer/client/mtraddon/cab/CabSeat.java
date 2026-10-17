package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.StationAnnouncerKeys;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.VehicleCar;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;
import java.util.Map;

/**
 * DRIVER SNAPS TO THE FRONT CAB (MANUAL_DRIVING_PLAN.md §6 item 14: "placed at the front of
 * the leading car while driving, free look; stepping out = letting go of the controls; eye
 * height/side configurable per train type").
 *
 * <h2>Which car is in front (MTR 4.0.1, {@code Vehicle.getVehicleCarsAndPositions})</h2>
 * A train always moves toward INCREASING {@code railProgress}, which is its head. Not reversed,
 * car 0 is laid out from the head backwards, so car 0 leads. Reversed (after an opposite-rail
 * turnaround) the layout starts at {@code railProgress − totalLength} and runs forwards, so car
 * 0 is at the BACK and the last car leads. The cars themselves never turn around: every car's
 * local +z points toward car {@code i + 1} (bogie 1 → bogie 2; MTR's gangway 2 is the +z end).
 * So the cab end of the leading car is local {@code z = −length/2} (not reversed) or
 * {@code +length/2} (reversed), and travel is −z / +z respectively. Local +x is to the LEFT of
 * +z (MTR's {@code rotateY}), i.e. right-of-travel = +x when not reversed, −x when reversed.
 *
 * <h2>How the player is placed</h2>
 * MTR carries a riding player by its OWN car-local position ({@code VehicleRidingMovement}'s
 * private statics {@code ridingVehicleCarNumber / ridingVehicleX / Y / Z}, car origin = car
 * centre, metres = blocks) and re-derives the world position from it every frame in
 * {@code movePlayer}. {@code CabSeatRidingMixin} writes the seat into those statics at the HEAD
 * of {@code movePlayer} for the leading car, so MTR itself puts the player there (its floor
 * clamp keeps the point on a floor box and supplies Y) — no teleports. Walking input is read
 * as zero while seated ({@link #walkLocked}), and MTR is asked to send the new riding position
 * to the server once whenever the seat moves (its {@code sendPositionUpdateTime}).
 *
 * <p>The seat sits {@code inset} blocks behind the frontmost FLOOR edge (not the body end — the
 * floors are where MTR lets a rider stand, and staying ≥ 0.35 inside them keeps MTR's gangway
 * test from firing), {@code lateral} blocks right of the centre line. Both come from
 * {@code AddonClientConfig.cabSeatTrains} keyed by the leading car's vehicle id, else the global
 * defaults; {@code eyeOffset} lifts/lowers the camera ({@link CabCamera}).</p>
 *
 * <p>Active only while this client is the DRIVER ({@link CrewView#isDriver()}) of the vehicle it
 * rides. The "cab seat" key stands up / sits back down; MTR's sneak-to-dismount still works.
 * Client thread only (ticks + MTR's render-thread {@code movePlayer}, which is the client
 * thread). Inactive = {@link #seatVehicle} 0, one long compare per {@code movePlayer} call.</p>
 */
@Environment(EnvType.CLIENT)
public final class CabSeat {
    /** Smallest distance kept from the frontmost floor edge (MTR's 0.3 corner probe + margin). */
    private static final double MIN_INSET = 0.35;
    /** A seat that moved further than this asks MTR to resend the riding position. */
    private static final double RESEND_DISTANCE = 0.25;

    /** One train type's seat ({@code cabSeatTrains} value). Null fields fall back to the globals. */
    public static final class Placement {
        /** Blocks behind the frontmost floor edge of the leading car. */
        @Nullable
        public Double inset;
        /** Blocks right of the car's centre line (negative = left), w.r.t. travel. */
        @Nullable
        public Double lateral;
        /** Camera up (+) / down (−) while seated, blocks. */
        @Nullable
        public Double eyeOffset;

        public Placement() {
        }
    }

    private static KeyBinding seatKey;

    /** The player wants to sit (toggled by the key; back to true when the driver role ends). */
    private static boolean wantSeat = true;

    // Decided each tick, read every frame by CabSeatRidingMixin.
    /** Vehicle being driven from the seat, 0 = feature inactive. */
    private static long seatVehicle;
    /** Index of the leading car. */
    private static int seatCar = -1;
    /** Leading car's cab end: −1 = local −z (not reversed), +1 = local +z (reversed). */
    private static int frontSign = -1;
    private static double carHalfLength;
    private static double inset;
    /** Car-local x of the seat (already signed for the direction of travel). */
    private static double localX;
    private static double eyeOffset;

    /** Set at the HEAD of each movePlayer call: walking input reads as zero for this call. */
    public static boolean walkLocked;

    // Last seat written (to resend the position only when it actually moves).
    private static int lastCar = -1;
    private static double lastX;
    private static double lastZ;
    /** Seat computed by {@link #target}. */
    public static double targetX;
    public static double targetZ;

    private CabSeat() {
    }

    /** Registers the cab-seat key, its tick, and {@link CabCamera} (lean out). */
    public static void register() {
        seatKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.cab_seat", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N, StationAnnouncerKeys.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(CabSeat::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CabSeat::clear));
        CabCamera.register();
    }

    private static void clear() {
        wantSeat = true;
        deactivate();
    }

    private static void deactivate() {
        seatVehicle = 0;
        seatCar = -1;
        lastCar = -1;
        walkLocked = false;
        eyeOffset = 0;
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftClient client) {
        boolean pressed = false;
        while (seatKey.wasPressed()) {
            pressed = true;
        }
        AddonClientConfig config = AddonClientConfig.get();
        long vehicleId = CrewView.isDriver() ? CrewView.vehicleId() : 0;
        if (client.player == null || vehicleId == 0 || !config.cabSeatEnabled
                || !VehicleRidingMovement.isRiding(vehicleId)) {
            if (vehicleId == 0) {
                wantSeat = true; // the next sign-on sits down again
            }
            deactivate();
            return;
        }
        if (pressed) {
            wantSeat = !wantSeat;
            Text message = wantSeat
                    ? Text.translatable("msg.station_announcer.cab_seat.taken")
                    : Text.translatable("msg.station_announcer.cab_seat.left", seatKey.getBoundKeyLocalizedText());
            if (!CabHud.notice(message)) {
                client.player.sendMessage(message, true);
            }
        }
        if (!wantSeat) {
            deactivate();
            return;
        }
        try {
            VehicleExtension vehicle = find(vehicleId);
            ObjectImmutableList<VehicleCar> cars = vehicle == null ? null : vehicle.vehicleExtraData.immutableVehicleCars;
            if (cars == null || cars.isEmpty()) {
                deactivate();
                return;
            }
            boolean reversed = vehicle.getReversed();
            int lead = reversed ? cars.size() - 1 : 0;
            VehicleCar car = cars.get(lead);
            Placement placement = placement(config, car.getVehicleId());
            double wantInset = placement != null && placement.inset != null ? placement.inset : config.cabSeatInset;
            double lateral = placement != null && placement.lateral != null ? placement.lateral : config.cabSeatLateral;
            double eye = placement != null && placement.eyeOffset != null ? placement.eyeOffset : config.cabSeatEyeOffset;
            seatCar = lead;
            frontSign = reversed ? 1 : -1;
            carHalfLength = Math.max(0.5, car.getLength() / 2);
            inset = clamp(wantInset, MIN_INSET, Math.max(MIN_INSET, carHalfLength));
            localX = reversed ? -lateral : lateral;
            eyeOffset = clamp(eye, -1.5, 1.5);
            seatVehicle = vehicleId;
        } catch (Exception e) {
            deactivate(); // MTR's client data swapped mid-sync: try again next tick
        }
    }

    @Nullable
    private static Placement placement(AddonClientConfig config, @Nullable String trainType) {
        Map<String, Placement> map = config.cabSeatTrains;
        if (map == null || map.isEmpty() || trainType == null) {
            return null;
        }
        return map.get(trainType);
    }

    @Nullable
    private static VehicleExtension find(long vehicleId) {
        for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
            if (vehicle.getId() == vehicleId) {
                return vehicle;
            }
        }
        return null;
    }

    // ------------------------------------------------------- movePlayer side

    /**
     * Called from {@code CabSeatRidingMixin} at the HEAD of MTR's {@code movePlayer} for the
     * vehicle this client rides. True = write {@link #targetX}/{@link #targetZ} into the
     * leading car's riding statics for this call.
     */
    public static boolean seatThisCar(long vehicleId, int carNumber,
                                      ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways) {
        if (vehicleId != seatVehicle || carNumber != seatCar) {
            walkLocked = vehicleId == seatVehicle; // other cars of the driven train: no walking either
            return false;
        }
        walkLocked = true;
        // Frontmost floor edge (car-local); without floors, the body end.
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < floorsAndDoorways.size(); i++) {
            ObjectBooleanImmutablePair<Box> entry = floorsAndDoorways.get(i);
            if (entry.rightBoolean()) {
                Box box = entry.left();
                minZ = Math.min(minZ, box.getMinZMapped());
                maxZ = Math.max(maxZ, box.getMaxZMapped());
            }
        }
        if (minZ > maxZ) {
            minZ = -carHalfLength;
            maxZ = carHalfLength;
        }
        double z = frontSign < 0 ? minZ + inset : maxZ - inset;
        targetZ = clamp(z, minZ + MIN_INSET, Math.max(minZ + MIN_INSET, maxZ - MIN_INSET));
        targetX = localX;
        return true;
    }

    /**
     * After the seat was written: whether MTR should resend the riding position to the server
     * (the seat changed car or moved), remembering what was written.
     */
    public static boolean seatMoved(int carNumber) {
        boolean moved = carNumber != lastCar
                || Math.abs(targetX - lastX) > RESEND_DISTANCE
                || Math.abs(targetZ - lastZ) > RESEND_DISTANCE;
        lastCar = carNumber;
        lastX = targetX;
        lastZ = targetZ;
        return moved;
    }

    /** Seated right now (the camera adds {@link #eyeOffset()}). */
    public static boolean isSeated() {
        return seatVehicle != 0;
    }

    public static double eyeOffset() {
        return seatVehicle != 0 ? eyeOffset : 0;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
