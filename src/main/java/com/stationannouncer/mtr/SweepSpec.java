package com.stationannouncer.mtr;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * The Structure Sweeper's settings, kept in the held item's NBT (key
 * {@link #KEY}): which template, how its length maps onto the track, where it
 * sits relative to the rail, and what it may do to the world.
 */
public final class SweepSpec {
    public static final String KEY = "Sweep";
    public static final int MAX_OFFSET = 32;
    public static final int MAX_LEG_DEPTH = 128;

    /** What empty (air) cells of the template do to the world. */
    public enum AirMode {
        /** Dig out natural ground/plants only at rail level and above (tunnels, cuttings) — never under a viaduct. */
        ABOVE_RAIL("Above rail"),
        /** Dig everywhere the template is empty. */
        EVERYWHERE("Everywhere"),
        /** Air never removes anything. */
        NEVER("Never");

        public final String label;

        AirMode(String label) {
            this.label = label;
        }
    }

    public String template = "";
    public SweepGeometry.Mode mode = SweepGeometry.Mode.STRETCH;
    public boolean flip;
    public int sideOffset;
    public int heightOffset;
    public int phase;
    public boolean legs = true;
    public int legDepth = 64;
    public AirMode air = AirMode.ABOVE_RAIL;
    /** Quarter turns applied to the template before pinning (0..3). */
    public int turn;
    /** Rail column / rail level in the (turned) template; −1 = the template's suggestion. */
    public int railW = -1;
    public int railH = -1;

    public void clamp() {
        if (template == null) {
            template = "";
        }
        sideOffset = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, sideOffset));
        heightOffset = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, heightOffset));
        phase = Math.max(0, Math.min(SweepTemplate.MAX_LENGTH - 1, phase));
        legDepth = Math.max(1, Math.min(MAX_LEG_DEPTH, legDepth));
        if (mode == null) {
            mode = SweepGeometry.Mode.STRETCH;
        }
        turn &= 3;
        railW = Math.max(-1, Math.min(SweepTemplate.MAX_WIDTH, railW));
        railH = Math.max(-1, Math.min(SweepTemplate.MAX_HEIGHT, railH));
        if (air == null) {
            air = AirMode.ABOVE_RAIL;
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Template", template == null ? "" : template);
        nbt.putString("Mode", mode.name());
        nbt.putBoolean("Flip", flip);
        nbt.putInt("Side", sideOffset);
        nbt.putInt("Height", heightOffset);
        nbt.putInt("Phase", phase);
        nbt.putBoolean("Legs", legs);
        nbt.putInt("LegDepth", legDepth);
        nbt.putString("Air", air.name());
        nbt.putInt("Turn", turn);
        nbt.putInt("RailW", railW);
        nbt.putInt("RailH", railH);
        return nbt;
    }

    public static SweepSpec fromNbt(NbtCompound nbt) {
        SweepSpec spec = new SweepSpec();
        if (nbt == null) {
            return spec;
        }
        spec.template = nbt.getString("Template");
        spec.mode = parse(SweepGeometry.Mode.class, nbt.getString("Mode"), SweepGeometry.Mode.STRETCH);
        spec.flip = nbt.getBoolean("Flip");
        spec.sideOffset = nbt.getInt("Side");
        spec.heightOffset = nbt.getInt("Height");
        spec.phase = nbt.getInt("Phase");
        spec.legs = !nbt.contains("Legs") || nbt.getBoolean("Legs");
        spec.legDepth = nbt.contains("LegDepth") ? nbt.getInt("LegDepth") : 64;
        spec.air = parse(AirMode.class, nbt.getString("Air"), AirMode.ABOVE_RAIL);
        spec.turn = nbt.getInt("Turn");
        spec.railW = nbt.contains("RailW") ? nbt.getInt("RailW") : -1;
        spec.railH = nbt.contains("RailH") ? nbt.getInt("RailH") : -1;
        spec.clamp();
        return spec;
    }

    public static SweepSpec read(ItemStack stack) {
        NbtCompound root = stack.getNbt();
        return fromNbt(root != null && root.contains(KEY) ? root.getCompound(KEY) : null);
    }

    public void write(ItemStack stack) {
        clamp();
        stack.getOrCreateNbt().put(KEY, toNbt());
    }

    /** Back to the template's own fit (after picking another template). */
    public void resetFit() {
        turn = 0;
        railW = -1;
        railH = -1;
        sideOffset = 0;
        heightOffset = 0;
        phase = 0;
    }

    public SweepSpec copy() {
        return fromNbt(toNbt());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return name == null || name.isEmpty() ? fallback : Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
