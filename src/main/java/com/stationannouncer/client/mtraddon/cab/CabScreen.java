package com.stationannouncer.client.mtraddon.cab;

import com.stationannouncer.client.mtraddon.DrivingHud;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.mtraddon.crew.CrewProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.mod.data.VehicleExtension;
import java.util.ArrayList;
import java.util.List;

/**
 * The cab menu (MANUAL_DRIVING_PLAN.md §3.2): the driver of a manual-siding train picks a
 * timetable SLOT here — one upcoming departure of the depot (or its depot group) that the manual
 * run takes over; the automatic train for it stays home (see {@code SlotEngine}). Phase 4 adds the
 * startup ritual to this screen.
 *
 * <p>FlatUi, no vanilla widgets; click targets are recorded while drawing. The list refreshes from
 * the server every few seconds while open (slots move: MTR rewrites departures every ~25 s).</p>
 */
@Environment(EnvType.CLIENT)
public final class CabScreen extends Screen {
    private static final long REFRESH_MILLIS = 4_000;

    record Option(long autoSidingId, int index, long slotYard, long manualYard, String firstStop, long firstDeparture,
                  String routeName, String routeNumber, int routeColor, String destination, boolean claimedByYou) {
    }

    /** Last slot list from the server (client thread). */
    private static long optionsSidingId;
    private static long optionsServerNow;
    private static long optionsReceivedAt;
    private static final List<Option> OPTIONS = new ArrayList<>();

    private final long manualSidingId;
    private final boolean manualTrain;
    private long lastRequest;
    private final List<Hit> hits = new ArrayList<>();
    private int scroll;
    /** 0 = Start up, 1 = Service. Opens on Start up while the ritual is pending. */
    private int tab = CabStartup.pending() ? 0 : 1;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private CabScreen(long manualSidingId, boolean manualTrain) {
        super(Text.translatable("gui.station_announcer.cab.menu"));
        this.manualSidingId = manualSidingId;
        this.manualTrain = manualTrain;
    }

    static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(CrewProtocol.SLOT_LIST_S2C, (client, handler, buf, sender) -> {
            long sidingId = buf.readLong();
            long serverNow = buf.readLong();
            int count = Math.min(buf.readVarInt(), 32);
            List<Option> options = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                options.add(new Option(buf.readLong(), buf.readVarInt(), buf.readLong(), buf.readLong(), buf.readString(64),
                        buf.readLong(), buf.readString(64), buf.readString(64), buf.readInt(), buf.readString(64),
                        buf.readBoolean()));
            }
            client.execute(() -> {
                optionsSidingId = sidingId;
                optionsServerNow = serverNow;
                optionsReceivedAt = System.currentTimeMillis();
                OPTIONS.clear();
                OPTIONS.addAll(options);
            });
        });
    }

    /** Opens the menu for the train the player drives (or a hint when not driving). */
    static void open(MinecraftClient client) {
        open(client, -1);
    }

    /** Opens the cab menu on a tab (0 start up, 1 service, 2 train; -1 = the default). */
    static void open(MinecraftClient client, int tab) {
        VehicleExtension vehicle = DrivingHud.findDrivenVehicle();
        if (vehicle == null) {
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("gui.station_announcer.cab.menu_not_driving"), true);
            }
            return;
        }
        CabScreen screen = new CabScreen(vehicle.vehicleExtraData.getSidingId(), vehicle.vehicleExtraData.getIsManualAllowed());
        if (tab >= 0 && tab <= 2) {
            screen.tab = tab;
        }
        client.setScreen(screen);
    }

    @Override
    protected void init() {
        request();
    }

    private void request() {
        lastRequest = System.currentTimeMillis();
        if (!manualTrain || !ClientPlayNetworking.canSend(CrewProtocol.SLOT_LIST_C2S)) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(manualSidingId);
        ClientPlayNetworking.send(CrewProtocol.SLOT_LIST_C2S, buf);
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() - lastRequest > REFRESH_MILLIS) {
            request();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        hits.clear();
        c.fill(0, 0, width, height, 0x88000000);
        int w = Math.min(320, width - 16);
        int h = Math.min(220, height - 16);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        FlatUi.pane(c, x, y, w, h);
        // Tabs: Start up | Service.
        String[] tabs = {tr("gui.station_announcer.cab.tab_startup"), tr("gui.station_announcer.cab.tab_service"),
                tr("gui.station_announcer.cab.tab_train")};
        int tx = x + 8;
        for (int i = 0; i < tabs.length; i++) {
            int tw = textRenderer.getWidth(tabs[i]) + 12;
            final int index = i;
            FlatUi.button(c, textRenderer, tabs[i], tx, y + 6, tw, 14, mouseX, mouseY,
                    tab == i ? FlatUi.ButtonStyle.PRIMARY : FlatUi.ButtonStyle.GHOST);
            hits.add(new Hit(tx, y + 6, tw, 14, () -> tab = index));
            tx += tw + 4;
        }
        int by = y + h - 24;
        FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.close"), x + w - 66, by, 58, 16, mouseX, mouseY,
                FlatUi.ButtonStyle.FLAT);
        hits.add(new Hit(x + w - 66, by, 58, 16, this::close));
        if (tab == 0) {
            renderStartup(c, x, y + 24, w, mouseX, mouseY);
            return;
        }
        if (tab == 2) {
            renderTrain(c, x, y + 24, w, mouseX, mouseY);
            return;
        }
        FlatUi.label(c, textRenderer, tr(manualTrain ? "gui.station_announcer.cab.pick_service_hint"
                : "gui.station_announcer.cab.pick_service_auto"), x + 8, y + 26);

        int listY = y + 40;
        int listH = h - 40 - 40;
        long serverNow = optionsServerNow + (System.currentTimeMillis() - optionsReceivedAt);
        List<Option> options = optionsSidingId == manualSidingId ? OPTIONS : List.of();
        if (manualTrain && options.isEmpty()) {
            FlatUi.label(c, textRenderer, tr(optionsReceivedAt == 0 ? "gui.station_announcer.cab.loading"
                    : "gui.station_announcer.cab.no_slots"), x + 8, listY + 4);
        }
        int rowH = 26;
        int visible = Math.max(1, listH / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, options.size() - visible)));
        c.enableScissor(x + 1, listY, x + w - 1, listY + listH);
        for (int i = scroll; i < options.size() && i < scroll + visible + 1; i++) {
            Option option = options.get(i);
            int ry = listY + (i - scroll) * rowH;
            if (option.claimedByYou()) {
                FlatUi.rect(c, x + 4, ry, w - 8, rowH - 2, FlatUi.SELECTED);
            }
            // Bullet.
            int color = option.routeColor() | 0xFF000000;
            int cx = x + 16;
            int cy = ry + 12;
            for (int dy = -7; dy <= 7; dy++) {
                int hw = (int) Math.floor(Math.sqrt(49 - dy * dy + 5.6));
                c.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, color);
            }
            String label = option.routeNumber().isEmpty() ? initial(option.routeName()) : option.routeNumber();
            c.drawText(textRenderer, label, cx - textRenderer.getWidth(label) / 2 + 1, cy - 3, 0xFFFFFFFF, false);
            // Text.
            String line1 = CabHud.hhmm(option.firstDeparture()) + "  " + option.firstStop() + " → " + option.destination();
            c.drawText(textRenderer, fit(line1, w - 100), x + 28, ry + 3, FlatUi.TEXT, false);
            long untilYard = option.manualYard() - serverNow;
            String line2 = Text.translatable("gui.station_announcer.cab.leave_yard", CabHud.hhmmss(option.manualYard()),
                    untilYard >= 0 ? Text.translatable("gui.station_announcer.cab.in_minutes", untilYard / 60_000, (untilYard / 1000) % 60).getString()
                            : tr("gui.station_announcer.cab.late_already")).getString();
            c.drawText(textRenderer, fit(line2, w - 100), x + 28, ry + 14, untilYard >= 0 ? FlatUi.TEXT_DIM : 0xFFFFC04D, false);
            // Action.
            int bx = x + w - 66;
            if (option.claimedByYou()) {
                FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.give_back"), bx, ry + 4, 58, 16,
                        mouseX, mouseY, FlatUi.ButtonStyle.DANGER);
                hits.add(new Hit(bx, ry + 4, 58, 16, this::release));
            } else {
                FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.take"), bx, ry + 4, 58, 16,
                        mouseX, mouseY, FlatUi.ButtonStyle.PRIMARY);
                hits.add(new Hit(bx, ry + 4, 58, 16, () -> claim(option)));
            }
        }
        c.disableScissor();

        c.drawText(textRenderer, fit(tr("gui.station_announcer.cab.slot_rules"), w - 16), x + 8, by - 12, FlatUi.TEXT_FAINT, false);
    }

    /** The startup: one big button that plays the cinematic ({@link CabCinematic}), and Skip. */
    private void renderStartup(DrawContext c, int x, int y, int w, int mouseX, int mouseY) {
        if (CabStartup.step() == CabStartup.DONE) {
            c.drawText(textRenderer, tr("gui.station_announcer.cab.started_up"), x + 8, y + 6, FlatUi.OK, false);
            return;
        }
        int bw = Math.min(200, w - 32);
        int bx = x + (w - bw) / 2;
        int by = y + 18;
        FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.start_train"), bx, by, bw, 28, mouseX, mouseY,
                FlatUi.ButtonStyle.PRIMARY);
        hits.add(new Hit(bx, by, bw, 28, CabStartup::begin));
        String hint = fit(tr("gui.station_announcer.cab.start_train_steps"), w - 16);
        c.drawText(textRenderer, hint, x + (w - textRenderer.getWidth(hint)) / 2, by + 36, FlatUi.TEXT_DIM, false);
        int sy = by + 56;
        FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.skip"), x + 8, sy, 70, 16, mouseX, mouseY, FlatUi.ButtonStyle.GHOST);
        hits.add(new Hit(x + 8, sy, 70, 16, CabStartup::complete));
        c.drawText(textRenderer, fit(tr("gui.station_announcer.cab.skip_hint"), w - 100), x + 84, sy + 4, FlatUi.TEXT_FAINT, false);
    }

    /** The horn for this train type: test each one locally, pick one (ops; same rule as /crew horn). */
    private void renderTrain(DrawContext c, int x, int y, int w, int mouseX, int mouseY) {
        c.drawText(textRenderer, tr("gui.station_announcer.cab.horn_title"), x + 8, y + 4, FlatUi.TEXT_DIM, false);
        byte current = com.stationannouncer.client.mtraddon.crew.CrewView.horn();
        for (int i = 0; i < CrewProtocol.HORNS.length; i++) {
            int ry = y + 18 + i * 22;
            boolean chosen = i == current;
            FlatUi.rect(c, x + 8, ry, w - 16, 18, chosen ? FlatUi.SELECTED : FlatUi.PANE);
            c.drawText(textRenderer, tr("gui.station_announcer.cab.horn_" + CrewProtocol.HORNS[i]), x + 14, ry + 5,
                    chosen ? FlatUi.TEXT : FlatUi.TEXT_DIM, false);
            final int horn = i;
            FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.horn_test"), x + w - 132, ry + 1, 56, 16,
                    mouseX, mouseY, FlatUi.ButtonStyle.GHOST);
            hits.add(new Hit(x + w - 132, ry + 1, 56, 16, () -> testHorn(horn)));
            if (!chosen) {
                FlatUi.button(c, textRenderer, tr("gui.station_announcer.cab.horn_use"), x + w - 72, ry + 1, 56, 16,
                        mouseX, mouseY, FlatUi.ButtonStyle.FLAT);
                hits.add(new Hit(x + w - 72, ry + 1, 56, 16, () -> useHorn(horn)));
            }
        }
        c.drawText(textRenderer, fit(tr("gui.station_announcer.cab.horn_hint"), w - 16), x + 8, y + 18 + 4 * 22 + 4,
                FlatUi.TEXT_FAINT, false);
    }

    private static void testHorn(int horn) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvent.of(CrewProtocol.hornSound(horn)), 1.0f, 0.8f));
    }

    private static void useHorn(int horn) {
        if (ClientPlayNetworking.canSend(CrewProtocol.HORN_SET_C2S)) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(horn);
            ClientPlayNetworking.send(CrewProtocol.HORN_SET_C2S, buf);
        }
    }

    private void claim(Option option) {
        if (!ClientPlayNetworking.canSend(CrewProtocol.SLOT_CLAIM_C2S)) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(manualSidingId);
        buf.writeLong(option.autoSidingId());
        buf.writeVarInt(option.index());
        buf.writeLong(option.slotYard());
        ClientPlayNetworking.send(CrewProtocol.SLOT_CLAIM_C2S, buf);
        lastRequest = System.currentTimeMillis() - REFRESH_MILLIS + 500; // refresh shortly to show the claim
    }

    private void release() {
        if (!ClientPlayNetworking.canSend(CrewProtocol.SLOT_RELEASE_C2S)) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(manualSidingId);
        ClientPlayNetworking.send(CrewProtocol.SLOT_RELEASE_C2S, buf);
        lastRequest = System.currentTimeMillis() - REFRESH_MILLIS + 500;
    }

    /** Dev hook: claim the first offered slot without clicking (#cab-claim). */
    static void devClaimFirst() {
        if (MinecraftClient.getInstance().currentScreen instanceof CabScreen screen && !OPTIONS.isEmpty()) {
            screen.claim(OPTIONS.get(0));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hit hit : hits) {
                if (FlatUi.inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) Math.signum(vertical);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String fit(String s, int max) {
        if (textRenderer.getWidth(s) <= max) {
            return s;
        }
        String cut = s;
        while (cut.length() > 1 && textRenderer.getWidth(cut + "…") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static String initial(@Nullable String name) {
        return name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }
}
