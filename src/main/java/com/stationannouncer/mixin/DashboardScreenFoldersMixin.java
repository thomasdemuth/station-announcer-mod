package com.stationannouncer.mixin;

import com.stationannouncer.client.mtraddon.ClientLineFolders;
import net.minecraft.client.gui.screen.Screen;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSet;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.DashboardScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> line folders inside MTR's dashboard Routes list ({@link ClientLineFolders}).
 * {@code tick2} rebuilds every list each tick through {@code DashboardList.setData(ObjectSet,…)}
 * (which sorts by name); for the Routes list we hand it an ORDERED list with folder rows
 * ({@code setData(ObjectArrayList,…)} keeps order). A folder row is a synthetic
 * {@code DashboardListItem(id, name, color)} with {@code data == null}: MTR's own icon
 * handlers only act on items whose data is a Route/Station/…, and we HEAD-cancel them for
 * folder rows — draw-area = open / close, edit = the folder screen, delete / find = nothing.
 *
 * <p>Targets verified with javap against MTR FABRIC-4.0.1+1.20.4: {@code public void tick2()},
 * {@code private void onFind/onDrawArea/onEdit/onDelete(DashboardListItem, int)}, the
 * private {@code DashboardList dashboardList} field. Client section of the mixin config.</p>
 */
@Mixin(value = DashboardScreen.class, remap = false)
public abstract class DashboardScreenFoldersMixin {
    @Shadow
    private DashboardList dashboardList;

    @Redirect(method = "tick2", at = @At(value = "INVOKE",
            target = "Lorg/mtr/mod/screen/DashboardList;setData(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectSet;ZZZZZZ)V"))
    private void stationannouncer$folders(DashboardList list, ObjectSet<DashboardListItem> data,
                                          boolean find, boolean drawArea, boolean edit, boolean sort, boolean add, boolean delete) {
        String search = "";
        try {
            search = ((DashboardListAccessor) (Object) list).stationAnnouncer$getTextFieldSearch().getText2();
        } catch (Throwable ignored) {
            // no search box: folder view as normal
        }
        if (!ClientLineFolders.dashboardList(list, data, search, find, drawArea, edit, sort, add, delete)) {
            list.setData(data, find, drawArea, edit, sort, add, delete);
        }
    }

    @Inject(method = "onDrawArea", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$toggleFolder(DashboardListItem item, int index, CallbackInfo ci) {
        if (ClientLineFolders.onToggle(item)) {
            ci.cancel();
        }
    }

    @Inject(method = "onEdit", at = @At("HEAD"), cancellable = true)
    private void stationannouncer$editFolder(DashboardListItem item, int index, CallbackInfo ci) {
        if (ClientLineFolders.onEdit((Screen) (Object) this, item)) {
            ci.cancel();
        }
    }

    @Inject(method = {"onDelete", "onFind"}, at = @At("HEAD"), cancellable = true)
    private void stationannouncer$ignoreFolder(DashboardListItem item, int index, CallbackInfo ci) {
        if (ClientLineFolders.isFolderRow(item)) {
            ci.cancel();
        }
    }
}
