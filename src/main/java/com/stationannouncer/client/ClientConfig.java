package com.stationannouncer.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Per-player settings, stored at {@code config/station_announcer/client.json}.
 * Lets a player mute PA text-to-speech or the chime entirely on their end.
 */
@Environment(EnvType.CLIENT)
public class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig instance;

    /** Master switch for spoken announcements on this client. */
    public boolean enableTts = true;

    /** Master switch for the ding-dong chime on this client. */
    public boolean enableChime = true;

    /** Where announcement text is shown: {@code chat} or {@code actionbar}. */
    public String displayMode = "chat";

    /**
     * TTS backend: {@code auto} (Minecraft narrator, falling back to the OS voice),
     * {@code narrator}, or {@code system}.
     */
    public String ttsBackend = "auto";

    /**
     * Voice name for the {@code system} backend ("" = OS default). Voice names are
     * OS-specific: `say -v ?` on macOS, `Get-InstalledVoice` (System.Speech) on
     * Windows, `espeak --voices` on Linux. The narrator backend has no voice API.
     */
    public String voice = "";

    /**
     * Which volume slider the chime answers to: {@code master}, {@code blocks},
     * {@code ambient} or {@code voice}.
     *
     * <p>Master by default, and deliberately NOT voice: that slider is meant
     * for narration and voice chat, it is one a lot of players turn down, and
     * a chime nobody can hear is indistinguishable from a broken mod.</p>
     */
    public String chimeCategory = "master";

    /** The chime's sound category, falling back to master for an unknown name. */
    public net.minecraft.sound.SoundCategory chimeSoundCategory() {
        return switch (chimeCategory == null ? "" : chimeCategory.toLowerCase(Locale.ROOT)) {
            case "blocks" -> net.minecraft.sound.SoundCategory.BLOCKS;
            case "ambient" -> net.minecraft.sound.SoundCategory.AMBIENT;
            case "voice" -> net.minecraft.sound.SoundCategory.VOICE;
            default -> net.minecraft.sound.SoundCategory.MASTER;
        };
    }

    public boolean useActionBar() {
        return "actionbar".equalsIgnoreCase(displayMode);
    }

    public String backend() {
        return ttsBackend == null ? "auto" : ttsBackend.toLowerCase(Locale.ROOT);
    }

    public static synchronized ClientConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Writes the current values back to disk (the settings screen saves through this). */
    public static synchronized void persist() {
        if (instance != null) {
            instance.save(path());
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("client.json");
    }

    private static ClientConfig load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
                    if (loaded != null) {
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, using defaults", path, e);
        }
        ClientConfig config = new ClientConfig();
        config.save(path);
        return config;
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
