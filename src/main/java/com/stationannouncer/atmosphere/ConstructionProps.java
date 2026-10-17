package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/**
 * Small shared helpers of the construction-prop family: pixel boxes, the
 * four horizontal rotations of a NORTH-authored shape, and the
 * "right-click with an empty hand cycles the variant" gesture the signs,
 * boards and walls share.
 */
public final class ConstructionProps {
    private ConstructionProps() {
    }

    /** A box in model pixels (0..16); values outside the block are allowed. */
    public static VoxelShape px(double x0, double y0, double z0, double x1, double y1, double z1) {
        return VoxelShapes.cuboid(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0);
    }

    public static VoxelShape union(VoxelShape... shapes) {
        VoxelShape out = VoxelShapes.empty();
        for (VoxelShape s : shapes) {
            out = VoxelShapes.union(out, s);
        }
        return out.simplify();
    }

    /** NORTH-authored shape → array indexed by {@link Direction#getHorizontal()}. */
    public static VoxelShape[] rotations(VoxelShape north) {
        return FacingDecorBlock.rotations(north);
    }

    /**
     * Empty main or off hand → advance {@code property} to its next value
     * (server side), with a little click. Anything else passes through so
     * the held item can be used normally.
     */
    public static ActionResult cycleWithEmptyHand(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                                  Hand hand, Property<?> property, SoundEvent sound) {
        if (!player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            world.setBlockState(pos, cycle(state, property), Block.NOTIFY_ALL);
            world.playSound(null, pos, sound, SoundCategory.BLOCKS, 0.6f, 1.2f);
        }
        return ActionResult.success(world.isClient);
    }

    private static <T extends Comparable<T>> BlockState cycle(BlockState state, Property<T> property) {
        return state.cycle(property);
    }

    /** The run direction to the facing-relative right (the house convention, see ZebraBoardBlock). */
    public static Direction right(Direction facing) {
        return facing.rotateYClockwise();
    }
}
