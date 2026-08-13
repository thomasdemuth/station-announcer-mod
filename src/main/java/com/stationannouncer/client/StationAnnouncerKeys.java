package com.stationannouncer.client;

import com.stationannouncer.client.gui.StationAnnouncerConfigScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key binds. They appear in vanilla's Controls screen under this
 * mod's own category, alongside the driving HUD's key that the dispatch addon
 * registers.
 *
 * <p>Both ship UNBOUND. A mod that grabs a letter key by default is a mod that
 * eventually collides with something the player already uses, and neither of
 * these is needed often enough to earn a key without being asked.</p>
 */
@Environment(EnvType.CLIENT)
public final class StationAnnouncerKeys {
    /** Shared with the dispatch addon's HUD key so all of them list together. */
    public static final String CATEGORY = "key.station_announcer.category";

    private static KeyBinding settingsKey;
    private static KeyBinding muteKey;

    private StationAnnouncerKeys() {
    }

    public static void register() {
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.settings", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.station_announcer.mute", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(StationAnnouncerKeys::tick);
    }

    private static void tick(MinecraftClient client) {
        while (settingsKey.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(new StationAnnouncerConfigScreen(null));
            }
        }
        while (muteKey.wasPressed()) {
            ClientConfig config = ClientConfig.get();
            config.enableTts = !config.enableTts;
            ClientConfig.persist();
            if (client.player != null) {
                // Silence is hard to tell from a broken mod, so say which it is.
                client.player.sendMessage(Text.translatable(config.enableTts
                        ? "msg.station_announcer.speech_on"
                        : "msg.station_announcer.speech_off"), true);
            }
        }
    }
}
