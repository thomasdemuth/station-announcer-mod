package com.stationannouncer.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.generated.data.NameColorDataBaseSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code protected final Data data} on every TSC data object (javap 4.0.1:
 * {@code NameColorDataBaseSchema}). The slot hooks on {@code Siding} need the owning
 * simulator's clock and maps.
 */
@Mixin(value = NameColorDataBaseSchema.class, remap = false)
public interface NameColorDataAccessor {
    @Accessor("data")
    Data stationAnnouncer$getData();
}
