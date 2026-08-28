package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.FacingDecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
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
 * The elevated platform's black station name board (Bay Parkway). The name
 * auto-follows the MTR station, both faces; right-click opens the custom-name
 * screen — the named-column machinery on a board that mounts three ways.
 *
 * <p>MOUNT comes from the clicked face, the way {@link ElExitSignBlock} does
 * it: a wall gives the flush wall plate facing out of that wall, a ceiling
 * gives the hanging board, and anything you stand it on top of (a windscreen's
 * top rail, a floor) gives the standing board on its two legs. FACING for the
 * standing and hanging boards is the placer's look direction reversed — the
 * facing-decor convention used by every other decor block here — and for the
 * wall board it is the clicked side, i.e. out of the wall.
 *
 * <p>The plate itself is block-model geometry; only the letters are painted,
 * by {@code StationDecorRenderer.paintElNameBoard}, which keys the canvas
 * plane off MOUNT. Plate boxes (model px, north-authored frame):
 * <pre>
 *   standing  x 1..15  y 6..13  z 7.4..8.6
 *   hanging   x 1..15  y 5..12  z 7.4..8.6
 *   wall      x 1..15  y 5..12  z 13.8..15.0
 * </pre>
 * They are all 14 x 7 px, so the renderer's canvas size and text fitting are
 * shared; only the plate's top y and front z differ. Keep this table, the one
 * in {@code tools/gen_el_assets.py} ({@code NAME_BOARD_PLATE}) and the
 * renderer in step.
 */
public class ElNameBoardBlock extends FacingDecorBlock implements BlockEntityProvider {
    public static final EnumProperty<Mount> MOUNT = EnumProperty.of("mount", Mount.class);

    public enum Mount implements StringIdentifiable {
        STANDING, WALL, HANGING;

        @Override
        public String asString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final VoxelShape[] standing;
    private final VoxelShape[] wall;
    private final VoxelShape[] hanging;

    public ElNameBoardBlock(Settings settings, VoxelShape northShape) {
        super(settings, northShape);
        setDefaultState(getDefaultState().with(MOUNT, Mount.STANDING));
        // northShape is the standing board (plate + legs down to the floor).
        this.standing = FacingDecorBlock.rotations(northShape);
        this.wall = FacingDecorBlock.rotations(createCuboidShape(1.0, 5.0, 13.6, 15.0, 12.0, 16.0));
        this.hanging = FacingDecorBlock.rotations(createCuboidShape(1.0, 5.0, 7.2, 15.0, 16.0, 8.8));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MOUNT);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape[] shapes = switch (state.get(MOUNT)) {
            case WALL -> wall;
            case HANGING -> hanging;
            default -> standing;
        };
        return shapes[state.get(FACING).getHorizontal()];
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction side = context.getSide();
        if (side.getAxis().isHorizontal()) {
            // clicked a wall: plate flat on it, reading out of the wall
            return getDefaultState().with(MOUNT, Mount.WALL).with(FACING, side);
        }
        Mount mount = side == Direction.DOWN ? Mount.HANGING : Mount.STANDING;
        return getDefaultState().with(MOUNT, mount)
                .with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationDecorBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof StationDecorBlockEntity decor) {
                StationAnnouncer.GUI_OPENER.accept(decor);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
