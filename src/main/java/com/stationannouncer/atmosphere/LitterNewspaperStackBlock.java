package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;

/**
 * A tied bundle of newspapers, as dropped off a delivery van. FACING turns
 * the bundle (front page masthead reads from the facing side).
 */
public class LitterNewspaperStackBlock extends FacingDecorBlock {
    public LitterNewspaperStackBlock(Settings settings) {
        super(settings, Block.createCuboidShape(3, 0, 5, 13, 5, 11));
    }
}
