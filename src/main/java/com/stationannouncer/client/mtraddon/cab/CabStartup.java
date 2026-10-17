package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * The short, skippable startup (Thomas: "a short ritual, and should be skippable"; 2026-10-05:
 * the three-button version "works, but isn't very fun" → a cinematic). "Start the train" in the
 * cab menu plays {@link CabCinematic} — key in, lights, battery, a real door test, a real brake
 * test to B7, ready — and at its end (or on a skip) the server is told the train is started up
 * and power is released (until then {@code CrewEngine} holds a manual train at neutral). "Skip",
 * or the per-player setting {@code crewSkipStartup}, sends it straight away. Client thread;
 * resets when the vehicle or the driver role changes.
 */
@Environment(EnvType.CLIENT)
public final class CabStartup {
    public static final int KEY_IN = 0;
    public static final int BATTERY = 1;
    public static final int BRAKE_TEST = 2;
    public static final int DONE = 3;

    private static long vehicleId;
    private static boolean sent;

    private CabStartup() {
    }

    /** Per tick: the cinematic, vehicle tracking, auto-skip when the player chose to. */
    static void tick(MinecraftClient client) {
        CabCinematic.tick(client);
        if (!CrewView.isDriver() || CrewView.flag(CrewProtocol.FLAG_STARTED_UP)) {
            if (!CrewView.isDriver()) {
                vehicleId = 0;
                sent = false;
            }
            return;
        }
        if (CrewView.vehicleId() != vehicleId) {
            vehicleId = CrewView.vehicleId();
            sent = false;
        }
        if (AddonClientConfig.get().crewSkipStartup && !CabCinematic.isRunning()) {
            complete();
        }
    }

    /** True while this driver still has to start the train up (the HUD prompts for it). */
    public static boolean pending() {
        return CrewView.isDriver() && !CrewView.flag(CrewProtocol.FLAG_STARTED_UP);
    }

    /** KEY_IN while pending (BATTERY while the cinematic plays), DONE once started up. */
    public static int step() {
        return !pending() ? DONE : CabCinematic.isRunning() ? BATTERY : KEY_IN;
    }

    /** The cab menu's "Start the train": plays the cinematic (or, with no train to film, just starts up). */
    static void begin() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!pending() || CabCinematic.isRunning()) {
            return;
        }
        if (!CabCinematic.start(client, true)) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_LEVER_CLICK, 1.0f, 0.6f));
            complete();
        }
    }

    static void complete() {
        if (CabCinematic.isRunning()) {
            CabCinematic.skip(); // calls back here once it has ended
            return;
        }
        if (!sent && ClientPlayNetworking.canSend(CrewProtocol.STARTUP_C2S)) {
            sent = true;
            ClientPlayNetworking.send(CrewProtocol.STARTUP_C2S, PacketByteBufs.create());
        }
    }
}
