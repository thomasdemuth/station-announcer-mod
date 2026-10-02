package com.stationannouncer.mtr;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the three earthworks tools build, as one plain settings object
 * (the {@link BridgeSpec} pattern): the Embankment Creator fills under the
 * track bed and slopes the sides down to the ground, the Trench Creator
 * cuts the track bed out of the terrain and slopes the sides up to the
 * surface, the ROW Clearer takes trees, plants, snow and (optionally)
 * terrain out of a corridor along the track. Each tool only shows and uses
 * its own fields; the slope settings are shared by the first two.
 *
 * <p>Every layer is a weighted block mix ({@link EarthworksPalette}).
 * Persisted on the item under {@link #TAG}; missing keys fall back to the
 * kind's defaults, so old items and presets keep working.</p>
 */
public final class EarthworksSpec {
    public static final String TAG = "Earthworks";

    public enum Kind { EMBANKMENT, TRENCH, CLEARER }

    public enum TrackMode { AUTO, SINGLE }

    public enum WallMode { NEVER, AUTO, ALWAYS }

    /** A block-mix layer; which kinds use it is in {@link #usedBy}. */
    public enum Layer {
        TOP("Top / shoulder"), CORE("Core fill"), SLOPE("Slope surface"), TOE("Toe (foot of the slope)"),
        FLOOR("Track bed"), DITCH("Ditch lining"), FACE("Cut face"), WALL("Retaining wall");

        public final String label;

        Layer(String label) {
            this.label = label;
        }

        public boolean usedBy(Kind kind) {
            return switch (this) {
                case TOP, CORE, SLOPE, TOE -> kind == Kind.EMBANKMENT;
                case FLOOR, DITCH, FACE -> kind == Kind.TRENCH;
                case WALL -> kind != Kind.CLEARER;
            };
        }
    }

    /** Slope presets: run (blocks across) per block of height, ×10. */
    public static final int[] SLOPE_PRESETS = {5, 10, 15, 20, 30};
    public static final String[] SLOPE_LABELS = {"2:1", "1:1", "1:1.5", "1:2", "1:3"};

    public Kind kind;

    // tracks
    public TrackMode trackMode = TrackMode.AUTO;
    public int trackReach = 8;
    public int previewTracks = 1;

    // formation (embankment / trench)
    /** Track-bed cells beyond the outermost tracks on each side. */
    public int shoulder = 2;
    public int topThickness = 1;
    /** Embankment: deeper fills than this are skipped (bridge territory). */
    public int maxHeight = 24;
    /** Trench: deeper cuts than this are skipped (tunnel territory). */
    public int maxDepth = 24;
    public int ditchWidth = 1;
    public int ditchDepth = 1;
    /** Cuts only take natural terrain, fluids, plants and trees — never buildings. */
    public boolean protectBuilds = true;
    /** Trench: trees standing on (or in) the cut go too, whole. */
    public boolean fellTrees = true;

    // slopes (embankment / trench)
    /** Blocks across per block of height, ×10 (10 = 1:1, 15 = 1:1.5, 5 = 2:1). */
    public int slopeRun = 15;
    public int surfaceThickness = 1;
    public WallMode wallMode = WallMode.AUTO;
    /** AUTO walls: a slope wider than this (blocks) becomes a wall instead. */
    public int wallTrigger = 10;
    public int wallThickness = 1;
    /** Wall rises this far above the track bed / ground (a parapet). */
    public int wallParapet = 0;
    public boolean benches = false;
    /** A bench every this many blocks of height. */
    public int benchEvery = 6;
    public int benchWidth = 2;
    /** Natural edge noise, 0 = ruler-straight. */
    public int blend = 1;

    // ROW clearer
    /** Corridor cells beyond the outermost tracks on each side. */
    public int corridor = 6;
    public int clearHeight = 24;
    public int clearBelow = 6;
    public boolean trees = true;
    public boolean plants = true;
    public boolean snow = true;
    public boolean terrain = false;
    public int terrainWidth = 2;
    public int terrainHeight = 6;

    public final Map<Layer, String> palettes = new EnumMap<>(Layer.class);

    /** Which layer the next sneak-click on a world block goes into, and whether it replaces or adds. */
    public Layer pickTarget = Layer.TOP;
    public boolean pickAdd = false;

    public static final int MAX_SHOULDER = 12, MAX_TOP = 4, MAX_FILL = 96, MAX_DITCH_W = 3, MAX_DITCH_D = 3;
    public static final int MIN_RUN = 3, MAX_RUN = 50, MAX_SURFACE = 3, MIN_TRIGGER = 2, MAX_TRIGGER = 48;
    public static final int MAX_WALL = 3, MAX_PARAPET = 2, MIN_BENCH_EVERY = 3, MAX_BENCH_EVERY = 24, MAX_BENCH_W = 8;
    public static final int MAX_BLEND = 3, MAX_CORRIDOR = 32, MAX_CLEAR_H = 64, MAX_CLEAR_BELOW = 16;
    public static final int MAX_TERRAIN_W = 16, MAX_TERRAIN_H = 32, MAX_PREVIEW_TRACKS = 3;

    public EarthworksSpec(Kind kind) {
        this.kind = kind;
        switch (kind) {
            case EMBANKMENT -> {
                palettes.put(Layer.TOP, "3 minecraft:gravel, 1 minecraft:andesite");
                palettes.put(Layer.CORE, "4 minecraft:dirt, 1 minecraft:stone");
                palettes.put(Layer.SLOPE, "6 minecraft:grass_block, 1 minecraft:coarse_dirt");
                palettes.put(Layer.TOE, "");
                palettes.put(Layer.WALL, "minecraft:stone_bricks");
                pickTarget = Layer.TOP;
            }
            case TRENCH -> {
                palettes.put(Layer.FLOOR, "3 minecraft:gravel, 1 minecraft:andesite");
                palettes.put(Layer.DITCH, "minecraft:cobblestone");
                palettes.put(Layer.FACE, "3 minecraft:stone, 1 minecraft:andesite, 1 minecraft:cobblestone");
                palettes.put(Layer.WALL, "minecraft:stone_bricks");
                slopeRun = 10;
                pickTarget = Layer.FACE;
            }
            case CLEARER -> pickTarget = Layer.TOP;
        }
    }

    public String palette(Layer layer) {
        return palettes.getOrDefault(layer, "");
    }

    public void setPalette(Layer layer, String text) {
        palettes.put(layer, text == null ? "" : text.trim());
    }

    public EarthworksSpec copy() {
        return fromNbt(kind, toNbt());
    }

    /** Run per block of rise as a number (1.5 for 1:1.5). */
    public double run() {
        return slopeRun / 10.0;
    }

    public String slopeLabel() {
        for (int i = 0; i < SLOPE_PRESETS.length; i++) {
            if (SLOPE_PRESETS[i] == slopeRun) {
                return SLOPE_LABELS[i];
            }
        }
        return slopeRun >= 10 ? "1:" + trim(run()) : trim(10.0 / slopeRun) + ":1";
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    public EarthworksSpec clamp() {
        trackReach = clamp(trackReach, BridgeSpec.MIN_REACH, BridgeSpec.MAX_REACH);
        previewTracks = clamp(previewTracks, 1, MAX_PREVIEW_TRACKS);
        shoulder = clamp(shoulder, 0, MAX_SHOULDER);
        topThickness = clamp(topThickness, 1, MAX_TOP);
        maxHeight = clamp(maxHeight, 2, MAX_FILL);
        maxDepth = clamp(maxDepth, 2, MAX_FILL);
        ditchWidth = clamp(ditchWidth, 0, MAX_DITCH_W);
        ditchDepth = clamp(ditchDepth, 1, MAX_DITCH_D);
        slopeRun = clamp(slopeRun, MIN_RUN, MAX_RUN);
        surfaceThickness = clamp(surfaceThickness, 1, MAX_SURFACE);
        wallTrigger = clamp(wallTrigger, MIN_TRIGGER, MAX_TRIGGER);
        wallThickness = clamp(wallThickness, 1, MAX_WALL);
        wallParapet = clamp(wallParapet, 0, MAX_PARAPET);
        benchEvery = clamp(benchEvery, MIN_BENCH_EVERY, MAX_BENCH_EVERY);
        benchWidth = clamp(benchWidth, 1, MAX_BENCH_W);
        blend = clamp(blend, 0, MAX_BLEND);
        corridor = clamp(corridor, 0, MAX_CORRIDOR);
        clearHeight = clamp(clearHeight, 2, MAX_CLEAR_H);
        clearBelow = clamp(clearBelow, 0, MAX_CLEAR_BELOW);
        terrainWidth = clamp(terrainWidth, 0, MAX_TERRAIN_W);
        terrainHeight = clamp(terrainHeight, 2, MAX_TERRAIN_H);
        if (trackMode == null) trackMode = TrackMode.AUTO;
        if (wallMode == null) wallMode = WallMode.AUTO;
        if (pickTarget == null || !pickTarget.usedBy(kind)) {
            pickTarget = firstLayer(kind);
        }
        for (Layer layer : Layer.values()) {
            String p = palette(layer);
            if (p.length() > 600) {
                palettes.put(layer, p.substring(0, 600));
            }
        }
        return this;
    }

    public static Layer firstLayer(Kind kind) {
        for (Layer layer : Layer.values()) {
            if (layer.usedBy(kind)) {
                return layer;
            }
        }
        return Layer.TOP;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ NBT

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putString("TrackMode", trackMode.name());
        n.putInt("TrackReach", trackReach);
        n.putInt("PreviewTracks", previewTracks);
        n.putInt("Shoulder", shoulder);
        n.putInt("TopThickness", topThickness);
        n.putInt("MaxHeight", maxHeight);
        n.putInt("MaxDepth", maxDepth);
        n.putInt("DitchWidth", ditchWidth);
        n.putInt("DitchDepth", ditchDepth);
        n.putBoolean("ProtectBuilds", protectBuilds);
        n.putBoolean("FellTrees", fellTrees);
        n.putInt("SlopeRun", slopeRun);
        n.putInt("SurfaceThickness", surfaceThickness);
        n.putString("WallMode", wallMode.name());
        n.putInt("WallTrigger", wallTrigger);
        n.putInt("WallThickness", wallThickness);
        n.putInt("WallParapet", wallParapet);
        n.putBoolean("Benches", benches);
        n.putInt("BenchEvery", benchEvery);
        n.putInt("BenchWidth", benchWidth);
        n.putInt("Blend", blend);
        n.putInt("Corridor", corridor);
        n.putInt("ClearHeight", clearHeight);
        n.putInt("ClearBelow", clearBelow);
        n.putBoolean("Trees", trees);
        n.putBoolean("Plants", plants);
        n.putBoolean("Snow", snow);
        n.putBoolean("Terrain", terrain);
        n.putInt("TerrainWidth", terrainWidth);
        n.putInt("TerrainHeight", terrainHeight);
        NbtCompound p = new NbtCompound();
        palettes.forEach((layer, text) -> p.putString(layer.name(), text));
        n.put("Palettes", p);
        n.putString("PickTarget", pickTarget.name());
        n.putBoolean("PickAdd", pickAdd);
        return n;
    }

    public static EarthworksSpec fromNbt(Kind kind, NbtCompound n) {
        EarthworksSpec s = new EarthworksSpec(kind);
        if (n == null) {
            return s.clamp();
        }
        s.trackMode = enumOr(TrackMode.class, n, "TrackMode", s.trackMode);
        s.trackReach = intOr(n, "TrackReach", s.trackReach);
        s.previewTracks = intOr(n, "PreviewTracks", s.previewTracks);
        s.shoulder = intOr(n, "Shoulder", s.shoulder);
        s.topThickness = intOr(n, "TopThickness", s.topThickness);
        s.maxHeight = intOr(n, "MaxHeight", s.maxHeight);
        s.maxDepth = intOr(n, "MaxDepth", s.maxDepth);
        s.ditchWidth = intOr(n, "DitchWidth", s.ditchWidth);
        s.ditchDepth = intOr(n, "DitchDepth", s.ditchDepth);
        s.protectBuilds = boolOr(n, "ProtectBuilds", s.protectBuilds);
        s.fellTrees = boolOr(n, "FellTrees", s.fellTrees);
        s.slopeRun = intOr(n, "SlopeRun", s.slopeRun);
        s.surfaceThickness = intOr(n, "SurfaceThickness", s.surfaceThickness);
        s.wallMode = enumOr(WallMode.class, n, "WallMode", s.wallMode);
        s.wallTrigger = intOr(n, "WallTrigger", s.wallTrigger);
        s.wallThickness = intOr(n, "WallThickness", s.wallThickness);
        s.wallParapet = intOr(n, "WallParapet", s.wallParapet);
        s.benches = boolOr(n, "Benches", s.benches);
        s.benchEvery = intOr(n, "BenchEvery", s.benchEvery);
        s.benchWidth = intOr(n, "BenchWidth", s.benchWidth);
        s.blend = intOr(n, "Blend", s.blend);
        s.corridor = intOr(n, "Corridor", s.corridor);
        s.clearHeight = intOr(n, "ClearHeight", s.clearHeight);
        s.clearBelow = intOr(n, "ClearBelow", s.clearBelow);
        s.trees = boolOr(n, "Trees", s.trees);
        s.plants = boolOr(n, "Plants", s.plants);
        s.snow = boolOr(n, "Snow", s.snow);
        s.terrain = boolOr(n, "Terrain", s.terrain);
        s.terrainWidth = intOr(n, "TerrainWidth", s.terrainWidth);
        s.terrainHeight = intOr(n, "TerrainHeight", s.terrainHeight);
        if (n.contains("Palettes")) {
            NbtCompound p = n.getCompound("Palettes");
            for (Layer layer : Layer.values()) {
                if (p.contains(layer.name())) {
                    s.palettes.put(layer, p.getString(layer.name()));
                }
            }
        }
        s.pickTarget = enumOr(Layer.class, n, "PickTarget", s.pickTarget);
        s.pickAdd = boolOr(n, "PickAdd", s.pickAdd);
        return s.clamp();
    }

    public static EarthworksSpec read(Kind kind, ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return fromNbt(kind, nbt != null && nbt.contains(TAG) ? nbt.getCompound(TAG) : null);
    }

    public void write(ItemStack stack) {
        stack.getOrCreateNbt().put(TAG, clamp().toNbt());
    }

    private static int intOr(NbtCompound n, String key, int fallback) {
        return n.contains(key) ? n.getInt(key) : fallback;
    }

    private static boolean boolOr(NbtCompound n, String key, boolean fallback) {
        return n.contains(key) ? n.getBoolean(key) : fallback;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, NbtCompound n, String key, E fallback) {
        if (!n.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, n.getString(key).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
