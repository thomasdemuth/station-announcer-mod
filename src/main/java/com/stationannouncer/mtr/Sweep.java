package com.stationannouncer.mtr;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import org.mtr.mod.item.ItemNodeModifierBase;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Registration for the Structure Sweeper (STRUCTURE_SWEEP_PLAN.md): the
 * Template Wand and the sweeper item (Operations tab), the world's template
 * store, the packets and {@code /sweep}:
 * <ul>
 *   <li>{@code /sweep template list | save <name> | capture <c1> <c2> <rail> <facing> <name> | delete | export | import};</li>
 *   <li>{@code /sweep use <name>} — load a template into the held sweeper;</li>
 *   <li>{@code /sweep build <from> <to>} (rail between two nodes) and {@code /sweep line <from> <to>} (straight, no rail);</li>
 *   <li>{@code /sweep undo}.</li>
 * </ul>
 * Using the tools needs creative mode or operator rights; the command needs operator rights.
 */
public final class Sweep {
    /** C2S: save the wand's box as a template (hand, name, trim empty space). */
    public static final Identifier SAVE_TEMPLATE_C2S = StationAnnouncer.id("sweep_save_template");
    /** C2S: the sweeper screen saves the held tool's settings (hand + NBT). */
    public static final Identifier UPDATE_C2S = StationAnnouncer.id("update_sweep");
    /** C2S: screen actions (action byte + string). */
    public static final Identifier ACTION_C2S = StationAnnouncer.id("sweep_action");
    /** C2S: list request → {@link #LIST_S2C}. */
    public static final Identifier LIST_REQUEST_C2S = StationAnnouncer.id("sweep_list_request");
    /** S2C: every template's name, author and size. */
    public static final Identifier LIST_S2C = StationAnnouncer.id("sweep_list");
    /** C2S: one template's cells (name) → {@link #TEMPLATE_S2C}. */
    public static final Identifier TEMPLATE_REQUEST_C2S = StationAnnouncer.id("sweep_template_request");
    /** S2C: name + gzipped template NBT (empty when too large to send). */
    public static final Identifier TEMPLATE_S2C = StationAnnouncer.id("sweep_template");
    /** S2C: open the wand's save screen (hand). */
    public static final Identifier OPEN_SAVE_S2C = StationAnnouncer.id("sweep_open_save");

    /** S2C: the track picked with the sweeper (point count + x,y,z doubles, first node first). */
    public static final Identifier TRACK_S2C = StationAnnouncer.id("sweep_track");
    /** C2S: Build pressed (hand + spec NBT) — sweep along the picked track. */
    public static final Identifier BUILD_C2S = StationAnnouncer.id("sweep_build");
    /** C2S: the picked track was dropped (Cancel). */
    public static final Identifier CANCEL_C2S = StationAnnouncer.id("sweep_cancel");

    public static final byte ACTION_UNDO = 0;
    public static final byte ACTION_DELETE = 1;
    public static final byte ACTION_CLEAR_WAND = 2;

    /** Templates bigger than this (gzipped) are not sent to clients for the preview. */
    private static final int MAX_SEND = 900_000;

    /** Client hook: opens the wand's save screen for a hand. */
    public static Consumer<Hand> SAVE_SCREEN_OPENER = hand -> {
    };

    public static ItemSweepWand TEMPLATE_WAND;
    public static ItemStructureSweeper STRUCTURE_SWEEPER;

    private Sweep() {
    }

    public static boolean allowed(ServerPlayerEntity player) {
        return player.isCreative() || player.hasPermissionLevel(2);
    }

    public static void register() {
        TEMPLATE_WAND = Registry.register(Registries.ITEM, StationAnnouncer.id("sweep_wand"), new ItemSweepWand());
        STRUCTURE_SWEEPER = Registry.register(Registries.ITEM, StationAnnouncer.id("structure_sweeper"), new ItemStructureSweeper());
        ModContent.OPERATIONS_ENTRIES.add(TEMPLATE_WAND);
        ModContent.OPERATIONS_ENTRIES.add(STRUCTURE_SWEEPER);
        SweepTemplateStore.register();

        ServerPlayNetworking.registerGlobalReceiver(SAVE_TEMPLATE_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            String name = buf.readString(SweepTemplateStore.MAX_NAME * 4);
            boolean trim = buf.readBoolean();
            server.execute(() -> saveFromWand(player, player.getStackInHand(hand), name, trim));
        });
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            NbtCompound nbt = buf.readNbt();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof ItemStructureSweeper && nbt != null) {
                    SweepSpec.fromNbt(nbt).write(stack);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION_C2S, (server, player, handler, buf, sender) -> {
            byte action = buf.readByte();
            String arg = buf.readString(256);
            server.execute(() -> {
                switch (action) {
                    case ACTION_UNDO -> EarthworksService.undo(player);
                    case ACTION_DELETE -> {
                        if (player.hasPermissionLevel(2) || player.isCreative()) {
                            if (SweepTemplateStore.delete(arg)) {
                                player.sendMessage(Text.translatable("msg.station_announcer.sweep.deleted", arg), true);
                            }
                            sendList(player);
                        }
                    }
                    case ACTION_CLEAR_WAND -> {
                        for (Hand h : Hand.values()) {
                            if (player.getStackInHand(h).getItem() instanceof ItemSweepWand) {
                                ItemSweepWand.clear(player.getStackInHand(h));
                            }
                        }
                    }
                    default -> {
                    }
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(BUILD_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            NbtCompound nbt = buf.readNbt();
            server.execute(() -> {
                SweepSpec spec = SweepSpec.fromNbt(nbt);
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof ItemStructureSweeper) {
                    spec.write(stack);
                }
                SweepService.buildPending(player, spec);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(CANCEL_C2S, (server, player, handler, buf, sender) ->
                server.execute(() -> SweepService.cancelPending(player.getUuid())));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SweepService.cancelPending(handler.player.getUuid()));
        ServerPlayNetworking.registerGlobalReceiver(LIST_REQUEST_C2S, (server, player, handler, buf, sender) ->
                server.execute(() -> sendList(player)));
        ServerPlayNetworking.registerGlobalReceiver(TEMPLATE_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            String name = buf.readString(SweepTemplateStore.MAX_NAME * 4);
            server.execute(() -> {
                SweepTemplate t = SweepTemplateStore.get(name);
                PacketByteBuf out = PacketByteBufs.create();
                out.writeString(name);
                byte[] bytes = t == null ? new byte[0] : t.toBytes();
                out.writeByteArray(bytes.length > MAX_SEND ? new byte[0] : bytes);
                ServerPlayNetworking.send(player, TEMPLATE_S2C, out);
            });
        });
        registerCommand();
    }

    static void openSaveScreen(ServerPlayerEntity player, Hand hand) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeBoolean(hand == Hand.OFF_HAND);
        ServerPlayNetworking.send(player, OPEN_SAVE_S2C, out);
    }

    static void sendTrack(ServerPlayerEntity player, SweepGeometry.Polyline line) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeVarInt(line.size());
        for (int i = 0; i < line.size(); i++) {
            out.writeDouble(line.x[i]);
            out.writeDouble(line.y[i]);
            out.writeDouble(line.z[i]);
        }
        ServerPlayNetworking.send(player, TRACK_S2C, out);
    }

    static void sendList(ServerPlayerEntity player) {
        List<SweepTemplate> all = SweepTemplateStore.all();
        PacketByteBuf out = PacketByteBufs.create();
        out.writeVarInt(all.size());
        for (SweepTemplate t : all) {
            out.writeString(t.name);
            out.writeString(t.author);
            out.writeVarInt(t.length);
            out.writeVarInt(t.width);
            out.writeVarInt(t.height);
            out.writeVarInt(t.solidCount());
            out.writeLong(t.created);
        }
        ServerPlayNetworking.send(player, LIST_S2C, out);
    }

    /** Captures the wand's box as a template; returns true when saved. */
    static boolean saveFromWand(ServerPlayerEntity player, ItemStack stack, String rawName, boolean trim) {
        if (!allowed(player)) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.not_allowed"), true);
            return false;
        }
        if (!(stack.getItem() instanceof ItemSweepWand)) {
            return false;
        }
        ItemSweepWand.Selection sel = ItemSweepWand.read(stack);
        if (!sel.complete()) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.incomplete"), true);
            return false;
        }
        boolean ok = capture(player.getServerWorld(), player, SweepService.Feedback.of(player), player.getName().getString(),
                sel.c1(), sel.c2(), trim, rawName);
        if (ok) {
            ItemSweepWand.clear(stack);
        }
        return ok;
    }

    /** Captures a box as a template; {@code player} (may be null) gets the new template loaded into a held sweeper. */
    static boolean capture(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity player, SweepService.Feedback feedback,
                           String author, BlockPos c1, BlockPos c2, boolean trim, String rawName) {
        String name = SweepTemplateStore.cleanName(rawName);
        if (name == null) {
            feedback.send(Text.translatable("msg.station_announcer.sweep.bad_name"), true);
            return false;
        }
        SweepTemplate.Capture capture = SweepTemplate.capture(world, c1, c2, trim, name, author);
        if (capture.template() == null) {
            feedback.send(Text.translatable(capture.errorKey(), capture.errorArgs()), false);
            return false;
        }
        boolean replaced = SweepTemplateStore.get(name) != null;
        if (!SweepTemplateStore.save(capture.template())) {
            feedback.send(Text.translatable("msg.station_announcer.sweep.save_failed"), false);
            return false;
        }
        SweepTemplate t = capture.template();
        feedback.send(Text.translatable(replaced ? "msg.station_announcer.sweep.replaced" : "msg.station_announcer.sweep.saved",
                t.name, t.length, t.width, t.height, t.solidCount()), false);
        if (player != null) {
            // a held sweeper picks the new template up straight away
            for (Hand h : Hand.values()) {
                ItemStack held = player.getStackInHand(h);
                if (held.getItem() instanceof ItemStructureSweeper) {
                    SweepSpec spec = SweepSpec.read(held);
                    spec.template = t.name;
                    spec.resetFit();
                    spec.write(held);
                }
            }
        }
        StationAnnouncer.LOGGER.info("Sweep: {} saved template '{}' ({}x{}x{}, {} blocks, rail column {} level {})",
                author, t.name, t.length, t.width, t.height, t.solidCount(), t.pinW, t.pinH);
        return true;
    }

    // ------------------------------------------------------------- command

    private static ServerPlayerEntity player(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.translatable("commands.station_announcer.sweep.player_only"));
        }
        return player;
    }

    private static SweepService.Feedback feedback(ServerCommandSource source) {
        return (text, bar) -> {
            ServerPlayerEntity p = source.getPlayer();
            if (p != null) {
                p.sendMessage(text, bar);
            } else {
                source.sendFeedback(() -> text, false);
            }
        };
    }

    private static UUID owner(ServerCommandSource source) {
        ServerPlayerEntity p = source.getPlayer();
        return p != null ? p.getUuid() : Util.NIL_UUID;
    }

    /**
     * The settings a command build uses: the held sweeper's (if any), the
     * template named in the command, then {@code key=value} options:
     * mode=repeat|stretch|once, flip=0|1, legs=0|1, air=above|all|never,
     * side=N, height=N, phase=N, depth=N, turn=0..3 (or 90/180/270), railw=N, railh=N (rail column / level). Null (error sent) when nothing usable.
     */
    private static SweepSpec specFor(ServerCommandSource source, String template, String options) {
        ServerPlayerEntity p = source.getPlayer();
        SweepSpec spec = null;
        if (p != null && p.getMainHandStack().getItem() instanceof ItemStructureSweeper) {
            spec = SweepSpec.read(p.getMainHandStack());
        }
        if (spec == null) {
            if (template == null) {
                source.sendError(Text.translatable("commands.station_announcer.sweep.hold_tool"));
                return null;
            }
            spec = new SweepSpec();
        }
        if (template != null) {
            spec.template = template;
        }
        if (options != null) {
            for (String token : options.trim().split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = token.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = token.substring(eq + 1).toLowerCase(Locale.ROOT);
                try {
                    switch (key) {
                        case "mode" -> spec.mode = value.startsWith("r") ? SweepGeometry.Mode.REPEAT
                                : value.startsWith("o") ? SweepGeometry.Mode.ONCE : SweepGeometry.Mode.STRETCH;
                        case "flip" -> spec.flip = value.equals("1") || value.equals("true") || value.equals("on");
                        case "legs" -> spec.legs = value.equals("1") || value.equals("true") || value.equals("on");
                        case "air" -> spec.air = value.startsWith("n") ? SweepSpec.AirMode.NEVER
                                : value.startsWith("e") || value.startsWith("all") ? SweepSpec.AirMode.EVERYWHERE : SweepSpec.AirMode.ABOVE_RAIL;
                        case "side" -> spec.sideOffset = Integer.parseInt(value);
                        case "height" -> spec.heightOffset = Integer.parseInt(value);
                        case "phase" -> spec.phase = Integer.parseInt(value);
                        case "depth" -> spec.legDepth = Integer.parseInt(value);
                        case "turn" -> spec.turn = Integer.parseInt(value) / (Integer.parseInt(value) >= 4 ? 90 : 1);
                        case "railw" -> spec.railW = Integer.parseInt(value);
                        case "railh" -> spec.railH = Integer.parseInt(value);
                        default -> source.sendError(Text.literal("Unknown option " + key));
                    }
                } catch (NumberFormatException e) {
                    source.sendError(Text.literal("Not a number: " + token));
                    return null;
                }
            }
        }
        spec.clamp();
        return spec;
    }

    private static int sweep(CommandContext<ServerCommandSource> ctx, boolean rail, String template, String options) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity p = source.getPlayer();
        if (p != null && !allowed(p)) {
            source.sendError(Text.translatable("msg.station_announcer.sweep.not_allowed"));
            return 0;
        }
        SweepSpec spec = specFor(source, template, options);
        if (spec == null) {
            return 0;
        }
        BlockPos from = BlockPosArgumentType.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgumentType.getBlockPos(ctx, "to");
        net.minecraft.server.world.ServerWorld world = source.getWorld();
        SweepService.Feedback fb = feedback(source);
        UUID owner = owner(source);
        String who = source.getName();
        if (!rail) {
            SweepService.buildLine(owner, world, spec, from, to, fb, who);
            return 1;
        }
        if (p == null) {
            // MTR's rail lookup wants a player; the console finds the rail itself
            BridgeService.findRail(world, from, to, found -> {
                if (found == null) {
                    fb.send(Text.translatable("commands.station_announcer.bridge.no_rail"), true);
                } else {
                    SweepService.buildFromRail(owner, world, spec, found, from, fb, who);
                }
            });
            return 1;
        }
        ItemNodeModifierBase.getRail(new org.mtr.mapping.holder.World(world), new org.mtr.mapping.holder.BlockPos(from),
                new org.mtr.mapping.holder.BlockPos(to), new org.mtr.mapping.holder.ServerPlayerEntity(p), found -> {
                    if (found == null) {
                        fb.send(Text.translatable("commands.station_announcer.bridge.no_rail"), true);
                    } else {
                        SweepService.buildFromRail(owner, world, spec, found, from, fb, who);
                    }
                });
        return 1;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, net.minecraft.command.argument.PosArgument> fromTo(boolean rail) {
        return CommandManager.argument("from", BlockPosArgumentType.blockPos())
                .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                        .executes(ctx -> sweep(ctx, rail, null, null))
                        .then(CommandManager.argument("template", StringArgumentType.string())
                                .suggests((c, b) -> {
                                    SweepTemplateStore.all().forEach(t -> b.suggest(StringArgumentType.escapeIfRequired(t.name)));
                                    return b.buildFuture();
                                })
                                .executes(ctx -> sweep(ctx, rail, StringArgumentType.getString(ctx, "template"), null))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(ctx -> sweep(ctx, rail, StringArgumentType.getString(ctx, "template"),
                                                StringArgumentType.getString(ctx, "options"))))));
    }

    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("sweep")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("template")
                                .then(CommandManager.literal("list").executes(ctx -> {
                                    List<SweepTemplate> all = SweepTemplateStore.all();
                                    ctx.getSource().sendFeedback(() -> Text.translatable("commands.station_announcer.sweep.list", all.size()), false);
                                    for (SweepTemplate t : all) {
                                        ctx.getSource().sendFeedback(() -> Text.literal(" · " + t.name + "  " + t.length + "x" + t.width + "x"
                                                + t.height + ", " + t.solidCount() + " blocks, by " + t.author), false);
                                    }
                                    return all.size();
                                }))
                                .then(CommandManager.literal("save")
                                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayerEntity player = player(ctx);
                                                    if (player == null) {
                                                        return 0;
                                                    }
                                                    ItemStack stack = player.getMainHandStack();
                                                    if (!(stack.getItem() instanceof ItemSweepWand)) {
                                                        ctx.getSource().sendError(Text.translatable("commands.station_announcer.sweep.hold_wand"));
                                                        return 0;
                                                    }
                                                    return saveFromWand(player, stack, StringArgumentType.getString(ctx, "name"), true) ? 1 : 0;
                                                })))
                                .then(CommandManager.literal("capture")
                                        .then(CommandManager.argument("c1", BlockPosArgumentType.blockPos())
                                                .then(CommandManager.argument("c2", BlockPosArgumentType.blockPos())
                                                        .then(CommandManager.argument("fit", StringArgumentType.word())
                                                                .suggests((c, b) -> {
                                                                    b.suggest("trim");
                                                                    b.suggest("full");
                                                                    return b.buildFuture();
                                                                })
                                                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                                        .executes(ctx -> {
                                                                            ServerCommandSource src = ctx.getSource();
                                                                            boolean trim = !StringArgumentType.getString(ctx, "fit").equalsIgnoreCase("full");
                                                                            return capture(src.getWorld(), src.getPlayer(), feedback(src), src.getName(),
                                                                                    BlockPosArgumentType.getBlockPos(ctx, "c1"),
                                                                                    BlockPosArgumentType.getBlockPos(ctx, "c2"), trim,
                                                                                    StringArgumentType.getString(ctx, "name")) ? 1 : 0;
                                                                        }))))))
                                .then(CommandManager.literal("delete")
                                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                .suggests((c, b) -> {
                                                    SweepTemplateStore.all().forEach(t -> b.suggest(t.name));
                                                    return b.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    boolean ok = SweepTemplateStore.delete(name);
                                                    ctx.getSource().sendFeedback(() -> Text.translatable(ok
                                                            ? "msg.station_announcer.sweep.deleted" : "commands.station_announcer.sweep.unknown", name), false);
                                                    return ok ? 1 : 0;
                                                })))
                                .then(CommandManager.literal("export")
                                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                                .suggests((c, b) -> {
                                                    SweepTemplateStore.all().forEach(t -> b.suggest(t.name));
                                                    return b.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    Path file = SweepTemplateStore.export(name);
                                                    ctx.getSource().sendFeedback(() -> file == null
                                                            ? Text.translatable("commands.station_announcer.sweep.unknown", name)
                                                            : Text.translatable("commands.station_announcer.sweep.exported", name, file.toString()), false);
                                                    return file == null ? 0 : 1;
                                                })))
                                .then(CommandManager.literal("import")
                                        .then(CommandManager.argument("file", StringArgumentType.greedyString())
                                                .suggests((c, b) -> {
                                                    SweepTemplateStore.importable().forEach(b::suggest);
                                                    return b.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String file = StringArgumentType.getString(ctx, "file");
                                                    SweepTemplate t = SweepTemplateStore.importFile(file);
                                                    ctx.getSource().sendFeedback(() -> t == null
                                                            ? Text.translatable("commands.station_announcer.sweep.import_failed", file)
                                                            : Text.translatable("commands.station_announcer.sweep.imported", t.name), false);
                                                    return t == null ? 0 : 1;
                                                }))))
                        .then(CommandManager.literal("use")
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .suggests((c, b) -> {
                                            SweepTemplateStore.all().forEach(t -> b.suggest(t.name));
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerPlayerEntity player = player(ctx);
                                            if (player == null) {
                                                return 0;
                                            }
                                            ItemStack stack = player.getMainHandStack();
                                            if (!(stack.getItem() instanceof ItemStructureSweeper)) {
                                                ctx.getSource().sendError(Text.translatable("commands.station_announcer.sweep.hold_tool"));
                                                return 0;
                                            }
                                            SweepTemplate t = SweepTemplateStore.get(StringArgumentType.getString(ctx, "name"));
                                            if (t == null) {
                                                ctx.getSource().sendError(Text.translatable("commands.station_announcer.sweep.unknown",
                                                        StringArgumentType.getString(ctx, "name")));
                                                return 0;
                                            }
                                            SweepSpec spec = SweepSpec.read(stack);
                                            spec.template = t.name;
                                            spec.write(stack);
                                            player.sendMessage(Text.translatable("msg.station_announcer.sweep.using", t.name), true);
                                            return 1;
                                        })))
                        .then(CommandManager.literal("pick")
                                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                                .executes(ctx -> {
                                                    ServerPlayerEntity player = player(ctx);
                                                    if (player == null) {
                                                        return 0;
                                                    }
                                                    BlockPos from = BlockPosArgumentType.getBlockPos(ctx, "from");
                                                    BlockPos to = BlockPosArgumentType.getBlockPos(ctx, "to");
                                                    BridgeService.findRail(player.getServerWorld(), from, to, rail -> {
                                                        if (rail == null) {
                                                            player.sendMessage(Text.translatable("commands.station_announcer.bridge.no_rail"), true);
                                                        } else {
                                                            SweepService.pickRail(player, rail, from);
                                                        }
                                                    });
                                                    return 1;
                                                }))))
                        .then(CommandManager.literal("build").then(fromTo(true)))
                        .then(CommandManager.literal("line").then(fromTo(false)))
                        .then(CommandManager.literal("undo").executes(ctx -> {
                            ServerCommandSource src = ctx.getSource();
                            EarthworksService.undo(owner(src), src.getWorld(), text -> feedback(src).send(text, true));
                            return 1;
                        }))));
    }
}
