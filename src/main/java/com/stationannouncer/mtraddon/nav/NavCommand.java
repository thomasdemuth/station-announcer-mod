package com.stationannouncer.mtraddon.nav;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two in-game entry points for journey directions.
 *
 * <ul>
 *   <li>{@code /nav [stepfree] <station name…>} — plans the fastest route from where the
 *       caller is standing to the named station and pushes it to their HUD.
 *       {@code /nav stop} clears the HUD.</li>
 *   <li>{@code /navpair}, {@code /navpair list}, {@code /navpair revoke <index|all>} —
 *       pairing a browser with this player so the dispatch web UI can send journeys to
 *       the same HUD.</li>
 * </ul>
 *
 * <h2>Why {@code /navpair} and not {@code /dispatch pair}</h2>
 * Brigadier DOES merge two literal roots of the same name — {@code CommandNode.addChild}
 * (bytecode-checked against brigadier 1.3.10) keeps the EXISTING node and grafts the
 * incoming node's children onto it. But it copies only the incoming node's
 * {@code command}, never its {@code requirement}: the surviving root keeps whatever
 * permission predicate was registered FIRST. {@code AnalyticsCommand} already registers
 * {@code dispatch} behind {@code hasPermissionLevel(editPermissionLevel)}, so a merged
 * {@code /dispatch pair} would be op-only — and registering ours first instead would
 * strip the op gate off {@code /dispatch stats|terrain|satellite}. Pairing your own
 * browser must need no permission, so it gets its own ungated root. Nothing in the
 * existing {@code dispatch} tree is touched.
 *
 * <h2>Threading</h2>
 * Commands run on the server thread. {@code /nav} hops to the caller's dimension's
 * SIMULATOR thread with {@code simulator.run(...)} to resolve the station name and plan
 * (both need live simulator data), then hops back with {@code server.execute(...)} to
 * send the packet and print the confirmation — the same round trip
 * {@code DisruptionBroadcaster} uses, and nothing but plain records crosses between.
 * Station-name SUGGESTIONS come from a per-dimension cache refreshed on the simulator
 * thread; a stale suggestion can never mis-plan, because the name is resolved again,
 * authoritatively, on the simulator thread when the command actually runs.
 */
public final class NavCommand {
    /** How long a dimension's suggestion name list is reused before a background refresh. */
    private static final long SUGGESTION_CACHE_MILLIS = 30_000;
    /** Suggestion lists are for typing, not for browsing. */
    private static final int MAX_SUGGESTIONS = 200;
    /** How many candidates an ambiguous name lists before it gives up. */
    private static final int MAX_CANDIDATES = 5;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);

    /** dimension → display names, published whole from the simulator thread. */
    private static final Map<String, List<String>> SUGGESTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SUGGESTIONS_REFRESHED = new ConcurrentHashMap<>();

    /** What the simulator thread hands back; carries no MTR types. */
    private record Outcome(NavPlanner.Journey journey, String error, List<String> candidates) {
        static Outcome ok(NavPlanner.Journey journey) {
            return new Outcome(journey, null, List.of());
        }

        static Outcome error(String message) {
            return new Outcome(null, message, List.of());
        }

        static Outcome ambiguous(List<String> candidates) {
            return new Outcome(null, null, candidates);
        }
    }

    private NavCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("nav")
                    .then(CommandManager.literal("stop")
                            .executes(NavCommand::runStop))
                    .then(CommandManager.literal("stepfree")
                            .then(CommandManager.argument("station", StringArgumentType.greedyString())
                                    .suggests(NavCommand::suggestStations)
                                    .executes(context -> runNav(context, true))))
                    .then(CommandManager.argument("station", StringArgumentType.greedyString())
                            .suggests(NavCommand::suggestStations)
                            .executes(context -> runNav(context, false))));

            dispatcher.register(CommandManager.literal("navpair")
                    .executes(NavCommand::runPair)
                    .then(CommandManager.literal("list")
                            .executes(NavCommand::runPairList))
                    .then(CommandManager.literal("revoke")
                            .then(CommandManager.literal("all")
                                    .executes(NavCommand::runRevokeAll))
                            .then(CommandManager.argument("index", IntegerArgumentType.integer(1, 999))
                                    .executes(context -> runRevoke(context,
                                            IntegerArgumentType.getInteger(context, "index"))))));
        });
    }

    // --------------------------------------------------------------------- /nav

    private static int runStop(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("Only a player can use /nav."));
            return 0;
        }
        NavNetworking.clear(player);
        context.getSource().sendFeedback(() ->
                Text.literal("Navigation cleared.").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int runNav(CommandContext<ServerCommandSource> context, boolean stepFree) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player can use /nav."));
            return 0;
        }
        String query = StringArgumentType.getString(context, "station").trim();
        if (query.isEmpty()) {
            source.sendError(Text.literal("Usage: /nav [stepfree] <station name>"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        Simulator simulator = simulatorFor(player.getServerWorld());
        if (simulator == null) {
            source.sendError(Text.literal("No MTR simulation is running in this dimension."));
            return 0;
        }
        Position from = new Position((long) Math.floor(player.getX()),
                (long) Math.floor(player.getY()), (long) Math.floor(player.getZ()));
        UUID playerId = player.getUuid();

        simulator.run(() -> {
            Outcome outcome;
            try {
                outcome = planOn(simulator, from, query, stepFree);
            } catch (Throwable throwable) {
                StationAnnouncer.LOGGER.warn("Nav command failed in dimension {} ({})",
                        simulator.dimension, throwable.toString());
                outcome = Outcome.error("Route planning failed — see the server log.");
            }
            // Refresh the suggestion cache off the same visit; it is free here.
            cacheSuggestions(simulator);
            Outcome result = outcome;
            server.execute(() -> deliver(server, playerId, query, stepFree, result));
        });
        return 1;
    }

    /** SIMULATOR THREAD: resolve the station name, then plan. */
    private static Outcome planOn(Simulator simulator, Position from, String query, boolean stepFree) {
        List<Station> matches = matchStations(simulator, query);
        if (matches.isEmpty()) {
            return Outcome.error("No station matching \"" + query + "\" in this dimension.");
        }
        if (matches.size() > 1) {
            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < matches.size() && i < MAX_CANDIDATES; i++) {
                candidates.add(NavPlanner.displayName(matches.get(i).getName()));
            }
            return Outcome.ambiguous(candidates);
        }
        NavPlanner.Journey journey = NavPlanner.plan(simulator, from, matches.get(0).getId(), stepFree);
        if (journey == null) {
            return Outcome.error("No route to " + NavPlanner.displayName(matches.get(0).getName())
                    + (stepFree ? " that is step-free" : "")
                    + " from here (nothing within " + (int) NavPlanner.ORIGIN_WALK_RADIUS
                    + " blocks, or the network does not connect).");
        }
        return Outcome.ok(journey);
    }

    /** SERVER THREAD: send the packet and tell the caller what happened. */
    private static void deliver(MinecraftServer server, UUID playerId, String query,
                                boolean stepFree, Outcome outcome) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) {
            return; // logged off while we were planning
        }
        if (outcome.error() != null) {
            player.sendMessage(Text.literal(outcome.error()).formatted(Formatting.RED), false);
            return;
        }
        if (!outcome.candidates().isEmpty()) {
            player.sendMessage(Text.literal("\"" + query + "\" matches several stations:")
                    .formatted(Formatting.YELLOW), false);
            for (String candidate : outcome.candidates()) {
                player.sendMessage(Text.literal("  " + candidate).formatted(Formatting.GRAY), false);
            }
            return;
        }
        NavPlanner.Journey journey = outcome.journey();
        NavNetworking.send(player, journey);
        String arrival = journey.plannedArriveMs() > 0
                ? ", arrive ~" + CLOCK.format(Instant.ofEpochMilli(journey.plannedArriveMs())
                .atZone(ZoneId.systemDefault()))
                : "";
        player.sendMessage(Text.literal("Directions sent to your HUD — " + journey.destination()
                        + (stepFree ? " (step-free)" : "") + ", " + journey.legs().size() + " leg"
                        + (journey.legs().size() == 1 ? "" : "s") + ", "
                        + duration(journey.totalSeconds()) + arrival)
                .formatted(Formatting.AQUA), false);
    }

    /**
     * SIMULATOR THREAD. Case-insensitive; MTR names are {@code "English|Other"} so BOTH
     * halves are matched. Exact wins outright (so a station whose name is a prefix of
     * another is still reachable), then prefix, then substring.
     */
    private static List<Station> matchStations(Simulator simulator, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Station> exact = new ArrayList<>();
        List<Station> prefix = new ArrayList<>();
        List<Station> contains = new ArrayList<>();
        for (Station station : simulator.stations) {
            if (station == null) {
                continue;
            }
            int rank = 3;
            for (String half : halves(station.getName())) {
                String lower = half.toLowerCase(Locale.ROOT);
                if (lower.equals(needle)) {
                    rank = Math.min(rank, 0);
                } else if (lower.startsWith(needle)) {
                    rank = Math.min(rank, 1);
                } else if (lower.contains(needle)) {
                    rank = Math.min(rank, 2);
                }
            }
            switch (rank) {
                case 0 -> exact.add(station);
                case 1 -> prefix.add(station);
                case 2 -> contains.add(station);
                default -> {
                    // no match
                }
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        return prefix.isEmpty() ? contains : prefix;
    }

    private static String[] halves(String name) {
        if (name == null || name.isEmpty()) {
            return new String[0];
        }
        int bar = name.indexOf('|');
        return bar < 0 ? new String[]{name} : new String[]{name.substring(0, bar), name.substring(bar + 1)};
    }

    // ----------------------------------------------------------------- /navpair

    private static int runPair(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player can pair a browser."));
            return 0;
        }
        String code = NavStore.mintCode(player);
        MutableText codeText = Text.literal(code)
                .formatted(Formatting.GREEN, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Click to copy.\nType it into the dispatch map's "
                                        + "\"Send to my HUD\" panel at /dispatch/ within "
                                        + NavStore.CODE_TTL_MINUTES + " minutes."))));
        source.sendFeedback(() -> Text.literal("Pairing code: ").formatted(Formatting.AQUA)
                .append(codeText), false);
        source.sendFeedback(() -> Text.literal("Enter it in the dispatch map within "
                        + NavStore.CODE_TTL_MINUTES + " minutes. /navpair list shows paired browsers.")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int runPairList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player has paired browsers."));
            return 0;
        }
        List<NavStore.Token> tokens = NavStore.tokensOf(player.getUuid());
        if (tokens.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No paired browsers. Run /navpair to add one.")
                    .formatted(Formatting.YELLOW), false);
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Paired browsers (" + tokens.size() + "):")
                .formatted(Formatting.AQUA, Formatting.BOLD), false);
        long now = System.currentTimeMillis();
        for (int i = 0; i < tokens.size(); i++) {
            NavStore.Token token = tokens.get(i);
            int index = i + 1;
            String label = token.label().isEmpty() ? "(unnamed)" : token.label();
            source.sendFeedback(() -> Text.literal("  " + index + ". " + label
                            + "  last used " + ago(now - token.lastUsedAt())
                            + ", paired " + ago(now - token.createdAt()) + " ago")
                    .formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal("  /navpair revoke <number|all> to unpair.")
                .formatted(Formatting.DARK_GRAY), false);
        return tokens.size();
    }

    private static int runRevoke(CommandContext<ServerCommandSource> context, int index) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player has paired browsers."));
            return 0;
        }
        NavStore.Token revoked = NavStore.revoke(player.getUuid(), index);
        if (revoked == null) {
            source.sendError(Text.literal("No paired browser number " + index
                    + " — /navpair list shows the current numbering."));
            return 0;
        }
        String label = revoked.label().isEmpty() ? "(unnamed)" : revoked.label();
        source.sendFeedback(() -> Text.literal("Unpaired " + label + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int runRevokeAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player has paired browsers."));
            return 0;
        }
        int removed = NavStore.revokeAll(player.getUuid());
        source.sendFeedback(() -> Text.literal(removed == 0
                ? "No paired browsers to unpair."
                : "Unpaired " + removed + " browser" + (removed == 1 ? "" : "s") + ".")
                .formatted(removed == 0 ? Formatting.YELLOW : Formatting.GREEN), false);
        return removed;
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * The {@link Simulator} for a world, matched on MTR's own {@code "<namespace>/<path>"}
     * dimension id — the mapping {@code DisruptionBroadcaster} uses.
     */
    private static Simulator simulatorFor(ServerWorld world) {
        if (world == null) {
            return null;
        }
        ObjectImmutableList<Simulator> simulators = com.stationannouncer.mtraddon.disruption.MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            return null;
        }
        String worldId;
        try {
            worldId = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable throwable) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world for /nav", throwable);
            return null;
        }
        for (Simulator simulator : simulators) {
            if (simulator != null && simulator.dimension.equals(worldId)) {
                return simulator;
            }
        }
        return null;
    }

    /** SIMULATOR THREAD: publish this dimension's station display names for tab-complete. */
    private static void cacheSuggestions(Simulator simulator) {
        try {
            // LinkedHashMap keyed by the lowercased name: MTR happily allows two stations
            // with the same name, and a duplicated suggestion is just noise.
            Map<String, String> names = new LinkedHashMap<>();
            for (Station station : simulator.stations) {
                if (station == null) {
                    continue;
                }
                String name = NavPlanner.displayName(station.getName());
                if (!name.isEmpty()) {
                    names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
            SUGGESTIONS.put(simulator.dimension, List.copyOf(names.values()));
            SUGGESTIONS_REFRESHED.put(simulator.dimension, System.currentTimeMillis());
        } catch (Throwable ignored) {
            // Suggestions are a convenience; never let them break a command.
        }
    }

    /**
     * Serves the cached names immediately and kicks a simulator-thread refresh when they
     * are stale. Suggestions are cosmetic, so an empty first list (before any refresh has
     * landed) is fine — typing the name still works.
     */
    private static CompletableFuture<Suggestions> suggestStations(CommandContext<ServerCommandSource> context,
                                                                  SuggestionsBuilder builder) {
        Simulator simulator = simulatorFor(context.getSource().getWorld());
        if (simulator != null) {
            Long refreshed = SUGGESTIONS_REFRESHED.get(simulator.dimension);
            if (refreshed == null || System.currentTimeMillis() - refreshed > SUGGESTION_CACHE_MILLIS) {
                // Stamp first so a burst of keystrokes queues one refresh, not dozens.
                SUGGESTIONS_REFRESHED.put(simulator.dimension, System.currentTimeMillis());
                simulator.run(() -> cacheSuggestions(simulator));
            }
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            int offered = 0;
            for (String name : SUGGESTIONS.getOrDefault(simulator.dimension, List.of())) {
                if (offered >= MAX_SUGGESTIONS) {
                    break;
                }
                if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).contains(remaining)) {
                    builder.suggest(name);
                    offered++;
                }
            }
        }
        return builder.buildFuture();
    }

    private static String duration(int seconds) {
        if (seconds < 60) {
            return Math.max(0, seconds) + "s";
        }
        int minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : (minutes / 60) + "h" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }

    private static String ago(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " min";
        }
        return seconds < 86400 ? (seconds / 3600) + "h" : (seconds / 86400) + "d";
    }
}
