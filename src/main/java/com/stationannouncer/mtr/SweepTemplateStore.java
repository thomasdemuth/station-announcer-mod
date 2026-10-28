package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The world's sweep templates: one gzipped NBT file each under
 * {@code <save>/station-announcer-addon/sweep_templates/}, shared by everyone
 * on the server. Export / import copy a template to and from
 * {@code config/station_announcer/sweep_templates/} on the machine running the
 * server, for moving templates between worlds. Server thread only.
 */
public final class SweepTemplateStore {
    public static final int MAX_NAME = 40;

    private static final Map<String, SweepTemplate> TEMPLATES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static Path dir;

    private SweepTemplateStore() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(SweepTemplateStore::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TEMPLATES.clear();
            dir = null;
        });
    }

    private static void load(MinecraftServer server) {
        TEMPLATES.clear();
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("station-announcer-addon").resolve("sweep_templates").normalize();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : files) {
                SweepTemplate t = read(file);
                if (t != null && !t.name.isEmpty()) {
                    TEMPLATES.put(t.name, t);
                } else {
                    StationAnnouncer.LOGGER.warn("Sweep: could not read template {}", file.getFileName());
                }
            }
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Sweep: could not list templates", e);
        }
        StationAnnouncer.LOGGER.info("Sweep: {} templates loaded", TEMPLATES.size());
    }

    private static SweepTemplate read(Path file) {
        try {
            return SweepTemplate.fromNbt(NbtIo.readCompressed(file, NbtSizeTracker.of(64L * 1024 * 1024)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A cleaned-up template name, or null when it has nothing usable. */
    public static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll("[\\p{Cntrl}\"\\\\]", "").trim();
        if (n.length() > MAX_NAME) {
            n = n.substring(0, MAX_NAME).trim();
        }
        return n.isEmpty() ? null : n;
    }

    static String fileName(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }
        return base + "_" + Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode()) + ".nbt";
    }

    public static List<SweepTemplate> all() {
        return new ArrayList<>(TEMPLATES.values());
    }

    public static SweepTemplate get(String name) {
        return name == null ? null : TEMPLATES.get(name);
    }

    /** Saves (or replaces) a template; false when the world's folder cannot be written. */
    public static boolean save(SweepTemplate template) {
        if (dir == null) {
            return false;
        }
        try {
            Files.createDirectories(dir);
            NbtIo.writeCompressed(template.toNbt(), dir.resolve(fileName(template.name)));
            TEMPLATES.put(template.name, template);
            return true;
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Sweep: could not save template {}", template.name, e);
            return false;
        }
    }

    public static boolean delete(String name) {
        SweepTemplate t = get(name);
        if (t == null || dir == null) {
            return false;
        }
        TEMPLATES.remove(t.name);
        try {
            Files.deleteIfExists(dir.resolve(fileName(t.name)));
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Sweep: could not delete template {}", t.name, e);
        }
        return true;
    }

    public static Path exportDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("sweep_templates");
    }

    /** Writes a copy to the export folder; returns the file, or null. */
    public static Path export(String name) {
        SweepTemplate t = get(name);
        if (t == null) {
            return null;
        }
        try {
            Path out = exportDir();
            Files.createDirectories(out);
            Path file = out.resolve(fileName(t.name));
            NbtIo.writeCompressed(t.toNbt(), file);
            return file;
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Sweep: could not export template {}", name, e);
            return null;
        }
    }

    /** Files in the export folder that can be imported. */
    public static List<String> importable() {
        List<String> names = new ArrayList<>();
        Path in = exportDir();
        if (!Files.isDirectory(in)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(in, "*.nbt")) {
            for (Path file : files) {
                names.add(file.getFileName().toString());
            }
        } catch (IOException e) {
            // nothing to offer
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Reads a file from the export folder into the world; returns the template, or null. */
    public static SweepTemplate importFile(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return null;
        }
        SweepTemplate t = read(exportDir().resolve(fileName));
        if (t == null || cleanName(t.name) == null) {
            return null;
        }
        return save(t) ? t : null;
    }
}
