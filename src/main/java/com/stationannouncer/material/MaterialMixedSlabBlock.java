package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two material slabs of DIFFERENT textures in one cell — stone over planks.
 * Not an item of its own: placing a material slab onto one of another texture
 * makes this block ({@link MaterialSlabBlock#getPlacementState}).
 *
 * <p>The bottom half wears the usual {@link MaterialPalette} slot
 * ({@code mat_hi/mat_lo}); the top half a second slot ({@code top_hi/top_lo}).
 * 64 × 64 = 4096 states — two small properties each, like the first slot.</p>
 *
 * <p>Mining takes only the half under the crosshair and leaves the other as a
 * plain material slab. Vanilla's break order (both sides) is onBreak → remove
 * → onBroken → (survival only) afterBreak, so onBreak decides the half from a
 * fresh raycast, onBroken puts the other half back, afterBreak drops the
 * broken one. Explosions and other non-player breaks drop both halves.</p>
 */
public class MaterialMixedSlabBlock extends Block implements MaterialBlock {
    public static final IntProperty TOP_HI = IntProperty.of("top_hi", 0, 7);
    public static final IntProperty TOP_LO = IntProperty.of("top_lo", 0, 7);

    /** A half being mined: which one, and whether a drop will follow (survival). */
    private record Breaking(boolean top, boolean drops) {
    }

    private static final Map<String, Breaking> BREAKING = new ConcurrentHashMap<>();

    public MaterialMixedSlabBlock(Settings settings) {
        super(settings);
        setDefaultState(withTopSlot(MaterialPalette.withSlot(getDefaultState(), 0), 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(MaterialPalette.MAT_HI, MaterialPalette.MAT_LO, TOP_HI, TOP_LO);
    }

    public static int topSlot(BlockState state) {
        return state.contains(TOP_HI) ? state.get(TOP_HI) * 8 + state.get(TOP_LO) : 0;
    }

    public static BlockState withTopSlot(BlockState state, int slot) {
        int s = Math.max(0, Math.min(MaterialPalette.SLOTS - 1, slot));
        return state.with(TOP_HI, s / 8).with(TOP_LO, s % 8);
    }

    /** The state with both halves; a single double slab when they wear the same slot. */
    public static BlockState of(int bottomSlot, int topSlot) {
        if (bottomSlot == topSlot) {
            return MaterialPalette.withSlot(MaterialBlocks.MATERIAL_SLAB.getDefaultState()
                    .with(MaterialSlabBlock.TYPE, SlabType.DOUBLE), bottomSlot);
        }
        return withTopSlot(MaterialPalette.withSlot(MaterialBlocks.MATERIAL_MIXED_SLAB.getDefaultState(), bottomSlot), topSlot);
    }

    /** One half as a single material slab. */
    private static BlockState half(int slot, boolean top) {
        return MaterialPalette.withSlot(MaterialBlocks.MATERIAL_SLAB.getDefaultState()
                .with(MaterialSlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM), slot);
    }

    private static ItemStack slabStack(int slot, @Nullable World world) {
        boolean client = world != null && world.isClient;
        return MaterialPalette.stackWith(MaterialBlocks.MATERIAL_SLAB, MaterialPalette.materialAt(slot, client));
    }

    private static String key(World world, BlockPos pos) {
        return world.getRegistryKey().getValue() + (world.isClient ? "|c|" : "|s|") + pos.asLong();
    }

    /** True when the player is looking at the top half of the block at {@code pos}. */
    public static boolean lookingAtTop(PlayerEntity player, BlockPos pos) {
        HitResult hit = player.raycast(player.isCreative() ? 6.0 : 5.5, 1.0f, false);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(pos)) {
            return block.getPos().y - pos.getY() >= 0.5;
        }
        return false;
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        MaterialBlocks.clientRemesh(world, pos);
        boolean drops = !player.isCreative() && player.canHarvest(state);
        BREAKING.put(key(world, pos), new Breaking(lookingAtTop(player, pos), drops));
        return super.onBreak(world, pos, state, player);
    }

    @Override
    public void onBroken(WorldAccess world, BlockPos pos, BlockState state) {
        if (!(world instanceof World w)) {
            return;
        }
        String key = key(w, pos);
        Breaking breaking = BREAKING.get(key);
        if (breaking == null) {
            return;
        }
        if (!breaking.drops() || w.isClient) {
            BREAKING.remove(key); // no afterBreak follows (creative / the client's prediction)
        }
        // The half NOT mined stays, as a plain slab.
        int keep = breaking.top() ? MaterialPalette.slot(state) : topSlot(state);
        w.setBlockState(pos, half(keep, !breaking.top()), Block.NOTIFY_ALL);
    }

    @Override
    public void afterBreak(World world, PlayerEntity player, BlockPos pos, BlockState state,
                           @Nullable BlockEntity blockEntity, ItemStack tool) {
        Breaking breaking = BREAKING.remove(key(world, pos));
        if (breaking == null) {
            super.afterBreak(world, player, pos, state, blockEntity, tool);
            return;
        }
        player.incrementStat(net.minecraft.stat.Stats.MINED.getOrCreateStat(this));
        player.addExhaustion(0.005f);
        if (world instanceof ServerWorld) {
            int slot = breaking.top() ? topSlot(state) : MaterialPalette.slot(state);
            dropStack(world, pos, slabStack(slot, world));
        }
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        // Non-player breaks (explosions, /setblock destroy): both halves.
        World world = builder.getWorld();
        return List.of(slabStack(MaterialPalette.slot(state), world), slabStack(topSlot(state), world));
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return slabStack(MaterialPalette.slot(state), world instanceof World w ? w : null);
    }
}
