package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The item form of a material ramp / stair. Carries its material in NBT
 * ({@link MaterialPalette#NBT_KEY}); the name reads "Ramp (Oak Planks)".
 *
 * <ul>
 *   <li>right-click in the air → the texture picker;</li>
 *   <li>sneak + right-click any block → take that block as the material
 *       (its exact state, so a sideways log stays sideways) — nothing is
 *       placed;</li>
 *   <li>sneak + right-click a PLACED ramp / stair → the picker for that
 *       block (optionally its whole connected run);</li>
 *   <li>plain right-click on a block → place, wearing the material.</li>
 * </ul>
 */
public class MaterialBlockItem extends BlockItem {
    public MaterialBlockItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player != null && player.isSneaking()) {
            BlockState clicked = context.getWorld().getBlockState(context.getBlockPos());
            if (clicked.getBlock() instanceof MaterialBlock) {
                // Sneak-clicking a placed ramp / stair retextures IT (or its whole run).
                if (context.getWorld().isClient) {
                    MaterialBlocks.BLOCK_PICKER_OPENER.accept(context.getBlockPos());
                }
                return ActionResult.success(context.getWorld().isClient);
            }
            if (!MaterialPalette.isUsable(clicked)) {
                if (!context.getWorld().isClient) {
                    player.sendMessage(Text.translatable("msg.station_announcer.material.not_full_block",
                            clicked.getBlock().getName()), true);
                }
                return ActionResult.FAIL;
            }
            String material = MaterialPalette.stringify(clicked);
            MaterialPalette.setMaterial(context.getStack(), material);
            if (!context.getWorld().isClient) {
                player.sendMessage(Text.translatable("msg.station_announcer.material.picked",
                        clicked.getBlock().getName()), true);
            }
            return ActionResult.success(context.getWorld().isClient);
        }
        return super.useOnBlock(context);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient) {
            MaterialBlocks.PICKER_OPENER.accept(hand);
        }
        return TypedActionResult.success(user.getStackInHand(hand), world.isClient);
    }

    @Override
    public Text getName(ItemStack stack) {
        return Text.translatable(getTranslationKey(stack) + ".named",
                MaterialPalette.displayName(MaterialPalette.materialOf(stack)));
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.station_announcer.material.pick").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.material.copy").formatted(Formatting.DARK_GRAY));
        if (getBlock() instanceof MaterialRampBlock) {
            tooltip.add(Text.translatable("tooltip.station_announcer.material.ramp").formatted(Formatting.DARK_GRAY));
        }
    }
}
