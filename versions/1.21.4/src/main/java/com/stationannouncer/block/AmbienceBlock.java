package com.stationannouncer.block;

import com.mojang.serialization.MapCodec;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The station ambience block: a vent grille that quietly loops a hum or air
 * sound around itself. Right-click opens the sound/volume/radius GUI. No
 * ticker — playback is driven entirely by the client's AmbienceSoundManager.
 */
public class AmbienceBlock extends BlockWithEntity {
    public static final MapCodec<AmbienceBlock> CODEC = createCodec(AmbienceBlock::new);

    public AmbienceBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new AmbienceBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            if (world.getBlockEntity(pos) instanceof AmbienceBlockEntity ambience) {
                StationAnnouncer.GUI_OPENER.accept(ambience);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }
}
