package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.StationDecorBlock;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.mtr.core.data.Station;

/**
 * Renders the station decor blocks: the NYC mosaic station-name band and the
 * station-colored / named iron columns. Same emissive quad-canvas pipeline as
 * the PIDS screens; station name and color come live from MTR
 * ({@code InitClient.findStation}), with the block's custom name overriding
 * the text when set.
 */
@Environment(EnvType.CLIENT)
public class StationDecorRenderer implements BlockEntityRenderer<StationDecorBlockEntity> {
    private static final int MOSAIC_FIELD = 0xFFE8E0CE;   // cream tile field
    private static final int MOSAIC_GROUT = 0xFFCDC3AC;   // grout lines between tiles
    private static final int MOSAIC_TEXT = 0xFF17172B;    // deep navy lettering
    private static final int FALLBACK_COLOR = 0xFF1F4D3A; // classic dark green border
    private static final int BOARD_BLACK = 0xFF0C0C0E;
    private static final int TEXT_WHITE = 0xFFF5F5F5;

    /** Canvas resolution used throughout: 64 units per block. */
    private static final float UNIT = 1.0f / 64.0f;

    @Override
    public void render(StationDecorBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        net.minecraft.block.Block block = entity.getCachedState().getBlock();
        // Holding lights draw no text at all — they never need the station
        // lookup, which is the most expensive thing in this method.
        if (block instanceof com.stationannouncer.mtr.HoldingLightBlock holdingLight) {
            matrices.push();
            matrices.translate(0.5, 0.0, 0.5);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facingOf(entity).asRotation()));
            paintHoldingLight(entity, matrices, vertexConsumers, holdingLight);
            matrices.pop();
            return;
        }

        Direction facing;
        if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            // The panel plane follows the railing run; the block owns that rule
            // so the settings screen can name the two faces the same way.
            facing = com.stationannouncer.mtr.RailingSignBlock.frontOf(entity.getCachedState());
        } else {
            facing = entity.getCachedState().contains(StationDecorBlock.FACING)
                    ? entity.getCachedState().get(StationDecorBlock.FACING)
                    : Direction.NORTH;
        }

        Station station = MtrDataCache.station(entity.getPos());
        String custom = entity.getCustomName();
        String name = !custom.isEmpty() ? custom
                : station != null ? firstLang(station.getName()) : "";
        int color = 0xFF000000 | (station != null ? station.getColor() : FALLBACK_COLOR & 0xFFFFFF);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        if (block instanceof StationDecorBlock) {
            // Adjacent mosaics merge into one band (see mosaicPanel): a lone
            // block centres on itself, two side by side centre on their seam.
            MosaicPanel mosaic = mosaicPanel(entity, facing, name);
            if (mosaic.run() > 0) {
                paintMosaic(matrices, vertexConsumers, mosaic.text(), color, mosaic.run());
            }
        } else if (block instanceof com.stationannouncer.mtr.StationColumnBlock) {
            paintColumnBoard(matrices, vertexConsumers, name);
        } else if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            paintRailingSign(entity, matrices, vertexConsumers, name);
        }
        matrices.pop();
    }

    /** The block's facing, defaulting to north for blocks without the property. */
    private static Direction facingOf(StationDecorBlockEntity entity) {
        return entity.getCachedState().contains(StationDecorBlock.FACING)
                ? entity.getCachedState().get(StationDecorBlock.FACING)
                : Direction.NORTH;
    }

    // ------------------------------------------------------ holding lights

    /** Last known departure time per holding-light position (for the green 15 s window). */
    private static final java.util.Map<Long, Long> LAST_DEPARTURE = new java.util.HashMap<>();

    /** Drops all per-position state (called on disconnect so nothing leaks across worlds). */
    static void clearWorldState() {
        LAST_DEPARTURE.clear();
        SIGN_PANELS.clear();
        RouteBullets.clearCache();
        lastNameInput = null;
        lastNameUpper = "";
    }

    /**
     * One-entry memo for {@code name.toUpperCase()}. Station names are drawn
     * upper-case on every mosaic and name board, every frame, and a whole
     * station's worth of columns share one name.
     */
    private static String lastNameInput;
    private static String lastNameUpper = "";

    private static String upperCase(String name) {
        if (!name.equals(lastNameInput)) {
            lastNameInput = name;
            lastNameUpper = name.toUpperCase();
        }
        return lastNameUpper;
    }

    /**
     * Three round lenses on both faces of the hanging box. Yellow: lit from
     * {@code on} seconds before arrival until {@code off} seconds before
     * departure (held trains keep it lit, since MTR pushes the departure time
     * back). Green: solid from {@code on} seconds before departure until
     * {@code off} seconds after the train has gone. Both edges default to the
     * light's own timing and are adjustable per block with the MTR brush.
     *
     * <p>A yellow light may additionally follow the addon's platform hold rules
     * (Feature 1): while a rule is actually holding a train at its platform the
     * lenses are lit, and in {@code ONLY} mode that is all the light ever does.
     * Every state of every holding light is solid — a lamp on a platform is
     * either on or it is off.</p>
     */
    private void paintHoldingLight(StationDecorBlockEntity entity, MatrixStack matrices,
                                   VertexConsumerProvider vertexConsumers,
                                   com.stationannouncer.mtr.HoldingLightBlock light) {
        boolean green = light.green;
        // Timing is per block: the brush can move both edges of the window.
        long onMillis = 1000L * entity.lightSecondsOr(entity.getLightOnSeconds(), light.defaultOnSeconds());
        long offMillis = 1000L * entity.lightSecondsOr(entity.getLightOffSeconds(), light.defaultOffSeconds());
        long platformId = resolvePlatform(entity);
        StationDecorBlockEntity.HoldIndicator holdMode =
                green ? StationDecorBlockEntity.HoldIndicator.OFF : entity.getHoldIndicator();
        boolean active = false;
        if (platformId != 0 && holdMode != StationDecorBlockEntity.HoldIndicator.ONLY) {
            long offset = org.mtr.mod.data.ArrivalsCacheClient.INSTANCE.getMillisOffset();
            long now = System.currentTimeMillis();
            Long storedDeparture = LAST_DEPARTURE.get(entity.getPos().asLong());
            for (org.mtr.core.operation.ArrivalResponse arrival
                    : MtrDataCache.arrivalsForPlatform(entity.getPos().asLong(), platformId)) {
                long arrivalLocal = arrival.getArrival() - offset;
                long departureLocal = arrival.getDeparture() - offset;
                if (green) {
                    // Remember the upcoming departure so the window survives
                    // the arrival entry disappearing once the train leaves.
                    if (departureLocal >= now) {
                        LAST_DEPARTURE.put(entity.getPos().asLong(), departureLocal);
                        storedDeparture = departureLocal;
                    }
                } else if (now >= arrivalLocal - onMillis && now <= departureLocal - offMillis) {
                    active = true;
                }
                break; // only the first (current/next) arrival matters
            }
            if (green && storedDeparture != null
                    && now >= storedDeparture - onMillis && now <= storedDeparture + offMillis) {
                active = true; // solid "time to leave"
            }
        }

        // A dispatcher hold overrides the timetable: the schedule says go, the
        // rule says wait, so the lenses stay lit for as long as the hold lasts.
        if (holdMode.followsHoldRules() && platformId != 0
                && com.stationannouncer.client.mtraddon.ClientHoldState.isHeld(platformId)) {
            active = true;
        }

        int lit = green ? 0xFF38E464 : 0xFFFFC03C;
        int dark = green ? 0xFF15321D : 0xFF3A3220;
        int lens = active ? lit : dark;
        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            // Lens face of the box (model x 3..13, y 9..13, z 6..10).
            matrices.translate(0.3125, 13.0 / 16.0, -0.125 - 0.003);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
            for (float cx : LENS_CENTERS) {
                painter.circleBullet(cx, 8, 4.5f, lens, "", false);
                if (active) {
                    painter.circleBullet(cx, 8, 3.0f, green ? 0xFFA0FFC0 : 0xFFFFE49A, "", false);
                }
            }
            matrices.pop();
        }
    }

    /** Centers of the three lenses, in canvas units. */
    private static final float[] LENS_CENTERS = {8, 20, 32};

    /** Configured platform id (stored via the brush picker) or the closest platform; 0 = none. */
    private static long resolvePlatform(StationDecorBlockEntity entity) {
        String stored = entity.getCustomName();
        if (!stored.isEmpty()) {
            try {
                long id = Long.parseLong(stored.trim());
                if (org.mtr.mod.client.MinecraftClientData.getInstance().platformIdMap.containsKey(id)) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // fall through to auto-detection
            }
        }
        return MtrDataCache.closestPlatform(entity.getPos(), 8);
    }

    // -------------------------------------------------------- railing sign

    /** Longest run of adjacent sign segments merged into one panel. */
    private static final int MAX_SIGN_RUN = 8;

    /**
     * A merged sign panel: how many segments it spans (0 = this segment draws
     * nothing), its text, which faces carry a sign and the route bullets on
     * each face.
     *
     * <p>Every segment of a run can be brushed, so the run has to agree on one
     * answer: a face is off when ANY segment says off (switching a face off
     * anywhere switches the whole merged panel), and the bullets come from the
     * first segment that has any — the same "first one that says something
     * wins" rule the custom name follows.</p>
     */
    private record SignPanel(int run, String text, boolean front, boolean back,
                             java.util.List<String> frontRoutes, java.util.List<String> backRoutes) {
        java.util.List<String> routes(boolean isFront) {
            return isFront ? frontRoutes : backRoutes;
        }
    }

    /**
     * Walking the run costs up to eight block-state lookups plus a block
     * entity lookup per segment, and the answer only changes when somebody
     * builds or renames — so it is resolved a couple of times a second
     * instead of every frame.
     */
    private static final java.util.Map<Long, SignPanel> SIGN_PANELS = new java.util.HashMap<>();
    private static final long SIGN_PANEL_TTL_MS = 500;
    private static long signPanelExpiry;

    private static SignPanel signPanel(StationDecorBlockEntity entity, net.minecraft.client.world.ClientWorld world,
                                       net.minecraft.util.math.BlockPos pos, String autoName) {
        long now = System.currentTimeMillis();
        if (now >= signPanelExpiry) {
            SIGN_PANELS.clear();
            signPanelExpiry = now + SIGN_PANEL_TTL_MS;
        }
        SignPanel cached = SIGN_PANELS.get(pos.asLong());
        if (cached != null) {
            return cached;
        }
        // The run is perpendicular to the face the panel looks at.
        Direction frontFace = com.stationannouncer.mtr.RailingSignBlock.frontOf(entity.getCachedState());
        Direction negDir = frontFace.rotateYCounterclockwise();
        Direction posDir = frontFace.rotateYClockwise();

        SignPanel panel;
        // Only the first segment of a contiguous run draws (the merged panel).
        if (world.getBlockState(pos.offset(negDir)).getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock) {
            panel = new SignPanel(0, "", false, false, java.util.List.of(), java.util.List.of());
        } else {
            int run = 1;
            while (run < MAX_SIGN_RUN
                    && world.getBlockState(pos.offset(posDir, run)).getBlock() instanceof com.stationannouncer.mtr.RailingSignBlock) {
                run++;
            }
            // A custom name typed on ANY segment of the run wins over the auto name.
            String custom = entity.getCustomName();
            boolean front = entity.isSignFront();
            boolean back = entity.isSignBack();
            java.util.List<String> frontRoutes = entity.getFrontRoutes();
            java.util.List<String> backRoutes = entity.getBackRoutes();
            for (int i = 1; i < run; i++) {
                if (!(world.getBlockEntity(pos.offset(posDir, i)) instanceof StationDecorBlockEntity other)) {
                    continue;
                }
                if (custom.isEmpty()) {
                    custom = other.getCustomName();
                }
                front &= other.isSignFront();
                back &= other.isSignBack();
                if (frontRoutes.isEmpty()) {
                    frontRoutes = other.getFrontRoutes();
                }
                if (backRoutes.isEmpty()) {
                    backRoutes = other.getBackRoutes();
                }
            }
            panel = new SignPanel(run, !custom.isEmpty() ? custom : (autoName.isEmpty() ? "Subway" : autoName),
                    front, back, frontRoutes, backRoutes);
        }
        SIGN_PANELS.put(pos.asLong(), panel);
        return panel;
    }

    /** Canvas z of the panel's two faces: model x 5..11 either side of the railing's centre plane. */
    private static final float PANEL_FRONT_Z = 0.0f;
    private static final float PANEL_CENTRE_Z = 12.25f;
    private static final float PANEL_BACK_Z = 24.5f;

    /**
     * How far in front of the panel face its inner keyline sits. Both are
     * quads in the SAME debug-quad layer, and the hanging board's z-fighting
     * cost us a lesson here: 0.001 blocks apart is not enough. 0.4 canvas
     * units is ~0.006 blocks.
     */
    private static final float KEYLINE_STANDOFF = 0.4f;

    /** How far the slab's lids clear the rails they meet, in canvas units (~0.005 blocks). */
    private static final float SLAB_OVERSHOOT = 0.3f;

    /**
     * The black station-name panel set into the entrance railing. It is a
     * SOLID slab, not two facing sheets: the two faces sit at ±3/16 from the
     * railing's centre plane (inside the post width, so nothing z-fights) and
     * the space between them is filled, which is what stops the lettering
     * reading as though it were floating in mid-air with the balusters showing
     * through. Adjacent sign segments merge: the run's first block draws one
     * continuous panel across all of them, so long station names get the room
     * they need.
     *
     * <p>Each face is independent — it can be switched off (that side then
     * shows plain railing, and the slab stops at the centre plane) and carries
     * its own route bullets.</p>
     */
    private void paintRailingSign(StationDecorBlockEntity entity, MatrixStack matrices,
                                  VertexConsumerProvider vertexConsumers, String autoName) {
        net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        net.minecraft.util.math.BlockPos pos = entity.getPos();
        SignPanel panel = signPanel(entity, world, pos, autoName);
        if (panel.run() == 0) {
            return; // not the first segment of the run: the first one draws it all
        }
        boolean front = panel.front();
        boolean back = panel.back();
        if (!front && !back) {
            return; // both faces off: a plain railing segment
        }
        int run = panel.run();
        String text = panel.text();

        float panelWidth = 64 * run - 8;           // canvas units (1 px margin each end)
        float halfWidthBlocks = (16 * run - 2) / 32.0f;
        float centerOffset = (run - 1) / 2.0f;     // run center, in blocks from this segment

        for (int side = 0; side < 2; side++) {
            boolean thisSide = side == 0 ? front : back;
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            // Panel: y 6..18 (48 canvas units tall), spanning the whole run.
            matrices.translate((side == 0 ? centerOffset : -centerOffset) + halfWidthBlocks,
                    1.125, -0.1875 - 0.004);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);

            // The slab itself is drawn once, from the first side's frame: a face
            // that is switched off stops the mass at the railing's centre plane.
            if (side == 0) {
                // The slab OVERSHOOTS the panel's 48 units top and bottom: the
                // railing's balusters end at exactly model y 6 and y 18, and a
                // lid flush with them would be coplanar with the rails they meet.
                // Overshooting buries those faces inside the slab instead.
                CanvasPainter.box(vertexConsumers.getBuffer(net.minecraft.client.render.RenderLayer.getDebugQuads()),
                        matrices.peek().getPositionMatrix(),
                        0, -SLAB_OVERSHOOT, front ? PANEL_FRONT_Z : PANEL_CENTRE_Z,
                        panelWidth, 48 + SLAB_OVERSHOOT, back ? PANEL_BACK_Z : PANEL_CENTRE_Z,
                        BOARD_BLACK);
            }
            if (thisSide) {
                painter.quad(1, 1, panelWidth - 1, 47, -KEYLINE_STANDOFF, 0xFF17171A);
                paintRailingFace(painter, text, panel.routes(side == 0), panelWidth);
            }
            matrices.pop();
        }
    }

    /**
     * Bullet geometry on a 48-unit-tall panel, in canvas units.
     *
     * <p>The bullets sit UNDER the station name, left-aligned and small — the
     * real entrance signs read that way ("Borough Hall Station" over a row of
     * small ② ③ discs), not as one big disc beside the name.</p>
     */
    private static final float BULLET_DIAMETER = 13;
    private static final float BULLET_GAP = 3;
    /** Left margin for both the name and the bullet row, so their edges line up. */
    private static final float SIGN_LEFT = 9;
    private static final float SIGN_BOTTOM = 5;
    /** The bullet row never takes more than this share of the panel width. */
    private static final float BULLET_MAX_SHARE = 0.8f;

    /**
     * One face of the sign: the station name, and beneath it the route bullets
     * in a left-aligned row.
     */
    private void paintRailingFace(CanvasPainter painter, String text, java.util.List<String> routes, float panelWidth) {
        int count = routes.size();
        float maxWidth = Math.max(20, panelWidth - 2 * SIGN_LEFT);

        float diameter = BULLET_DIAMETER;
        float gap = BULLET_GAP;
        if (count > 0) {
            float rowWidth = count * diameter + (count - 1) * gap;
            float allowed = maxWidth * BULLET_MAX_SHARE;
            if (rowWidth > allowed) {
                float scale = allowed / rowWidth;
                diameter *= scale;
                gap *= scale;
            }
        }

        // The name gets whatever height the bullet row does not need.
        float bulletBand = count > 0 ? diameter + SIGN_BOTTOM + 2 : 0;
        float nameHeight = 48 - bulletBand;

        float size = 13;
        java.util.List<String> lines;
        if (painter.width(text, size) <= maxWidth) {
            lines = java.util.List.of(text);
        } else {
            size = 9;
            java.util.List<String> wrapped = painter.wrap(text, size, maxWidth);
            if (wrapped.size() == 1) {
                // one very long word: shrink it onto a single line instead
                size = Math.max(5, maxWidth / Math.max(1, painter.width(text, 1)));
                lines = java.util.List.of(text);
            } else {
                lines = java.util.List.of(painter.trimToWidth(wrapped.get(0), size, maxWidth),
                        painter.trimToWidth(wrapped.get(1), size, maxWidth));
            }
        }

        if (lines.size() == 1) {
            painter.text(lines.get(0), SIGN_LEFT, nameHeight / 2 - size / 2.0f, size, TEXT_WHITE);
        } else {
            painter.text(lines.get(0), SIGN_LEFT, nameHeight / 2 - size - 1, size, TEXT_WHITE);
            painter.text(lines.get(1), SIGN_LEFT, nameHeight / 2 + 1, size, TEXT_WHITE);
        }

        float bulletY = 48 - SIGN_BOTTOM - diameter / 2.0f;
        float bulletX = SIGN_LEFT + diameter / 2.0f;
        for (String routeName : routes) {
            RouteBullets.Bullet bullet = RouteBullets.bulletFor(routeName);
            if (RouteBullets.isNoEntry(routeName)) {
                painter.prohibitionBullet(bulletX, bulletY, diameter / 2.0f, bullet.color());
            } else {
                painter.circleBullet(bulletX, bulletY, diameter / 2.0f, bullet.color(), bullet.label(),
                        RouteBullets.needsDarkText(bullet.color()));
            }
            bulletX += diameter + gap;
        }
    }

    /** MTR names can be "English|Other Language" — display the first part. */
    private static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    // ------------------------------------------------------------- mosaic

    /** Longest run of adjacent mosaic blocks merged into one band. */
    private static final int MAX_MOSAIC_RUN = 8;

    /** A merged mosaic band: how many blocks it spans (0 = this block draws nothing) and its text. */
    private record MosaicPanel(int run, String text) {
    }

    /** Same cheap TTL cache as the railing sign — the answer only changes when somebody builds or renames. */
    private static final java.util.Map<Long, MosaicPanel> MOSAIC_PANELS = new java.util.HashMap<>();
    private static long mosaicPanelExpiry;

    /**
     * Resolves the merged band this mosaic belongs to. Neighbours count only
     * when they are mosaics facing the same way, so two back-to-back signs on
     * opposite walls never merge into one another.
     *
     * <p>The band is drawn by the block at the run's start, centred on the
     * run — which is what lets an even-length run centre on a seam (two
     * blocks centre across 4 blocks' worth of wall) instead of always being
     * symmetric about one block's middle.</p>
     */
    private static MosaicPanel mosaicPanel(StationDecorBlockEntity entity, Direction facing, String autoName) {
        net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) {
            return new MosaicPanel(1, autoName);
        }
        net.minecraft.util.math.BlockPos pos = entity.getPos();
        long now = System.currentTimeMillis();
        if (now >= mosaicPanelExpiry) {
            MOSAIC_PANELS.clear();
            mosaicPanelExpiry = now + SIGN_PANEL_TTL_MS;
        }
        MosaicPanel cached = MOSAIC_PANELS.get(pos.asLong());
        if (cached != null) {
            return cached;
        }

        // The band runs along the wall, i.e. perpendicular to the facing:
        // local +X in the rotated render frame is facing.rotateYClockwise().
        Direction posDir = facing.rotateYClockwise();
        Direction negDir = facing.rotateYCounterclockwise();

        MosaicPanel panel;
        if (isMosaicFacing(world, pos.offset(negDir), facing)) {
            panel = new MosaicPanel(0, ""); // a block further back draws the whole band
        } else {
            int run = 1;
            while (run < MAX_MOSAIC_RUN && isMosaicFacing(world, pos.offset(posDir, run), facing)) {
                run++;
            }
            // A custom name typed on ANY block of the run wins over the auto name.
            String custom = entity.getCustomName();
            for (int i = 1; i < run && custom.isEmpty(); i++) {
                if (world.getBlockEntity(pos.offset(posDir, i)) instanceof StationDecorBlockEntity other) {
                    custom = other.getCustomName();
                }
            }
            panel = new MosaicPanel(run, !custom.isEmpty() ? custom : autoName);
        }
        MOSAIC_PANELS.put(pos.asLong(), panel);
        return panel;
    }

    /** True when the block at {@code pos} is a mosaic mounted the same way as its neighbour. */
    private static boolean isMosaicFacing(net.minecraft.client.world.ClientWorld world,
                                          net.minecraft.util.math.BlockPos pos, Direction facing) {
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof StationDecorBlock
                && state.contains(StationDecorBlock.FACING)
                && state.get(StationDecorBlock.FACING) == facing;
    }

    /**
     * The classic mosaic frieze: cream tile field, station-color border tile
     * rows, dark lettering. The band auto-sizes to the name (never narrower
     * than the run it spans) and is centred on the run, so it extends evenly
     * past both ends however many blocks were placed.
     */
    private void paintMosaic(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                             String name, int color, int run) {
        String text = upperCase(name.isEmpty() ? "BAKER CITY" : name);
        // The band paints directly onto the wall behind the block: 0.01 in
        // front of the wall face (local +0.5) so nothing z-fights. The X shift
        // moves the origin to the RUN's centre — half a block per extra block,
        // which is exactly the seam for an even-length run.
        matrices.push();
        matrices.translate((run - 1) / 2.0, 1.0, 0.5 - 0.01);
        matrices.scale(-UNIT, -UNIT, UNIT);
        CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);

        float textSize = 26;
        float textWidth = painter.width(text, textSize);
        // Snap the band to whole 8-unit tiles so the border pattern always
        // fits exactly (no partial tiles, nothing hanging past the edges).
        float bandWidth = (float) (Math.ceil(Math.max(64.0 * run, textWidth + 40) / 8.0) * 8.0);
        float left = -bandWidth / 2.0f;
        float right = bandWidth / 2.0f;

        // Grout backing for the whole frame, then individual border tiles
        // inset by the grout gap — the frame reads as real small tiles.
        painter.quad(left, 0, right, 64, 0.0f, darken(color));
        for (float x = left; x < right - 0.1f; x += 8) {
            painter.quad(x + 0.4f, 0.4f, x + 7.6f, 7.6f, -0.1f, color);       // top row
            painter.quad(x + 0.4f, 56.4f, x + 7.6f, 63.6f, -0.1f, color);     // bottom row
        }
        for (float y = 8; y < 56 - 0.1f; y += 8) {
            painter.quad(left + 0.4f, y + 0.4f, left + 7.6f, y + 7.6f, -0.1f, color);   // left column
            painter.quad(right - 7.6f, y + 0.4f, right - 0.4f, y + 7.6f, -0.1f, color); // right column
        }

        // Cream field with its own grout grid.
        painter.quad(left + 8, 8, right - 8, 56, -0.1f, MOSAIC_FIELD);
        for (float x = left + 8; x <= right - 8 + 0.1f; x += 8) {
            painter.quad(Math.max(x - 0.4f, left + 8), 8, Math.min(x + 0.4f, right - 8), 56, -0.2f, MOSAIC_GROUT);
        }
        for (float y = 16; y <= 48; y += 8) {
            painter.quad(left + 8, y - 0.4f, right - 8, y + 0.4f, -0.2f, MOSAIC_GROUT);
        }

        painter.textCentered(text, 0, 32 - textSize / 2.0f, textSize, MOSAIC_TEXT);
        matrices.pop();
    }

    private static int darken(int argb) {
        int r = (argb >> 16 & 0xFF) * 3 / 4;
        int g = (argb >> 8 & 0xFF) * 3 / 4;
        int b = (argb & 0xFF) * 3 / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------- column

    /**
     * The black station-name board on both flange faces of a named column
     * (the column body itself, including the station-color tint, comes from
     * the block model and color provider).
     */
    private void paintColumnBoard(MatrixStack matrices, VertexConsumerProvider vertexConsumers, String name) {
        if (name.isEmpty()) {
            return;
        }
        // Name board on both wide faces (front flange face at local z -0.25).
        for (int side = 0; side < 2; side++) {
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
            }
            matrices.translate(0.3125, 0.75, -0.25 - 0.01);
            matrices.scale(-UNIT, -UNIT, UNIT);
            CanvasPainter painter = new CanvasPainter(matrices, vertexConsumers);
            painter.quad(0, 0, 40, 16, 0.0f, BOARD_BLACK);
            String text = upperCase(name);
            float size = Math.min(9, 36 / Math.max(1, painter.width(text, 1)));
            painter.textCentered(text, 20, 8 - size / 2.0f, size, TEXT_WHITE);
            matrices.pop();
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(StationDecorBlockEntity blockEntity) {
        return true; // the mosaic band extends past the block
    }
}
