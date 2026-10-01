package com.stationannouncer.material;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Which full block a material ramp / stair wears.
 *
 * <p><b>Why a palette in the blockstate:</b> Thomas plays with Sodium and no
 * Indium, so a block cannot hand its block-entity data to the chunk mesher
 * (Sodium never calls the Fabric renderer API — the concrete-floor lesson in
 * CLAUDE.md). What the mesher DOES see is the blockstate. So each world keeps
 * a palette of up to {@link #SLOTS} materials, a block stores its slot in two
 * 8-value properties ({@link #MAT_HI} × {@link #MAT_LO} — two small properties
 * instead of one 64-value one keeps every state's neighbour table small), and
 * the client model looks the slot up and builds its quads from that material's
 * own sprites. Chunk-meshed like any block: smooth lighting, AO, no render
 * distance, no per-frame cost.</p>
 *
 * <p>Slot 0 is always {@link #DEFAULT}. Slots are allocated on the server the
 * first time a material is used and never reassigned within a world (a block
 * already placed must keep its look). Stored at
 * {@code <save>/station_announcer/materials.json}; the whole palette is sent to
 * every client on join and whenever a slot is added.</p>
 */
public final class MaterialPalette {
    public static final int SLOTS = 64;
    public static final IntProperty MAT_HI = IntProperty.of("mat_hi", 0, 7);
    public static final IntProperty MAT_LO = IntProperty.of("mat_lo", 0, 7);
    public static final String DEFAULT = "minecraft:smooth_stone";
    public static final String NBT_KEY = "Material";
    public static final int MAX_MATERIAL_LENGTH = 256;
    public static final Identifier PALETTE_S2C = StationAnnouncer.id("material_palette");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] SERVER_SLOTS = new String[SLOTS];
    private static Path path;
    private static MinecraftServer server;

    /**
     * Client side: material string → slot the client already knows (-1 if
     * none), installed by the client palette. Placement runs on both sides; the
     * client's guess only matters until the server's state arrives.
     */
    public static Function<String, Integer> clientSlotLookup = material -> 0;
    /** Client side: slot → material string (null if unknown). */
    public static Function<Integer, String> clientSlotMaterial = slot -> null;

    private MaterialPalette() {
    }

    // ------------------------------------------------------------ state helpers

    public static int slot(BlockState state) {
        return state.contains(MAT_HI) ? state.get(MAT_HI) * 8 + state.get(MAT_LO) : 0;
    }

    public static BlockState withSlot(BlockState state, int slot) {
        int s = Math.max(0, Math.min(SLOTS - 1, slot));
        return state.with(MAT_HI, s / 8).with(MAT_LO, s % 8);
    }

    // ------------------------------------------------------------ materials

    /** A material string → its block state, or null if it is not a usable full block. */
    @Nullable
    public static BlockState parse(String material) {
        if (material == null || material.isEmpty() || material.length() > MAX_MATERIAL_LENGTH) {
            return null;
        }
        try {
            BlockState state = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), material, false).blockState();
            return isUsable(state) ? state : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "Any full block": a plain model-rendered full cube with no block entity. */
    public static boolean isUsable(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof MaterialBlock || block instanceof BlockEntityProvider) {
            return false;
        }
        try {
            return state.getRenderType() == BlockRenderType.MODEL
                    && state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        } catch (Exception e) {
            return false;
        }
    }

    /** Canonical string for a state: "minecraft:oak_log[axis=y]". */
    public static String stringify(BlockState state) {
        return BlockArgumentParser.stringifyBlockState(state);
    }

    /** The material an item carries (the default when it has none or a bad one). */
    public static String materialOf(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        String material = nbt != null ? nbt.getString(NBT_KEY) : "";
        return parse(material) != null ? material : DEFAULT;
    }

    public static void setMaterial(ItemStack stack, String material) {
        stack.getOrCreateNbt().putString(NBT_KEY, material);
    }

    public static ItemStack stackWith(Block block, String material) {
        ItemStack stack = new ItemStack(block);
        if (!DEFAULT.equals(material)) {
            setMaterial(stack, material);
        }
        return stack;
    }

    /** A block's display name for a material string (for item names and messages). */
    public static net.minecraft.text.Text displayName(String material) {
        BlockState state = parse(material);
        return (state == null ? Blocks.SMOOTH_STONE : state.getBlock()).getName();
    }

    // ------------------------------------------------------------ server palette

    /**
     * The slot for a material on the side {@code isClient} says, allocating a
     * new one on the server (and telling every client) when needed. Falls back
     * to slot 0 when the palette is full.
     */
    public static int slotFor(String material, boolean isClient) {
        if (isClient) {
            Integer slot = clientSlotLookup.apply(material);
            return slot == null || slot < 0 ? 0 : slot;
        }
        if (parse(material) == null || DEFAULT.equals(material)) {
            return 0;
        }
        for (int i = 1; i < SLOTS; i++) {
            if (material.equals(SERVER_SLOTS[i])) {
                return i;
            }
        }
        for (int i = 1; i < SLOTS; i++) {
            if (SERVER_SLOTS[i] == null) {
                SERVER_SLOTS[i] = material;
                save();
                broadcast();
                return i;
            }
        }
        StationAnnouncer.LOGGER.warn("Material palette is full ({} materials); {} falls back to smooth stone",
                SLOTS, material);
        return 0;
    }

    /** True when slotFor would have to fall back (palette full and material new). */
    public static boolean isFullFor(String material) {
        if (DEFAULT.equals(material)) {
            return false;
        }
        for (int i = 1; i < SLOTS; i++) {
            if (SERVER_SLOTS[i] == null || material.equals(SERVER_SLOTS[i])) {
                return false;
            }
        }
        return true;
    }

    /** Server (or client, via the hook): the material string in a slot. */
    public static String materialAt(int slot, boolean isClient) {
        if (slot <= 0 || slot >= SLOTS) {
            return DEFAULT;
        }
        String material = isClient ? clientSlotMaterial.apply(slot) : SERVER_SLOTS[slot];
        return material == null ? DEFAULT : material;
    }

    public static int usedSlots() {
        int used = 1;
        for (int i = 1; i < SLOTS; i++) {
            if (SERVER_SLOTS[i] != null) {
                used++;
            }
        }
        return used;
    }

    static void load(MinecraftServer minecraftServer) {
        server = minecraftServer;
        path = minecraftServer.getSavePath(WorldSavePath.ROOT).resolve("station_announcer").resolve("materials.json").normalize();
        java.util.Arrays.fill(SERVER_SLOTS, null);
        SERVER_SLOTS[0] = DEFAULT;
        try {
            if (Files.exists(path)) {
                JsonArray slots = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("slots");
                for (int i = 1; slots != null && i < Math.min(SLOTS, slots.size()); i++) {
                    JsonElement e = slots.get(i);
                    SERVER_SLOTS[i] = e == null || e.isJsonNull() ? null : e.getAsString();
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}; material ramps and stairs fall back to smooth stone", path, e);
        }
    }

    static void unload() {
        server = null;
        path = null;
        java.util.Arrays.fill(SERVER_SLOTS, null);
    }

    private static void save() {
        if (path == null) {
            return;
        }
        JsonArray slots = new JsonArray();
        for (String slot : SERVER_SLOTS) {
            if (slot == null) {
                slots.add(com.google.gson.JsonNull.INSTANCE);
            } else {
                slots.add(slot);
            }
        }
        JsonObject root = new JsonObject();
        root.add("slots", slots);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }

    static PacketByteBuf syncBuf() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(SLOTS);
        for (String slot : SERVER_SLOTS) {
            buf.writeString(slot == null ? "" : slot, MAX_MATERIAL_LENGTH);
        }
        return buf;
    }

    private static void broadcast() {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, PALETTE_S2C, syncBuf());
        }
    }
}
