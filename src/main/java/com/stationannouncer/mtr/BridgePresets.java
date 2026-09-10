package com.stationannouncer.mtr;

import java.util.List;

/**
 * The Bridge Creator's built-in presets: complete specs a player can start
 * from in the settings screen (their own saved presets sit beside these on
 * the client). Names are shown as-is.
 */
public final class BridgePresets {
    public record Preset(String name, BridgeSpec spec) {
    }

    public static final List<Preset> BUILTIN = List.of(
            new Preset("Stone viaduct", stoneViaduct()),
            new Preset("Brick arches", brickArches()),
            new Preset("Steel girder", steelGirder()),
            new Preset("Concrete box girder", concreteBox()),
            new Preset("Modern viaduct", modernViaduct()),
            new Preset("Wooden trestle", woodenTrestle()),
            new Preset("NYC steel el", nycEl()),
            new Preset("Piers only", piersOnly()),
            new Preset("Deck + railing only", deckOnly()));

    private BridgePresets() {
    }

    public static BridgeSpec byName(String name) {
        for (Preset p : BUILTIN) {
            if (p.name.equals(name)) {
                return p.spec.copy();
            }
        }
        return null;
    }

    private static BridgeSpec base(String material) {
        BridgeSpec s = new BridgeSpec();
        for (BridgeSpec.Slot slot : BridgeSpec.Slot.values()) {
            s.setMaterial(slot, slot == BridgeSpec.Slot.EDGE ? "" : material);
        }
        return s;
    }

    private static BridgeSpec stoneViaduct() {
        BridgeSpec s = base("minecraft:stone_bricks");
        s.railingMaterial = "minecraft:stone_brick_wall";
        s.girderStyle = BridgeSpec.GirderStyle.NONE;
        s.pierStyle = BridgeSpec.PierStyle.WALL;
        s.pierInset = 0;
        s.pierSpacing = 9;
        s.pierThickness = 1;
        s.archStyle = BridgeSpec.ArchStyle.FILLED;
        s.archRise = 4;
        s.overhang = 2;
        return s.clamp();
    }

    private static BridgeSpec brickArches() {
        BridgeSpec s = base("minecraft:bricks");
        s.railingMaterial = "minecraft:brick_wall";
        s.edgeMaterial = "minecraft:stone_bricks";
        s.girderStyle = BridgeSpec.GirderStyle.NONE;
        s.pierStyle = BridgeSpec.PierStyle.WALL;
        s.pierInset = 0;
        s.pierSpacing = 11;
        s.pierThickness = 2;
        s.archStyle = BridgeSpec.ArchStyle.FILLED;
        s.archRise = 5;
        s.footing = true;
        s.footingMaterial = "minecraft:stone_bricks";
        return s.clamp();
    }

    private static BridgeSpec steelGirder() {
        BridgeSpec s = base("minecraft:iron_block");
        s.deckMaterial = "minecraft:polished_deepslate";
        s.edgeMaterial = "minecraft:iron_block";
        s.girderStyle = BridgeSpec.GirderStyle.EDGES;
        s.girderDepth = 2;
        s.railingMaterial = "minecraft:iron_bars";
        s.railingHeight = 1;
        s.pierStyle = BridgeSpec.PierStyle.EDGES;
        s.pierMaterial = "minecraft:light_gray_concrete";
        s.pierInset = 1;
        s.pierSpacing = 12;
        s.pierCap = true;
        s.capMaterial = "minecraft:iron_block";
        s.archStyle = BridgeSpec.ArchStyle.NONE;
        return s.clamp();
    }

    private static BridgeSpec concreteBox() {
        BridgeSpec s = base("minecraft:gray_concrete");
        s.deckMaterial = "minecraft:light_gray_concrete";
        s.edgeMaterial = "minecraft:white_concrete";
        s.girderStyle = BridgeSpec.GirderStyle.FULL;
        s.girderDepth = 2;
        s.railingMaterial = "minecraft:light_gray_concrete_powder";
        s.railingHeight = 1;
        s.railingMaterial = "minecraft:andesite_wall";
        s.pierStyle = BridgeSpec.PierStyle.CENTRE;
        s.pierThickness = 2;
        s.pierSpacing = 14;
        s.pierCap = true;
        s.capMaterial = "minecraft:gray_concrete";
        return s.clamp();
    }

    private static BridgeSpec modernViaduct() {
        BridgeSpec s = base("minecraft:light_gray_concrete");
        s.deckMaterial = "minecraft:smooth_stone";
        s.edgeMaterial = "minecraft:white_concrete";
        s.girderStyle = BridgeSpec.GirderStyle.EDGES;
        s.girderDepth = 1;
        s.railingMaterial = "minecraft:light_gray_stained_glass_pane";
        s.railingHeight = 2;
        s.pierStyle = BridgeSpec.PierStyle.CENTRE;
        s.pierThickness = 2;
        s.pierSpacing = 10;
        s.pierCap = true;
        s.capMaterial = "minecraft:white_concrete";
        s.footing = true;
        return s.clamp();
    }

    private static BridgeSpec woodenTrestle() {
        BridgeSpec s = base("minecraft:spruce_log");
        s.deckMaterial = "minecraft:spruce_planks";
        s.girderStyle = BridgeSpec.GirderStyle.TRACKS;
        s.girderMaterial = "minecraft:spruce_log";
        s.girderDepth = 1;
        s.railingMaterial = "minecraft:spruce_fence";
        s.overhang = 1;
        s.pierStyle = BridgeSpec.PierStyle.TWIN;
        s.pierMaterial = "minecraft:spruce_log";
        s.pierSpacing = 4;
        s.pierCap = true;
        s.capMaterial = "minecraft:spruce_planks";
        return s.clamp();
    }

    private static BridgeSpec nycEl() {
        BridgeSpec s = base("station_announcer:el_girder_plate");
        s.deckMaterial = "station_announcer:el_track_deck";
        s.girderStyle = BridgeSpec.GirderStyle.EDGES;
        s.girderMaterial = "station_announcer:el_girder_plate";
        s.girderDepth = 1;
        s.railing = false;
        s.overhang = 1;
        s.pierStyle = BridgeSpec.PierStyle.EDGES;
        s.pierMaterial = "station_announcer:el_street_column";
        s.pierInset = 0;
        s.pierSpacing = 8;
        s.pierCap = true;
        s.capMaterial = "station_announcer:el_girder_plate";
        return s.clamp();
    }

    private static BridgeSpec piersOnly() {
        BridgeSpec s = base("minecraft:stone_bricks");
        s.deck = false;
        s.girderStyle = BridgeSpec.GirderStyle.NONE;
        s.railing = false;
        s.pierStyle = BridgeSpec.PierStyle.EDGES;
        s.pierInset = 1;
        s.pierSpacing = 8;
        return s.clamp();
    }

    private static BridgeSpec deckOnly() {
        BridgeSpec s = base("minecraft:stone_bricks");
        s.railingMaterial = "minecraft:stone_brick_wall";
        s.girderStyle = BridgeSpec.GirderStyle.NONE;
        s.pierStyle = BridgeSpec.PierStyle.NONE;
        return s.clamp();
    }
}
