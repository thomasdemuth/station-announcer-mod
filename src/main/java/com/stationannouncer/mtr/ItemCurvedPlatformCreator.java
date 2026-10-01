package com.stationannouncer.mtr;

import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.TooltipContext;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;

import java.util.List;

/**
 * Curved Platform Creator: stand on the platform, click the platform track's
 * two nodes — the platform edge along that stretch is cut to follow the track
 * ({@link CurvedPlatforms#build}). Right-click the air to undo the last build.
 * Same MTR base as the bridge and pillar creators (node clicking, the glint
 * while armed); no material to pick, the edge is always the concrete platform
 * edge with its tactile strip.
 */
public class ItemCurvedPlatformCreator extends ItemNodeModifierSelectableBlockBase {
    public ItemCurvedPlatformCreator() {
        super(false, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        CurvedPlatforms.build(player.getServerWorld().data, rail.railMath, player.data, mode(stack.data));
    }

    @Override
    public org.mtr.mapping.holder.ActionResult useOnBlock2(org.mtr.mapping.holder.ItemUsageContext context) {
        return MtrPillars.claimNodeClick(context, super.useOnBlock2(context));
    }

    /** Right-click in the air: undo the last build. Sneak + right-click the air: cycle the filler mode. */
    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient() || !(player.data instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer)) {
            return;
        }
        if (serverPlayer.isSneaking()) {
            net.minecraft.item.ItemStack stack = serverPlayer.getStackInHand(hand.data);
            int next = (mode(stack) + 1) % 3;
            stack.getOrCreateNbt().putInt(CurvedPlatforms.MODE_KEY, next);
            serverPlayer.sendMessage(net.minecraft.text.Text.translatable("msg.station_announcer.curved_platform.mode."
                    + next), true);
            return;
        }
        CurvedPlatforms.undo(serverPlayer.getServerWorld(), serverPlayer);
    }

    static int mode(net.minecraft.item.ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.getNbt();
        return nbt == null ? CurvedPlatforms.MODE_EDGES : Math.max(0, Math.min(2, nbt.getInt(CurvedPlatforms.MODE_KEY)));
    }

    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.curved_platform_creator").formatted(TextFormatting.GRAY));
        tooltip.add(TextHelper.translatable("msg.station_announcer.curved_platform.mode." + mode(stack.data))
                .formatted(TextFormatting.GOLD));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.curved_platform_creator.undo").formatted(TextFormatting.DARK_GRAY));
    }
}
