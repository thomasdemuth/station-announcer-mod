package com.stationannouncer.mtr;

import net.minecraft.nbt.NbtCompound;
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
 * The Structure Sweeper: click two connected rail nodes to PICK a track —
 * the chosen template appears on it as a ghost and the side panel opens to
 * fit it (turn, rail column / level, offsets, repeat mode…); Build places
 * it, running from the FIRST node clicked to the second. Right-click the air
 * for the panel again.
 */
public class ItemStructureSweeper extends ItemNodeModifierSelectableBlockBase {
    /** The first node of a two-node click, remembered before MTR hands us the rail (which has its own direction). */
    private static final String FROM = "SweepFrom";

    public ItemStructureSweeper() {
        super(false, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
    }

    @Override
    public ActionResult useOnBlock2(ItemUsageContext context) {
        if (!context.getWorld().isClient()) {
            NbtCompound nbt = context.getStack().data.getNbt();
            if (nbt != null) {
                if (nbt.contains(TAG_POS)) {
                    nbt.putLong(FROM, nbt.getLong(TAG_POS));
                } else {
                    nbt.remove(FROM);
                }
            }
        }
        return MtrPillars.claimNodeClick(context, super.useOnBlock2(context));
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        NbtCompound nbt = stack.data.getNbt();
        net.minecraft.util.math.BlockPos from = nbt != null && nbt.contains(FROM)
                ? net.minecraft.util.math.BlockPos.fromLong(nbt.getLong(FROM)) : null;
        if (nbt != null) {
            nbt.remove(FROM);
        }
        SweepService.pickRail(player.data, rail, from);
    }

    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            MtrPillars.SETTINGS_OPENER.accept(hand.data);
        }
    }

    @Override
    public boolean hasGlint2(ItemStack stack) {
        NbtCompound nbt = stack.data.getNbt();
        return nbt != null && nbt.contains(TAG_POS);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        SweepSpec spec = SweepSpec.read(stack.data);
        String template = spec.template.isEmpty()
                ? Text.translatable("tooltip.station_announcer.structure_sweeper.none").getString() : spec.template;
        tooltip.add(TextHelper.literal(Text.translatable("tooltip.station_announcer.structure_sweeper.template",
                template, Text.translatable("gui.station_announcer.sweep.mode." + spec.mode.name().toLowerCase(java.util.Locale.ROOT)))
                .getString()).formatted(TextFormatting.GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.structure_sweeper.use").formatted(TextFormatting.DARK_GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.creator.settings").formatted(TextFormatting.DARK_GRAY));
    }
}
