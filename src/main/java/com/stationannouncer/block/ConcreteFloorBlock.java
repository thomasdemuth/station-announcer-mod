package com.stationannouncer.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Concrete platform floor: poured slabs {@link #slabBlocks} blocks across.
 *
 * <p>The slab joints are a pure function of world position - a groove wherever
 * {@code x} or {@code z} lands on the grid - but they are stored in the
 * blockstate rather than computed at render time, and that is deliberate:
 *
 * <p>The first cut of this used a Fabric {@code BakedModel} that read the
 * {@code BlockPos} while the chunk meshed, which is elegant and costs no
 * blockstates. It is also invisible under <b>Sodium</b>, which does not
 * implement the Fabric Renderer API unless Indium is installed: Sodium never
 * calls {@code emitBlockQuads} and falls back to {@code getQuads}, which has no
 * position, so every block drew the same jointless piece. Plain blockstate
 * properties are understood by every renderer there is.
 *
 * <p>A joint is drawn inside the NORTH and WEST edges only, so exactly one
 * groove appears per slab boundary rather than one from each neighbour - hence
 * two properties rather than four.
 *
 * <p>Grime is a separate axis and stays in vanilla's hands: each of these four
 * property combinations maps to a weighted list of grime variants in the
 * blockstate file, which vanilla (and Sodium) pick from the position hash.
 */
public class ConcreteFloorBlock extends Block {
    public static final BooleanProperty JOINT_NORTH = BooleanProperty.of("joint_north");
    public static final BooleanProperty JOINT_WEST = BooleanProperty.of("joint_west");

    private final int slabBlocks;
    private final boolean clean;

    public ConcreteFloorBlock(Settings settings, int slabBlocks, boolean clean) {
        super(settings);
        this.slabBlocks = slabBlocks;
        this.clean = clean;
        setDefaultState(getDefaultState().with(JOINT_NORTH, false).with(JOINT_WEST, false));
    }

    /** Slab width in blocks; the joint grid pitch. */
    public int slabBlocks() {
        return slabBlocks;
    }

    /** Fresh (clean) or grimy palette - the platform edge adopts both from its neighbours. */
    public boolean isClean() {
        return clean;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        // Runs from the Block super-constructor - must not read instance fields.
        builder.add(JOINT_NORTH, JOINT_WEST);
    }

    /**
     * {@code floorMod}, not {@code %}, so the grid keeps its phase across
     * x = 0 and z = 0 instead of mirroring about the origin.
     */
    private BlockState withJoints(BlockState state, BlockPos pos) {
        return state.with(JOINT_NORTH, Math.floorMod(pos.getZ(), slabBlocks) == 0)
                .with(JOINT_WEST, Math.floorMod(pos.getX(), slabBlocks) == 0);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return withJoints(getDefaultState(), context.getBlockPos());
    }

    /**
     * Catches every way a block can appear that is not a player placing it -
     * {@code /fill}, {@code /setblock}, structure pastes, world edits - all of
     * which would otherwise leave the default jointless state behind.
     *
     * <p>The equality guard is what stops the {@code setBlockState} below from
     * recursing back into here forever.
     */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (world.isClient) {
            return;
        }
        BlockState wanted = withJoints(state, pos);
        if (wanted != state) {
            world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
        }
    }
}
