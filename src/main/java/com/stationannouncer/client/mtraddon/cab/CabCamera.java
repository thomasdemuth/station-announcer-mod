package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.StationAnnouncerKeys;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.client.mtraddon.crew.CrewView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.VehicleCar;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.block.BlockPSDAPGDoorBase;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

/**
 * LEAN OUT OF THE WINDOW (MANUAL_DRIVING_PLAN.md §3.7): while the lean key is held, the CAMERA
 * (never the player entity) eases out through the side of the car over {@link #EASE_TICKS}
 * ticks, turned to look along the train, and eases back on release — the guard checking the
 * doors, or an OPTO driver at the front.
 *
 * <ul>
 *   <li><b>Who:</b> anyone riding an MTR vehicle who holds a crew role ({@link CrewView#active()})
 *       or a valid MTR driver/guard key — so it also works on a server without the crew addon.</li>
 *   <li><b>Side:</b> the side with a platform (blocks implementing MTR's {@code PlatformHelper}
 *       — what MTR itself opens doors for — or a PSD/APG door) beside the player's stretch of
 *       car, probed once when the lean starts; else the side the player stands nearer; else the
 *       side they look toward. Kept for the whole lean.</li>
 *   <li><b>How far:</b> to just outside the body side (floor half-width + {@link #WALL}), at least
 *       {@link #MIN_LEAN}, capped by {@code cabLeanMax}; a ray against the world's blocks
 *       (PSDs, tunnel walls, columns) shortens it, keeping {@link #CLEARANCE} off the hit.</li>
 *   <li><b>Look:</b> along the train toward its far end (the driver in front looks back, the guard
 *       at the back looks forward), slightly outward; the mouse still moves the view within
 *       ±{@link #FREE_LOOK} degrees of that.</li>
 * </ul>
 *
 * <p>Riding data comes from {@code CabSeatRidingMixin} (HEAD of MTR's {@code movePlayer} for the
 * car the player rides: the car's pose and the player's car-local position, previous frame).
 * The camera is moved in {@code CabCameraMixin} (TAIL of {@code Camera.update}). MTR anchors a
 * ridden train to the PLAYER, not the camera, so a moved camera sees the train from outside
 * correctly; but MTR's curve compensation subtracts {@code Camera.getYaw()}, so
 * {@code CabCameraRenderVehiclesMixin} hands it the unleaned yaw ({@link #unleanedYaw}).</p>
 *
 * <p>Also applies the cab seat's eye offset ({@link CabSeat#eyeOffset()}). Client thread only;
 * nothing runs while neither is in use.</p>
 */
@Environment(EnvType.CLIENT)
public final class CabCamera {
    static final int EASE_TICKS = 6;
    private static final double MIN_LEAN = 0.6;
    /** Body wall thickness past the outermost floor edge. */
    private static final double WALL = 0.3;
    private static final double CLEARANCE = 0.2;
    private static final float FREE_LOOK = 45;
    /** Outward component of the look direction (≈ 8° away from the car side). */
    private static final double LOOK_OUTWARD = 0.14;
    /** Riding data older than this is not trusted (the player left the car / stopped rendering). */
    private static final long STALE_NANOS = 500_000_000L;

    private static KeyBinding leanKey;

    // ---- riding data (written per frame by CabSeatRidingMixin for the ridden car) ----
    @Nullable
    private static PositionAndRotation ridePose;
    private static long rideVehicle;
    private static int rideCar;
    private static double rideX;
    private static double rideY;
    private static double rideZ;
    private static long rideNanos;
    /** Outermost |x| of the ridden car's floors (only measured while {@link #listening}). */
    private static double rideFloorHalfWidth;
    /** The lean key is down or the camera is still out: measure floors too. */
    private static boolean listening;
    /** When {@link #listening} last switched on / the floors were last measured (nanoTime). */
    private static long listenSince;
    private static long floorNanos;

    // ---- lean state (tick) ----
    private static float progress;
    private static float prevProgress;
    /** +1 = car-local +x side, −1 = −x side. */
    private static int side;
    /** Look toward car-local +z (true) or −z. */
    private static boolean lookPlusZ;
    private static double leanAmount;
    /** Player yaw relative to the car when the lean started (degrees). */
    private static float startRelYaw;

    // ---- per frame ----
    /** Degrees the camera yaw was turned this frame (0 = not leaning). */
    private static float yawOffset;

    private CabCamera() {
    }

    static void register() {
        leanKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.cab_lean", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT, StationAnnouncerKeys.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(CabCamera::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CabCamera::clear));
    }

    private static void clear() {
        ridePose = null;
        rideVehicle = 0;
        progress = prevProgress = 0;
        yawOffset = 0;
        listening = false;
    }

    // ------------------------------------------------------- movePlayer side

    /**
     * HEAD of MTR's {@code movePlayer} for the car the player currently rides (after the cab
     * seat was applied). Field writes only, unless {@link #listening}.
     */
    public static void recordRide(long vehicleId, int carNumber, PositionAndRotation pose, double x, double y, double z,
                                  ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways) {
        ridePose = pose;
        rideVehicle = vehicleId;
        rideCar = carNumber;
        rideX = x;
        rideY = y;
        rideZ = z;
        rideNanos = System.nanoTime();
        if (listening) {
            double half = 0;
            for (int i = 0; i < floorsAndDoorways.size(); i++) {
                ObjectBooleanImmutablePair<Box> entry = floorsAndDoorways.get(i);
                if (entry.rightBoolean()) {
                    Box box = entry.left();
                    half = Math.max(half, Math.max(Math.abs(box.getMinXMapped()), Math.abs(box.getMaxXMapped())));
                }
            }
            rideFloorHalfWidth = half;
            floorNanos = rideNanos;
        }
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftClient client) {
        prevProgress = progress;
        boolean down = leanKey.isPressed();
        boolean wasListening = listening;
        listening = down || progress > 0;
        if (!listening) {
            yawOffset = 0; // fully back: MTR's curve compensation sees the plain camera again
            return;
        }
        if (!wasListening) {
            listenSince = System.nanoTime();
        }
        boolean want = down && canLean(client);
        if (want && progress == 0) {
            // Wait (≤ 1 tick) for a frame that measured the floors, then pick side and distance.
            if (floorNanos <= listenSince || !start(client)) {
                want = false;
            }
        }
        float step = 1f / EASE_TICKS;
        progress = want ? Math.min(1f, progress + step) : Math.max(0f, progress - step);
    }

    private static boolean canLean(MinecraftClient client) {
        if (client.player == null || client.world == null || client.currentScreen != null
                || !client.options.getPerspective().isFirstPerson() || !AddonClientConfig.get().cabLeanEnabled) {
            return false;
        }
        long vehicleId = rideVehicle;
        if (vehicleId == 0 || ridePose == null || System.nanoTime() - rideNanos > STALE_NANOS
                || !VehicleRidingMovement.isRiding(vehicleId)) {
            return false;
        }
        if (CrewView.active() && CrewView.vehicleId() == vehicleId) {
            return true;
        }
        VehicleExtension vehicle = find(vehicleId);
        return vehicle != null && VehicleRidingMovement.getValidHoldingKey(vehicle.vehicleExtraData.getDepotId()) != null;
    }

    /** Picks side, look direction and distance for a new lean. False = no usable data. */
    private static boolean start(MinecraftClient client) {
        PositionAndRotation pose = ridePose;
        VehicleExtension vehicle = find(rideVehicle);
        if (pose == null || vehicle == null || client.player == null || client.world == null) {
            return false;
        }
        ObjectImmutableList<VehicleCar> cars = vehicle.vehicleExtraData.immutableVehicleCars;
        if (rideCar < 0 || rideCar >= cars.size()) {
            return false;
        }
        VehicleCar car = cars.get(rideCar);
        double length = Math.max(1, car.getLength());
        double halfWidth = rideFloorHalfWidth > 0.2 ? rideFloorHalfWidth + WALL : Math.max(1, car.getWidth() / 2);

        // Side: platform, else nearer side, else the side the player looks toward.
        int platform = platformSide(client.world, pose, halfWidth, rideY, rideZ);
        if (platform != 0) {
            side = platform;
        } else if (Math.abs(rideX) > 0.2) {
            side = rideX > 0 ? 1 : -1;
        } else {
            double yaw = Math.toRadians(client.player.getYaw());
            double wx = -Math.sin(yaw);
            double wz = Math.cos(yaw);
            double c = pose.yaw;
            double lx = wx * Math.cos(c) - wz * Math.sin(c); // world → car-local (inverse rotateY)
            side = lx >= 0 ? 1 : -1;
        }

        // Look toward the train's far end from where the player stands (+z = toward car i + 1).
        double along = (rideCar + MathHelper.clamp(rideZ / length + 0.5, 0, 1)) / cars.size();
        lookPlusZ = along < 0.5;

        // halfWidth is the body side; the eye goes WALL further out
        leanAmount = MathHelper.clamp(halfWidth + WALL - side * rideX, MIN_LEAN,
                Math.max(MIN_LEAN, AddonClientConfig.get().cabLeanMax));
        startRelYaw = relativeYaw(client.player.getYaw(), pose);
        return true;
    }

    /** +1 / −1 = a platform on that car-local side next to the player, 0 = none or both. */
    private static int platformSide(World world, PositionAndRotation pose, double halfWidth, double y, double z) {
        int plus = 0;
        int minus = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int s = -1; s <= 1; s += 2) {
            int hits = 0;
            for (double dz = -2; dz <= 2; dz += 1) {
                for (double dy = -2; dy <= 1; dy += 1) {
                    for (double dx = 0.5; dx <= 1.5; dx += 1) {
                        setWorld(pos, pose, s * (halfWidth + dx), y + dy, z + dz);
                        Block block = world.getBlockState(pos).getBlock();
                        if (block instanceof PlatformHelper || block instanceof BlockPSDAPGDoorBase) {
                            hits++;
                        }
                    }
                }
            }
            if (s > 0) {
                plus = hits;
            } else {
                minus = hits;
            }
        }
        return plus > minus ? 1 : minus > plus ? -1 : 0;
    }

    /** Car-local point → world block (pitch ignored: a few blocks of probe either way). */
    private static void setWorld(BlockPos.Mutable out, PositionAndRotation pose, double x, double y, double z) {
        double c = pose.yaw;
        double cos = Math.cos(c);
        double sin = Math.sin(c);
        out.set(MathHelper.floor(pose.position.x + x * cos + z * sin),
                MathHelper.floor(pose.position.y + y),
                MathHelper.floor(pose.position.z + z * cos - x * sin));
    }

    /** Player yaw relative to the car's local +z, degrees. */
    private static float relativeYaw(float playerYaw, PositionAndRotation pose) {
        // MC yaw of car-local +z is −deg(carYaw)
        return MathHelper.wrapDegrees(playerYaw + (float) Math.toDegrees(pose.yaw));
    }

    @Nullable
    private static VehicleExtension find(long vehicleId) {
        if (vehicleId == 0) {
            return null;
        }
        for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
            if (vehicle.getId() == vehicleId) {
                return vehicle;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- camera

    /** Cheap gate for {@code CabCameraMixin}: anything to do this frame? */
    public static boolean active() {
        return progress > 0 || prevProgress > 0 || CabSeat.isSeated();
    }

    /**
     * TAIL of {@code Camera.update} (first person only). Returns the new camera position or null
     * to leave it; {@link #cameraYaw} is valid after a non-null return.
     */
    @Nullable
    public static Vec3d cameraPos(BlockView area, Entity focused, Vec3d pos, float tickDelta) {
        yawOffset = 0;
        pendingEased = 0;
        double eye = CabSeat.eyeOffset();
        float p = MathHelper.lerp(tickDelta, prevProgress, progress);
        PositionAndRotation pose = ridePose;
        if (p <= 0 || pose == null || !(focused instanceof net.minecraft.client.network.ClientPlayerEntity)) {
            return eye == 0 ? null : pos.add(0, eye, 0);
        }
        float eased = p * p * (3 - 2 * p);
        Vec3d base = eye == 0 ? pos : pos.add(0, eye, 0);
        double c = pose.yaw;
        // car-local (side, 0, 0) → world (MTR rotateY)
        Vec3d out = new Vec3d(side * Math.cos(c), 0, -side * Math.sin(c));
        double amount = leanAmount * eased;
        if (amount > 0.01 && area instanceof World world) {
            Vec3d end = base.add(out.multiply(amount + CLEARANCE));
            BlockHitResult hit = world.raycast(new RaycastContext(base, end, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, focused));
            if (hit.getType() != HitResult.Type.MISS) {
                amount = Math.max(0, Math.min(amount, hit.getPos().distanceTo(base) - CLEARANCE));
            }
        }
        pendingEased = eased;
        return base.add(out.multiply(amount));
    }

    private static float pendingEased;

    /** The camera yaw while leaning, eased from vanilla's {@code yaw}; records the offset. */
    public static float cameraYaw(float vanillaYaw) {
        PositionAndRotation pose = ridePose;
        if (pendingEased <= 0 || pose == null) {
            yawOffset = 0;
            return vanillaYaw;
        }
        double c = pose.yaw;
        double lz = lookPlusZ ? 1 : -1;
        double lx = side * LOOK_OUTWARD;
        double wx = lx * Math.cos(c) + lz * Math.sin(c);
        double wz = lz * Math.cos(c) - lx * Math.sin(c);
        float target = (float) Math.toDegrees(Math.atan2(-wx, wz));
        float free = MathHelper.clamp(MathHelper.wrapDegrees(relativeYaw(vanillaYaw, pose) - startRelYaw), -FREE_LOOK, FREE_LOOK);
        float delta = MathHelper.wrapDegrees(target + free - vanillaYaw) * pendingEased;
        pendingEased = 0;
        yawOffset = delta;
        return vanillaYaw + delta;
    }

    /** Third person this frame: the camera is vanilla's, so nothing to undo for MTR. */
    public static void resetYaw() {
        yawOffset = 0;
        pendingEased = 0;
    }

    /** For MTR's riding-curve compensation: the camera yaw without the lean. */
    public static float unleanedYaw(float cameraYaw) {
        return cameraYaw - yawOffset;
    }
}
