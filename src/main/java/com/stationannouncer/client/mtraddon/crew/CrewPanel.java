package com.stationannouncer.client.mtraddon.crew;

import com.stationannouncer.client.mtraddon.AddonClientConfig;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * The phase-1 crew panel (MANUAL_DRIVING_PLAN.md §3.1/§3.5/§3.8): a small overlay in
 * the TOP-RIGHT corner while the player holds a crew role — role line, the ready
 * lamps of the active interlocks, a hint when an interlock is holding the player
 * back, the last buzz code, and the driver's vigilance state. The full cab HUD
 * replaces it later.
 *
 * <p>The driving HUD owns the top-left corner; if the player moved that HUD to the
 * top right, this panel takes the top left instead. It drops below vanilla's
 * status-effect icons, which also live top-right. Everything is built per frame
 * from {@link CrewState} — a handful of short strings, no lookups.</p>
 */
@Environment(EnvType.CLIENT)
final class CrewPanel {
    private static final int COLOR_BACKGROUND = 0xA0101014;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_FAINT = 0xFFB0B0B8;
    private static final int COLOR_DIM = 0xFF707078;
    private static final int COLOR_GREEN = 0xFF44DD66;
    private static final int COLOR_AMBER = 0xFFFFAA00;
    private static final int COLOR_RED = 0xFFFF5555;
    private static final int COLOR_LAMP_OFF = 0xFF34343C;
    private static final int COLOR_LAMP_RIM = 0xFF000000;

    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int LAMP_SIZE = 6;
    private static final int LAMP_INDENT = 9;
    private static final int MARGIN = 6;
    /** Vanilla's status-effect icon rows are 26 px apart at the top-right. */
    private static final int EFFECT_ROW = 26;
    private static final long BLINK_PERIOD_MILLIS = 250;

    private CrewPanel() {
    }

    /** One panel line; {@code lamp} draws a small square before the text. */
    private record Line(String text, int color, boolean lamp, int lampColor) {
        static Line text(String text, int color) {
            return new Line(text, color, false, 0);
        }

        static Line lamp(String text, boolean lit) {
            return new Line(text, lit ? COLOR_TEXT : COLOR_DIM, true, lit ? COLOR_GREEN : COLOR_LAMP_OFF);
        }
    }

    static void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden || !CrewState.active()
                || !AddonClientConfig.get().crewPanelEnabled || !AddonClientConfig.get().isClassicHud()) {
            // The cab HUD shows crew lamps and hints itself; this panel serves the classic style.
            return;
        }
        List<Line> lines = buildLines(System.currentTimeMillis());
        if (lines.isEmpty()) {
            return;
        }
        TextRenderer font = client.textRenderer;
        int contentWidth = 0;
        for (Line line : lines) {
            contentWidth = Math.max(contentWidth, font.getWidth(line.text) + (line.lamp ? LAMP_INDENT : 0));
        }
        int panelWidth = contentWidth + 2 * PADDING;
        int panelHeight = lines.size() * LINE_HEIGHT + 2 * PADDING - 2;

        AddonClientConfig config = AddonClientConfig.get();
        boolean left = config.hudEnabled && "top_right".equals(config.hudCorner);
        int x = left ? MARGIN : context.getScaledWindowWidth() - MARGIN - panelWidth;
        int y = MARGIN + (left ? 0 : effectRowsOffset(client));

        context.fill(x, y, x + panelWidth, y + panelHeight, COLOR_BACKGROUND);
        int textY = y + PADDING;
        for (Line line : lines) {
            int textX = x + PADDING;
            if (line.lamp) {
                int lampY = textY + (LINE_HEIGHT - 2 - LAMP_SIZE) / 2;
                context.fill(textX, lampY, textX + LAMP_SIZE, lampY + LAMP_SIZE, COLOR_LAMP_RIM);
                context.fill(textX + 1, lampY + 1, textX + LAMP_SIZE - 1, lampY + LAMP_SIZE - 1, line.lampColor);
                textX += LAMP_INDENT;
            }
            context.drawTextWithShadow(font, line.text, textX, textY, line.color);
            textY += LINE_HEIGHT;
        }
    }

    /** Drops the panel under vanilla's top-right status-effect icons (good row, then bad row). */
    private static int effectRowsOffset(MinecraftClient client) {
        boolean good = false;
        boolean bad = false;
        for (StatusEffectInstance effect : client.player.getStatusEffects()) {
            if (!effect.shouldShowIcon()) {
                continue;
            }
            if (effect.getEffectType().isBeneficial()) {
                good = true;
            } else {
                bad = true;
            }
        }
        return bad ? 2 * EFFECT_ROW : good ? EFFECT_ROW : 0;
    }

    private static List<Line> buildLines(long now) {
        List<Line> lines = new ArrayList<>();
        boolean driver = CrewState.myRole == CrewProtocol.ROLE_DRIVER;
        boolean guard = CrewState.myRole == CrewProtocol.ROLE_GUARD;
        boolean bothAboard = CrewState.hasDriver && CrewState.hasGuard;

        // Role.
        String role = driver
                ? tr(CrewState.hasGuard ? "gui.station_announcer.crew.driver" : "gui.station_announcer.crew.driver_opto")
                : tr("gui.station_announcer.crew.guard");
        lines.add(Line.text(role, COLOR_TEXT));

        // Ready lamps: only the ones an active interlock reads, only with both ends crewed.
        boolean driverWaits = CrewState.flag(CrewProtocol.FLAG_DRIVER_WAITS);
        boolean guardWaits = CrewState.flag(CrewProtocol.FLAG_GUARD_WAITS);
        boolean guardReady = CrewState.flag(CrewProtocol.FLAG_GUARD_READY);
        boolean driverReady = CrewState.flag(CrewProtocol.FLAG_DRIVER_READY);
        if (bothAboard) {
            if (driverWaits) {
                lines.add(Line.lamp(tr("gui.station_announcer.crew.guard_ready"), guardReady));
            }
            if (guardWaits) {
                lines.add(Line.lamp(tr("gui.station_announcer.crew.driver_ready"), driverReady));
            }
        }

        // Hint: what an interlock is waiting on at this stop.
        if (bothAboard && CrewState.flag(CrewProtocol.FLAG_DOORS_THIS_STOP)) {
            if (driver && driverWaits && !guardReady) {
                lines.add(Line.text(tr("gui.station_announcer.crew.wait_guard"), COLOR_AMBER));
            } else if (guard && !guardReady) {
                if (guardWaits && !driverReady) {
                    lines.add(Line.text(tr("gui.station_announcer.crew.wait_driver"), COLOR_AMBER));
                } else {
                    String key = CrewState.flag(CrewProtocol.FLAG_DOORS_OPEN)
                            ? "gui.station_announcer.crew.buzz_when_ready"
                            : "gui.station_announcer.crew.buzz_ready_now";
                    lines.add(Line.text(Text.translatable(key, CrewClient.buzzerKeyName()).getString(), COLOR_FAINT));
                }
            }
        }

        // Last buzz code from the other end.
        if (CrewState.buzzCount > 0) {
            String dots = "•".repeat(CrewState.buzzCount);
            String key = CrewState.buzzRole == CrewProtocol.ROLE_DRIVER
                    ? "gui.station_announcer.crew.buzz_from_driver"
                    : "gui.station_announcer.crew.buzz_from_guard";
            lines.add(Line.text(Text.translatable(key, dots).getString(), COLOR_AMBER));
        }

        // Vigilance (driver only).
        if (driver) {
            switch (CrewState.vigilanceState) {
                case CrewProtocol.VIGIL_OK ->
                        lines.add(Line.text(tr("gui.station_announcer.crew.vigilance"), COLOR_DIM));
                case CrewProtocol.VIGIL_WARNING -> {
                    long seconds = (CrewState.vigilanceLeft(now) + 999) / 1000;
                    boolean on = (now / BLINK_PERIOD_MILLIS) % 2 == 0;
                    lines.add(Line.text(Text.translatable("gui.station_announcer.crew.vigilance_warning",
                            seconds).getString(), on ? COLOR_AMBER : COLOR_DIM));
                }
                case CrewProtocol.VIGIL_TRIPPED -> lines.add(Line.text(Text.translatable(
                        "gui.station_announcer.crew.vigilance_tripped", CrewClient.vigilanceKeyName())
                        .getString(), COLOR_RED));
                default -> {
                }
            }
        }
        return lines;
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }
}
