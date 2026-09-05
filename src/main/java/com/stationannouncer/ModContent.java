package com.stationannouncer;

import com.stationannouncer.block.AmbienceBlock;
import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.block.AnnouncerBlock;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlock;
import com.stationannouncer.block.ConcreteFloorBlock;
import com.stationannouncer.block.ElRailingBlock;
import com.stationannouncer.block.ElRoofBlock;
import com.stationannouncer.block.ElRoofLightBlock;
import com.stationannouncer.block.ElWallBlock;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.BenchBlock;
import com.stationannouncer.block.DecorBlock;
import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.block.FareMachineBlock;
import com.stationannouncer.entity.SeatEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import com.stationannouncer.block.SpeakerBlock;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.item.SpeakerLinkItem;
import net.minecraft.block.Block;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/** Registers blocks, items, block entity types, the sound event and creative-tab entries. */
public final class ModContent {
    public static final AnnouncerBlock ANNOUNCER_BLOCK = new AnnouncerBlock(
            AbstractBlock.Settings.create()
                    .strength(2.0f, 6.0f)
                    .sounds(BlockSoundGroup.METAL));

    public static final ControlBoxBlock CONTROL_BOX_BLOCK = new ControlBoxBlock(
            AbstractBlock.Settings.create()
                    .strength(2.0f, 6.0f)
                    .sounds(BlockSoundGroup.METAL));

    public static final SpeakerBlock SPEAKER_BLOCK = new SpeakerBlock(
            AbstractBlock.Settings.create()
                    .strength(1.5f, 6.0f)
                    .sounds(BlockSoundGroup.METAL));

    // ------------------------------------------------------- decor (1.7)

    public static final DecorBlock GLOBE_LAMP_GREEN = new DecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL)
                    .nonOpaque().luminance(state -> 14),
            Block.createCuboidShape(3.5, 0.0, 3.5, 12.5, 16.0, 12.5));

    public static final DecorBlock GLOBE_LAMP_RED = new DecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL)
                    .nonOpaque().luminance(state -> 14),
            Block.createCuboidShape(3.5, 0.0, 3.5, 12.5, 16.0, 12.5));

    /** Stackable fluted cast-iron pole; put a globe on top at any height. */
    public static final DecorBlock GLOBE_LAMP_POLE = new DecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(5.0, 0.0, 5.0, 11.0, 16.0, 11.0));

    /** Subway entrance railing (1.5 blocks tall, fence-style connections). */
    public static final com.stationannouncer.block.RailingBlock ENTRANCE_RAILING =
            new com.stationannouncer.block.RailingBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());

    public static final BenchBlock BENCH = new BenchBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.WOOD).nonOpaque(),
            net.minecraft.util.shape.VoxelShapes.union(
                    Block.createCuboidShape(0.0, 0.0, 2.0, 16.0, 9.0, 13.0),   // seat + frame
                    Block.createCuboidShape(0.0, 9.0, 12.0, 16.0, 15.0, 14.0)).simplify()); // backrest

    /** Stainless mesh platform barrier; segments line up into a continuous run. */
    public static final FacingDecorBlock PLATFORM_BARRIER = new FacingDecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(0.0, 0.0, 5.5, 16.0, 16.0, 10.5));

    /** Conductor's zebra board, bolted flat to the wall behind it. */
    public static final com.stationannouncer.block.ZebraBoardBlock ZEBRA_BOARD_WALL =
            new com.stationannouncer.block.ZebraBoardBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    Block.createCuboidShape(0.0, 4.0, 10.0, 16.0, 14.0, 16.0), null);

    /** NYC subway staircases: two 8 px steps per block, yellow safety ends. */
    public static final com.stationannouncer.block.SubwayStairBlock SUBWAY_STAIRS =
            new com.stationannouncer.block.SubwayStairBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), true);
    public static final com.stationannouncer.block.SubwayStairBlock SUBWAY_STAIRS_OLD =
            new com.stationannouncer.block.SubwayStairBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.STONE).nonOpaque(), false);

    /** Black stringer beam dividing a wide staircase into lanes. */
    public static final com.stationannouncer.block.StairDividerBlock SUBWAY_STAIR_DIVIDER =
            new com.stationannouncer.block.StairDividerBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());

    /** Steel tube handrails: one block per mounting style, variants on the item. */
    public static final com.stationannouncer.block.HandrailBlock SUBWAY_HANDRAIL_WALL =
            new com.stationannouncer.block.HandrailBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    com.stationannouncer.block.HandrailBlock.Style.WALL);
    public static final com.stationannouncer.block.HandrailBlock SUBWAY_HANDRAIL_STANDING =
            new com.stationannouncer.block.HandrailBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    com.stationannouncer.block.HandrailBlock.Style.STANDING);
    public static final com.stationannouncer.block.HandrailBlock SUBWAY_HANDRAIL_DOUBLE =
            new com.stationannouncer.block.HandrailBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    com.stationannouncer.block.HandrailBlock.Style.DOUBLE);
    public static final com.stationannouncer.block.HandrailBlock SUBWAY_HANDRAIL_FLOATING =
            new com.stationannouncer.block.HandrailBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    com.stationannouncer.block.HandrailBlock.Style.FLOATING);

    /** Zebra board slung under the ceiling; the end blocks carry the drop pole. */
    public static final com.stationannouncer.block.ZebraBoardBlock ZEBRA_BOARD_HANGING =
            new com.stationannouncer.block.ZebraBoardBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
                    Block.createCuboidShape(0.0, 4.0, 5.0, 16.0, 14.0, 11.0),
                    Block.createCuboidShape(7.0, 13.0, 7.0, 9.0, 16.0, 9.0));

    // --------------------------------------------------- station floors (2.5)

    /**
     * Station floors. Plain full cubes; everything interesting is in the art.
     *
     * <p>Both pairs vary through vanilla's weighted random blockstate variants.
     * The concrete blocks additionally carry two joint properties, filled in
     * from the world position when they are placed - see
     * {@link ConcreteFloorBlock} for why that is not done at render time. The
     * three widths are separate blocks purely so the slab size can be chosen
     * per area; they share every texture and model.
     */
    public static AbstractBlock.Settings floorSettings() {
        return AbstractBlock.Settings.create().strength(1.6f, 6.0f).sounds(BlockSoundGroup.STONE);
    }

    public static final Block PLATFORM_TILE_FLOOR = new Block(floorSettings());
    public static final Block PLATFORM_TILE_FLOOR_CLEAN = new Block(floorSettings());

    /** Concrete slab floors, indexed alongside SLAB_WIDTHS - keep the orders in step. */
    public static final int[] SLAB_WIDTHS = {1, 2, 3, 4};
    public static final ConcreteFloorBlock[] PLATFORM_CONCRETE_FLOOR = {
            new ConcreteFloorBlock(floorSettings(), 1, false),
            new ConcreteFloorBlock(floorSettings(), 2, false),
            new ConcreteFloorBlock(floorSettings(), 3, false),
            new ConcreteFloorBlock(floorSettings(), 4, false)};
    public static final ConcreteFloorBlock[] PLATFORM_CONCRETE_FLOOR_CLEAN = {
            new ConcreteFloorBlock(floorSettings(), 1, true),
            new ConcreteFloorBlock(floorSettings(), 2, true),
            new ConcreteFloorBlock(floorSettings(), 3, true),
            new ConcreteFloorBlock(floorSettings(), 4, true)};

    /**
     * Trackside warning plates. The GATE variant sits on the block centre
     * plane, the same plane the dividing walls use, so it drops into a run of
     * them and reads as part of the fence rather than bolted onto it.
     */
    public static final FacingDecorBlock TRACK_WARNING_SIGN_WALL = new FacingDecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(0.0, 0.0, 13.0, 16.0, 16.0, 16.0));
    /** A real hinged gate: right-click swings the plate off its post. */
    public static final com.stationannouncer.block.TrackWarningGateBlock TRACK_WARNING_SIGN_GATE =
            new com.stationannouncer.block.TrackWarningGateBlock(
                    AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque());

    public static final BlockItem TRACK_WARNING_SIGN_WALL_ITEM =
            new BlockItem(TRACK_WARNING_SIGN_WALL, new Item.Settings());
    public static final BlockItem TRACK_WARNING_SIGN_GATE_ITEM =
            new BlockItem(TRACK_WARNING_SIGN_GATE, new Item.Settings());

    /** Fare-control dividing walls. Stack them; any pattern joins any other. */
    public static final com.stationannouncer.block.GateWallBlock GATE_SCROLL =
            new com.stationannouncer.block.GateWallBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), 1);
    /** Mesh runs unbroken for up to three blocks between uprights. */
    public static final com.stationannouncer.block.GateWallBlock GATE_GRILLE =
            new com.stationannouncer.block.GateWallBlock(
                    AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(), 3);

    public static final BlockItem GATE_SCROLL_ITEM = new BlockItem(GATE_SCROLL, new Item.Settings());
    public static final BlockItem GATE_GRILLE_ITEM = new BlockItem(GATE_GRILLE, new Item.Settings());

    public static final AmbienceBlock AMBIENCE_BLOCK = new AmbienceBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL));

    public static final FareMachineBlock FARE_MACHINE = new FareMachineBlock(
            AbstractBlock.Settings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(1.0, 0.0, 8.0, 15.0, 16.0, 16.0)); // lower half, flush to the wall behind

    public static final BlockItem ANNOUNCER_BLOCK_ITEM =
            new BlockItem(ANNOUNCER_BLOCK, new Item.Settings());
    public static final BlockItem CONTROL_BOX_ITEM =
            new BlockItem(CONTROL_BOX_BLOCK, new Item.Settings());
    public static final BlockItem SPEAKER_ITEM =
            new BlockItem(SPEAKER_BLOCK, new Item.Settings());
    public static final SpeakerLinkItem SPEAKER_LINK =
            new SpeakerLinkItem(new Item.Settings().maxCount(1));

    public static final BlockItem GLOBE_LAMP_GREEN_ITEM = new BlockItem(GLOBE_LAMP_GREEN, new Item.Settings());
    public static final BlockItem GLOBE_LAMP_RED_ITEM = new BlockItem(GLOBE_LAMP_RED, new Item.Settings());
    public static final BlockItem GLOBE_LAMP_POLE_ITEM = new BlockItem(GLOBE_LAMP_POLE, new Item.Settings());
    public static final BlockItem ENTRANCE_RAILING_ITEM = new BlockItem(ENTRANCE_RAILING, new Item.Settings());
    public static final BlockItem BENCH_ITEM = new BlockItem(BENCH, new Item.Settings());
    public static final BlockItem PLATFORM_BARRIER_ITEM = new BlockItem(PLATFORM_BARRIER, new Item.Settings());

    /** NYC elevated station kit v2 (tools/gen_el2_assets.py). */
    public static final ElRoofBlock EL_ROOF = new ElRoofBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_ROOF_ITEM = new BlockItem(EL_ROOF, new Item.Settings());
    public static final ElRailingBlock EL_RAILING = new ElRailingBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_RAILING_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_RAILING, new Item.Settings());
    public static final ElWallBlock EL_WALL = new ElWallBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final ElWallBlock EL_WALL_GLASS = new ElWallBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.GLASS).nonOpaque());
    public static final ElWallBlock EL_WALL_CREAM = new ElWallBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final ElWallBlock EL_WALL_GREEN = new ElWallBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final ElWallBlock EL_WALL_DOORWAY = new com.stationannouncer.block.ElWallDoorwayBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_WALL_DOORWAY_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_WALL_DOORWAY, new Item.Settings());
    public static final BlockItem EL_WALL_CREAM_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_WALL_CREAM, new Item.Settings());
    public static final BlockItem EL_WALL_GREEN_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_WALL_GREEN, new Item.Settings());
    public static final BlockItem EL_WALL_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_WALL, new Item.Settings());
    public static final BlockItem EL_WALL_GLASS_ITEM = new com.stationannouncer.item.EdgeRunItem(EL_WALL_GLASS, new Item.Settings());
    public static final FacingDecorBlock EL_LAMP_HEAD = new FacingDecorBlock(
            AbstractBlock.Settings.create().strength(1.0f).sounds(BlockSoundGroup.METAL).nonOpaque().luminance(state -> 14),
            Block.createCuboidShape(2.0, 0.0, 0.0, 14.0, 13.0, 8.0));
    public static final BlockItem EL_LAMP_HEAD_ITEM = new BlockItem(EL_LAMP_HEAD, new Item.Settings());
    public static final ElRoofLightBlock EL_ROOF_LIGHT = new ElRoofLightBlock(
            AbstractBlock.Settings.create().strength(1.0f).sounds(BlockSoundGroup.METAL).nonOpaque().luminance(state -> 15),
            Block.createCuboidShape(6.0, 11.0, 0.0, 10.0, 16.0, 16.0));
    public static final BlockItem EL_ROOF_LIGHT_ITEM = new BlockItem(EL_ROOF_LIGHT, new Item.Settings());
    /** El stair family (tools/gen_el2_stairs.py): sloped side courses beside subway_stairs + sloped roof. */
    public static final com.stationannouncer.block.ElStairSideBlock EL_STAIR_RAILING = new com.stationannouncer.block.ElStairSideBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final com.stationannouncer.block.ElStairSideBlock EL_STAIR_WALL = new com.stationannouncer.block.ElStairSideBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final com.stationannouncer.block.ElStairSideBlock EL_STAIR_WALL_GLASS = new com.stationannouncer.block.ElStairSideBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.GLASS).nonOpaque());
    public static final com.stationannouncer.block.ElStairRoofBlock EL_STAIR_ROOF = new com.stationannouncer.block.ElStairRoofBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final com.stationannouncer.block.ElStairSideBlock EL_STAIR_OPEN = new com.stationannouncer.block.ElStairSideBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_STAIR_OPEN_ITEM = new com.stationannouncer.item.ElStairSideItem(EL_STAIR_OPEN, new Item.Settings());
    public static final BlockItem EL_STAIR_RAILING_ITEM = new com.stationannouncer.item.ElStairSideItem(EL_STAIR_RAILING, new Item.Settings());
    public static final BlockItem EL_STAIR_WALL_ITEM = new com.stationannouncer.item.ElStairSideItem(EL_STAIR_WALL, new Item.Settings());
    public static final BlockItem EL_STAIR_WALL_GLASS_ITEM = new com.stationannouncer.item.ElStairSideItem(EL_STAIR_WALL_GLASS, new Item.Settings());
    public static final BlockItem EL_STAIR_ROOF_ITEM = new BlockItem(EL_STAIR_ROOF, new Item.Settings());
    public static final com.stationannouncer.block.ElLandingRoofBlock EL_LANDING_ROOF = new com.stationannouncer.block.ElLandingRoofBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_LANDING_ROOF_ITEM = new BlockItem(EL_LANDING_ROOF, new Item.Settings());
    /** Thin steel landing deck: walkable top slab, clearance below (tools/gen_el2_stairs.py). */
    public static final com.stationannouncer.block.DecorBlock EL_STAIR_LANDING = new com.stationannouncer.block.DecorBlock(
            AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(0.0, 11.0, 0.0, 16.0, 16.0, 16.0));
    public static final BlockItem EL_STAIR_LANDING_ITEM = new BlockItem(EL_STAIR_LANDING, new Item.Settings());
    /** El kit v2 STRUCTURE (tools/gen_el2_structure.py): street columns, plate girder with knee braces, decks. */
    public static final com.stationannouncer.mtr.ColumnBlock EL_STREET_COLUMN = new com.stationannouncer.mtr.ColumnBlock(
            AbstractBlock.Settings.create().strength(3.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(3.0, 0.0, 3.0, 13.0, 16.0, 13.0));
    public static final com.stationannouncer.mtr.ColumnBlock EL_STREET_COLUMN_LATTICE = new com.stationannouncer.mtr.ColumnBlock(
            AbstractBlock.Settings.create().strength(3.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(3.0, 0.0, 3.0, 13.0, 16.0, 13.0));
    public static final com.stationannouncer.block.ElGirderBlock EL_GIRDER_PLATE = new com.stationannouncer.block.ElGirderBlock(
            AbstractBlock.Settings.create().strength(3.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final com.stationannouncer.block.ElDeckBlock EL_TRACK_DECK = new com.stationannouncer.block.ElDeckBlock(
            AbstractBlock.Settings.create().strength(3.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final com.stationannouncer.block.ElDeckBlock EL_PLATE_DECK = new com.stationannouncer.block.ElDeckBlock(
            AbstractBlock.Settings.create().strength(3.0f, 6.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem EL_STREET_COLUMN_ITEM = new BlockItem(EL_STREET_COLUMN, new Item.Settings());
    public static final BlockItem EL_STREET_COLUMN_LATTICE_ITEM = new BlockItem(EL_STREET_COLUMN_LATTICE, new Item.Settings());
    public static final BlockItem EL_GIRDER_PLATE_ITEM = new BlockItem(EL_GIRDER_PLATE, new Item.Settings());
    public static final BlockItem EL_TRACK_DECK_ITEM = new BlockItem(EL_TRACK_DECK, new Item.Settings());
    public static final BlockItem EL_PLATE_DECK_ITEM = new BlockItem(EL_PLATE_DECK, new Item.Settings());
    public static final BlockItem SUBWAY_STAIRS_ITEM =
            new com.stationannouncer.item.SubwayStairItem(SUBWAY_STAIRS, new Item.Settings());
    public static final BlockItem SUBWAY_STAIRS_OLD_ITEM = new BlockItem(SUBWAY_STAIRS_OLD, new Item.Settings());
    public static final BlockItem SUBWAY_STAIR_DIVIDER_ITEM = new BlockItem(SUBWAY_STAIR_DIVIDER, new Item.Settings());
    public static final BlockItem SUBWAY_HANDRAIL_WALL_ITEM =
            new com.stationannouncer.item.HandrailItem(SUBWAY_HANDRAIL_WALL, new Item.Settings());
    public static final BlockItem SUBWAY_HANDRAIL_STANDING_ITEM =
            new com.stationannouncer.item.HandrailItem(SUBWAY_HANDRAIL_STANDING, new Item.Settings());
    public static final BlockItem SUBWAY_HANDRAIL_DOUBLE_ITEM =
            new com.stationannouncer.item.HandrailItem(SUBWAY_HANDRAIL_DOUBLE, new Item.Settings());
    public static final BlockItem SUBWAY_HANDRAIL_FLOATING_ITEM =
            new com.stationannouncer.item.HandrailItem(SUBWAY_HANDRAIL_FLOATING, new Item.Settings());
    public static final BlockItem ZEBRA_BOARD_WALL_ITEM = new BlockItem(ZEBRA_BOARD_WALL, new Item.Settings());
    public static final BlockItem ZEBRA_BOARD_HANGING_ITEM = new BlockItem(ZEBRA_BOARD_HANGING, new Item.Settings());
    public static final BlockItem PLATFORM_TILE_FLOOR_ITEM =
            new BlockItem(PLATFORM_TILE_FLOOR, new Item.Settings());
    public static final BlockItem PLATFORM_TILE_FLOOR_CLEAN_ITEM =
            new BlockItem(PLATFORM_TILE_FLOOR_CLEAN, new Item.Settings());
    public static final BlockItem[] PLATFORM_CONCRETE_FLOOR_ITEM = {
            new BlockItem(PLATFORM_CONCRETE_FLOOR[0], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR[1], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR[2], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR[3], new Item.Settings())};
    public static final BlockItem[] PLATFORM_CONCRETE_FLOOR_CLEAN_ITEM = {
            new BlockItem(PLATFORM_CONCRETE_FLOOR_CLEAN[0], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR_CLEAN[1], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR_CLEAN[2], new Item.Settings()),
            new BlockItem(PLATFORM_CONCRETE_FLOOR_CLEAN[3], new Item.Settings())};

    public static final BlockItem FARE_MACHINE_ITEM = new BlockItem(FARE_MACHINE, new Item.Settings());
    public static final BlockItem AMBIENCE_BLOCK_ITEM = new BlockItem(AMBIENCE_BLOCK, new Item.Settings());

    public static final BlockEntityType<AnnouncerBlockEntity> ANNOUNCER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(AnnouncerBlockEntity::new, ANNOUNCER_BLOCK).build(null);
    public static final BlockEntityType<ControlBoxBlockEntity> CONTROL_BOX_BLOCK_ENTITY =
            BlockEntityType.Builder.create(ControlBoxBlockEntity::new, CONTROL_BOX_BLOCK).build(null);
    public static final BlockEntityType<SpeakerBlockEntity> SPEAKER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(SpeakerBlockEntity::new, SPEAKER_BLOCK).build(null);
    public static final BlockEntityType<AmbienceBlockEntity> AMBIENCE_BLOCK_ENTITY =
            BlockEntityType.Builder.create(AmbienceBlockEntity::new, AMBIENCE_BLOCK).build(null);

    /** Invisible rideable marker for bench sitting. */
    public static final EntityType<SeatEntity> SEAT_ENTITY =
            EntityType.Builder.<SeatEntity>create(SeatEntity::new, SpawnGroup.MISC)
                    .setDimensions(0.2f, 0.2f).maxTrackingRange(10).build("seat");

    public static final SoundEvent CHIME = SoundEvent.of(StationAnnouncer.id("chime"));
    public static final SoundEvent AMBIENCE_HUM = SoundEvent.of(StationAnnouncer.id("ambience_hum"));
    public static final SoundEvent AMBIENCE_VENT = SoundEvent.of(StationAnnouncer.id("ambience_vent"));
    /** The emergency exit gate alarm: a 2 s loop, replayed for the alarm's life. */
    public static final SoundEvent GATE_ALARM = SoundEvent.of(StationAnnouncer.id("gate_alarm"));

    /**
     * One selectable chime: sound event id ("" = the built-in ding-dong),
     * display name for the GUI dropdown, and how many ticks the announcement
     * voice waits so the chime finishes before speech starts.
     */
    public record ChimeOption(String id, String label, int leadTicks) {
    }

    /** Built-in chimes offered by the GUI dropdown (from the chimes folder). */
    public static final List<ChimeOption> CHIME_OPTIONS = List.of(
            new ChimeOption("", "Ding-Dong (Default)", 22),
            new ChimeOption("station_announcer:chime_marimba1", "Marimba 1", 46),
            new ChimeOption("station_announcer:chime_marimba2", "Marimba 2", 46),
            new ChimeOption("station_announcer:chime_marimba3", "Marimba 3", 48),
            new ChimeOption("station_announcer:chime_marimba4", "Marimba 4", 48),
            new ChimeOption("station_announcer:chime_marimba5", "Marimba 5", 48),
            new ChimeOption("station_announcer:chime_marimba6", "Marimba 6", 48),
            new ChimeOption("station_announcer:chime_synth1", "Synth 1", 48),
            new ChimeOption("station_announcer:chime_synth2", "Synth 2", 48),
            new ChimeOption("station_announcer:chime_synth3", "Synth 3", 48),
            new ChimeOption("station_announcer:chime_synth4", "Synth 4", 48),
            new ChimeOption("station_announcer:chime_synth5", "Synth 5", 97));

    /** Ticks the voice waits after starting the given chime id (default chime for unknown ids). */
    public static int chimeLeadTicks(String chimeSound) {
        String id = chimeSound == null ? "" : chimeSound.trim();
        for (ChimeOption option : CHIME_OPTIONS) {
            if (option.id().equals(id)) {
                return option.leadTicks();
            }
        }
        return CHIME_OPTIONS.get(0).leadTicks();
    }

    /**
     * The two creative tabs, split by what a builder is doing at the time.
     *
     * <p>DECORATION is the scenery a station is built out of - floors, columns,
     * benches, railings, lamps, signage, fare gates - plus the creator tools
     * that mass-place that scenery. OPERATIONS is the equipment that runs:
     * the PA network, every PIDS display, the holding lights and the ambience
     * block. A block belongs in OPERATIONS if it does something at runtime that
     * a player configures or that reacts to trains; the fare machine and
     * turnstile stay in DECORATION because they are furniture you walk through,
     * which is how they get built.
     *
     * <p>Mounting poles live with what they hold up, not with each other.
     *
     * <p>The optional MTR modules append to these lists when they register.
     * Both are read lazily by the {@code entries} callback, so registration
     * order does not matter.
     */
    public static final List<ItemConvertible> DECORATION_ENTRIES = new ArrayList<>();
    public static final List<ItemConvertible> OPERATIONS_ENTRIES = new ArrayList<>();

    public static final ItemGroup DECORATION = FabricItemGroup.builder()
            .icon(() -> new ItemStack(BENCH_ITEM))
            .displayName(Text.translatable("itemGroup.station_announcer.decoration"))
            .entries((context, entries) -> DECORATION_ENTRIES.forEach(entries::add))
            .build();

    public static final ItemGroup OPERATIONS = FabricItemGroup.builder()
            .icon(() -> new ItemStack(CONTROL_BOX_ITEM))
            .displayName(Text.translatable("itemGroup.station_announcer.operations"))
            .entries((context, entries) -> OPERATIONS_ENTRIES.forEach(entries::add))
            .build();

    private ModContent() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, StationAnnouncer.id("announcer_block"), ANNOUNCER_BLOCK);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("pa_control_box"), CONTROL_BOX_BLOCK);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("speaker"), SPEAKER_BLOCK);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("globe_lamp_green"), GLOBE_LAMP_GREEN);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("globe_lamp_red"), GLOBE_LAMP_RED);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("globe_lamp_pole"), GLOBE_LAMP_POLE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("entrance_railing"), ENTRANCE_RAILING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("bench"), BENCH);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("platform_barrier"), PLATFORM_BARRIER);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_roof"), EL_ROOF);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_railing"), EL_RAILING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_wall"), EL_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_wall_glass"), EL_WALL_GLASS);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_wall_cream"), EL_WALL_CREAM);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_wall_green"), EL_WALL_GREEN);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_wall_doorway"), EL_WALL_DOORWAY);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_platform_lamp_head"), EL_LAMP_HEAD);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_roof_light"), EL_ROOF_LIGHT);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_railing"), EL_STAIR_RAILING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_wall"), EL_STAIR_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_wall_glass"), EL_STAIR_WALL_GLASS);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_roof"), EL_STAIR_ROOF);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_open"), EL_STAIR_OPEN);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_landing_roof"), EL_LANDING_ROOF);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_stair_landing"), EL_STAIR_LANDING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_street_column"), EL_STREET_COLUMN);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_street_column_lattice"), EL_STREET_COLUMN_LATTICE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_girder_plate"), EL_GIRDER_PLATE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_track_deck"), EL_TRACK_DECK);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("el_plate_deck"), EL_PLATE_DECK);
        com.stationannouncer.block.ElGirderBlock.COLUMNS.add(EL_STREET_COLUMN);
        com.stationannouncer.block.ElGirderBlock.COLUMNS.add(EL_STREET_COLUMN_LATTICE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_stairs"), SUBWAY_STAIRS);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_stairs_old"), SUBWAY_STAIRS_OLD);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_stair_divider"), SUBWAY_STAIR_DIVIDER);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_handrail_wall"), SUBWAY_HANDRAIL_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_handrail_standing"), SUBWAY_HANDRAIL_STANDING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_handrail_double"), SUBWAY_HANDRAIL_DOUBLE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("subway_handrail_floating"), SUBWAY_HANDRAIL_FLOATING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("zebra_board_wall"), ZEBRA_BOARD_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("zebra_board_hanging"), ZEBRA_BOARD_HANGING);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("platform_tile_floor"), PLATFORM_TILE_FLOOR);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("platform_tile_floor_clean"), PLATFORM_TILE_FLOOR_CLEAN);
        for (int i = 0; i < SLAB_WIDTHS.length; i++) {
            Registry.register(Registries.BLOCK,
                    StationAnnouncer.id("platform_concrete_floor_" + SLAB_WIDTHS[i]),
                    PLATFORM_CONCRETE_FLOOR[i]);
            Registry.register(Registries.BLOCK,
                    StationAnnouncer.id("platform_concrete_floor_" + SLAB_WIDTHS[i] + "_clean"),
                    PLATFORM_CONCRETE_FLOOR_CLEAN[i]);
        }
        Registry.register(Registries.BLOCK, StationAnnouncer.id("fare_machine"), FARE_MACHINE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("track_warning_sign_wall"), TRACK_WARNING_SIGN_WALL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("track_warning_sign_gate"), TRACK_WARNING_SIGN_GATE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("gate_scroll"), GATE_SCROLL);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("gate_grille"), GATE_GRILLE);
        Registry.register(Registries.BLOCK, StationAnnouncer.id("ambience_block"), AMBIENCE_BLOCK);

        Registry.register(Registries.ITEM, StationAnnouncer.id("announcer_block"), ANNOUNCER_BLOCK_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("pa_control_box"), CONTROL_BOX_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("speaker"), SPEAKER_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("speaker_link"), SPEAKER_LINK);
        Registry.register(Registries.ITEM, StationAnnouncer.id("globe_lamp_green"), GLOBE_LAMP_GREEN_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("globe_lamp_red"), GLOBE_LAMP_RED_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("globe_lamp_pole"), GLOBE_LAMP_POLE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("entrance_railing"), ENTRANCE_RAILING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("bench"), BENCH_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("platform_barrier"), PLATFORM_BARRIER_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_roof"), EL_ROOF_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_railing"), EL_RAILING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_wall"), EL_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_wall_glass"), EL_WALL_GLASS_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_wall_cream"), EL_WALL_CREAM_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_wall_green"), EL_WALL_GREEN_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_wall_doorway"), EL_WALL_DOORWAY_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_platform_lamp_head"), EL_LAMP_HEAD_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_roof_light"), EL_ROOF_LIGHT_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_railing"), EL_STAIR_RAILING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_wall"), EL_STAIR_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_wall_glass"), EL_STAIR_WALL_GLASS_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_roof"), EL_STAIR_ROOF_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_open"), EL_STAIR_OPEN_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_landing_roof"), EL_LANDING_ROOF_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_stair_landing"), EL_STAIR_LANDING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_street_column"), EL_STREET_COLUMN_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_street_column_lattice"), EL_STREET_COLUMN_LATTICE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_girder_plate"), EL_GIRDER_PLATE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_track_deck"), EL_TRACK_DECK_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("el_plate_deck"), EL_PLATE_DECK_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_stairs"), SUBWAY_STAIRS_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_stairs_old"), SUBWAY_STAIRS_OLD_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_stair_divider"), SUBWAY_STAIR_DIVIDER_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_handrail_wall"), SUBWAY_HANDRAIL_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_handrail_standing"), SUBWAY_HANDRAIL_STANDING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_handrail_double"), SUBWAY_HANDRAIL_DOUBLE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("subway_handrail_floating"), SUBWAY_HANDRAIL_FLOATING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("zebra_board_wall"), ZEBRA_BOARD_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("zebra_board_hanging"), ZEBRA_BOARD_HANGING_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("platform_tile_floor"), PLATFORM_TILE_FLOOR_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("platform_tile_floor_clean"), PLATFORM_TILE_FLOOR_CLEAN_ITEM);
        for (int i = 0; i < SLAB_WIDTHS.length; i++) {
            Registry.register(Registries.ITEM,
                    StationAnnouncer.id("platform_concrete_floor_" + SLAB_WIDTHS[i]),
                    PLATFORM_CONCRETE_FLOOR_ITEM[i]);
            Registry.register(Registries.ITEM,
                    StationAnnouncer.id("platform_concrete_floor_" + SLAB_WIDTHS[i] + "_clean"),
                    PLATFORM_CONCRETE_FLOOR_CLEAN_ITEM[i]);
        }
        Registry.register(Registries.ITEM, StationAnnouncer.id("fare_machine"), FARE_MACHINE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("track_warning_sign_wall"), TRACK_WARNING_SIGN_WALL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("track_warning_sign_gate"), TRACK_WARNING_SIGN_GATE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("gate_scroll"), GATE_SCROLL_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("gate_grille"), GATE_GRILLE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("ambience_block"), AMBIENCE_BLOCK_ITEM);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("announcer_block"), ANNOUNCER_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("pa_control_box"), CONTROL_BOX_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("speaker"), SPEAKER_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("ambience_block"), AMBIENCE_BLOCK_ENTITY);
        Registry.register(Registries.ENTITY_TYPE, StationAnnouncer.id("seat"), SEAT_ENTITY);

        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("chime"), CHIME);
        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("ambience_hum"), AMBIENCE_HUM);
        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("ambience_vent"), AMBIENCE_VENT);
        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("gate_alarm"), GATE_ALARM);
        for (ChimeOption option : CHIME_OPTIONS) {
            if (!option.id().isEmpty()) {
                Identifier id = new Identifier(option.id());
                Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
            }
        }

        Registry.register(Registries.ITEM_GROUP, StationAnnouncer.id("decoration"), DECORATION);
        Registry.register(Registries.ITEM_GROUP, StationAnnouncer.id("operations"), OPERATIONS);

        // --- decoration: floors first, then what stands on them
        DECORATION_ENTRIES.add(PLATFORM_TILE_FLOOR_ITEM);
        DECORATION_ENTRIES.add(PLATFORM_TILE_FLOOR_CLEAN_ITEM);
        for (int i = 0; i < SLAB_WIDTHS.length; i++) {
            DECORATION_ENTRIES.add(PLATFORM_CONCRETE_FLOOR_ITEM[i]);
        }
        for (int i = 0; i < SLAB_WIDTHS.length; i++) {
            DECORATION_ENTRIES.add(PLATFORM_CONCRETE_FLOOR_CLEAN_ITEM[i]);
        }
        DECORATION_ENTRIES.add(BENCH_ITEM);
        DECORATION_ENTRIES.add(PLATFORM_BARRIER_ITEM);
        DECORATION_ENTRIES.add(EL_ROOF_ITEM);
        DECORATION_ENTRIES.add(EL_RAILING_ITEM);
        DECORATION_ENTRIES.add(EL_WALL_ITEM);
        DECORATION_ENTRIES.add(EL_WALL_GLASS_ITEM);
        DECORATION_ENTRIES.add(EL_WALL_CREAM_ITEM);
        DECORATION_ENTRIES.add(EL_WALL_GREEN_ITEM);
        DECORATION_ENTRIES.add(EL_WALL_DOORWAY_ITEM);
        DECORATION_ENTRIES.add(EL_LAMP_HEAD_ITEM);
        DECORATION_ENTRIES.add(EL_ROOF_LIGHT_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_RAILING_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_WALL_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_WALL_GLASS_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_OPEN_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_ROOF_ITEM);
        DECORATION_ENTRIES.add(EL_LANDING_ROOF_ITEM);
        DECORATION_ENTRIES.add(EL_STAIR_LANDING_ITEM);
        DECORATION_ENTRIES.add(EL_STREET_COLUMN_ITEM);
        DECORATION_ENTRIES.add(EL_STREET_COLUMN_LATTICE_ITEM);
        DECORATION_ENTRIES.add(EL_GIRDER_PLATE_ITEM);
        DECORATION_ENTRIES.add(EL_TRACK_DECK_ITEM);
        DECORATION_ENTRIES.add(EL_PLATE_DECK_ITEM);
        DECORATION_ENTRIES.add(ZEBRA_BOARD_WALL_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_STAIRS_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_STAIRS_OLD_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_STAIR_DIVIDER_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_HANDRAIL_WALL_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_HANDRAIL_STANDING_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_HANDRAIL_DOUBLE_ITEM);
        DECORATION_ENTRIES.add(SUBWAY_HANDRAIL_FLOATING_ITEM);
        DECORATION_ENTRIES.add(ZEBRA_BOARD_HANGING_ITEM);
        DECORATION_ENTRIES.add(FARE_MACHINE_ITEM);
        DECORATION_ENTRIES.add(TRACK_WARNING_SIGN_WALL_ITEM);
        DECORATION_ENTRIES.add(TRACK_WARNING_SIGN_GATE_ITEM);
        DECORATION_ENTRIES.add(GATE_SCROLL_ITEM);
        DECORATION_ENTRIES.add(GATE_GRILLE_ITEM);
        DECORATION_ENTRIES.add(ENTRANCE_RAILING_ITEM);
        DECORATION_ENTRIES.add(GLOBE_LAMP_GREEN_ITEM);
        DECORATION_ENTRIES.add(GLOBE_LAMP_RED_ITEM);
        DECORATION_ENTRIES.add(GLOBE_LAMP_POLE_ITEM);

        // --- operations: the PA network, then the ambience emitter
        OPERATIONS_ENTRIES.add(ANNOUNCER_BLOCK_ITEM);
        OPERATIONS_ENTRIES.add(CONTROL_BOX_ITEM);
        OPERATIONS_ENTRIES.add(SPEAKER_ITEM);
        OPERATIONS_ENTRIES.add(SPEAKER_LINK);
        OPERATIONS_ENTRIES.add(AMBIENCE_BLOCK_ITEM);
    }
}
