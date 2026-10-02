package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ramp rail item, MSD style: right-clicking IN THE AIR cycles the
 * {@link RampRailBlock.Shape} the next placement uses (stored on the stack).
 * Height and direction always come from the ramp, side from the click.
 */
public class RampRailItem extends BlockItem {
    private static final String SHAPE_KEY = "RampRailShape";

    public RampRailItem(Block block, Settings settings) {
        super(block, settings);
    }

    public static RampRailBlock.Shape selectedShape(ItemStack stack) {
        int ordinal = stack.hasNbt() ? stack.getNbt().getInt(SHAPE_KEY) : 0;
        RampRailBlock.Shape[] values = RampRailBlock.Shape.values();
        return values[Math.floorMod(ordinal, values.length)];
    }

    private static Text shapeText(RampRailBlock.Shape shape) {
        return Text.translatable("gui.station_announcer.ramp_rail_shape." + shape.asString());
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        // Only reached when the click did NOT target a block — the cycle gesture.
        ItemStack stack = player.getStackInHand(hand);
        RampRailBlock.Shape next = selectedShape(stack).next();
        stack.getOrCreateNbt().putInt(SHAPE_KEY, next.ordinal());
        if (!world.isClient) {
            player.sendMessage(Text.translatable("gui.station_announcer.ramp_rail_mode", shapeText(next),
                    next.ordinal() + 1, RampRailBlock.Shape.values().length), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        RampRailBlock.Shape shape = selectedShape(stack);
        tooltip.add(Text.translatable("gui.station_announcer.ramp_rail_models", RampRailBlock.Shape.values().length)
                .formatted(Formatting.GOLD));
        tooltip.add(Text.translatable("gui.station_announcer.ramp_rail_mode", shapeText(shape),
                shape.ordinal() + 1, RampRailBlock.Shape.values().length).formatted(Formatting.GOLD));
        tooltip.add(Text.translatable("gui.station_announcer.ramp_rail_hint").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("gui.station_announcer.ramp_rail_hint_follow").formatted(Formatting.DARK_GRAY));
    }
}
