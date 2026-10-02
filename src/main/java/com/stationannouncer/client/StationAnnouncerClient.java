package com.stationannouncer.client;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.client.gui.AmbienceScreen;
import com.stationannouncer.client.gui.PaSourceScreen;
import com.stationannouncer.client.gui.SpeakerScreen;
import com.stationannouncer.client.render.LinkLineRenderer;
import com.stationannouncer.client.render.PaRangeRenderer;
import com.stationannouncer.client.sound.AmbienceSoundManager;
import com.stationannouncer.client.tts.TtsManager;
import com.stationannouncer.net.AnnouncerNetworking;
import com.stationannouncer.pa.PaText;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class StationAnnouncerClient implements ClientModInitializer {
    // The voice waits for the selected chime to finish before speaking; the
    // per-chime lead ticks live in ModContent.CHIME_OPTIONS.

    /** Pending client-side actions, e.g. "speak after the chime finishes". */
    private static final List<DelayedTask> TASKS = new ArrayList<>();

    /** Size of {@link #TASKS}, readable without taking the lock every client tick. */
    private static volatile int pendingTasks;

    /** The chime currently playing, kept so a newer announcement can cut it off. */
    private static SoundInstance currentChime;

    @Override
    public void onInitializeClient() {
        // Key binds (both unbound by default) show up in vanilla's Controls screen.
        StationAnnouncerKeys.register();
        // Ramps and stairs in any full block's texture: palette, models, picker.
        com.stationannouncer.client.material.MaterialClient.register();

        // The barrier's wire mesh is a cutout texture (alpha holes).
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_ROOF, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_WALL_GLASS, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_WALL_WINDOW, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STREET_COLUMN_LATTICE, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STAIR_WALL_GLASS, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STAIR_UPPER, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STAIR_ROOF, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_LANDING_ROOF, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.PLATFORM_BARRIER,
                net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.SUBWAY_STAIRS,
                net.minecraft.client.render.RenderLayer.getCutoutMipped());
        // The gate ironwork is see-through between the bars.
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.GATE_SCROLL,
                net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.GATE_GRILLE,
                net.minecraft.client.render.RenderLayer.getCutoutMipped());


        // Lets the (common) block classes open the matching client-only screen safely.
        StationAnnouncer.GUI_OPENER = be -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (be instanceof ControlBoxBlockEntity || be instanceof AnnouncerBlockEntity) {
                client.setScreen(new PaSourceScreen((AbstractPaBlockEntity) be));
            } else if (be instanceof SpeakerBlockEntity speaker) {
                client.setScreen(new SpeakerScreen(speaker));
            } else if (be instanceof AmbienceBlockEntity ambience) {
                client.setScreen(new AmbienceScreen(ambience));
            }
        };

        // Looping station ambience around ambience blocks.
        AmbienceSoundManager.register();

        // Dev-only helper: touching <runDir>/screenshot.flag saves a vanilla
        // screenshot — lets headless test tooling grab visual proof without
        // OS-level screen capture permissions. No-op in production.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.world == null) {
                    return;
                }
                java.io.File flag = new java.io.File(client.runDirectory, "screenshot.flag");
                if (flag.exists() && flag.delete()) {
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
                            client.runDirectory, client.getFramebuffer(), text -> {});
                }
                // Dev-only helper #2: <runDir>/commands.txt is consumed one line per
                // tick and sent as chat commands from this player (needs op/cheats),
                // so a headless rig can place blocks and teleport without a console.
                java.io.File commands = new java.io.File(client.runDirectory, "commands.txt");
                if (commands.exists() && client.player != null && (client.player.age % 25) == 0) {
                    try {
                        java.util.List<String> lines = java.nio.file.Files.readAllLines(commands.toPath());
                        java.util.List<String> rest = new java.util.ArrayList<>();
                        boolean sent = false;
                        for (String line : lines) {
                            String cmd = line.strip();
                            if (cmd.isEmpty()) {
                                continue;
                            }
                            if (!sent) {
                                if (cmd.startsWith("#pose ")) {
                                    // Dev-only: hold a fare barrier at an absolute angle
                                    // (#pose x y z degrees; omit degrees to release).
                                    String[] a = cmd.split("\\s+");
                                    net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(
                                            Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                                    if (a.length > 4) {
                                        com.stationannouncer.client.mtr.TurnstileRenderer.DEV_POSE.put(p, Float.parseFloat(a[4]));
                                    } else {
                                        com.stationannouncer.client.mtr.TurnstileRenderer.DEV_POSE.remove(p);
                                    }
                                } else if (cmd.startsWith("#turn ") || cmd.startsWith("#deny ")) {
                                    // Dev-only: pose a fare barrier's animation on THIS client
                                    // (#turn x y z [ticksAgo] [dir] / #deny x y z [ticksAgo]) —
                                    // freeze ticks first and the pose holds for a screenshot.
                                    String[] a = cmd.split("\\s+");
                                    net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(
                                            Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                                    long ago = a.length > 4 ? Long.parseLong(a[4]) : 4;
                                    int dir = a.length > 5 ? Integer.parseInt(a[5]) : 1;
                                    if (client.world.getBlockEntity(p) instanceof com.stationannouncer.mtr.TurnstileBlockEntity be) {
                                        long now = client.world.getTime();
                                        if (cmd.startsWith("#turn ")) {
                                            be.devPose(now - ago, dir, -1);
                                        } else {
                                            be.devPose(-1, dir, now - ago);
                                        }
                                    }
                                } else if (cmd.startsWith("#crack ")) {
                                    // Dev-only: pose a mining crack on a block on THIS client
                                    // (#crack x y z stage, stage 0-9; -1 clears) — how the
                                    // breaking overlay on renderer-drawn blocks gets screenshotted.
                                    String[] a = cmd.split("\\s+");
                                    net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(
                                            Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                                    int stage = a.length > 4 ? Integer.parseInt(a[4]) : 5;
                                    // One breaking block per "entity" in vanilla, so each pos gets
                                    // its own synthetic (negative, never a real entity) breaker id.
                                    int breaker = Long.hashCode(p.asLong()) | Integer.MIN_VALUE;
                                    client.worldRenderer.setBlockBreakingInfo(breaker, p, stage);
                                } else if (cmd.equals("#reload")) {
                                    // Dev-only: F3+T without a keyboard, so a headless rig can
                                    // pick up regenerated models/textures copied into build/resources.
                                    client.reloadResources();
                                } else if (cmd.startsWith("#sign-")) {
                                    // Dev-only: the MTA sign editor / loader (#sign-editor x y z,
                                    // #sign-load x y z <json file>, #sign-close).
                                    com.stationannouncer.client.mtr.SignDevHooks.open(client, cmd);
                                } else if (cmd.equals("#bridge-editor")) {
                                    // Dev-only: the Bridge Creator screen for the held item.
                                    client.setScreen(new com.stationannouncer.client.mtr.BridgeCreatorScreen(
                                            net.minecraft.util.Hand.MAIN_HAND, client.player.getMainHandStack()));
                                } else if (cmd.startsWith("#earthworks-editor")) {
                                    // Dev-only: the earthworks screen for the held tool
                                    // (#earthworks-editor [part] [before]).
                                    net.minecraft.item.ItemStack held = client.player.getMainHandStack();
                                    if (held.getItem() instanceof com.stationannouncer.mtr.ItemEarthworksCreator tool) {
                                        com.stationannouncer.client.mtr.EarthworksScreen screen = new com.stationannouncer.client.mtr.EarthworksScreen(
                                                net.minecraft.util.Hand.MAIN_HAND, held, tool.kind);
                                        String[] a = cmd.split("\\s+");
                                        if (a.length > 1) {
                                            screen.select(a[1]);
                                        }
                                        if (a.length > 2 && a[2].equals("before")) {
                                            screen.showBefore();
                                        }
                                        client.setScreen(screen);
                                    }
                                } else if (cmd.equals("#material-picker")) {
                                    // Dev-only: the ramp/stairs texture picker for the held item.
                                    client.setScreen(com.stationannouncer.client.material.MaterialPickerScreen.forHand(
                                            net.minecraft.util.Hand.MAIN_HAND, client.player.getMainHandStack()));
                                } else if (cmd.startsWith("#marker-")) {
                                    // Dev-only: exit/place marker editors without a mouse
                                    // (#marker-editor x y z / #marker-exit x y z NAME dest… /
                                    // #marker-place x y z category name…) — see WayfindingClient.
                                    com.stationannouncer.client.mtr.WayfindingClient.devHook(client, cmd);
                                } else if (cmd.startsWith("#interline")) {
                                    // Dev-only: drive the Interlining screen without a mouse
                                    // (#interline [sections N | depots N | groups | suggest DIR [target]
                                    // | apply | delay N DURATION]) — see InterlineScreen.devHook.
                                    com.stationannouncer.client.mtraddon.InterlineScreen.devHook(client, cmd);
                                } else if (cmd.startsWith("#poster-")) {
                                    // Dev-only: open the poster screens without a mouse
                                    // (#poster-list <disruptionId> / #poster-editor <posterId> /
                                    // #poster-picker <x> <y> <z>) so the rig can screenshot them.
                                    com.stationannouncer.client.mtraddon.PosterDevHooks.open(client, cmd);
                                } else if (cmd.startsWith("#pa-fire ") || cmd.startsWith("#pa-net ")) {
                                    // Dev-only: send the editor's packets without clicking —
                                    // #pa-fire x y z [message…] / #pa-net bx by bz sx sy sz unlink|vol radius.
                                    String[] a = cmd.split("\\s+", cmd.startsWith("#pa-fire ") ? 5 : 9);
                                    net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                                    buf.writeBlockPos(new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                                    if (cmd.startsWith("#pa-fire ")) {
                                        buf.writeString(a.length > 4 ? a[4] : "", AbstractPaBlockEntity.MAX_TEXT_LENGTH);
                                        ClientPlayNetworking.send(AnnouncerNetworking.FIRE_NOW_C2S, buf);
                                    } else {
                                        BlockPos target = new BlockPos(Integer.parseInt(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6]));
                                        boolean unlink = a[7].equals("unlink");
                                        buf.writeVarInt(unlink ? 1 : 0);
                                        if (unlink) {
                                            buf.writeBlockPos(target);
                                        }
                                        buf.writeVarInt(unlink ? 0 : 1);
                                        if (!unlink) {
                                            buf.writeBlockPos(target);
                                            buf.writeVarInt(Integer.parseInt(a[7]));
                                            buf.writeVarInt(Integer.parseInt(a[8]));
                                        }
                                        ClientPlayNetworking.send(AnnouncerNetworking.NETWORK_EDIT_C2S, buf);
                                    }
                                } else if (cmd.equals("#pa-close")) {
                                    client.setScreen(null);
                                } else if (cmd.startsWith("#pa-gui ")) {
                                    // Dev-only: open a PA block's screen (control box, announcer,
                                    // speaker) without a mouse: #pa-gui x y z [card N | network | templates | chime].
                                    String[] a = cmd.split("\\s+");
                                    net.minecraft.block.entity.BlockEntity be = client.world.getBlockEntity(new BlockPos(
                                            Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                                    if (be != null) {
                                        StationAnnouncer.GUI_OPENER.accept(be);
                                        if (a.length > 4 && client.currentScreen instanceof PaSourceScreen pa) {
                                            pa.devShow(a[4], a.length > 5 ? a[5] : ""); // card N | network | templates | chime
                                        }
                                    }
                                } else {
                                    client.player.networkHandler.sendChatCommand(
                                            cmd.startsWith("/") ? cmd.substring(1) : cmd);
                                }
                                sent = true;
                            } else {
                                rest.add(line);
                            }
                        }
                        if (rest.isEmpty()) {
                            commands.delete();
                        } else {
                            java.nio.file.Files.write(commands.toPath(), rest);
                        }
                    } catch (java.io.IOException ignored) {
                    }
                }
            });
        }

        // White link lines between control boxes and speakers while the tool is held.
        LinkLineRenderer.register();
        // Speaker reach rings and the area-link box (Speaker Link held / speaker screen open).
        PaRangeRenderer.register();

        // The bench seat entity is invisible — nothing to draw.
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                ModContent.SEAT_ENTITY, net.minecraft.client.render.entity.EmptyEntityRenderer::new);

        // NYC PIDS renderers; class only touched when MTR is present.
        if (FabricLoader.getInstance().isModLoaded("mtr")) {
            com.stationannouncer.client.mtr.MtrPidsClient.register();
            com.stationannouncer.client.mtraddon.AddonClientInit.register();
        }

        ClientPlayNetworking.registerGlobalReceiver(AnnouncerNetworking.ANNOUNCE_S2C,
                (client, handler, buf, responseSender) -> {
                    String text = buf.readString(AbstractPaBlockEntity.MAX_TEXT_LENGTH);
                    float volume = buf.readFloat();
                    BlockPos pos = buf.readBlockPos();
                    boolean showChat = buf.readBoolean();
                    boolean playChime = buf.readBoolean();
                    String chimeSound = buf.readString(AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
                    client.execute(() -> handleAnnouncement(client, text, volume, pos, showChat, playChime, chimeSound));
                });

        // The control box screen's Network tab: every member's health and settings.
        ClientPlayNetworking.registerGlobalReceiver(AnnouncerNetworking.NETWORK_INFO_S2C,
                (client, handler, buf, responseSender) -> {
                    BlockPos boxPos = buf.readBlockPos();
                    int count = Math.min(buf.readVarInt(), AnnouncerNetworking.MAX_NETWORK_ROWS);
                    List<ControlBoxBlockEntity.Member> members = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        members.add(new ControlBoxBlockEntity.Member(buf.readBlockPos(), buf.readBoolean(),
                                buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readString(128)));
                    }
                    client.execute(() -> PaSourceScreen.receiveNetworkInfo(boxPos, members));
                });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Almost every tick there is nothing queued: check that first so
            // the common case costs one field read and no iterator.
            if (pendingTasks == 0) {
                return;
            }
            synchronized (TASKS) {
                Iterator<DelayedTask> iterator = TASKS.iterator();
                while (iterator.hasNext()) {
                    DelayedTask task = iterator.next();
                    if (--task.ticks <= 0) {
                        iterator.remove();
                        task.action.run();
                    }
                }
                pendingTasks = TASKS.size();
            }
        });

        // Leaving a world: cancel queued speech and anything still pending.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            synchronized (TASKS) {
                TASKS.clear();
                pendingTasks = 0;
            }
            TtsManager.stop();
            currentChime = null; // the sound engine stops world sounds itself
            LinkLineRenderer.invalidate();
            PaRangeRenderer.clearPreview();
        });
    }

    private static void handleAnnouncement(MinecraftClient client, String text, float volume, BlockPos pos,
                                           boolean showChat, boolean playChime, String chimeSound) {
        if (client.player == null || client.world == null || text.isBlank()) {
            return;
        }
        interruptPlayback(client);
        ClientConfig config = ClientConfig.get();
        // The client half of PaText: the clock, then "{shown|spoken}" split into
        // what chat shows and what the voice says.
        String filled = text.contains(PaText.TIME) ? PaText.fill(text, null, PaText.clock()) : text;
        String shown = PaText.shown(filled);
        String spoken = PaText.spoken(filled);

        if (showChat && !shown.isBlank()) {
            Text message = Text.literal("[PA] ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(shown).formatted(Formatting.YELLOW));
            client.player.sendMessage(message, config.useActionBar());
        }
        play(client, spoken, volume, playChime, chimeSound);
    }

    /**
     * An editor's ▶: plays one message on THIS client only — chime and voice,
     * no chat — exactly as a listener at {@code volume} would hear it.
     * {@code {station}} is looked up client-side at {@code stationPos}.
     */
    public static void previewLocally(String raw, @org.jetbrains.annotations.Nullable BlockPos stationPos,
                                      float volume, boolean playChime, String chimeSound) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        String text = PaText.stripDisabled(raw);
        if (text.contains(PaText.STATION)) {
            text = PaText.fill(text, PaText.clientStationAt(stationPos), null);
        }
        text = PaText.fill(text, null, PaText.clock());
        interruptPlayback(client);
        play(client, PaText.spoken(text), Math.max(volume, TtsManager.AUDIBLE_THRESHOLD), playChime, chimeSound);
    }

    /** Stops whatever PA audio is playing on this client (an editor's ■). */
    public static void stopPreview() {
        interruptPlayback(MinecraftClient.getInstance());
    }

    private static void play(MinecraftClient client, String spoken, float volume, boolean playChime, String chimeSound) {
        ClientConfig config = ClientConfig.get();

        boolean audible = volume >= TtsManager.AUDIBLE_THRESHOLD;
        boolean chime = audible && playChime && config.enableChime;
        if (chime) {
            // Played through the sound manager (not world.playSound) so the next
            // announcement can cut it off.
            //
            // NO ATTENUATION, and at the listener rather than at the speaker.
            // The server has already worked out this player's volume from their
            // distance to the loudest source in range; letting Minecraft apply
            // its own distance rolloff on top attenuated it TWICE, and its
            // rolloff runs out around 16 blocks while a PA radius may be 128 —
            // so anyone standing further than that from the block heard nothing
            // at all. The ambience block solves it the same way.
            currentChime = new PositionedSoundInstance(
                    resolveChime(client, chimeSound).getId(), config.chimeSoundCategory(),
                    volume, 1.0f, SoundInstance.createRandom(), false, 0,
                    SoundInstance.AttenuationType.NONE, 0.0, 0.0, 0.0, true);
            client.getSoundManager().play(currentChime);
        }
        if (audible && config.enableTts && !spoken.isBlank()) {
            schedule(chime ? ModContent.chimeLeadTicks(chimeSound) : 1, () -> TtsManager.speak(spoken, volume));
        }
    }

    /**
     * Latest announcement wins: whenever a new one arrives, whatever is still
     * playing — a queued voice line, speech in progress, or the chime itself —
     * is stopped first so PA audio never overlaps.
     */
    private static void interruptPlayback(MinecraftClient client) {
        synchronized (TASKS) {
            TASKS.clear(); // pending "speak after chime" actions
            pendingTasks = 0;
        }
        TtsManager.stop();
        if (currentChime != null) {
            client.getSoundManager().stop(currentChime);
            currentChime = null;
        }
    }

    /**
     * Resolves the block's custom chime sound id, falling back to the built-in
     * ding-dong if the id is malformed or no resource pack provides it.
     */
    private static SoundEvent resolveChime(MinecraftClient client, String chimeSound) {
        if (!chimeSound.isBlank()) {
            Identifier id = Identifier.tryParse(chimeSound);
            if (id != null && client.getSoundManager().get(id) != null) {
                return SoundEvent.of(id);
            }
            // Diagnostic breadcrumb: an unknown id usually means the client
            // runs an older mod version or is missing a resource pack.
            StationAnnouncer.LOGGER.warn("Chime sound '{}' not found on this client, playing the default chime", chimeSound);
        }
        return ModContent.CHIME;
    }

    private static void schedule(int ticks, Runnable action) {
        synchronized (TASKS) {
            TASKS.add(new DelayedTask(ticks, action));
            pendingTasks = TASKS.size();
        }
    }

    private static final class DelayedTask {
        int ticks;
        final Runnable action;

        DelayedTask(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }
}
