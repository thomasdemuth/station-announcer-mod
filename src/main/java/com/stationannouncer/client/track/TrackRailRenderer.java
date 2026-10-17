package com.stationannouncer.client.track;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.track.TrackRails;
import com.stationannouncer.track.TrackPath;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Camera;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.LightType;
import org.mtr.mapping.holder.LightmapTextureManager;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Init;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.RailType;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.RailResource;

import java.util.Arrays;

/**
 * Draws TILTED (canted) rails: a faithful re-implementation of MTR 4.0.1's
 * {@code RenderRails.renderRailStandard} (styles incl. {@code default_3d}, flat default rail),
 * {@code renderSignalsStandard} and {@code renderRailOneWayArrows}, banked about the low rail.
 * Called from {@code RenderRailsTiltMixin}; every entry point returns {@code false} — and draws
 * nothing — for a rail without an active tilted custom path, so MTR's own code runs for it.
 *
 * <ul>
 *   <li>Model styles (and the 3D default rail, which in 4.0.1 is just the {@code default_3d} /
 *       {@code default_3d_siding} style resource): MTR's per-segment yaw/pitch/jitter matrix plus a
 *       roll about the low rail, tilt sampled at the segment midpoint ({@link TrackRailBank}).</li>
 *   <li>Flat default rail, signal strips and one-way arrows: every quad corner is banked on its own
 *       ({@link TrackRailBank#bank}) with the tilt at THAT end of the segment, so strips stay
 *       continuous across segments.</li>
 * </ul>
 * Segments follow our own path A→B with the same count as {@link TrackPath#forEachSegment}, i.e.
 * the same quads MTR draws for an untilted custom path through the RailMath redirect.
 *
 * Render thread only (MTR calls these from its world render pass).
 */
public final class TrackRailRenderer {
    private static final Identifier IRON_BLOCK_TEXTURE = new Identifier("textures/block/iron_block.png");
    private static final Identifier WOOL_TEXTURE = new Identifier("textures/block/white_wool.png");
    private static final Identifier ONE_WAY_RAIL_ARROW_TEXTURE = new Identifier("mtr", "textures/block/one_way_rail_arrow.png");
    /** MTR's thickness nudge between the two faces of a flat quad. */
    private static final float SKIN = 0.003125F;

    private static boolean broken;

    private TrackRailRenderer() {
    }

    // ------------------------------------------------------------------ entry points (mixin)

    /**
     * Replaces the 10-argument {@code renderRailStandard} for a tilted rail.
     *
     * @return true when the rail was drawn here (the caller must then cancel MTR's drawing)
     */
    public static boolean renderStandard(ClientWorld clientWorld, Rail rail, float yOffset, boolean hasColor, boolean flashing, float railWidth, Identifier defaultTexture, float u1, float v1, float u2, float v2) {
        final TrackPath path = tiltedPath(rail);
        if (path == null) {
            return false;
        }
        try {
            drawStandard(clientWorld, rail, path, yOffset, hasColor, flashing, railWidth, defaultTexture, u1, v1, u2, v2);
        } catch (Throwable e) {
            disable(e);
            return false;
        }
        return true;
    }

    /** Replaces {@code renderSignalsStandard} for a tilted rail. */
    public static boolean renderSignals(ClientWorld clientWorld, Rail rail) {
        final TrackPath path = tiltedPath(rail);
        if (path == null) {
            return false;
        }
        try {
            drawSignals(clientWorld, rail, path);
        } catch (Throwable e) {
            disable(e);
            return false;
        }
        return true;
    }

    /** Replaces {@code renderRailOneWayArrows} for a tilted rail. */
    public static boolean renderOneWayArrows(Rail rail, float yOffset) {
        final TrackPath path = tiltedPath(rail);
        if (path == null) {
            return false;
        }
        try {
            drawOneWayArrows(rail, path, yOffset);
        } catch (Throwable e) {
            disable(e);
            return false;
        }
        return true;
    }

    private static TrackPath tiltedPath(Rail rail) {
        if (broken || rail == null) {
            return null;
        }
        try {
            final TrackPath path = TrackRails.path(rail);
            return path != null && path.hasTilt() ? path : null;
        } catch (Throwable e) {
            disable(e);
            return null;
        }
    }

    private static void disable(Throwable e) {
        // Throwable: a linkage error from an MTR update must fall back to stock rendering, not crash the frame.
        if (!broken) {
            broken = true;
            StationAnnouncer.LOGGER.error("Tilted rail renderer failed; tilted rails draw flat (MTR's renderer) for the rest of this session", e);
        }
    }

    // ------------------------------------------------------------------ rails

    private static void drawStandard(ClientWorld clientWorld, Rail rail, TrackPath path, float yOffset, boolean hasColor, boolean flashing, float railWidth, Identifier defaultTexture, float u1, float v1, float u2, float v2) {
        final boolean[] renderType = {false, false};
        final boolean default3d = OptimizedRenderer.hasOptimizedRendering() && Config.getClient().getDefaultRail3D() && rail.getTransportMode() == TransportMode.TRAIN;
        final Culling culling = new Culling();

        for (final String style : rail.getStyles()) {
            final String newStyle = default3d && style.equals("default") ? (rail.isSiding() ? "default_3d_siding" : "default_3d") : style;
            if (newStyle.equals("default")) {
                renderType[0] = true;
            } else {
                final boolean flip = newStyle.endsWith("_2");
                CustomResourceLoader.getRailById(RailResource.getIdWithoutDirection(newStyle), railResource -> {
                    if (drawModel(clientWorld, path, railResource, flip, culling)) {
                        renderType[1] = true;
                    }
                });
            }
        }

        if (renderType[0] || hasColor) {
            final int color = hasColor ? (flashing ? MainRenderer.getFlashingColor(RailType.getRailColor(rail), 1) : RailType.getRailColor(rail)) : -1;
            final Identifier texture = renderType[1] && !renderType[0] ? IRON_BLOCK_TEXTURE : defaultTexture;
            final double[] a = new double[5];
            final double[] b = new double[5];
            final double[] bank = new double[2];
            final double[] q = new double[12];
            final int n = TrackRailBank.segmentCount(path.length(), 0.5);
            final double step = path.length() / n;
            for (int i = 0; i < n; i++) {
                final double sA = i * step;
                final double sB = i + 1 == n ? path.length() : (i + 1) * step;
                path.pointAt(sA, a);
                path.pointAt(sB, b);
                // MTR culls/lights at corner 1 (offset1 = −railWidth) at rail height
                final double cx = a[0] - Math.sin(a[3]) * -railWidth;
                final double cz = a[2] + Math.cos(a[3]) * -railWidth;
                if (!culling.visible(cx, a[1], cz)) {
                    continue;
                }
                bankedQuad(path, sA, sB, a, b, -railWidth, railWidth, yOffset, bank, q);
                final float textureOffset = (float) ((int) (cx + cz) % 4) * 0.25F;
                final int light = hasColor ? GraphicsHolder.getDefaultLight() : light(clientWorld, cx, a[1], cz);
                final float tu1 = u1 < 0 ? 0 : u1;
                final float tv1 = v1 < 0 ? 0.1875F + textureOffset : v1;
                final float tu2 = u2 < 0 ? 1 : u2;
                final float tv2 = v2 < 0 ? 0.3125F + textureOffset : v2;
                final double[] c = q.clone();
                MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
                    IDrawing.drawTexture(graphicsHolder,
                            c[0], c[1], c[2], c[3], c[4] + SKIN, c[5], c[6], c[7], c[8], c[9], c[10] + SKIN, c[11],
                            offset, tu1, tv1, tu2, tv2, Direction.UP, color, light);
                    IDrawing.drawTexture(graphicsHolder,
                            c[3], c[4] + SKIN, c[5], c[0], c[1], c[2], c[9], c[10] + SKIN, c[11], c[6], c[7], c[8],
                            offset, tu1, tv1, tu2, tv2, Direction.UP, color, light);
                });
            }
        }
    }

    /** One style along the path; true when at least one segment was within render distance (MTR's {@code renderType[1]}). */
    private static boolean drawModel(ClientWorld clientWorld, TrackPath path, RailResource railResource, boolean flip, Culling culling) {
        final double[] a = new double[5];
        final double[] b = new double[5];
        final double modelYOffset = railResource.getModelYOffset();
        final int n = TrackRailBank.segmentCount(path.length(), railResource.getRepeatInterval());
        final double step = path.length() / n;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            final double sA = i * step;
            final double sB = i + 1 == n ? path.length() : (i + 1) * step;
            path.pointAt(sA, a);
            path.pointAt(sB, b);
            final double x1 = a[0];
            final double y1 = a[1];
            final double z1 = a[2];
            final double x3 = b[0];
            final double y2 = b[1];
            final double z3 = b[2];
            if (!culling.visible(x1, y1, z1)) {
                continue;
            }
            final int light = light(clientWorld, x1, y1, z1);
            final double differenceX = x3 - x1;
            final double differenceZ = z3 - z1;
            final double yaw = Math.atan2(differenceZ, differenceX);
            final double pitch = Math.atan2(y2 - y1, Math.sqrt(differenceX * differenceX + differenceZ * differenceZ));
            final double tilt = path.tilt((sA + sB) / 2);
            final float roll = (float) TrackRailBank.modelRoll(tilt, flip);
            final double pivotX = TrackRailBank.modelPivotX(tilt, flip);
            final double pivotY = TrackRailBank.modelPivotY(modelYOffset, pitch);
            final float jitter = (float) (x1 * z1 % 10.0) / 100.0F;
            final StoredMatrixTransformations transformations = new StoredMatrixTransformations((x1 + x3) / 2.0, (y1 + y2) / 2.0 + modelYOffset, (z1 + z3) / 2.0);
            transformations.add(graphicsHolder -> {
                graphicsHolder.rotateYRadians((float) (Math.PI / 2 - yaw + (flip ? Math.PI : 0.0)));
                graphicsHolder.rotateXRadians((float) (Math.PI - pitch * (flip ? -1 : 1)));
                if (roll != 0) {
                    graphicsHolder.translate(pivotX, pivotY, 0);
                    graphicsHolder.rotateZRadians(roll);
                    graphicsHolder.translate(-pivotX, -pivotY, 0);
                }
                graphicsHolder.rotateZDegrees(jitter);
            });
            railResource.render(transformations, light);
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ signals + arrows

    private static void drawSignals(ClientWorld clientWorld, Rail rail, TrackPath path) {
        final int[] colors = rail.getSignalColors().toIntArray();
        Arrays.sort(colors);
        final LongArrayList preBlockedSignalColors = MinecraftClientData.getInstance().railIdToPreBlockedSignalColors.get(rail.getHexId());
        final LongArrayList currentlyBlockedSignalColors = MinecraftClientData.getInstance().railIdToCurrentlyBlockedSignalColors.get(rail.getHexId());
        final Culling culling = new Culling();
        final double[] a = new double[5];
        final double[] b = new double[5];
        final double[] bank = new double[2];
        final double[] q = new double[12];
        final int n = TrackRailBank.segmentCount(path.length(), 1.0);
        final double step = path.length() / n;

        for (int i = 0; i < colors.length; i++) {
            final int rawColor = colors[i];
            final boolean preBlocked = preBlockedSignalColors != null && preBlockedSignalColors.contains(rawColor);
            final boolean currentlyBlocked = currentlyBlockedSignalColors != null && currentlyBlockedSignalColors.contains(rawColor);
            final boolean shouldFlash = preBlocked || currentlyBlocked;
            final int color = shouldFlash ? MainRenderer.getFlashingColor(rawColor, currentlyBlocked ? 1 : 4) : 0xFF000000 | rawColor;
            final float u1 = 0.0625F * i + 1 - 0.0625F * colors.length / 2F;
            final float u2 = u1 + 0.0625F;
            for (int j = 0; j < n; j++) {
                final double sA = j * step;
                final double sB = j + 1 == n ? path.length() : (j + 1) * step;
                path.pointAt(sA, a);
                path.pointAt(sB, b);
                final double offset1 = u1 - 1F;
                final double cx = a[0] - Math.sin(a[3]) * offset1;
                final double cz = a[2] + Math.cos(a[3]) * offset1;
                if (!culling.visible(cx, a[1], cz)) {
                    continue;
                }
                bankedQuad(path, sA, sB, a, b, offset1, u2 - 1F, 0.125, bank, q);
                final int light = shouldFlash ? GraphicsHolder.getDefaultLight() : light(clientWorld, cx, a[1], cz);
                final double[] c = q.clone();
                MainRenderer.scheduleRender(WOOL_TEXTURE, false, shouldFlash ? QueuedRenderLayer.EXTERIOR : QueuedRenderLayer.LIGHT, (graphicsHolder, offset) -> {
                    IDrawing.drawTexture(graphicsHolder,
                            c[0], c[1], c[2], c[3], c[4] + SKIN, c[5], c[6], c[7], c[8], c[9], c[10] + SKIN, c[11],
                            offset, u1, 0, u2, 1, Direction.UP, color, light);
                    IDrawing.drawTexture(graphicsHolder,
                            c[9], c[10] + SKIN, c[11], c[6], c[7], c[8], c[3], c[4] + SKIN, c[5], c[0], c[1], c[2],
                            offset, u1, 0, u2, 1, Direction.UP, color, light);
                });
            }
        }
    }

    private static void drawOneWayArrows(Rail rail, TrackPath path, float yOffset) {
        final long speedLimit1 = rail.getSpeedLimitKilometersPerHour(false);
        final long speedLimit2 = rail.getSpeedLimitKilometersPerHour(true);
        if (speedLimit1 != 0 && speedLimit2 != 0) {
            return;
        }
        final float v1 = speedLimit1 == 0 ? 0.25F : 0.75F;
        final float v2 = speedLimit1 == 0 ? 0.75F : 0.25F;
        final Culling culling = new Culling();
        final double[] a = new double[5];
        final double[] b = new double[5];
        final double[] bank = new double[2];
        final double[] q = new double[12];
        final int n = TrackRailBank.segmentCount(path.length(), 1.0);
        final double step = path.length() / n;
        for (int j = 0; j < n; j++) {
            final double sA = j * step;
            final double sB = j + 1 == n ? path.length() : (j + 1) * step;
            path.pointAt(sA, a);
            path.pointAt(sB, b);
            final double cx = a[0] + Math.sin(a[3]);
            final double cz = a[2] - Math.cos(a[3]);
            if (!culling.visible(cx, a[1], cz)) {
                continue;
            }
            bankedQuad(path, sA, sB, a, b, -1, 1, yOffset + 0.125, bank, q);
            final double[] c = q.clone();
            MainRenderer.scheduleRender(ONE_WAY_RAIL_ARROW_TEXTURE, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
                IDrawing.drawTexture(graphicsHolder,
                        c[0], c[1], c[2], c[3], c[4] + SKIN, c[5], c[6], c[7], c[8], c[9], c[10] + SKIN, c[11],
                        offset, 0, v1, 1, v2, Direction.UP, -1, GraphicsHolder.getDefaultLight());
                IDrawing.drawTexture(graphicsHolder,
                        c[3], c[4] + SKIN, c[5], c[0], c[1], c[2], c[9], c[10] + SKIN, c[11], c[6], c[7], c[8],
                        offset, 0, v1, 1, v2, Direction.UP, -1, GraphicsHolder.getDefaultLight());
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fills {@code q} with MTR's four quad corners {x,y,z} × {1,2,3,4} — 1 = start at {@code offset1},
     * 2 = start at {@code offset2}, 3 = end at {@code offset2}, 4 = end at {@code offset1} (the
     * {@code RailMath.render} corner order) — each banked by the tilt at its own end, {@code h} above
     * the path. The caller adds MTR's {@link #SKIN} to corners 2 and 4 like MTR does.
     */
    private static void bankedQuad(TrackPath path, double sA, double sB, double[] a, double[] b, double offset1, double offset2, double h, double[] bank, double[] q) {
        final double tiltA = path.tilt(sA);
        final double tiltB = path.tilt(sB);
        corner(a, offset1, h, tiltA, bank, q, 0);
        corner(a, offset2, h, tiltA, bank, q, 3);
        corner(b, offset2, h, tiltB, bank, q, 6);
        corner(b, offset1, h, tiltB, bank, q, 9);
    }

    private static void corner(double[] point, double u, double h, double tilt, double[] bank, double[] q, int index) {
        TrackRailBank.bank(u, h, tilt, bank);
        q[index] = point[0] - Math.sin(point[3]) * bank[0];
        q[index + 1] = point[1] + bank[1];
        q[index + 2] = point[2] + Math.cos(point[3]) * bank[0];
    }

    private static int light(ClientWorld clientWorld, double x, double y, double z) {
        final BlockPos blockPos = Init.newBlockPos(x, y + 0.1, z);
        return LightmapTextureManager.pack(clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos), clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
    }

    /** MTR's {@code renderWithinRenderDistance} test, camera state read once per pass and no per-segment allocation. */
    private static final class Culling {
        private final int cameraX;
        private final int cameraY;
        private final int cameraZ;
        private final double positionX;
        private final double positionY;
        private final double positionZ;
        private final double cosYaw;
        private final double sinYaw;
        private final double cosPitch;
        private final double sinPitch;
        private final int renderDistance;

        private Culling() {
            final Camera camera = MinecraftClient.getInstance().getGameRendererMapped().getCamera();
            final BlockPos cameraBlockPos = camera.getBlockPos();
            cameraX = cameraBlockPos.getX();
            cameraY = cameraBlockPos.getY();
            cameraZ = cameraBlockPos.getZ();
            final Vector3d cameraPosition = camera.getPos();
            positionX = cameraPosition.getXMapped();
            positionY = cameraPosition.getYMapped();
            positionZ = cameraPosition.getZMapped();
            final float yaw = (float) Math.toRadians(camera.getYaw());
            final float pitch = (float) Math.toRadians(camera.getPitch());
            cosYaw = Math.cos(yaw);
            sinYaw = Math.sin(yaw);
            cosPitch = Math.cos(pitch);
            sinPitch = Math.sin(pitch);
            renderDistance = MinecraftClientHelper.getRenderDistance() * 16;
        }

        /** Same block as MTR's {@code Init.newBlockPos(x1, y1 + 0.1, z1)}; same Manhattan range and in-front test. */
        boolean visible(double x, double y, double z) {
            final int distance = Math.abs((int) Math.floor(x) - cameraX) + Math.abs((int) Math.floor(y + 0.1) - cameraY) + Math.abs((int) Math.floor(z) - cameraZ);
            if (distance > renderDistance) {
                return false;
            }
            if (distance < 32) {
                return true;
            }
            // Vec3d.rotateY(yaw) then rotateX(pitch); only z matters
            final double dx = x - positionX;
            final double dy = y - positionY;
            final double dz = z - positionZ;
            final double z1 = dz * cosYaw - dx * sinYaw;
            final double z2 = z1 * cosPitch - dy * sinPitch;
            return z2 > 0;
        }
    }
}
