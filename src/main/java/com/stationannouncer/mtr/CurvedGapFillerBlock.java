package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.mtr.mod.block.PlatformHelper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A gap filler in a CURVED platform edge: the {@link CurvedPlatformEdgeBlock}'s
 * cut and tactile strip, with a slot under the deck and a plate that slides
 * out of it toward the track — the Union Square picture, on the curve that made
 * it necessary. Same sequencing, platform link, sounds and brush screen as the
 * straight {@link GapFillerBlock} (both are {@link GapFillerHost}s and share
 * {@link GapFillerBlockEntity}).
 *
 * <p>The plate slides straight out of the block's track face (model −z), so its
 * two ends stay on the block's side lines and the plates of neighbouring
 * fillers stay side by side along the curve; its leading edge follows the cut.
 * Reach lives in the block entity — the blockstate already carries the cut, and
 * cut × reach × phase would be 85k states — so collision reads the block entity
 * ({@code dynamicBounds}). Auto reach measures from the middle of the cut and
 * adds the 1/cos a slanted cut costs the plate's straight-out travel.</p>
 */
public class CurvedGapFillerBlock extends Block implements BlockEntityProvider, PlatformHelper, GapFillerHost {
    private final GapFillerBlock.Style style;
    /** Body behind the cut: [facing][cut a][cut b]. */
    private final VoxelShape[][][] bodies = new VoxelShape[4][21][21];
    /** Body + extended plate, built on demand: key = facing, a, b, reach. */
    private final Map<Integer, VoxelShape> withPlate = new ConcurrentHashMap<>();

    public CurvedGapFillerBlock(Settings settings, GapFillerBlock.Style style) {
        super(settings);
        this.style = style;
        setDefaultState(getDefaultState().with(GapFillerBlock.TRACK_SIDE, Direction.NORTH)
                .with(CurvedPlatformEdgeBlock.CUT_A, 8).with(CurvedPlatformEdgeBlock.CUT_B, 8)
                .with(GapFillerBlock.PHASE, GapFillerPhase.RETRACTED));
        for (Direction side : Direction.Type.HORIZONTAL) {
            for (int a = 0; a <= 20; a++) {
                for (int b = 0; b <= 20; b++) {
                    bodies[side.getHorizontal()][a][b] = CurvedPlatformEdgeBlock.buildShape(side,
                            CurvedPlatformEdgeBlock.depthPx(a), CurvedPlatformEdgeBlock.depthPx(b));
                }
            }
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(GapFillerBlock.TRACK_SIDE, CurvedPlatformEdgeBlock.CUT_A, CurvedPlatformEdgeBlock.CUT_B,
                GapFillerBlock.PHASE);
    }

    @Override
    public GapFillerBlock.Style style() {
        return style;
    }

    @Override
    public double[] edgeCentre(BlockState state, BlockPos pos) {
        Direction side = state.get(GapFillerBlock.TRACK_SIDE);
        float mid = Math.max(0f, (cutA(state) + cutB(state)) / 2f);
        float[] p = GapFillerBlock.rotateXZ(side, 8, mid);
        return new double[]{pos.getX() + p[0] / 16.0, pos.getZ() + p[1] / 16.0};
    }

    @Override
    public double travelPerGap(BlockState state) {
        float rise = cutB(state) - cutA(state);
        return Math.sqrt(256.0 + rise * rise) / 16.0;
    }

    static float cutA(BlockState state) {
        return CurvedPlatformEdgeBlock.depthPx(state.get(CurvedPlatformEdgeBlock.CUT_A));
    }

    static float cutB(BlockState state) {
        return CurvedPlatformEdgeBlock.depthPx(state.get(CurvedPlatformEdgeBlock.CUT_B));
    }

    /** By hand it places straight, like a curved edge; the Curved Platform Creator cuts it. */
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(GapFillerBlock.TRACK_SIDE, context.getHorizontalPlayerFacing());
    }

    // ---------------------------------------------------------------- shapes

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction side = state.get(GapFillerBlock.TRACK_SIDE);
        int a = state.get(CurvedPlatformEdgeBlock.CUT_A);
        int b = state.get(CurvedPlatformEdgeBlock.CUT_B);
        VoxelShape body = bodies[side.getHorizontal()][a][b];
        if (!state.get(GapFillerBlock.PHASE).plateSolid() || world == null
                || !(world.getBlockEntity(pos) instanceof GapFillerBlockEntity filler)) {
            return body;
        }
        int reach = filler.reachUnits(state);
        int key = ((side.getHorizontal() * 21 + a) * 21 + b) * 13 + reach;
        return withPlate.computeIfAbsent(key, k -> VoxelShapes.union(body,
                plateShape(side, CurvedPlatformEdgeBlock.depthPx(a), CurvedPlatformEdgeBlock.depthPx(b), reach * 2f)).simplify());
    }

    /** The extended plate, in 4 strips along the cut (north frame: from the cut out to −reach). */
    private VoxelShape plateShape(Direction side, float da, float db, float travel) {
        float drop = style == GapFillerBlock.Style.LOOP ? travel * GapFillerBlock.LOOP_SLOPE : 0f;
        float top = GapFillerBlock.PLATE_TOP - drop;
        VoxelShape shape = VoxelShapes.empty();
        for (int i = 0; i < 4; i++) {
            float x0 = 4f * i, x1 = 4f * (i + 1);
            float edge = da + (db - da) * (x0 + x1) / 32f;
            shape = VoxelShapes.union(shape, GapFillerBlock.rotated(side, x0, top - GapFillerBlock.PLATE_THICKNESS,
                    edge - travel, x1, top, Math.max(edge - travel + 0.5f, Math.min(16f, edge))));
        }
        return shape;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCollisionShape(state, world, pos, context);
    }

    // ------------------------------------------------------------ interaction

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(org.mtr.mod.Items.BRUSH.get().data)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof GapFillerBlockEntity filler) {
                StationAnnouncer.GUI_OPENER.accept(filler);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !newState.isOf(this) && world.getBlockEntity(pos) instanceof GapFillerBlockEntity filler) {
            filler.unlink();
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GapFillerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || type != GapFillers.GAP_FILLER_BLOCK_ENTITY) {
            return null;
        }
        return (tickWorld, tickPos, tickState, be) -> GapFillerBlockEntity.serverTick(tickWorld, tickPos, tickState,
                (GapFillerBlockEntity) be);
    }
}
