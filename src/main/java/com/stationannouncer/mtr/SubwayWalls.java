package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AlcoveBlock;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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

/**
 * The BMT/IND platform wall: a field of small white tiles, a horizontal band
 * in the station's colour with a darker trim line, black paint below platform
 * level where the wall faces the track, walk-in alcoves, and the black name
 * tablets with one letter per tile.
 *
 * <p>Everything is built on a grid of FOUR TILES PER BLOCK, which is what lets
 * a wall of any size be plain full blocks with no edge pieces: the textures
 * tile seamlessly and the tablet's letters land on that same grid.</p>
 *
 * <p>Registration lives here rather than in {@link MtrStationDecor} so the whole
 * feature is one file plus one call, and assets come from
 * {@code tools/gen_subway_wall.py} — never hand-edit them.</p>
 */
public final class SubwayWalls {
    private static AbstractBlock.Settings tile() {
        return AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.STONE);
    }

    /** The white field, dirty and freshly rebuilt. */
    public static final Block TILE_WALL = new Block(tile());
    public static final Block TILE_WALL_CLEAN = new Block(tile());

    /** The station-colour band. Tinted at render time, so the block itself is plain. */
    public static final Block TILE_BAND = new Block(tile());

    /** Painted black: the wall below platform level, facing the track. */
    public static final Block TILE_DARK = new Block(tile());

    /**
     * The walk-in niche, lined either with the wall's own white tile or with
     * black paint. Not a full cube, so it must not occlude its neighbours.
     */
    public static final AlcoveBlock TILE_ALCOVE = new AlcoveBlock(tile().nonOpaque());
    public static final AlcoveBlock TILE_ALCOVE_DARK = new AlcoveBlock(tile().nonOpaque());

    /**
     * The red-and-white no-clearance band. Stands 15 px so it reads as a
     * painted band set into the wall, but fills its block like any other.
     */
    public static final com.stationannouncer.block.StripeBlock NO_CLEARANCE_STRIPE =
            new com.stationannouncer.block.StripeBlock(tile().nonOpaque(), 15.0);

    /** A tile block carrying a name tablet (brush to edit). */
    public static final TileTabletBlock NAME_TABLET = new TileTabletBlock(tile());

    public static final BlockEntityType<TileTabletBlockEntity> TILE_TABLET_BLOCK_ENTITY =
            BlockEntityType.Builder.create(TileTabletBlockEntity::new, NAME_TABLET).build(null);

    /** C2S: the tablet editor saves (pos + text + which tile row it sits on). */
    public static final Identifier UPDATE_TILE_TABLET_C2S = StationAnnouncer.id("update_tile_tablet");

    private SubwayWalls() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_TILE_TABLET_C2S,
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    String text = buf.readString(TileTabletBlockEntity.MAX_TEXT_LENGTH);
                    int row = buf.readVarInt();
                    server.execute(() -> {
                        ServerWorld world = player.getServerWorld();
                        if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0
                                || !world.canPlayerModifyAt(player, pos)
                                || !(world.getBlockEntity(pos) instanceof TileTabletBlockEntity tablet)) {
                            return;
                        }
                        tablet.setText(text);
                        tablet.sync();
                        // The row is geometry, so it rides in the blockstate.
                        BlockState state = world.getBlockState(pos);
                        int clamped = Math.max(0, Math.min(TileTabletBlock.ROWS - 1, row));
                        if (state.getBlock() instanceof TileTabletBlock && state.get(TileTabletBlock.ROW) != clamped) {
                            world.setBlockState(pos, state.with(TileTabletBlock.ROW, clamped), Block.NOTIFY_ALL);
                        }
                    });
                });

        registerBlock("subway_tile_wall", TILE_WALL);
        registerBlock("subway_tile_wall_clean", TILE_WALL_CLEAN);
        registerBlock("subway_tile_band", TILE_BAND);
        registerBlock("subway_tile_dark", TILE_DARK);
        registerBlock("subway_tile_alcove", TILE_ALCOVE);
        registerBlock("subway_tile_alcove_dark", TILE_ALCOVE_DARK);
        registerBlock("no_clearance_stripe", NO_CLEARANCE_STRIPE);
        registerBlock("subway_name_tablet", NAME_TABLET);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("tile_tablet"), TILE_TABLET_BLOCK_ENTITY);
    }

    private static void registerBlock(String name, Block block) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(name), block);
        BlockItem item = new BlockItem(block, new Item.Settings());
        Registry.register(Registries.ITEM, StationAnnouncer.id(name), item);
        ModContent.DECORATION_ENTRIES.add(item);
    }
}
