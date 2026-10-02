package com.stationannouncer.mtr;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.mtr.mod.item.ItemNodeModifierBase;

/**
 * Registration for the earthworks tools: the three items (Operations tab),
 * their packets, the applier and {@code /earthworks}:
 * <ul>
 *   <li>{@code /earthworks build <from> <to>} — along the rail between two nodes, held tool's settings;</li>
 *   <li>{@code /earthworks line <from> <to>} — along a straight line (no rail needed);</li>
 *   <li>{@code /earthworks preset "<name>"} — load a built-in preset into the held tool;</li>
 *   <li>{@code /earthworks undo}.</li>
 * </ul>
 */
public final class Earthworks {
    /** C2S: the settings screen saves the held tool's spec (hand + NBT). */
    public static final Identifier UPDATE_C2S = StationAnnouncer.id("update_earthworks");
    /** C2S: screen actions (hand + action byte). */
    public static final Identifier ACTION_C2S = StationAnnouncer.id("earthworks_action");
    public static final byte ACTION_UNDO = 0;

    public static ItemEarthworksCreator EMBANKMENT_CREATOR;
    public static ItemEarthworksCreator TRENCH_CREATOR;
    public static ItemEarthworksCreator ROW_CLEARER;

    private Earthworks() {
    }

    public static void register() {
        EMBANKMENT_CREATOR = item(EarthworksSpec.Kind.EMBANKMENT);
        TRENCH_CREATOR = item(EarthworksSpec.Kind.TRENCH);
        ROW_CLEARER = item(EarthworksSpec.Kind.CLEARER);

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_C2S, (server, player, handler, buf, responseSender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            net.minecraft.nbt.NbtCompound nbt = buf.readNbt();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof ItemEarthworksCreator creator && nbt != null) {
                    EarthworksSpec.fromNbt(creator.kind, nbt).write(stack);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION_C2S, (server, player, handler, buf, responseSender) -> {
            buf.readBoolean();
            byte action = buf.readByte();
            server.execute(() -> {
                if (action == ACTION_UNDO) {
                    EarthworksService.undo(player);
                }
            });
        });
        EarthworksService.register();
        registerCommand();
    }

    private static ItemEarthworksCreator item(EarthworksSpec.Kind kind) {
        ItemEarthworksCreator item = new ItemEarthworksCreator(kind);
        Registry.register(Registries.ITEM, StationAnnouncer.id(EarthworksService.itemId(kind)), item);
        ModContent.OPERATIONS_ENTRIES.add(item);
        return item;
    }

    // ------------------------------------------------------------- command

    private interface Action {
        int run(ServerPlayerEntity player, ItemStack stack, ItemEarthworksCreator creator);
    }

    private static int withTool(CommandContext<ServerCommandSource> ctx, Action action) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.bridge.player_only"));
            return 0;
        }
        ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof ItemEarthworksCreator creator)) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.earthworks.hold_tool"));
            return 0;
        }
        return action.run(player, stack, creator);
    }

    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("earthworks")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("build")
                                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                                .executes(ctx -> withTool(ctx, (player, stack, creator) -> {
                                                    BlockPos from = BlockPosArgumentType.getBlockPos(ctx, "from");
                                                    BlockPos to = BlockPosArgumentType.getBlockPos(ctx, "to");
                                                    org.mtr.mapping.holder.World world = new org.mtr.mapping.holder.World(player.getServerWorld());
                                                    ItemNodeModifierBase.getRail(world, new org.mtr.mapping.holder.BlockPos(from),
                                                            new org.mtr.mapping.holder.BlockPos(to),
                                                            new org.mtr.mapping.holder.ServerPlayerEntity(player), rail -> {
                                                                if (rail == null) {
                                                                    player.sendMessage(Text.translatable("commands.station_announcer.bridge.no_rail"), true);
                                                                } else {
                                                                    EarthworksService.buildFromRail(player, stack, creator.kind, rail);
                                                                }
                                                            });
                                                    return 1;
                                                })))))
                        .then(CommandManager.literal("line")
                                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                                .executes(ctx -> withTool(ctx, (player, stack, creator) -> {
                                                    EarthworksService.buildLine(player, stack, creator.kind,
                                                            BlockPosArgumentType.getBlockPos(ctx, "from"),
                                                            BlockPosArgumentType.getBlockPos(ctx, "to"));
                                                    return 1;
                                                })))))
                        .then(CommandManager.literal("preset")
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            for (EarthworksPresets.Preset p : EarthworksPresets.BUILTIN) {
                                                builder.suggest("\"" + p.name() + "\"");
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> withTool(ctx, (player, stack, creator) -> {
                                            String name = StringArgumentType.getString(ctx, "name").replace("\"", "").trim();
                                            EarthworksSpec preset = EarthworksPresets.byName(creator.kind, name);
                                            if (preset == null) {
                                                ctx.getSource().sendError(Text.translatable("commands.station_announcer.bridge.unknown_preset", name));
                                                return 0;
                                            }
                                            EarthworksSpec current = EarthworksSpec.read(creator.kind, stack);
                                            preset.trackMode = current.trackMode;
                                            preset.trackReach = current.trackReach;
                                            preset.write(stack);
                                            player.sendMessage(Text.translatable("commands.station_announcer.bridge.preset_loaded", name), true);
                                            return 1;
                                        }))))
                        .then(CommandManager.literal("undo").executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                return 0;
                            }
                            EarthworksService.undo(player);
                            return 1;
                        }))));
    }
}
