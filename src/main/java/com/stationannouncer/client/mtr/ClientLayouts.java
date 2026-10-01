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
 * last one received: every scanned walk drawn as a ribbon just above the
 * floor — green where it is step-free, amber where it takes stairs, blue for
 * the step-free alternative — plus the exits (green posts) and the street
 * openings the scan found without a marker (amber posts). Drawn only while
 * the player holds a tool that reveals markers, for 10 minutes or until
 * {@code /stationlayout hide}.
 */
public final class ClientLayouts {
    private static final long OVERLAY_MILLIS = 10 * 60 * 1000L;
    private static final double OVERLAY_RANGE = 160;

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
                        link.get("stepFree").getAsBoolean() ? 0xFF3DD68C : 0xFFF0A020, 0.0f);
                if (link.has("stepFreeAlt")) {
                    drawPath(buffer, m, camera, link.getAsJsonObject("stepFreeAlt").getAsJsonArray("path"), 0xFF3D8BFF, 0.12f);
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
                post(buffer, m, x, y, z, kind.equals("opening") ? 0xFFF0A020 : 0xFF1E8E3E);
            }
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

    private static void post(VertexConsumer buffer, Matrix4f m, double x, double y, double z, int color) {
        double r = 0.12;
        double top = y + 2.2;
        quad(buffer, m, x - r, y, z - r, x + r, y, z - r, x + r, top, z - r, x - r, top, z - r, color);
        quad(buffer, m, x - r, y, z + r, x + r, y, z + r, x + r, top, z + r, x - r, top, z + r, color);
        quad(buffer, m, x - r, y, z - r, x - r, y, z + r, x - r, top, z + r, x - r, top, z - r, color);
        quad(buffer, m, x + r, y, z - r, x + r, y, z + r, x + r, top, z + r, x + r, top, z - r, color);
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
