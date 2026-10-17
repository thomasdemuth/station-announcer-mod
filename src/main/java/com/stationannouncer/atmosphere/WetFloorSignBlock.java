package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The yellow folding A-frame floor sign. FACING = the side its front leaf
 * faces (towards the player who placed it); both leaves carry the legend.
 * Right-click with an empty hand cycles the legend.
 */
public class WetFloorSignBlock extends FacingDecorBlock {
    public enum Variant implements StringIdentifiable {
        WET_FLOOR("wet_floor"), CAUTION("caution"), CLOSED("closed");

        private final String id;

        Variant(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public static final EnumProperty<Variant> VARIANT = EnumProperty.of("variant", Variant.class);

    public WetFloorSignBlock(Settings settings) {
        super(settings, ConstructionProps.px(2.5, 0, 2, 13.5, 16, 14));
        setDefaultState(getDefaultState().with(VARIANT, Variant.WET_FLOOR));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(VARIANT);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ConstructionProps.cycleWithEmptyHand(state, world, pos, player, hand, VARIANT, SoundEvents.BLOCK_BAMBOO_HIT);
    }
}
