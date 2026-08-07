package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
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

    public static final BlockItem WALL_1_ITEM = new BlockItem(WALL_1, new Item.Settings());
    public static final BlockItem WALL_2_ITEM = new BlockItem(WALL_2, new Item.Settings());
    public static final BlockItem STANDING_1_ITEM = new BlockItem(STANDING_1, new Item.Settings());
    public static final BlockItem STANDING_2_ITEM = new BlockItem(STANDING_2, new Item.Settings());
    public static final BlockItem HANGING_ITEM = new BlockItem(HANGING, new Item.Settings());
    public static final BlockItem HANGING_MINI_ITEM = new BlockItem(HANGING_MINI, new Item.Settings());

    public static final BlockEntityType<PidsBlockEntity> PIDS_BLOCK_ENTITY =
            BlockEntityType.Builder.<PidsBlockEntity>create((pos, state) -> {
                PidsStyle style = state.getBlock() instanceof BlockPidsNyc block ? block.style : PidsStyle.DEPARTURES_WALL;
                return new PidsBlockEntity(style,
                        new org.mtr.mapping.holder.BlockPos(pos),
                        new org.mtr.mapping.holder.BlockState(state));
            }, WALL_1, WALL_2, STANDING_1, STANDING_2, HANGING, HANGING_MINI).build(null);

    /** C2S: the mini's "Next train" toggle screen saves (pos + boolean). */
    public static final Identifier UPDATE_MINI_C2S = StationAnnouncer.id("update_mini");

    private MtrPids() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_MINI_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean nextTrainMode = buf.readBoolean();
            server.execute(() -> {
                ServerWorld world = player.getServerWorld();
                if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 64.0 * 64.0
                        && world.canPlayerModifyAt(player, pos)
                        && world.getBlockEntity(pos) instanceof PidsBlockEntity pids) {
                    pids.setNextTrainMode(nextTrainMode);
                    pids.markDirty();
                    world.getChunkManager().markForUpdate(pos);
                }
            });
        });
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_wall_1"), WALL_1);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_wall_2"), WALL_2);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_standing_1"), STANDING_1);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_standing_2"), STANDING_2);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_hanging"), HANGING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pids_nyc_hanging_mini"), HANGING_MINI);

        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_wall_1"), WALL_1_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_wall_2"), WALL_2_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_standing_1"), STANDING_1_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_standing_2"), STANDING_2_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_hanging"), HANGING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pids_nyc_hanging_mini"), HANGING_MINI_ITEM);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("pids_nyc"), PIDS_BLOCK_ENTITY);

        ModContent.BAKER_CITY_ENTRIES.add(WALL_1_ITEM);
        ModContent.BAKER_CITY_ENTRIES.add(WALL_2_ITEM);
        ModContent.BAKER_CITY_ENTRIES.add(STANDING_1_ITEM);
        ModContent.BAKER_CITY_ENTRIES.add(STANDING_2_ITEM);
        ModContent.BAKER_CITY_ENTRIES.add(HANGING_ITEM);
        ModContent.BAKER_CITY_ENTRIES.add(HANGING_MINI_ITEM);
    }
}
