package com.stationannouncer.client.mtr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The sign editor's "in world" view: a detached camera that looks at the sign
 * from in front of the face being edited, so the draft is judged where it
 * hangs — against the wall, under the canopy, beside the next sign — instead
 * of on a flat swatch.
 *
 * <p>The camera is a client-only {@link MarkerEntity} that is never added to
 * the world; {@code MinecraftClient.setCameraEntity} only needs an entity to
 * read a position and a look from. The screen keeps drawing its side panes
 * over the frame and leaves the centre open, so {@link #update} is told where
 * on screen (NDC) the sign should land and turns the camera by that much —
 * the sign sits in the middle of the PANE, not of the window.</p>
 *
 * <p>Entering starts from the player's own eye and look and eases into the
 * orbit, so the view flies in rather than cutting. While active the first
 * person hand, crosshair and HUD are hidden and third person is suspended;
 * {@link #exit} puts all of it back, and the screen calls it from
 * {@code removed()} so no path out of the editor can leave the camera behind.</p>
 */
@Environment(EnvType.CLIENT)
final class SignWorldView {
    private static final float MIN_DISTANCE = 0.9f;
    private static final float MAX_DISTANCE = 48f;
    private static final float MAX_ORBIT_YAW = 85f;
    private static final float MAX_ORBIT_PITCH = 70f;

    private final MinecraftClient client = MinecraftClient.getInstance();
    private Entity camera;
    private Entity previousCamera;
    private boolean previousHudHidden;
    private Perspective previousPerspective;

    // Orbit the user steers (degrees about the face normal, blocks from the sign).
    private float orbitYaw;
    private float orbitPitch;
    private float distance = 4;
    private float homeDistance = 4;

    // Eased state actually shown.
    private float shownYaw;
    private float shownPitch;
    private float shownDistance;
    private float blend;
    private float startLookYaw;
    private float startLookPitch;
    private long lastNanos;
    private double lastEntityY = Double.NaN;

    boolean active() {
        return camera != null;
    }

    /** Starts the view; {@code fitDistance} frames the whole sign in the pane. */
    void enter(Vec3d target, Vec3d normal, float fitDistance) {
        if (client.world == null || client.player == null || camera != null) {
            return;
        }
        homeDistance = MathHelper.clamp(fitDistance, MIN_DISTANCE, MAX_DISTANCE);
        distance = homeDistance;
        orbitYaw = 0;
        orbitPitch = 0;
        // Begin where the player is looking from, expressed as an orbit about the sign.
        Vec3d eye = client.player.getCameraPosVec(1.0f);
        Vec3d offset = eye.subtract(target);
        double length = Math.max(0.001, offset.length());
        double baseAngle = Math.atan2(normal.x, normal.z);
        shownYaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(offset.x, offset.z) - baseAngle));
        shownPitch = (float) Math.toDegrees(Math.asin(MathHelper.clamp(offset.y / length, -1, 1)));
        shownDistance = (float) length;
        startLookYaw = client.player.getYaw();
        startLookPitch = client.player.getPitch();
        blend = 0;
        lastNanos = System.nanoTime();
        lastEntityY = Double.NaN;

        camera = new MarkerEntity(EntityType.MARKER, client.world);
        previousCamera = client.getCameraEntity();
        previousHudHidden = client.options.hudHidden;
        previousPerspective = client.options.getPerspective();
        client.options.hudHidden = true;
        client.options.setPerspective(Perspective.FIRST_PERSON);
        place(eye.x, eye.y, eye.z, startLookYaw, startLookPitch);
        client.setCameraEntity(camera);
    }

    void exit() {
        if (camera == null) {
            return;
        }
        client.options.hudHidden = previousHudHidden;
        if (previousPerspective != null) {
            client.options.setPerspective(previousPerspective);
        }
        Entity back = previousCamera != null && previousCamera.isAlive() ? previousCamera : client.player;
        if (back != null) {
            client.setCameraEntity(back);
        }
        camera = null;
        previousCamera = null;
    }

    void orbit(double dxPixels, double dyPixels) {
        orbitYaw = MathHelper.clamp(orbitYaw - (float) dxPixels * 0.45f, -MAX_ORBIT_YAW, MAX_ORBIT_YAW);
        orbitPitch = MathHelper.clamp(orbitPitch + (float) dyPixels * 0.35f, -MAX_ORBIT_PITCH, MAX_ORBIT_PITCH);
    }

    void zoom(double steps) {
        distance = MathHelper.clamp(distance * (float) Math.pow(0.88, steps), MIN_DISTANCE, MAX_DISTANCE);
    }

    /** Back to the straight-on framing; {@code fitDistance} may have changed with the plate. */
    void reset(float fitDistance) {
        homeDistance = MathHelper.clamp(fitDistance, MIN_DISTANCE, MAX_DISTANCE);
        distance = homeDistance;
        orbitYaw = 0;
        orbitPitch = 0;
    }

    /**
     * Once per frame. {@code ndcX}/{@code ndcY} are where the sign's centre
     * should appear (-1..1, right and up positive); {@code tanHalfH}/{@code
     * tanHalfV} the tangents of half the horizontal / vertical field of view.
     */
    void update(Vec3d target, Vec3d normal, float ndcX, float ndcY, float tanHalfH, float tanHalfV) {
        if (camera == null || client.world == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        float ease = 1 - (float) Math.exp(-dt * 9);
        shownYaw += MathHelper.wrapDegrees(orbitYaw - shownYaw) * ease;
        shownPitch += (orbitPitch - shownPitch) * ease;
        shownDistance += (distance - shownDistance) * ease;
        blend += (1 - blend) * ease;

        double angle = Math.atan2(normal.x, normal.z) + Math.toRadians(shownYaw);
        double pitchRad = Math.toRadians(shownPitch);
        Vec3d dir = new Vec3d(Math.sin(angle) * Math.cos(pitchRad), Math.sin(pitchRad), Math.cos(angle) * Math.cos(pitchRad));

        // Never look from inside a wall: stop short of whatever is between the sign and the eye.
        float reach = shownDistance;
        Vec3d from = target.add(dir.multiply(0.6));
        Vec3d to = target.add(dir.multiply(reach));
        if (reach > 0.9f) {
            HitResult hit = client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, camera));
            if (hit.getType() == HitResult.Type.BLOCK) {
                reach = Math.max(MIN_DISTANCE, (float) hit.getPos().distanceTo(target) - 0.3f);
            }
        }
        Vec3d eye = target.add(dir.multiply(reach));

        float lookYaw = (float) Math.toDegrees(Math.atan2(dir.x, -dir.z));
        float lookPitch = (float) Math.toDegrees(Math.asin(MathHelper.clamp(dir.y, -1, 1)));
        // Turn away from the sign by the pane's offset from the window centre.
        lookYaw -= (float) Math.toDegrees(Math.atan(ndcX * tanHalfH));
        lookPitch += (float) Math.toDegrees(Math.atan(ndcY * tanHalfV));
        float yaw = startLookYaw + MathHelper.wrapDegrees(lookYaw - startLookYaw) * blend;
        float pitch = startLookPitch + (lookPitch - startLookPitch) * blend;

        // The game camera adds its own tick-lerped eye height on top of the
        // entity's y (it is still gliding down from the player's 1.62 when the
        // view opens, a marker's being 0). Read what it added last frame and
        // take it back out, so the eye is exactly where the orbit says.
        double eyeOffset = 0;
        if (!Double.isNaN(lastEntityY) && client.gameRenderer.getCamera().getFocusedEntity() == camera) {
            eyeOffset = client.gameRenderer.getCamera().getPos().y - lastEntityY;
        } else if (client.player != null) {
            eyeOffset = client.player.getStandingEyeHeight();
        }
        eyeOffset = MathHelper.clamp(eyeOffset, 0, 2);
        place(eye.x, eye.y - eyeOffset, eye.z, yaw, pitch);
    }

    private void place(double x, double y, double z, float yaw, float pitch) {
        camera.setPos(x, y, z);
        camera.prevX = x;
        camera.prevY = y;
        camera.prevZ = z;
        camera.lastRenderX = x;
        camera.lastRenderY = y;
        camera.lastRenderZ = z;
        camera.setYaw(yaw);
        camera.setPitch(pitch);
        camera.prevYaw = yaw;
        camera.prevPitch = pitch;
        lastEntityY = y;
    }
}
