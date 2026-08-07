package com.stationannouncer.client.mtraddon;

import com.stationannouncer.mtraddon.LiftDoorSides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.Position;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import org.mtr.libraries.com.logisticscraft.occlusionculling.util.Vec3d;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Items;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.IGui;
import org.mtr.mod.item.ItemLiftRefresher;
import org.mtr.mod.model.ModelLift1;
import org.mtr.mod.model.ModelSmallCube;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.RenderLifts;
import org.mtr.mod.render.RenderVehicleHelper;
import org.mtr.mod.render.RenderVehicles;
import org.mtr.mod.render.StoredMatrixTransformations;
import java.util.function.Function;

/**
 * Feature 3 — the replacement for MTR's {@code RenderLifts.render(long, Vector3d)},
 * called from {@code RenderLiftsMixin} on the RENDER thread once per frame while
 * at least one lift has a door-side config. A faithful adaptation of MTR 4.0.1's
 * method (source: mtr-src/.../render/RenderLifts.java; every member used here
 * javap-verified as public against the 4.0.1 jar):
 *
 * <ul>
 *   <li><b>Unconfigured lifts</b> go through logic line-for-line identical to
 *       stock — same doorway boxes (front, back only if double-sided), the same
 *       {@code new ModelLift1(...)} cab, the same one-or-two floor displays.</li>
 *   <li><b>Configured lifts</b> get a doorway box per enabled side (the ±X boxes
 *       mirror the stock ±Z ones with width/depth swapped), an
 *       {@link AddonModelLift} cab with door openings on exactly those sides, and
 *       a floor display above each door. Which sides actually OPEN at the current
 *       floor stays automatic: {@code RenderVehicleHelper.canOpenDoors} per
 *       doorway, exactly like stock decides front vs back.</li>
 *   <li><b>Boarding</b>: all open doorways are passed to
 *       {@code VehicleRidingMovement.startRiding}/{@code movePlayer} exactly as
 *       stock passes its one or two, so entering/leaving works from every
 *       configured side with MTR's own movement code.</li>
 * </ul>
 *
 * <p>Server-side nothing changes — lift motion and door timing remain MTR's
 * simulation; door sides are presentation + boarding boxes only.</p>
 */
@Environment(EnvType.CLIENT)
public final class AddonRenderLifts implements IGui {

    // Stock RenderLifts' private constants, verbatim.
    private static final int LIFT_DISPLAY_COLOR = 0xFFFF0000;
    private static final ModelSmallCube MODEL_SMALL_CUBE = new ModelSmallCube(new Identifier("textures/block/redstone_block.png"));
    private static final float LIFT_DOOR_VALUE = 0.75F;
    private static final float LIFT_FLOOR_PADDING = 0.25F;

    private AddonRenderLifts() {
    }

    /** Mirrors stock {@code RenderLifts.render(long, Vector3d)} with per-side doors for configured lifts. */
    public static void render(long millisElapsed, Vector3d cameraShakeOffset) {
        final MinecraftClient minecraftClient = MinecraftClient.getInstance();
        final ClientWorld clientWorld = minecraftClient.getWorldMapped();
        final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
        if (clientWorld == null || clientPlayerEntity == null) {
            return;
        }

        final ObjectArrayList<Function<OcclusionCullingInstance, Runnable>> cullingTasks = new ObjectArrayList<>();
        final Vector3d cameraPosition = minecraftClient.getGameRendererMapped().getCamera().getPos();
        final Vec3d camera = new Vec3d(cameraPosition.getXMapped(), cameraPosition.getYMapped(), cameraPosition.getZMapped());

        final boolean canRide = !clientPlayerEntity.isSpectator();
        final boolean isHoldingRefresher = clientPlayerEntity.isHolding(Items.LIFT_REFRESHER.get());

        MinecraftClientData.getInstance().liftWrapperList.values().forEach(liftWrapper -> {
            final Lift lift = liftWrapper.getLift();

            if (isHoldingRefresher) {
                // Render lift path for debugging (stock, verbatim)
                final LiftFloor[] previousLiftFloor = {null};
                lift.iterateFloors(liftFloor -> {
                    final Position position = liftFloor.getPosition();
                    final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(position.getX(), position.getY(), position.getZ());
                    MODEL_SMALL_CUBE.render(storedMatrixTransformations, GraphicsHolder.getDefaultLight());

                    if (previousLiftFloor[0] != null) {
                        final Position position1 = liftFloor.getPosition();
                        final Position position2 = previousLiftFloor[0].getPosition();
                        MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> {
                            final ObjectArrayList<Vector> trackPositions = ItemLiftRefresher.findPath(new World(clientWorld.data), position1, position2);
                            for (int i = 1; i < trackPositions.size(); i++) {
                                graphicsHolder.drawLineInWorld(
                                        (float) (trackPositions.get(i - 1).x - offset.getXMapped() + 0.5),
                                        (float) (trackPositions.get(i - 1).y - offset.getYMapped() + 0.5),
                                        (float) (trackPositions.get(i - 1).z - offset.getZMapped() + 0.5),
                                        (float) (trackPositions.get(i).x - offset.getXMapped() + 0.5),
                                        (float) (trackPositions.get(i).y - offset.getYMapped() + 0.5),
                                        (float) (trackPositions.get(i).z - offset.getZMapped() + 0.5),
                                        ARGB_WHITE
                                );
                            }
                        });
                    }

                    previousLiftFloor[0] = liftFloor;
                });
            }

            // Calculating vehicle transformations in advance (stock, verbatim)
            final PositionAndRotation absolutePositionAndRotation = getLiftPositionAndRotation(clientWorld, lift);
            cullingTasks.add(occlusionCullingInstance -> {
                final double longestDimension = Math.max(lift.getHeight(), Math.max(lift.getWidth(), lift.getDepth()));
                final boolean shouldRender = occlusionCullingInstance.isAABBVisible(new Vec3d(
                        absolutePositionAndRotation.position.x - longestDimension,
                        absolutePositionAndRotation.position.y - longestDimension,
                        absolutePositionAndRotation.position.z - longestDimension
                ), new Vec3d(
                        absolutePositionAndRotation.position.x + longestDimension,
                        absolutePositionAndRotation.position.y + longestDimension,
                        absolutePositionAndRotation.position.z + longestDimension
                ), camera);
                return () -> liftWrapper.shouldRender = shouldRender;
            });

            if (liftWrapper.shouldRender) {
                // Riding offset (stock, verbatim)
                final IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> ridingVehicleCarNumberAndOffset = VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(lift.getId());
                final PositionAndRotation ridingCarPositionAndRotation;
                final Vector3d offsetVector;
                final Double offsetRotation;
                if (ridingVehicleCarNumberAndOffset == null) {
                    ridingCarPositionAndRotation = null;
                    offsetVector = null;
                    offsetRotation = null;
                } else {
                    ridingCarPositionAndRotation = absolutePositionAndRotation;
                    offsetVector = ridingVehicleCarNumberAndOffset.right().left();
                    offsetRotation = ridingVehicleCarNumberAndOffset.right().right();
                }

                final PositionAndRotation renderingPositionAndRotation = RenderVehicles.getRenderPositionAndRotation(offsetVector, offsetRotation, ridingCarPositionAndRotation, absolutePositionAndRotation, cameraShakeOffset);

                // Feature 3: null = unconfigured lift = stock behavior throughout.
                final LiftDoorSides doorSides = ClientLiftDoors.get(lift.getId());

                // A temporary list to store all floors and doorways
                final ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways = new ObjectArrayList<>();
                // Find open doorways (close to platform blocks, unlocked platform screen doors, or unlocked automatic platform gates)
                final ObjectArrayList<Box> openDoorways = new ObjectArrayList<>();

                // Stock ±Z doorway boxes; the ±X ones mirror them with width/depth swapped.
                final Box doorwayFront = new Box(-LIFT_DOOR_VALUE, 0, -lift.getDepth() / 2 + LIFT_FLOOR_PADDING, LIFT_DOOR_VALUE, 0, -lift.getDepth() / 2);
                final Box doorwayBack = new Box(-LIFT_DOOR_VALUE, 0, lift.getDepth() / 2 - LIFT_FLOOR_PADDING, LIFT_DOOR_VALUE, 0, lift.getDepth() / 2);
                final Box doorwayLeft = new Box(-lift.getWidth() / 2 + LIFT_FLOOR_PADDING, 0, -LIFT_DOOR_VALUE, -lift.getWidth() / 2, 0, LIFT_DOOR_VALUE);
                final Box doorwayRight = new Box(lift.getWidth() / 2 - LIFT_FLOOR_PADDING, 0, -LIFT_DOOR_VALUE, lift.getWidth() / 2, 0, LIFT_DOOR_VALUE);

                // Which sides HAVE doors: configured sides, or stock's front + back-if-double-sided.
                final boolean hasFront = doorSides == null || doorSides.front();
                final boolean hasBack = doorSides == null ? lift.getIsDoubleSided() : doorSides.back();
                final boolean hasLeft = doorSides != null && doorSides.left();
                final boolean hasRight = doorSides != null && doorSides.right();

                // Which of those are OPEN at the current floor: automatic per doorway, exactly like stock.
                final boolean frontOpen;
                final boolean backOpen;
                final boolean leftOpen;
                final boolean rightOpen;
                if (lift.hasCoolDown()) {
                    final double doorCheckValue = Math.min(lift.getDoorValue(), LIFT_DOOR_VALUE);
                    frontOpen = hasFront && RenderVehicleHelper.canOpenDoors(doorwayFront, absolutePositionAndRotation, doorCheckValue);
                    backOpen = hasBack && RenderVehicleHelper.canOpenDoors(doorwayBack, absolutePositionAndRotation, doorCheckValue);
                    leftOpen = hasLeft && RenderVehicleHelper.canOpenDoors(doorwayLeft, absolutePositionAndRotation, doorCheckValue);
                    rightOpen = hasRight && RenderVehicleHelper.canOpenDoors(doorwayRight, absolutePositionAndRotation, doorCheckValue);
                    if (frontOpen) {
                        openDoorways.add(doorwayFront);
                    }
                    if (backOpen) {
                        openDoorways.add(doorwayBack);
                    }
                    if (leftOpen) {
                        openDoorways.add(doorwayLeft);
                    }
                    if (rightOpen) {
                        openDoorways.add(doorwayRight);
                    }
                } else {
                    frontOpen = false;
                    backOpen = false;
                    leftOpen = false;
                    rightOpen = false;
                }

                if (canRide) {
                    // Player position relative to the car (stock, verbatim)
                    final Vector3d playerPosition = absolutePositionAndRotation.transformBackwards(clientPlayerEntity.getPos(), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
                    // Check and mount player — ALL open doorways go in, so boarding works from every configured side
                    VehicleRidingMovement.startRiding(openDoorways, 0, 0, lift.getId(), 0, playerPosition.getXMapped(), playerPosition.getYMapped(), playerPosition.getZMapped(), absolutePositionAndRotation.yaw);

                    final Box floor = new Box(-lift.getWidth() / 2 + LIFT_FLOOR_PADDING, 0, -lift.getDepth() / 2 + LIFT_FLOOR_PADDING, lift.getWidth() / 2 - LIFT_FLOOR_PADDING, 0, lift.getDepth() / 2 - LIFT_FLOOR_PADDING);
                    floorsAndDoorways.add(new ObjectBooleanImmutablePair<>(floor, true));
                    RenderVehicleHelper.renderFloorOrDoorway(floor, ARGB_WHITE, playerPosition, renderingPositionAndRotation, offsetVector == null);

                    openDoorways.forEach(doorway -> {
                        floorsAndDoorways.add(new ObjectBooleanImmutablePair<>(doorway, false));
                        RenderVehicleHelper.renderFloorOrDoorway(doorway, 0xFFFF0000, playerPosition, renderingPositionAndRotation, offsetVector == null);
                    });
                }

                // Render the lift
                final StoredMatrixTransformations storedMatrixTransformations = RenderVehicles.getStoredMatrixTransformations(offsetVector == null, renderingPositionAndRotation, 0);
                final float openDoorValue = lift.getDoorValue() / LIFT_DOOR_VALUE;
                if (doorSides == null) {
                    // Stock cab, stock call, pixel-identical.
                    new ModelLift1((int) Math.round(lift.getHeight() * 2), (int) Math.round(lift.getWidth()), (int) Math.round(lift.getDepth()), lift.getIsDoubleSided()).render(
                            storedMatrixTransformations,
                            null,
                            RenderLifts.getLiftResource(lift.getStyle()).getTexture(),
                            absolutePositionAndRotation.light,
                            frontOpen ? openDoorValue : 0, backOpen ? openDoorValue : 0, false,
                            0, 1, true, true, false, true, false
                    );
                } else {
                    new AddonModelLift((int) Math.round(lift.getHeight() * 2), (int) Math.round(lift.getWidth()), (int) Math.round(lift.getDepth()), hasFront, hasBack, hasLeft, hasRight).renderMultiDoor(
                            storedMatrixTransformations,
                            RenderLifts.getLiftResource(lift.getStyle()).getTexture(),
                            absolutePositionAndRotation.light,
                            frontOpen ? openDoorValue : 0, backOpen ? openDoorValue : 0,
                            leftOpen ? openDoorValue : 0, rightOpen ? openDoorValue : 0
                    );
                }

                // Render the display inside the lift: stock puts one above the front door and one
                // above the back door when double-sided; we put one above every door side.
                renderDisplays(storedMatrixTransformations, clientWorld, lift, hasFront, hasBack, hasLeft, hasRight);

                if (canRide) {
                    // Main logic for player movement inside the car (stock, verbatim)
                    VehicleRidingMovement.movePlayer(
                            millisElapsed, lift.getId(), 0,
                            floorsAndDoorways,
                            null, null, null,
                            absolutePositionAndRotation
                    );
                }
            }
        });

        if (!OptimizedRenderer.renderingShadows()) {
            MainRenderer.WORKER_THREAD.scheduleLifts(occlusionCullingInstance -> {
                final ObjectArrayList<Runnable> tasks = new ObjectArrayList<>();
                cullingTasks.forEach(occlusionCullingInstanceRunnableFunction -> tasks.add(occlusionCullingInstanceRunnableFunction.apply(occlusionCullingInstance)));
                minecraftClient.execute(() -> tasks.forEach(Runnable::run));
            });
        }
    }

    /**
     * One in-cab floor display per door side. For an unconfigured lift this
     * reproduces stock exactly: front display (rotated 180°) always, back display
     * when double-sided, both inset {@code depth/2 - 0.25} from the centre. Side
     * displays use the same expression with the width instead.
     */
    private static void renderDisplays(StoredMatrixTransformations storedMatrixTransformations, ClientWorld clientWorld, Lift lift,
                                       boolean hasFront, boolean hasBack, boolean hasLeft, boolean hasRight) {
        if (hasFront) {
            renderDisplay(storedMatrixTransformations, clientWorld, lift, 180, lift.getDepth());
        }
        if (hasBack) {
            renderDisplay(storedMatrixTransformations, clientWorld, lift, 0, lift.getDepth());
        }
        if (hasLeft) {
            renderDisplay(storedMatrixTransformations, clientWorld, lift, -90, lift.getWidth());
        }
        if (hasRight) {
            renderDisplay(storedMatrixTransformations, clientWorld, lift, 90, lift.getWidth());
        }
    }

    private static void renderDisplay(StoredMatrixTransformations storedMatrixTransformations, ClientWorld clientWorld, Lift lift, float rotateYDegrees, double wallDistance) {
        final StoredMatrixTransformations storedMatrixTransformationsNew = storedMatrixTransformations.copy();
        storedMatrixTransformationsNew.add(graphicsHolder -> {
            if (rotateYDegrees != 0) {
                graphicsHolder.rotateYDegrees(rotateYDegrees);
            }
            graphicsHolder.translate(0.875F, -1.5, wallDistance / 2 - 0.25 - SMALL_OFFSET);
        });
        RenderLifts.renderLiftDisplay(storedMatrixTransformationsNew, new World(clientWorld.data), lift, 0.1875F, 0.3125F);
    }

    /** Stock RenderLifts' private getLiftPositionAndRotation, reimplemented (all APIs public). */
    private static PositionAndRotation getLiftPositionAndRotation(ClientWorld clientWorld, Lift lift) {
        final Vector position = lift.getPosition((floorPosition1, floorPosition2) -> ItemLiftRefresher.findPath(new World(clientWorld.data), floorPosition1, floorPosition2));
        return new PositionAndRotation(new Vector(
                position.x + lift.getOffsetX(),
                position.y + lift.getOffsetY(),
                position.z + lift.getOffsetZ()
        ), -Math.PI / 2 - lift.getAngle().angleRadians, 0);
    }
}
