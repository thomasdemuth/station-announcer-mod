package com.stationannouncer.atmosphere.mixin;

import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkLightProvider.class)
public interface ChunkLightProviderAccessor {
    @Accessor("chunkProvider")
    ChunkProvider stationAnnouncer$chunkProvider();
}
