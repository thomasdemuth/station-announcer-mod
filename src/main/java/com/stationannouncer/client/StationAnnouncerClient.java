package com.stationannouncer.client;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.client.gui.AmbienceScreen;
import com.stationannouncer.client.gui.AnnouncerScreen;
import com.stationannouncer.client.gui.ControlBoxScreen;
import com.stationannouncer.client.gui.SpeakerScreen;
import com.stationannouncer.client.render.LinkLineRenderer;
import com.stationannouncer.client.sound.AmbienceSoundManager;
import com.stationannouncer.client.tts.TtsManager;
import com.stationannouncer.net.AnnouncerNetworking;
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

        // The barrier's wire mesh is a cutout texture (alpha holes).
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_ROOF, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_WALL_GLASS, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STREET_COLUMN_LATTICE, net.minecraft.client.render.RenderLayer.getCutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.EL_STAIR_WALL_GLASS, net.minecraft.client.render.RenderLayer.getCutoutMipped());
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
            if (be instanceof ControlBoxBlockEntity box) {
                client.setScreen(new ControlBoxScreen(box));
            } else if (be instanceof AnnouncerBlockEntity announcer) {
                client.setScreen(new AnnouncerScreen(announcer));
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
                                if (cmd.startsWith("#poster-")) {
                                    // Dev-only: open the poster screens without a mouse
                                    // (#poster-list <disruptionId> / #poster-editor <posterId> /
                                    // #poster-picker <x> <y> <z>) so the rig can screenshot them.
                                    com.stationannouncer.client.mtraddon.PosterDevHooks.open(client, cmd);
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
        });
    }

    private static void handleAnnouncement(MinecraftClient client, String text, float volume, BlockPos pos,
                                           boolean showChat, boolean playChime, String chimeSound) {
        if (client.player == null || client.world == null || text.isBlank()) {
            return;
        }
        interruptPlayback(client);
        ClientConfig config = ClientConfig.get();

        if (showChat) {
            Text message = Text.literal("[PA] ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(text).formatted(Formatting.YELLOW));
            client.player.sendMessage(message, config.useActionBar());
        }

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
        if (audible && config.enableTts) {
            schedule(chime ? ModContent.chimeLeadTicks(chimeSound) : 1, () -> TtsManager.speak(text, volume));
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
