package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtr.PlaceMarkerBlockEntity;
import com.stationannouncer.mtr.Wayfinding;
import com.stationannouncer.wayfinding.Place;
import com.stationannouncer.wayfinding.PlaceCategory;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Place Marker's editor (FlatUi, no vanilla widgets): name, category,
 * description, arrival radius and whether Map+ shows it. Opens by itself when
 * the marker is placed, and on a brush (or marker-item) right-click after.
 */
public class PlaceMarkerScreen extends Screen {
    private static final int W = 316;
    private static final int H = 232;
    private static final PlaceCategory[] CATEGORIES = PlaceCategory.values();

    private final PlaceMarkerBlockEntity marker;
    private FlatUi.TextBox nameBox;
    private FlatUi.TextBox descriptionBox;
    private PlaceCategory category;
    private int radius;
    private boolean hidden;
    private boolean draggingRadius;

    private int left;
    private int top;
    private final List<Hit> hits = new ArrayList<>();
    private int[] radiusTrack = new int[4];

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public PlaceMarkerScreen(PlaceMarkerBlockEntity marker) {
        super(Text.translatable("gui.station_announcer.place_marker.title"));
        this.marker = marker;
        this.category = marker.getCategory();
        this.radius = marker.getRadius();
        this.hidden = marker.isHidden();
    }

    @Override
    protected void init() {
        if (nameBox == null) {
            nameBox = new FlatUi.TextBox(textRenderer, Place.MAX_NAME, false);
            nameBox.placeholder = Text.translatable("gui.station_announcer.place_marker.name_hint").getString();
            String name = marker.getName();
            nameBox.load("New place".equals(name) ? "" : name);
            nameBox.setFocused(true);
            descriptionBox = new FlatUi.TextBox(textRenderer, Place.MAX_DESCRIPTION, true);
            descriptionBox.placeholder = Text.translatable("gui.station_announcer.place_marker.description_hint").getString();
            descriptionBox.load(marker.getDescription());
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        c.fill(0, 0, width, height, 0xB0000000);
        int w = Math.min(W, width - 12);
        int h = Math.min(H, height - 12);
        left = (width - w) / 2;
        top = (height - h) / 2;
        FlatUi.rect(c, left, top, w, h, FlatUi.GROUND);
        FlatUi.outline(c, left, top, w, h, FlatUi.BORDER_STRONG);

        int x = left + 10;
        int right = left + w - 10;
        int y = top + 8;
        c.drawText(textRenderer, title, x, y, FlatUi.TEXT, false);
        String where = marker.getPos().getX() + " " + marker.getPos().getY() + " " + marker.getPos().getZ();
        c.drawText(textRenderer, where, right - textRenderer.getWidth(where), y, FlatUi.TEXT_FAINT, false);

        // Name
        y += 16;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.place_marker.name").getString(), x, y);
        y += 10;
        nameBox.setBounds(x, y, right - x, 16);
        nameBox.render(c, mx, my);

        // Category chips
        y += 22;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.place_marker.category").getString(), x, y);
        y += 10;
        int cx = x;
        for (PlaceCategory option : CATEGORIES) {
            String label = Text.translatable(option.translationKey()).getString();
            int cw = textRenderer.getWidth(label) + 16;
            if (cx + cw > right) {
                cx = x;
                y += 15;
            }
            boolean on = option == category;
            boolean hover = FlatUi.inside(mx, my, cx, y, cw, 13);
            FlatUi.rect(c, cx, y, cw, 13, on ? FlatUi.ACCENT_DIM : hover ? 0xFF34343C : FlatUi.PANE_RAISED);
            FlatUi.outline(c, cx, y, cw, 13, on ? FlatUi.ACCENT : FlatUi.BORDER);
            FlatUi.rect(c, cx + 4, y + 4, 5, 5, 0xFF000000 | option.color());
            c.drawText(textRenderer, label, cx + 12, y + 3, on ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            hits.add(new Hit(cx, y, cw, 13, () -> category = option));
            cx += cw + 3;
        }

        // Description
        y += 20;
        FlatUi.heading(c, textRenderer, Text.translatable("gui.station_announcer.place_marker.description").getString(), x, y);
        y += 10;
        descriptionBox.setBounds(x, y, right - x, 34);
        descriptionBox.render(c, mx, my);

        // Radius slider
        y += 42;
        String radiusLabel = radius == 0 ? Text.translatable("gui.station_announcer.place_marker.radius_point").getString()
                : Text.translatable("gui.station_announcer.place_marker.radius_value", radius).getString();
        c.drawText(textRenderer, Text.translatable("gui.station_announcer.place_marker.radius").getString(),
                x, y, FlatUi.TEXT_DIM, false);
        int tx = x + 86;
        int tw = right - tx - 70;
        radiusTrack = new int[]{tx, y - 2, tw, 12};
        float v = radius / (float) Place.MAX_RADIUS;
        boolean hot = draggingRadius || FlatUi.inside(mx, my, tx, y - 2, tw, 12);
        FlatUi.rect(c, tx, y + 3, tw, 3, FlatUi.INPUT);
        FlatUi.rect(c, tx, y + 3, Math.round(tw * v), 3, hot ? FlatUi.ACCENT : FlatUi.ACCENT_DIM);
        FlatUi.rect(c, tx + Math.round((tw - 4) * v), y, 4, 9, hot ? FlatUi.TEXT : FlatUi.TEXT_DIM);
        c.drawText(textRenderer, radiusLabel, tx + tw + 6, y, FlatUi.TEXT, false);

        // Map visibility
        y += 16;
        c.drawText(textRenderer, Text.translatable("gui.station_announcer.place_marker.map").getString(),
                x, y + 3, FlatUi.TEXT_DIM, false);
        String[] visibility = {
                Text.translatable("gui.station_announcer.place_marker.shown").getString(),
                Text.translatable("gui.station_announcer.place_marker.hidden").getString()};
        int sx = x + 86;
        int sw = 120;
        int hovered = FlatUi.segmented(c, textRenderer, visibility, hidden ? 1 : 0, sx, y, sw, 14, mx, my);
        hits.add(new Hit(sx, y, sw / 2, 14, () -> hidden = false));
        hits.add(new Hit(sx + sw / 2, y, sw - sw / 2, 14, () -> hidden = true));

        // Footer
        int by = top + h - 24;
        String id = marker.getPlaceId().isEmpty() ? "" : "id " + marker.getPlaceId();
        c.drawText(textRenderer, id, x, by + 4, FlatUi.TEXT_FAINT, false);
        FlatUi.button(c, textRenderer, Text.translatable("gui.cancel").getString(), right - 128, by, 60, 16,
                mx, my, FlatUi.ButtonStyle.GHOST);
        hits.add(new Hit(right - 128, by, 60, 16, this::close));
        boolean canSave = !nameBox.getText().isBlank();
        FlatUi.button(c, textRenderer, Text.translatable("gui.station_announcer.place_marker.save").getString(),
                right - 60, by, 60, 16, mx, my, FlatUi.ButtonStyle.PRIMARY, canSave);
        if (canSave) {
            hits.add(new Hit(right - 60, by, 60, 16, this::save));
        }
    }

    /** Dev rig only (WayfindingClient#devHook): fill in and save through the real path. */
    void devSave(String categoryId, String name) {
        init();
        category = PlaceCategory.byId(categoryId);
        nameBox.load(name);
        radius = 16;
        save();
    }

    private void save() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(marker.getPos());
        buf.writeString(Place.clean(nameBox.getText(), Place.MAX_NAME), Place.MAX_NAME);
        buf.writeString(category.id(), 32);
        buf.writeString(descriptionBox.getText(), Place.MAX_DESCRIPTION);
        buf.writeVarInt(radius);
        buf.writeBoolean(hidden);
        ClientPlayNetworking.send(Wayfinding.UPDATE_PLACE_MARKER_C2S, buf);
        close();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean inName = nameBox.mouseClicked(mx, my, button);
        boolean inDescription = !inName && descriptionBox.mouseClicked(mx, my, button);
        nameBox.setFocused(inName);
        descriptionBox.setFocused(inDescription);
        if (inName || inDescription) {
            return true;
        }
        if (button == 0 && FlatUi.inside(mx, my, radiusTrack[0], radiusTrack[1], radiusTrack[2], radiusTrack[3])) {
            draggingRadius = true;
            dragRadius(mx);
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (button == 0 && FlatUi.inside(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingRadius) {
            dragRadius(mx);
            return true;
        }
        nameBox.mouseDragged(mx, my);
        descriptionBox.mouseDragged(mx, my);
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingRadius = false;
        nameBox.mouseReleased();
        descriptionBox.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (descriptionBox.mouseScrolled(mx, my, vertical)) {
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    private void dragRadius(double mx) {
        float v = (float) Math.max(0, Math.min(1, (mx - radiusTrack[0]) / Math.max(1, radiusTrack[2] - 4)));
        int raw = Math.round(v * Place.MAX_RADIUS);
        radius = raw <= 16 ? raw : Math.round(raw / 4f) * 4; // fine steps close in, coarse far out
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameBox.charTyped(chr) || descriptionBox.charTyped(chr)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (nameBox.isFocused() || descriptionBox.isFocused())) {
            nameBox.setFocused(false);
            descriptionBox.setFocused(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            boolean toDescription = nameBox.isFocused();
            nameBox.setFocused(!toDescription);
            descriptionBox.setFocused(toDescription);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && nameBox.isFocused()
                && !nameBox.getText().isBlank()) {
            save();
            return true;
        }
        if (key == GLFW.GLFW_KEY_S && hasControlDown() && !nameBox.getText().isBlank()) {
            save();
            return true;
        }
        if (nameBox.keyPressed(key, modifiers) || descriptionBox.keyPressed(key, modifiers)) {
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }
}
