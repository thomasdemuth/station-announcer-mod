package com.stationannouncer.atmosphere;

import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A raw plywood sheet nailed over a window or a wall: a 1 px board against
 * the face that was clicked (FACING = that face's outward direction; a
 * floor/ceiling click falls back to facing the player). Right-click with an
 * empty hand cycles plain / POST NO BILLS stencil / NO TRESPASSING spray.
 * The grain is picked per position by weighted model variants.
 */
public class PlywoodBoardBlock extends FacingDecorBlock {
    public enum Variant implements StringIdentifiable {
        PLAIN("plain"), POST_NO_BILLS("post_no_bills"), NO_TRESPASSING("no_trespassing");

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

    public PlywoodBoardBlock(Settings settings) {
        super(settings, ConstructionProps.px(0, 0, 15, 16, 16, 16));
        setDefaultState(getDefaultState().with(VARIANT, Variant.PLAIN));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(VARIANT);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        Direction facing = side.getAxis().isHorizontal() ? side : context.getHorizontalPlayerFacing().getOpposite();
        return getDefaultState().with(FACING, facing);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ConstructionProps.cycleWithEmptyHand(state, world, pos, player, hand, VARIANT, SoundEvents.BLOCK_WOOD_HIT);
    }
}
