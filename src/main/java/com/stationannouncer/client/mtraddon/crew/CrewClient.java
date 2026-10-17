package com.stationannouncer.client.mtraddon.crew;

import com.stationannouncer.client.StationAnnouncerKeys;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.VehicleCar;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.item.ItemDriverKey;
import java.util.Objects;

/**
 * Client half of the train-crew feature (MANUAL_DRIVING_PLAN.md phase 1: driver /
 * guard roles, OPTO, buzzer, horn, vigilance). Wire format: {@link CrewProtocol}.
 *
 * <p><b>Role detection</b>: each tick the vehicle the player rides is found the way
 * {@code DrivingHud} does it ({@code VehicleRidingMovement.isRiding}), and the held
 * MTR key decides the role — {@code canDrive} = driver, else {@code canOpenDoors} =
 * guard. MTR's own server-side {@code isDriver()} cannot tell the two apart (a guard
 * key counts too), so this client claim is what the server's crew logic uses. The
 * claim goes out when any of (vehicle, role, vigilance preference, train type)
 * changes, and as a heartbeat every {@link CrewProtocol#HEARTBEAT_MILLIS} while the
 * player holds a role; losing the role sends one {@code vehicleId = 0}.</p>
 *
 * <p><b>Keys</b> (vanilla Controls, the mod's category): buzzer B, horn G (hold),
 * vigilance acknowledge V. Presses are detected as edges against the key's own
 * down-state, so OS key repeat never sends a second buzz.</p>
 *
 * <p>All state is static, touched only on the client thread, and cleared on
 * disconnect.</p>
 */
@Environment(EnvType.CLIENT)
public final class CrewClient {
    private static KeyBinding buzzerKey;
    private static KeyBinding hornKey;
    private static KeyBinding vigilanceKey;

    // Edge detection (down-state at the end of the previous tick).
    private static boolean buzzerWasDown;
    private static boolean vigilanceWasDown;
    /** Whether the server was last told the horn is on. */
    private static boolean hornSent;

    // Last role claim sent (see class javadoc).
    private static long sentVehicleId;
    private static byte sentRole = CrewProtocol.ROLE_NONE;
    private static int sentVigilance = -1;
    private static String sentTrainType = "";
    private static long lastSentAt;

    /** This tick's detected role (not the server's verdict, which arrives in STATUS). */
    private static byte localRole = CrewProtocol.ROLE_NONE;

    private CrewClient() {
    }

    public static void register() {
        buzzerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.crew_buzzer", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, StationAnnouncerKeys.CATEGORY));
        hornKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.crew_horn", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G, StationAnnouncerKeys.CATEGORY));
        vigilanceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.crew_vigilance", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V, StationAnnouncerKeys.CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.STATUS_S2C,
                (client, handler, buf, responseSender) -> {
                    long vehicleId = buf.readLong();
                    byte myRole = buf.readByte();
                    boolean hasDriver = buf.readBoolean();
                    boolean hasGuard = buf.readBoolean();
                    int flags = buf.readByte() & 0xFF;
                    byte vigilanceState = buf.readByte();
                    int vigilanceMillisLeft = Math.max(0, buf.readVarInt());
                    byte horn = buf.readByte();
                    byte incident = buf.readByte();
                    int incidentMillisLeft = Math.max(0, buf.readVarInt());
                    client.execute(() -> {
                        onStatus(vehicleId, myRole, hasDriver, hasGuard, flags, vigilanceState, vigilanceMillisLeft, horn);
                        CrewState.incident = incident;
                        CrewState.incidentMillisLeft = incidentMillisLeft;
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.BUZZ_S2C,
                (client, handler, buf, responseSender) -> {
                    byte fromRole = buf.readByte();
                    client.execute(() -> {
                        CrewState.addBuzz(fromRole, System.currentTimeMillis());
                        CrewSounds.playBuzzer();
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.HORN_S2C,
                (client, handler, buf, responseSender) -> {
                    int entityId = buf.readVarInt();
                    byte horn = CrewProtocol.clampHorn(buf.readByte());
                    boolean on = buf.readBoolean();
                    client.execute(() -> CrewSounds.horn(client, entityId, horn, on));
                });
        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.MESSAGE_S2C,
                (client, handler, buf, responseSender) -> {
                    byte code = buf.readByte();
                    client.execute(() -> showMessage(client, code));
                });

        ClientTickEvents.END_CLIENT_TICK.register(CrewClient::tick);
        HudRenderCallback.EVENT.register(CrewPanel::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> clear(client)));
    }

    /** The buzzer key's bound name, for messages and the panel ("[B]"). */
    static Text buzzerKeyName() {
        return buzzerKey == null ? Text.literal("?") : buzzerKey.getBoundKeyLocalizedText();
    }

    /** The vigilance key's bound name ("[V]"). */
    static Text vigilanceKeyName() {
        return vigilanceKey == null ? Text.literal("?") : vigilanceKey.getBoundKeyLocalizedText();
    }

    private static void clear(MinecraftClient client) {
        CrewState.clear();
        CrewSounds.clear(client);
        buzzerWasDown = false;
        vigilanceWasDown = false;
        hornSent = false;
        sentVehicleId = 0;
        sentRole = CrewProtocol.ROLE_NONE;
        sentVigilance = -1;
        sentTrainType = "";
        lastSentAt = 0;
        localRole = CrewProtocol.ROLE_NONE;
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        long now = System.currentTimeMillis();

        // Role detection + claim.
        long vehicleId = 0;
        byte role = CrewProtocol.ROLE_NONE;
        String trainType = "";
        try {
            VehicleExtension vehicle = findRiddenVehicle();
            if (vehicle != null) {
                ItemDriverKey key = VehicleRidingMovement.getValidHoldingKey(vehicle.vehicleExtraData.getDepotId());
                role = key == null ? CrewProtocol.ROLE_NONE
                        : key.canDrive ? CrewProtocol.ROLE_DRIVER
                        : key.canOpenDoors ? CrewProtocol.ROLE_GUARD
                        : CrewProtocol.ROLE_NONE;
                if (role != CrewProtocol.ROLE_NONE) {
                    vehicleId = vehicle.getId();
                    trainType = trainType(vehicle);
                }
            }
        } catch (Exception e) {
            // MTR's client data can be swapped underneath us mid-sync; treat as
            // "not riding" for this tick rather than crash.
            vehicleId = 0;
            role = CrewProtocol.ROLE_NONE;
            trainType = "";
        }
        localRole = role;
        AddonClientConfig config = AddonClientConfig.get();
        int vigilance = config.crewVigilanceEnabled
                ? MathHelper.clamp(config.crewVigilanceSeconds, CrewProtocol.MIN_VIGIL_SECONDS,
                CrewProtocol.MAX_VIGIL_SECONDS)
                : 0;
        boolean changed = vehicleId != sentVehicleId || role != sentRole || vigilance != sentVigilance
                || !trainType.equals(sentTrainType);
        boolean heartbeat = role != CrewProtocol.ROLE_NONE && now - lastSentAt >= CrewProtocol.HEARTBEAT_MILLIS;
        if (changed || heartbeat) {
            // A player with no role and nothing claimed before has nothing to say.
            if (role != CrewProtocol.ROLE_NONE || sentVehicleId != 0 || sentRole != CrewProtocol.ROLE_NONE) {
                sendRole(vehicleId, role, vigilance, trainType);
            }
            sentVehicleId = vehicleId;
            sentRole = role;
            sentVigilance = vigilance;
            sentTrainType = trainType;
            lastSentAt = now;
        }

        // Keys.
        boolean buzzerDown = buzzerKey.isPressed();
        boolean buzzerPressed = drain(buzzerKey) && !buzzerWasDown;
        buzzerWasDown = buzzerDown;
        if (buzzerPressed && role != CrewProtocol.ROLE_NONE) {
            send(CrewProtocol.BUZZ_C2S, PacketByteBufs.create());
            CrewSounds.playBuzzerFeedback();
        }

        boolean vigilanceDown = vigilanceKey.isPressed();
        boolean vigilancePressed = drain(vigilanceKey) && !vigilanceWasDown;
        vigilanceWasDown = vigilanceDown;
        if (vigilancePressed && role == CrewProtocol.ROLE_DRIVER) {
            send(CrewProtocol.VIGIL_ACK_C2S, PacketByteBufs.create());
        }

        drain(hornKey); // only the held state matters
        boolean wantHorn = role == CrewProtocol.ROLE_DRIVER && hornKey.isPressed();
        if (wantHorn != hornSent) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(wantHorn);
            send(CrewProtocol.HORN_C2S, buf);
            hornSent = wantHorn;
        }

        CrewState.tickBuzz(now);
        CrewSounds.tickVigilance(client);
        CrewSounds.tick();
    }

    /** The vehicle the player rides, whatever key they hold. */
    @Nullable
    private static VehicleExtension findRiddenVehicle() {
        for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
            if (VehicleRidingMovement.isRiding(vehicle.getId())) {
                return vehicle;
            }
        }
        return null;
    }

    /** Car 0's vehicle id (picks the horn server-side), capped to the wire limit. */
    private static String trainType(VehicleExtension vehicle) {
        var cars = vehicle.vehicleExtraData.immutableVehicleCars;
        if (cars.isEmpty()) {
            return "";
        }
        VehicleCar car = cars.get(0);
        String id = Objects.requireNonNullElse(car.getVehicleId(), "");
        return id.length() > CrewProtocol.MAX_TRAIN_TYPE ? id.substring(0, CrewProtocol.MAX_TRAIN_TYPE) : id;
    }

    /** Consumes queued presses; true if there was at least one. */
    private static boolean drain(KeyBinding key) {
        boolean any = false;
        while (key.wasPressed()) {
            any = true;
        }
        return any;
    }

    private static void sendRole(long vehicleId, byte role, int vigilance, String trainType) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(vehicleId);
        buf.writeByte(role);
        buf.writeVarInt(vigilance);
        buf.writeString(trainType, CrewProtocol.MAX_TRAIN_TYPE);
        send(CrewProtocol.ROLE_C2S, buf);
    }

    private static void send(Identifier channel, PacketByteBuf buf) {
        // A server without the addon never registered the channel; stay silent there.
        if (ClientPlayNetworking.canSend(channel)) {
            ClientPlayNetworking.send(channel, buf);
        }
    }

    // ------------------------------------------------------------- receivers

    private static void onStatus(long vehicleId, byte myRole, boolean hasDriver, boolean hasGuard, int flags,
                                 byte vigilanceState, int vigilanceMillisLeft, byte horn) {
        byte previousVigilance = CrewState.active() ? CrewState.vigilanceState : CrewProtocol.VIGIL_OFF;
        CrewState.vehicleId = vehicleId;
        CrewState.myRole = vehicleId == 0 ? CrewProtocol.ROLE_NONE : myRole;
        CrewState.hasDriver = hasDriver;
        CrewState.hasGuard = hasGuard;
        CrewState.flags = flags;
        CrewState.vigilanceState = vigilanceState;
        CrewState.vigilanceMillisLeft = vigilanceMillisLeft;
        CrewState.statusReceivedAt = System.currentTimeMillis();
        CrewState.horn = CrewProtocol.clampHorn(horn);
        if (CrewState.active() && vigilanceState == CrewProtocol.VIGIL_TRIPPED
                && previousVigilance != CrewProtocol.VIGIL_TRIPPED) {
            CrewSounds.playVigilanceTrip();
        }
    }

    private static void showMessage(MinecraftClient client, byte code) {
        if (client.player == null) {
            return;
        }
        Text text = switch (code) {
            case CrewProtocol.MSG_DRIVER_TAKEN -> Text.translatable("msg.station_announcer.crew.driver_taken");
            case CrewProtocol.MSG_GUARD_TAKEN -> Text.translatable("msg.station_announcer.crew.guard_taken");
            case CrewProtocol.MSG_WAIT_FOR_GUARD -> Text.translatable("msg.station_announcer.crew.wait_for_guard");
            case CrewProtocol.MSG_WAIT_FOR_DRIVER -> Text.translatable("msg.station_announcer.crew.wait_for_driver");
            case CrewProtocol.MSG_GUARD_HAS_DOORS -> Text.translatable("msg.station_announcer.crew.guard_has_doors");
            case CrewProtocol.MSG_VIGIL_TRIPPED ->
                    Text.translatable("msg.station_announcer.crew.vigil_tripped", vigilanceKeyName());
            case CrewProtocol.MSG_VIGIL_RESET -> Text.translatable("msg.station_announcer.crew.vigil_reset");
            case CrewProtocol.MSG_NO_PARTNER -> Text.translatable("msg.station_announcer.crew.no_partner");
            case CrewProtocol.MSG_SLOT_CLAIMED -> Text.translatable("msg.station_announcer.crew.slot_claimed");
            case CrewProtocol.MSG_SLOT_TAKEN -> Text.translatable("msg.station_announcer.crew.slot_taken");
            case CrewProtocol.MSG_SLOT_GONE -> Text.translatable("msg.station_announcer.crew.slot_gone");
            case CrewProtocol.MSG_SLOT_FROM_SIDING -> Text.translatable("msg.station_announcer.crew.slot_from_siding");
            case CrewProtocol.MSG_SLOT_NO_SHOW -> Text.translatable("msg.station_announcer.crew.slot_no_show");
            case CrewProtocol.MSG_SLOT_RELEASED -> Text.translatable("msg.station_announcer.crew.slot_released");
            case CrewProtocol.MSG_STARTUP_NEEDED -> Text.translatable("msg.station_announcer.crew.startup_needed",
                    com.stationannouncer.client.mtraddon.cab.CabHud.menuKeyName());
            case CrewProtocol.MSG_STARTED_UP -> Text.translatable("msg.station_announcer.crew.started_up");
            case CrewProtocol.MSG_INCIDENT_DOOR_FAULT -> Text.translatable("msg.station_announcer.crew.incident_door_fault");
            case CrewProtocol.MSG_INCIDENT_HELD -> Text.translatable("msg.station_announcer.crew.incident_held");
            case CrewProtocol.MSG_INCIDENT_SICK -> Text.translatable("msg.station_announcer.crew.incident_sick");
            case CrewProtocol.MSG_INCIDENT_ALARM -> Text.translatable("msg.station_announcer.crew.incident_alarm");
            case CrewProtocol.MSG_INCIDENT_CLEARED -> Text.translatable("msg.station_announcer.crew.incident_cleared");
            case CrewProtocol.MSG_HORN_SET -> Text.translatable("msg.station_announcer.crew.horn_set");
            case CrewProtocol.MSG_HORN_NOT_ALLOWED -> Text.translatable("msg.station_announcer.crew.horn_not_allowed");
            case CrewProtocol.MSG_SLOT_NO_PATH -> Text.translatable("msg.station_announcer.crew.slot_no_path");
            default -> null;
        };
        if (text != null && !com.stationannouncer.client.mtraddon.cab.CabHud.notice(text)) {
            client.player.sendMessage(text, true);
        }
    }
}
