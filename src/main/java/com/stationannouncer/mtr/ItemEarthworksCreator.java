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

/**
 * The Embankment Creator, Trench Creator and ROW Clearer (one class, three
 * items): right-click the air for the settings screen, click two connected
 * rail nodes to build along that track (and, in Auto, its parallel tracks),
 * sneak-click any block to put it into the layer chosen in the screen —
 * replacing the mix or adding to it.
 */
public class ItemEarthworksCreator extends ItemNodeModifierSelectableBlockBase {
    public final EarthworksSpec.Kind kind;

    public ItemEarthworksCreator(EarthworksSpec.Kind kind) {
        super(false, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
        this.kind = kind;
    }

    @Override
    public ActionResult useOnBlock2(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player != null && player.isSneaking() && kind != EarthworksSpec.Kind.CLEARER) {
            net.minecraft.block.BlockState state = context.getWorld().getBlockState(context.getBlockPos()).data;
            if (!state.isAir()) {
                if (!context.getWorld().isClient()) {
                    net.minecraft.item.ItemStack stack = context.getStack().data;
                    EarthworksSpec spec = EarthworksSpec.read(kind, stack);
                    String material = BridgeSpec.stringify(state);
                    spec.setPalette(spec.pickTarget, spec.pickAdd
                            ? EarthworksPalette.add(spec.palette(spec.pickTarget), material, 1) : material);
                    spec.write(stack);
                    player.data.sendMessage(Text.translatable(spec.pickAdd
                                    ? "msg.station_announcer.earthworks.picked_add" : "msg.station_announcer.earthworks.picked",
                            Text.translatable(state.getBlock().getTranslationKey()), spec.pickTarget.label), true);
                }
                return ActionResult.SUCCESS;
            }
        }
        return MtrPillars.claimNodeClick(context, super.useOnBlock2(context));
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        EarthworksService.buildFromRail(player.data, stack.data, kind, rail);
    }

    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            MtrPillars.SETTINGS_OPENER.accept(hand.data);
        }
    }

    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        EarthworksSpec spec = EarthworksSpec.read(kind, stack.data);
        String line = switch (kind) {
            case EMBANKMENT -> TextHelper.translatable("tooltip.station_announcer.embankment_creator",
                    spec.slopeLabel(), spec.maxHeight).getString();
            case TRENCH -> TextHelper.translatable("tooltip.station_announcer.trench_creator",
                    spec.slopeLabel(), spec.maxDepth).getString();
            case CLEARER -> TextHelper.translatable("tooltip.station_announcer.row_clearer",
                    spec.corridor, spec.clearHeight).getString();
        };
        tooltip.add(TextHelper.literal(line).formatted(TextFormatting.GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.earthworks.use").formatted(TextFormatting.DARK_GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.creator.settings").formatted(TextFormatting.DARK_GRAY));
    }
}
