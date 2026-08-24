package com.stationannouncer.item;

import com.stationannouncer.block.HandrailBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import java.util.List;

/**
 * Block item shared by the four handrail styles: right-clicking IN THE AIR
 * cycles which VARIANT the next placement uses (flat / 45° stairs / the two
 * stair-to-floor transitions / both corners), stored on the stack and read
 * back by {@link HandrailBlock#getPlacementState} — the MSD decoration UX.
 */
public class HandrailItem extends BlockItem {
    private static final String VARIANT_KEY = "HandrailVariant";

    public HandrailItem(Block block, Settings settings) {
        super(block, settings);
    }

    public static HandrailBlock.Variant selectedVariant(ItemStack stack) {
        int ordinal = stack.hasNbt() ? stack.getNbt().getInt(VARIANT_KEY) : 0;
        HandrailBlock.Variant[] values = HandrailBlock.Variant.values();
        return values[Math.floorMod(ordinal, values.length)];
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        // Only reached when the click did NOT target a block — the cycle gesture.
        ItemStack stack = player.getStackInHand(hand);
        HandrailBlock.Variant next = selectedVariant(stack).next();
        stack.getOrCreateNbt().putInt(VARIANT_KEY, next.ordinal());
        if (!world.isClient) {
            player.sendMessage(Text.translatable("gui.station_announcer.handrail_mode",
                    Text.translatable("gui.station_announcer.handrail_variant." + next.asString())), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        tooltip.add(Text.translatable("gui.station_announcer.handrail_mode",
                Text.translatable("gui.station_announcer.handrail_variant." + selectedVariant(stack).asString()))
                .formatted(net.minecraft.util.Formatting.GRAY));
        tooltip.add(Text.translatable("gui.station_announcer.handrail_hint")
                .formatted(net.minecraft.util.Formatting.DARK_GRAY));
    }
}
