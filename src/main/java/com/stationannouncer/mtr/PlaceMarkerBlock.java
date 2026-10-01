package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Place Marker: a named point of interest (landmark, park, district…) that
 * shows on Map+, can be searched and routed to, and that other mods can read
 * through {@link com.stationannouncer.wayfinding.WayfindingApi}. The brush
 * opens {@code PlaceMarkerScreen}. {@code /place} makes the same places
 * without a block.
 */
public class PlaceMarkerBlock extends MarkerBlock {
    public PlaceMarkerBlock(Settings settings) {
        super(settings);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PlaceMarkerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || type != Wayfinding.PLACE_MARKER_BLOCK_ENTITY) {
            return null;
        }
        return (tickWorld, tickPos, tickState, be) -> {
            PlaceMarkerBlockEntity marker = (PlaceMarkerBlockEntity) be;
            if (!marker.reconciled && tickWorld instanceof ServerWorld serverWorld) {
                marker.reconciled = true;
                Wayfinding.reconcilePlace(serverWorld, tickPos, marker);
            }
        };
    }

    @Override
    protected void onPlacedBy(World world, BlockPos pos, PlayerEntity player) {
        if (world.getBlockEntity(pos) instanceof PlaceMarkerBlockEntity marker) {
            marker.createdBy = player.getGameProfile().getName();
        }
    }

    @Override
    protected void onRemoved(World world, BlockPos pos) {
        Wayfinding.placeMarkerRemoved(world, pos);
    }
}
