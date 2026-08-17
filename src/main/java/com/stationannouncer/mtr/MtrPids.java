package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Registration for the MTR-backed NYC PIDS blocks. Only classloaded when the
 * MTR mod is present (see the isModLoaded guard in StationAnnouncer); the
 * blocks are real vanilla blocks under MTR's mapping layer, so plain registry
 * calls work.
 */
public final class MtrPids {
    public static final BlockPidsNyc WALL_1 = new BlockPidsNyc(PidsStyle.ROUTE_MAP_WALL);
    public static final BlockPidsNyc WALL_2 = new BlockPidsNyc(PidsStyle.DEPARTURES_WALL);
    public static final BlockPidsNyc STANDING_1 = new BlockPidsNyc(PidsStyle.ROUTE_MAP_STANDING);
    public static final BlockPidsNyc STANDING_2 = new BlockPidsNyc(PidsStyle.DEPARTURES_STANDING);
    public static final BlockPidsNyc HANGING = new BlockPidsNyc.Hanging(PidsStyle.HANGING);
    public static final BlockPidsNyc HANGING_MINI = new BlockPidsNyc.Hanging(PidsStyle.HANGING_MINI);

    /** Commuter-railroad departure boards: same 2-block envelope, our own screen. */
    public static final RailroadPidsBlock RAILROAD_WALL = new RailroadPidsBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), false);
    public static final RailroadPidsBlock RAILROAD_STANDING = new RailroadPidsBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), true);

    public static final BlockItem WALL_1_ITEM = new BlockItem(WALL_1, new Item.Settings());
    public static final BlockItem WALL_2_ITEM = new BlockItem(WALL_2, new Item.Settings());
    public static final BlockItem STANDING_1_ITEM = new BlockItem(STANDING_1, new Item.Settings());
    public static final BlockItem STANDING_2_ITEM = new BlockItem(STANDING_2, new Item.Settings());
    public static final BlockItem HANGING_ITEM = new BlockItem(HANGING, new Item.Settings());
    public static final BlockItem HANGING_MINI_ITEM = new BlockItem(HANGING_MINI, new Item.Settings());

    /** The small concourse departure board: next trains out, stops for the top two. */
    public static final RailroadDepartureBlock RAILROAD_DEPARTURE_WALL = new RailroadDepartureBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());

    /** The big concourse board: place a rectangle of them and they merge into one screen. */
    public static final DepartureBoardBlock DEPARTURE_BOARD_WALL = new DepartureBoardBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), false);
    public static final DepartureBoardBlock DEPARTURE_BOARD_HANGING = new DepartureBoardBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), true);

    public static final BlockItem RAILROAD_DEPARTURE_WALL_ITEM =
            new BlockItem(RAILROAD_DEPARTURE_WALL, new Item.Settings());
    public static final BlockItem DEPARTURE_BOARD_WALL_ITEM =
            new BlockItem(DEPARTURE_BOARD_WALL, new Item.Settings());
    public static final BlockItem DEPARTURE_BOARD_HANGING_ITEM =
            new BlockItem(DEPARTURE_BOARD_HANGING, new Item.Settings());

    /** Ceiling-hung railroad board: two alternating screens in a deep case. */
    public static final RailroadPidsHangingBlock RAILROAD_HANGING = new RailroadPidsHangingBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());

    public static final BlockItem RAILROAD_WALL_ITEM = new BlockItem(RAILROAD_WALL, new Item.Settings());
    public static final BlockItem RAILROAD_STANDING_ITEM = new BlockItem(RAILROAD_STANDING, new Item.Settings());

    public static final BlockItem RAILROAD_HANGING_ITEM = new BlockItem(RAILROAD_HANGING, new Item.Settings());

    public static final BlockEntityType<RailroadPidsBlockEntity> RAILROAD_PIDS_BLOCK_ENTITY =
            BlockEntityType.Builder.create(RailroadPidsBlockEntity::new, RAILROAD_WALL, RAILROAD_STANDING).build(null);
    public static final BlockEntityType<RailroadPidsBlockEntity> RAILROAD_HANGING_BLOCK_ENTITY =
            BlockEntityType.Builder.create(RailroadPidsBlockEntity::new, RAILROAD_HANGING).build(null);
    /** Its own type only so it can carry its own renderer. */
    public static final BlockEntityType<RailroadPidsBlockEntity> RAILROAD_DEPARTURE_BLOCK_ENTITY =
            BlockEntityType.Builder.create(RailroadPidsBlockEntity::new, RAILROAD_DEPARTURE_WALL).build(null);
    public static final BlockEntityType<DepartureBoardBlockEntity> DEPARTURE_BOARD_BLOCK_ENTITY =
            BlockEntityType.Builder.create(DepartureBoardBlockEntity::new, DEPARTURE_BOARD_WALL).build(null);
    public static final BlockEntityType<DepartureBoardBlockEntity> DEPARTURE_BOARD_HANGING_BLOCK_ENTITY =
            BlockEntityType.Builder.create(DepartureBoardBlockEntity::new, DEPARTURE_BOARD_HANGING).build(null);

    public static final BlockEntityType<PidsBlockEntity> PIDS_BLOCK_ENTITY =
            BlockEntityType.Builder.<PidsBlockEntity>create((pos, state) -> {
                PidsStyle style = state.getBlock() instanceof BlockPidsNyc block ? block.style : PidsStyle.DEPARTURES_WALL;
                return new PidsBlockEntity(style,
                        new org.mtr.mapping.holder.BlockPos(pos),
                        new org.mtr.mapping.holder.BlockState(state));
            }, WALL_1, WALL_2, STANDING_1, STANDING_2, HANGING, HANGING_MINI).build(null);

    /** C2S: the mini's "Next train" toggle screen saves (pos + boolean). */
    public static final Identifier UPDATE_MINI_C2S = StationAnnouncer.id("update_mini");

    /**
     * Opens a PIDS block entity's full settings screen, client side only.
     * Separate from {@link StationAnnouncer#GUI_OPENER} because the mini has
     * two entry points: the brush opens this, a bare click opens the quick
     * "Next train" toggle. Installed by the client module; a no-op on servers,
     * which is what keeps client classes off them.
     */
    public static java.util.function.Consumer<net.minecraft.block.entity.BlockEntity> CONFIG_GUI_OPENER =
            blockEntity -> {
            };

    /** C2S: the NYC PIDS settings screen saves (platforms + message rows + mini mode). */
    public static final Identifier UPDATE_NYC_PIDS_C2S = StationAnnouncer.id("update_nyc_pids");

    /** C2S: the railroad board's settings screen saves (pos + mode mask + platform ids). */
    public static final Identifier UPDATE_RAILROAD_PIDS_C2S = StationAnnouncer.id("update_railroad_pids");
    public static final Identifier UPDATE_DEPARTURE_BOARD_C2S = StationAnnouncer.id("update_departure_board");

    private MtrPids() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_MINI_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean nextTrainMode = buf.readBoolean();
            int onSeconds = buf.readVarInt();
            int offSeconds = buf.readVarInt();
            int count = Math.min(buf.readVarInt(), PidsBlockEntity.MAX_ARROW_OVERRIDES);
            long[] arrowPlatforms = new long[Math.max(0, count)];
            int[] arrowDirs = new int[arrowPlatforms.length];
            for (int i = 0; i < arrowPlatforms.length; i++) {
                arrowPlatforms[i] = buf.readLong();
                arrowDirs[i] = buf.readByte();
            }
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof PidsBlockEntity pids) {
                    pids.setNextTrainMode(nextTrainMode);
                    pids.setNextTrainOnSeconds(onSeconds);
                    pids.setNextTrainOffSeconds(offSeconds);
                    // The screen sends the whole map every save; AUTO entries
                    // are simply not sent, so replacing wholesale is correct.
                    pids.clearNextTrainArrows();
                    for (int i = 0; i < arrowPlatforms.length; i++) {
                        pids.putNextTrainArrow(arrowPlatforms[i], arrowDirs[i]);
                    }
                    pids.markDirty();
                    world.getChunkManager().markForUpdate(pos);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_RAILROAD_PIDS_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int modes = buf.readInt();
            int displayMode = buf.readVarInt();
            int flipSeconds = buf.readVarInt();
            String title = buf.readString(RailroadPidsBlockEntity.MAX_TITLE_LENGTH);
            int trackRevealSeconds = buf.readVarInt();
            int count = Math.min(buf.readVarInt(), RailroadPidsBlockEntity.MAX_PLATFORMS);
            long[] platformIds = new long[Math.max(0, count)];
            for (int i = 0; i < platformIds.length; i++) {
                platformIds[i] = buf.readLong();
            }
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof RailroadPidsBlockEntity pids) {
                    pids.setPlatformIds(platformIds);
                    pids.setConnectionModes(modes);
                    pids.setDisplayMode(RailroadPidsBlockEntity.DisplayMode.byOrdinal(displayMode));
                    pids.setFlipSeconds(flipSeconds);
                    pids.setTitle(title);
                    pids.setTrackRevealSeconds(trackRevealSeconds);
                    pids.sync();
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_DEPARTURE_BOARD_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String title = buf.readString(DepartureBoardBlockEntity.MAX_TITLE_LENGTH);
            int trackRevealSeconds = buf.readVarInt();
            int count = Math.min(buf.readVarInt(), DepartureBoardBlockEntity.MAX_PLATFORMS);
            long[] platformIds = new long[Math.max(0, count)];
            for (int i = 0; i < platformIds.length; i++) {
                platformIds[i] = buf.readLong();
            }
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0
                        || !world.canPlayerModifyAt(player, pos)
                        || !(world.getBlockState(pos).getBlock() instanceof DepartureBoardBlock)) {
                    return;
                }
                // Write to EVERY cell of the merged board, not just the clicked
                // one: the origin moves when the board grows, and settings kept
                // on the origin alone would appear to vanish.
                net.minecraft.block.BlockState state = world.getBlockState(pos);
                DepartureBoardBlock.Rect rect = DepartureBoardBlock.rectangle(world, pos, state);
                net.minecraft.util.math.Direction right =
                        DepartureBoardBlock.screenRight(state.get(DepartureBoardBlock.FACING));
                for (int y = 0; y < rect.height(); y++) {
                    for (int x = 0; x < rect.width(); x++) {
                        BlockPos cell = rect.origin().up(y).offset(right, x);
                        if (world.getBlockEntity(cell) instanceof DepartureBoardBlockEntity board) {
                            board.setTitle(title);
                            board.setTrackRevealSeconds(trackRevealSeconds);
                            board.setPlatformIds(platformIds);
                            board.sync();
                        }
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_NYC_PIDS_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean nextTrainMode = buf.readBoolean();
            String message = buf.readString(256);
            int platformCount = Math.min(buf.readVarInt(), 64);
            long[] platformIds = new long[Math.max(0, platformCount)];
            for (int i = 0; i < platformIds.length; i++) {
                platformIds[i] = buf.readLong();
            }
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0
                        || !world.canPlayerModifyAt(player, pos)
                        || !(world.getBlockEntity(pos) instanceof PidsBlockEntity pids)) {
                    return;
                }
                org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet ids =
                        new org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet();
                for (long id : platformIds) {
                    ids.add(id);
                }
                // Our screen offers one message box, so it lands in the first
                // of MTR's rows and the rest are cleared — otherwise text typed
                // through MTR's own screen would linger invisibly. The hide-
                // arrival flags ARE preserved, since we never offer them and
                // setData rewrites every field at once.
                String[] allMessages = new String[pids.maxArrivals];
                boolean[] hideArrivals = new boolean[pids.maxArrivals];
                for (int i = 0; i < pids.maxArrivals; i++) {
                    allMessages[i] = i == 0 ? message : "";
                    hideArrivals[i] = pids.getHideArrival(i);
                }
                pids.setData(allMessages, hideArrivals, ids, pids.getDisplayPage());
                pids.setNextTrainMode(nextTrainMode);
                pids.markDirty();
                world.getChunkManager().markForUpdate(pos);
            });
        });

        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_wall_1"), WALL_1);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_wall_2"), WALL_2);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_standing_1"), STANDING_1);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_standing_2"), STANDING_2);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_hanging"), HANGING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_hanging_mini"), HANGING_MINI);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("railroad_pids_wall"), RAILROAD_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("railroad_pids_standing"), RAILROAD_STANDING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("railroad_pids_hanging"), RAILROAD_HANGING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("railroad_departure_wall"), RAILROAD_DEPARTURE_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("departure_board_wall"), DEPARTURE_BOARD_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("departure_board_hanging"), DEPARTURE_BOARD_HANGING);

        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_wall_1"), WALL_1_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_wall_2"), WALL_2_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_standing_1"), STANDING_1_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_standing_2"), STANDING_2_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_hanging"), HANGING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_hanging_mini"), HANGING_MINI_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("railroad_pids_wall"), RAILROAD_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("railroad_pids_standing"), RAILROAD_STANDING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("railroad_pids_hanging"), RAILROAD_HANGING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("railroad_departure_wall"), RAILROAD_DEPARTURE_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("departure_board_wall"), DEPARTURE_BOARD_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("departure_board_hanging"), DEPARTURE_BOARD_HANGING_ITEM);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("pids_nyc"), PIDS_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("railroad_pids"), RAILROAD_PIDS_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("railroad_pids_hanging"), RAILROAD_HANGING_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("railroad_departure"), RAILROAD_DEPARTURE_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("departure_board"), DEPARTURE_BOARD_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("departure_board_hanging"), DEPARTURE_BOARD_HANGING_BLOCK_ENTITY);

        ModContent.OPERATIONS_ENTRIES.add(WALL_1_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(WALL_2_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(STANDING_1_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(STANDING_2_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(HANGING_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(HANGING_MINI_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(RAILROAD_WALL_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(RAILROAD_STANDING_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(RAILROAD_HANGING_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(RAILROAD_DEPARTURE_WALL_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(DEPARTURE_BOARD_WALL_ITEM);
        ModContent.OPERATIONS_ENTRIES.add(DEPARTURE_BOARD_HANGING_ITEM);
    }
}
