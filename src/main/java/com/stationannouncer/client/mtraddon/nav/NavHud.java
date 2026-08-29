package com.stationannouncer.client.mtraddon.nav;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.math.MathHelper;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;

/**
 * The journey card. Style-matched to {@code DrivingHud}: same background,
 * text and accent colours, the same 4 px padding / 10 px line height / 6 px
 * bullet, and the same 2 Hz blink for a loud state.
 *
 * <p>Every value it draws comes from {@link ClientNav}'s 4 Hz snapshot; the
 * per-frame work is a few {@code getWidth} calls, the fills, and the blink
 * phase (a colour choice, not a recomputation).</p>
 *
 * <p><b>Cooperating with the driving HUD.</b> {@code DrivingHud} owns whatever
 * corner {@code AddonClientConfig.hudCorner} names, and only while the player
 * is driving a vehicle with a valid driver key. When this card is configured
 * for that same corner and that condition holds, it steps out of the way by
 * the driving panel's WORST-CASE height, derived from the very same config
 * flags that decide which sections that panel builds (see
 * {@link #drivingHudReserve}). Reserving the maximum rather than the live
 * height keeps the two panels independent — nothing here reads
 * {@code DrivingHud}'s private snapshot — at the cost of a little empty space
 * when its panel happens to be short.</p>
 */
@Environment(EnvType.CLIENT)
public final class NavHud {
    // Shared with ClientNav so the instruction can pick its own accent.
    static final int COLOR_BACKGROUND = 0xA0101014;
    static final int COLOR_TEXT = 0xFFFFFFFF;
    static final int COLOR_FAINT = 0xFFB0B0B8;
    static final int COLOR_GREEN = 0xFF44DD66;
    static final int COLOR_AMBER = 0xFFFFAA00;
    static final int COLOR_RED = 0xFFFF5555;

    /** Bullet colour for a route the client has no data for. */
    static final int COLOR_FALLBACK_BULLET = 0xFF6E6E78;

    private static final int PANEL_PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int GAP_HEIGHT = 3;
    private static final int BULLET_SIZE = 6;
    private static final int BULLET_INDENT = 9;
    private static final int MIN_PANEL_WIDTH = 90;

    /** Alert blink: 250 ms on, 250 ms off (2 Hz) — DrivingHud's constant. */
    private static final long BLINK_PERIOD_MILLIS = 250;

    // Progress pips.
    private static final int PIP_WIDTH = 4;
    private static final int PIP_HEIGHT = 3;
    private static final int PIP_SPACING = 2;
    private static final int PIP_ROW_HEIGHT = 6;
    private static final int MAX_PIPS = 24;

    /** Gap left between the driving panel and this one when they share a corner. */
    private static final int PANEL_SEPARATION = 4;

    private NavHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(NavHud::render);
    }

    private static void render(DrawContext context, float tickDelta) {
        AddonClientConfig config = AddonClientConfig.get();
        if (!config.navHudEnabled) {
            return;
        }
        ClientNav.Snapshot snapshot = ClientNav.snapshot();
        if (snapshot == null || !snapshot.active()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden
                || client.inGameHud.getDebugHud().shouldShowDebugHud()
                || (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen))) {
            return;
        }
        TextRenderer textRenderer = client.textRenderer;

        boolean hasBullet = snapshot.bulletColor() != 0;
        int titleIndent = hasBullet ? BULLET_INDENT : 0;
        boolean hasPips = snapshot.stopsTotal() > 0;

        int panelWidth = Math.max(MIN_PANEL_WIDTH, titleIndent + textRenderer.getWidth(snapshot.title()));
        if (!snapshot.detail().isEmpty()) {
            panelWidth = Math.max(panelWidth, textRenderer.getWidth(snapshot.detail()));
        }
        int pips = hasPips ? Math.min(snapshot.stopsTotal(), MAX_PIPS) : 0;
        if (pips > 0) {
            panelWidth = Math.max(panelWidth, pips * (PIP_WIDTH + PIP_SPACING) - PIP_SPACING);
        }
        panelWidth += 2 * PANEL_PADDING;

        int panelHeight = 2 * PANEL_PADDING + LINE_HEIGHT;
        if (!snapshot.detail().isEmpty()) {
            panelHeight += LINE_HEIGHT;
        }
        if (pips > 0) {
            panelHeight += GAP_HEIGHT + PIP_ROW_HEIGHT;
        }

        String corner = corner(config);
        boolean rightSide = "top_right".equals(corner) || "bottom_right".equals(corner);
        boolean bottomSide = "bottom_left".equals(corner) || "bottom_right".equals(corner);
        int margin = MathHelper.clamp(config.hudMargin, 0, 64);
        int reserve = corner.equals(config.hudCorner) ? drivingHudReserve(config) : 0;

        int x = rightSide ? context.getScaledWindowWidth() - panelWidth - margin : margin;
        int y = bottomSide
                ? context.getScaledWindowHeight() - panelHeight - margin - reserve
                : margin + reserve;

        context.fill(x, y, x + panelWidth, y + panelHeight, COLOR_BACKGROUND);

        // Only the loudest state (red) blinks — amber just recolours, so a
        // "departs in 40 s" card stays readable.
        boolean blinkOn = (System.currentTimeMillis() / BLINK_PERIOD_MILLIS) % 2 == 0
                || !snapshot.alert() || snapshot.alertColor() != COLOR_RED;
        int titleColor = snapshot.alert() ? snapshot.alertColor() : COLOR_TEXT;

        int textY = y + PANEL_PADDING;
        int textX = x + PANEL_PADDING;
        if (hasBullet) {
            int bulletY = textY + (LINE_HEIGHT - 2 - BULLET_SIZE) / 2;
            context.fill(textX, bulletY, textX + BULLET_SIZE, bulletY + BULLET_SIZE, snapshot.bulletColor());
        }
        if (blinkOn) {
            context.drawTextWithShadow(textRenderer, snapshot.title(), textX + titleIndent, textY, titleColor);
        }
        textY += LINE_HEIGHT;

        if (!snapshot.detail().isEmpty()) {
            int detailColor = snapshot.alert() ? snapshot.alertColor() : COLOR_FAINT;
            context.drawTextWithShadow(textRenderer, snapshot.detail(), textX, textY, detailColor);
            textY += LINE_HEIGHT;
        }

        if (pips > 0) {
            textY += GAP_HEIGHT;
            int done = MathHelper.clamp(snapshot.stopsDone(), 0, pips);
            for (int i = 0; i < pips; i++) {
                int pipX = textX + i * (PIP_WIDTH + PIP_SPACING);
                int color = i < done ? COLOR_GREEN : (i == done ? COLOR_AMBER : COLOR_FAINT);
                context.fill(pipX, textY, pipX + PIP_WIDTH, textY + PIP_HEIGHT, color);
            }
        }
    }

    /** The configured corner, falling back to the top right when the value is junk. */
    private static String corner(AddonClientConfig config) {
        String corner = config.navHudCorner;
        return "top_left".equals(corner) || "bottom_left".equals(corner) || "bottom_right".equals(corner)
                ? corner
                : "top_right";
    }

    /**
     * Vertical space to leave for the driving HUD when both panels want the
     * same corner: zero unless that HUD is enabled AND the player is actually
     * driving (the exact condition {@code DrivingHud} draws under — riding a
     * vehicle while holding a valid driver key for its depot), otherwise the
     * worst-case panel height its enabled sections can produce.
     */
    private static int drivingHudReserve(AddonClientConfig config) {
        if (!config.hudEnabled || !isDrivingCached()) {
            return 0;
        }
        int rows = 1;  // the doors-obstructed alert row
        int gaps = 1;
        if (config.hudShowNextStop) {
            rows += 2;
            gaps++;
        }
        if (config.hudShowOnTime) {
            rows += 1;
            gaps++;
        }
        if (config.hudShowDoors) {
            rows += 1;
            gaps++;
        }
        if (config.hudShowSpeedLimits) {
            rows += 3;
            gaps++;
        }
        if (config.hudShowSignals) {
            rows += 4; // three signal entries plus the obstruction cue
            gaps++;
        }
        return 2 * PANEL_PADDING + rows * LINE_HEIGHT + gaps * GAP_HEIGHT + PANEL_SEPARATION;
    }

    /** How long the driving check is reused; the same 4 Hz budget the snapshot keeps. */
    private static final long DRIVING_TTL_MILLIS = 250;

    private static boolean drivingCached;
    private static long drivingCheckedAt;

    /** The driving check, kept off the per-frame path (it walks MTR's vehicle set). */
    private static boolean isDrivingCached() {
        long now = System.currentTimeMillis();
        if (now - drivingCheckedAt >= DRIVING_TTL_MILLIS) {
            drivingCheckedAt = now;
            drivingCached = isDriving();
        }
        return drivingCached;
    }

    /** {@code DrivingGuiRenderer}'s visibility condition, as DrivingHud uses it. */
    private static boolean isDriving() {
        try {
            for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
                if (VehicleRidingMovement.isRiding(vehicle.getId())
                        && VehicleRidingMovement.getValidHoldingKey(vehicle.vehicleExtraData.getDepotId()) != null) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
