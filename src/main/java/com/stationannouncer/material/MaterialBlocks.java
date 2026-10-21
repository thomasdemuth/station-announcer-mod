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
 * Material ramps, stairs, slabs, walls and fences: registration, the picker's packets and the
 * {@link MaterialPalette} lifecycle. Common code (no MTR); the client half is
 * {@code client.material.MaterialClient}.
 */
public final class MaterialBlocks {
    public static final MaterialRampBlock MATERIAL_RAMP =
            new MaterialRampBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
    public static final MaterialStairsBlock MATERIAL_STAIRS =
            new MaterialStairsBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
    public static final MaterialSlabBlock MATERIAL_SLAB =
            new MaterialSlabBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE_SLAB));
    /** Two different textures in one cell; made by placing a slab on a slab, no item of its own. */
    public static final MaterialMixedSlabBlock MATERIAL_MIXED_SLAB =
            new MaterialMixedSlabBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE_SLAB));
    public static final MaterialVerticalSlabBlock MATERIAL_VERTICAL_SLAB =
            new MaterialVerticalSlabBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE_SLAB));
    public static final MaterialWallBlock MATERIAL_WALL =
            new MaterialWallBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE).solid());
    public static final MaterialFenceBlock MATERIAL_FENCE =
            new MaterialFenceBlock(AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE).solid());
    public static final Item MATERIAL_RAMP_ITEM = new MaterialBlockItem(MATERIAL_RAMP, new Item.Settings());
    public static final Item MATERIAL_STAIRS_ITEM = new MaterialBlockItem(MATERIAL_STAIRS, new Item.Settings());
    public static final Item MATERIAL_SLAB_ITEM = new MaterialBlockItem(MATERIAL_SLAB, new Item.Settings());
    public static final Item MATERIAL_VERTICAL_SLAB_ITEM = new MaterialBlockItem(MATERIAL_VERTICAL_SLAB, new Item.Settings());
    public static final Item MATERIAL_WALL_ITEM = new MaterialBlockItem(MATERIAL_WALL, new Item.Settings());
    public static final Item MATERIAL_FENCE_ITEM = new MaterialBlockItem(MATERIAL_FENCE, new Item.Settings());

    /** C2S: the picker set the held item's material (hand ordinal, material). */
    public static final Identifier SET_ITEM_MATERIAL_C2S = StationAnnouncer.id("set_item_material");
    /** C2S: the picker retextured a placed block (pos, material, whole connected run). */
    public static final Identifier SET_BLOCK_MATERIAL_C2S = StationAnnouncer.id("set_block_material");
    private static final int MAX_RUN = 1024;

    /** Client hooks, installed by MaterialClient; no-ops on a dedicated server. */
    public static Consumer<Hand> PICKER_OPENER = hand -> {
    };
    /** Opens the picker for a placed block; the flag = the top half of a mixed slab. */
    public static java.util.function.BiConsumer<BlockPos, Boolean> BLOCK_PICKER_OPENER = (pos, top) -> {
    };
    /** Client: re-mesh around a broken code-modelled block a moment later (see {@link #clientRemesh}). */
    public static Consumer<BlockPos> CLIENT_REMESH = pos -> {
    };

    /**
     * Called from the code-modelled blocks' {@code onBreak} (ramps, stairs, ramp
     * rails, curved edges). Breaking one by hand could leave its picture behind —
     * walk-through, gone after F3+A — on Thomas's client (Sodium + Iris + a large
     * mod set; not reproducible on the rig), i.e. the section rebuild for the
     * break got lost. The client re-meshes the spot again shortly after, a
     * targeted F3+A. No-op on the server.
     */
    public static void clientRemesh(net.minecraft.world.World world, BlockPos pos) {
        if (world.isClient) {
            CLIENT_REMESH.accept(pos.toImmutable());
        }
    }

    private MaterialBlocks() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, StationAnnouncer.id("material_ramp"), MATERIAL_RAMP);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("material_stairs"), MATERIAL_STAIRS);
        Registry.register(Registries.ITEM, StationAnnouncer.id("material_ramp"), MATERIAL_RAMP_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("material_stairs"), MATERIAL_STAIRS_ITEM);
        ModContent.DECORATION_ENTRIES.add(MATERIAL_RAMP_ITEM);
        ModContent.DECORATION_ENTRIES.add(MATERIAL_STAIRS_ITEM);
        register("material_slab", MATERIAL_SLAB, MATERIAL_SLAB_ITEM);
        register("material_vertical_slab", MATERIAL_VERTICAL_SLAB, MATERIAL_VERTICAL_SLAB_ITEM);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("material_mixed_slab"), MATERIAL_MIXED_SLAB);
        register("material_wall", MATERIAL_WALL, MATERIAL_WALL_ITEM);
        register("material_fence", MATERIAL_FENCE, MATERIAL_FENCE_ITEM);
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
            boolean top = buf.isReadable() && buf.readBoolean(); // mixed slab: which half (older senders omit it)
            server.execute(() -> retexture(player, pos, material, run, top));
        });
    }

    private static void register(String name, Block block, Item item) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(name), block);
        Registry.register(Registries.ITEM, StationAnnouncer.id(name), item);
        ModContent.DECORATION_ENTRIES.add(item);
    }

    private static void retexture(ServerPlayerEntity player, BlockPos pos, String material, boolean run, boolean top) {
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
        boolean upper = top && start.getBlock() instanceof MaterialMixedSlabBlock;
        int oldSlot = upper ? MaterialMixedSlabBlock.topSlot(start) : MaterialPalette.slot(start);
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
            if (state.getBlock() != block
                    || (upper ? MaterialMixedSlabBlock.topSlot(state) : MaterialPalette.slot(state)) != oldSlot) {
                continue;
            }
            BlockState updated = upper ? MaterialMixedSlabBlock.withTopSlot(state, newSlot) : MaterialPalette.withSlot(state, newSlot);
            if (state.getBlock() instanceof MaterialMixedSlabBlock) {
                // both halves now the same texture: a plain double slab
                updated = MaterialMixedSlabBlock.of(MaterialPalette.slot(updated), MaterialMixedSlabBlock.topSlot(updated),
                        updated.get(MaterialMixedSlabBlock.FACING));
            }
            world.setBlockState(at, updated, Block.NOTIFY_ALL);
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
