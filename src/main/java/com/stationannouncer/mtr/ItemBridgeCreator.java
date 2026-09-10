package com.stationannouncer.mtr;

import net.minecraft.text.Text;
import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.TooltipContext;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;

import java.util.List;
import java.util.Locale;

/**
 * Bridge Creator: the configurable successor of the pillar, railing and
 * viaduct creators. Right-click in the air for the settings screen (parts,
 * materials, pier spacing, presets, a live 3D preview); click two connected
 * rail nodes to build the whole bridge under that stretch of track — and,
 * in Auto mode, under every parallel track of the line beside it. Manual
 * mode collects tracks one node pair at a time and builds from the screen.
 * Sneak-right-click any block in the world to use it as the material of
 * the part chosen in the screen ("Pick in world").
 */
public class ItemBridgeCreator extends ItemNodeModifierSelectableBlockBase {
    public ItemBridgeCreator() {
        super(false, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
    }

    @Override
    public ActionResult useOnBlock2(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player != null && player.isSneaking()) {
            // Material pick: the clicked block state goes into the slot chosen in the screen.
            net.minecraft.block.BlockState state = context.getWorld().getBlockState(context.getBlockPos()).data;
            if (!state.isAir()) {
                if (!context.getWorld().isClient()) {
                    net.minecraft.item.ItemStack stack = context.getStack().data;
                    BridgeSpec spec = BridgeSpec.read(stack);
                    spec.setMaterial(spec.pickTarget, BridgeSpec.stringify(state));
                    spec.write(stack);
                    player.data.sendMessage(Text.translatable("msg.station_announcer.bridge.picked",
                            Text.translatable(state.getBlock().getTranslationKey()),
                            Text.translatable("gui.station_announcer.bridge.slot." + spec.pickTarget.name().toLowerCase(Locale.ROOT))), true);
                }
                return ActionResult.SUCCESS;
            }
        }
        return super.useOnBlock2(context);
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        BridgeSpec spec = BridgeSpec.read(stack.data);
        if (spec.trackMode == BridgeSpec.TrackMode.MANUAL) {
            BridgeService.addManualTrack(player.data, stack.data, rail);
        } else {
            BridgeService.buildFromRail(player.data, stack.data, rail);
        }
    }

    /** Right-click in the air: the settings screen. */
    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            MtrPillars.SETTINGS_OPENER.accept(hand.data);
        }
    }

    /** Glint while armed (first node clicked) or while manual tracks are selected. */
    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && (nbt.contains(TAG_POS) || nbt.contains(BridgeService.TRACKS_TAG))) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        BridgeSpec spec = BridgeSpec.read(stack.data);
        int parts = (spec.deck ? 1 : 0) + (spec.girderStyle != BridgeSpec.GirderStyle.NONE ? 1 : 0)
                + (spec.railing ? 1 : 0) + (spec.pierStyle != BridgeSpec.PierStyle.NONE ? 1 : 0)
                + (spec.archStyle != BridgeSpec.ArchStyle.NONE ? 1 : 0);
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.bridge_creator", parts, spec.pierSpacing)
                .formatted(TextFormatting.GRAY));
        String mode = switch (spec.trackMode) {
            case AUTO -> TextHelper.translatable("tooltip.station_announcer.bridge_creator.auto", spec.trackReach).getString();
            case SINGLE -> TextHelper.translatable("tooltip.station_announcer.bridge_creator.single").getString();
            case MANUAL -> TextHelper.translatable("tooltip.station_announcer.bridge_creator.manual",
                    BridgeService.manualTrackCount(stack.data)).getString();
        };
        tooltip.add(TextHelper.literal(mode).formatted(TextFormatting.GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.creator.settings").formatted(TextFormatting.DARK_GRAY));
    }
}
