package com.stationannouncer.client.mtraddon.cab;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** The cab HUD's pixel sprites (one GUI pixel per cell), the same shapes as the approved mock-up. */
@Environment(EnvType.CLIENT)
final class Icons {
    static final int[][] ARROW = of("#...", "##..", "###.", "####", "###.", "##..", "#...");
    static final int[][] STOP_MARK = of(".....", "#####", "#####", "#####", "#####", "#####", ".....");
    static final int[][] DOOR = of("#######", "#..#..#", "#..#..#", "#..#..#", "#..#..#", "#..#..#", "#######");
    static final int[][] GUARD = of("..##...", "..##...", ".####..", "#.##.#.", "#.##.#.", ".#..#..", ".#..#..");
    static final int[][] DRIVER = of(".###...", ".###...", "#####..", "#.#.#..", "..#.##.", ".#.#...", ".#.#...");
    static final int[][] HOLD = of("..#.#..", ".##.##.", ".#####.", "######.", ".#####.", "..####.", "..###..");
    static final int[][] CLOCK = of(".###.", "#.#.#", "#.##.", "#...#", ".###.");
    static final int[][] MARKER = of("#####", "#.#.#", "#####", "..#..", "..#..", "..#..", "..#..");
    static final int[][] WARN = of("...#...", "..#.#..", "..#.#..", ".#.#.#.", ".#...#.", "#..#..#", "#######");
    static final int[][] CHEVRON_UP = of("..#..", ".#.#.", "#...#");
    static final int[][] CHEVRON_DOWN = of("#...#", ".#.#.", "..#..");

    private Icons() {
    }

    private static int[][] of(String... rows) {
        int[][] out = new int[rows.length][];
        for (int j = 0; j < rows.length; j++) {
            out[j] = new int[rows[j].length()];
            for (int i = 0; i < rows[j].length(); i++) {
                out[j][i] = rows[j].charAt(i) == '#' ? 1 : 0;
            }
        }
        return out;
    }
}
