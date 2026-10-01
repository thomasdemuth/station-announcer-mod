package com.stationannouncer.client.material;

import com.stationannouncer.material.MaterialPalette;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

/**
 * Client mirror of the world's {@link MaterialPalette}: slot → material. A new
 * or changed slot invalidates the material quads and rebuilds the loaded
 * chunks, so blocks placed with a brand-new material show it at once.
 */
@Environment(EnvType.CLIENT)
public final class ClientMaterialPalette {
    private static volatile String[] strings = defaults();
    private static volatile BlockState[] states = resolve(strings);

    private ClientMaterialPalette() {
    }

    public static BlockState state(int slot) {
        BlockState[] current = states;
        return slot >= 0 && slot < current.length && current[slot] != null ? current[slot] : Blocks.SMOOTH_STONE.getDefaultState();
    }

    public static void register() {
        MaterialPalette.clientSlotLookup = material -> {
            if (MaterialPalette.DEFAULT.equals(material)) {
                return 0;
            }
            String[] current = strings;
            for (int i = 1; i < current.length; i++) {
                if (material.equals(current[i])) {
                    return i;
                }
            }
            return -1;
        };
        MaterialPalette.clientSlotMaterial = slot -> {
            String[] current = strings;
            return slot >= 0 && slot < current.length ? current[slot] : null;
        };

        ClientPlayNetworking.registerGlobalReceiver(MaterialPalette.PALETTE_S2C, (client, handler, buf, responseSender) -> {
            int count = buf.readVarInt();
            if (count < 0 || count > 1024) {
                return;
            }
            String[] next = new String[MaterialPalette.SLOTS];
            for (int i = 0; i < count; i++) {
                String material = buf.readString(MaterialPalette.MAX_MATERIAL_LENGTH);
                if (i < next.length) {
                    next[i] = material.isEmpty() ? null : material;
                }
            }
            next[0] = MaterialPalette.DEFAULT;
            client.execute(() -> apply(client, next));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            strings = defaults();
            states = resolve(strings);
            MaterialSprites.invalidate();
        });
    }

    /**
     * A palette sync. The normal case — the server just ADDED a slot because
     * someone placed a ramp/stair in a new texture — needs no re-mesh at all:
     * the slot is assigned (and this packet sent) before the block update that
     * uses it, and that block update re-meshes its own chunk section with the
     * new material. Re-meshing the whole world here (the first cut did) made
     * every first placement of a new texture reload every chunk. Only a slot
     * that CHANGED meaning (never happens within one world) forces a reload.
     */
    private static void apply(MinecraftClient client, String[] next) {
        if (Arrays.equals(next, strings)) {
            return;
        }
        boolean reassigned = false;
        String[] previous = strings;
        for (int i = 0; i < next.length; i++) {
            if (previous[i] != null && !previous[i].equals(next[i])) {
                reassigned = true;
                break;
            }
        }
        strings = next;
        states = resolve(next);
        MaterialSprites.invalidate();
        if (reassigned && client.worldRenderer != null && client.world != null) {
            client.worldRenderer.reload();
        }
    }

    private static String[] defaults() {
        String[] slots = new String[MaterialPalette.SLOTS];
        slots[0] = MaterialPalette.DEFAULT;
        return slots;
    }

    private static BlockState[] resolve(String[] materials) {
        BlockState[] resolved = new BlockState[materials.length];
        for (int i = 0; i < materials.length; i++) {
            resolved[i] = materials[i] == null ? null : MaterialPalette.parse(materials[i]);
        }
        return resolved;
    }
}
