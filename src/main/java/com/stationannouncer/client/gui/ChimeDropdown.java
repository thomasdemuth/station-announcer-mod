package com.stationannouncer.client.gui;

import com.stationannouncer.ModContent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chime selector shared by the announcer and control box screens: a
 * dropdown (cycling button) over the built-in chimes from the chimes folder.
 * Cycling to a chime plays it immediately as a preview (master category, so
 * it is audible regardless of the Voice/Speech volume slider). A sound id set
 * by an older version or by hand that is not in the list is kept as an extra
 * "Custom" entry rather than silently discarded.
 */
@Environment(EnvType.CLIENT)
final class ChimeDropdown {
    /** The preview currently playing, cut off when the user keeps cycling. */
    private static SoundInstance preview;

    private ChimeDropdown() {
    }

    static CyclingButtonWidget<ModContent.ChimeOption> build(int x, int y, int width, int height,
                                                             String currentId, Consumer<String> onChange) {
        List<ModContent.ChimeOption> options = new ArrayList<>(ModContent.CHIME_OPTIONS);
        ModContent.ChimeOption current = options.stream()
                .filter(option -> option.id().equals(currentId.trim()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            current = new ModContent.ChimeOption(currentId, "Custom", ModContent.chimeLeadTicks(""));
            options.add(current);
        }
        return CyclingButtonWidget.<ModContent.ChimeOption>builder(option -> Text.literal(option.label()))
                .values(options)
                .initially(current)
                .build(x, y, width, height,
                        Text.translatable("gui.station_announcer.chime_sound"),
                        (button, value) -> {
                            onChange.accept(value.id());
                            playPreview(value.id());
                        });
    }

    private static void playPreview(String chimeId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (preview != null) {
            client.getSoundManager().stop(preview);
            preview = null;
        }
        SoundEvent event = ModContent.CHIME;
        if (!chimeId.isBlank()) {
            Identifier id = Identifier.tryParse(chimeId);
            if (id == null || client.getSoundManager().get(id) == null) {
                return; // unknown custom id: no preview rather than a wrong sound
            }
            event = SoundEvent.of(id);
        }
        preview = PositionedSoundInstance.master(event, 1.0f);
        client.getSoundManager().play(preview);
    }
}
