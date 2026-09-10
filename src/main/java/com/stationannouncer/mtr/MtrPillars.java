package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/**
 * Registration for the rail-structure creator items (MTR-only, like the PIDS
 * blocks): the configurable Pillar Creator and El Structure Creator (width,
 * spacing and style live in item NBT, edited in a settings screen with a 3D
 * preview), four Railing Creators matching the MTR bridge widths, and three
 * Viaduct Creators (deck + girders + pillars in one pass). The ten fixed
 * {@code pillar_creator_<width>x<spacing>} variants stay registered but
 * hidden so existing items keep working.
 */
public final class MtrPillars {
    /** {width, spacing} of the legacy fixed pillar creator variants. */
    public static final int[][] SIZES = {
            {1, 4}, {1, 6}, {1, 8},
            {3, 4}, {3, 6}, {3, 8},
            {5, 4}, {5, 6}, {5, 8}, {5, 10},
    };

    /** Railing creator widths (match MTR's bridge creator widths). */
    public static final int[] RAILING_WIDTHS = {3, 5, 7, 9};

    /** Viaduct creator deck widths. */
    public static final int[] VIADUCT_WIDTHS = {3, 5, 7};

    /** C2S: the creator settings screen saves (hand + width + spacing + lattice + girder). */
    public static final Identifier UPDATE_CREATOR_C2S = StationAnnouncer.id("update_creator");
    /** C2S: the bridge creator screen saves its whole spec (hand + NBT). */
    public static final Identifier UPDATE_BRIDGE_C2S = StationAnnouncer.id("update_bridge");
    /** C2S: bridge creator actions from the screen (hand + action byte, see {@link #BRIDGE_BUILD}). */
    public static final Identifier BRIDGE_ACTION_C2S = StationAnnouncer.id("bridge_action");
    public static final byte BRIDGE_BUILD = 0;
    public static final byte BRIDGE_CLEAR_TRACKS = 1;
    public static final byte BRIDGE_UNDO = 2;

    /** Opens the settings screen for the creator in the given hand; installed by the client module. */
    public static java.util.function.Consumer<Hand> SETTINGS_OPENER = hand -> {
    };

    public static ItemPillarCreator PILLAR_CREATOR;
    public static ItemElStructureCreator EL_STRUCTURE_CREATOR;
    public static ItemBridgeCreator BRIDGE_CREATOR;

    private MtrPillars() {
    }

    public static void register() {
        PILLAR_CREATOR = new ItemPillarCreator(3, 6);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pillar_creator"), PILLAR_CREATOR);
        ModContent.OPERATIONS_ENTRIES.add(PILLAR_CREATOR);
        EL_STRUCTURE_CREATOR = new ItemElStructureCreator();
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_structure_creator"), EL_STRUCTURE_CREATOR);
        ModContent.OPERATIONS_ENTRIES.add(EL_STRUCTURE_CREATOR);
        BRIDGE_CREATOR = new ItemBridgeCreator();
        Registry.register(Registries.ITEM, StationAnnouncer.id("bridge_creator"), BRIDGE_CREATOR);
        ModContent.OPERATIONS_ENTRIES.add(BRIDGE_CREATOR);
        for (int[] size : SIZES) {
            // legacy fixed variants: registered (items in chests keep working), not in the tab
            Registry.register(Registries.ITEM,
                    StationAnnouncer.id("pillar_creator_" + size[0] + "x" + size[1]),
                    new ItemPillarCreator(size[0], size[1]));
        }
        for (int width : RAILING_WIDTHS) {
            ItemRailingCreator item = new ItemRailingCreator(width);
            Registry.register(Registries.ITEM, StationAnnouncer.id("railing_creator_" + width), item);
            ModContent.OPERATIONS_ENTRIES.add(item);
        }
        for (int width : VIADUCT_WIDTHS) {
            ItemViaductCreator item = new ItemViaductCreator(width);
            Registry.register(Registries.ITEM, StationAnnouncer.id("viaduct_creator_" + width), item);
            ModContent.OPERATIONS_ENTRIES.add(item);
        }

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_CREATOR_C2S, (server, player, handler, buf, responseSender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            int width = buf.readInt();
            int spacing = buf.readInt();
            boolean lattice = buf.readBoolean();
            boolean girder = buf.readBoolean();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof ItemPillarCreator || stack.getItem() instanceof ItemElStructureCreator) {
                    CreatorSettings.write(stack, width, spacing, lattice, girder);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_BRIDGE_C2S, (server, player, handler, buf, responseSender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            net.minecraft.nbt.NbtCompound nbt = buf.readNbt();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof ItemBridgeCreator && nbt != null) {
                    BridgeSpec.fromNbt(nbt).write(stack);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(BRIDGE_ACTION_C2S, (server, player, handler, buf, responseSender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            byte action = buf.readByte();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (!(stack.getItem() instanceof ItemBridgeCreator)) {
                    return;
                }
                switch (action) {
                    case BRIDGE_BUILD -> BridgeService.buildManual(player, stack);
                    case BRIDGE_CLEAR_TRACKS -> BridgeService.clearManualTracks(stack);
                    case BRIDGE_UNDO -> BridgeService.undo(player);
                    default -> {
                    }
                }
            });
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> BridgeService.clearAll());
        BridgeCommand.register();
    }
}
