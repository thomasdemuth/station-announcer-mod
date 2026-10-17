package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.ClientLineFolders;
import org.mtr.mod.screen.DashboardListItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Indents a route row that sits inside an open line folder in MTR's dashboard
 * ({@link ClientLineFolders#indent}); every other row is untouched. Target javap-verified:
 * {@code public String getName(boolean)} (MTR 4.0.1).
 */
@Mixin(value = DashboardListItem.class, remap = false)
public abstract class DashboardListItemMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void stationannouncer$indent(boolean formatted, CallbackInfoReturnable<String> cir) {
        String prefix = ClientLineFolders.indent((DashboardListItem) (Object) this);
        if (!prefix.isEmpty()) {
            cir.setReturnValue(prefix + cir.getReturnValue());
        }
    }
}
