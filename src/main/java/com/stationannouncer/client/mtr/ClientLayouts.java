package com.stationannouncer.client.mtr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.wayfinding.layout.LayoutScanner;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * The client's copy of the station layouts it has asked about (Exit Marker
 * Layout tab, {@code /stationlayout show}), and the in-world overlay of the
 * last one received. Drawn only while the player holds a tool that reveals
 * markers, for 10 minutes or until {@code /stationlayout hide}, with a colour
 * key on the left of the screen ({@link #LEGEND} — the Layout tab shows the same):
 * <ul>
 *   <li>walks as ribbons just above the floor: green step-free, amber with stairs or
 *       an escalator, blue (a little higher) the step-free way round;</li>
 *   <li>posts: white Exit Marker, grey street entrance the scan found, purple fare
 *       control; a cap on top says what the anchor reaches without steps (green every
 *       platform, yellow some, red none) and a blue band under it means by lift;</li>
 *   <li>outlines on the floor you stand on: white the MTR station area, grey the box
 *       the scan looked at.</li>
 * </ul>
 */
public final class ClientLayouts {
    private static final long OVERLAY_MILLIS = 10 * 60 * 1000L;
    private static final double OVERLAY_RANGE = 160;

    static final int C_STEP_FREE = 0xFF3DD68C;
    static final int C_STAIRS = 0xFFF0A020;
    static final int C_ALT = 0xFF3D8BFF;
    static final int C_EXIT = 0xFFF2F2F2;
    static final int C_OPENING = 0xFF9AA0A6;
    static final int C_FARE = 0xFFA05CFF;
    static final int C_ALL = 0xFF3DD68C;
    static final int C_SOME = 0xFFF2D43D;
    static final int C_NONE = 0xFFE5484D;
    static final int C_AREA = 0xFFFFFFFF;
    static final int C_SCANNED = 0xFF8A8F98;

    /** The colour key: swatch colour, shape (0 ribbon, 1 post, 2 cap, 3 outline, 4 band), translation key. */
    static final Object[][] LEGEND = {
            {C_STEP_FREE, 0, "gui.station_announcer.layout.legend.path_step_free"},
            {C_STAIRS, 0, "gui.station_announcer.layout.legend.path_stairs"},
            {C_ALT, 0, "gui.station_announcer.layout.legend.path_alt"},
            {C_EXIT, 1, "gui.station_announcer.layout.legend.post_exit"},
            {C_OPENING, 1, "gui.station_announcer.layout.legend.post_opening"},
            {C_FARE, 1, "gui.station_announcer.layout.legend.post_fare"},
            {C_ALL, 2, "gui.station_announcer.layout.legend.cap_all"},
            {C_SOME, 2, "gui.station_announcer.layout.legend.cap_some"},
            {C_NONE, 2, "gui.station_announcer.layout.legend.cap_none"},
            {C_ALT, 4, "gui.station_announcer.layout.legend.cap_lift"},
            {C_AREA, 3, "gui.station_announcer.layout.legend.area"},
            {C_SCANNED, 3, "gui.station_announcer.layout.legend.scanned"},
    };

    /** One station's state as last heard from the server. */
    public record Entry(byte state, String message, @Nullable JsonObject layout, long receivedAt) {
    }

    private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile long overlayStation;
    private static volatile long overlayUntil;

    private ClientLayouts() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LayoutScanner.DATA_S2C, (client, handler, buf, sender) -> {
            long stationId = buf.readLong();
            byte state = buf.readByte();
            String message = buf.readString(512);
            byte[] data = buf.readByteArray();
            JsonObject layout = data.length == 0 ? null : unzip(data);
            client.execute(() -> receive(stationId, state, message, layout));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ENTRIES.clear();
            overlayStation = 0;
        });
        WorldRenderEvents.AFTER_ENTITIES.register(ClientLayouts::render);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(ClientLayouts::renderLegend);
    }

    private static void receive(long stationId, byte state, String message, @Nullable JsonObject layout) {
        if (state == LayoutScanner.STATE_HIDE) {
            overlayStation = 0;
            return;
        }
        Entry previous = ENTRIES.get(stationId);
        JsonObject kept = layout != null ? layout : previous == null ? null : previous.layout();
        ENTRIES.put(stationId, new Entry(state, message, kept, System.currentTimeMillis()));
        if (state == LayoutScanner.STATE_DONE && layout != null) {
            overlayStation = stationId;
            overlayUntil = System.currentTimeMillis() + OVERLAY_MILLIS;
        }
    }

    @Nullable
    public static Entry entry(long stationId) {
        return ENTRIES.get(stationId);
    }

    public static boolean overlayShown(long stationId) {
        return overlayStation == stationId && System.currentTimeMillis() < overlayUntil;
    }

    public static void hideOverlay() {
        overlayStation = 0;
    }

    public static void showOverlay(long stationId) {
        overlayStation = stationId;
        overlayUntil = System.currentTimeMillis() + OVERLAY_MILLIS;
    }

    /** Ask the server: 0 = status/data, 1 = scan this station, 2 = scan every station. */
    public static void request(int action, long stationId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(action);
        buf.writeLong(stationId);
        ClientPlayNetworking.send(LayoutScanner.REQUEST_C2S, buf);
    }

    @Nullable
    private static JsonObject unzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- overlay

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        long station = overlayStation;
        if (station == 0 || client.player == null || System.currentTimeMillis() > overlayUntil
                || !com.stationannouncer.mtr.MarkerBlock.revealedFor(client.player)) {
            return;
        }
        Entry entry = ENTRIES.get(station);
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (entry == null || entry.layout() == null || matrices == null || consumers == null) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        JsonArray links = entry.layout().getAsJsonArray("links");
        if (links != null) {
            for (JsonElement element : links) {
                JsonObject link = element.getAsJsonObject();
                drawPath(buffer, m, camera, link.getAsJsonArray("path"),
                        link.get("stepFree").getAsBoolean() ? C_STEP_FREE : C_STAIRS, 0.0f);
                if (link.has("stepFreeAlt")) {
                    drawPath(buffer, m, camera, link.getAsJsonObject("stepFreeAlt").getAsJsonArray("path"), C_ALT, 0.12f);
                }
            }
        }
        JsonArray anchors = entry.layout().getAsJsonArray("anchors");
        if (anchors != null) {
            for (JsonElement element : anchors) {
                JsonObject anchor = element.getAsJsonObject();
                String kind = anchor.get("kind").getAsString();
                if (kind.equals("platform")) {
                    continue;
                }
                JsonArray pos = anchor.getAsJsonArray("pos");
                double x = pos.get(0).getAsDouble();
                double y = pos.get(1).getAsDouble();
                double z = pos.get(2).getAsDouble();
                if (Math.abs(x - camera.x) > OVERLAY_RANGE || Math.abs(z - camera.z) > OVERLAY_RANGE) {
                    continue;
                }
                int color = kind.equals("opening") ? C_OPENING : kind.equals("fare") ? C_FARE : C_EXIT;
                box(buffer, m, x, y, z, 0.12, 0, 2.2, color);
                String access = anchor.has("stepFree") ? anchor.get("stepFree").getAsString() : null;
                if (access != null) {
                    box(buffer, m, x, y, z, 0.2, 2.2, 2.6, access.equals("all") ? C_ALL : access.equals("some") ? C_SOME : C_NONE);
                    if (anchor.has("lift") && anchor.get("lift").getAsBoolean()) {
                        box(buffer, m, x, y, z, 0.17, 1.9, 2.15, C_ALT);
                    }
                }
            }
        }
        // the MTR station area and the scanned box, on the floor the player stands on — or,
        // seen from above, just over street level (the box's top minus the scan's head room)
        double feet = client.player.getY() + 0.04;
        JsonObject region = entry.layout().getAsJsonObject("region");
        if (region != null) {
            double bottom = region.getAsJsonArray("min").get(1).getAsDouble();
            double top = region.getAsJsonArray("max").get(1).getAsDouble() - 7.96;
            feet = Math.max(bottom, Math.min(feet, Math.max(bottom, top)));
        }
        JsonArray area = entry.layout().getAsJsonArray("area");
        if (area != null && area.size() == 4) {
            outline(buffer, m, area.get(0).getAsDouble(), area.get(1).getAsDouble(),
                    area.get(2).getAsDouble() + 1, area.get(3).getAsDouble() + 1, feet + 0.01, C_AREA);
        }
        if (region != null) {
            JsonArray min = region.getAsJsonArray("min");
            JsonArray max = region.getAsJsonArray("max");
            outline(buffer, m, min.get(0).getAsDouble(), min.get(2).getAsDouble(),
                    max.get(0).getAsDouble() + 1, max.get(2).getAsDouble() + 1, feet, C_SCANNED);
        }
        matrices.pop();
    }

    private static void drawPath(VertexConsumer buffer, Matrix4f m, Vec3d camera, @Nullable JsonArray path, int color, float lift) {
        if (path == null || path.size() < 2) {
            return;
        }
        double[] prev = null;
        for (JsonElement element : path) {
            JsonArray p = element.getAsJsonArray();
            double[] point = {p.get(0).getAsDouble(), p.get(1).getAsDouble() + 0.06 + lift, p.get(2).getAsDouble()};
            if (prev != null && Math.abs(point[0] - camera.x) < OVERLAY_RANGE && Math.abs(point[2] - camera.z) < OVERLAY_RANGE) {
                ribbon(buffer, m, prev, point, color);
            }
            prev = point;
        }
    }

    /** A flat ribbon 0.16 wide along a segment (both windings, readable from above and below). */
    private static void ribbon(VertexConsumer buffer, Matrix4f m, double[] a, double[] b, int color) {
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double len = Math.hypot(dx, dz);
        double nx = len < 1e-6 ? 0.08 : -dz / len * 0.08;
        double nz = len < 1e-6 ? 0 : dx / len * 0.08;
        quad(buffer, m, a[0] + nx, a[1], a[2] + nz, b[0] + nx, b[1], b[2] + nz,
                b[0] - nx, b[1], b[2] - nz, a[0] - nx, a[1], a[2] - nz, color);
    }

    /** An upright square column {@code r} from the centre, from y+{@code from} to y+{@code to}, sides only. */
    private static void box(VertexConsumer buffer, Matrix4f m, double x, double y, double z, double r, double from,
                            double to, int color) {
        double y0 = y + from;
        double y1 = y + to;
        quad(buffer, m, x - r, y0, z - r, x + r, y0, z - r, x + r, y1, z - r, x - r, y1, z - r, color);
        quad(buffer, m, x - r, y0, z + r, x + r, y0, z + r, x + r, y1, z + r, x - r, y1, z + r, color);
        quad(buffer, m, x - r, y0, z - r, x - r, y0, z + r, x - r, y1, z + r, x - r, y1, z - r, color);
        quad(buffer, m, x + r, y0, z - r, x + r, y0, z + r, x + r, y1, z + r, x + r, y1, z - r, color);
        quad(buffer, m, x - r, y1, z - r, x + r, y1, z - r, x + r, y1, z + r, x - r, y1, z + r, color);
    }

    /** A flat rectangle outline at height {@code y} (block edges x0..x1, z0..z1). */
    private static void outline(VertexConsumer buffer, Matrix4f m, double x0, double z0, double x1, double z1, double y,
                                int color) {
        ribbon(buffer, m, new double[]{x0, y, z0}, new double[]{x1, y, z0}, color);
        ribbon(buffer, m, new double[]{x1, y, z0}, new double[]{x1, y, z1}, color);
        ribbon(buffer, m, new double[]{x1, y, z1}, new double[]{x0, y, z1}, color);
        ribbon(buffer, m, new double[]{x0, y, z1}, new double[]{x0, y, z0}, color);
    }

    // -------------------------------------------------------------- legend

    /** The colour key, on the left of the screen, while the overlay is drawn. */
    private static void renderLegend(net.minecraft.client.gui.DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        long station = overlayStation;
        if (station == 0 || client.player == null || client.options.hudHidden || client.currentScreen != null
                || System.currentTimeMillis() > overlayUntil
                || !com.stationannouncer.mtr.MarkerBlock.revealedFor(client.player)) {
            return;
        }
        Entry entry = ENTRIES.get(station);
        if (entry == null || entry.layout() == null) {
            return;
        }
        var font = client.textRenderer;
        String title = net.minecraft.text.Text.translatable("gui.station_announcer.layout.legend.title",
                entry.layout().has("name") ? entry.layout().get("name").getAsString() : "").getString();
        float scale = 0.75f;
        int lineH = 10;
        int width = font.getWidth(title);
        for (Object[] row : LEGEND) {
            width = Math.max(width, 14 + font.getWidth(net.minecraft.text.Text.translatable((String) row[2]).getString()));
        }
        int height = 14 + LEGEND.length * lineH;
        int screenH = context.getScaledWindowHeight();
        context.getMatrices().push();
        context.getMatrices().translate(4, Math.max(4, screenH / 2f - height * scale / 2f), 0);
        context.getMatrices().scale(scale, scale, 1);
        context.fill(-3, -3, width + 5, height + 1, 0xA0101114);
        context.drawText(font, title, 0, 0, 0xFFFFFFFF, false);
        int y = 14;
        for (Object[] row : LEGEND) {
            drawSwatch(context, 0, y, (int) row[0], (int) row[1]);
            context.drawText(font, net.minecraft.text.Text.translatable((String) row[2]).getString(), 14, y, 0xFFD0D3D8, false);
            y += lineH;
        }
        context.getMatrices().pop();
    }

    /** One legend swatch, 10 wide, at the text's top-left. Shapes: 0 ribbon, 1 post, 2 cap, 3 outline, 4 band. */
    static void drawSwatch(net.minecraft.client.gui.DrawContext c, int x, int y, int color, int shape) {
        switch (shape) {
            case 0 -> c.fill(x, y + 3, x + 10, y + 6, color);
            case 1 -> c.fill(x + 4, y, x + 6, y + 8, color);
            case 2 -> {
                c.fill(x + 4, y + 3, x + 6, y + 8, C_EXIT);
                c.fill(x + 3, y, x + 7, y + 3, color);
            }
            case 4 -> {
                c.fill(x + 4, y + 5, x + 6, y + 8, C_EXIT);
                c.fill(x + 3, y + 3, x + 7, y + 5, color);
                c.fill(x + 3, y, x + 7, y + 3, C_ALL);
            }
            default -> {
                c.fill(x, y + 1, x + 10, y + 2, color);
                c.fill(x, y + 6, x + 10, y + 7, color);
                c.fill(x, y + 1, x + 1, y + 7, color);
                c.fill(x + 9, y + 1, x + 10, y + 7, color);
            }
        }
    }

    private static void quad(VertexConsumer buffer, Matrix4f m, double x1, double y1, double z1, double x2, double y2,
                             double z2, double x3, double y3, double z3, double x4, double y4, double z4, int argb) {
        vertex(buffer, m, x1, y1, z1, argb);
        vertex(buffer, m, x2, y2, z2, argb);
        vertex(buffer, m, x3, y3, z3, argb);
        vertex(buffer, m, x4, y4, z4, argb);
        vertex(buffer, m, x4, y4, z4, argb);
        vertex(buffer, m, x3, y3, z3, argb);
        vertex(buffer, m, x2, y2, z2, argb);
        vertex(buffer, m, x1, y1, z1, argb);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f m, double x, double y, double z, int argb) {
        buffer.vertex(m, (float) x, (float) y, (float) z)
                .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                .next();
    }
}
