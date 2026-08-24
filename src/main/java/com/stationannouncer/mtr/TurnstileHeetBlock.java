package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * High Entrance/Exit Turnstile — the full-height "iron maiden" rotating gate.
 * Same fare behavior as the low turnstile (MTR picks entry or exit from the
 * travel record, the indicator lamp shows the result), but the unit is a
 * self-contained two-block cage: side frames of vertical bars with a four-wing
 * comb rotor between them. It opts out of the fare-array row
 * ({@link #joinsRow()}), so neighboring turnstile tubing caps against it.
 *
 * <p>Like every fare block it never physically blocks movement through the
 * lane — only the side frames carry collision; walking the center IS the
 * fare event (the turnstile's precedent).
 */
public class TurnstileHeetBlock extends TurnstileBlock {
    private final VoxelShape[] frameCollision;
    private final VoxelShape[] outline;

    public TurnstileHeetBlock(Settings settings) {
        super(settings);
        // North frame: the lane runs along z, side frames flank it on x.
        VoxelShape sides = VoxelShapes.union(
                createCuboidShape(0.0, 0.0, 1.0, 2.0, 16.0, 15.0),
                createCuboidShape(14.0, 0.0, 1.0, 16.0, 16.0, 15.0)).simplify();
        this.frameCollision = rotations(sides);
        this.outline = rotations(VoxelShapes.union(
                sides, createCuboidShape(6.5, 0.0, 6.5, 9.5, 16.0, 9.5)).simplify());
    }

    @Override
    protected boolean joinsRow() {
        return false;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return rotated(outline, state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return rotated(frameCollision, state);
    }
}
