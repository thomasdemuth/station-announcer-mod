package com.stationannouncer.client.track;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The game-side half of the editor's World view: owns the detached camera and everything it
 * changes, and records what each world frame was really drawn with.
 *
 * <h2>Camera</h2>
 * A client-only {@link MarkerEntity} (never added to the world) becomes the camera entity — the
 * sign editor's proven trick ({@code SignWorldView}). Each frame {@link #place} puts it at the EASED
 * eye the view computed and aims it with {@link TrackWorldViewMath#aim} so the target lands exactly in
 * the middle of the editor pane while the world is drawn full-window.
 *
 * <h2>Eye height</h2>
 * The game camera adds a tick-eased eye height on top of the entity's y ({@code Camera.cameraY},
 * gliding by halves per tick from the player's 1.62 to the marker's 0). Measuring and subtracting it
 * frame by frame jittered (the lerp depends on each frame's tick delta). Instead, on open the camera is
 * pointed at the marker and {@code updateEyeHeight()} is run until it has converged to 0 — from then on
 * the camera sits exactly at the entity. close() does the same for the player (no "rising from the
 * feet" when the view closes).
 *
 * <h2>What is changed and restored</h2>
 * camera entity, {@code options.hudHidden} (also hides hand, crosshair and block outline),
 * perspective (forced first person — third person would pull the camera back), and
 * {@code client.chunkCullingEnabled}: OFF for as long as the view is open. Occlusion culling
 * flood-fills visibility from the camera's section, asynchronously in vanilla; a camera that moves
 * across sections every frame keeps working from a stale set and sections pop out ("things
 * un-render"); without it only the frustum and the distance decide. FOV is NOT touched (the sprint
 * FOV multiplier eases back to 1 on its own because the camera is not a player). Everything is
 * restored by {@link #close}, which is idempotent and is also called by a WATCHDOG on every client tick
 * when the view was not rendered for {@value #STALE_MILLIS} ms, no screen is open any more (Esc or a
 * path that skipped {@code removed()}), or the world changed / went away (disconnect, dimension
 * change). Only one World view owns the camera at a time.
 *
 * <h2>Frame capture</h2>
 * A {@link WorldRenderEvents#START} listener (installed lazily on first use — no init call needed)
 * copies the frame's projection matrix (FOV, aspect, bobbing / nausea when they apply), the camera
 * rotation matrix the world renderer gets, and the camera position into {@link #FRAME}. The screen is
 * drawn after the world in the same frame, so {@code FRAME} is exactly what the user sees (the eased
 * pose of the previous {@link #place}) while the overlay is drawn and the next mouse event is handled.
 */
@Environment(EnvType.CLIENT)
final class TrackWorldViewCamera {
    static final long STALE_MILLIS = 1500;

    /** The last rendered world frame (shared: there is one world render per frame). */
    static final TrackWorldViewMath FRAME = new TrackWorldViewMath();
    private static final float[] CAPTURE_P = new float[16];
    private static final float[] CAPTURE_V = new float[16];
    private static final double[] CAPTURE_PD = new double[16];
    private static final double[] CAPTURE_VD = new double[16];
    private static boolean hooksInstalled;
    private static TrackWorldViewCamera owner;

    private final MinecraftClient client = MinecraftClient.getInstance();
    private MarkerEntity marker;
    private Entity previousCamera;
    private boolean previousHudHidden;
    private Perspective previousPerspective;
    private boolean previousCulling = true;
    private ClientWorld openWorld;
    private long lastUseNanos;

    /** Where the player's view was when the camera was taken (the fly-in starts here). */
    private Vec3d startEye = Vec3d.ZERO;
    private float startYaw;
    private float startPitch;

    /** World y of the rails the view looks at (the clip never puts the eye below it). Set by the view. */
    double railLevel = Double.NEGATIVE_INFINITY;

    static void installHooks() {
        if (hooksInstalled) {
            return;
        }
        hooksInstalled = true;
        WorldRenderEvents.START.register(TrackWorldViewCamera::capture);
        ClientTickEvents.END_CLIENT_TICK.register(client -> watchdog());
    }

    private static void capture(WorldRenderContext context) {
        try {
            context.projectionMatrix().get(CAPTURE_P);
            context.matrixStack().peek().getPositionMatrix().get(CAPTURE_V);
            for (int i = 0; i < 16; i++) {
                CAPTURE_PD[i] = CAPTURE_P[i];
                CAPTURE_VD[i] = CAPTURE_V[i];
            }
            final Vec3d pos = context.camera().getPos();
            FRAME.set(CAPTURE_PD, CAPTURE_VD, pos.x, pos.y, pos.z);
        } catch (RuntimeException ignored) {
            // never break world rendering over an editor overlay
        }
    }

    private static void watchdog() {
        final TrackWorldViewCamera view = owner;
        if (view == null) {
            return;
        }
        final MinecraftClient client = view.client;
        final boolean stale = client.world == null || client.world != view.openWorld || client.currentScreen == null
            || (System.nanoTime() - view.lastUseNanos) / 1_000_000L > STALE_MILLIS;
        if (stale) {
            view.close();
        }
    }

    boolean active() {
        return marker != null;
    }

    Vec3d startEye() {
        return startEye;
    }

    float startYaw() {
        return startYaw;
    }

    float startPitch() {
        return startPitch;
    }

    /** Takes the camera (no-op when already active). False when there is no world/player. */
    boolean open() {
        installHooks();
        lastUseNanos = System.nanoTime();
        if (marker != null) {
            return true;
        }
        if (client.world == null || client.player == null) {
            return false;
        }
        if (owner != null && owner != this) {
            owner.close();
        }
        final Camera camera = client.gameRenderer.getCamera();
        if (camera.isReady()) {
            startEye = camera.getPos();
            startYaw = camera.getYaw();
            startPitch = camera.getPitch();
        } else {
            startEye = client.player.getCameraPosVec(1);
            startYaw = client.player.getYaw();
            startPitch = client.player.getPitch();
        }
        openWorld = client.world;

        marker = new MarkerEntity(EntityType.MARKER, client.world);
        previousCamera = client.getCameraEntity();
        previousHudHidden = client.options.hudHidden;
        previousPerspective = client.options.getPerspective();
        previousCulling = client.chunkCullingEnabled;
        client.options.hudHidden = true;
        client.options.setPerspective(Perspective.FIRST_PERSON);
        client.chunkCullingEnabled = false;
        put(startEye.x, startEye.y, startEye.z, startYaw, startPitch);
        client.setCameraEntity(marker);
        settleEyeHeight(marker);
        owner = this;
        return true;
    }

    /** Puts everything back. Idempotent; safe after a disconnect. */
    void close() {
        if (owner == this) {
            owner = null;
        }
        if (marker == null) {
            return;
        }
        final MarkerEntity ours = marker;
        marker = null;
        try {
            client.options.hudHidden = previousHudHidden;
            if (previousPerspective != null) {
                client.options.setPerspective(previousPerspective);
            }
            client.chunkCullingEnabled = previousCulling;
            if (client.cameraEntity == ours) {
                final Entity back = previousCamera != null && previousCamera.isAlive()
                    && previousCamera.getWorld() == client.world ? previousCamera : client.player;
                if (back != null) {
                    client.setCameraEntity(back);
                    settleEyeHeight(back);
                } else {
                    // no world any more: GameRenderer re-targets the player when it next renders one
                    client.cameraEntity = null;
                }
            }
        } finally {
            previousCamera = null;
            openWorld = null;
        }
    }

    /**
     * Points the game camera at {@code entity} now and converges its eased eye height to that entity's
     * (it otherwise glides there over ~10 ticks, which the view would see as the eye bobbing).
     */
    private void settleEyeHeight(Entity entity) {
        try {
            if (client.world == null) {
                return;
            }
            final Camera camera = client.gameRenderer.getCamera();
            camera.update(client.world, entity, false, false, 1);
            for (int i = 0; i < 48; i++) {
                camera.updateEyeHeight();
            }
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
    }

    /**
     * How far from {@code target} along {@code unit} (target → eye) the eye can stand without terrain
     * in between, up to {@code distance} — vanilla F5 style. The orbit target can sit a little BELOW the
     * rails (framing centres the track's box in view), i.e. inside the ground: a ray from there hits the
     * grass at once and would bury the camera, so the ray starts where the line of sight is clear of the
     * rail level (+1.5), and an eye below that level is never clipped.
     */
    double clipDistance(Vec3d target, Vec3d unit, double distance) {
        if (marker == null || client.world == null) {
            return distance;
        }
        final double clearY = railLevel + 1.5;
        final Vec3d eye = target.add(unit.multiply(distance));
        if (distance > 1.2 && unit.y > 1e-6 && eye.y > clearY) {
            final double startD = Math.max(0.6, (clearY - target.y) / unit.y);
            if (startD < distance - 0.5) {
                final Vec3d from = target.add(unit.multiply(startD));
                final HitResult hit = client.world.raycast(new RaycastContext(from, eye, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, marker));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    return Math.max(startD, hit.getPos().distanceTo(target) - 0.3);
                }
            }
        }
        return distance;
    }

    /**
     * Once per frame while shown: puts the camera at {@code eye} looking so that {@code target} lands at
     * NDC {@code (ndcX, ndcY)}. {@code lookBlend} 0..1 blends the look from the player's own (fly-in) to
     * that aim. Takes effect on the NEXT world frame.
     */
    void place(Vec3d target, Vec3d eye, double ndcX, double ndcY, double tanHalfH, double tanHalfV, double lookBlend) {
        lastUseNanos = System.nanoTime();
        if (marker == null || client.world == null) {
            return;
        }
        final double[] aim = TrackWorldViewMath.aim(target.x - eye.x, target.y - eye.y, target.z - eye.z,
            ndcX, ndcY, tanHalfH, tanHalfV);
        float yaw = (float) aim[0];
        float pitch = (float) aim[1];
        if (lookBlend < 1) {
            final float s = (float) Math.max(0, lookBlend);
            yaw = startYaw + MathHelper.wrapDegrees(yaw - startYaw) * s;
            pitch = startPitch + (pitch - startPitch) * s;
        }
        put(eye.x, eye.y, eye.z, yaw, pitch);
        // keep culling off even if something else switched it back on meanwhile
        client.chunkCullingEnabled = false;
    }

    /** Position AND previous position (the camera lerps prev → current by the tick delta: no jumps). */
    private void put(double x, double y, double z, float yaw, float pitch) {
        marker.setPos(x, y, z);
        marker.prevX = x;
        marker.prevY = y;
        marker.prevZ = z;
        marker.lastRenderX = x;
        marker.lastRenderY = y;
        marker.lastRenderZ = z;
        marker.setYaw(yaw);
        marker.setPitch(pitch);
        marker.prevYaw = yaw;
        marker.prevPitch = pitch;
    }
}
