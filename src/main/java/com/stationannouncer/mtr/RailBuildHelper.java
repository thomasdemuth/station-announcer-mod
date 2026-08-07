package com.stationannouncer.mtr;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;

/**
 * Shared geometry for the rail-following creator items (pillar, railing,
 * viaduct): stepping along a rail with the local perpendicular, and building
 * material columns down to the ground. Extracted from ItemPillarCreator in
 * 1.7, behavior unchanged.
 */
final class RailBuildHelper {
    /** How much solid "bridge deck" right under the rail a column may pass through. */
    static final int MAX_DECK_SKIP = 3;

    /** One sampling step along the rail: position and unit perpendicular (null on degenerate spans). */
    interface StepConsumer {
        void accept(Vector center, Vector perpendicular);
    }

    private RailBuildHelper() {
    }

    /**
     * Walks the rail from {@code start} to its end in {@code step}-block
     * increments, handing each position and the local horizontal
     * perpendicular (unit length, or null where the direction degenerates)
     * to the consumer.
     */
    static void walk(RailMath railMath, double start, double step, StepConsumer consumer) {
        double length = railMath.getLength();
        for (double distance = start; distance < length; distance += step) {
            Vector center = railMath.getPosition(distance, false);
            double t2 = Math.min(distance + 0.5, length);
            Vector a = railMath.getPosition(t2 - 0.5, false);
            Vector b = railMath.getPosition(t2, false);
            Vector direction = new Vector(b.x - a.x, 0, b.z - a.z);
            Vector perpendicular = direction.x == 0 && direction.z == 0
                    ? null
                    : direction.normalize().rotateY(Math.PI / 2);
            consumer.accept(center, perpendicular);
        }
    }

    /**
     * Builds one column straight down from one block below the rail at
     * {@code top}, replacing air/liquids/plants until the ground. Thin
     * decking directly under the rail (up to {@link #MAX_DECK_SKIP} solid
     * blocks before anything was placed) is passed through untouched.
     * @return 1 if any block was placed, else 0.
     */
    static int buildColumn(ServerWorld world, Vector top, net.minecraft.block.BlockState state) {
        int x = (int) Math.floor(top.x);
        int z = (int) Math.floor(top.z);
        int startY = (int) Math.floor(top.y) - 1;
        int placed = 0;
        for (int y = startY; y >= world.getBottomY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (world.getBlockState(pos).isReplaceable()) {
                world.setBlockState(pos, state, 3);
                placed++;
            } else if (placed > 0 || y <= startY - MAX_DECK_SKIP) {
                break; // hit the ground (existing deck right under the rail is skipped)
            }
        }
        return placed > 0 ? 1 : 0;
    }

    /** Places {@code state} at the block containing {@code position} if replaceable. @return true if placed. */
    static boolean placeIfReplaceable(ServerWorld world, Vector position, int y, net.minecraft.block.BlockState state) {
        BlockPos pos = new BlockPos((int) Math.floor(position.x), y, (int) Math.floor(position.z));
        if (world.getBlockState(pos).isReplaceable()) {
            world.setBlockState(pos, state, 3);
            return true;
        }
        return false;
    }
}
