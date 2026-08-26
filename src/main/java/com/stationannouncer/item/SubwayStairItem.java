package com.stationannouncer.item;

import com.stationannouncer.block.SubwayStairBlock;
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
 * Block item for the modern (floating) subway stair: right-clicking IN THE
 * AIR cycles whether the next placement is the see-through floating
 * construction or the closed one, stored on the stack and read back by
 * {@link SubwayStairBlock#getPlacementState} — the handrail item's UX.
 * Placed stairs can still be toggled in place with a bare-hand right-click.
 */
public class SubwayStairItem extends BlockItem {
    private static final String SOLID_KEY = "StairSolid";

    public SubwayStairItem(Block block, Settings settings) {
        super(block, settings);
    }

    public static boolean selectedSolid(ItemStack stack) {
        return stack.hasNbt() && stack.getNbt().getBoolean(SOLID_KEY);
    }

    private static Text modeText(boolean solid) {
        return Text.translatable("gui.station_announcer.stair_mode",
                Text.translatable("gui.station_announcer.stair_variant." + (solid ? "solid" : "floating")));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        // Only reached when the click did NOT target a block — the cycle gesture.
        ItemStack stack = player.getStackInHand(hand);
        boolean next = !selectedSolid(stack);
        stack.getOrCreateNbt().putBoolean(SOLID_KEY, next);
        if (!world.isClient) {
            player.sendMessage(modeText(next), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        tooltip.add(modeText(selectedSolid(stack)).copy()
                .formatted(net.minecraft.util.Formatting.GRAY));
        tooltip.add(Text.translatable("gui.station_announcer.stair_hint")
                .formatted(net.minecraft.util.Formatting.DARK_GRAY));
    }
}
