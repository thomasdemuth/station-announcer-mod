package com.stationannouncer.mtr;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Railing Creator — lays the chosen block (fences, walls, bars, anything)
 * along BOTH edges of a bridge deck, at rail height. Width matches the MTR
 * bridge creator that built the deck (3/5/7/9): railings sit on the deck's
 * outermost cells. Only replaceable blocks are touched, so track, platforms
 * and existing structure survive; fence-likes connect themselves via normal
 * block updates. Sneak-right-click a block to choose the material, then
 * right-click two connected rail nodes.
 */
public class ItemRailingCreator extends ItemNodeModifierSelectableBlockBase {
    /** Deck width this railing matches; railings go at ±width/2 blocks. */
    public final int width;

    public ItemRailingCreator(int width) {
        super(true, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
        this.width = width;
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

        int offset = width / 2; // deck spans -offset..+offset
        Set<BlockPos> placedPositions = new HashSet<>();
        int[] placed = {0};
        RailBuildHelper.walk(rail.railMath, 0.15, 0.3, (center, perpendicular) -> {
            if (perpendicular == null) {
                return;
            }
            int y = (int) Math.floor(center.y); // on top of the deck (deck top = rail base level)
            for (int side = -1; side <= 1; side += 2) {
                double distance = side * offset;
                org.mtr.core.tool.Vector edge = center.add(perpendicular.multiply(distance, 0, distance));
                BlockPos pos = new BlockPos((int) Math.floor(edge.x), y, (int) Math.floor(edge.z));
                if (placedPositions.add(pos) && world.getBlockState(pos).isReplaceable()) {
                    world.setBlockState(pos, state, 3);
                    placed[0]++;
                }
            }
        });
        player.data.sendMessage(Text.translatable("msg.station_announcer.railing.built", placed[0]), true);
    }


    /** "Flashing" while armed: enchant glint between the first and second node click. */
    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.railing_creator", width)
                .formatted(TextFormatting.GRAY));
        super.addTooltips(stack, world, tooltip, options); // saved material + usage hints
    }
}
