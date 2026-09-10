package com.stationannouncer.mtr;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.mod.item.ItemNodeModifierBase;

/**
 * {@code /bridge} — drives the held Bridge Creator without clicking:
 * <ul>
 *   <li>{@code /bridge build <x1 y1 z1> <x2 y2 z2>} builds under the rail
 *       between two nodes with the held creator's settings (in manual mode the
 *       rail is added to the selection instead);</li>
 *   <li>{@code /bridge tracks build|clear} builds from / clears the manual
 *       selection;</li>
 *   <li>{@code /bridge preset <name>} loads a built-in preset into the held
 *       creator;</li>
 *   <li>{@code /bridge undo} puts back the last build's blocks.</li>
 * </ul>
 * Permission level 2 (the same as /announce by default) — it places blocks.
 */
public final class BridgeCommand {
    private BridgeCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("bridge")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("build")
                                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                                .executes(BridgeCommand::build))))
                        .then(CommandManager.literal("tracks")
                                .then(CommandManager.literal("build").executes(ctx -> withCreator(ctx, (player, stack) -> {
                                    BridgeService.buildManual(player, stack);
                                    return 1;
                                })))
                                .then(CommandManager.literal("clear").executes(ctx -> withCreator(ctx, (player, stack) -> {
                                    BridgeService.clearManualTracks(stack);
                                    player.sendMessage(Text.translatable("msg.station_announcer.bridge.tracks_cleared"), true);
                                    return 1;
                                }))))
                        .then(CommandManager.literal("preset")
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            for (BridgePresets.Preset p : BridgePresets.BUILTIN) {
                                                builder.suggest("\"" + p.name() + "\"");
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> withCreator(ctx, (player, stack) -> {
                                            String name = StringArgumentType.getString(ctx, "name").replace("\"", "").trim();
                                            BridgeSpec preset = BridgePresets.byName(name);
                                            if (preset == null) {
                                                ctx.getSource().sendError(Text.translatable("commands.station_announcer.bridge.unknown_preset", name));
                                                return 0;
                                            }
                                            BridgeSpec current = BridgeSpec.read(stack);
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
                            BridgeService.undo(player);
                            return 1;
                        }))));
    }

    private interface CreatorAction {
        int run(ServerPlayerEntity player, ItemStack stack);
    }

    private static int withCreator(CommandContext<ServerCommandSource> ctx, CreatorAction action) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.bridge.player_only"));
            return 0;
        }
        ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof ItemBridgeCreator)) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.bridge.hold_creator"));
            return 0;
        }
        return action.run(player, stack);
    }

    private static int build(CommandContext<ServerCommandSource> ctx) {
        return withCreator(ctx, (player, stack) -> {
            BlockPos from;
            BlockPos to;
            try {
                from = BlockPosArgumentType.getLoadedBlockPos(ctx, "from");
                to = BlockPosArgumentType.getLoadedBlockPos(ctx, "to");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                ctx.getSource().sendError(Text.literal(e.getMessage()));
                return 0;
            }
            org.mtr.mapping.holder.World world = new org.mtr.mapping.holder.World(player.getServerWorld());
            ItemNodeModifierBase.getRail(world, new org.mtr.mapping.holder.BlockPos(from), new org.mtr.mapping.holder.BlockPos(to),
                    new org.mtr.mapping.holder.ServerPlayerEntity(player), rail -> {
                        if (rail == null) {
                            player.sendMessage(Text.translatable("commands.station_announcer.bridge.no_rail"), true);
                            return;
                        }
                        if (BridgeSpec.read(stack).trackMode == BridgeSpec.TrackMode.MANUAL) {
                            BridgeService.addManualTrack(player, stack, rail);
                        } else {
                            BridgeService.buildFromRail(player, stack, rail);
                        }
                    });
            return 1;
        });
    }
}
