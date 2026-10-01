package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.ExitMarkerBlockEntity;
import com.stationannouncer.mtr.MarkerBlock;
import com.stationannouncer.mtr.PlaceMarkerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;
import org.mtr.mod.client.MinecraftClientData;

/**
 * Draws an exit or place marker — ONLY while the local player holds a tool
 * that reveals markers ({@link MarkerBlock#reveals}); the block itself has no
 * model. A slowly turning diamond on a thin post, a billboarded label (drawn
 * faintly through walls too, so a marker behind a wall can still be found),
 * and for places with an arrival radius a ring on the ground showing it.
 *
 * <p>Colours say state at a glance: exits are green when pinned to an exit
 * that exists, amber when not pinned yet, red when the exit they are pinned
 * to no longer exists in MTR (renamed or deleted in MTR's own dashboard).</p>
 *
 * <p>All geometry is written before any text: drawing text switches render
 * layer and flushes the quad buffer, so a buffer must never be held across a
 * text draw (the stop marker crash).</p>
 */
public class MarkerRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int EXIT_OK = 0xFF1E8E3E;
    private static final int EXIT_UNPINNED = 0xFFC98A00;
    private static final int EXIT_ORPHAN = 0xFFC23B3B;
    private static final int POST = 0xFF2A2A30;
    private static final int RING_SEGMENTS = 64;

    private final TextRenderer font;

    public MarkerRenderer(BlockEntityRendererFactory.Context context) {
        this.font = context.getTextRenderer();
    }

    /** What a marker shows: colour, title line, subtitle line, ground ring radius. */
    private record Look(int color, String title, String subtitle, int radius) {
    }

    @Override
    public void render(T be, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !MarkerBlock.revealedFor(client.player) || be.getWorld() == null) {
            return;
        }
        Look look = be instanceof ExitMarkerBlockEntity exit ? exitLook(exit)
                : be instanceof PlaceMarkerBlockEntity place ? placeLook(place) : null;
        if (look == null) {
            return;
        }
        float time = (be.getWorld().getTime() % 3600L) + tickDelta;

        // ---- geometry (one buffer, released before any text is drawn)
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        // thin post from the ground to the diamond
        box(buffer, matrix, 0.47f, 0.0f, 0.47f, 0.53f, 0.46f, 0.53f, POST);
        // foot plate so the exact block reads when looking down
        box(buffer, matrix, 0.3f, 0.0f, 0.3f, 0.7f, 0.03f, 0.7f, shade(look.color, 0.8f));
        diamond(buffer, matrices, look.color, time);
        if (look.radius > 0) {
            ring(buffer, matrix, look.radius, look.color);
        }

        // ---- label
        matrices.push();
        matrices.translate(0.5, 1.2, 0.5);
        matrices.multiply(client.gameRenderer.getCamera().getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f labelMatrix = matrices.peek().getPositionMatrix();
        int background = 0xB0000000 | (look.color & 0xFFFFFF);
        drawCentered(look.title, 0, labelMatrix, consumers, background, 0xFFFFFFFF);
        if (!look.subtitle.isEmpty()) {
            drawCentered(look.subtitle, 11, labelMatrix, consumers, 0x90000000, 0xFFE0E0E6);
        }
        matrices.pop();
    }

    private void drawCentered(String text, float y, Matrix4f matrix, VertexConsumerProvider consumers,
                              int background, int color) {
        float x = -font.getWidth(text) / 2.0f;
        // Faint copy through walls first (so a hidden marker can be found), then the real one.
        font.draw(text, x, y, 0x40FFFFFF, false, matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
                background & 0x40FFFFFF, FULL_BRIGHT);
        font.draw(text, x, y, color, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL,
                background, FULL_BRIGHT);
    }

    // ------------------------------------------------------------------ looks

    private static Look exitLook(ExitMarkerBlockEntity marker) {
        String exit = marker.getExitName();
        Station station = station(marker.getStationId());
        String stationName = station == null ? "" : firstLang(station.getName());
        if (marker.getStationId() == 0 || exit.isEmpty()) {
            String hint = Text.translatable("gui.station_announcer.exit_marker.unpinned_hint").getString();
            return new Look(EXIT_UNPINNED, Text.translatable("gui.station_announcer.exit_marker.world_unpinned").getString(),
                    stationName.isEmpty() ? hint : stationName + " · " + hint, 0);
        }
        String title = Text.translatable("gui.station_announcer.exit_marker.world_title", exit).getString();
        if (station != null && !hasExit(station, exit)) {
            return new Look(EXIT_ORPHAN, title,
                    Text.translatable("gui.station_announcer.exit_marker.world_orphan", stationName).getString(), 0);
        }
        String destinations = "";
        if (station != null) {
            for (StationExit stationExit : station.getExits()) {
                if (exit.equals(stationExit.getName()) && !stationExit.getDestinations().isEmpty()) {
                    destinations = firstLang(stationExit.getDestinations().get(0));
                    break;
                }
            }
        }
        String subtitle = stationName.isEmpty() ? destinations
                : destinations.isEmpty() ? stationName : stationName + " · " + destinations;
        return new Look(EXIT_OK, title, subtitle, 0);
    }

    private static Look placeLook(PlaceMarkerBlockEntity marker) {
        int color = 0xFF000000 | marker.getCategory().color();
        String name = marker.getName().isEmpty()
                ? Text.translatable("gui.station_announcer.place_marker.unnamed").getString() : marker.getName();
        String subtitle = Text.translatable(marker.getCategory().translationKey()).getString();
        if (marker.isHidden()) {
            subtitle += " · " + Text.translatable("gui.station_announcer.place_marker.hidden_short").getString();
        }
        return new Look(color, name, subtitle, marker.getRadius());
    }

    private static Station station(long id) {
        if (id == 0) {
            return null;
        }
        try {
            Station station = MinecraftClientData.getInstance().stationIdMap.get(id);
            return station != null ? station : MinecraftClientData.getDashboardInstance().stationIdMap.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasExit(Station station, String name) {
        for (StationExit exit : station.getExits()) {
            if (name.equals(exit.getName())) {
                return true;
            }
        }
        return false;
    }

    static String firstLang(String name) {
        if (name == null) {
            return "";
        }
        int bar = name.indexOf('|');
        return (bar < 0 ? name : name.substring(0, bar)).trim();
    }

    // --------------------------------------------------------------- geometry

    /** A spinning, bobbing octahedron at the top of the post. */
    private static void diamond(VertexConsumer buffer, MatrixStack matrices, int color, float time) {
        matrices.push();
        matrices.translate(0.5, 0.72 + 0.04 * MathHelper.sin(time * 0.08f), 0.5);
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(time * 2.5f));
        Matrix4f m = matrices.peek().getPositionMatrix();
        float r = 0.24f;
        float h = 0.28f;
        float[][] ring = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
        for (int i = 0; i < 4; i++) {
            float[] a = ring[i];
            float[] b = ring[(i + 1) % 4];
            int upper = shade(color, i % 2 == 0 ? 1.0f : 0.86f);
            int lower = shade(color, i % 2 == 0 ? 0.62f : 0.52f);
            tri(buffer, m, a[0], 0, a[1], b[0], 0, b[1], 0, h, 0, upper);
            tri(buffer, m, b[0], 0, b[1], a[0], 0, a[1], 0, -h, 0, lower);
        }
        matrices.pop();
    }

    /** The arrival radius as a thin ring just above the ground. */
    private static void ring(VertexConsumer buffer, Matrix4f m, int radius, int color) {
        float y = 0.04f;
        float inner = radius - 0.06f;
        float outer = radius + 0.06f;
        int argb = (color & 0x00FFFFFF) | 0xC0000000;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double a0 = Math.PI * 2 * i / RING_SEGMENTS;
            double a1 = Math.PI * 2 * (i + 1) / RING_SEGMENTS;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(buffer, m,
                    0.5f + inner * c0, y, 0.5f + inner * s0,
                    0.5f + outer * c0, y, 0.5f + outer * s0,
                    0.5f + outer * c1, y, 0.5f + outer * s1,
                    0.5f + inner * c1, y, 0.5f + inner * s1, argb);
        }
    }

    private static void box(VertexConsumer buffer, Matrix4f m, float x0, float y0, float z0,
                            float x1, float y1, float z1, int color) {
        int side = shade(color, 0.8f);
        int end = shade(color, 0.9f);
        quad(buffer, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, color);
        quad(buffer, m, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, shade(color, 0.5f));
        quad(buffer, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, side);
        quad(buffer, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, side);
        quad(buffer, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, end);
        quad(buffer, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, end);
    }

    private static void tri(VertexConsumer buffer, Matrix4f m, float ax, float ay, float az,
                            float bx, float by, float bz, float cx, float cy, float cz, int color) {
        quad(buffer, m, ax, ay, az, bx, by, bz, cx, cy, cz, cx, cy, cz, color);
    }

    /** Both windings, so a face shows from whichever side the camera is on. */
    private static void quad(VertexConsumer buffer, Matrix4f m,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4, int argb) {
        vertex(buffer, m, x1, y1, z1, argb);
        vertex(buffer, m, x2, y2, z2, argb);
        vertex(buffer, m, x3, y3, z3, argb);
        vertex(buffer, m, x4, y4, z4, argb);
        vertex(buffer, m, x4, y4, z4, argb);
        vertex(buffer, m, x3, y3, z3, argb);
        vertex(buffer, m, x2, y2, z2, argb);
        vertex(buffer, m, x1, y1, z1, argb);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f m, float x, float y, float z, int argb) {
        buffer.vertex(m, x, y, z)
                .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                .next();
    }

    private static int shade(int argb, float factor) {
        int r = Math.round(((argb >> 16) & 0xFF) * factor);
        int g = Math.round(((argb >> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // --------------------------------------------------------------- culling

    @Override
    public boolean rendersOutsideBoundingBox(T be) {
        return true; // the ring can be far larger than the block
    }

    @Override
    public int getRenderDistance() {
        return 128;
    }
}
