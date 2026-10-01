package com.stationannouncer.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.pa.PaText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PA announcement templates, kept client-side so they follow the player across
 * worlds and servers (the sign templates' pattern): the player's own in
 * {@code config/station_announcer/pa_templates.json}, built-ins deleted by the
 * player remembered by name in {@code pa_templates_hidden.json}.
 *
 * <p>A template is one or more messages plus, optionally, the box settings they
 * were saved with — so "Add" can drop a single line into any box while "Replace"
 * can rebuild a whole station loop, timing and chime included.</p>
 */
@Environment(EnvType.CLIENT)
public final class PaTemplates {
    public static final int MAX_NAME = 32;
    public static final int MAX_TEMPLATES = 128;

    /** The box settings a template may carry. */
    public record Settings(int delaySeconds, boolean showChat, boolean playChime, String chimeSound,
                           boolean randomOrder, int autoMinSeconds, int autoMaxSeconds) {
    }

    public record Template(String name, List<PaText.Entry> messages, @Nullable Settings settings, boolean builtIn) {
        public String summary() {
            if (messages.isEmpty()) {
                return "";
            }
            String first = PaText.shown(messages.get(0).text());
            return messages.size() == 1 ? first : messages.size() + " messages · " + first;
        }
    }

    /** Built-in station lines: generic PA phrasing, no agency slogans. */
    private static final List<Template> BUILT_IN = List.of(
            line("Stand clear", "Stand clear of the closing doors, please."),
            line("Doors", "Please do not hold the doors. Another train is directly behind this one."),
            line("Mind the gap", "Please watch the gap between the train and the platform."),
            line("Welcome", "Welcome to {station}."),
            line("Time check", "The time is now {time}."),
            line("Delay", "We are being held momentarily by the train dispatcher. We apologize for the delay."),
            line("Unattended bags", "Please do not leave bags unattended. Report anything suspicious to station staff."),
            line("Courtesy", "Please let customers off the train before boarding, and make room for others."),
            line("Platform edge", "For your safety, please stand behind the yellow line."),
            new Template("Station loop", List.of(
                    new PaText.Entry("Welcome to {station}.", true),
                    new PaText.Entry("Please stand behind the yellow line.", true),
                    new PaText.Entry("Please do not leave bags unattended. Report anything suspicious to station staff.", true),
                    new PaText.Entry("The time is now {time}.", true)),
                    new Settings(0, true, true, "", false, 120, 300), true));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Template> user;
    private static Set<String> hidden;

    private PaTemplates() {
    }

    private static Template line(String name, String text) {
        return new Template(name, List.of(new PaText.Entry(text, true)), null, true);
    }

    /** Yours first (newest saves at the end), then the built-ins you have not deleted. */
    public static synchronized List<Template> all() {
        List<Template> out = new ArrayList<>(user().values());
        for (Template template : BUILT_IN) {
            if (!hidden().contains(template.name()) && !user().containsKey(template.name())) {
                out.add(template);
            }
        }
        return out;
    }

    public static synchronized int hiddenCount() {
        int count = 0;
        for (Template template : BUILT_IN) {
            if (hidden().contains(template.name())) {
                count++;
            }
        }
        return count;
    }

    public static synchronized boolean exists(String name) {
        return user().containsKey(name);
    }

    /** Saves (or replaces) one of yours; false when the list is full. */
    public static synchronized boolean put(String name, List<PaText.Entry> messages, @Nullable Settings settings) {
        if (!user().containsKey(name) && user().size() >= MAX_TEMPLATES) {
            return false;
        }
        user().put(name, new Template(name, List.copyOf(messages), settings, false));
        save();
        return true;
    }

    /** Deletes one of yours, or hides a built-in until restored. */
    public static synchronized void delete(Template template) {
        if (template.builtIn()) {
            if (hidden().add(template.name())) {
                saveHidden();
            }
        } else {
            user().remove(template.name());
            save();
        }
    }

    public static synchronized void restoreBuiltIns() {
        hidden().clear();
        saveHidden();
    }

    // ---------------------------------------------------------------- storage

    private static Map<String, Template> user() {
        if (user == null) {
            user = load();
        }
        return user;
    }

    private static Set<String> hidden() {
        if (hidden == null) {
            hidden = loadHidden();
        }
        return hidden;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("pa_templates.json");
    }

    private static Path hiddenPath() {
        return path().resolveSibling("pa_templates_hidden.json");
    }

    private static Map<String, Template> load() {
        Map<String, Template> loaded = new LinkedHashMap<>();
        Path path = path();
        try {
            if (Files.exists(path)) {
                JsonElement root = JsonParser.parseString(Files.readString(path));
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                        if (entry.getValue().isJsonObject() && loaded.size() < MAX_TEMPLATES) {
                            Template template = fromJson(entry.getKey(), entry.getValue().getAsJsonObject());
                            if (!template.messages().isEmpty()) {
                                loaded.put(entry.getKey(), template);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read PA templates from {}", path, e);
        }
        return loaded;
    }

    private static Template fromJson(String name, JsonObject json) {
        List<PaText.Entry> messages = new ArrayList<>();
        if (json.has("messages") && json.get("messages").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("messages")) {
                if (element.isJsonObject()) {
                    JsonObject message = element.getAsJsonObject();
                    String text = message.has("text") ? PaText.clean(message.get("text").getAsString()) : "";
                    boolean on = !message.has("on") || message.get("on").getAsBoolean();
                    if (!text.isEmpty()) {
                        messages.add(new PaText.Entry(text, on));
                    }
                }
            }
        }
        Settings settings = null;
        if (json.has("settings") && json.get("settings").isJsonObject()) {
            JsonObject s = json.getAsJsonObject("settings");
            settings = new Settings(intOr(s, "delay", 0), boolOr(s, "chat", true), boolOr(s, "chime", true),
                    s.has("chimeSound") ? s.get("chimeSound").getAsString() : "",
                    boolOr(s, "random", true), intOr(s, "autoMin", 0), intOr(s, "autoMax", 0));
        }
        return new Template(name, List.copyOf(messages), settings, false);
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static boolean boolOr(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    private static void save() {
        Path path = path();
        try {
            JsonObject root = new JsonObject();
            for (Template template : user.values()) {
                JsonObject json = new JsonObject();
                JsonArray messages = new JsonArray();
                for (PaText.Entry entry : template.messages()) {
                    JsonObject message = new JsonObject();
                    message.addProperty("text", entry.text());
                    if (!entry.enabled()) {
                        message.addProperty("on", false);
                    }
                    messages.add(message);
                }
                json.add("messages", messages);
                Settings s = template.settings();
                if (s != null) {
                    JsonObject settings = new JsonObject();
                    settings.addProperty("delay", s.delaySeconds());
                    settings.addProperty("chat", s.showChat());
                    settings.addProperty("chime", s.playChime());
                    settings.addProperty("chimeSound", s.chimeSound());
                    settings.addProperty("random", s.randomOrder());
                    settings.addProperty("autoMin", s.autoMinSeconds());
                    settings.addProperty("autoMax", s.autoMaxSeconds());
                    json.add("settings", settings);
                }
                root.add(template.name(), json);
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write PA templates to {}", path, e);
        }
    }

    private static Set<String> loadHidden() {
        Set<String> loaded = new LinkedHashSet<>();
        Path path = hiddenPath();
        try {
            if (Files.exists(path)) {
                JsonElement root = JsonParser.parseString(Files.readString(path));
                if (root.isJsonArray()) {
                    root.getAsJsonArray().forEach(element -> {
                        if (element.isJsonPrimitive()) {
                            loaded.add(element.getAsString());
                        }
                    });
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read deleted PA templates from {}", path, e);
        }
        return loaded;
    }

    private static void saveHidden() {
        Path path = hiddenPath();
        try {
            if (hidden.isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            JsonArray root = new JsonArray();
            hidden.forEach(root::add);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write deleted PA templates to {}", path, e);
        }
    }
}
