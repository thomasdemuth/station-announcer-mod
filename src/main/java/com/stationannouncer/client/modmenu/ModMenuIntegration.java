package com.stationannouncer.client.modmenu;

import com.stationannouncer.client.gui.StationAnnouncerConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Puts the settings screen behind the config button on Mod Menu's mod list.
 *
 * <p>Mod Menu is a compile-only dependency: Fabric only instantiates the
 * {@code modmenu} entrypoint when Mod Menu is actually installed, so this
 * class is never loaded otherwise and the mod runs fine without it.</p>
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return StationAnnouncerConfigScreen::new;
    }
}
