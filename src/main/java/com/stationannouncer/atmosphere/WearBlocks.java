package com.stationannouncer.atmosphere;

import com.stationannouncer.block.ConcreteFloorBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.Instrument;
import net.minecraft.sound.BlockSoundGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The WEAR VARIANTS family (ATMOSPHERE_PLAN.md §1 D / phase 5): damaged copies of the station
 * surfaces that drop straight into an existing wall or floor. Each one is drawn on the SAME grid,
 * with the SAME base colours, as the block it replaces - only the damage differs - so a builder
 * can swap a few blocks of a finished wall without a seam.
 *
 * <pre>
 *   id                                   copies                         damage
 *   subway_tile_wall_cracked             subway_tile_wall               hairline cracks, chipped corners
 *   subway_tile_wall_missing             subway_tile_wall               missing tiles, mortar bed showing
 *   subway_tile_wall_patched             subway_tile_wall               mismatched replacement tiles
 *   subway_tile_wall_stained             subway_tile_wall               dirty grout, drips, grimy lower third
 *   platform_tile_floor_cracked          platform_tile_floor            cracked/chipped/missing floor tiles
 *   platform_concrete_floor_N_cracked    platform_concrete_floor_N      cracks + newer-concrete patches
 * </pre>
 *
 * <p>All are plain full opaque cubes. Variety is vanilla weighted blockstate variants (works
 * under Sodium without Indium). Walls never rotate their side texture (drips must run down), so
 * their variants are separate textures, permuted across the four sides. The tile floor rotates
 * freely like the original. The concrete floors are {@link ConcreteFloorBlock}s - the same class
 * as the originals - so they carry the same world-grid {@code joint_north}/{@code joint_west}
 * properties and a cracked slab sits seamlessly in a run.
 *
 * <p>Assets: {@code python3 tools/gen_wear_assets.py} (never hand-edit the output).
 */
public final class WearBlocks {
    private WearBlocks() {
    }

    /** Matches {@code SubwayWalls.tile()} so the swap feels identical. */
    private static AbstractBlock.Settings wall() {
        return AbstractBlock.Settings.create().mapColor(MapColor.OFF_WHITE).instrument(Instrument.BASEDRUM)
                .strength(1.5f).sounds(BlockSoundGroup.STONE);
    }

    /** Matches {@code ModContent.floorSettings()}. */
    private static AbstractBlock.Settings floor() {
        return AbstractBlock.Settings.create().mapColor(MapColor.STONE_GRAY).instrument(Instrument.BASEDRUM)
                .strength(1.6f, 6.0f).sounds(BlockSoundGroup.STONE);
    }

    public static final Block SUBWAY_TILE_WALL_CRACKED = new Block(wall());
    public static final Block SUBWAY_TILE_WALL_MISSING = new Block(wall());
    public static final Block SUBWAY_TILE_WALL_PATCHED = new Block(wall());
    public static final Block SUBWAY_TILE_WALL_STAINED = new Block(wall());

    public static final Block PLATFORM_TILE_FLOOR_CRACKED = new Block(floor());

    /** Slab widths, in step with {@code ModContent.SLAB_WIDTHS} and the array below. */
    public static final int[] SLAB_WIDTHS = {1, 2, 3, 4};

    /** Cracked concrete, one per slab width; ids {@code platform_concrete_floor_<N>_cracked}. */
    public static final ConcreteFloorBlock[] PLATFORM_CONCRETE_FLOOR_CRACKED = {
            new ConcreteFloorBlock(floor(), 1, false),
            new ConcreteFloorBlock(floor(), 2, false),
            new ConcreteFloorBlock(floor(), 3, false),
            new ConcreteFloorBlock(floor(), 4, false)};

    /** The four damaged tile walls. */
    public static final List<Block> WALLS = List.of(
            SUBWAY_TILE_WALL_CRACKED, SUBWAY_TILE_WALL_MISSING, SUBWAY_TILE_WALL_PATCHED, SUBWAY_TILE_WALL_STAINED);

    /** The damaged floors (tile + every concrete width). */
    public static final List<Block> FLOORS;

    /** Every wear block, in creative-tab order. */
    public static final List<Block> ALL;

    static {
        List<Block> floors = new ArrayList<>();
        floors.add(PLATFORM_TILE_FLOOR_CRACKED);
        Collections.addAll(floors, PLATFORM_CONCRETE_FLOOR_CRACKED);
        FLOORS = List.copyOf(floors);
        List<Block> all = new ArrayList<>(WALLS);
        all.addAll(floors);
        ALL = List.copyOf(all);
    }

    public static void register() {
        AtmosphereRegistry.block("subway_tile_wall_cracked", SUBWAY_TILE_WALL_CRACKED);
        AtmosphereRegistry.block("subway_tile_wall_missing", SUBWAY_TILE_WALL_MISSING);
        AtmosphereRegistry.block("subway_tile_wall_patched", SUBWAY_TILE_WALL_PATCHED);
        AtmosphereRegistry.block("subway_tile_wall_stained", SUBWAY_TILE_WALL_STAINED);
        AtmosphereRegistry.block("platform_tile_floor_cracked", PLATFORM_TILE_FLOOR_CRACKED);
        for (int i = 0; i < SLAB_WIDTHS.length; i++) {
            AtmosphereRegistry.block("platform_concrete_floor_" + SLAB_WIDTHS[i] + "_cracked",
                    PLATFORM_CONCRETE_FLOOR_CRACKED[i]);
        }
    }
}
