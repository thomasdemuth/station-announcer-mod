package com.stationannouncer.mtr;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * Settings the rail-following creator items keep in their item NBT, edited
 * in the client's settings screen (right-click in the air) and written by
 * the server through {@link MtrPillars#UPDATE_CREATOR_C2S}. Missing keys
 * fall back to the item's own defaults, so pre-existing creators keep their
 * old behaviour until touched.
 */
public final class CreatorSettings {
    public static final String WIDTH = "Width";
    public static final String SPACING = "Spacing";
    public static final String LATTICE = "Lattice";
    public static final String GIRDER = "Girder";

    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 9;
    public static final int MIN_SPACING = 2;
    public static final int MAX_SPACING = 16;

    private CreatorSettings() {
    }

    /** Odd widths only: a cell under the rail plus an equal number each side. */
    public static int clampWidth(int width) {
        int w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        return (w % 2 == 0) ? w - 1 : w;
    }

    public static int clampSpacing(int spacing) {
        return Math.max(MIN_SPACING, Math.min(MAX_SPACING, spacing));
    }

    public static int width(ItemStack stack, int fallback) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.contains(WIDTH) ? clampWidth(nbt.getInt(WIDTH)) : clampWidth(fallback);
    }

    public static int spacing(ItemStack stack, int fallback) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.contains(SPACING) ? clampSpacing(nbt.getInt(SPACING)) : clampSpacing(fallback);
    }

    public static boolean lattice(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.getBoolean(LATTICE);
    }

    public static boolean girder(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt == null || !nbt.contains(GIRDER) || nbt.getBoolean(GIRDER);
    }

    public static void write(ItemStack stack, int width, int spacing, boolean lattice, boolean girder) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putInt(WIDTH, clampWidth(width));
        nbt.putInt(SPACING, clampSpacing(spacing));
        nbt.putBoolean(LATTICE, lattice);
        nbt.putBoolean(GIRDER, girder);
    }
}
