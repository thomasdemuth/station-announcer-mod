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
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
 * <p>One half wears the usual {@link MaterialPalette} slot ({@code mat_hi/mat_lo},
 * the "base"), the other a second slot ({@code top_hi/top_lo}). {@link #FACING} is
 * the side the SECOND half sits on: up (as placed, base below), down, or a
 * horizontal side for a split down the middle. The MTR brush cycles it
 * (sneak = backwards). 64 × 64 × 6 = 24,576 states.</p>
 *
 * <p>Mining takes only the half under the crosshair and leaves the other as a
 * plain material slab (a sideways split has no plain-slab equivalent, so it
 * breaks whole and drops both). Vanilla's break order (both sides) is onBreak →
 * remove → onBroken → (survival only) afterBreak, so onBreak decides the half
 * from a fresh raycast, onBroken puts the other half back, afterBreak drops the
 * mined one. Explosions and other non-player breaks drop both halves.</p>
 */
public class MaterialMixedSlabBlock extends Block implements MaterialBlock {
    public static final IntProperty TOP_HI = IntProperty.of("top_hi", 0, 7);
    public static final IntProperty TOP_LO = IntProperty.of("top_lo", 0, 7);
    /** The side the second half is on. */
    public static final DirectionProperty FACING = Properties.FACING;
    /** Brush cycle order. */
    private static final Direction[] CYCLE = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST};

    /** A half being mined: whether it is the second one, and whether a drop will follow (survival). */
    private record Breaking(boolean second, boolean drops) {
    }

    private static final Map<String, Breaking> BREAKING = new ConcurrentHashMap<>();

    public MaterialMixedSlabBlock(Settings settings) {
        super(settings);
        setDefaultState(withTopSlot(MaterialPalette.withSlot(getDefaultState(), 0), 0).with(FACING, Direction.UP));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(MaterialPalette.MAT_HI, MaterialPalette.MAT_LO, TOP_HI, TOP_LO, FACING);
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

    /** True when {@code hit} (a world position on the block at {@code pos}) is in its SECOND half. */
    public static boolean isSecondHalf(BlockState state, BlockPos pos, Vec3d hit) {
        Direction facing = state.contains(FACING) ? state.get(FACING) : Direction.UP;
        Direction.Axis axis = facing.getAxis();
        double along = hit.getComponentAlongAxis(axis) - pos.getComponentAlongAxis(axis);
        return facing.getDirection() == Direction.AxisDirection.POSITIVE ? along >= 0.5 : along < 0.5;
    }

    /** True when the player is looking at the second half of the block at {@code pos}. */
    public static boolean lookingAtSecond(PlayerEntity player, BlockPos pos, BlockState state) {
        HitResult hit = player.raycast(player.isCreative() ? 6.0 : 5.5, 1.0f, false);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(pos)) {
            return isSecondHalf(state, pos, block.getPos());
        }
        return false;
    }

    /** What stays when one half is mined: a plain slab, or null (a sideways split breaks whole). */
    @Nullable
    private static BlockState remainder(BlockState state, boolean minedSecond) {
        Direction facing = state.get(FACING);
        if (facing.getAxis().isHorizontal()) {
            return null;
        }
        int keep = minedSecond ? MaterialPalette.slot(state) : topSlot(state);
        // The kept half is on top when it is the second half facing up, or the base half facing down.
        boolean keptOnTop = minedSecond ? facing == Direction.DOWN : facing == Direction.UP;
        return half(keep, keptOnTop);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!MaterialBlock.isBrush(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            Direction now = state.get(FACING);
            int i = 0;
            while (CYCLE[i] != now) {
                i++;
            }
            Direction next = CYCLE[Math.floorMod(i + (player.isSneaking() ? -1 : 1), CYCLE.length)];
            world.setBlockState(pos, state.with(FACING, next), Block.NOTIFY_ALL);
            player.sendMessage(Text.translatable("msg.station_announcer.material.mixed_facing." + next.asString(),
                    MaterialPalette.displayName(MaterialPalette.materialAt(topSlot(state), false)),
                    MaterialPalette.displayName(MaterialPalette.materialAt(MaterialPalette.slot(state), false))), true);
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        MaterialBlocks.clientRemesh(world, pos);
        boolean drops = !player.isCreative() && player.canHarvest(state);
        BREAKING.put(key(world, pos), new Breaking(lookingAtSecond(player, pos, state), drops));
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
        // The half NOT mined stays, as a plain slab (nothing stays of a sideways split).
        BlockState rest = remainder(state, breaking.second());
        if (rest != null) {
            w.setBlockState(pos, rest, Block.NOTIFY_ALL);
        }
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
            if (remainder(state, breaking.second()) == null) {
                dropStack(world, pos, slabStack(MaterialPalette.slot(state), world));
                dropStack(world, pos, slabStack(topSlot(state), world));
            } else {
                dropStack(world, pos, slabStack(breaking.second() ? topSlot(state) : MaterialPalette.slot(state), world));
            }
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
