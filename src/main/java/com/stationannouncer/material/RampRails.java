package com.stationannouncer.material;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

import java.util.LinkedHashMap;
import java.util.Map;

/** The six ramp rails (one block per style) and their items; called from {@link MaterialBlocks#register()}. */
public final class RampRails {
    /** id → block, in creative-tab order. */
    public static final Map<String, RampRailBlock> BLOCKS = new LinkedHashMap<>();

    public static final RampRailBlock STANDING = rail("ramp_handrail_standing", RampRailBlock.Style.STANDING, false);
    public static final RampRailBlock WALL = rail("ramp_handrail_wall", RampRailBlock.Style.WALL, false);
    public static final RampRailBlock DOUBLE = rail("ramp_handrail_double", RampRailBlock.Style.DOUBLE, false);
    public static final RampRailBlock FLOATING = rail("ramp_handrail_floating", RampRailBlock.Style.FLOATING, false);
    public static final RampRailBlock GLASS = rail("ramp_railing_glass", RampRailBlock.Style.GLASS, true);
    public static final RampRailBlock PICKETS = rail("ramp_railing_pickets", RampRailBlock.Style.PICKETS, false);

    private RampRails() {
    }

    private static RampRailBlock rail(String id, RampRailBlock.Style style, boolean glass) {
        RampRailBlock block = new RampRailBlock(AbstractBlock.Settings.create().strength(1.5f)
                .sounds(glass ? BlockSoundGroup.GLASS : BlockSoundGroup.METAL).nonOpaque(), style);
        BLOCKS.put(id, block);
        return block;
    }

    static void register() {
        BLOCKS.forEach((id, block) -> {
            Registry.register(Registries.BLOCK, StationAnnouncer.id(id), block);
            Item item = Registry.register(Registries.ITEM, StationAnnouncer.id(id), new RampRailItem(block, new Item.Settings()));
            ModContent.DECORATION_ENTRIES.add(item);
        });
    }
}
