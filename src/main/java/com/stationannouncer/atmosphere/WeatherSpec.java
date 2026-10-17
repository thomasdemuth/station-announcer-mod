package com.stationannouncer.atmosphere;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * What the Weathering Tool does to an area: an amount 0..100 per layer
 * (how much of it, relative to where it would naturally collect), how many
 * lights fail and how badly, and the area options. Stored in the tool's NBT
 * under {@code Weather}; the presets are just values of this.
 */
public final class WeatherSpec {
    /** The layers, in screen order. Ordinals index {@link #amounts}: only APPEND. */
    public enum Layer {
        DUST("dust"), SOOT("soot"), STAINS("stains"), RUST("rust"), EFFLORESCENCE("efflorescence"),
        PUDDLES("puddles"), GUM("gum"), OIL("oil"), COBWEBS("cobwebs"), LITTER("litter"), TRASH("trash"),
        LIGHTS("lights"), SEVERITY("severity");

        public final String id;

        Layer(String id) {
            this.id = id;
        }
    }

    public enum Preset {
        PRISTINE("pristine", new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
        LIVED_IN("lived_in", new int[]{25, 15, 8, 6, 6, 3, 30, 15, 2, 12, 3, 6, 20}),
        GRITTY("gritty", new int[]{50, 35, 18, 15, 15, 7, 50, 30, 8, 28, 10, 15, 40}),
        NEGLECTED("neglected", new int[]{75, 55, 35, 30, 30, 15, 60, 45, 25, 48, 25, 32, 65}),
        ABANDONED("abandoned", new int[]{90, 70, 55, 50, 45, 30, 40, 50, 60, 60, 45, 65, 90});

        public final String id;
        final int[] amounts;

        Preset(String id, int[] amounts) {
            this.id = id;
            this.amounts = amounts;
        }

        public static Preset byId(String id) {
            for (Preset p : values()) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
            return null;
        }
    }

    public final int[] amounts = new int[Layer.values().length];
    /** Remove our grime / litter and repair lights in the area before applying. */
    public boolean clean = true;
    /** Two corners clicked at the same height: how many blocks above them to include. */
    public int height = 5;
    /** Station mode (sneak-click): blocks below / above the clicked block to include. */
    public int stationBelow = 4;
    public int stationAbove = 8;

    public WeatherSpec() {
        load(Preset.LIVED_IN);
    }

    public int get(Layer layer) {
        return amounts[layer.ordinal()];
    }

    public void set(Layer layer, int value) {
        amounts[layer.ordinal()] = Math.max(0, Math.min(100, value));
    }

    public void load(Preset preset) {
        System.arraycopy(preset.amounts, 0, amounts, 0, Math.min(amounts.length, preset.amounts.length));
    }

    /** The preset these amounts equal, or null (custom). */
    public Preset matching() {
        for (Preset p : Preset.values()) {
            if (java.util.Arrays.equals(p.amounts, amounts)) {
                return p;
            }
        }
        return null;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putIntArray("Amounts", amounts.clone());
        nbt.putBoolean("Clean", clean);
        nbt.putInt("Height", height);
        nbt.putInt("Below", stationBelow);
        nbt.putInt("Above", stationAbove);
        return nbt;
    }

    public static WeatherSpec fromNbt(NbtCompound nbt) {
        WeatherSpec spec = new WeatherSpec();
        if (nbt == null) {
            return spec;
        }
        int[] a = nbt.getIntArray("Amounts");
        for (int i = 0; i < Math.min(a.length, spec.amounts.length); i++) {
            spec.amounts[i] = Math.max(0, Math.min(100, a[i]));
        }
        if (nbt.contains("Clean")) {
            spec.clean = nbt.getBoolean("Clean");
        }
        if (nbt.contains("Height")) {
            spec.height = clamp(nbt.getInt("Height"), 1, 24);
        }
        if (nbt.contains("Below")) {
            spec.stationBelow = clamp(nbt.getInt("Below"), 0, 32);
        }
        if (nbt.contains("Above")) {
            spec.stationAbove = clamp(nbt.getInt("Above"), 1, 32);
        }
        return spec;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static WeatherSpec read(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return fromNbt(nbt == null || !nbt.contains("Weather") ? null : nbt.getCompound("Weather"));
    }

    public void write(ItemStack stack) {
        stack.getOrCreateNbt().put("Weather", toNbt());
    }
}
