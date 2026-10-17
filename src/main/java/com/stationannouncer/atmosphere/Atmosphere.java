package com.stationannouncer.atmosphere;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.stationannouncer.StationAnnouncer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.function.Consumer;

/**
 * The lived-in station layer (ATMOSPHERE_PLAN.md): failing lights on any
 * light block, out-of-service fare equipment, grime decals, litter and
 * trash, construction props, and the Weathering Tool that dresses a whole
 * area. Everything registers from here into its own creative tab,
 * "Baker City: Atmosphere".
 */
public final class Atmosphere {
    /** C2S: the maintenance screen picked a condition (hand bool, condition ordinal). */
    public static final Identifier MAINTENANCE_SELECT_C2S = StationAnnouncer.id("maintenance_select");

    /** Installed by the client: opens the maintenance tool's condition picker. */
    public static Consumer<Hand> MAINTENANCE_SCREEN = hand -> {
    };
    /** Installed by the client: opens the weathering tool's settings. */
    public static Consumer<Hand> WEATHERING_SCREEN = hand -> {
    };

    public static final ItemGroup TAB = FabricItemGroup.builder()
            .icon(() -> new ItemStack(Registries.ITEM.get(StationAnnouncer.id("traffic_cone"))))
            .displayName(Text.translatable("itemGroup.station_announcer.atmosphere"))
            .entries((context, entries) -> AtmosphereRegistry.ENTRIES.forEach(entries::add))
            .build();

    public static MaintenanceToolItem MAINTENANCE_TOOL;

    private Atmosphere() {
    }

    public static void register() {
        Registry.register(Registries.ITEM_GROUP, StationAnnouncer.id("atmosphere"), TAB);

        MAINTENANCE_TOOL = AtmosphereRegistry.item("maintenance_tool", new MaintenanceToolItem(new Item.Settings().maxCount(1)));

        ConstructionBlocks.register();
        GrimeBlocks.register();
        LitterBlocks.register();
        WearBlocks.register();
        Weathering.register();

        LightConditions.register();
        OutOfService.register();

        ServerPlayNetworking.registerGlobalReceiver(MAINTENANCE_SELECT_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            LightCondition condition = LightCondition.byOrdinal(buf.readVarInt());
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof MaintenanceToolItem) {
                    MaintenanceToolItem.select(stack, condition);
                }
            });
        });
        registerCommand();
    }

    // ------------------------------------------------------------- command

    private static final SuggestionProvider<ServerCommandSource> CONDITIONS = (ctx, builder) -> {
        for (LightCondition c : LightCondition.values()) {
            builder.suggest(c.id);
        }
        return builder.buildFuture();
    };

    private static LightCondition condition(CommandContext<ServerCommandSource> ctx) {
        LightCondition c = LightCondition.byId(StringArgumentType.getString(ctx, "condition"));
        if (c == null) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.atmosphere.unknown_condition"));
        }
        return c;
    }

    /** Every glowing block in a box (capped) gets the condition with the given chance. */
    private static int lights(CommandContext<ServerCommandSource> ctx, int percent) {
        LightCondition c = condition(ctx);
        if (c == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        BlockPos a = BlockPosArgumentType.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgumentType.getBlockPos(ctx, "to");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > 2_000_000) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.atmosphere.too_big"));
            return 0;
        }
        Random random = world.getRandom();
        Long2IntOpenHashMap changes = new Long2IntOpenHashMap();
        for (BlockPos p : BlockPos.iterate(min, max)) {
            if (LightConditions.canGlow(world.getBlockState(p)) && random.nextInt(100) < percent) {
                changes.put(p.asLong(), LightConditionState.pack(c, random.nextInt(256)));
            }
        }
        int n = LightConditions.setAll(world, changes);
        ctx.getSource().sendFeedback(() -> Text.translatable("commands.station_announcer.atmosphere.lights_set", n,
                Text.translatable("atmosphere.station_announcer.light." + c.id)), true);
        return n;
    }

    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("atmosphere")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("light")
                                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("condition", StringArgumentType.word())
                                                .suggests(CONDITIONS)
                                                .executes(ctx -> {
                                                    LightCondition c = condition(ctx);
                                                    if (c == null) {
                                                        return 0;
                                                    }
                                                    BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
                                                    if (!LightConditions.set(ctx.getSource().getWorld(), pos, c, 0)) {
                                                        ctx.getSource().sendError(Text.translatable("commands.station_announcer.atmosphere.not_a_light"));
                                                        return 0;
                                                    }
                                                    ctx.getSource().sendFeedback(() -> Text.translatable(
                                                            "commands.station_announcer.atmosphere.light_set", pos.toShortString(),
                                                            Text.translatable("atmosphere.station_announcer.light." + c.id)), true);
                                                    return 1;
                                                }))))
                        .then(CommandManager.literal("lights")
                                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                                .then(CommandManager.argument("condition", StringArgumentType.word())
                                                        .suggests(CONDITIONS)
                                                        .executes(ctx -> lights(ctx, 100))
                                                        .then(CommandManager.argument("percent", IntegerArgumentType.integer(1, 100))
                                                                .executes(ctx -> lights(ctx, IntegerArgumentType.getInteger(ctx, "percent"))))))))
                        .then(CommandManager.literal("service")
                                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.literal("out").executes(ctx -> service(ctx, true)))
                                        .then(CommandManager.literal("in").executes(ctx -> service(ctx, false)))))
                        .then(Weathering.command())));
    }

    private static int service(CommandContext<ServerCommandSource> ctx, boolean out) {
        BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
        if (!OutOfService.set(ctx.getSource().getWorld(), pos, out)) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.atmosphere.not_equipment"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.translatable(out ? "commands.station_announcer.atmosphere.service_out"
                : "commands.station_announcer.atmosphere.service_in", pos.toShortString()), true);
        return 1;
    }
}
