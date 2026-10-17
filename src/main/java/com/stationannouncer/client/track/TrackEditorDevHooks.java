package com.stationannouncer.client.track;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * Dev-rig only (reached from the dev client's run/commands.txt hook): opens and drives the track
 * editor without a mouse so the headless rig can screenshot it. Never called in production.
 *
 * <pre>
 * #track-editor x y z                        editor on every rail at node (x, y, z)
 * #track-editor x y z schematic|3d|world     … on that view tab
 * #track-editor x y z tool select|bezier|tilt|height   … with that tool
 * #track-editor x y z node|mtr|advanced      … with that inspector section open
 * #track-close                               close it (previews are cleared)
 * </pre>
 * Options combine: {@code #track-editor 10 64 -5 world tool tilt}.
 */
@Environment(EnvType.CLIENT)
public final class TrackEditorDevHooks {
    private TrackEditorDevHooks() {
    }

    /** Returns true when the line was a track-editor dev command. */
    public static boolean handle(String line) {
        final String[] a = line.trim().split("\\s+");
        if (a.length == 0) {
            return false;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        try {
            switch (a[0]) {
                case "#track-editor" -> {
                    final long x = Long.parseLong(a[1]);
                    final long y = Long.parseLong(a[2]);
                    final long z = Long.parseLong(a[3]);
                    if (!TrackEditorScreen.open(x, y, z, null)) {
                        return true;
                    }
                    if (client.currentScreen instanceof TrackEditorScreen screen) {
                        for (int i = 4; i < a.length; i++) {
                            switch (a[i].toLowerCase(java.util.Locale.ROOT)) {
                                case "schematic" -> screen.devTab(TrackEditorScreen.ViewTab.SCHEMATIC);
                                case "3d" -> screen.devTab(TrackEditorScreen.ViewTab.VIEW3D);
                                case "world" -> screen.devTab(TrackEditorScreen.ViewTab.WORLD);
                                case "tool" -> {
                                    if (i + 1 < a.length) {
                                        final String name = a[++i].toUpperCase(java.util.Locale.ROOT);
                                        screen.devTool(TrackEditorScreen.Tool.valueOf(name.equals("BÉZIER") ? "BEZIER" : name));
                                    }
                                }
                                default -> screen.devDisclose(a[i].toLowerCase(java.util.Locale.ROOT));
                            }
                        }
                    }
                    return true;
                }
                case "#track-close" -> {
                    if (client.currentScreen instanceof TrackEditorScreen) {
                        client.setScreen(null);
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            return true; // malformed dev command
        }
    }
}
