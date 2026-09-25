package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.GapFillerStore;
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
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
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

/**
 * A gap filler: a section of platform edge with a steel plate that slides out
 * across the gap to the car side while a train stands at the platform, then
 * back under the edge before the train may leave.
 *
 * <p>Two styles from the two NYC stations that made them famous:
 * <ul>
 *   <li>{@link Style#UNION} — 14 St–Union Square (Lexington downtown): a
 *       hydraulic grate that shoots straight out at platform level and lands
 *       with a bang;</li>
 *   <li>{@link Style#LOOP} — the old South Ferry outer loop: an older riveted
 *       section that rolls out and DOWN its sloping rails until it bumps the
 *       car side.</li>
 * </ul>
 *
 * <p>The block is the platform edge itself ({@link PlatformHelper}, so train
 * doors open against it exactly as against {@link PlatformEdgeBlock}). Its
 * static body — concrete, deck, tactile strip, the slot — is the block model;
 * the moving plate is drawn by {@code GapFillerRenderer}. The plate's
 * COLLISION rides {@link #PHASE}, which the block entity copies from the
 * simulator-side {@link com.stationannouncer.mtraddon.GapFillerEngine}.</p>
 *
 * <p>Frame: authored NORTH = track side (the model's -z), rotated by the
 * blockstate like every other facing block here. {@link #REACH} is how far
 * the plate travels past the edge, in 2 px steps (2..24 px, up to a block and
 * a half) — set automatically from the platform's rail when the block links,
 * adjustable with the brush.</p>
 */
public class GapFillerBlock extends Block implements BlockEntityProvider, PlatformHelper {
    public enum Style {
        UNION(GapFillerStore.Settings.UNION),
        LOOP(GapFillerStore.Settings.LOOP);

        public final GapFillerStore.Settings defaults;

        Style(GapFillerStore.Settings defaults) {
            this.defaults = defaults;
        }
    }

    /** The side the track (and the plate's travel) is on. */
    public static final DirectionProperty TRACK_SIDE = Properties.HORIZONTAL_FACING;
    /** Plate travel past the edge, in units of 2 px (1..12 → 2..24 px). */
    public static final IntProperty REACH = IntProperty.of("reach", 1, 12);
    public static final EnumProperty<GapFillerPhase> PHASE = EnumProperty.of("phase", GapFillerPhase.class);

    /** Top and bottom of the plate at rest (px); the deck above it is y 15..16. */
    public static final float PLATE_TOP = 15f;
    public static final float PLATE_THICKNESS = 2f;
    /** How far the plate stays inside the slot when fully out (px). */
    public static final float PLATE_OVERLAP = 4f;
    /** South Ferry's sloping rails: the plate drops 1 px for every 8 px it travels. */
    public static final float LOOP_SLOPE = 1f / 8f;

    public final Style style;
    /** [facing horizontal index][reach 1..12][plate solid 0/1]. */
    private final VoxelShape[][][] collision = new VoxelShape[4][13][2];

    public GapFillerBlock(Settings settings, Style style) {
        super(settings);
        this.style = style;
        setDefaultState(getDefaultState().with(TRACK_SIDE, Direction.NORTH).with(REACH, 4)
                .with(PHASE, GapFillerPhase.RETRACTED));
        for (Direction side : Direction.Type.HORIZONTAL) {
            for (int reach = 1; reach <= 12; reach++) {
                VoxelShape body = VoxelShapes.fullCube();
                collision[side.getHorizontal()][reach][0] = body;
                float travel = reach * 2f;
                float drop = style == Style.LOOP ? travel * LOOP_SLOPE : 0f;
                VoxelShape plate = rotated(side, 0, PLATE_TOP - PLATE_THICKNESS - drop, -travel,
                        16, PLATE_TOP - drop, 0);
                collision[side.getHorizontal()][reach][1] = VoxelShapes.union(body, plate).simplify();
            }
        }
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(TRACK_SIDE, REACH, PHASE);
    }

    /** Like the platform edge: you stand on the platform looking at the track. */
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(TRACK_SIDE, context.getHorizontalPlayerFacing());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collision[state.get(TRACK_SIDE).getHorizontal()][state.get(REACH)][state.get(PHASE).plateSolid() ? 1 : 0];
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCollisionShape(state, world, pos, context);
    }

    /** North-frame pixel box → the facing's frame (the blockstate's y rotation). */
    static VoxelShape rotated(Direction side, float x0, float y0, float z0, float x1, float y1, float z1) {
        float[] a = rotateXZ(side, x0, z0);
        float[] b = rotateXZ(side, x1, z1);
        return Block.createCuboidShape(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    /** y90: (16−z, x) · y180: (16−x, 16−z) · y270: (z, 16−x) — the rule gen_gate_assets uses. */
    static float[] rotateXZ(Direction side, float x, float z) {
        return switch (side) {
            case EAST -> new float[]{16 - z, x};
            case SOUTH -> new float[]{16 - x, 16 - z};
            case WEST -> new float[]{z, 16 - x};
            default -> new float[]{x, z};
        };
    }

    // ------------------------------------------------------------ interaction

    /** The MTR brush opens the settings screen. */
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

    // ------------------------------------------------------------ block entity

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GapFillerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || type != GapFillers.GAP_FILLER_BLOCK_ENTITY) {
            return null;
        }
        return (tickWorld, tickPos, tickState, be) -> GapFillerBlockEntity.serverTick(tickWorld, tickPos, tickState,
                (GapFillerBlockEntity) be);
    }
}
