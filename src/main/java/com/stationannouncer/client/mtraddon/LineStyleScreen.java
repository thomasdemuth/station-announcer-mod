package com.stationannouncer.client.mtraddon;

import com.stationannouncer.client.gui.FlatPaScreen;
import com.stationannouncer.client.gui.LineStyleEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.mtr.core.data.Route;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;

/**
 * The line-style editor ({@link LineStyleEditor}) on a screen of its own: previews on
 * the left, the inspector on the right, Save / Cancel. The route screen embeds the same
 * editor in its Style tab; this one stays for opening a line's style directly.
 */
@Environment(EnvType.CLIENT)
public final class LineStyleScreen extends FlatPaScreen {
    private static final int HIT_SAVE = 1;
    private static final int HIT_CANCEL = 2;

    private final Route route;
    private final Screen parent;
    private final LineStyleEditor editor;
    private int scroll;
    private int contentHeight;
    private int viewHeight;

    public LineStyleScreen(Route route, Screen parent) {
        super(Text.literal("Line style"));
        this.route = route;
        this.parent = parent;
        this.editor = new LineStyleEditor(this, route);
    }

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, FlatUi.GROUND);
        c.drawText(textRenderer, "Line style", 8, 8, FlatUi.TEXT, false);
        int titleEnd = 8 + textWidth("Line style") + 8;
        int count = editor.routeCount();
        String scope = editor.line() + " · " + (count == 1 ? "1 route" : count + " routes (every direction)");
        c.drawText(textRenderer, trim(scope, Math.max(20, width - titleEnd - 120)), titleEnd, 8, FlatUi.TEXT_DIM, false);
        button(c, mx, my, "Save", width - 56, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.PRIMARY, true, HIT_SAVE, 0);
        button(c, mx, my, "Cancel", width - 110, 4, 50, FlatUi.BUTTON_HEIGHT, FlatUi.ButtonStyle.FLAT, true, HIT_CANCEL, 0);

        int top = 26;
        int bottom = height - 6;
        int previewW = MathHelper.clamp(Math.round(width * 0.42f), 150, 270);
        FlatUi.pane(c, 6, top, previewW, bottom - top);
        editor.drawPreview(c, 6, top, previewW, bottom - top);

        int ix = 6 + previewW + 6;
        int iw = width - ix - 6;
        FlatUi.pane(c, ix, top, iw, bottom - top);
        int viewTop = top + 1;
        viewHeight = bottom - top - 2;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentHeight - viewHeight));
        beginClip(c, ix + 1, viewTop, iw - 2, viewHeight);
        int end = editor.drawInspector(c, mx, my, ix + 8, viewTop + 6 - scroll, iw - 16);
        contentHeight = end + scroll - viewTop + 6;
        endClip(c);
        FlatUi.scrollThumb(c, ix + iw - 1, viewTop, viewHeight, contentHeight, scroll);
    }

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        if (id >= LineStyleEditor.BASE && editor.onHit(id, arg, mx, my)) {
            return;
        }
        switch (id) {
            case HIT_SAVE -> save();
            case HIT_CANCEL -> close();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        editor.onSlider(key, value);
    }

    @Override
    protected boolean onEnter() {
        save();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = MathHelper.clamp(scroll - (int) Math.round(vertical * 14), 0, Math.max(0, contentHeight - viewHeight));
        return true;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    /** Dev-only, through ClientLineStyles.devHook. */
    void devCommand(String args) {
        if (args.equals("save")) {
            save();
        } else if (args.equals("cancel")) {
            close();
        } else if (args.startsWith("scroll ")) {
            scroll = Integer.parseInt(args.substring(7).trim());
        } else {
            editor.devCommand(args);
        }
    }

    private void save() {
        editor.save();
        try {
            InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(
                    new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addRoute(route)));
        } catch (Exception e) {
            com.stationannouncer.StationAnnouncer.LOGGER.warn("Line style: could not save the route", e);
        }
        close();
    }
}
