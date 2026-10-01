package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Exit Marker: stands where a station exit really is (the top of the street
 * stair, the doorway) and pins that spot to one of the station's MTR exits.
 * The brush opens {@code ExitMarkerScreen}, which also adds, renames and
 * deletes the station's exits themselves — the same data MTR's dashboard edits.
 */
public class ExitMarkerBlock extends MarkerBlock {
    public ExitMarkerBlock(Settings settings) {
        super(settings);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ExitMarkerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient || type != Wayfinding.EXIT_MARKER_BLOCK_ENTITY) {
            return null;
        }
        return (tickWorld, tickPos, tickState, be) -> {
            ExitMarkerBlockEntity marker = (ExitMarkerBlockEntity) be;
            if (!marker.reconciled && tickWorld instanceof ServerWorld serverWorld) {
                marker.reconciled = true;
                Wayfinding.reconcileExit(serverWorld, tickPos, marker);
            }
        };
    }

    @Override
    protected void onRemoved(World world, BlockPos pos) {
        Wayfinding.exitMarkerRemoved(world, pos);
    }
}
