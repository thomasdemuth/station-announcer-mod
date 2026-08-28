package com.stationannouncer.mtr;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The black EXIT sign: hanging from a ceiling stub or flat on a wall (MOUNT
 * from the clicked face), with the arrow cycled by right-click — plain EXIT,
 * right, left, down.
 *
 * <p>PLACEMENT CONVENTION (shared with {@link ElNameBoardBlock}): clicking a
 * wall gives MOUNT=WALL with FACING = the clicked side, so the lettering
 * reads out of the wall; clicking a ceiling or a floor gives MOUNT=CEILING
 * with FACING = the placer's look reversed, the facing-decor convention. The
 * plate is a wide thin strip — 12 x 3.5 px, i.e. ~0.75 m x 0.2 m, the real
 * proportions — and the wall variant stands 1 px off the wall on two visible
 * standoff brackets.
 */
public class ElExitSignBlock extends Block {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Mount> MOUNT = EnumProperty.of("mount", Mount.class);
    public static final EnumProperty<Arrow> ARROW = EnumProperty.of("arrow", Arrow.class);

    public enum Mount implements StringIdentifiable {
        CEILING, WALL;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Arrow implements StringIdentifiable {
        NONE, RIGHT, LEFT, DOWN;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final VoxelShape[] hanging;
    private final VoxelShape[] wall;

    public ElExitSignBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH)
                .with(MOUNT, Mount.CEILING).with(ARROW, Arrow.NONE));
        // Plate is a wide thin strip (12 x 3.5 px, gen_el_phase3.EXIT_PLATE);
        // the hanging outline runs up to the ceiling so the stub is clickable,
        // the wall outline covers plate + standoff brackets.
        this.hanging = TurnstileBaseBlock.rotations(createCuboidShape(2.0, 8.0, 7.2, 14.0, 16.0, 8.8));
        this.wall = TurnstileBaseBlock.rotations(createCuboidShape(2.0, 7.5, 14.0, 14.0, 12.0, 16.0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, MOUNT, ARROW);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape[] shapes = state.get(MOUNT) == Mount.WALL ? wall : hanging;
        return shapes[state.get(FACING).getHorizontal()];
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        if (side.getAxis().isHorizontal()) {
            // clicked a wall: plate on it, facing outward
            return getDefaultState().with(MOUNT, Mount.WALL).with(FACING, side);
        }
        return getDefaultState().with(MOUNT, Mount.CEILING)
                .with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    /** Right-click cycles the arrow: EXIT → EXIT→ → ←EXIT → EXIT↓. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        world.setBlockState(pos, state.cycle(ARROW));
        world.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.BLOCKS, 0.4f, 1.4f);
        return ActionResult.CONSUME;
    }
}
