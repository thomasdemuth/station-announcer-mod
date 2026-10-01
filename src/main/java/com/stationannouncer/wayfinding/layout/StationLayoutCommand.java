package com.stationannouncer.wayfinding.layout;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /stationlayout} — station layout scans, only ever on request.
 *
 * <pre>
 * /stationlayout scan &lt;station…&gt;   scan one station now            (level 2)
 * /stationlayout scanall             every station MTR knows          (level 2)
 * /stationlayout cancel              drop the queue                   (level 2)
 * /stationlayout status              what is running / scanned        (anyone)
 * /stationlayout show &lt;station…&gt;   draw its paths while holding the brush (anyone)
 * /stationlayout hide                stop drawing them                 (anyone)
 * </pre>
 */
public final class StationLayoutCommand {
    private StationLayoutCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("stationlayout")
                .then(CommandManager.literal("scan")
                        .requires(source -> source.hasPermissionLevel(LayoutScanner.SCAN_PERMISSION))
                        .then(CommandManager.argument("station", StringArgumentType.greedyString())
                                .suggests(StationLayoutCommand::suggestStations)
                                .executes(StationLayoutCommand::scan)))
                .then(CommandManager.literal("scanall")
                        .requires(source -> source.hasPermissionLevel(LayoutScanner.SCAN_PERMISSION))
                        .executes(context -> {
                            UUID requester = context.getSource().getEntity() instanceof ServerPlayerEntity player
                                    ? player.getUuid() : null;
                            int added = LayoutScanner.requestAll(requester);
                            if (requester == null) {
                                context.getSource().sendFeedback(() -> Text.translatable(added == 0
                                        ? "msg.station_announcer.layout.mtr_loading"
                                        : "msg.station_announcer.layout.bulk_started", added), true);
                            }
                            return added;
                        }))
                .then(CommandManager.literal("cancel")
                        .requires(source -> source.hasPermissionLevel(LayoutScanner.SCAN_PERMISSION))
                        .executes(context -> {
                            LayoutScanner.cancelAll();
                            context.getSource().sendFeedback(() -> Text.translatable(
                                    "msg.station_announcer.layout.cancelled"), true);
                            return 1;
                        }))
                .then(CommandManager.literal("status").executes(StationLayoutCommand::status))
                .then(CommandManager.literal("show")
                        .then(CommandManager.argument("station", StringArgumentType.greedyString())
                                .suggests(StationLayoutCommand::suggestStations)
                                .executes(StationLayoutCommand::show)))
                .then(CommandManager.literal("hide").executes(context -> {
                    if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                        LayoutScanner.sendHide(player);
                    }
                    return 1;
                })));
    }

    private static LayoutScanner.KnownStation resolve(CommandContext<ServerCommandSource> context) {
        String query = StringArgumentType.getString(context, "station");
        LayoutScanner.KnownStation station = LayoutScanner.resolve(query);
        if (station == null) {
            context.getSource().sendError(Text.translatable("msg.station_announcer.layout.unknown_station", query));
        }
        return station;
    }

    private static int scan(CommandContext<ServerCommandSource> context) {
        LayoutScanner.KnownStation station = resolve(context);
        if (station == null) {
            return 0;
        }
        UUID requester = context.getSource().getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
        LayoutScanner.request(List.of(station.id()), requester, false);
        context.getSource().sendFeedback(() -> Text.translatable("msg.station_announcer.layout.queued", station.name()), true);
        return 1;
    }

    private static int show(CommandContext<ServerCommandSource> context) {
        LayoutScanner.KnownStation station = resolve(context);
        if (station == null || !(context.getSource().getEntity() instanceof ServerPlayerEntity player)) {
            return 0;
        }
        if (LayoutScanner.layoutJson(station.id()) == null) {
            context.getSource().sendError(Text.translatable("msg.station_announcer.layout.not_scanned", station.name()));
            return 0;
        }
        LayoutScanner.sendData(player, station.id());
        context.getSource().sendFeedback(() -> Text.translatable("msg.station_announcer.layout.showing", station.name()), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> Text.literal("Station layouts: " + LayoutScanner.statusLine()).formatted(Formatting.GOLD), false);
        for (String line : LayoutScanner.scannedSummaries()) {
            source.sendFeedback(() -> Text.literal(" • " + line).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestStations(CommandContext<ServerCommandSource> context,
                                                                  SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (LayoutScanner.KnownStation station : LayoutScanner.knownStations()) {
            if (station.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(station.name());
            }
        }
        return builder.buildFuture();
    }
}
