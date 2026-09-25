package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;

/**
 * A wall course that reads as ONE panel when stacked: the ESI windscreen's
 * full-height glass (a black kick curb at the foot, a cap at the top, clear
 * glass between) and the welded-mesh fence. {@link #COURSE} says where in
 * its stack of the same block this cell is, and the blockstate draws the
 * curb only on the bottom course, the cap only on the top one. Everything
 * else is the wall family (runs, corners, doorways join it).
 */
public class ElStackWallBlock extends ElWallBlock {
    public enum Course implements StringIdentifiable {
        SINGLE("single"), BOTTOM("bottom"), MIDDLE("middle"), TOP("top");

        private final String name;

        Course(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final EnumProperty<Course> COURSE = EnumProperty.of("course", Course.class);

    public ElStackWallBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(COURSE, Course.SINGLE));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(COURSE);
    }

    @Override
    protected BlockState withExtras(BlockState state, WorldAccess world, BlockPos pos) {
        boolean above = world.getBlockState(pos.up()).isOf(this);
        boolean below = world.getBlockState(pos.down()).isOf(this);
        Course course = above ? (below ? Course.MIDDLE : Course.BOTTOM) : (below ? Course.TOP : Course.SINGLE);
        return state.with(COURSE, course);
    }
}
