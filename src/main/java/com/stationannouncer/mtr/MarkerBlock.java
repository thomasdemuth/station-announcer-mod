package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * An invisible wayfinding marker: no model, no collision, and no outline — so
 * it cannot even be aimed at — unless the player is holding a tool that
 * "reveals" markers ({@link #reveals}): the MTR brush, a marker item, or any
 * MTR rail tool (the same family MTR's own rail overlay reacts to). Vanilla's
 * light block works the same way. While revealed, the block entity renderer
 * draws the pin and its label, and the brush (or the marker's own item)
 * right-click opens its editor.
 *
 * <p>Shared by {@link ExitMarkerBlock} and {@link PlaceMarkerBlock}; the store
 * lifecycle (adopt on first tick, forget when broken) lives in
 * {@link Wayfinding}.</p>
 */
public abstract class MarkerBlock extends Block implements BlockEntityProvider {
    /** What the outline shows while revealed: a slim post, easy to hit, never in the way. */
    private static final VoxelShape REVEALED = Block.createCuboidShape(4, 0, 4, 12, 14, 12);

    protected MarkerBlock(Settings settings) {
        super(settings);
    }

    // ---------------------------------------------------------------- reveal

    /** Does holding this stack make markers visible and clickable? */
    public static boolean reveals(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        try {
            if (stack.isOf(org.mtr.mod.Items.BRUSH.get().data)) {
                return true;
            }
        } catch (Throwable ignored) {
            // MTR items not registered yet (early class loading): fall through
        }
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            return block instanceof MarkerBlock || block instanceof org.mtr.mod.block.BlockNode;
        }
        return item instanceof org.mtr.mod.item.ItemNodeModifierBase;
    }

    public static boolean revealedFor(@Nullable Entity entity) {
        return entity instanceof LivingEntity living
                && (reveals(living.getMainHandStack()) || reveals(living.getOffHandStack()));
    }

    // ---------------------------------------------------------------- shapes

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return context instanceof EntityShapeContext entityContext && revealedFor(entityContext.getEntity())
                ? REVEALED : VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0f;
    }

    // ------------------------------------------------------------ interaction

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        boolean brush;
        try {
            brush = held.isOf(org.mtr.mod.Items.BRUSH.get().data);
        } catch (Throwable t) {
            brush = false;
        }
        // The marker's own item edits too (sneak-click still places another one).
        if (!brush && !held.isOf(asItem())) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                StationAnnouncer.GUI_OPENER.accept(be);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient && placer instanceof PlayerEntity player && player.isMainPlayer()) {
            // A fresh marker is useless until it is named / pinned: open its editor right away.
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                StationAnnouncer.GUI_OPENER.accept(be);
            }
        } else if (!world.isClient && placer instanceof PlayerEntity player) {
            onPlacedBy(world, pos, player);
        }
    }

    /** Server thread: remember who placed it (places record their author). */
    protected void onPlacedBy(World world, BlockPos pos, PlayerEntity player) {
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !newState.isOf(this)) {
            onRemoved(world, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    /** Server thread: the block is gone (broken, replaced, /setblock). */
    protected abstract void onRemoved(World world, BlockPos pos);
}
