package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.OverlayTexture;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ModelPartExtension;
import org.mtr.mod.client.DoorAnimationType;
import org.mtr.mod.model.ModelTrainBase;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.RenderStage;

/**
 * Feature 3 — the lift cab model with doors on any of the four sides, a faithful
 * generalization of MTR's {@code org.mtr.mod.model.ModelLift1} (all cuboids,
 * UVs and pivots copied verbatim from its source; MTR 4.0.1's compiled class
 * matches that source per javap). Differences from stock:
 *
 * <ul>
 *   <li>Instead of "front always + back if double-sided", the constructor takes
 *       four booleans (cab space: front = -Z, back = +Z, left = -X, right = +X).
 *       Each door side renders the stock door assembly rotated onto that wall
 *       (side assemblies are the stock front/back assemblies rotated ±90° about
 *       the cab centre, with the wall distance using width instead of depth);
 *       each doorless side renders a solid wall using the exact piece layout
 *       stock uses for the non-door walls.</li>
 *   <li>{@code ModelTrainBase}'s public render only carries TWO door-value
 *       channels (left/right); the side doors' slide amounts are stashed on the
 *       instance by {@link #renderMultiDoor} before delegating to the base
 *       class, so the base's render-stage scheduling (LIGHT / INTERIOR /
 *       EXTERIOR / ALWAYS_ON_LIGHT layering) is reused unchanged.</li>
 *   <li>Stock's {@code wall_patch} (the side-wall stubs drawn beside a door on
 *       a 2-block-wide wall) is split into its two halves so a stub is skipped
 *       when the wall it returns onto is itself fully occupied by a door
 *       (adjacent full-width doors on a 2x2 cab).</li>
 * </ul>
 *
 * <p>Instances are created per lift per frame, exactly like stock creates
 * {@code new ModelLift1(...)} per frame — same cost profile.</p>
 */
@Environment(EnvType.CLIENT)
public class AddonModelLift extends ModelTrainBase {

    private static final float PI = (float) Math.PI;
    private static final float HALF_PI = (float) (Math.PI / 2);
    /**
     * Corner de-fighting nudge for the ±X door assemblies, in model pixels
     * (0.1 px = 1/160 block). Where two adjacent walls both carry doors, the
     * assemblies' frame posts overlap with coplanar faces at the shared corner,
     * and their floor/ceiling strips share the y=0 / ceiling planes — stock
     * never hits this because stock doors only exist on opposite walls. The
     * side assemblies are shifted this much toward the cab centre (kills the
     * post-face pairs, and pulls the exterior skin off the block boundary) and
     * their floor raised / ceiling lowered by the same amount.
     */
    private static final float CORNER_EPS = 0.1f;

    private final ModelPartExtension main;
    private final ModelPartExtension main_ceiling;
    private final ModelPartExtension main_edge;
    private final ModelPartExtension main_edge_wall;
    private final ModelPartExtension main_edge_ceiling;
    private final ModelPartExtension main_corner;
    private final ModelPartExtension main_corner_wall;
    private final ModelPartExtension main_corner_ceiling;
    private final ModelPartExtension main_exterior;
    private final ModelPartExtension main_exterior_ceiling;
    private final ModelPartExtension main_exterior_edge;
    private final ModelPartExtension main_exterior_edge_ceiling;
    private final ModelPartExtension main_exterior_corner;
    private final ModelPartExtension main_exterior_corner_wall;
    private final ModelPartExtension main_exterior_corner_ceiling;
    private final ModelPartExtension main_light;
    private final ModelPartExtension door;
    private final ModelPartExtension door_left;
    private final ModelPartExtension door_right;
    private final ModelPartExtension door_wall;
    private final ModelPartExtension door_ceiling;
    private final ModelPartExtension door_exterior;
    private final ModelPartExtension door_left_exterior;
    private final ModelPartExtension door_right_exterior;
    private final ModelPartExtension door_wall_exterior;
    private final ModelPartExtension door_ceiling_exterior;
    /** Stock wall_patch's first half (wall_r2 child): the stub toward the wall on the door's -X end (pre-rotation). */
    private final ModelPartExtension wall_patch_a;
    /** Stock wall_patch's second half (wall_r3 child). */
    private final ModelPartExtension wall_patch_b;
    private final ModelPartExtension wall_patch_wall_a;
    private final ModelPartExtension wall_patch_wall_b;

    private final int heightCount;
    private final int heightOffset;
    private final int width;
    private final int depth;
    private final boolean doorFront;
    private final boolean doorBack;
    private final boolean doorLeft;
    private final boolean doorRight;

    /**
     * Slide amounts for the ±X doors, stashed by {@link #renderMultiDoor} before
     * the base class schedules the render stages. Safe as instance state: one
     * model instance per lift per frame, values written once before scheduling.
     */
    private float leftDoorSlideZ;
    private float rightDoorSlideZ;

    public AddonModelLift(int height, int width, int depth, boolean doorFront, boolean doorBack, boolean doorLeft, boolean doorRight) {
        super(128, 128, DoorAnimationType.CONSTANT, false);
        heightCount = height - 4;
        heightOffset = -heightCount * 8;
        this.width = width;
        this.depth = depth;
        this.doorFront = doorFront;
        this.doorBack = doorBack;
        this.doorLeft = doorLeft;
        this.doorRight = doorRight;

        // ---- everything below is ModelLift1's constructor, verbatim (wall_patch split into halves) ----

        main = createModelPart();
        main.setPivot(0, 24, 0);
        main.setTextureUVOffset(0, 34).addCuboid(-8, 0, -8, 16, 0, 16, 0, false);

        main_ceiling = createModelPart();
        main_ceiling.setPivot(0, 24, 0);
        main_ceiling.setTextureUVOffset(79, 44).addCuboid(-8, -32, -8, 16, 0, 16, 0, false);

        main_edge = createModelPart();
        main_edge.setPivot(0, 24, 0);
        main_edge.setTextureUVOffset(18, 44).addCuboid(-4, 0, -8, 8, 0, 6, 0, false);
        main_edge.setTextureUVOffset(76, 33).addCuboid(-4, -32, -3, 8, 32, 1, 0, false);
        main_edge.setTextureUVOffset(28, 52).addCuboid(-4, -13, -4, 8, 1, 1, 0, false);
        main_edge.setTextureUVOffset(26, 50).addCuboid(-4, -2, -4, 8, 1, 1, 0, false);

        main_edge_wall = createModelPart();
        main_edge_wall.setPivot(0, 24, 0);
        main_edge_wall.setTextureUVOffset(76, 33).addCuboid(-4, -40, -3, 8, 8, 1, 0, false);

        main_edge_ceiling = createModelPart();
        main_edge_ceiling.setPivot(0, 24, 0);
        main_edge_ceiling.setTextureUVOffset(97, 44).addCuboid(-4, -32, -8, 8, 0, 6, 0, false);

        main_corner = createModelPart();
        main_corner.setPivot(0, 24, 0);
        main_corner.setTextureUVOffset(20, 44).addCuboid(2, 0, -8, 6, 0, 6, 0, false);
        main_corner.setTextureUVOffset(112, 62).addCuboid(3, -32, -3, 5, 32, 1, 0, false);
        main_corner.setTextureUVOffset(29, 52).addCuboid(5, -13, -4, 3, 1, 1, 0, false);
        main_corner.setTextureUVOffset(27, 50).addCuboid(5, -2, -4, 3, 1, 1, 0, false);
        main_corner.setTextureUVOffset(104, 68).addCuboid(2, -32, -3, 1, 32, 1, 0, false);

        final ModelPartExtension handrail_bottom_r1 = main_corner.addChild();
        handrail_bottom_r1.setPivot(0, 0, 0);
        setRotationAngle(handrail_bottom_r1, 0, -1.5708F, 0);
        handrail_bottom_r1.setTextureUVOffset(36, 50).addCuboid(-8, -2, -4, 3, 1, 1, 0, false);
        handrail_bottom_r1.setTextureUVOffset(38, 52).addCuboid(-8, -13, -4, 3, 1, 1, 0, false);
        handrail_bottom_r1.setTextureUVOffset(112, 62).addCuboid(-8, -32, -3, 5, 32, 1, 0, false);

        main_corner_wall = createModelPart();
        main_corner_wall.setPivot(0, 24, 0);
        main_corner_wall.setTextureUVOffset(112, 62).addCuboid(3, -40, -3, 5, 8, 1, 0, false);
        main_corner_wall.setTextureUVOffset(104, 68).addCuboid(2, -40, -3, 1, 8, 1, 0, false);

        final ModelPartExtension wall_r1 = main_corner_wall.addChild();
        wall_r1.setPivot(0, 0, 0);
        setRotationAngle(wall_r1, 0, -1.5708F, 0);
        wall_r1.setTextureUVOffset(112, 62).addCuboid(-8, -40, -3, 5, 8, 1, 0, false);

        main_corner_ceiling = createModelPart();
        main_corner_ceiling.setPivot(0, 24, 0);
        main_corner_ceiling.setTextureUVOffset(99, 44).addCuboid(2, -32, -8, 6, 0, 6, 0, false);

        main_exterior = createModelPart();
        main_exterior.setPivot(0, 24, 0);
        main_exterior.setTextureUVOffset(0, 17).addCuboid(-8, 0, -8, 16, 1, 16, 0, false);

        main_exterior_ceiling = createModelPart();
        main_exterior_ceiling.setPivot(0, 24, 0);
        main_exterior_ceiling.setTextureUVOffset(0, 0).addCuboid(-8, -33, -8, 16, 1, 16, 0, false);

        main_exterior_edge = createModelPart();
        main_exterior_edge.setPivot(0, 24, 0);
        main_exterior_edge.setTextureUVOffset(18, 27).addCuboid(-4, 0, -8, 8, 1, 6, 0, false);

        main_exterior_edge_ceiling = createModelPart();
        main_exterior_edge_ceiling.setPivot(0, 24, 0);
        main_exterior_edge_ceiling.setTextureUVOffset(18, 10).addCuboid(-4, -33, -8, 8, 1, 6, 0, false);

        main_exterior_corner = createModelPart();
        main_exterior_corner.setPivot(0, 24, 0);
        main_exterior_corner.setTextureUVOffset(20, 27).addCuboid(2, 0, -8, 6, 1, 6, 0, false);

        main_exterior_corner_wall = createModelPart();
        main_exterior_corner_wall.setPivot(0, 24, 0);
        main_exterior_corner_wall.setTextureUVOffset(108, 68).addCuboid(2, -40, -3, 1, 8, 1, 0, false);

        main_exterior_corner_ceiling = createModelPart();
        main_exterior_corner_ceiling.setPivot(0, 24, 0);
        main_exterior_corner_ceiling.setTextureUVOffset(20, 10).addCuboid(2, -33, -8, 6, 1, 6, 0, false);

        main_light = createModelPart();
        main_light.setPivot(0, 24, 0);
        main_light.setTextureUVOffset(79, 28).addCuboid(-8, -32.5F, -8, 16, 0, 16, 0, false);

        door = createModelPart();
        door.setPivot(0, 24, 0);
        door.setTextureUVOffset(90, 66).addCuboid(-16, -32, 4, 4, 32, 3, 0, false);
        door.setTextureUVOffset(14, 84).addCuboid(12, -32, 4, 4, 32, 3, 0, false);
        door.setTextureUVOffset(20, 101).addCuboid(-16, 0, 0, 32, 0, 8, 0, false);

        door_left = createModelPart();
        door_left.setPivot(0, 24, 0);
        door_left.setTextureUVOffset(52, 68).addCuboid(-12, -32, 6, 12, 32, 0, 0, false);

        door_right = createModelPart();
        door_right.setPivot(0, 24, 0);
        door_right.setTextureUVOffset(28, 68).addCuboid(0, -32, 6, 12, 32, 0, 0, false);

        door_wall = createModelPart();
        door_wall.setPivot(0, 24, 0);
        door_wall.setTextureUVOffset(48, 0).addCuboid(-16, -40, 4, 32, 8, 3, 0, false);

        door_ceiling = createModelPart();
        door_ceiling.setPivot(0, 24, 0);
        door_ceiling.setTextureUVOffset(20, 101).addCuboid(-16, -32, 0, 32, 0, 8, 0, false);

        door_exterior = createModelPart();
        door_exterior.setPivot(0, 24, 0);
        door_exterior.setTextureUVOffset(0, 84).addCuboid(-16, -32, 4, 4, 32, 3, 0, false);
        door_exterior.setTextureUVOffset(76, 66).addCuboid(12, -32, 4, 4, 32, 3, 0, false);
        door_exterior.setTextureUVOffset(28, 109).addCuboid(-16, 0, 0, 32, 1, 8, 0, false);

        door_left_exterior = createModelPart();
        door_left_exterior.setPivot(0, 24, 0);
        door_left_exterior.setTextureUVOffset(0, 50).addCuboid(-12, -32, 6, 12, 32, 2, 0, false);

        door_right_exterior = createModelPart();
        door_right_exterior.setPivot(0, 24, 0);
        door_right_exterior.setTextureUVOffset(48, 34).addCuboid(0, -32, 6, 12, 32, 2, 0, false);

        door_wall_exterior = createModelPart();
        door_wall_exterior.setPivot(0, 24, 0);
        door_wall_exterior.setTextureUVOffset(48, 17).addCuboid(-16, -40, 5, 32, 8, 3, 0, false);

        door_ceiling_exterior = createModelPart();
        door_ceiling_exterior.setPivot(0, 24, 0);
        door_ceiling_exterior.setTextureUVOffset(0, 119).addCuboid(-16, -33, 0, 32, 1, 8, 0, false);

        wall_patch_a = createModelPart();
        wall_patch_a.setPivot(0, 24, 0);

        final ModelPartExtension wall_r2 = wall_patch_a.addChild();
        wall_r2.setPivot(0, 0, 0);
        setRotationAngle(wall_r2, 0, -1.5708F, 0);
        wall_r2.setTextureUVOffset(108, 95).addCuboid(0, -32, 13, 4, 32, 1, 0, false);
        wall_r2.setTextureUVOffset(30, 50).addCuboid(0, -2, 12, 4, 1, 1, 0, false);
        wall_r2.setTextureUVOffset(32, 52).addCuboid(0, -13, 12, 4, 1, 1, 0, false);

        wall_patch_b = createModelPart();
        wall_patch_b.setPivot(0, 24, 0);

        final ModelPartExtension wall_r3 = wall_patch_b.addChild();
        wall_r3.setPivot(0, 0, 0);
        setRotationAngle(wall_r3, 0, 1.5708F, 0);
        wall_r3.setTextureUVOffset(108, 95).addCuboid(-4, -32, 13, 4, 32, 1, 0, false);
        wall_r3.setTextureUVOffset(30, 50).addCuboid(-4, -2, 12, 4, 1, 1, 0, false);
        wall_r3.setTextureUVOffset(32, 52).addCuboid(-4, -13, 12, 4, 1, 1, 0, false);

        wall_patch_wall_a = createModelPart();
        wall_patch_wall_a.setPivot(0, 24, 0);

        final ModelPartExtension wall_r4 = wall_patch_wall_a.addChild();
        wall_r4.setPivot(0, 0, 0);
        setRotationAngle(wall_r4, 0, -1.5708F, 0);
        wall_r4.setTextureUVOffset(108, 95).addCuboid(0, -40, 13, 4, 8, 1, 0, false);

        wall_patch_wall_b = createModelPart();
        wall_patch_wall_b.setPivot(0, 24, 0);

        final ModelPartExtension wall_r5 = wall_patch_wall_b.addChild();
        wall_r5.setPivot(0, 0, 0);
        setRotationAngle(wall_r5, 0, 1.5708F, 0);
        wall_r5.setTextureUVOffset(108, 95).addCuboid(-4, -40, 13, 4, 8, 1, 0, false);

        buildModel();
    }

    /**
     * Renders the cab with an independent door-open value per side (each 0..1,
     * stock convention: {@code doorValue / LIFT_DOOR_VALUE} while that side's
     * doorway is open, else 0). Front/back travel through the base class's two
     * door channels; the side doors' slide amounts are computed here with the
     * exact same animation call the base class makes, then read back inside the
     * protected render.
     */
    public void renderMultiDoor(StoredMatrixTransformations storedMatrixTransformations, Identifier texture, int light,
                                float doorFrontValue, float doorBackValue, float doorLeftValue, float doorRightValue) {
        leftDoorSlideZ = DoorAnimationType.getDoorAnimationZ(doorAnimationType, getDoorMax(), getDoorDuration(), doorLeftValue, false);
        rightDoorSlideZ = DoorAnimationType.getDoorAnimationZ(doorAnimationType, getDoorMax(), getDoorDuration(), doorRightValue, false);
        // Same trailing arguments as stock RenderLifts passes to ModelLift1.render:
        // opening=false, car 0 of 1, head1IsFront, lightsOn, not translucent, renderDetails, not atPlatform.
        render(storedMatrixTransformations, null, texture, light, doorFrontValue, doorBackValue, false, 0, 1, true, true, false, true, false);
    }

    @Override
    protected void render(GraphicsHolder graphicsHolder, RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails) {
        // Base-class door channels: left = front (-Z) door, right = back (+Z) door.
        final float doorFrontZ = doorLeftZ;
        final float doorBackZ = doorRightZ;
        final float halfWidth = width * 8;
        final float halfDepth = depth * 8;

        // ---- interior grid cells: floor, ceiling, light (stock, unaffected by door sides)
        if (renderStage == RenderStage.LIGHT) {
            for (int i = 1; i < width; i++) {
                for (int j = 1; j < depth; j++) {
                    ModelTrainBase.renderOnce(main_light, graphicsHolder, light, (i - width / 2F) * 16, heightOffset, (j - depth / 2F) * 16);
                }
            }
            return;
        }
        if (renderStage != RenderStage.INTERIOR && renderStage != RenderStage.EXTERIOR) {
            return; // stock ModelLift1 draws nothing for the other stages either
        }

        final boolean isInterior = renderStage == RenderStage.INTERIOR;
        final ModelPartExtension mainPiece = isInterior ? main : main_exterior;
        final ModelPartExtension mainCeilingPiece = isInterior ? main_ceiling : main_exterior_ceiling;
        final ModelPartExtension mainEdgePiece = isInterior ? main_edge : main_exterior_edge;
        final ModelPartExtension mainEdgeCeilingPiece = isInterior ? main_edge_ceiling : main_exterior_edge_ceiling;
        final ModelPartExtension mainCornerPiece = isInterior ? main_corner : main_exterior_corner;
        final ModelPartExtension mainCornerWallPiece = isInterior ? main_corner_wall : main_exterior_corner_wall;
        final ModelPartExtension mainCornerCeilingPiece = isInterior ? main_corner_ceiling : main_exterior_corner_ceiling;
        final ModelPartExtension doorLeftPiece = isInterior ? door_left : door_left_exterior;
        final ModelPartExtension doorRightPiece = isInterior ? door_right : door_right_exterior;
        final ModelPartExtension doorPiece = isInterior ? door : door_exterior;
        final ModelPartExtension doorWallPiece = isInterior ? door_wall : door_wall_exterior;
        final ModelPartExtension doorCeilingPiece = isInterior ? door_ceiling : door_ceiling_exterior;

        for (int i = 1; i < width; i++) {
            for (int j = 1; j < depth; j++) {
                final float x = (i - width / 2F) * 16;
                final float z = (j - depth / 2F) * 16;
                ModelTrainBase.renderOnce(mainPiece, graphicsHolder, light, x, z);
                ModelTrainBase.renderOnce(mainCeilingPiece, graphicsHolder, light, x, heightOffset, z);
            }
        }

        // A door on a 2-block wall spans the whole wall (door assembly incl. frame = 32 px).
        final boolean fullDoorFront = doorFront && width == 2;
        final boolean fullDoorBack = doorBack && width == 2;
        final boolean fullDoorLeft = doorLeft && depth == 2;
        final boolean fullDoorRight = doorRight && depth == 2;

        // ---- FRONT wall (-Z): stock front-door assembly, or a solid wall of flipped stock segments
        if (doorFront) {
            // Stock: renderOnceFlipped(...) calls, written out as explicit (x, y, z, rotY) piece renders.
            part(doorLeftPiece, graphicsHolder, light, doorFrontZ, 0, 8 - halfDepth, PI);
            part(doorRightPiece, graphicsHolder, light, -doorFrontZ, 0, 8 - halfDepth, PI);
            part(doorPiece, graphicsHolder, light, 0, 0, 8 - halfDepth, PI);
            wallBands(doorWallPiece, graphicsHolder, light, 0, 8 - halfDepth, PI);
            part(doorCeilingPiece, graphicsHolder, light, 0, heightOffset, 8 - halfDepth, PI);
            if (isInterior && width == 2) {
                // Skip a stub when the wall it returns onto is itself a full-width
                // door wall. Flipped by PI, patch_a lands at the +X (right) end.
                if (!fullDoorRight) {
                    part(wall_patch_a, graphicsHolder, light, 0, 0, 8 - halfDepth, PI);
                    wallBands(wall_patch_wall_a, graphicsHolder, light, 0, 8 - halfDepth, PI);
                }
                if (!fullDoorLeft) {
                    part(wall_patch_b, graphicsHolder, light, 0, 0, 8 - halfDepth, PI);
                    wallBands(wall_patch_wall_b, graphicsHolder, light, 0, 8 - halfDepth, PI);
                }
            }
            for (int i = 1; i < width - 2; i++) {
                final float p = i * 8 - halfWidth + 4;
                part(mainEdgePiece, graphicsHolder, light, -p, 0, -halfDepth, PI);
                part(mainEdgePiece, graphicsHolder, light, p, 0, -halfDepth, PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, -p, -halfDepth, PI);
                    wallBands(main_edge_wall, graphicsHolder, light, p, -halfDepth, PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, -p, heightOffset, -halfDepth, PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, p, heightOffset, -halfDepth, PI);
            }
        } else {
            // Solid front wall: the stock back-wall cell layout, flipped onto -Z
            // (the same flipped pieces stock uses for the front wall's door-adjacent segments).
            for (int i = 1; i < width; i++) {
                final float x = (i - width / 2F) * 16;
                part(mainEdgePiece, graphicsHolder, light, -(x - 4), 0, -halfDepth, PI);
                part(mainEdgePiece, graphicsHolder, light, -(x + 4), 0, -halfDepth, PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, -(x - 4), -halfDepth, PI);
                    wallBands(main_edge_wall, graphicsHolder, light, -(x + 4), -halfDepth, PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, -(x - 4), heightOffset, -halfDepth, PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, -(x + 4), heightOffset, -halfDepth, PI);
            }
        }

        // ---- BACK wall (+Z): stock double-sided door assembly, or the stock solid back wall
        if (doorBack) {
            part(doorLeftPiece, graphicsHolder, light, -doorBackZ, 0, halfDepth - 8, 0);
            part(doorRightPiece, graphicsHolder, light, doorBackZ, 0, halfDepth - 8, 0);
            part(doorPiece, graphicsHolder, light, 0, 0, halfDepth - 8, 0);
            wallBands(doorWallPiece, graphicsHolder, light, 0, halfDepth - 8, 0);
            part(doorCeilingPiece, graphicsHolder, light, 0, heightOffset, halfDepth - 8, 0);
            if (isInterior && width == 2) {
                // Unflipped, patch_a lands at the -X (left) end.
                if (!fullDoorLeft) {
                    part(wall_patch_a, graphicsHolder, light, 0, 0, halfDepth - 8, 0);
                    wallBands(wall_patch_wall_a, graphicsHolder, light, 0, halfDepth - 8, 0);
                }
                if (!fullDoorRight) {
                    part(wall_patch_b, graphicsHolder, light, 0, 0, halfDepth - 8, 0);
                    wallBands(wall_patch_wall_b, graphicsHolder, light, 0, halfDepth - 8, 0);
                }
            }
            for (int i = 1; i < width - 2; i++) {
                final float p = i * 8 - halfWidth + 4;
                part(mainEdgePiece, graphicsHolder, light, p, 0, halfDepth, 0);
                part(mainEdgePiece, graphicsHolder, light, -p, 0, halfDepth, 0);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, p, halfDepth, 0);
                    wallBands(main_edge_wall, graphicsHolder, light, -p, halfDepth, 0);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, p, heightOffset, halfDepth, 0);
                part(mainEdgeCeilingPiece, graphicsHolder, light, -p, heightOffset, halfDepth, 0);
            }
        } else {
            // Stock's edge2Z (!isDoubleSided) branch, verbatim.
            for (int i = 1; i < width; i++) {
                final float x = (i - width / 2F) * 16;
                part(mainEdgePiece, graphicsHolder, light, x - 4, 0, halfDepth, 0);
                part(mainEdgePiece, graphicsHolder, light, x + 4, 0, halfDepth, 0);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, x - 4, halfDepth, 0);
                    wallBands(main_edge_wall, graphicsHolder, light, x + 4, halfDepth, 0);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, x - 4, heightOffset, halfDepth, 0);
                part(mainEdgeCeilingPiece, graphicsHolder, light, x + 4, heightOffset, halfDepth, 0);
            }
        }

        // ---- LEFT wall (-X): the stock door assembly turned onto the -X wall
        // (same -HALF_PI as edge1X; the wall distance uses width instead of depth),
        // or the stock edge1X solid side wall.
        if (doorLeft) {
            // -HALF_PI, matching the solid edge1X branch below: vanilla yaw +90
            // maps local +z to world +x, and this assembly's wall side is local
            // +z, so the -X wall needs the NEGATIVE rotation. +HALF_PI here put
            // the whole assembly ~12 px inboard - the door stood at the cab
            // centre and its leaves crossed into an overlapped middle column.
            final float leftPivotX = 8 - halfWidth + CORNER_EPS;
            part(doorLeftPiece, graphicsHolder, light, leftPivotX, -CORNER_EPS, -leftDoorSlideZ, -HALF_PI);
            part(doorRightPiece, graphicsHolder, light, leftPivotX, -CORNER_EPS, leftDoorSlideZ, -HALF_PI);
            part(doorPiece, graphicsHolder, light, leftPivotX, -CORNER_EPS, 0, -HALF_PI);
            wallBands(doorWallPiece, graphicsHolder, light, leftPivotX, 0, -HALF_PI);
            part(doorCeilingPiece, graphicsHolder, light, leftPivotX, heightOffset + CORNER_EPS, 0, -HALF_PI);
            if (isInterior && depth == 2) {
                // At -HALF_PI, patch_a lands at the doorway's -Z (front) end and
                // patch_b at the +Z (back) end - gate each on the wall it returns onto.
                if (!fullDoorFront) {
                    part(wall_patch_a, graphicsHolder, light, leftPivotX, -CORNER_EPS, 0, -HALF_PI);
                    wallBands(wall_patch_wall_a, graphicsHolder, light, leftPivotX, 0, -HALF_PI);
                }
                if (!fullDoorBack) {
                    part(wall_patch_b, graphicsHolder, light, leftPivotX, -CORNER_EPS, 0, -HALF_PI);
                    wallBands(wall_patch_wall_b, graphicsHolder, light, leftPivotX, 0, -HALF_PI);
                }
            }
            for (int i = 1; i < depth - 2; i++) {
                final float p = i * 8 - halfDepth + 4;
                part(mainEdgePiece, graphicsHolder, light, -halfWidth, 0, p, -HALF_PI);
                part(mainEdgePiece, graphicsHolder, light, -halfWidth, 0, -p, -HALF_PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, -halfWidth, p, -HALF_PI);
                    wallBands(main_edge_wall, graphicsHolder, light, -halfWidth, -p, -HALF_PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, -halfWidth, heightOffset, p, -HALF_PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, -halfWidth, heightOffset, -p, -HALF_PI);
            }
        } else {
            // Stock's edge1X branch, verbatim.
            for (int j = 1; j < depth; j++) {
                final float z = (j - depth / 2F) * 16;
                part(mainEdgePiece, graphicsHolder, light, -halfWidth, 0, z - 4, -HALF_PI);
                part(mainEdgePiece, graphicsHolder, light, -halfWidth, 0, z + 4, -HALF_PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, -halfWidth, z - 4, -HALF_PI);
                    wallBands(main_edge_wall, graphicsHolder, light, -halfWidth, z + 4, -HALF_PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, -halfWidth, heightOffset, z - 4, -HALF_PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, -halfWidth, heightOffset, z + 4, -HALF_PI);
            }
        }

        // ---- RIGHT wall (+X): the stock door assembly turned onto the +X wall
        // (same +HALF_PI as edge2X), or the stock edge2X solid side wall.
        if (doorRight) {
            // +HALF_PI, the mirror of the left wall (see comment there).
            final float rightPivotX = halfWidth - 8 - CORNER_EPS;
            part(doorLeftPiece, graphicsHolder, light, rightPivotX, -CORNER_EPS, rightDoorSlideZ, HALF_PI);
            part(doorRightPiece, graphicsHolder, light, rightPivotX, -CORNER_EPS, -rightDoorSlideZ, HALF_PI);
            part(doorPiece, graphicsHolder, light, rightPivotX, -CORNER_EPS, 0, HALF_PI);
            wallBands(doorWallPiece, graphicsHolder, light, rightPivotX, 0, HALF_PI);
            part(doorCeilingPiece, graphicsHolder, light, rightPivotX, heightOffset + CORNER_EPS, 0, HALF_PI);
            if (isInterior && depth == 2) {
                // At +HALF_PI, patch_a lands at the doorway's +Z (back) end.
                if (!fullDoorBack) {
                    part(wall_patch_a, graphicsHolder, light, rightPivotX, -CORNER_EPS, 0, HALF_PI);
                    wallBands(wall_patch_wall_a, graphicsHolder, light, rightPivotX, 0, HALF_PI);
                }
                if (!fullDoorFront) {
                    part(wall_patch_b, graphicsHolder, light, rightPivotX, -CORNER_EPS, 0, HALF_PI);
                    wallBands(wall_patch_wall_b, graphicsHolder, light, rightPivotX, 0, HALF_PI);
                }
            }
            for (int i = 1; i < depth - 2; i++) {
                final float p = i * 8 - halfDepth + 4;
                part(mainEdgePiece, graphicsHolder, light, halfWidth, 0, -p, HALF_PI);
                part(mainEdgePiece, graphicsHolder, light, halfWidth, 0, p, HALF_PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, halfWidth, -p, HALF_PI);
                    wallBands(main_edge_wall, graphicsHolder, light, halfWidth, p, HALF_PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, halfWidth, heightOffset, -p, HALF_PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, halfWidth, heightOffset, p, HALF_PI);
            }
        } else {
            // Stock's edge2X branch, verbatim.
            for (int j = 1; j < depth; j++) {
                final float z = (j - depth / 2F) * 16;
                part(mainEdgePiece, graphicsHolder, light, halfWidth, 0, z - 4, HALF_PI);
                part(mainEdgePiece, graphicsHolder, light, halfWidth, 0, z + 4, HALF_PI);
                if (isInterior) {
                    wallBands(main_edge_wall, graphicsHolder, light, halfWidth, z - 4, HALF_PI);
                    wallBands(main_edge_wall, graphicsHolder, light, halfWidth, z + 4, HALF_PI);
                }
                part(mainEdgeCeilingPiece, graphicsHolder, light, halfWidth, heightOffset, z - 4, HALF_PI);
                part(mainEdgeCeilingPiece, graphicsHolder, light, halfWidth, heightOffset, z + 4, HALF_PI);
            }
        }

        // ---- corners: stock positions/rotations; suppressed when an adjacent wall
        // is fully occupied by a door (stock's width==2 gating, generalized).
        if (!fullDoorBack && !fullDoorLeft) {
            corner(mainCornerPiece, mainCornerWallPiece, mainCornerCeilingPiece, graphicsHolder, light, isInterior, -halfWidth, halfDepth, 0);
        }
        if (!fullDoorBack && !fullDoorRight) {
            corner(mainCornerPiece, mainCornerWallPiece, mainCornerCeilingPiece, graphicsHolder, light, isInterior, halfWidth, halfDepth, HALF_PI);
        }
        if (!fullDoorFront && !fullDoorLeft) {
            corner(mainCornerPiece, mainCornerWallPiece, mainCornerCeilingPiece, graphicsHolder, light, isInterior, -halfWidth, -halfDepth, -HALF_PI);
        }
        if (!fullDoorFront && !fullDoorRight) {
            corner(mainCornerPiece, mainCornerWallPiece, mainCornerCeilingPiece, graphicsHolder, light, isInterior, halfWidth, -halfDepth, PI);
        }
    }

    @Override
    protected void baseTransform(GraphicsHolder graphicsHolder) {
        // Stock ModelLift1 checks for the 1.16.5 offset bug; this build is 1.20.4-only.
        graphicsHolder.translate(0, -1.5, 0);
    }

    @Override
    protected int getDoorMax() {
        return 24 / 4; // stock ModelLift1
    }

    // ------------------------------------------------------------- helpers

    /** One piece at an explicit pivot translation + Y rotation (the general form of stock's renderOnce/renderOnceFlipped). */
    private static void part(ModelPartExtension piece, GraphicsHolder graphicsHolder, int light, float x, float y, float z, float rotateY) {
        piece.render(graphicsHolder, x, y, z, rotateY, light, OverlayTexture.getDefaultUvMapped());
    }

    /** The upper-wall band repeated per extra height unit — stock's renderWall/renderWallOnce/renderWallOnceFlipped generalized. */
    private void wallBands(ModelPartExtension piece, GraphicsHolder graphicsHolder, int light, float x, float z, float rotateY) {
        for (int i = 0; i < heightCount; i++) {
            piece.render(graphicsHolder, x, -i * 8, z, rotateY, light, OverlayTexture.getDefaultUvMapped());
        }
    }

    private void corner(ModelPartExtension cornerPiece, ModelPartExtension cornerWallPiece, ModelPartExtension cornerCeilingPiece,
                        GraphicsHolder graphicsHolder, int light, boolean isInterior, float x, float z, float rotateY) {
        part(cornerPiece, graphicsHolder, light, x, 0, z, rotateY);
        if (isInterior) {
            wallBands(cornerWallPiece, graphicsHolder, light, x, z, rotateY);
        }
        part(cornerCeilingPiece, graphicsHolder, light, x, heightOffset, z, rotateY);
    }
}
