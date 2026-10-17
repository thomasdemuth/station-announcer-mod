package com.stationannouncer.atmosphere;

import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
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
 * The Maintenance Tool: puts any light into a condition (flickering, dim,
 * dead…) or repairs it, and switches fare equipment in / out of service.
 * <ul>
 *   <li>right-click the air: pick the condition it applies (screen);</li>
 *   <li>right-click a light: apply it (again = repair);</li>
 *   <li>sneak-right-click a light: repair;</li>
 *   <li>sneak-right-click the air: reroll the next light's pattern
 *       (a new blink rhythm for the same condition).</li>
 * </ul>
 * While held, every light with a condition shows a coloured marker.
 */
public class MaintenanceToolItem extends Item {
    private static final String TAG_CONDITION = "Condition";
    private static final String TAG_SALT = "Salt";

    public MaintenanceToolItem(Settings settings) {
        super(settings);
    }

    public static LightCondition selected(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        LightCondition c = nbt == null ? null : LightCondition.byId(nbt.getString(TAG_CONDITION));
        return c == null ? LightCondition.STUTTER : c;
    }

    public static void select(ItemStack stack, LightCondition condition) {
        stack.getOrCreateNbt().putString(TAG_CONDITION, condition.id);
    }

    private static int salt(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt == null ? 0 : nbt.getInt(TAG_SALT) & 0xFF;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof com.stationannouncer.mtr.ServicePosterBlock) {
            if (!world.isClient() && player != null && player.canModifyBlocks()) {
                BlockPos data = state.get(com.stationannouncer.mtr.ServicePosterBlock.HALF)
                        == net.minecraft.block.enums.DoubleBlockHalf.LOWER ? pos : pos.down();
                if (world.getBlockEntity(data) instanceof com.stationannouncer.mtr.ServicePosterBlockEntity poster) {
                    int wear = player.isSneaking() ? 0 : (poster.getWear() + 1) % 4;
                    poster.setWear(wear);
                    poster.sync();
                    player.sendMessage(Text.translatable("msg.station_announcer.maintenance.poster_wear",
                            Text.translatable("atmosphere.station_announcer.wear." + wear)), true);
                }
            }
            return ActionResult.success(world.isClient());
        }
        if (OutOfService.toggleable(state)) {
            if (!world.isClient() && player != null) {
                OutOfService.toggle((ServerWorld) world, pos, state, player);
            }
            return ActionResult.success(world.isClient());
        }
        if (!LightConditions.canGlow(state)) {
            return ActionResult.PASS;
        }
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        if (player != null && !player.canModifyBlocks()) {
            return ActionResult.FAIL;
        }
        ServerWorld server = (ServerWorld) world;
        ItemStack stack = context.getStack();
        LightCondition current = LightConditions.get(server, pos);
        LightCondition wanted = selected(stack);
        LightCondition next = player != null && player.isSneaking() || current == wanted ? LightCondition.HEALTHY : wanted;
        LightConditions.set(server, pos, next, salt(stack));
        if (player != null) {
            player.sendMessage(Text.translatable("msg.station_announcer.maintenance.set",
                    Text.translatable(state.getBlock().getTranslationKey()),
                    Text.translatable("atmosphere.station_announcer.light." + next.id)), true);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (player.isSneaking()) {
            if (!world.isClient()) {
                int salt = (salt(stack) + 1) & 0xFF;
                stack.getOrCreateNbt().putInt(TAG_SALT, salt);
                player.sendMessage(Text.translatable("msg.station_announcer.maintenance.reroll", salt), true);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
        if (world.isClient()) {
            Atmosphere.MAINTENANCE_SCREEN.accept(hand);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.station_announcer.maintenance.applies",
                Text.translatable("atmosphere.station_announcer.light." + selected(stack).id)).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.maintenance.use").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("tooltip.station_announcer.maintenance.use2").formatted(Formatting.DARK_GRAY));
    }
}
