package com.stationannouncer.atmosphere;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared registration helpers for the atmosphere families (grime, litter,
 * construction props, tools). Depends on nothing else in the package so each
 * family compiles on its own. Everything lands in the "Baker City:
 * Atmosphere" tab in registration order.
 */
public final class AtmosphereRegistry {
    public static final List<ItemStack> ENTRIES = new ArrayList<>();

    private AtmosphereRegistry() {
    }

    public static <B extends Block> B block(String id, B block) {
        return block(id, block, b -> new BlockItem(b, new Item.Settings()));
    }

    public static <B extends Block> B block(String id, B block, Function<B, ? extends Item> itemFactory) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(id), block);
        Item item = itemFactory.apply(block);
        if (item != null) {
            Registry.register(Registries.ITEM, StationAnnouncer.id(id), item);
            ENTRIES.add(new ItemStack(item));
        }
        return block;
    }

    public static <T extends Item> T item(String id, T item) {
        Registry.register(Registries.ITEM, StationAnnouncer.id(id), item);
        ENTRIES.add(new ItemStack(item));
        return item;
    }
}
