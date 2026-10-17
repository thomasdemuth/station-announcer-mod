package com.stationannouncer.atmosphere;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Litter and trash (ATMOSPHERE_PLAN.md §1C / §2.3): floor litter, garbage
 * bag heaps, cardboard, the station trash can, a swept pile and newspaper
 * bundles. Assets come from tools/gen_litter_assets.py.
 */
public final class LitterBlocks {
    public static LitterBlock LITTER;
    public static TrashBagsBlock TRASH_BAGS;
    public static TrashCardboardPileBlock CARDBOARD_PILE;
    public static TrashCanBlock TRASH_CAN;
    public static LitterSweptPileBlock SWEPT_PILE;
    public static LitterNewspaperStackBlock NEWSPAPER_STACK;

    private LitterBlocks() {
    }

    public static void register() {
        LITTER = AtmosphereRegistry.block("litter", new LitterBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.WHITE_GRAY).noCollision().breakInstantly().replaceable().nonOpaque()
                .sounds(BlockSoundGroup.AZALEA_LEAVES).pistonBehavior(PistonBehavior.DESTROY)));
        TRASH_BAGS = AtmosphereRegistry.block("trash_bags", new TrashBagsBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.BLACK).strength(0.3f).nonOpaque()
                .sounds(BlockSoundGroup.WOOL).pistonBehavior(PistonBehavior.DESTROY)));
        CARDBOARD_PILE = AtmosphereRegistry.block("cardboard_pile", new TrashCardboardPileBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.BROWN).strength(0.3f).nonOpaque()
                .sounds(BlockSoundGroup.SCAFFOLDING).pistonBehavior(PistonBehavior.DESTROY)));
        TRASH_CAN = AtmosphereRegistry.block("trash_can", new TrashCanBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.GRAY).strength(1.5f, 4.0f).requiresTool().nonOpaque()
                .sounds(BlockSoundGroup.LANTERN)));
        SWEPT_PILE = AtmosphereRegistry.block("swept_pile", new LitterSweptPileBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.GRAY).noCollision().breakInstantly().replaceable().nonOpaque()
                .sounds(BlockSoundGroup.SAND).pistonBehavior(PistonBehavior.DESTROY)));
        NEWSPAPER_STACK = AtmosphereRegistry.block("newspaper_stack", new LitterNewspaperStackBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.WHITE_GRAY).strength(0.4f).nonOpaque()
                .sounds(BlockSoundGroup.WOOL).pistonBehavior(PistonBehavior.DESTROY)));
    }
}
