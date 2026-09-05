package com.stationannouncer.mtr;

import com.stationannouncer.block.ElRailingBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.shape.VoxelShape;

/**
 * El platform lamp pole: a {@link ColumnBlock} stack that also counts an el
 * railing below as "connected", so a lamp planted on a railing continues the
 * railing's post upward without a base plate (Thomas: the lamp is an
 * extension of the railing outside).
 */
public class ElLampPoleBlock extends ColumnBlock {
    public ElLampPoleBlock(Settings settings, VoxelShape shape) {
        super(settings, shape);
    }

    @Override
    protected boolean connectsDown(BlockState below) {
        return super.connectsDown(below) || ElRailingBlock.isElRailing(below);
    }
}
