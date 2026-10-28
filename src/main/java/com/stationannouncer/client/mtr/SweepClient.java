package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.ItemSweepWand;
import com.stationannouncer.mtr.Sweep;
import com.stationannouncer.mtr.SweepTemplate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the Structure Sweeper: the template list and template
 * cells fetched from the server (for the screen's preview), the wand's save
 * screen, and the wand's selection drawn in the world while it is held
 * (box in white, the rail cell in cyan, an arrow for "forward").
 */
@Environment(EnvType.CLIENT)
public final class SweepClient {
    public record Entry(String name, String author, int length, int width, int height, int blocks, long created) {
    }

    private static List<Entry> list = List.of();
    private static int listVersion;
    private static final Map<String, SweepTemplate> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SweepTemplate> eldest) {
            return size() > 8;
        }
    };
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> UNAVAILABLE = new HashSet<>();
    private static int templateVersion;

    private SweepClient() {
    }

    public static List<Entry> list() {
        return list;
    }

    public static int listVersion() {
        return listVersion;
    }

    public static int templateVersion() {
        return templateVersion;
    }

    public static void requestList() {
        ClientPlayNetworking.send(Sweep.LIST_REQUEST_C2S, PacketByteBufs.create());
    }

    /** The template's cells if already fetched; otherwise asks the server and returns null. */
    public static SweepTemplate template(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        SweepTemplate t = CACHE.get(name);
        if (t == null && !PENDING.contains(name) && !UNAVAILABLE.contains(name)) {
            PENDING.add(name);
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(name);
            ClientPlayNetworking.send(Sweep.TEMPLATE_REQUEST_C2S, buf);
        }
        return t;
    }

    /** True when the server would not send this template (too large for the preview). */
    public static boolean unavailable(String name) {
        return UNAVAILABLE.contains(name);
    }

    public static void forget(String name) {
        CACHE.remove(name);
        PENDING.remove(name);
        UNAVAILABLE.remove(name);
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(Sweep.LIST_S2C, (client, handler, buf, sender) -> {
            int n = buf.readVarInt();
            List<Entry> entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                entries.add(new Entry(buf.readString(), buf.readString(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readLong()));
            }
            client.execute(() -> {
                list = List.copyOf(entries);
                listVersion++;
                // a re-saved template may have changed: fetch it again when next needed
                Set<String> names = new HashSet<>();
                for (Entry e : entries) {
                    names.add(e.name());
                }
                CACHE.keySet().removeIf(k -> !names.contains(k));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Sweep.TEMPLATE_S2C, (client, handler, buf, sender) -> {
            String name = buf.readString();
            byte[] bytes = buf.readByteArray();
            client.execute(() -> {
                PENDING.remove(name);
                SweepTemplate t = bytes.length == 0 ? null : SweepTemplate.fromBytes(bytes);
                if (t == null) {
                    UNAVAILABLE.add(name);
                } else {
                    CACHE.put(name, t);
                }
                templateVersion++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Sweep.OPEN_SAVE_S2C, (client, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            client.execute(() -> openSave(hand));
        });
        ClientPlayNetworking.registerGlobalReceiver(Sweep.TRACK_S2C, (client, handler, buf, sender) -> {
            int n = buf.readVarInt();
            double[] x = new double[n], y = new double[n], z = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = buf.readDouble();
                y[i] = buf.readDouble();
                z[i] = buf.readDouble();
            }
            client.execute(() -> {
                if (n < 2) {
                    return;
                }
                SweepPreview.setTrack(new com.stationannouncer.mtr.SweepGeometry.Polyline(x, y, z));
                openSweeper(client);
            });
        });
        Sweep.SAVE_SCREEN_OPENER = SweepClient::openSave;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SweepGhostRenderer::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            SweepPreview.clearTrack();
            list = List.of();
            CACHE.clear();
            PENDING.clear();
            UNAVAILABLE.clear();
        }));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || context.consumers() == null) {
                return;
            }
            ItemStack stack = client.player.getMainHandStack();
            if (!(stack.getItem() instanceof ItemSweepWand)) {
                stack = client.player.getOffHandStack();
                if (!(stack.getItem() instanceof ItemSweepWand)) {
                    return;
                }
            }
            drawSelection(context.matrixStack(), context.consumers(), context.camera().getPos(), ItemSweepWand.read(stack));
        });
    }

    /** Opens the sweeper's panel for whichever hand holds the sweeper. */
    static void openSweeper(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        for (Hand hand : Hand.values()) {
            ItemStack held = client.player.getStackInHand(hand);
            if (held.getItem() instanceof com.stationannouncer.mtr.ItemStructureSweeper) {
                if (!(client.currentScreen instanceof SweepScreen)) {
                    client.setScreen(new SweepScreen(hand, held));
                }
                return;
            }
        }
    }

    private static void openSave(Hand hand) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        ItemStack stack = client.player.getStackInHand(hand);
        if (stack.getItem() instanceof ItemSweepWand && (client.currentScreen == null || client.currentScreen instanceof SweepSaveScreen)) {
            client.setScreen(new SweepSaveScreen(hand, stack));
        }
    }

    private static void drawSelection(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cam, ItemSweepWand.Selection s) {
        if (s.c1() == null) {
            return;
        }
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        if (s.c2() == null) {
            // live box: from corner 1 to the block you aim at (or stand in) — where the next click puts corner 2
            MinecraftClient client = MinecraftClient.getInstance();
            BlockPos other = client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                    && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                    ? hit.getBlockPos() : client.player.getBlockPos();
            ItemSweepWand.Selection live = new ItemSweepWand.Selection(s.c1(), other);
            WorldRenderer.drawBox(matrices, lines, new Box(s.c1()).expand(0.01), 1f, 0.85f, 0.2f, 1f);
            WorldRenderer.drawBox(matrices, lines, new Box(Vec3d.of(live.min()), Vec3d.of(live.max()).add(1, 1, 1)).expand(0.004),
                    1f, 0.85f, 0.2f, 0.6f);
        } else {
            Box box = new Box(Vec3d.of(s.min()), Vec3d.of(s.max()).add(1, 1, 1)).expand(0.004);
            WorldRenderer.drawBox(matrices, lines, box, 1f, 1f, 1f, 1f);
        }
        matrices.pop();
    }

    /** Dev hooks: {@code #sweep-editor} (held sweeper's screen), {@code #sweep-save} (held wand's save screen), {@code #sweep-view N}. */
    public static void devHook(MinecraftClient client, String cmd) {
        if (client.player == null) {
            return;
        }
        String[] a = cmd.split("\\s+");
        if (cmd.startsWith("#sweep-editor")) {
            ItemStack held = client.player.getMainHandStack();
            if (held.getItem() instanceof com.stationannouncer.mtr.ItemStructureSweeper) {
                SweepScreen screen = new SweepScreen(Hand.MAIN_HAND, held);
                if (a.length > 1) {
                    screen.devView(Integer.parseInt(a[1]));
                }
                client.setScreen(screen);
            }
        } else if (cmd.startsWith("#sweep-build")) {
            if (client.currentScreen instanceof SweepScreen screen) {
                screen.devBuild();
            }
        } else if (cmd.startsWith("#sweep-set") && a.length > 2) {
            if (client.currentScreen instanceof SweepScreen screen) {
                screen.devSet(a[1], Integer.parseInt(a[2]));
            }
        } else if (cmd.startsWith("#sweep-open")) {
            openSweeper(client);
        } else if (cmd.startsWith("#sweep-save")) {
            openSave(Hand.MAIN_HAND);
        } else if (cmd.startsWith("#sweep-close")) {
            client.setScreen(null);
        }
    }
}
