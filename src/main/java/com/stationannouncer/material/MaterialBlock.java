package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * A block that wears any full block's texture through {@link MaterialPalette}
 * (the material ramp, the material stairs). The helpers keep the material with
 * the block both ways: placing stamps the item's material into the state,
 * breaking / picking gives back an item carrying it.
 */
public interface MaterialBlock {
    /** The item form of this block carrying {@code state}'s material. */
    default ItemStack stackFor(Block self, BlockState state, World world) {
        boolean client = world != null && world.isClient;
        return MaterialPalette.stackWith(self, MaterialPalette.materialAt(MaterialPalette.slot(state), client));
    }

    /** The placement state with the placing item's material. */
    static BlockState withPlacedMaterial(BlockState state, ItemPlacementContext context) {
        if (state == null) {
            return null;
        }
        String material = MaterialPalette.materialOf(context.getStack());
        return MaterialPalette.withSlot(state, MaterialPalette.slotFor(material, context.getWorld().isClient));
    }
}
