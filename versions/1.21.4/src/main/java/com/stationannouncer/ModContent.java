package com.stationannouncer;

import com.stationannouncer.block.AmbienceBlock;
import com.stationannouncer.block.AmbienceBlockEntity;
import com.stationannouncer.block.AnnouncerBlock;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlock;
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
                    Block.createCuboidShape(0.0, 9.0, 12.0, 16.0, 15.0, 14.0))); // backrest

    /** Stainless mesh platform barrier; segments line up into a continuous run. */
    public static final FacingDecorBlock PLATFORM_BARRIER = new FacingDecorBlock(
            AbstractBlock.Settings.create().strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque(),
            Block.createCuboidShape(0.0, 0.0, 5.5, 16.0, 16.0, 10.5));

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
     * Everything shown in the Baker City creative tab, in order. The optional
     * MTR module appends its PIDS items here before the tab is first built.
     */
    public static final List<ItemConvertible> BAKER_CITY_ENTRIES = new ArrayList<>();

    public static final ItemGroup BAKER_CITY = FabricItemGroup.builder()
            .icon(() -> new ItemStack(CONTROL_BOX_ITEM))
            .displayName(Text.translatable("itemGroup.station_announcer.baker_city"))
            .entries((context, entries) -> BAKER_CITY_ENTRIES.forEach(entries::add))
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
        Registry.register(Registries.BLOCK, StationAnnouncer.id("fare_machine"), FARE_MACHINE);
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
        Registry.register(Registries.ITEM, StationAnnouncer.id("fare_machine"), FARE_MACHINE_ITEM);
        Registry.register(Registries.ITEM, StationAnnouncer.id("ambience_block"), AMBIENCE_BLOCK_ITEM);

        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("announcer_block"), ANNOUNCER_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("pa_control_box"), CONTROL_BOX_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("speaker"), SPEAKER_BLOCK_ENTITY);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("ambience_block"), AMBIENCE_BLOCK_ENTITY);
        Registry.register(Registries.ENTITY_TYPE, StationAnnouncer.id("seat"), SEAT_ENTITY);

        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("chime"), CHIME);
        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("ambience_hum"), AMBIENCE_HUM);
        Registry.register(Registries.SOUND_EVENT, StationAnnouncer.id("ambience_vent"), AMBIENCE_VENT);
        for (ChimeOption option : CHIME_OPTIONS) {
            if (!option.id().isEmpty()) {
                Identifier id = new Identifier(option.id());
                Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
            }
        }

        Registry.register(Registries.ITEM_GROUP, StationAnnouncer.id("baker_city"), BAKER_CITY);
        BAKER_CITY_ENTRIES.add(ANNOUNCER_BLOCK_ITEM);
        BAKER_CITY_ENTRIES.add(CONTROL_BOX_ITEM);
        BAKER_CITY_ENTRIES.add(SPEAKER_ITEM);
        BAKER_CITY_ENTRIES.add(SPEAKER_LINK);
        BAKER_CITY_ENTRIES.add(GLOBE_LAMP_GREEN_ITEM);
        BAKER_CITY_ENTRIES.add(GLOBE_LAMP_RED_ITEM);
        BAKER_CITY_ENTRIES.add(GLOBE_LAMP_POLE_ITEM);
        BAKER_CITY_ENTRIES.add(ENTRANCE_RAILING_ITEM);
        BAKER_CITY_ENTRIES.add(BENCH_ITEM);
        BAKER_CITY_ENTRIES.add(PLATFORM_BARRIER_ITEM);
        BAKER_CITY_ENTRIES.add(FARE_MACHINE_ITEM);
        BAKER_CITY_ENTRIES.add(AMBIENCE_BLOCK_ITEM);
    }
}
