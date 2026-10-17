package com.stationannouncer.mixin;

import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mod.screen.DashboardList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The dashboard list's search box (javap-verified: {@code private final TextFieldWidgetExtension textFieldSearch}, MTR 4.0.1). */
@Mixin(value = DashboardList.class, remap = false)
public interface DashboardListAccessor {
    @Accessor("textFieldSearch")
    TextFieldWidgetExtension stationAnnouncer$getTextFieldSearch();
}
