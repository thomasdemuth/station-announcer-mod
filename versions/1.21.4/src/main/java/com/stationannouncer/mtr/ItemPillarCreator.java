package com.stationannouncer.mtr;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.TooltipContext;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;
import java.util.List;

/**
 * Pillar Creator — companion to MTR's bridge/tunnel/wall creators. Works the
 * same way (extends the same selectable-block base): sneak-right-click any
 * block to choose the pillar material, then click two connected rail nodes.
 * Along the existing rail between them, columns of the chosen block are built
 * straight down, starting one block below the rail, until they hit the
 * ground.
 *
 * <p>{@code width} is the horizontal gap between the pillar pair (1 = a
 * single pillar centered under the rail; 3/5 = two pillars that many blocks
 * apart, straddling the track). {@code spacing} is the distance in blocks
 * between pillar sets along the rail; sets start half a spacing in from the
 * nodes so both ends look symmetric and platforms stay clear.
 *
 * <p>Thin decking directly under the rail (e.g. a bridge built by MTR's
 * bridge creator) is passed through untouched; the first solid block below
 * that ends the column (see {@link RailBuildHelper#buildColumn}).
 */
public class ItemPillarCreator extends ItemNodeModifierSelectableBlockBase {
    /** Horizontal distance between the two pillars (1 = one centered pillar). */
    public final int width;
    /** Blocks of rail between consecutive pillar sets. */
    public final int spacing;

    public ItemPillarCreator(int width, int spacing) {
        super(true, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
        this.width = width;
        this.spacing = spacing;
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        BlockState savedState = getSavedState(stack);
        if (savedState == null || savedState.isAir()) {
            player.data.sendMessage(Text.translatable("msg.station_announcer.pillar.no_material"), true);
            return;
        }
        net.minecraft.block.BlockState state = savedState.data;
        ServerWorld world = player.getServerWorld().data;

        int[] built = {0};
        // Start half a spacing in so the pattern is symmetric and nothing
        // lands exactly on the rail nodes (usually platforms/stations).
        RailBuildHelper.walk(rail.railMath, spacing / 2.0, spacing, (center, perpendicular) -> {
            if (width <= 1 || perpendicular == null) {
                built[0] += RailBuildHelper.buildColumn(world, center, state);
                return;
            }
            double offset = width / 2.0;
            built[0] += RailBuildHelper.buildColumn(world, center.add(perpendicular.multiply(offset, 0, offset)), state);
            built[0] += RailBuildHelper.buildColumn(world, center.add(perpendicular.multiply(-offset, 0, -offset)), state);
        });
        player.data.sendMessage(Text.translatable("msg.station_announcer.pillar.built", built[0]), true);
    }


    /** "Flashing" while armed: enchant glint between the first and second node click. */
    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.pillar_creator",
                width <= 1 ? 1 : 2, spacing).formatted(TextFormatting.GRAY));
        super.addTooltips(stack, world, tooltip, options); // saved material + usage hints
    }
}
