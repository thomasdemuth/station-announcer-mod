package com.stationannouncer.mtr;

import com.stationannouncer.mtr.EarthworksSpec.Kind;
import com.stationannouncer.mtr.EarthworksSpec.Layer;
import com.stationannouncer.mtr.EarthworksSpec.WallMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Built-in starting points for the three earthworks tools (load into the item, then tweak). */
public final class EarthworksPresets {
    public record Preset(String name, Kind kind, EarthworksSpec spec) {
    }

    public static final List<Preset> BUILTIN = new ArrayList<>();

    static {
        // ---------------------------------------------------- embankments
        add("Grassy embankment", Kind.EMBANKMENT, s -> {
        });
        add("Ballast shoulders, steep", Kind.EMBANKMENT, s -> {
            s.shoulder = 3;
            s.topThickness = 2;
            s.slopeRun = 10;
            s.palettes.put(Layer.SLOPE, "4 minecraft:gravel, 2 minecraft:andesite, 1 minecraft:cobblestone");
        });
        add("Riprap causeway (over water)", Kind.EMBANKMENT, s -> {
            s.slopeRun = 15;
            s.palettes.put(Layer.SLOPE, "3 minecraft:cobblestone, 2 minecraft:stone, 1 minecraft:mossy_cobblestone");
            s.palettes.put(Layer.TOE, "2 minecraft:mossy_cobblestone, 1 minecraft:cobblestone");
            s.palettes.put(Layer.CORE, "3 minecraft:stone, 1 minecraft:cobblestone");
            s.maxHeight = 40;
        });
        add("Stone retaining walls", Kind.EMBANKMENT, s -> {
            s.wallMode = WallMode.ALWAYS;
            s.wallParapet = 1;
            s.palettes.put(Layer.WALL, "4 minecraft:stone_bricks, 1 minecraft:mossy_stone_bricks, 1 minecraft:cracked_stone_bricks");
        });
        add("Concrete viaduct fill", Kind.EMBANKMENT, s -> {
            s.wallMode = WallMode.ALWAYS;
            s.blend = 0;
            s.palettes.put(Layer.TOP, "minecraft:gray_concrete");
            s.palettes.put(Layer.WALL, "minecraft:light_gray_concrete");
        });
        add("Desert sand", Kind.EMBANKMENT, s -> {
            s.slopeRun = 20;
            s.palettes.put(Layer.TOP, "3 minecraft:gravel, 1 minecraft:sandstone");
            s.palettes.put(Layer.CORE, "3 minecraft:sandstone, 1 minecraft:sand");
            s.palettes.put(Layer.SLOPE, "4 minecraft:sand, 1 minecraft:sandstone");
        });
        add("Tall with benches", Kind.EMBANKMENT, s -> {
            s.benches = true;
            s.benchEvery = 6;
            s.benchWidth = 2;
            s.slopeRun = 15;
            s.wallMode = WallMode.NEVER;
            s.maxHeight = 48;
        });

        // -------------------------------------------------------- trenches
        add("Rock cutting", Kind.TRENCH, s -> {
            s.slopeRun = 5;
            s.wallMode = WallMode.NEVER;
            s.benches = true;
            s.benchEvery = 8;
            s.benchWidth = 1;
        });
        add("Grassy cutting", Kind.TRENCH, s -> {
            s.slopeRun = 15;
            s.palettes.put(Layer.FACE, "6 minecraft:grass_block, 1 minecraft:coarse_dirt, 1 minecraft:dirt");
        });
        add("Brick-walled cutting", Kind.TRENCH, s -> {
            s.wallMode = WallMode.ALWAYS;
            s.wallParapet = 1;
            s.palettes.put(Layer.WALL, "5 minecraft:bricks, 1 minecraft:stone_bricks");
        });
        add("Concrete U-trough", Kind.TRENCH, s -> {
            s.wallMode = WallMode.ALWAYS;
            s.ditchWidth = 0;
            s.blend = 0;
            s.wallParapet = 1;
            s.palettes.put(Layer.FLOOR, "minecraft:gray_concrete");
            s.palettes.put(Layer.WALL, "minecraft:light_gray_concrete");
        });
        add("Badlands cut", Kind.TRENCH, s -> {
            s.slopeRun = 10;
            s.benches = true;
            s.benchEvery = 5;
            s.benchWidth = 2;
            s.palettes.put(Layer.FACE, "3 minecraft:terracotta, 2 minecraft:orange_terracotta, 1 minecraft:red_sand");
        });

        // ---------------------------------------------------------- clearer
        add("Full clearing", Kind.CLEARER, s -> {
        });
        add("Light trim (plants and snow)", Kind.CLEARER, s -> {
            s.trees = false;
            s.corridor = 2;
            s.clearHeight = 6;
        });
        add("Wide forest right-of-way", Kind.CLEARER, s -> {
            s.corridor = 12;
            s.clearHeight = 40;
        });
        add("Clearance envelope (cut terrain)", Kind.CLEARER, s -> {
            s.corridor = 4;
            s.terrain = true;
            s.terrainWidth = 2;
            s.terrainHeight = 6;
        });
    }

    private EarthworksPresets() {
    }

    private static void add(String name, Kind kind, Consumer<EarthworksSpec> setup) {
        EarthworksSpec spec = new EarthworksSpec(kind);
        setup.accept(spec);
        BUILTIN.add(new Preset(name, kind, spec.clamp()));
    }

    public static List<Preset> forKind(Kind kind) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : BUILTIN) {
            if (p.kind() == kind) {
                out.add(p);
            }
        }
        return out;
    }

    public static EarthworksSpec byName(Kind kind, String name) {
        for (Preset p : forKind(kind)) {
            if (p.name().equalsIgnoreCase(name)) {
                return p.spec().copy();
            }
        }
        return null;
    }
}
