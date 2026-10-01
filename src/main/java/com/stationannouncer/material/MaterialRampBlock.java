package com.stationannouncer.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * An ADA ramp in any full block's texture.
 *
 * <p><b>The slope is 1:12</b> — the ADA's maximum for a ramp: one unit of rise
 * per twelve of run. In blocks that is one block up over twelve blocks along,
 * so a ramp is a RUN of blocks, each carrying one twelfth of the rise:
 * {@link #SEG} 0..11 is which twelfth (segment 0 starts at the floor, segment
 * 11 arrives at the next floor level). {@link #SEG} 12 is a level LANDING at
 * full height — ADA ramps need landings, and it is also where a longer ramp
 * steps up: the next run starts one block higher, segment 0 on top of the
 * landing (or on top of any full block — the texture is world-aligned, so a
 * full block of the same material under the upper run is seamless).</p>
 *
 * <p>{@link #FACING} points UPHILL. Placing a ramp continues whatever run it
 * touches: a ramp beside it (same facing) gives it the same segment (wide
 * ramps), else the segment after the ramp just downhill, else segment 0 on top
 * of a finished run one level down, else the segment before the ramp just
 * uphill. Collision is the slope stepped in quarter-block strips, so walking
 * up is smooth (each step is a third of a pixel).</p>
 */
public class MaterialRampBlock extends Block implements MaterialBlock {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final int RUN = 12;
    public static final IntProperty SEG = IntProperty.of("seg", 0, RUN);
    private static final int STRIPS = 4;

    /** [horizontal facing][seg]. */
    private final VoxelShape[][] shapes = new VoxelShape[4][RUN + 1];

    public MaterialRampBlock(Settings settings) {
        super(settings);
        setDefaultState(MaterialPalette.withSlot(getDefaultState().with(FACING, Direction.NORTH).with(SEG, 0), 0));
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (int seg = 0; seg <= RUN; seg++) {
                shapes[facing.getHorizontal()][seg] = buildShape(facing, seg);
            }
        }
    }

    /** Surface height (px) at the DOWNHILL edge of a segment. */
    public static float lowPx(int seg) {
        return seg >= RUN ? 16f : seg * 16f / RUN;
    }

    /** Surface height (px) at the UPHILL edge of a segment. */
    public static float highPx(int seg) {
        return seg >= RUN ? 16f : (seg + 1) * 16f / RUN;
    }

    private static VoxelShape buildShape(Direction facing, int seg) {
        if (seg >= RUN) {
            return VoxelShapes.fullCube();
        }
        // North frame: uphill is north (z = 0 high, z = 16 low).
        VoxelShape shape = VoxelShapes.empty();
        float low = lowPx(seg);
        float high = highPx(seg);
        for (int k = 0; k < STRIPS; k++) {
            float z0 = 16f - 16f * (k + 1) / STRIPS;
            float z1 = 16f - 16f * k / STRIPS;
            float top = low + (high - low) * (k + 1) / STRIPS;
            shape = VoxelShapes.union(shape, rotated(facing, 0, 0, z0, 16, Math.max(0.01f, top), z1));
        }
        return shape.simplify();
    }

    private static VoxelShape rotated(Direction facing, float x0, float y0, float z0, float x1, float y1, float z1) {
        float[] a = rotateXZ(facing, x0, z0);
        float[] b = rotateXZ(facing, x1, z1);
        return Block.createCuboidShape(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
    }

    /** Blockstate y rotation of a north-frame point: y90 (16−z, x) · y180 (16−x, 16−z) · y270 (z, 16−x). */
    public static float[] rotateXZ(Direction facing, float x, float z) {
        return switch (facing) {
            case EAST -> new float[]{16 - z, x};
            case SOUTH -> new float[]{16 - x, 16 - z};
            case WEST -> new float[]{z, 16 - x};
            default -> new float[]{x, z};
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SEG, MaterialPalette.MAT_HI, MaterialPalette.MAT_LO);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(FACING).getHorizontal()][state.get(SEG)];
    }

    /**
     * Let light into the ramp's cell, as vanilla stairs and slabs do. The slope
     * is not flush with the block top, so smooth lighting samples the ramp's OWN
     * cell; an opaque cell there reads dark and blotched the slope (seen on the
     * rig). The landing is a full cube and stays opaque.
     */
    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return state.get(SEG) < RUN;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction facing = context.getHorizontalPlayerFacing();
        int seg = segmentFor(context.getWorld(), context.getBlockPos(), facing);
        return MaterialBlock.withPlacedMaterial(getDefaultState().with(FACING, facing).with(SEG, seg), context);
    }

    /** Continue whatever ramp run this position touches (see the class comment). */
    private int segmentFor(BlockView world, BlockPos pos, Direction facing) {
        for (Direction side : new Direction[]{facing.rotateYClockwise(), facing.rotateYCounterclockwise()}) {
            BlockState beside = world.getBlockState(pos.offset(side));
            if (sameRun(beside, facing)) {
                return beside.get(SEG);
            }
        }
        BlockState downhill = world.getBlockState(pos.offset(facing.getOpposite()));
        if (sameRun(downhill, facing)) {
            return Math.min(RUN, downhill.get(SEG) + 1);
        }
        BlockState belowDownhill = world.getBlockState(pos.offset(facing.getOpposite()).down());
        if (sameRun(belowDownhill, facing) && belowDownhill.get(SEG) >= RUN - 1) {
            return 0;
        }
        BlockState uphill = world.getBlockState(pos.offset(facing));
        if (sameRun(uphill, facing) && uphill.get(SEG) > 0 && uphill.get(SEG) < RUN) {
            return uphill.get(SEG) - 1;
        }
        return 0;
    }

    private boolean sameRun(BlockState state, Direction facing) {
        return state.getBlock() instanceof MaterialRampBlock && state.get(FACING) == facing;
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        return List.of(stackFor(this, state, builder.getWorld()));
    }

    @Override
    public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
        return stackFor(this, state, world instanceof World w ? w : null);
    }
}
