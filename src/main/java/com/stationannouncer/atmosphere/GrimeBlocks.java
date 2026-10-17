package com.stationannouncer.atmosphere;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;

import java.util.List;

/**
 * The grime decal family (ATMOSPHERE_PLAN.md §2.2). Every block is a {@link GrimeDecalBlock}
 * (vanilla multiface: properties {@code north east south west up down}, each = "a decal on that
 * side of the cell"; {@code down} is the floor, {@code up} the ceiling).
 *
 * <pre>
 *   id                     faces allowed          render layer
 *   grime_dust             all six                translucent
 *   grime_soot             all six                translucent
 *   grime_water_stain      all six                translucent
 *   grime_rust_streak      4 walls + up           translucent
 *   grime_efflorescence    4 walls + up           translucent
 *   grime_puddle           down only              translucent
 *   grime_gum              down only              translucent
 *   grime_oil              down only              translucent
 *   cobweb_corner          all six                cutout
 * </pre>
 *
 * Assets: {@code python3 tools/gen_grime_assets.py} (never hand-edit the output).
 */
public final class GrimeBlocks {
    private GrimeBlocks() {
    }

    public static final GrimeDecalBlock DUST = new GrimeDecalBlock.AnySurface(decal(BlockSoundGroup.SAND));
    public static final GrimeDecalBlock SOOT = new GrimeDecalBlock.AnySurface(decal(BlockSoundGroup.SAND));
    public static final GrimeDecalBlock WATER_STAIN = new GrimeDecalBlock.AnySurface(decal(BlockSoundGroup.GLOW_LICHEN));
    public static final GrimeDecalBlock RUST_STREAK = new GrimeDecalBlock.WallsAndCeiling(decal(BlockSoundGroup.GLOW_LICHEN));
    public static final GrimeDecalBlock EFFLORESCENCE = new GrimeDecalBlock.WallsAndCeiling(decal(BlockSoundGroup.CALCITE));
    public static final GrimeDecalBlock PUDDLE = new GrimeDecalBlock.FloorOnly(decal(BlockSoundGroup.MUD));
    public static final GrimeDecalBlock GUM = new GrimeDecalBlock.FloorOnly(decal(BlockSoundGroup.HONEY));
    public static final GrimeDecalBlock OIL = new GrimeDecalBlock.FloorOnly(decal(BlockSoundGroup.HONEY));
    public static final GrimeDecalBlock COBWEB_CORNER = new GrimeDecalBlock.AnySurface(decal(BlockSoundGroup.WOOL));

    /** Every grime decal, in creative-tab order. */
    public static final List<GrimeDecalBlock> ALL = List.of(
            DUST, SOOT, WATER_STAIN, RUST_STREAK, EFFLORESCENCE, PUDDLE, GUM, OIL, COBWEB_CORNER);

    /** All but the cobweb render translucent; the cobweb is cutout. */
    public static final List<GrimeDecalBlock> TRANSLUCENT = List.of(
            DUST, SOOT, WATER_STAIN, RUST_STREAK, EFFLORESCENCE, PUDDLE, GUM, OIL);

    private static AbstractBlock.Settings decal(BlockSoundGroup sounds) {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.CLEAR)
                .replaceable()
                .noCollision()
                .breakInstantly()
                .nonOpaque()
                .sounds(sounds)
                .pistonBehavior(PistonBehavior.DESTROY);
    }

    public static void register() {
        AtmosphereRegistry.block("grime_dust", DUST);
        AtmosphereRegistry.block("grime_soot", SOOT);
        AtmosphereRegistry.block("grime_water_stain", WATER_STAIN);
        AtmosphereRegistry.block("grime_rust_streak", RUST_STREAK);
        AtmosphereRegistry.block("grime_efflorescence", EFFLORESCENCE);
        AtmosphereRegistry.block("grime_puddle", PUDDLE);
        AtmosphereRegistry.block("grime_gum", GUM);
        AtmosphereRegistry.block("grime_oil", OIL);
        AtmosphereRegistry.block("cobweb_corner", COBWEB_CORNER);
    }
}
