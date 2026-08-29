package com.stationannouncer.mtraddon.analytics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.dispatch.TerrainScanner;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /dispatch stats [line]} — the in-game scorecard for the timetable analytics.
 * Prints, for the caller's dimension, each line's on-time percentage, its average and
 * worst headway against the reference headway, and any bunching warnings. The web board
 * at {@code /dispatch/} is the deep view; this is the quick check.
 *
 * <p>Permission level comes from {@code editPermissionLevel} (default 2), the same gate
 * the addon's other admin surfaces use.</p>
 *
 * <p><b>Thread:</b> the server thread. It only reads {@link AnalyticsAggregator}'s
 * volatile published snapshot — no simulator hop, no file access, no recomputation.</p>
 */
public final class AnalyticsCommand {
    private AnalyticsCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("dispatch")
                        .requires(source -> source.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel))
                        .then(CommandManager.literal("stats")
                                .executes(context -> run(context, null))
                                .then(CommandManager.argument("line", StringArgumentType.greedyString())
                                        .suggests(AnalyticsCommand::suggestLines)
                                        .executes(context -> run(context,
                                                StringArgumentType.getString(context, "line")))))
                        // Dispatch-map terrain: an explicit, time-budgeted world scan for
                        // water. Same permission gate as the stats board.
                        .then(CommandManager.literal("terrain")
                                .then(CommandManager.literal("scan")
                                        .executes(context -> TerrainScanner.startScan(context.getSource(),
                                                DEFAULT_TERRAIN_MARGIN))
                                        .then(CommandManager.argument("margin", IntegerArgumentType.integer(0, 1024))
                                                .executes(context -> TerrainScanner.startScan(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "margin")))))
                                .then(CommandManager.literal("status")
                                        .executes(context -> {
                                            context.getSource().sendFeedback(() -> Text.literal("Terrain: "
                                                    + TerrainScanner.status()).formatted(Formatting.AQUA), false);
                                            return 1;
                                        })))));
    }

    /** How far past the outermost rail/station the terrain scan reaches by default. */
    private static final int DEFAULT_TERRAIN_MARGIN = 128;

    /**
     * MTR keys its simulators by {@code "<namespace>/<path>"} (see {@code Init.getWorldId}),
     * which is what the recorded events carry — build the same string from the caller's world.
     */
    private static String dimensionOf(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        return world == null ? "" : world.getRegistryKey().getValue().getNamespace()
                + "/" + world.getRegistryKey().getValue().getPath();
    }

    private static int run(CommandContext<ServerCommandSource> context, String lineFilter) {
        ServerCommandSource source = context.getSource();
        AddonServerConfig.Analytics config = AddonServerConfig.get().analytics;
        if (!config.enabled) {
            source.sendError(Text.literal("Timetable analytics is disabled (analytics.enabled=false)."));
            return 0;
        }
        String dimension = dimensionOf(source);
        AnalyticsAggregator.Aggregate aggregate = AnalyticsAggregator.get(dimension);
        if (aggregate == null || aggregate.lines.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No departures recorded in this dimension yet (window "
                    + config.windowMinutes + " min).").formatted(Formatting.YELLOW), false);
            return 0;
        }

        List<AnalyticsAggregator.LineStats> lines = aggregate.lines;
        if (lineFilter != null && !lineFilter.isBlank()) {
            String needle = lineFilter.trim().toLowerCase(Locale.ROOT);
            lines = lines.stream().filter(line -> matches(line, needle)).toList();
            if (lines.isEmpty()) {
                source.sendError(Text.literal("No line matching \"" + lineFilter.trim() + "\" in this dimension."));
                return 0;
            }
        }

        final List<AnalyticsAggregator.LineStats> shown = lines;
        int ageSeconds = (int) Math.max(0, (System.currentTimeMillis() - aggregate.computedAt) / 1000);
        source.sendFeedback(() -> Text.literal("Dispatch stats — last " + config.windowMinutes + " min, "
                        + aggregate.departures + " departures (updated " + ageSeconds + "s ago)")
                .formatted(Formatting.AQUA, Formatting.BOLD), false);

        for (AnalyticsAggregator.LineStats line : shown) {
            source.sendFeedback(() -> Text.literal(header(line)).formatted(Formatting.WHITE), false);
            source.sendFeedback(() -> Text.literal("  on time " + line.onTimePercent + "% ("
                            + line.onTime + "/" + line.departures + ", ±" + config.onTimeToleranceSeconds
                            + "s)  avg dev " + signedSeconds(line.averageDeviationMillis)
                            + "  worst late " + signedSeconds(line.worstLateMillis))
                    .formatted(onTimeColor(line.onTimePercent)), false);
            source.sendFeedback(() -> Text.literal("  headway avg " + duration(line.averageHeadwayMillis)
                            + "  worst gap " + duration(line.maxHeadwayMillis)
                            + "  tightest " + duration(line.minHeadwayMillis)
                            + "  vs " + duration(line.scheduledHeadwayMillis)
                            + " (" + line.headwaySource + ", " + line.headwaySamples + " samples)")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("  dwell avg " + duration(line.averageDwellMillis)
                            + "  overrun avg " + signedSeconds(line.averageDwellOverrunMillis)
                            + "  max " + signedSeconds(line.maxDwellOverrunMillis))
                    .formatted(Formatting.GRAY), false);
            if (line.bunching.isEmpty()) {
                source.sendFeedback(() -> Text.literal("  no bunching").formatted(Formatting.DARK_GRAY), false);
            } else {
                source.sendFeedback(() -> Text.literal("  BUNCHING: " + line.bunching.size()
                                + " gap(s) under " + Math.round(config.bunchingFraction * 100) + "% of headway")
                        .formatted(Formatting.RED), false);
                line.bunching.stream().limit(3).forEach(alert -> source.sendFeedback(() ->
                        Text.literal("    " + duration(alert.gapMillis()) + " at "
                                        + AnalyticsAggregator.displayName(alert.stationName()) + " platform "
                                        + AnalyticsAggregator.displayName(alert.platformName()))
                                .formatted(Formatting.RED), false));
            }
        }
        return shown.size();
    }

    private static boolean matches(AnalyticsAggregator.LineStats line, String needle) {
        return line.number.toLowerCase(Locale.ROOT).equals(needle)
                || line.name.toLowerCase(Locale.ROOT).contains(needle)
                || AnalyticsAggregator.displayName(line.name).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String header(AnalyticsAggregator.LineStats line) {
        String name = AnalyticsAggregator.displayName(line.name);
        return line.number.isEmpty() ? name : "[" + line.number + "] " + name;
    }

    private static Formatting onTimeColor(double percent) {
        if (percent >= 90) {
            return Formatting.GREEN;
        }
        return percent >= 75 ? Formatting.YELLOW : Formatting.RED;
    }

    /** {@code -1} is the aggregator's "not enough data" sentinel. */
    private static String duration(long millis) {
        if (millis < 0) {
            return "—";
        }
        long seconds = Math.round(millis / 1000.0);
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + String.format(Locale.ROOT, "%02d", seconds % 60) + "s";
    }

    private static String signedSeconds(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return (seconds > 0 ? "+" : "") + seconds + "s";
    }

    private static CompletableFuture<Suggestions> suggestLines(CommandContext<ServerCommandSource> context,
                                                               SuggestionsBuilder builder) {
        AnalyticsAggregator.Aggregate aggregate = AnalyticsAggregator.get(dimensionOf(context.getSource()));
        if (aggregate != null) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (AnalyticsAggregator.LineStats line : aggregate.lines) {
                String name = AnalyticsAggregator.displayName(line.name);
                if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
                if (!line.number.isEmpty() && line.number.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(line.number);
                }
            }
        }
        return builder.buildFuture();
    }
}
