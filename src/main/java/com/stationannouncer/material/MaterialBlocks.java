package com.stationannouncer.material;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Material ramps and stairs: registration, the picker's packets and the
 * {@link MaterialPalette} lifecycle. Common code (no MTR); the client half is
 * {@code client.material.MaterialClient}.
 */
public final class MaterialBlocks {
    public static final MaterialRampBlock MATERIAL_RAMP =
            new MaterialRampBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
    public static final MaterialStairsBlock MATERIAL_STAIRS =
            new MaterialStairsBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
    public static final Item MATERIAL_RAMP_ITEM = new MaterialBlockItem(MATERIAL_RAMP, new Item.Settings());
    public static final Item MATERIAL_STAIRS_ITEM = new MaterialBlockItem(MATERIAL_STAIRS, new Item.Settings());

    /** C2S: the picker set the held item's material (hand ordinal, material). */
    public static final Identifier SET_ITEM_MATERIAL_C2S = StationAnnouncer.id("set_item_material");
    /** C2S: the picker retextured a placed block (pos, material, whole connected run). */
    public static final Identifier SET_BLOCK_MATERIAL_C2S = StationAnnouncer.id("set_block_material");
    private static final int MAX_RUN = 1024;

    /** Client hooks, installed by MaterialClient; no-ops on a dedicated server. */
    public static Consumer<Hand> PICKER_OPENER = hand -> {
    };
    public static Consumer<BlockPos> BLOCK_PICKER_OPENER = pos -> {
    };

    private MaterialBlocks() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, StationAnnouncer.id("material_ramp"), MATERIAL_RAMP);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("material_stairs"), MATERIAL_STAIRS);
        Registry.register(Registries.ITEM, StationAnnouncer.id("material_ramp"), MATERIAL_RAMP_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("material_stairs"), MATERIAL_STAIRS_ITEM);
        ModContent.DECORATION_ENTRIES.add(MATERIAL_RAMP_ITEM);
        ModContent.DECORATION_ENTRIES.add(MATERIAL_STAIRS_ITEM);
        RampRails.register();

        ServerLifecycleEvents.SERVER_STARTED.register(MaterialPalette::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MaterialPalette.unload());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sender.sendPacket(MaterialPalette.PALETTE_S2C, MaterialPalette.syncBuf()));

        ServerPlayNetworking.registerGlobalReceiver(SET_ITEM_MATERIAL_C2S, (server, player, handler, buf, responseSender) -> {
            Hand hand = buf.readByte() == 1 ? Hand.OFF_HAND : Hand.MAIN_HAND;
            String material = buf.readString(MaterialPalette.MAX_MATERIAL_LENGTH);
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof MaterialBlockItem && MaterialPalette.parse(material) != null) {
                    MaterialPalette.setMaterial(stack, material);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SET_BLOCK_MATERIAL_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String material = buf.readString(MaterialPalette.MAX_MATERIAL_LENGTH);
            boolean run = buf.readBoolean();
            server.execute(() -> retexture(player, pos, material, run));
        });
    }

    private static void retexture(ServerPlayerEntity player, BlockPos pos, String material, boolean run) {
        ServerWorld world = player.getServerWorld();
        BlockState start = world.getBlockState(pos);
        if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0 || !world.canPlayerModifyAt(player, pos)
                || !(start.getBlock() instanceof MaterialBlock) || MaterialPalette.parse(material) == null) {
            return;
        }
        if (MaterialPalette.isFullFor(material)) {
            player.sendMessage(Text.translatable("msg.station_announcer.material.palette_full", MaterialPalette.SLOTS), true);
            return;
        }
        int oldSlot = MaterialPalette.slot(start);
        int newSlot = MaterialPalette.slotFor(material, false);
        Block block = start.getBlock();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos.toImmutable());
        seen.add(pos.toImmutable());
        int changed = 0;
        while (!queue.isEmpty() && changed < MAX_RUN) {
            BlockPos at = queue.poll();
            BlockState state = world.getBlockState(at);
            if (state.getBlock() != block || MaterialPalette.slot(state) != oldSlot) {
                continue;
            }
            world.setBlockState(at, MaterialPalette.withSlot(state, newSlot), Block.NOTIFY_ALL);
            changed++;
            if (!run) {
                break;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = at.offset(direction);
                if (seen.add(next)) {
                    queue.add(next);
                }
                // Ramps step up a level across a landing / run end: follow diagonals too.
                if (direction.getAxis().isHorizontal()) {
                    for (BlockPos diagonal : new BlockPos[]{next.up(), next.down()}) {
                        if (seen.add(diagonal)) {
                            queue.add(diagonal);
                        }
                    }
                }
            }
        }
        player.sendMessage(Text.translatable("msg.station_announcer.material.retextured", changed,
                MaterialPalette.displayName(material)), true);
    }
}
