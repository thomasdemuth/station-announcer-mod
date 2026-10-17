package com.stationannouncer.client.gui;

import com.stationannouncer.client.StationAnnouncerClient;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtr.RouteTrainPa;
import com.stationannouncer.pa.PaText;
import com.stationannouncer.pa.TrainAnnouncements;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A route's ON-BOARD announcements ({@link RouteTrainPa}), opened from the "Train PA…"
 * button on MTR's Edit Route screen: the shared {@link TrainRowsEditor} plus a chime
 * switch. Every train on the route speaks the rows at every stop to the players riding
 * it — {@code {station}} is the stop being served.
 */
@Environment(EnvType.CLIENT)
public class RouteTrainScreen extends FlatPaScreen {
    private static final int H_BACKDROP = 1;
    private static final int H_CHIME = 2;
    private static final int H_SAVE = 3;
    private static final int H_CANCEL = 4;
    /** The stop a preview pretends to serve. */
    private static final PaText.Train SAMPLE = PaText.SAMPLE.withStation("Atlantic Avenue");

    private final long routeId;
    private final String routeName;
    @Nullable
    private final Screen parent;
    private final TrainRowsEditor rows;
    private boolean chime = true;
    private String chimeSound = "";
    private boolean loaded;
    private boolean chimeDirty;
    private int pendingRow = -1;
    private int scroll;
    private int content;
    private int paneX;
    private int paneY;
    private int paneW;
    private int paneH;

    public RouteTrainScreen(long routeId, String routeName, @Nullable Screen parent) {
        super(Text.literal("Train PA"));
        this.routeId = routeId;
        this.routeName = routeName;
        this.parent = parent;
        this.rows = new TrainRowsEditor(this, true, List.of(), text -> sampleOf(text),
                text -> StationAnnouncerClient.previewLocally(sampleOf(text), null, 1.0f, chime, chimeSound));
    }

    private static String sampleOf(String raw) {
        String text = PaText.fillTrain(raw, SAMPLE);
        return text.contains(PaText.TIME) ? PaText.fill(text, null, PaText.clock()) : text;
    }

    /** Client receiver for {@code route_train_pa}; registered once from the addon's client init. */
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(RouteTrainPa.DATA_S2C, (client, handler, buf, responseSender) -> {
            long routeId = buf.readLong();
            RouteTrainPa.Entry entry;
            try {
                entry = RouteTrainPa.Entry.read(buf);
            } catch (RuntimeException e) {
                return;
            }
            client.execute(() -> {
                if (client.currentScreen instanceof com.stationannouncer.client.mtraddon.RouteSettingsScreen routeScreen) {
                    routeScreen.acceptTrainPa(routeId, entry);
                    return;
                }
                if (client.currentScreen instanceof RouteTrainScreen screen && screen.routeId == routeId && !screen.loaded) {
                    screen.chime = entry.chime();
                    screen.chimeSound = entry.chimeSound();
                    screen.rows.load(entry.trains().rows);
                    screen.loaded = true;
                    if (screen.pendingRow >= 0) {
                        screen.rows.select(screen.pendingRow);
                    }
                }
            });
        });
    }

    @Override
    protected void init() {
        if (!loaded) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(routeId);
            ClientPlayNetworking.send(RouteTrainPa.QUERY_C2S, buf);
        }
    }

    @Override
    public void removed() {
        StationAnnouncerClient.stopPreview();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    /** Dev rig only: show a row open without clicking (applied once the server answers). */
    public void devShow(int row) {
        pendingRow = row;
        if (loaded) {
            rows.select(row);
        }
    }

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        FlatUi.rect(c, 0, 0, width, height, 0xB0000000);
        int w = Math.min(width - 12, 340);
        int h = Math.min(height - 12, 340);
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        FlatUi.rect(c, x0, y0, w, h, FlatUi.GROUND);
        FlatUi.outline(c, x0, y0, w, h, FlatUi.BORDER_STRONG);
        c.drawText(textRenderer, "Train PA", x0 + 8, y0 + 7, FlatUi.TEXT, false);
        c.drawText(textRenderer, trim(routeName, w - 70), x0 + 14 + textWidth("Train PA"), y0 + 7, FlatUi.TEXT_FAINT, false);

        paneX = x0 + 6;
        paneY = y0 + 20;
        paneW = w - 12;
        paneH = h - 44;
        FlatUi.pane(c, paneX, paneY, paneW, paneH);
        int x = paneX + 6;
        int iw = paneW - 12;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, content - (paneH - 4)));
        beginClip(c, paneX + 1, paneY + 2, paneW - 2, paneH - 4);
        int y = paneY + 6 - scroll;
        if (!loaded) {
            c.drawText(textRenderer, "Asking the server…", x, y, FlatUi.TEXT_FAINT, false);
            y += 12;
        } else {
            c.drawText(textRenderer, trim("Spoken to everyone riding this route's trains, at every stop.", iw),
                    x, y, FlatUi.TEXT_DIM, false);
            y += 12;
            toggleRow(c, mx, my, "Chime first", chime, x, y, iw, H_CHIME, 0);
            y += 17;
            FlatUi.heading(c, textRenderer, "Announcements", x, y);
            y += 11;
            y = rows.draw(c, mx, my, x, y, iw);
        }
        content = y + scroll - paneY;
        endClip(c);
        FlatUi.scrollThumb(c, paneX + paneW, paneY + 2, paneH - 4, content, scroll);

        int fy = y0 + h - 20;
        button(c, mx, my, "Save", x0 + w - 6 - 56, fy, 56, 16, FlatUi.ButtonStyle.PRIMARY, loaded, H_SAVE, 0);
        button(c, mx, my, "Cancel", x0 + w - 6 - 56 - 4 - 56, fy, 56, 16, FlatUi.ButtonStyle.GHOST, true, H_CANCEL, 0);
    }

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        if (id >= TrainRowsEditor.BASE && rows.onHit(id, arg)) {
            return;
        }
        switch (id) {
            case 0, H_BACKDROP -> {
                if (FlatUi.inside(mx, my, paneX, paneY, paneW, paneH)) {
                    rows.select(-1);
                }
            }
            case H_CHIME -> {
                chime = !chime;
                chimeDirty = true;
            }
            case H_SAVE -> save();
            case H_CANCEL -> close();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        rows.onSlider(key, value);
    }

    @Override
    protected boolean onEnter() {
        if (rows.owns(focused)) {
            focus(null);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_S && Screen.hasControlDown()) {
            save();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (rows.owns(focused) && rows.editor.mouseScrolled(mx, my, vertical)) {
            return true;
        }
        scroll -= (int) Math.signum(vertical) * 18;
        return true;
    }

    private void save() {
        if (!loaded) {
            return;
        }
        if (rows.dirty || chimeDirty) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(routeId);
            new RouteTrainPa.Entry(chime, chimeSound, new TrainAnnouncements(true, List.of(), rows.rows)).write(buf);
            ClientPlayNetworking.send(RouteTrainPa.UPDATE_C2S, buf);
        }
        close();
    }
}
