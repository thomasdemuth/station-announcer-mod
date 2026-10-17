package com.stationannouncer.atmosphere;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.PaStationNames;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Weathering Tool's server side: plans a pass over a box or a whole MTR
 * station ({@link WeatheringPlanner}), applies it a slice per tick, and keeps
 * one undo per player (blocks and light conditions).
 */
public final class Weathering {
    public static final Identifier UPDATE_C2S = StationAnnouncer.id("update_weathering");
    public static final Identifier ACTION_C2S = StationAnnouncer.id("weathering_action");
    public static final byte ACTION_UNDO = 0;
    public static final byte ACTION_CANCEL_CORNER = 1;

    private static final int BUDGET = 6000;

    public static WeatheringToolItem WEATHERING_TOOL;

    private static final class Job {
        final ServerWorld world;
        final BlockPos[] pos;
        final BlockState[] target;
        final BlockState[] before;
        final Long2IntOpenHashMap lights;
        final Long2IntOpenHashMap lightsBefore;
        final boolean undo;
        final String summary;
        int cursor;

        Job(ServerWorld world, Map<BlockPos, BlockState> blocks, Long2IntOpenHashMap lights,
            Long2IntOpenHashMap lightsBefore, boolean undo, String summary) {
            this.world = world;
            int n = blocks.size();
            this.pos = new BlockPos[n];
            this.target = new BlockState[n];
            int i = 0;
            // removals first, then placements
            for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
                if (e.getValue().isAir()) {
                    pos[i] = e.getKey();
                    target[i++] = e.getValue();
                }
            }
            for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
                if (!e.getValue().isAir()) {
                    pos[i] = e.getKey();
                    target[i++] = e.getValue();
                }
            }
            this.before = new BlockState[n];
            this.lights = lights;
            this.lightsBefore = lightsBefore;
            this.undo = undo;
            this.summary = summary;
        }
    }

    private record Undo(ServerWorld world, BlockPos[] pos, BlockState[] before, Long2IntOpenHashMap lightsBefore) {
    }

    private static final Map<UUID, Job> RUNNING = new HashMap<>();
    private static final Map<UUID, Undo> LAST = new HashMap<>();

    private Weathering() {
    }

    public static void register() {
        WEATHERING_TOOL = AtmosphereRegistry.item("weathering_tool", new WeatheringToolItem(new Item.Settings().maxCount(1)));
        ServerTickEvents.END_SERVER_TICK.register(Weathering::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Job job : RUNNING.values()) {
                while (job.cursor < job.pos.length) {
                    step(job);
                }
                LightConditions.setAll(job.world, job.lights);
            }
            RUNNING.clear();
            LAST.clear();
        });
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            NbtCompound nbt = buf.readNbt();
            server.execute(() -> {
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof WeatheringToolItem && nbt != null) {
                    WeatherSpec.fromNbt(nbt).write(stack);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION_C2S, (server, player, handler, buf, sender) -> {
            Hand hand = buf.readBoolean() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            byte action = buf.readByte();
            server.execute(() -> {
                if (action == ACTION_UNDO) {
                    undo(player);
                } else if (action == ACTION_CANCEL_CORNER) {
                    WeatheringToolItem.clearCorner(player.getStackInHand(hand));
                }
            });
        });
    }

    // ------------------------------------------------------------- entry points

    /** Two corners. If they share a height, the box reaches {@code spec.height} blocks above them. */
    public static void weatherBox(ServerPlayerEntity player, BlockPos a, BlockPos b, WeatherSpec spec) {
        int top = Math.max(a.getY(), b.getY());
        if (a.getY() == b.getY()) {
            top += spec.height;
        }
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), top, Math.max(a.getZ(), b.getZ()));
        run(player, player.getServerWorld(), min, max, spec, null);
    }

    /** The MTR station around {@code pos}, from {@code stationBelow} below to {@code stationAbove} above it. */
    public static void weatherStation(ServerPlayerEntity player, BlockPos pos, WeatherSpec spec) {
        PaStationNames.StationArea area = PaStationNames.area(player.getServerWorld(), pos);
        if (area == null) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.no_station"), true);
            return;
        }
        BlockPos min = new BlockPos(area.minX(), pos.getY() - spec.stationBelow, area.minZ());
        BlockPos max = new BlockPos(area.maxX(), pos.getY() + spec.stationAbove, area.maxZ());
        run(player, player.getServerWorld(), min, max, spec, area.name());
    }

    private static void run(ServerPlayerEntity player, ServerWorld world, BlockPos min, BlockPos max, WeatherSpec spec,
                            String stationName) {
        if (RUNNING.containsKey(player.getUuid())) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.busy"), true);
            return;
        }
        long cells = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (cells > WeatheringPlanner.MAX_CELLS) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.too_big", cells,
                    WeatheringPlanner.MAX_CELLS), false);
            return;
        }
        long started = System.nanoTime();
        WeatheringPlanner.Result result;
        try {
            result = WeatheringPlanner.plan(world, min, max, spec, world.getRandom().nextLong());
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.error("Weathering: planning failed", t);
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.failed"), true);
            return;
        }
        Long2IntOpenHashMap before = LightConditions.inBox(world, min, max);
        Long2IntOpenHashMap lightsBefore = new Long2IntOpenHashMap();
        for (long p : result.lights().keySet()) {
            lightsBefore.put(p, before.get(p));
        }
        int lightFaults = 0;
        for (int v : result.lights().values()) {
            if (v != 0) {
                lightFaults++;
            }
        }
        String where = stationName != null ? stationName
                : Text.translatable("msg.station_announcer.weathering.area", max.getX() - min.getX() + 1,
                max.getZ() - min.getZ() + 1).getString();
        WeatherSpec.Preset preset = spec.matching();
        String what = preset == null ? Text.translatable("gui.station_announcer.weathering.custom").getString()
                : Text.translatable("gui.station_announcer.weathering.preset." + preset.id).getString();
        String summary = Text.translatable("msg.station_announcer.weathering.done", where, what, result.decals(),
                result.litter(), lightFaults, result.removed()).getString();
        StationAnnouncer.LOGGER.info("Weathering: {} {} {}..{} — {} decals, {} litter, {} light faults, {} removed, {} cans, planned in {} ms",
                player.getName().getString(), what, min.toShortString(), max.toShortString(), result.decals(),
                result.litter(), lightFaults, result.removed(), result.cans(), (System.nanoTime() - started) / 1_000_000);
        if (result.blocks().isEmpty() && result.lights().isEmpty()) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.nothing"), true);
            return;
        }
        RUNNING.put(player.getUuid(), new Job(world, result.blocks(), result.lights(), lightsBefore, false, summary));
    }

    // ------------------------------------------------------------- applying

    private static void tick(MinecraftServer server) {
        if (RUNNING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Job>> it = RUNNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Job> entry = it.next();
            Job job = entry.getValue();
            int end = Math.min(job.pos.length, job.cursor + BUDGET);
            while (job.cursor < end) {
                step(job);
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (job.cursor >= job.pos.length) {
                LightConditions.setAll(job.world, job.lights);
                it.remove();
                if (job.undo) {
                    if (player != null) {
                        player.sendMessage(Text.translatable("msg.station_announcer.weathering.undone",
                                job.pos.length, job.lights.size()), true);
                    }
                } else {
                    LAST.put(entry.getKey(), new Undo(job.world, job.pos, job.before, job.lightsBefore));
                    if (player != null) {
                        player.sendMessage(Text.literal(job.summary), false);
                    }
                }
            } else if (player != null && server.getTicks() % 10 == 0) {
                player.sendMessage(Text.translatable("msg.station_announcer.weathering.progress",
                        job.cursor * 100 / job.pos.length), true);
            }
        }
    }

    private static void step(Job job) {
        int i = job.cursor++;
        job.before[i] = job.world.getBlockState(job.pos[i]);
        job.world.setBlockState(job.pos[i], job.target[i], Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }

    public static void undo(ServerPlayerEntity player) {
        if (RUNNING.containsKey(player.getUuid())) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.busy"), true);
            return;
        }
        Undo undo = LAST.remove(player.getUuid());
        if (undo == null) {
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.nothing_to_undo"), true);
            return;
        }
        if (player.getServerWorld() != undo.world) {
            LAST.put(player.getUuid(), undo);
            player.sendMessage(Text.translatable("msg.station_announcer.weathering.undo_other_world"), true);
            return;
        }
        Map<BlockPos, BlockState> blocks = new java.util.LinkedHashMap<>();
        for (int i = undo.pos.length - 1; i >= 0; i--) {
            blocks.put(undo.pos[i], undo.before[i]);
        }
        RUNNING.put(player.getUuid(), new Job(undo.world, blocks, undo.lightsBefore, new Long2IntOpenHashMap(), true, ""));
    }

    // ------------------------------------------------------------- command

    private static WeatherSpec presetSpec(CommandContext<ServerCommandSource> ctx) {
        WeatherSpec spec = new WeatherSpec();
        try {
            WeatherSpec.Preset p = WeatherSpec.Preset.byId(StringArgumentType.getString(ctx, "preset"));
            if (p != null) {
                spec.load(p);
            }
        } catch (IllegalArgumentException ignored) {
            // no preset argument
        }
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null && player.getMainHandStack().getItem() instanceof WeatheringToolItem) {
            WeatherSpec held = WeatherSpec.read(player.getMainHandStack());
            spec.clean = held.clean;
            spec.height = held.height;
            spec.stationBelow = held.stationBelow;
            spec.stationAbove = held.stationAbove;
        }
        return spec;
    }

    /** {@code /atmosphere weather <from> <to> [preset]}, {@code station [preset]}, {@code undo}. */
    public static LiteralArgumentBuilder<ServerCommandSource> command() {
        com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> presets = (ctx, builder) -> {
            for (WeatherSpec.Preset p : WeatherSpec.Preset.values()) {
                builder.suggest(p.id);
            }
            return builder.buildFuture();
        };
        return CommandManager.literal("weather")
                .then(CommandManager.literal("undo").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player != null) {
                        undo(player);
                    }
                    return 1;
                }))
                .then(CommandManager.literal("station")
                        .executes(ctx -> station(ctx))
                        .then(CommandManager.argument("preset", StringArgumentType.word()).suggests(presets)
                                .executes(Weathering::station)))
                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                .executes(Weathering::box)
                                .then(CommandManager.argument("preset", StringArgumentType.word()).suggests(presets)
                                        .executes(Weathering::box))));
    }

    private static int box(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        BlockPos a = BlockPosArgumentType.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgumentType.getBlockPos(ctx, "to");
        WeatherSpec spec = presetSpec(ctx);
        if (a.getY() == b.getY()) {
            spec.height = Math.max(spec.height, 1);
        }
        weatherBox(player, a, b, spec);
        return 1;
    }

    private static int station(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        weatherStation(player, player.getBlockPos(), presetSpec(ctx));
        return 1;
    }
}
