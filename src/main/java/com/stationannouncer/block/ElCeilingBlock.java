package com.stationannouncer.block;

import net.minecraft.block.BlockState;

/**
 * El station-house ceiling: a pressed-metal panel at the bottom of the cell
 * above the top wall course; open sides carry the wall plane on up as a
 * fascia with a cornice. The lit variant adds a flush troffer. Full-cube
 * collision (the cell is structure, not headroom), so stairs rising under it
 * see a ceiling and their walls become triangle walls. Joins cross girders
 * and columns without framing them. Assets: tools/gen_el2_mezz.py.
 */
public class ElCeilingBlock extends ElEdgePanelBlock {
    public ElCeilingBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected boolean sameSurface(BlockState state) {
        return state.getBlock() instanceof ElCeilingBlock;
    }

    @Override
    protected boolean framesInto(BlockState state) {
        return state.getBlock() instanceof ElGirderBlock;
    }
}
