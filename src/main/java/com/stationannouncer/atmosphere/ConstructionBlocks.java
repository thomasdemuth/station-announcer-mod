package com.stationannouncer.atmosphere;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;

/**
 * The construction / maintenance props of the lived-in station pass
 * (ATMOSPHERE_PLAN.md §1E): cones, floor signs, barricades and barriers,
 * cordon tape, the blue MTA hoarding, plywood boards, tube scaffold, the
 * NYC sidewalk shed and work lights. Assets: tools/gen_construction_assets.py.
 */
public final class ConstructionBlocks {
    private ConstructionBlocks() {
    }

    public static TrafficConeBlock TRAFFIC_CONE;
    public static WetFloorSignBlock WET_FLOOR_SIGN;
    public static ConstructionBarrierBlock BARRICADE;
    public static ConstructionBarrierBlock POLICE_BARRICADE;
    public static ConstructionBarrierBlock PLASTIC_BARRIER;
    public static CautionTapeBlock CAUTION_TAPE;
    public static ConstructionWallBlock CONSTRUCTION_WALL;
    public static PlywoodBoardBlock PLYWOOD_BOARD;
    public static ScaffoldFrameBlock SCAFFOLD;
    public static SidewalkShedBlock SIDEWALK_SHED;
    public static SidewalkShedPostBlock SIDEWALK_SHED_POST;
    public static ShedLightBlock SIDEWALK_SHED_LIGHT;
    public static WorkLightBlock WORK_LIGHT;
    public static CagedWorkLampBlock CAGED_WORK_LAMP;

    private static AbstractBlock.Settings props(MapColor color, float strength, BlockSoundGroup sound) {
        return AbstractBlock.Settings.create().mapColor(color).strength(strength).sounds(sound).nonOpaque();
    }

    public static void register() {
        TRAFFIC_CONE = AtmosphereRegistry.block("traffic_cone", new TrafficConeBlock(
                props(MapColor.ORANGE, 0.3f, BlockSoundGroup.BAMBOO).pistonBehavior(PistonBehavior.DESTROY)));
        WET_FLOOR_SIGN = AtmosphereRegistry.block("wet_floor_sign", new WetFloorSignBlock(
                props(MapColor.YELLOW, 0.3f, BlockSoundGroup.BAMBOO).pistonBehavior(PistonBehavior.DESTROY)));

        BARRICADE = AtmosphereRegistry.block("barricade", new ConstructionBarrierBlock(
                props(MapColor.ORANGE, 1.0f, BlockSoundGroup.WOOD),
                ConstructionProps.union(ConstructionProps.px(0, 0, 6.5, 16, 24, 9.5),
                        ConstructionProps.px(0, 0, 2, 16, 1.5, 14)),
                ConstructionProps.px(0, 0, 7, 16, 24, 9)));
        POLICE_BARRICADE = AtmosphereRegistry.block("police_barricade", new ConstructionBarrierBlock(
                props(MapColor.BLUE, 1.0f, BlockSoundGroup.WOOD),
                ConstructionProps.union(ConstructionProps.px(0, 10, 7, 16, 15.5, 9),
                        ConstructionProps.px(0, 0, 3.5, 16, 10, 12.5)),
                ConstructionProps.px(0, 0, 7, 16, 24, 9)));
        PLASTIC_BARRIER = AtmosphereRegistry.block("plastic_barrier", new ConstructionBarrierBlock(
                props(MapColor.ORANGE, 1.0f, BlockSoundGroup.BAMBOO),
                ConstructionProps.px(0, 0, 4, 16, 14.5, 12),
                ConstructionProps.px(0, 0, 4, 16, 14.5, 12)));

        CAUTION_TAPE = AtmosphereRegistry.block("caution_tape", new CautionTapeBlock(
                props(MapColor.YELLOW, 0.2f, BlockSoundGroup.WOOL).pistonBehavior(PistonBehavior.DESTROY)));

        CONSTRUCTION_WALL = AtmosphereRegistry.block("construction_wall", new ConstructionWallBlock(
                props(MapColor.BLUE, 1.5f, BlockSoundGroup.WOOD)));
        PLYWOOD_BOARD = AtmosphereRegistry.block("plywood_board", new PlywoodBoardBlock(
                props(MapColor.OAK_TAN, 1.0f, BlockSoundGroup.WOOD)));

        SCAFFOLD = AtmosphereRegistry.block("scaffold", new ScaffoldFrameBlock(
                props(MapColor.IRON_GRAY, 2.0f, BlockSoundGroup.METAL)));

        SIDEWALK_SHED = AtmosphereRegistry.block("sidewalk_shed", new SidewalkShedBlock(
                props(MapColor.DARK_GREEN, 2.0f, BlockSoundGroup.WOOD)));
        SIDEWALK_SHED_POST = AtmosphereRegistry.block("sidewalk_shed_post", new SidewalkShedPostBlock(
                props(MapColor.DARK_GREEN, 3.0f, BlockSoundGroup.METAL)));
        SIDEWALK_SHED_LIGHT = AtmosphereRegistry.block("sidewalk_shed_light", new ShedLightBlock(
                props(MapColor.DARK_GREEN, 0.5f, BlockSoundGroup.LANTERN).noCollision().luminance(state -> 13)));

        WORK_LIGHT = AtmosphereRegistry.block("work_light", new WorkLightBlock(
                props(MapColor.YELLOW, 1.0f, BlockSoundGroup.METAL).luminance(state -> 15)));
        CAGED_WORK_LAMP = AtmosphereRegistry.block("caged_work_lamp", new CagedWorkLampBlock(
                props(MapColor.ORANGE, 0.5f, BlockSoundGroup.LANTERN).noCollision().luminance(state -> 12)));
    }
}
