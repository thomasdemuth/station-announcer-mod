package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.wayfinding.ExitPin;
import com.stationannouncer.wayfinding.Place;
import com.stationannouncer.wayfinding.PlaceCategory;
import com.stationannouncer.wayfinding.PlaceCommand;
import com.stationannouncer.wayfinding.WayfindingStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wayfinding, server side: the Exit Marker and Place Marker blocks, their
 * editors' packets, the {@code /place} command and the
 * {@link WayfindingStore} lifecycle. One file plus one call from
 * {@link MtrStationDecor#register()}, like {@link GapFillers}.
 *
 * <p><b>Store first.</b> Every marker reconciles with the store on its first
 * server tick after loading: if the store knows its position the block ADOPTS
 * the store's record (command edits and exit renames made while it was
 * unloaded win); if not, the block registers the copy it carries (a fresh
 * placement or a structure paste). Breaking a marker forgets its record; a
 * marker removed by command while unloaded is tombstoned and removes itself
 * on load.</p>
 */
public final class Wayfinding {
    public static final ExitMarkerBlock EXIT_MARKER = new ExitMarkerBlock(settings());
    public static final PlaceMarkerBlock PLACE_MARKER = new PlaceMarkerBlock(settings());

    public static final BlockEntityType<ExitMarkerBlockEntity> EXIT_MARKER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(ExitMarkerBlockEntity::new, EXIT_MARKER).build(null);
    public static final BlockEntityType<PlaceMarkerBlockEntity> PLACE_MARKER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(PlaceMarkerBlockEntity::new, PLACE_MARKER).build(null);

    /** C2S from the exit editor: pos, station, pinned exit, renames, deletions. */
    public static final Identifier UPDATE_EXIT_MARKER_C2S = StationAnnouncer.id("update_exit_marker");
    /** C2S from the place editor: pos, name, category, description, radius, hidden. */
    public static final Identifier UPDATE_PLACE_MARKER_C2S = StationAnnouncer.id("update_place_marker");

    /** A packet may carry at most this many renames / deletions (a station has a handful of exits). */
    public static final int MAX_EXIT_EDITS = 64;

    private static int saveCountdown;

    private Wayfinding() {
    }

    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.create().noCollision().nonOpaque().strength(0.8f, 3.0f)
                .sounds(BlockSoundGroup.WOOL).pistonBehavior(PistonBehavior.DESTROY)
                .allowsSpawning((state, world, pos, type) -> false)
                .solidBlock((state, world, pos) -> false)
                .suffocates((state, world, pos) -> false)
                .blockVision((state, world, pos) -> false);
    }

    public static void register() {
        registerBlock("exit_marker", EXIT_MARKER);
        registerBlock("place_marker", PLACE_MARKER);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("exit_marker"), EXIT_MARKER_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("place_marker"), PLACE_MARKER_BLOCK_ENTITY);

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_EXIT_MARKER_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            long stationId = buf.readLong();
            String exitName = buf.readString(ExitPin.MAX_EXIT_NAME);
            int renameCount = Math.min(buf.readVarInt(), MAX_EXIT_EDITS);
            Map<String, String> renames = new HashMap<>();
            for (int i = 0; i < renameCount; i++) {
                renames.put(buf.readString(ExitPin.MAX_EXIT_NAME), buf.readString(ExitPin.MAX_EXIT_NAME));
            }
            int deleteCount = Math.min(buf.readVarInt(), MAX_EXIT_EDITS);
            Set<String> deleted = new HashSet<>();
            for (int i = 0; i < deleteCount; i++) {
                deleted.add(buf.readString(ExitPin.MAX_EXIT_NAME));
            }
            server.execute(() -> handleExitUpdate(player, pos, stationId, exitName, renames, deleted));
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_PLACE_MARKER_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String name = buf.readString(Place.MAX_NAME);
            String category = buf.readString(32);
            String description = buf.readString(Place.MAX_DESCRIPTION);
            int radius = buf.readVarInt();
            boolean hidden = buf.readBoolean();
            server.execute(() -> handlePlaceUpdate(player, pos, name, PlaceCategory.byId(category), description, radius, hidden));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PlaceCommand.register(dispatcher);
            com.stationannouncer.wayfinding.layout.StationLayoutCommand.register(dispatcher);
        });
        // Station layouts: scans on request only (Layout tab, /stationlayout).
        com.stationannouncer.wayfinding.layout.LayoutScanner.register();
        // The public API other mods (MTR-Games) use: places, exits, layouts, events.
        com.stationannouncer.wayfinding.WayfindingApiBackend.register();

        ServerLifecycleEvents.SERVER_STARTED.register(WayfindingStore::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> WayfindingStore.unload());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--saveCountdown <= 0) {
                saveCountdown = 100;
                WayfindingStore.saveIfDirty();
            }
        });
    }

    private static void registerBlock(String name, Block block) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(name), block);
        BlockItem item = new BlockItem(block, new Item.Settings());
        Registry.register(Registries.ITEM, StationAnnouncer.id(name), item);
        ModContent.OPERATIONS_ENTRIES.add(item);
    }

    // ------------------------------------------------------------------ keys

    /** {@code dimension|x|y|z} — the store's key for a marker block. */
    public static String blockKey(World world, BlockPos pos) {
        return world.getRegistryKey().getValue() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** MTR's id for this world ("minecraft/overworld"), what Map+ matches simulators on. */
    public static String mtrDim(World world) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return world.getRegistryKey().getValue().toString().replace(':', '/');
        }
    }

    @Nullable
    public static ServerWorld worldOf(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getWorld(RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id));
    }

    // ------------------------------------------------------------ exit pins

    /** First server tick after load: adopt the store's pin, or register ours. */
    static void reconcileExit(ServerWorld world, BlockPos pos, ExitMarkerBlockEntity marker) {
        String key = blockKey(world, pos);
        if (WayfindingStore.consumeTombstone(key)) {
            world.removeBlock(pos, false);
            return;
        }
        ExitPin pin = WayfindingStore.pinAt(key);
        if (pin != null) {
            marker.apply(pin.stationId(), pin.exitName());
            String mtrDim = mtrDim(world);
            if (!mtrDim.equals(pin.mtrDim())) {
                WayfindingStore.putPin(new ExitPin(key, pin.dimension(), mtrDim, pos.getX(), pos.getY(), pos.getZ(),
                        pin.stationId(), pin.exitName()));
            }
        } else {
            WayfindingStore.putPin(new ExitPin(key, world.getRegistryKey().getValue().toString(), mtrDim(world),
                    pos.getX(), pos.getY(), pos.getZ(), marker.getStationId(), marker.getExitName()));
        }
    }

    static void exitMarkerRemoved(World world, BlockPos pos) {
        WayfindingStore.removePin(blockKey(world, pos));
    }

    private static boolean canEdit(ServerPlayerEntity player, BlockPos pos) {
        ServerWorld world = player.getServerWorld();
        return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0 && world.canPlayerModifyAt(player, pos);
    }

    private static void handleExitUpdate(ServerPlayerEntity player, BlockPos pos, long stationId, String exitName,
                                         Map<String, String> renames, Set<String> deleted) {
        ServerWorld world = player.getServerWorld();
        if (!canEdit(player, pos) || !(world.getBlockEntity(pos) instanceof ExitMarkerBlockEntity marker)) {
            return;
        }
        String exit = exitName.trim();
        if (!exit.isEmpty() && !ExitPin.validExitName(exit)) {
            return;
        }
        // Renames/deletions in the editor carry every OTHER marker's pin along too.
        Map<String, String> cleanRenames = new HashMap<>();
        renames.forEach((from, to) -> {
            if (ExitPin.validExitName(from) && ExitPin.validExitName(to) && !from.equals(to)) {
                cleanRenames.put(from, to);
            }
        });
        if (stationId != 0 && (!cleanRenames.isEmpty() || !deleted.isEmpty())) {
            List<ExitPin> changed = WayfindingStore.applyExitEdits(stationId, cleanRenames, deleted);
            refreshLoadedPins(world.getServer(), changed);
        }
        marker.apply(stationId, stationId == 0 ? "" : exit);
        WayfindingStore.putPin(new ExitPin(blockKey(world, pos), world.getRegistryKey().getValue().toString(),
                mtrDim(world), pos.getX(), pos.getY(), pos.getZ(), stationId, stationId == 0 ? "" : exit));
    }

    private static void refreshLoadedPins(MinecraftServer server, List<ExitPin> pins) {
        for (ExitPin pin : pins) {
            ServerWorld world = worldOf(server, pin.dimension());
            BlockPos pos = new BlockPos(pin.x(), pin.y(), pin.z());
            if (world != null && world.isChunkLoaded(pos) && world.getBlockEntity(pos) instanceof ExitMarkerBlockEntity marker) {
                marker.apply(pin.stationId(), pin.exitName());
            }
        }
    }

    // ---------------------------------------------------------------- places

    /** First server tick after load: adopt the store's place, or register ours. */
    static void reconcilePlace(ServerWorld world, BlockPos pos, PlaceMarkerBlockEntity marker) {
        String key = blockKey(world, pos);
        if (WayfindingStore.consumeTombstone(key)) {
            world.removeBlock(pos, false);
            return;
        }
        Place stored = WayfindingStore.placeAtBlock(key);
        if (stored != null) {
            if (!mtrDim(world).equals(stored.mtrDim())) {
                stored = stored.withPosition(stored.dimension(), mtrDim(world), stored.x(), stored.y(), stored.z(), key);
                WayfindingStore.putPlace(stored);
            }
            marker.adopt(stored);
            return;
        }
        // Not known here: a fresh placement, or a paste. A pasted copy of a place that
        // still lives elsewhere must not steal its id — it becomes a place of its own.
        String id = marker.getPlaceId();
        Place existing = id.isEmpty() ? null : WayfindingStore.place(id);
        if (id.isEmpty() || (existing != null && !key.equals(existing.blockKey()))) {
            id = WayfindingStore.newId();
        }
        String name = marker.getName().isBlank() ? "New place" : marker.getName();
        Place place = new Place(id, name, marker.getCategory(), marker.getDescription(),
                world.getRegistryKey().getValue().toString(), mtrDim(world), pos.getX(), pos.getY(), pos.getZ(),
                marker.getRadius(), marker.isHidden(), key, marker.createdBy, System.currentTimeMillis());
        WayfindingStore.putPlace(place);
        marker.adopt(place);
    }

    static void placeMarkerRemoved(World world, BlockPos pos) {
        Place place = WayfindingStore.placeAtBlock(blockKey(world, pos));
        if (place != null) {
            WayfindingStore.removePlace(place.id());
        }
    }

    private static void handlePlaceUpdate(ServerPlayerEntity player, BlockPos pos, String name, PlaceCategory category,
                                          String description, int radius, boolean hidden) {
        ServerWorld world = player.getServerWorld();
        if (!canEdit(player, pos) || !(world.getBlockEntity(pos) instanceof PlaceMarkerBlockEntity marker)) {
            return;
        }
        if (!marker.reconciled) {
            marker.reconciled = true;
            reconcilePlace(world, pos, marker); // an editor saved before the block's first tick
        }
        Place current = WayfindingStore.placeAtBlock(blockKey(world, pos));
        if (current == null) {
            return;
        }
        String cleanName = Place.clean(name, Place.MAX_NAME);
        Place updated = current.withName(cleanName.isEmpty() ? current.name() : cleanName)
                .withCategory(category).withDescription(description).withRadius(radius).withHidden(hidden);
        WayfindingStore.putPlace(updated);
        marker.adopt(updated);
    }

    /** After a command changed a place: refresh its marker block if it is loaded. */
    public static void refreshLoadedPlace(MinecraftServer server, Place place) {
        if (!place.isBlock()) {
            return;
        }
        ServerWorld world = worldOf(server, place.dimension());
        BlockPos pos = new BlockPos(place.x(), place.y(), place.z());
        if (world != null && world.isChunkLoaded(pos) && world.getBlockEntity(pos) instanceof PlaceMarkerBlockEntity marker) {
            marker.adopt(place);
        }
    }

    /**
     * Remove a place by command. A block place's marker is broken if loaded
     * (which forgets the record), else tombstoned so it removes itself on load.
     */
    public static void removePlace(MinecraftServer server, Place place) {
        if (place.isBlock()) {
            ServerWorld world = worldOf(server, place.dimension());
            BlockPos pos = new BlockPos(place.x(), place.y(), place.z());
            if (world != null && world.isChunkLoaded(pos) && world.getBlockState(pos).isOf(PLACE_MARKER)) {
                world.removeBlock(pos, false);
            } else {
                WayfindingStore.tombstone(place.blockKey());
            }
        }
        WayfindingStore.removePlace(place.id());
    }
}
