package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.ModContent;
import com.stationannouncer.client.mtraddon.DrivingHud;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.block.BlockPSDAPGDoorBase;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.resource.VehicleResourceCache;
import java.util.ArrayDeque;

/**
 * THE CINEMATIC STARTUP (Thomas, 2026-10-05: "an action sequence using the train we are on and
 * the world ... we see the lights come on, then cut to the battery, we see it come on, then we
 * test the opening and closing of the doors, etc"; after his first test: "have each step require
 * user input, and at the end, automatically bring up the service menu to choose a departure").
 *
 * <p>Six shots, hard cuts between them. Each shot plays a short automatic INTRO, then HOLDS —
 * the camera keeps drifting gently, a pulsing prompt sits in the bottom bar — until the player
 * does that step's input, then plays its OUTRO and cuts. The player sets the pace; Esc skips the
 * whole thing at any moment (still counts as started up).</p>
 * <ol>
 *   <li><b>Key in</b> — first person, the view tips down to the desk. {@code [Space] Turn the key}
 *       (Space or Enter): key-turn sounds, the view tilts back up to the line.</li>
 *   <li><b>Lights</b> — outside, a 3/4 view of the leading car from the platform side, dark.
 *       {@code [Space] Lights on}: two flickers, then up (fluorescent clicks + hum).</li>
 *   <li><b>Battery</b> — along the car side at about car-floor height, aimed slightly up. {@code [Space] Battery on}:
 *       relay clunk, the BATT lamp lights.</li>
 *   <li><b>Door test</b> — the platform side, looking at a doorway. The player presses MTR's REAL
 *       door key ({@code key.mtr.train_toggle_doors}, its bound key shown): the first press opens
 *       the doors; once they are open the prompt changes to close; the second press closes them.
 *       If the doors do not move within 2 s (no platform: MTR keeps them shut; the guard has the
 *       doors), the press still counts. Doors that start open go close → open.</li>
 *   <li><b>Brake test</b> — beside the leading bogie at car-floor height. The player presses MTR's REAL brake key until the
 *       power level reads ≤ −7 (live notch in the prompt, "B3 … B7"); air hiss, "B7 holding ✔".
 *       Twelve presses that never reach B7 (lost packets) also pass.</li>
 *   <li><b>Ready</b> — back in the cab, chime, a short beat.</li>
 * </ol>
 * At the end (completed or skipped) {@link CabStartup#complete()} sends {@code STARTUP_C2S}; then,
 * if the train is a manual-siding train still in its siding ({@code getIsManualAllowed() &&
 * !getIsOnRoute()} — slots can only be taken there), the cab menu opens on its Service tab so the
 * driver picks a departure; otherwise back to the game.
 *
 * <h2>How it is put on screen</h2>
 * <ul>
 *   <li>A transparent {@link Screen} ({@link Letterbox}) is open for the whole sequence: it freezes
 *       look and movement input, hides the cursor, takes the step keys and Esc, and draws the
 *       letterbox, prompts/captions, the dark "lights off" overlay and the lamp strip.
 *       {@code options.hudHidden} is set meanwhile (hotbar, crosshair, hand, cab HUD); restored after.</li>
 *   <li>While a screen is open vanilla delivers no key bindings, so the screen matches MTR's door
 *       and brake bindings itself ({@link KeyBinding#matchesKey}, first press only, repeats ignored)
 *       and forwards each press with {@link KeyBinding#setPressed} (3 ticks down / 2 up — MTR's
 *       per-frame {@code VehicleRidingMovement.tick} sends one step per press start).</li>
 *   <li>The camera: {@code CabCameraMixin} (TAIL of {@code Camera.update}) calls {@link #camera}
 *       first and, while it returns true, applies {@link #x}/{@link #y}/{@link #z}/{@link #yaw}/
 *       {@link #pitch} instead of the cab seat / lean offsets. Exterior shots switch the perspective
 *       to third person (back) so the player's body is drawn in the cab window; restored after.
 *       Exterior dollies ease in asymptotically (they keep creeping while the shot holds) with a
 *       small sway.</li>
 *   <li>MTR rotates a RIDDEN train by {@code playerYaw − Camera.getYaw()} (curve compensation) and
 *       flips it by π when the two differ by more than 90°, so {@code CabCameraRenderVehiclesMixin}
 *       passes MTR's reads through {@link #mtrYaw}: the yaw vanilla computed this frame (the
 *       player's own view) while a shot is applied. MTR anchors the ridden train to the PLAYER
 *       (riding offset + its renderer entity), the camera position only feeds occlusion culling.</li>
 * </ul>
 *
 * <h2>Shot placement</h2>
 * Car poses come from {@code Vehicle.getVehicleCarsAndPositions()} turned into MTR's own
 * {@link PositionAndRotation} (exactly as {@code CabPassengers} does); car-local +z points to car
 * i + 1, the leading car's cab end is {@code z = −L} (not reversed) / {@code +L} (reversed). The
 * platform side is probed once (blocks implementing MTR's {@code PlatformHelper}, PSD/APG doors);
 * without one, the side that has side doorways, else the right of travel. Every candidate camera is
 * scored ({@code score}): ~1 block of free space at both dolly ends and the middle, a clear line to
 * the car side, and COLLIDER rays (glass, panes, leaves block) to the leading car's ends, roof line
 * and side and to the view's centre and corners 5 m out. The first candidate with 92 % of the rays
 * clear is used, else the clearest above 55 %; candidates go closer / further ahead, then higher,
 * then the other side, then head-on and high; none = that shot plays in first person (prompt,
 * sounds and the real key presses still happen).
 *
 * <p>Client thread only (ticks via {@link CabStartup#tick}, camera per frame). Dev hook
 * {@code #cab-cinematic [stop | step | <shot 0-5> [hold [tick | prompt]]]} ({@link #devHook}).</p>
 */
@Environment(EnvType.CLIENT)
public final class CabCinematic {
    static final int KEY_IN = 0;
    static final int LIGHTS = 1;
    static final int BATTERY = 2;
    static final int DOORS = 3;
    static final int BRAKE = 4;
    static final int READY = 5;
    static final int SHOTS = 6;
    /** Timeline tick at which each shot waits for the player (−1 = no input). */
    private static final int[] HOLD_AT = {10, 6, 8, 6, 6, -1};
    /** Timeline length of each shot in ticks (intro + outro; the hold adds whatever the player takes). */
    private static final int[] LENGTH = {30, 36, 34, 32, 30, 26};
    private static final int LETTERBOX_TICKS = 8;
    private static final int PRESS_TICKS = 3;
    private static final int GAP_TICKS = 2;
    /** Door test: a press counts once the doors have not moved for this long (2 s). */
    private static final int DOOR_STUCK_TICKS = 40;
    private static final int DOOR_MAX_WAIT_TICKS = 80;
    private static final int BRAKE_GIVE_UP_PRESSES = 12;
    /** Exterior dolly: progress = 1 − e^(−t/τ). */
    private static final float DOLLY_TAU = 50;
    /** Dev hook: {@code hold prompt} freezes at the shot's hold point (with its prompt). */
    private static final int HOLD_PROMPT = Integer.MIN_VALUE;
    /** Timeline tick each shot's caption appears (events() sets captionTick there). */
    private static final int[] CAPTION_AT = {11, 20, 9, 7, 7, 1};
    private static final String[] CAPTION = {"key", "lights", "battery", "doors", "brake", "ready"};

    // ---- sequence state (client thread) ----
    private static boolean running;
    /** False for a dev preview: no STARTUP_C2S and no service menu at the end. */
    private static boolean sendOnFinish;
    private static int shot;
    /** Timeline tick of the shot; stops at {@link #HOLD_AT} until {@link #inputDone}. */
    private static int shotTick;
    /** Ticks since the shot began, holds included (camera drift, prompt pulse). */
    private static int camTicks;
    /** The player did this shot's input (the timeline runs on past the hold). */
    private static boolean inputDone;
    /** Dev hold: the shot stays frozen at this tick (no presses, no sounds). −1 = playing. */
    private static int frozenTick = -1;
    private static long vehicleId;
    @Nullable
    private static VehicleExtension liveVehicle;
    private static boolean savedHudHidden;
    @Nullable
    private static Perspective savedPerspective;
    @Nullable
    private static Letterbox screen;

    // ---- per-sequence geometry ----
    /** Car-local side of the platform (+1 / −1), or of the doors when there is none. */
    private static int platformSide;
    private static boolean hasPlatform;

    // ---- current shot ----
    private static boolean exterior;
    private static Vec3d camFrom = Vec3d.ZERO;
    private static Vec3d camTo = Vec3d.ZERO;
    private static Vec3d look = Vec3d.ZERO;
    private static int captionTick = -1;
    private static boolean subOk;

    // ---- lamps / steps ----
    private static boolean batteryOn;
    /** Door presses forwarded this sequence (0, 1, 2): one alone is undone on a skip. */
    private static int doorPresses;
    /** 0 = waiting for the first press, 1 = doors moving, 2 = waiting for the second, 3 = done. */
    private static int doorStage;
    private static int doorStageTick;
    private static boolean doorWasOpen;
    private static double doorValueAtPress;
    private static int brakePresses;

    // ---- camera output (read by CabCameraMixin right after camera() returned true) ----
    public static double x;
    public static double y;
    public static double z;
    public static float yaw;
    public static float pitch;
    /** The yaw vanilla gave the camera this frame (the player's own view), for MTR. */
    private static float vanillaYaw;
    private static boolean applied;

    // ---- programmatic key presses (keep running after the sequence: a skip may close the doors) ----
    private static final ArrayDeque<KeyBinding> PRESSES = new ArrayDeque<>();
    @Nullable
    private static KeyBinding held;
    private static int heldTicks;
    private static int gapTicks;

    private CabCinematic() {
    }

    // ================================================================= control

    static boolean isRunning() {
        return running;
    }

    /** Shot index while running (0–5), else −1. */
    static int currentShot() {
        return running ? shot : -1;
    }

    /**
     * Starts the sequence on the train the player drives. {@code real} = send STARTUP_C2S at the
     * end (or on skip) and offer the service menu. False when there is no driven train.
     */
    static boolean start(MinecraftClient client, boolean real) {
        return start(client, real, 0, -1);
    }

    private static boolean start(MinecraftClient client, boolean real, int firstShot, int hold) {
        if (running || client.player == null || client.world == null) {
            return false;
        }
        VehicleExtension vehicle = DrivingHud.findDrivenVehicle();
        if (vehicle == null) {
            return false;
        }
        vehicleId = vehicle.getId();
        liveVehicle = vehicle;
        sendOnFinish = real;
        frozenTick = -1;
        batteryOn = firstShot > BATTERY;
        doorPresses = 0;
        try {
            probeSides(client.world, vehicle);
        } catch (RuntimeException e) {
            platformSide = 1;
            hasPlatform = false;
        }
        savedHudHidden = client.options.hudHidden;
        savedPerspective = client.options.getPerspective();
        client.options.hudHidden = true;
        running = true;
        screen = new Letterbox();
        client.setScreen(screen);
        beginShot(client, vehicle, MathHelper.clamp(firstShot, 0, SHOTS - 1));
        if (hold >= 0) {
            int holdAt = HOLD_AT[shot];
            // Default: mid-shot, but never before the caption is up (Lights' caption comes late).
            int defaultTick = hold == HOLD_PROMPT && holdAt >= 0 ? holdAt
                    : Math.max(LENGTH[shot] / 2, CAPTION_AT[shot] + 6);
            frozenTick = MathHelper.clamp(hold == Integer.MAX_VALUE || hold == HOLD_PROMPT ? defaultTick : hold, 0, LENGTH[shot] - 1);
            inputDone = holdAt >= 0 && frozenTick > holdAt;
            if (inputDone && shot == DOORS) {
                doorStage = 3;
            }
            for (int t = 1; t <= frozenTick; t++) {
                shotTick = t;
                events(vehicle, false);
            }
            shotTick = frozenTick;
            camTicks = 40;
        }
        return true;
    }

    /** Skip (Esc / the Skip button): ends now, still counts as started up. */
    static void skip() {
        finish(sendOnFinish, true);
    }

    /** The driver role or the train went away: end without telling the server anything. */
    static void cancel() {
        finish(false, true);
    }

    private static void finish(boolean send, boolean closeScreen) {
        if (!running) {
            return;
        }
        running = false;
        applied = false;
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.hudHidden = savedHudHidden;
        if (savedPerspective != null) {
            client.options.setPerspective(savedPerspective);
        }
        if (doorPresses == 1 && frozenTick < 0) {
            queue(doorKey()); // stopped between the two door presses: leave the doors as we found them
        }
        boolean serviceMenu = send && closeScreen && inSiding(find(vehicleId));
        if (send) {
            CabStartup.complete();
        }
        Letterbox ours = screen;
        screen = null;
        if (closeScreen && ours != null && client.currentScreen == ours) {
            if (serviceMenu) {
                CabScreen.open(client, 1); // replaces ours: the driver picks a departure next
            } else {
                client.setScreen(null);
            }
        }
    }

    /** A manual-siding train still in its siding: the only place a timetable slot can be taken. */
    private static boolean inSiding(@Nullable VehicleExtension vehicle) {
        if (vehicle == null) {
            return false;
        }
        try {
            return vehicle.vehicleExtraData.getIsManualAllowed() && !vehicle.getIsOnRoute();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ================================================================= tick

    /** END_CLIENT_TICK (through CabStartup.tick). */
    static void tick(MinecraftClient client) {
        runPresses();
        if (!running) {
            return;
        }
        VehicleExtension vehicle = find(vehicleId);
        liveVehicle = vehicle;
        if (vehicle == null || client.player == null || client.world == null || !VehicleRidingMovement.isRiding(vehicleId)) {
            cancel();
            return;
        }
        if (client.currentScreen != screen) {
            finish(sendOnFinish, false); // something else took the screen
            return;
        }
        if (frozenTick >= 0) {
            shotTick = frozenTick;
            return;
        }
        camTicks++;
        try {
            if (waiting()) {
                waitTick(vehicle);
                if (!inputDone) {
                    return;
                }
            }
            shotTick++;
            events(vehicle, true);
        } catch (RuntimeException e) {
            // MTR data mid-sync: the shot plays on without its event
        }
        if (shotTick >= LENGTH[shot]) {
            if (shot + 1 >= SHOTS) {
                finish(sendOnFinish, true);
            } else {
                beginShot(client, vehicle, shot + 1);
            }
        }
    }

    /** At the hold point, input still to come. */
    private static boolean waiting() {
        return HOLD_AT[shot] >= 0 && !inputDone && shotTick >= HOLD_AT[shot];
    }

    /** Per tick while holding: the door test's stages, the brake test's live notch. */
    private static void waitTick(VehicleExtension vehicle) {
        if (shot == DOORS && doorStage == 1) {
            double value = vehicle.persistentVehicleData.getDoorValue();
            boolean reached = doorWasOpen ? value <= 0.05 : value >= 0.9;
            boolean stuck = camTicks - doorStageTick >= DOOR_STUCK_TICKS && Math.abs(value - doorValueAtPress) < 0.05;
            if (reached || stuck || camTicks - doorStageTick >= DOOR_MAX_WAIT_TICKS) {
                doorStage = 2;
            }
        } else if (shot == BRAKE) {
            if (vehicle.vehicleExtraData.getPowerLevel() <= -Vehicle.MAX_POWER_LEVEL || brakePresses >= BRAKE_GIVE_UP_PRESSES) {
                inputDone = true;
            }
        }
    }

    /** Space / Enter in the key, lights and battery shots. */
    private static void stepKey() {
        if ((shot == KEY_IN || shot == LIGHTS || shot == BATTERY) && !inputDone && shotTick >= 2) {
            inputDone = true;
        }
    }

    /** The player pressed MTR's door key during the door test: forward it, move the test on. */
    private static void doorKeyPressed() {
        if (shot != DOORS || inputDone || doorStage >= 3) {
            return;
        }
        VehicleExtension vehicle = liveVehicle;
        queue(doorKey());
        if (doorStage == 0) {
            doorStage = 1;
            doorPresses = 1;
            doorStageTick = camTicks;
            doorValueAtPress = vehicle == null ? 0 : vehicle.persistentVehicleData.getDoorValue();
        } else {
            doorStage = 3; // second press (also while the doors are still moving)
            doorPresses = 2;
            inputDone = true;
        }
    }

    /** The player pressed MTR's brake key during the brake test: forward it, a soft hiss. */
    private static void brakeKeyPressed() {
        if (shot != BRAKE || inputDone) {
            return;
        }
        queue(brakeKey());
        brakePresses++;
        sound(true, SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.3f + 0.05f * Math.min(brakePresses, 8), 0.15f);
    }

    /** Timed events of the current shot at {@link #shotTick}; {@code live} = sounds too. */
    private static void events(VehicleExtension vehicle, boolean live) {
        int t = shotTick;
        int h = HOLD_AT[shot];
        switch (shot) {
            case KEY_IN -> {
                if (t == 3) {
                    sound(live, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, 1.9f, 0.35f); // key into the desk
                } else if (t == h + 1) {
                    captionTick = t;
                    sound(live, SoundEvents.BLOCK_LEVER_CLICK, 0.7f, 0.7f);
                    sound(live, SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, 1.4f, 0.4f);
                }
            }
            case LIGHTS -> {
                if (t == h + 1) {
                    sound(live, SoundEvents.BLOCK_LEVER_CLICK, 0.9f, 0.5f);
                } else if (t == h + 2 || t == h + 10) {
                    sound(live, SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, 2.0f, 0.5f);
                    sound(live, SoundEvents.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.2f);
                } else if (t == h + 14) {
                    captionTick = t;
                    sound(live, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.7f, 0.45f);
                    sound(live, SoundEvents.BLOCK_BEACON_AMBIENT, 1.9f, 0.35f);
                }
            }
            case BATTERY -> {
                if (t == h + 1) {
                    captionTick = t;
                    batteryOn = true;
                    sound(live, SoundEvents.BLOCK_PISTON_CONTRACT, 1.6f, 0.45f);
                    sound(live, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 0.55f);
                } else if (t == h + 5) {
                    sound(live, SoundEvents.BLOCK_BEACON_AMBIENT, 1.4f, 0.25f);
                }
            }
            case DOORS -> {
                if (t == h + 1) {
                    captionTick = t;
                }
            }
            case BRAKE -> {
                if (t == h + 1) {
                    captionTick = t;
                    subOk = vehicle.vehicleExtraData.getPowerLevel() <= -Vehicle.MAX_POWER_LEVEL;
                    sound(live, SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.4f);
                    sound(live, SoundEvents.BLOCK_LAVA_EXTINGUISH, 1.9f, 0.2f);
                }
            }
            case READY -> {
                if (t == 1) {
                    captionTick = t;
                    sound(live, ModContent.CHIME, 1.0f, 0.8f);
                }
            }
            default -> {
            }
        }
    }

    // ================================================================= shots

    private static void beginShot(MinecraftClient client, VehicleExtension vehicle, int which) {
        shot = which;
        shotTick = 0;
        camTicks = 0;
        inputDone = false;
        captionTick = -1;
        subOk = false;
        exterior = false;
        if (which == DOORS) {
            doorStage = 0;
            doorWasOpen = vehicle.vehicleExtraData.getDoorMultiplier() > 0 || vehicle.persistentVehicleData.getDoorValue() > 0.5;
        }
        if (which == BRAKE) {
            brakePresses = 0;
        }
        if (which != KEY_IN && which != READY && client.world != null && client.player != null) {
            try {
                exterior = place(client.world, client.player, vehicle, which);
            } catch (RuntimeException e) {
                exterior = false;
            }
        }
        client.options.setPerspective(exterior ? Perspective.THIRD_PERSON_BACK : Perspective.FIRST_PERSON);
    }

    /** The leading car: pose, half sizes, cab end sign, and the car's bogie centres (car-local z). */
    private record Lead(PositionAndRotation pose, VehicleCar car, int index, int total, double halfWidth,
                        double halfLength, int front, double[] bogieZ, TransportMode mode) {
        Vec3d at(double lx, double ly, double lz) {
            Vector v = pose.transformForwards(new Vector(lx, ly, lz), Vector::rotateX, Vector::rotateY, Vector::add);
            return new Vec3d(v.x, v.y, v.z);
        }
    }

    @Nullable
    private static Lead lead(VehicleExtension vehicle) {
        ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>>> cars
                = vehicle.getVehicleCarsAndPositions();
        if (cars.isEmpty()) {
            return null;
        }
        boolean reversed = vehicle.getReversed();
        int index = reversed ? cars.size() - 1 : 0;
        VehicleCar car = cars.get(index).left();
        ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>> bogies = cars.get(index).right();
        if (bogies.isEmpty() || bogies.size() > 2) {
            return null; // MTR's car transform handles one or two bogies only
        }
        ObjectArrayList<PositionAndRotation> bogiePoses = new ObjectArrayList<>(bogies.size());
        for (ObjectObjectImmutablePair<Vector, Vector> bogie : bogies) {
            bogiePoses.add(new PositionAndRotation(bogie.left(), bogie.right(), false));
        }
        PositionAndRotation pose = new PositionAndRotation(bogiePoses, car, false);
        double cos = Math.cos(pose.yaw);
        double sin = Math.sin(pose.yaw);
        double[] bogieZ = new double[bogies.size()];
        for (int i = 0; i < bogies.size(); i++) {
            ObjectObjectImmutablePair<Vector, Vector> bogie = bogies.get(i);
            double dx = (bogie.left().x + bogie.right().x) / 2 - pose.position.x;
            double dz = (bogie.left().z + bogie.right().z) / 2 - pose.position.z;
            bogieZ[i] = dx * sin + dz * cos; // inverse of MTR's rotateY (pitch ignored)
        }
        return new Lead(pose, car, index, cars.size(), Math.max(0.5, car.getWidth() / 2), Math.max(1, car.getLength() / 2),
                reversed ? 1 : -1, bogieZ, vehicle.getTransportMode());
    }

    /** Once per sequence: which side the platform is on (else the doors, else right of travel). */
    private static void probeSides(World world, VehicleExtension vehicle) {
        Lead lead = lead(vehicle);
        hasPlatform = false;
        platformSide = 1;
        if (lead == null) {
            return;
        }
        int[] hits = new int[2];
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int s = 0; s < 2; s++) {
            int side = s == 0 ? -1 : 1;
            for (double lz = -lead.halfLength() + 0.5; lz < lead.halfLength(); lz += 1) {
                for (double dx = 0.6; dx <= 1.5; dx += 0.8) {
                    for (double ly = -0.5; ly <= 1.6; ly += 1) {
                        Vec3d p = lead.at(side * (lead.halfWidth() + dx), ly, lz);
                        pos.set(MathHelper.floor(p.x), MathHelper.floor(p.y), MathHelper.floor(p.z));
                        Block block = world.getBlockState(pos).getBlock();
                        if (block instanceof PlatformHelper || block instanceof BlockPSDAPGDoorBase) {
                            hits[s]++;
                        }
                    }
                }
            }
        }
        if (hits[0] != hits[1] && Math.max(hits[0], hits[1]) >= 3) {
            hasPlatform = true;
            platformSide = hits[1] > hits[0] ? 1 : -1;
            return;
        }
        hasPlatform = hits[0] > 0 && hits[0] == hits[1]; // an island: both sides, keep the default side
        float[] doors = doorways(lead);
        int plus = 0;
        int minus = 0;
        if (doors != null) {
            for (int i = 0; i + 1 < doors.length; i += 2) {
                if (doors[i] > 0) {
                    plus++;
                } else {
                    minus++;
                }
            }
        }
        if (plus != minus && (plus == 0 || minus == 0)) {
            platformSide = plus > 0 ? 1 : -1;
        } else {
            platformSide = -lead.front(); // right of travel (+x when not reversed)
        }
    }

    /** One possible camera for a shot: dolly ends, what it looks at, a point on the car side it must see. */
    private record Candidate(Vec3d from, Vec3d to, Vec3d target, Vec3d anchor) {
    }

    /** A candidate this clear (share of frame rays that reach) is taken at once, in list order. */
    private static final double GOOD_SCORE = 0.92;
    /** Below this the best candidate is not used (the shot plays in first person). */
    private static final double MIN_SCORE = 0.55;

    /**
     * Picks a clear camera for an exterior shot. False = play it in first person.
     *
     * <p>Rig feedback (2026-10-05, Driveable siding): one ray to the car side squeezed through a gap
     * between brick pillars and the shot was two-thirds wall and glass. Now every candidate is
     * scored ({@link #score}): ~1 block of free space around the camera at both dolly ends and the
     * middle, plus rays from the camera to the leading car's front, back, roof line and side and
     * to the corners and centre of the view 5 m out — all with COLLIDER shapes, so glass, panes and
     * leaves block. The first candidate with {@link #GOOD_SCORE} wins; else the best one above
     * {@link #MIN_SCORE}; head-on and higher positions are the last candidates in each list. A second
     * pass with a tighter free space runs only when the first finds nothing.</p>
     */
    private static boolean place(World world, Entity player, VehicleExtension vehicle, int which) {
        Lead lead = lead(vehicle);
        if (lead == null) {
            return false;
        }
        double h = lead.halfWidth();
        double l = lead.halfLength();
        int f = lead.front();
        int platform = platformSide;
        int track = hasPlatform ? -platformSide : platformSide;
        double eye = hasPlatform ? 2.6 : 1.8;
        java.util.List<Candidate> list = new java.util.ArrayList<>();
        switch (which) {
            case LIGHTS -> {
                // 3/4 front view at eye height, drifting back along the car; then higher; then head-on.
                for (int side : new int[]{platform, -platform}) {
                    for (double up : new double[]{0, 1.6}) {
                        for (double lat : new double[]{5.5, 4.2, 3.0}) {
                            for (double ahead : new double[]{0.6 + lat * 0.5, 2.0 + lat}) {
                                double cy = eye + up;
                                list.add(new Candidate(lead.at(side * (h + lat), cy, f * (l + ahead)),
                                        lead.at(side * (h + lat - 0.5), cy, f * (l + ahead - 2.4)),
                                        lead.at(0, 1.6, f * (l - 1.8)), lead.at(side * (h + 0.15), 1.7, f * (l - 1.5))));
                            }
                        }
                    }
                }
                for (double back : new double[]{4.5, 3.2, 6.0}) {
                    list.add(new Candidate(lead.at(0, 2.2, f * (l + back)), lead.at(0, 2.1, f * (l + back - 1.2)),
                            lead.at(0, 1.6, f * (l - 1)), lead.at(0, 2.0, f * (l + 0.3))));
                }
                for (int side : new int[]{platform, -platform}) {
                    list.add(new Candidate(lead.at(side * (h + 3.5), eye + 3.5, f * (l + 3)),
                            lead.at(side * (h + 3.0), eye + 3.2, f * (l + 1)),
                            lead.at(0, 1.6, f * (l - 2)), lead.at(side * (h + 0.15), 2.5, f * (l - 1.5))));
                }
            }
            case BATTERY -> {
                // Along the body side at about car-floor height, aimed slightly up at the side.
                for (int side : new int[]{track, -track}) {
                    for (double cy : new double[]{1.0, 1.25, 1.9}) {
                        for (double lat : new double[]{3.0, 2.4, 3.6, 1.8}) { // 1.5–2 m further out than round 2
                            list.add(new Candidate(lead.at(side * (h + lat), cy, f * (l - 0.3)),
                                    lead.at(side * (h + lat), cy, f * (l - 1.9)),
                                    lead.at(side * (h + 0.1), cy + 0.9, -f * l), lead.at(side * (h + 0.15), cy, f * (l - 1.0))));
                        }
                    }
                }
            }
            case DOORS -> {
                double dz = doorZ(lead, platform);
                for (int side : new int[]{platform, -platform}) {
                    double sideEye = side == platform ? eye : 1.8;
                    for (double up : new double[]{0, 1.2}) {
                        for (double lat : new double[]{3.2, 2.2, 1.5}) {
                            double cy = sideEye + up;
                            list.add(new Candidate(lead.at(side * (h + lat), cy, dz - f * 1.8),
                                    lead.at(side * (h + Math.max(1.0, lat - 0.6)), cy, dz - f * 1.1),
                                    lead.at(side * h, 1.9, dz), lead.at(side * (h + 0.15), Math.min(cy, 2.0), dz)));
                        }
                    }
                }
            }
            case BRAKE -> {
                double bz = f * (l - 2.5);
                double best = -Double.MAX_VALUE;
                for (double candidate : lead.bogieZ()) {
                    if (candidate * f > best) {
                        best = candidate * f;
                        bz = candidate;
                    }
                }
                // Beside the leading bogie at about car-floor height, aimed slightly up at the car side.
                for (int side : new int[]{track, -track}) {
                    for (double cy : new double[]{0.95, 1.2, 1.7}) {
                        for (double lat : new double[]{2.8, 2.2, 3.4, 1.6}) { // 1.5–2 m further out than round 2
                            list.add(new Candidate(lead.at(side * (h + lat), cy, bz + f * 1.6),
                                    lead.at(side * (h + lat - 0.2), cy, bz + f * 1.0),
                                    lead.at(side * h * 0.9, cy + 0.25, bz), lead.at(side * (h + 0.15), cy, bz)));
                        }
                    }
                }
            }
            default -> {
                return false;
            }
        }
        for (double room : new double[]{0.85, 0.45}) {
            Candidate best = null;
            double bestScore = MIN_SCORE;
            for (Candidate c : list) {
                double score = score(world, player, lead, c, room);
                if (score >= GOOD_SCORE) {
                    set(c.from(), c.to(), c.target());
                    return true;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best != null) {
                set(best.from(), best.to(), best.target());
                return true;
            }
        }
        return false;
    }

    private static void set(Vec3d from, Vec3d to, Vec3d target) {
        camFrom = from;
        camTo = to;
        look = target;
    }

    /**
     * Share (0–1) of frame rays that reach, or 0 when the camera has no room or cannot see the
     * car side at all. Rays: from the dolly middle (and the ends for the car side) to the leading
     * car's front, back, roof line, side centre and the shot's target, and to the centre and corners
     * of the view 5 m out.
     */
    private static double score(World world, Entity player, Lead lead, Candidate c, double room) {
        Vec3d mid = c.from().add(c.to()).multiply(0.5);
        if (!roomy(world, c.from(), room) || !roomy(world, mid, room) || !roomy(world, c.to(), room)) {
            return 0;
        }
        if (!clear(world, player, c.anchor(), extend(c.anchor(), c.from())) || !clear(world, player, c.anchor(), extend(c.anchor(), c.to()))
                || !clear(world, player, c.from(), c.to())) {
            return 0;
        }
        double l = lead.halfLength();
        Vec3d[] carPoints = {
                lead.at(0, 1.8, l), lead.at(0, 1.8, -l), lead.at(0, 3.2, l * 0.6), lead.at(0, 3.2, -l * 0.6),
                lead.at(0, 3.2, 0), lead.at(lead.halfWidth() * Math.signum(mid.subtract(lead.pose().position.x,
                        lead.pose().position.y, lead.pose().position.z).dotProduct(sideAxis(lead))), 1.6, 0),
                c.target()};
        int total = 0;
        int reach = 0;
        for (Vec3d point : carPoints) {
            total++;
            Vec3d d = point.subtract(mid);
            double length = d.length();
            // stop short of the car (its body is no block, but what touches it may be)
            Vec3d end = length > 1 ? mid.add(d.multiply((length - 0.6) / length)) : point;
            if (clear(world, player, mid, end)) {
                reach++;
            }
        }
        // View frustum 5 m out: centre and corners (letterboxed: ±35° across, ±14° up/down).
        Vec3d forward = c.target().subtract(mid).normalize();
        Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1E-6) {
            right = new Vec3d(1, 0, 0);
        }
        right = right.normalize();
        Vec3d up = right.crossProduct(forward).normalize();
        double across = Math.tan(Math.toRadians(35));
        double vertical = Math.tan(Math.toRadians(14));
        double[][] dirs = {{0, 0}, {-across, -vertical}, {across, -vertical}, {-across, vertical}, {across, vertical},
                {-across, 0}, {across, 0}, {0, -vertical}};
        for (double[] dir : dirs) {
            total++;
            Vec3d ray = forward.add(right.multiply(dir[0])).add(up.multiply(dir[1])).normalize().multiply(5);
            if (clear(world, player, mid, mid.add(ray))) {
                reach++;
            }
        }
        return (double) reach / total;
    }

    /** World direction of the leading car's local +x (pitch ignored). */
    private static Vec3d sideAxis(Lead lead) {
        double c = lead.pose().yaw;
        return new Vec3d(Math.cos(c), 0, -Math.sin(c));
    }

    /** No block with a collision shape within {@code room} of the point along any axis. */
    private static boolean roomy(World world, Vec3d p, double room) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        double[][] offsets = {{0, 0, 0}, {room, 0, 0}, {-room, 0, 0}, {0, room, 0}, {0, -room, 0}, {0, 0, room}, {0, 0, -room},
                {room * 0.7, 0, room * 0.7}, {-room * 0.7, 0, room * 0.7}, {room * 0.7, 0, -room * 0.7}, {-room * 0.7, 0, -room * 0.7}};
        for (double[] o : offsets) {
            pos.set(MathHelper.floor(p.x + o[0]), MathHelper.floor(p.y + o[1]), MathHelper.floor(p.z + o[2]));
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Vec3d extend(Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        double length = d.length();
        return length < 1E-6 ? b : b.add(d.multiply(0.3 / length));
    }

    /** COLLIDER shapes: glass, panes, leaves and fences block the view too. */
    private static boolean clear(World world, Entity player, Vec3d a, Vec3d b) {
        if (a.squaredDistanceTo(b) < 1E-6) {
            return true;
        }
        return world.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                player)).getType() == HitResult.Type.MISS;
    }

    /** Car-local z of the side doorway to film: on {@code side}, nearest the front third of the car. */
    private static double doorZ(Lead lead, int side) {
        double want = lead.front() * lead.halfLength() * 0.4;
        float[] doors = doorways(lead);
        double best = want;
        double bestDistance = Double.MAX_VALUE;
        if (doors != null) {
            for (int pass = 0; pass < 2 && bestDistance == Double.MAX_VALUE; pass++) {
                for (int i = 0; i + 1 < doors.length; i += 2) {
                    if (pass == 0 && Math.signum(doors[i]) != side) {
                        continue;
                    }
                    double distance = Math.abs(doors[i + 1] - want);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = doors[i + 1];
                    }
                }
            }
        }
        return best;
    }

    /**
     * Side doorway centres of the leading car (x, z pairs, car space) from MTR's vehicle resource
     * (the boxes MTR lets players walk through), boxes of one doorway merged — as CabPassengers.
     * Null when the resource is not cached or has none.
     */
    @Nullable
    private static float[] doorways(Lead lead) {
        float[][] found = {null};
        try {
            CustomResourceLoader.getVehicleById(lead.mode(), lead.car().getVehicleId(), details -> {
                VehicleResourceCache cache = details.left().getCachedVehicleResource(lead.index(), lead.total(), false);
                if (cache != null) {
                    found[0] = merge(cache.doorways);
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
        return found[0] == null || found[0].length == 0 ? null : found[0];
    }

    private static float[] merge(ObjectImmutableList<Box> boxes) {
        float[] points = new float[boxes.size() * 3];
        int count = 0;
        for (Box box : boxes) {
            float bx = (float) ((box.getMinXMapped() + box.getMaxXMapped()) / 2);
            float bz = (float) ((box.getMinZMapped() + box.getMaxZMapped()) / 2);
            if (Math.abs(bx) < 0.3f) {
                continue; // end / gangway doorway
            }
            boolean merged = false;
            for (int i = 0; i < count; i++) {
                int o = i * 3;
                if (Math.signum(points[o]) == Math.signum(bx) && Math.abs(points[o + 1] / points[o + 2] - bz) < 0.8f) {
                    points[o] += bx;
                    points[o + 1] += bz;
                    points[o + 2] += 1;
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                int o = count * 3;
                points[o] = bx;
                points[o + 1] = bz;
                points[o + 2] = 1;
                count++;
            }
        }
        float[] result = new float[count * 2];
        for (int i = 0; i < count; i++) {
            result[i * 2] = points[i * 3] / points[i * 3 + 2];
            result[i * 2 + 1] = points[i * 3 + 1] / points[i * 3 + 2];
        }
        return result;
    }


    // ================================================================= camera

    /** Timeline time for drawing: does not run on past the hold point while the shot waits. */
    private static float timelineTime(float tickDelta) {
        if (frozenTick >= 0) {
            return frozenTick;
        }
        if (waiting() || (HOLD_AT[shot] >= 0 && !inputDone && shotTick + tickDelta > HOLD_AT[shot])) {
            return Math.min(shotTick, Math.max(0, HOLD_AT[shot]));
        }
        return Math.min(LENGTH[shot], shotTick + tickDelta);
    }

    private static float camTime(float tickDelta) {
        return frozenTick >= 0 ? camTicks : camTicks + tickDelta;
    }

    /**
     * TAIL of {@code Camera.update} ({@code CabCameraMixin}), every frame, before the cab seat and
     * lean offsets. True = the caller applies {@link #x}/{@link #y}/{@link #z}/{@link #yaw}/
     * {@link #pitch} and skips its own offsets. {@code pos}/{@code vanillaYawIn}/{@code vanillaPitch}
     * = what vanilla just computed.
     */
    public static boolean camera(Vec3d pos, float vanillaYawIn, float vanillaPitch, float tickDelta) {
        if (!running) {
            applied = false;
            return false;
        }
        applied = true;
        vanillaYaw = vanillaYawIn;
        float time = timelineTime(tickDelta);
        float cam = camTime(tickDelta);
        if (exterior) {
            // Ease in for good (keeps creeping while the shot holds), with a small sway.
            float e = 1 - (float) Math.exp(-cam / DOLLY_TAU);
            x = MathHelper.lerp(e, camFrom.x, camTo.x) + Math.sin(cam * 0.045) * 0.12;
            y = MathHelper.lerp(e, camFrom.y, camTo.y) + Math.sin(cam * 0.031 + 1) * 0.08;
            z = MathHelper.lerp(e, camFrom.z, camTo.z) + Math.cos(cam * 0.038) * 0.12;
            double dx = look.x - x;
            double dy = look.y - y;
            double dz = look.z - z;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            return true;
        }
        // First person: from the eye (plus the cab seat's eye offset).
        x = pos.x;
        y = pos.y + CabSeat.eyeOffset();
        z = pos.z;
        yaw = vanillaYawIn;
        float tilt = 0;
        if (shot == KEY_IN) {
            // Down to the desk during the intro, held (breathing), back up to the line after the turn.
            int h = HOLD_AT[KEY_IN];
            tilt = time <= h + 3 ? 24 * ease(MathHelper.clamp((time - 1) / 9, 0, 1))
                    : 24 * (1 - ease(MathHelper.clamp((time - h - 3) / 12, 0, 1)));
            if (!inputDone) {
                tilt += (float) Math.sin(cam * 0.08) * 0.6f;
            }
        }
        pitch = MathHelper.clamp(vanillaPitch + tilt, -90, 90);
        return true;
    }

    /**
     * MTR's reads of {@code Camera.getYaw()} for a ridden train ({@code CabCameraRenderVehiclesMixin}):
     * while a shot is applied, the player's own view yaw — the train must not swing with the
     * cinematic camera. Identity otherwise.
     */
    public static float mtrYaw(float cameraYaw) {
        return running && applied ? vanillaYaw : cameraYaw;
    }

    private static float ease(float p) {
        return p * p * (3 - 2 * p);
    }

    // ================================================================= keys + sounds

    @Nullable
    private static KeyBinding doorKey() {
        return binding("key.mtr.train_toggle_doors");
    }

    @Nullable
    private static KeyBinding brakeKey() {
        return binding("key.mtr.train_brake");
    }

    @Nullable
    private static KeyBinding binding(String translationKey) {
        for (KeyBinding binding : MinecraftClient.getInstance().options.allKeys) {
            if (binding.getTranslationKey().equals(translationKey)) {
                return binding;
            }
        }
        return null;
    }

    private static String keyName(@Nullable KeyBinding binding) {
        return "[" + (binding == null ? "?" : binding.getBoundKeyLocalizedText().getString()) + "]";
    }

    private static void queue(@Nullable KeyBinding binding) {
        if (binding != null && PRESSES.size() < 32) {
            PRESSES.add(binding);
        }
    }

    /** One press = PRESS_TICKS down, GAP_TICKS up (MTR counts a step on the first frame of a hold). */
    private static void runPresses() {
        if (held != null) {
            if (--heldTicks <= 0) {
                held.setPressed(false);
                held = null;
                gapTicks = GAP_TICKS;
            }
            return;
        }
        if (gapTicks > 0) {
            gapTicks--;
            return;
        }
        KeyBinding next = PRESSES.poll();
        if (next != null) {
            held = next;
            heldTicks = PRESS_TICKS;
            next.setPressed(true);
        }
    }

    private static void sound(boolean live, SoundEvent event, float pitchValue, float volume) {
        if (live) {
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(event, pitchValue, volume));
        }
    }

    @Nullable
    private static VehicleExtension find(long id) {
        if (id == 0) {
            return null;
        }
        for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
            if (vehicle.getId() == id) {
                return vehicle;
            }
        }
        return null;
    }

    /** Power notch as the driver reads it: P3, N, B5, EB. */
    private static String notch(int power) {
        if (power < -Vehicle.MAX_POWER_LEVEL) {
            return "EB";
        }
        return power > 0 ? "P" + power : power < 0 ? "B" + -power : "N";
    }

    // ================================================================= dev hook

    /**
     * {@code #cab-cinematic} — the real sequence (sends STARTUP_C2S and opens the service menu at
     * the end when this client is the driver and startup is pending); {@code #cab-cinematic stop} —
     * end it; {@code #cab-cinematic <shot 0-5>} — a preview from that shot (waits for input like the
     * real one, no STARTUP_C2S); {@code ... hold [tick | prompt]} — frozen at that tick (default:
     * mid-shot after the step, never before the caption is up; {@code prompt} = the hold point with its prompt) for screenshots,
     * until {@code stop}. While a real or preview
     * run waits, {@code #cab-cinematic step} does the current step's input (Space / door key /
     * brake key) for the headless rig.
     */
    public static void devHook(String[] parts) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (parts.length > 1 && parts[1].equals("stop")) {
            if (running) {
                frozenTick = -1;
                finish(false, true);
            }
            return;
        }
        if (parts.length > 1 && parts[1].equals("step")) {
            if (running && frozenTick < 0) {
                if (shot == DOORS) {
                    doorKeyPressed();
                } else if (shot == BRAKE) {
                    brakeKeyPressed();
                } else {
                    stepKey();
                }
            }
            return;
        }
        if (running) {
            finish(false, true);
        }
        int first = 0;
        int hold = -1;
        boolean preview = false;
        try {
            if (parts.length > 1) {
                first = Integer.parseInt(parts[1]);
                preview = true;
            }
            if (parts.length > 2 && parts[2].equals("hold")) {
                hold = parts.length <= 3 ? Integer.MAX_VALUE : parts[3].equals("prompt") ? HOLD_PROMPT : Integer.parseInt(parts[3]);
            }
        } catch (NumberFormatException e) {
            com.stationannouncer.StationAnnouncer.LOGGER.warn("[cab-cinematic] usage: #cab-cinematic [stop | step | <shot 0-5> [hold [tick|prompt]]]");
            return;
        }
        boolean real = !preview && CrewView.isDriver() && !CrewView.flag(CrewProtocol.FLAG_STARTED_UP);
        boolean started = start(client, real, first, hold);
        com.stationannouncer.StationAnnouncer.LOGGER.info("[cab-cinematic] {} shot={} hold={} real={} platformSide={} hasPlatform={} exterior={}",
                started ? "started" : "no driven train", first, hold, real, platformSide, hasPlatform, exterior);
    }

    // ================================================================= screen

    /**
     * The overlay screen for the whole sequence: letterbox bars, the prompt / caption in the bottom
     * bar, shot dots + "Esc: skip" in the top bar, the dark "lights off" overlay, and the lamp strip.
     * Never pauses; takes the step keys; the cursor is hidden.
     */
    static final class Letterbox extends Screen {
        /** MTR's door / brake key is down (first press forwarded, repeats ignored until released). */
        private boolean doorDown;
        private boolean brakeDown;

        Letterbox() {
            super(Text.empty());
        }

        @Override
        protected void init() {
            hideCursor(true);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                skip();
                return true;
            }
            if (frozenTick >= 0) {
                return true;
            }
            KeyBinding door = doorKey();
            if (shot == DOORS && door != null && door.matchesKey(keyCode, scanCode)) {
                if (!doorDown) {
                    doorDown = true;
                    doorKeyPressed();
                }
                return true;
            }
            KeyBinding brake = brakeKey();
            if (shot == BRAKE && brake != null && brake.matchesKey(keyCode, scanCode)) {
                if (!brakeDown) {
                    brakeDown = true;
                    brakeKeyPressed();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                stepKey();
            }
            return true; // everything else is swallowed: controls are locked
        }

        @Override
        public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
            KeyBinding door = doorKey();
            if (door != null && door.matchesKey(keyCode, scanCode)) {
                doorDown = false;
            }
            KeyBinding brake = brakeKey();
            if (brake != null && brake.matchesKey(keyCode, scanCode)) {
                brakeDown = false;
            }
            return true;
        }

        @Override
        public void close() {
            skip();
        }

        @Override
        public void removed() {
            hideCursor(false);
            if (running && screen == this) {
                finish(sendOnFinish, false);
            }
        }

        private void hideCursor(boolean hide) {
            if (client != null) {
                GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR,
                        hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
            }
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            if (!running) {
                return;
            }
            int w = width;
            int h = height;
            float time = timelineTime(delta);
            float cam = camTime(delta);
            int length = LENGTH[shot];

            // "Lights off": dark until the press, then two flickers and up.
            if (shot == LIGHTS) {
                float dark = inputDone ? lightsDark(time - HOLD_AT[LIGHTS]) : 0.88f;
                if (dark > 0.004f) {
                    c.fill(0, 0, w, h, ((int) (dark * 255) << 24) | 0x05070A);
                }
            }

            // Letterbox, sliding in on the first shot and out on the last.
            float bars = 1;
            if (shot == KEY_IN && frozenTick < 0) {
                bars = ease(MathHelper.clamp(time / LETTERBOX_TICKS, 0, 1));
            } else if (shot == READY && frozenTick < 0) {
                bars = ease(MathHelper.clamp((length - time) / LETTERBOX_TICKS, 0, 1));
            }
            int barH = Math.round(Math.max(26, h * 0.13f) * bars);
            c.fill(0, 0, w, barH, 0xFF000000);
            c.fill(0, h - barH, w, h, 0xFF000000);
            if (bars < 0.98f) {
                return;
            }

            // Top bar: shot dots, skip hint.
            int dotsW = SHOTS * 8;
            for (int i = 0; i < SHOTS; i++) {
                int color = i == shot ? CabHud.BLUE : i < shot ? CabHud.DIM : CabHud.OFF;
                disc(c, w / 2 - dotsW / 2 + i * 8 + 4, barH / 2, 2, color);
            }
            String hint = Text.translatable("gui.station_announcer.cab.cine_skip_esc").getString();
            c.drawText(textRenderer, hint, w - 8 - textRenderer.getWidth(hint), barH / 2 - 4, CabHud.FAINT, false);

            // Bottom bar: the pulsing prompt while the shot waits for the player, else the caption.
            String main = null;
            String sub = null;
            int alpha = 255;
            int subColor = CabHud.DIM;
            boolean prompting = HOLD_AT[shot] >= 0 && !inputDone && shotTick >= Math.min(HOLD_AT[shot], 3);
            if (prompting) {
                main = prompt();
                alpha = (int) (255 * (0.6f + 0.4f * (float) Math.sin(cam * 0.25f)));
                if (shot == BRAKE) {
                    sub = Text.translatable("gui.station_announcer.cab.cine_brake_sub").getString();
                }
            } else if (captionTick >= 0) {
                float fade = MathHelper.clamp((time - captionTick) / 4f, 0, 1);
                if (fade > 0.05f) {
                    alpha = (int) (fade * 255);
                    main = Text.translatable("gui.station_announcer.cab.cine_" + CAPTION[shot]).getString();
                    boolean ok = shot == BRAKE && subOk;
                    sub = Text.translatable("gui.station_announcer.cab.cine_" + CAPTION[shot] + (ok ? "_ok" : "_sub")).getString();
                    subColor = ok ? CabHud.GREEN : CabHud.DIM;
                }
            }
            if (main != null) {
                int a = Math.max(5, Math.min(255, alpha)) << 24;
                int top = h - barH + 4;
                int mainW = (int) (textRenderer.getWidth(main) * 1.5f);
                int subW = sub == null ? 0 : textRenderer.getWidth(sub);
                int left = w / 2 - Math.max(mainW, subW) / 2;
                c.fill(left - 8, top + 1, left - 6, top + Math.min(barH - 6, 24), a | (CabHud.BLUE & 0xFFFFFF));
                c.getMatrices().push();
                c.getMatrices().translate(left, top, 0);
                c.getMatrices().scale(1.5f, 1.5f, 1);
                c.drawText(textRenderer, main, 0, 0, a | 0xFFFFFF, false);
                c.getMatrices().pop();
                if (sub != null && barH >= 30) {
                    int sa = prompting ? 0xC0 << 24 : a;
                    c.drawText(textRenderer, sub, left, top + 15, sa | (subColor & 0xFFFFFF), false);
                }
            }

            // Lamp strip (from the battery shot on): BATT · DOORS · B7.
            if (shot >= BATTERY && shot <= BRAKE) {
                lamps(c, w, h - barH - 4);
            }
        }

        /** This shot's prompt, with the key to press. */
        private static String prompt() {
            String space = "[" + Text.translatable("key.keyboard.space").getString() + "]";
            switch (shot) {
                case KEY_IN:
                    return Text.translatable("gui.station_announcer.cab.cine_prompt_key", space).getString();
                case LIGHTS:
                    return Text.translatable("gui.station_announcer.cab.cine_prompt_lights", space).getString();
                case BATTERY:
                    return Text.translatable("gui.station_announcer.cab.cine_prompt_battery", space).getString();
                case DOORS: {
                    if (doorStage == 1) {
                        return Text.translatable("gui.station_announcer.cab.cine_doors_moving").getString();
                    }
                    boolean open = (doorStage == 0) != doorWasOpen;
                    return Text.translatable(open ? "gui.station_announcer.cab.cine_prompt_doors_open"
                            : "gui.station_announcer.cab.cine_prompt_doors_close", keyName(doorKey())).getString();
                }
                case BRAKE: {
                    VehicleExtension vehicle = liveVehicle;
                    int power = 0;
                    if (vehicle != null) {
                        try {
                            power = vehicle.vehicleExtraData.getPowerLevel();
                        } catch (RuntimeException ignored) {
                            // disposed between ticks
                        }
                    }
                    return Text.translatable("gui.station_announcer.cab.cine_prompt_brake", keyName(brakeKey()), notch(power)).getString();
                }
                default:
                    return "";
            }
        }

        private void lamps(DrawContext c, int w, int bottom) {
            VehicleExtension vehicle = liveVehicle;
            double doorValue = 0;
            int power = 0;
            if (vehicle != null) {
                try {
                    doorValue = vehicle.persistentVehicleData.getDoorValue();
                    power = vehicle.vehicleExtraData.getPowerLevel();
                } catch (RuntimeException ignored) {
                    // disposed between ticks
                }
            }
            String[] labels = {tr("battery"), tr("doors"), tr("brake")};
            int[] colors = {batteryOn ? CabHud.GREEN : CabHud.OFF, doorValue > 0.02 ? CabHud.AMBER : CabHud.OFF,
                    power <= -Vehicle.MAX_POWER_LEVEL ? CabHud.RED : CabHud.OFF};
            int cell = 0;
            for (String label : labels) {
                cell = Math.max(cell, textRenderer.getWidth(label) + 16);
            }
            int pw = cell * 3 + 4;
            int ph = 15;
            int px = w - 8 - pw;
            int py = bottom - ph;
            c.fill(px, py, px + pw, py + ph, CabHud.PANEL);
            for (int i = 0; i < 3; i++) {
                int cx = px + 4 + i * cell;
                disc(c, cx + 4, py + 7, 3, colors[i]);
                c.drawText(textRenderer, labels[i], cx + 11, py + 4, colors[i] == CabHud.OFF ? CabHud.FAINT : CabHud.TEXT, false);
            }
        }

        private static String tr(String lamp) {
            return Text.translatable("gui.station_announcer.cab.cine_lamp_" + lamp).getString();
        }

        private static void disc(DrawContext c, int cx, int cy, int r, int color) {
            for (int dy = -r; dy <= r; dy++) {
                int hw = (int) Math.floor(Math.sqrt(r * r - dy * dy + r * 0.8));
                c.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, color);
            }
        }

        /** Darkness of the lights shot, ticks after the press: dark, flicker, flicker, up. */
        private static float lightsDark(float o) {
            if (o < 2) {
                return 0.88f;
            }
            if (o < 3.5f) {
                return 0.2f;
            }
            if (o < 10) {
                return 0.85f;
            }
            if (o < 12.5f) {
                return 0.1f;
            }
            if (o < 14) {
                return 0.55f;
            }
            if (o < 21) {
                return 0.55f * (1 - (o - 14) / 7f);
            }
            return 0;
        }
    }
}
