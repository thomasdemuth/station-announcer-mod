package com.stationannouncer.mtr;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Template Wand: two corners and the structure is captured — right-click
 * a block, or right-click the AIR to put the corner where you stand (so a box
 * can reach up into open air). After the second corner the save screen opens.
 * Where the rail sits is decided later, on the real track, by the sweeper.
 * Sneak-right-click the air to start over; right-click the air with a full
 * box to reopen the save screen.
 */
public class ItemSweepWand extends Item {
    public static final String KEY = "SweepSel";

    public ItemSweepWand() {
        super(new Item.Settings().maxCount(1));
    }

    /** The box held in a wand (either corner may be missing). */
    public record Selection(BlockPos c1, BlockPos c2) {
        public boolean complete() {
            return c1 != null && c2 != null;
        }

        public BlockPos min() {
            return new BlockPos(Math.min(c1.getX(), c2.getX()), Math.min(c1.getY(), c2.getY()), Math.min(c1.getZ(), c2.getZ()));
        }

        public BlockPos max() {
            return new BlockPos(Math.max(c1.getX(), c2.getX()), Math.max(c1.getY(), c2.getY()), Math.max(c1.getZ(), c2.getZ()));
        }

        public int sizeX() {
            return c1 == null || c2 == null ? 0 : Math.abs(c1.getX() - c2.getX()) + 1;
        }

        public int sizeY() {
            return c1 == null || c2 == null ? 0 : Math.abs(c1.getY() - c2.getY()) + 1;
        }

        public int sizeZ() {
            return c1 == null || c2 == null ? 0 : Math.abs(c1.getZ() - c2.getZ()) + 1;
        }
    }

    public static Selection read(ItemStack stack) {
        NbtCompound root = stack.getNbt();
        if (root == null || !root.contains(KEY)) {
            return new Selection(null, null);
        }
        NbtCompound sel = root.getCompound(KEY);
        return new Selection(
                sel.contains("C1") ? BlockPos.fromLong(sel.getLong("C1")) : null,
                sel.contains("C2") ? BlockPos.fromLong(sel.getLong("C2")) : null);
    }

    public static void clear(ItemStack stack) {
        if (stack.getNbt() != null) {
            stack.getNbt().remove(KEY);
        }
    }

    /** Sets the next corner (a full box starts over); opens the save screen after the second. */
    private static void corner(ServerPlayerEntity player, ItemStack stack, Hand hand, BlockPos pos) {
        if (!Sweep.allowed(player)) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.not_allowed"), true);
            return;
        }
        Selection current = read(stack);
        if (current.c1() == null || current.complete()) {
            NbtCompound sel = new NbtCompound();
            sel.putLong("C1", pos.asLong());
            stack.getOrCreateNbt().put(KEY, sel);
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.corner1", pos.getX(), pos.getY(), pos.getZ()), true);
        } else {
            stack.getOrCreateSubNbt(KEY).putLong("C2", pos.asLong());
            Selection s = read(stack);
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.corner2", s.sizeX(), s.sizeY(), s.sizeZ()), true);
            Sweep.openSaveScreen(player, hand);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return ActionResult.PASS;
        }
        if (!context.getWorld().isClient() && player instanceof ServerPlayerEntity sp) {
            corner(sp, context.getStack(), context.getHand(), context.getBlockPos());
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (player.isSneaking()) {
            if (!world.isClient()) {
                clear(stack);
                player.sendMessage(Text.translatable("msg.station_announcer.sweep.cleared"), true);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
        if (read(stack).complete()) {
            if (world.isClient()) {
                Sweep.SAVE_SCREEN_OPENER.accept(hand);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
        if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
            corner(sp, stack, hand, player.getBlockPos());
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        Selection s = read(stack);
        return s.c1() != null && !s.complete();
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        Selection s = read(stack);
        if (s.complete()) {
            tooltip.add(Text.translatable("tooltip.station_announcer.sweep_wand.box", s.sizeX(), s.sizeY(), s.sizeZ())
                    .formatted(Formatting.GRAY));
        }
        tooltip.add(Text.translatable("tooltip.station_announcer.sweep_wand.use").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.sweep_wand.clear").formatted(Formatting.DARK_GRAY));
    }
}
