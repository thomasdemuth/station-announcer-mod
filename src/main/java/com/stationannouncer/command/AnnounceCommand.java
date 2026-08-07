package com.stationannouncer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.stationannouncer.AnnouncerRegistry;
import com.stationannouncer.config.ServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /announce tag=<tag> [tag=<tag> ...]} — triggers every loaded announcer
 * block carrying one of the given tags. Bare tags (without the {@code tag=}
 * prefix) are accepted too. Required permission level comes from the server
 * config (default 2).
 */
public final class AnnounceCommand {
    private AnnounceCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("announce")
                        .requires(source -> source.hasPermissionLevel(ServerConfig.get().announcePermissionLevel))
                        .then(CommandManager.argument("tags", StringArgumentType.greedyString())
                                .suggests(AnnounceCommand::suggestTags)
                                .executes(AnnounceCommand::run))));
    }

    private static int run(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Set<String> tags = parseTags(StringArgumentType.getString(context, "tags"));
        if (tags.isEmpty()) {
            source.sendError(Text.translatable("commands.station_announcer.announce.usage"));
            return 0;
        }
        int total = 0;
        for (String tag : tags) {
            int fired = AnnouncerRegistry.trigger(source.getServer(), tag);
            total += fired;
            if (fired > 0) {
                final int count = fired;
                source.sendFeedback(() -> Text.translatable(
                        "commands.station_announcer.announce.success", count, tag), true);
            } else {
                source.sendFeedback(() -> Text.translatable(
                        "commands.station_announcer.announce.unknown_tag", tag).formatted(Formatting.YELLOW), false);
            }
        }
        return total;
    }

    /** Splits the greedy argument on whitespace and strips optional {@code tag=} prefixes. */
    private static Set<String> parseTags(String input) {
        Set<String> tags = new LinkedHashSet<>();
        for (String token : input.trim().split("\\s+")) {
            if (token.startsWith("tag=")) {
                token = token.substring("tag=".length());
            }
            if (!token.isBlank()) {
                tags.add(token);
            }
        }
        return tags;
    }

    /**
     * Suggests {@code tag=<known-tag>} for the token currently being typed.
     * Because the argument is a single greedy string, suggestions are offset
     * past the last space so each tag token completes independently.
     */
    private static CompletableFuture<Suggestions> suggestTags(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        String current = remaining.substring(lastSpace + 1).toLowerCase();
        SuggestionsBuilder tokenBuilder = builder.createOffset(builder.getStart() + lastSpace + 1);

        for (String tag : AnnouncerRegistry.knownTags(context.getSource().getServer())) {
            String candidate = "tag=" + tag;
            if (candidate.toLowerCase().startsWith(current) || tag.toLowerCase().startsWith(current)) {
                tokenBuilder.suggest(candidate);
            }
        }
        return tokenBuilder.buildFuture();
    }
}
