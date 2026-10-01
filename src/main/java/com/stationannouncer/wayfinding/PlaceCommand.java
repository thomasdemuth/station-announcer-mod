package com.stationannouncer.wayfinding;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.stationannouncer.mtr.Wayfinding;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@code /place} — the same places a Place Marker block makes, without a block.
 *
 * <pre>
 * /place add &lt;category&gt; &lt;name…&gt;       here, at your feet          (level 2)
 * /place list [category]                                              (anyone)
 * /place near                          within 256 blocks, nearest first (anyone)
 * /place info &lt;place&gt;                                                (anyone)
 * /place rename &lt;place&gt; &lt;name…&gt;                                      (level 2)
 * /place category &lt;place&gt; &lt;category&gt;                                 (level 2)
 * /place describe &lt;place&gt; &lt;text…&gt;                                    (level 2)
 * /place radius &lt;place&gt; &lt;0..128&gt;                                     (level 2)
 * /place hide|show &lt;place&gt;             Map+ visibility                (level 2)
 * /place move &lt;place&gt;                  here (command places only)     (level 2)
 * /place tp &lt;place&gt;                                                  (level 2)
 * /place remove &lt;place&gt;                also breaks a marker block     (level 2)
 * </pre>
 *
 * {@code <place>} is an id, a full name or a unique name prefix; names with
 * spaces are quoted ({@code "City Hall"}) — tab completion does that for you.
 */
public final class PlaceCommand {
    private static final int EDIT_LEVEL = 2;
    private static final int NEAR_RADIUS = 256;

    private PlaceCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("place")
                .then(CommandManager.literal("add")
                        .requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(CommandManager.argument("category", StringArgumentType.word())
                                .suggests(PlaceCommand::suggestCategories)
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(PlaceCommand::add))))
                .then(CommandManager.literal("list")
                        .executes(context -> list(context, null))
                        .then(CommandManager.argument("category", StringArgumentType.word())
                                .suggests(PlaceCommand::suggestCategories)
                                .executes(context -> list(context,
                                        PlaceCategory.byId(StringArgumentType.getString(context, "category"))))))
                .then(CommandManager.literal("near").executes(PlaceCommand::near))
                .then(CommandManager.literal("info").then(placeArgument().executes(PlaceCommand::info)))
                .then(edit("rename", CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(context -> update(context, place -> {
                            String name = Place.clean(StringArgumentType.getString(context, "name"), Place.MAX_NAME);
                            return name.isEmpty() ? null : place.withName(name);
                        }))))
                .then(edit("category", CommandManager.argument("category", StringArgumentType.word())
                        .suggests(PlaceCommand::suggestCategories)
                        .executes(context -> update(context, place -> place.withCategory(
                                PlaceCategory.byId(StringArgumentType.getString(context, "category")))))))
                .then(edit("describe", CommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(context -> update(context, place -> place.withDescription(
                                StringArgumentType.getString(context, "text"))))))
                .then(edit("radius", CommandManager.argument("blocks", IntegerArgumentType.integer(0, Place.MAX_RADIUS))
                        .executes(context -> update(context, place -> place.withRadius(
                                IntegerArgumentType.getInteger(context, "blocks"))))))
                .then(CommandManager.literal("hide").requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(placeArgument().executes(context -> update(context, place -> place.withHidden(true)))))
                .then(CommandManager.literal("show").requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(placeArgument().executes(context -> update(context, place -> place.withHidden(false)))))
                .then(CommandManager.literal("move").requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(placeArgument().executes(PlaceCommand::move)))
                .then(CommandManager.literal("tp").requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(placeArgument().executes(PlaceCommand::teleport)))
                .then(CommandManager.literal("remove").requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                        .then(placeArgument().executes(PlaceCommand::remove))));
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> placeArgument() {
        return CommandManager.argument("place", StringArgumentType.string()).suggests(PlaceCommand::suggestPlaces);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> edit(
            String literal, RequiredArgumentBuilder<ServerCommandSource, ?> value) {
        return CommandManager.literal(literal).requires(source -> source.hasPermissionLevel(EDIT_LEVEL))
                .then(placeArgument().then(value));
    }

    // ------------------------------------------------------------- commands

    private static int add(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String categoryId = StringArgumentType.getString(context, "category");
        PlaceCategory category = PlaceCategory.byId(categoryId);
        if (category == PlaceCategory.OTHER && !categoryId.equalsIgnoreCase("other")) {
            source.sendError(Text.translatable("commands.station_announcer.place.bad_category", categoryId, categoryList()));
            return 0;
        }
        String name = Place.clean(StringArgumentType.getString(context, "name"), Place.MAX_NAME);
        if (name.isEmpty()) {
            source.sendError(Text.translatable("commands.station_announcer.place.no_name"));
            return 0;
        }
        ServerWorld world = source.getWorld();
        BlockPos pos = BlockPos.ofFloored(source.getPosition());
        String author = source.getEntity() instanceof ServerPlayerEntity player ? player.getGameProfile().getName() : "";
        Place place = new Place(WayfindingStore.newId(), name, category, "", world.getRegistryKey().getValue().toString(),
                Wayfinding.mtrDim(world), pos.getX(), pos.getY(), pos.getZ(), 0, false, null, author,
                System.currentTimeMillis());
        WayfindingStore.putPlace(place);
        source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.added",
                describe(place), pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> context, PlaceCategory filter) {
        ServerCommandSource source = context.getSource();
        List<Place> places = new ArrayList<>();
        for (Place place : WayfindingStore.places()) {
            if (filter == null || place.category() == filter) {
                places.add(place);
            }
        }
        places.sort(Comparator.comparing(place -> place.name().toLowerCase(Locale.ROOT)));
        return printList(source, places, "commands.station_announcer.place.list_header");
    }

    private static int near(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Vec3d here = source.getPosition();
        String dimension = source.getWorld().getRegistryKey().getValue().toString();
        List<Place> places = new ArrayList<>();
        for (Place place : WayfindingStore.places()) {
            if (place.dimension().equals(dimension) && distance(place, here) <= NEAR_RADIUS) {
                places.add(place);
            }
        }
        places.sort(Comparator.comparingDouble(place -> distance(place, here)));
        return printList(source, places, "commands.station_announcer.place.near_header");
    }

    private static int printList(ServerCommandSource source, List<Place> places, String headerKey) {
        if (places.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.none"), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable(headerKey, places.size()), false);
        Vec3d here = source.getPosition();
        String dimension = source.getWorld().getRegistryKey().getValue().toString();
        for (Place place : places) {
            MutableText line = Text.literal(" • ").formatted(Formatting.DARK_GRAY)
                    .append(describe(place))
                    .append(Text.literal("  " + place.x() + " " + place.y() + " " + place.z()).formatted(Formatting.GRAY));
            if (place.dimension().equals(dimension)) {
                line.append(Text.literal("  " + Math.round(distance(place, here)) + " m").formatted(Formatting.DARK_GRAY));
            }
            if (place.hidden()) {
                line.append(Text.translatable("commands.station_announcer.place.hidden_tag").formatted(Formatting.DARK_GRAY));
            }
            source.sendFeedback(() -> line, false);
        }
        return places.size();
    }

    private static int info(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Place place = resolve(context);
        if (place == null) {
            return 0;
        }
        source.sendFeedback(() -> describe(place).copy().formatted(Formatting.BOLD), false);
        source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.info",
                Text.translatable(place.category().translationKey()), place.x(), place.y(), place.z(),
                place.dimension(), place.radius(), place.id(),
                Text.translatable(place.isBlock() ? "commands.station_announcer.place.kind_block"
                        : "commands.station_announcer.place.kind_command")).formatted(Formatting.GRAY), false);
        if (!place.description().isEmpty()) {
            source.sendFeedback(() -> Text.literal(place.description()).formatted(Formatting.ITALIC), false);
        }
        return 1;
    }

    private static int update(CommandContext<ServerCommandSource> context, Function<Place, Place> change) {
        ServerCommandSource source = context.getSource();
        Place place = resolve(context);
        if (place == null) {
            return 0;
        }
        Place updated = change.apply(place);
        if (updated == null) {
            source.sendError(Text.translatable("commands.station_announcer.place.no_name"));
            return 0;
        }
        WayfindingStore.putPlace(updated);
        Wayfinding.refreshLoadedPlace(source.getServer(), updated);
        source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.updated", describe(updated)), true);
        return 1;
    }

    private static int move(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Place place = resolve(context);
        if (place == null) {
            return 0;
        }
        if (place.isBlock()) {
            source.sendError(Text.translatable("commands.station_announcer.place.move_block"));
            return 0;
        }
        ServerWorld world = source.getWorld();
        BlockPos pos = BlockPos.ofFloored(source.getPosition());
        Place moved = place.withPosition(world.getRegistryKey().getValue().toString(), Wayfinding.mtrDim(world),
                pos.getX(), pos.getY(), pos.getZ(), null);
        WayfindingStore.putPlace(moved);
        source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.moved",
                describe(moved), pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }

    private static int teleport(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Place place = resolve(context);
        if (place == null) {
            return 0;
        }
        ServerWorld world = Wayfinding.worldOf(source.getServer(), place.dimension());
        if (world == null || !(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("commands.station_announcer.place.no_tp"));
            return 0;
        }
        player.teleport(world, place.x() + 0.5, place.y(), place.z() + 0.5, player.getYaw(), player.getPitch());
        return 1;
    }

    private static int remove(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Place place = resolve(context);
        if (place == null) {
            return 0;
        }
        Wayfinding.removePlace(source.getServer(), place);
        source.sendFeedback(() -> Text.translatable("commands.station_announcer.place.removed", describe(place)), true);
        return 1;
    }

    // --------------------------------------------------------------- helpers

    private static Place resolve(CommandContext<ServerCommandSource> context) {
        String query = StringArgumentType.getString(context, "place");
        Place place = WayfindingStore.find(query);
        if (place == null) {
            context.getSource().sendError(Text.translatable("commands.station_announcer.place.unknown", query));
        }
        return place;
    }

    private static double distance(Place place, Vec3d here) {
        double dx = place.x() + 0.5 - here.x;
        double dz = place.z() + 0.5 - here.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** "Name (Category)" — hover shows the id, click suggests /place info. */
    private static Text describe(Place place) {
        int color = place.category().color();
        return Text.literal(place.name()).styled(style -> style
                        .withColor(color)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(place.id())))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/place info " + place.id())))
                .append(Text.literal(" (").formatted(Formatting.GRAY))
                .append(Text.translatable(place.category().translationKey()).formatted(Formatting.GRAY))
                .append(Text.literal(")").formatted(Formatting.GRAY));
    }

    private static String categoryList() {
        StringBuilder builder = new StringBuilder();
        for (PlaceCategory category : PlaceCategory.values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(category.id());
        }
        return builder.toString();
    }

    private static CompletableFuture<Suggestions> suggestCategories(CommandContext<ServerCommandSource> context,
                                                                    SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (PlaceCategory category : PlaceCategory.values()) {
            if (category.id().startsWith(typed)) {
                builder.suggest(category.id());
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPlaces(CommandContext<ServerCommandSource> context,
                                                                SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT).replace("\"", "");
        for (Place place : WayfindingStore.places()) {
            String name = place.name();
            if (name.toLowerCase(Locale.ROOT).contains(typed) || place.id().startsWith(typed)) {
                builder.suggest(quote(name), Text.literal(place.category().id() + " · " + place.id()));
            }
        }
        return builder.buildFuture();
    }

    private static String quote(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (!StringReader.isAllowedInUnquotedString(name.charAt(i))) {
                return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
        return name;
    }
}
