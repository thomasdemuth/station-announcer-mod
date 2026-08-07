package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Registration for the rail-structure creator items (MTR-only, like the PIDS
 * blocks): ten Pillar Creators ({@code pillar_creator_<width>x<spacing>}:
 * width 1 = one pillar centered under the rail, 3/5 = a pair that many
 * blocks apart; spacing = blocks of rail between sets), four Railing
 * Creators matching the MTR bridge widths, and three Viaduct Creators
 * (deck + girders + pillars in one pass).
 */
public final class MtrPillars {
    /** {width, spacing} for each pillar creator variant, in creative-tab order. */
    public static final int[][] SIZES = {
            {1, 4}, {1, 6}, {1, 8},
            {3, 4}, {3, 6}, {3, 8},
            {5, 4}, {5, 6}, {5, 8}, {5, 10},
    };

    /** Railing creator widths (match MTR's bridge creator widths). */
    public static final int[] RAILING_WIDTHS = {3, 5, 7, 9};

    /** Viaduct creator deck widths. */
    public static final int[] VIADUCT_WIDTHS = {3, 5, 7};

    private MtrPillars() {
    }

    public static void register() {
        for (int[] size : SIZES) {
            ItemPillarCreator item = new ItemPillarCreator(size[0], size[1]);
            Registry.register(Registries.ITEM,
                    StationAnnouncer.id("pillar_creator_" + size[0] + "x" + size[1]), item);
            ModContent.BAKER_CITY_ENTRIES.add(item);
        }
        for (int width : RAILING_WIDTHS) {
            ItemRailingCreator item = new ItemRailingCreator(width);
            Registry.register(Registries.ITEM, StationAnnouncer.id("railing_creator_" + width), item);
            ModContent.BAKER_CITY_ENTRIES.add(item);
        }
        for (int width : VIADUCT_WIDTHS) {
            ItemViaductCreator item = new ItemViaductCreator(width);
            Registry.register(Registries.ITEM, StationAnnouncer.id("viaduct_creator_" + width), item);
            ModContent.BAKER_CITY_ENTRIES.add(item);
        }
    }
}
