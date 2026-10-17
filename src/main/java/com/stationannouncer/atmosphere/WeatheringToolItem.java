package com.stationannouncer.atmosphere;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Weathering Tool: dresses an area as lived-in, gritty, neglected or
 * abandoned (or cleans it back to pristine).
 * <ul>
 *   <li>right-click the air: settings (preset, amounts per layer, area);</li>
 *   <li>right-click a block, then the opposite corner: weather that box
 *       (same height → up to {@code height} blocks above);</li>
 *   <li>sneak-right-click a block: weather the whole MTR station there;</li>
 *   <li>sneak-right-click the air: forget the first corner.</li>
 * </ul>
 */
public class WeatheringToolItem extends Item {
    public static final String TAG_CORNER = "Corner";
    private static final String TAG_CORNER_DIM = "CornerDim";

    public WeatheringToolItem(Settings settings) {
        super(settings);
    }

    @Nullable
    public static BlockPos corner(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.contains(TAG_CORNER) ? BlockPos.fromLong(nbt.getLong(TAG_CORNER)) : null;
    }

    public static void clearCorner(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt != null) {
            nbt.remove(TAG_CORNER);
            nbt.remove(TAG_CORNER_DIM);
        }
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.SUCCESS;
        }
        if (!player.canModifyBlocks()) {
            return ActionResult.FAIL;
        }
        ItemStack stack = context.getStack();
        BlockPos pos = context.getBlockPos();
        WeatherSpec spec = WeatherSpec.read(stack);
        if (player.isSneaking()) {
            clearCorner(stack);
            Weathering.weatherStation(serverPlayer, pos, spec);
            return ActionResult.CONSUME;
        }
        BlockPos first = corner(stack);
        String dim = world.getRegistryKey().getValue().toString();
        NbtCompound nbt = stack.getOrCreateNbt();
        if (first == null || !dim.equals(nbt.getString(TAG_CORNER_DIM))) {
            nbt.putLong(TAG_CORNER, pos.asLong());
            nbt.putString(TAG_CORNER_DIM, dim);
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.corner", pos.toShortString()), true);
            return ActionResult.CONSUME;
        }
        clearCorner(stack);
        Weathering.weatherBox(serverPlayer, first, pos, spec);
        return ActionResult.CONSUME;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (player.isSneaking()) {
            if (!world.isClient() && corner(stack) != null) {
                clearCorner(stack);
                player.sendMessage(Text.translatable("msg.station_announcer.weathering.corner_cleared"), true);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
        if (world.isClient()) {
            Atmosphere.WEATHERING_SCREEN.accept(hand);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return corner(stack) != null;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        WeatherSpec spec = WeatherSpec.read(stack);
        WeatherSpec.Preset preset = spec.matching();
        tooltip.add(Text.translatable("tooltip.station_announcer.weathering.preset", preset == null
                ? Text.translatable("gui.station_announcer.weathering.custom")
                : Text.translatable("gui.station_announcer.weathering.preset." + preset.id)).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.weathering.use").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.weathering.use2").formatted(Formatting.DARK_GRAY));
    }
}
