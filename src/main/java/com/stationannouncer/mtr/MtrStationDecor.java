package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/**
 * Registration for the MTR-data station decor: the mosaic station-name band,
 * the four iron column variants and the next-train arrow sign. Only
 * classloaded when MTR is present (the renderer needs MTR's station data).
 */
public final class MtrStationDecor {
    private static final VoxelShape MOSAIC_SHAPE = Block.createCuboidShape(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
    private static final VoxelShape COLUMN_SHAPE = Block.createCuboidShape(3.0, 0.0, 4.0, 13.0, 16.0, 12.0);

    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque();
    }

    public static final StationDecorBlock STATION_NAME_MOSAIC = new StationDecorBlock(settings(), MOSAIC_SHAPE);

    /** Plain and station-tinted columns: pure model blocks, no block entity. */
    public static final ColumnBlock COLUMN_IRON = new ColumnBlock(settings(), COLUMN_SHAPE);
    public static final ColumnBlock COLUMN_IRON_STATION = new ColumnBlock(settings(), COLUMN_SHAPE);

    /** Named columns carry the station-name board (block entity + screen). */
    public static final StationColumnBlock COLUMN_IRON_NAMED = new StationColumnBlock(settings(), COLUMN_SHAPE);
    public static final StationColumnBlock COLUMN_IRON_NAMED_STATION = new StationColumnBlock(settings(), COLUMN_SHAPE);

    /** Railing segment with the black station-name panel (brush to edit). */
    public static final RailingSignBlock ENTRANCE_RAILING_SIGN = new RailingSignBlock(settings());

    /** Emergency exit door: exit-only, alarms, fines anyone coming the wrong way. */
    public static final EmergencyExitDoorBlock EMERGENCY_EXIT_DOOR = new EmergencyExitDoorBlock(settings());

    /** Fare-control turnstiles: MTR-charging lane + solid end cap. */
    public static final TurnstileBlock TURNSTILE = new TurnstileBlock(settings());
    public static final TurnstileCapBlock TURNSTILE_CAP = new TurnstileCapBlock(settings());

    /** Holding lights (timed off MTR arrivals) and their hanging hardware. */
    public static final HoldingLightBlock HOLDING_LIGHT_YELLOW = new HoldingLightBlock(
            settings(), false, Block.createCuboidShape(3.0, 10.0, 6.0, 13.0, 16.0, 10.0));
    public static final HoldingLightBlock HOLDING_LIGHT_GREEN = new HoldingLightBlock(
            settings(), true, Block.createCuboidShape(3.0, 10.0, 6.0, 13.0, 16.0, 10.0));
    public static final com.stationannouncer.block.DecorBlock HOLDING_LIGHT_POLE =
            new com.stationannouncer.block.DecorBlock(settings(),
                    Block.createCuboidShape(5.5, 0.0, 5.5, 10.5, 16.0, 10.5));

    /**
     * Plain steel drop pole so hanging PIDS (and zebra boards) can mount lower
     * from the ceiling. 2 px gauge, matching the hanging PIDS's own ceiling
     * stub at x/z 7..9 so the two line up without a step.
     */
    public static final com.stationannouncer.block.DecorBlock PIDS_POLE =
            new com.stationannouncer.block.DecorBlock(settings(),
                    Block.createCuboidShape(7.0, 0.0, 7.0, 9.0, 16.0, 9.0));

    /**
     * Car-stop markers: the little numbered plates that mark where a train of
     * a given length stops, plus a thin pole to hang them lower.
     */
    public static final StopMarkerBlock STOP_MARKER = new StopMarkerBlock(settings());
    /** Turns with the face it is placed against, so markers can hang off a horizontal run too. */
    public static final com.stationannouncer.block.PoleBlock STOP_MARKER_POLE =
            new com.stationannouncer.block.PoleBlock(settings(), 1.0);

    public static final BlockEntityType<StopMarkerBlockEntity> STOP_MARKER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(StopMarkerBlockEntity::new, STOP_MARKER).build(null);

    public static final BlockEntityType<StationDecorBlockEntity> DECOR_BLOCK_ENTITY =
            BlockEntityType.Builder.create(StationDecorBlockEntity::new,
                    STATION_NAME_MOSAIC, COLUMN_IRON_NAMED, COLUMN_IRON_NAMED_STATION,
                    ENTRANCE_RAILING_SIGN, HOLDING_LIGHT_YELLOW, HOLDING_LIGHT_GREEN).build(null);

    /** C2S: the sign name screen saves (pos + custom name; "" = automatic). */
    public static final Identifier UPDATE_DECOR_C2S = StationAnnouncer.id("update_decor");

    /** C2S: the stop marker's plate editor saves (pos + mounting + the plate list). */
    public static final Identifier UPDATE_STOP_MARKER_C2S = StationAnnouncer.id("update_stop_marker");

    /** C2S: the railing sign's editor saves (pos + name + which faces show + their route bullets). */
    public static final Identifier UPDATE_RAILING_SIGN_C2S = StationAnnouncer.id("update_railing_sign");

    private MtrStationDecor() {
    }

    public static void register() {
        // The turnstile keeps a per-player fare cooldown map; drop it when the
        // server stops (mainly for singleplayer, where the JVM outlives worlds).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> {
                    TurnstileBlock.clearAttempts();
                    EmergencyExitDoorBlock.clearCrossings();
                });

        // Fare machines convert emeralds into MTR ticket balance at MTR's own
        // rate (1 emerald = $10, same as the MTR ticket machine); an empty
        // hand reads the balance back.
        com.stationannouncer.block.FareMachineBlock.FARE_HANDLER = (world, pos, player, heldStack) -> {
            org.mtr.mapping.holder.World holderWorld = new org.mtr.mapping.holder.World(world);
            org.mtr.mapping.holder.PlayerEntity holderPlayer = new org.mtr.mapping.holder.PlayerEntity(player);
            if (heldStack.isOf(net.minecraft.item.Items.EMERALD)) {
                heldStack.decrement(1);
                org.mtr.mod.data.TicketSystem.addBalance(holderWorld, holderPlayer, 10);
                world.playSound(null, pos, net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        net.minecraft.sound.SoundCategory.BLOCKS, 0.6f, 1.4f);
                player.sendMessage(net.minecraft.text.Text.translatable("msg.station_announcer.fare.paid",
                        10, org.mtr.mod.data.TicketSystem.getBalance(holderWorld, holderPlayer)), true);
            } else {
                player.sendMessage(net.minecraft.text.Text.translatable("msg.station_announcer.fare.balance",
                        org.mtr.mod.data.TicketSystem.getBalance(holderWorld, holderPlayer)), true);
            }
        };

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_DECOR_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String customName = buf.readString(StationDecorBlockEntity.MAX_NAME_LENGTH);
            // -1 means "leave this light on its own default", so plain ints
            // rather than varints.
            int lightOnSeconds = buf.readInt();
            int lightOffSeconds = buf.readInt();
            StationDecorBlockEntity.HoldIndicator holdIndicator =
                    StationDecorBlockEntity.HoldIndicator.byOrdinal(buf.readByte());
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                    decor.setCustomName(customName);
                    decor.setLightOnSeconds(lightOnSeconds);
                    decor.setLightOffSeconds(lightOffSeconds);
                    decor.setHoldIndicator(holdIndicator);
                    decor.sync();
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_RAILING_SIGN_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String customName = buf.readString(StationDecorBlockEntity.MAX_NAME_LENGTH);
            boolean front = buf.readBoolean();
            boolean back = buf.readBoolean();
            java.util.List<String> frontRoutes = readRoutes(buf);
            java.util.List<String> backRoutes = readRoutes(buf);
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                    decor.setCustomName(customName);
                    decor.setSignFront(front);
                    decor.setSignBack(back);
                    decor.setFrontRoutes(frontRoutes);
                    decor.setBackRoutes(backRoutes);
                    decor.sync();
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_STOP_MARKER_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            StopMarkerBlock.Style style = buf.readEnumConstant(StopMarkerBlock.Style.class);
            int count = Math.min(buf.readVarInt(), StopMarkerBlockEntity.MAX_SIGNS);
            java.util.List<StopMarkerBlockEntity.Sign> signs = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                StopMarkerBlockEntity.SignColor color = buf.readEnumConstant(StopMarkerBlockEntity.SignColor.class);
                signs.add(new StopMarkerBlockEntity.Sign(color,
                        buf.readString(StopMarkerBlockEntity.MAX_TEXT_LENGTH)));
            }
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof StopMarkerBlockEntity marker) {
                    marker.setSigns(signs);
                    marker.sync();
                    // The bracket and the blade stub are geometry, so the
                    // mounting style rides in the blockstate.
                    net.minecraft.block.BlockState state = world.getBlockState(pos);
                    if (state.getBlock() instanceof StopMarkerBlock
                            && StopMarkerBlock.Style.of(state) != style) {
                        world.setBlockState(pos, state
                                        .with(StopMarkerBlock.STANDOFF, style == StopMarkerBlock.Style.BRACKET)
                                        .with(StopMarkerBlock.BLADE, style == StopMarkerBlock.Style.BLADE),
                                Block.NOTIFY_ALL);
                    }
                }
            });
        });

        // Station furniture: things you build a station OUT of.
        // The tile-wall set registers itself (blocks, block entity, packet).
        SubwayWalls.register();

        registerBlock("station_name_mosaic", STATION_NAME_MOSAIC, ModContent.DECORATION_ENTRIES);
        registerBlock("column_iron", COLUMN_IRON, ModContent.DECORATION_ENTRIES);
        registerBlock("column_iron_station", COLUMN_IRON_STATION, ModContent.DECORATION_ENTRIES);
        registerBlock("column_iron_named", COLUMN_IRON_NAMED, ModContent.DECORATION_ENTRIES);
        registerBlock("column_iron_named_station", COLUMN_IRON_NAMED_STATION, ModContent.DECORATION_ENTRIES);
        registerBlock("entrance_railing_sign", ENTRANCE_RAILING_SIGN, ModContent.DECORATION_ENTRIES);
        registerBlock("emergency_exit_door", EMERGENCY_EXIT_DOOR, ModContent.DECORATION_ENTRIES);
        registerBlock("turnstile", TURNSTILE, ModContent.DECORATION_ENTRIES);
        registerBlock("turnstile_cap", TURNSTILE_CAP, ModContent.DECORATION_ENTRIES);
        registerBlock("stop_marker", STOP_MARKER, ModContent.DECORATION_ENTRIES);
        registerBlock("stop_marker_pole", STOP_MARKER_POLE, ModContent.DECORATION_ENTRIES);

        // Equipment: driven by live train data, and the poles that carry it.
        registerBlock("holding_light_yellow", HOLDING_LIGHT_YELLOW, ModContent.OPERATIONS_ENTRIES);
        registerBlock("holding_light_green", HOLDING_LIGHT_GREEN, ModContent.OPERATIONS_ENTRIES);
        registerBlock("holding_light_pole", HOLDING_LIGHT_POLE, ModContent.OPERATIONS_ENTRIES);
        registerBlock("pids_pole", PIDS_POLE, ModContent.OPERATIONS_ENTRIES);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("station_decor"), DECOR_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("stop_marker"), STOP_MARKER_BLOCK_ENTITY);
    }

    /** One face's route bullets off the wire, capped both in count and in length. */
    private static java.util.List<String> readRoutes(net.minecraft.network.PacketByteBuf buf) {
        int count = Math.min(buf.readVarInt(), StationDecorBlockEntity.MAX_ROUTE_BULLETS);
        java.util.List<String> routes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            routes.add(buf.readString(StationDecorBlockEntity.MAX_ROUTE_NAME_LENGTH));
        }
        return routes;
    }

    /** @param tab which creative tab the block's item belongs in. */
    private static void registerBlock(String name, Block block,
                                      java.util.List<net.minecraft.item.ItemConvertible> tab) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(name), block);
        BlockItem item = new BlockItem(block, new Item.Settings());
        Registry.register(Registries.ITEM, StationAnnouncer.id(name), item);
        tab.add(item);
    }
}
