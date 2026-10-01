package com.stationannouncer.client.gui;

import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.client.ClientConfig;
import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.render.PaRangeRenderer;
import com.stationannouncer.net.AnnouncerNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Per-speaker settings on FlatUi: volume and radius, the link's health, and a
 * live preview — the panel sits low on the screen so the world stays visible,
 * and {@link PaRangeRenderer} draws the speaker's reach at the player's height
 * while the sliders move. "Test here" plays the chime at exactly the volume
 * this player would hear from where they stand (the server's own falloff);
 * Copy / Paste carry volume + radius from speaker to speaker.
 */
@Environment(EnvType.CLIENT)
public class SpeakerScreen extends FlatPaScreen {
    private static final int W = 270;
    private static final int H = 112;

    private static final int H_TEST = 1;
    private static final int H_COPY = 2;
    private static final int H_PASTE = 3;
    private static final int H_SAVE = 4;
    private static final int H_CANCEL = 5;
    private static final int S_VOLUME = 1;
    private static final int S_RADIUS = 2;

    /** Copy / Paste clipboard: volume, radius (null = nothing copied). */
    private static int[] clipboard;
    private static SoundInstance testSound;

    private final SpeakerBlockEntity speaker;
    private int volume;
    private int radius;

    public SpeakerScreen(SpeakerBlockEntity speaker) {
        super(Text.literal("PA Speaker"));
        this.speaker = speaker;
        this.volume = speaker.getVolume();
        this.radius = speaker.getRadius();
    }

    @Override
    public void removed() {
        PaRangeRenderer.clearPreview();
    }

    @Override
    protected void draw(DrawContext c, int mx, int my) {
        PaRangeRenderer.setPreview(speaker.getPos(), radius);
        int x0 = (width - W) / 2;
        int y0 = height - H - 10;
        FlatUi.rect(c, x0, y0, W, H, 0xF0151518);
        FlatUi.outline(c, x0, y0, W, H, FlatUi.BORDER_STRONG);
        int x = x0 + 8;
        int w = W - 16;
        int y = y0 + 7;
        c.drawText(textRenderer, title, x, y, FlatUi.TEXT, false);
        c.drawText(textRenderer, speaker.getPos().toShortString(), x + textWidth(title.getString()) + 6, y, FlatUi.TEXT_FAINT, false);
        y += 12;
        drawLinkStatus(c, x, y, w);
        y += 14;

        slider(c, mx, my, S_VOLUME, "Volume", 46, volume, 0, 100, volume + "%", x, y, w);
        y += 15;
        slider(c, mx, my, S_RADIUS, "Radius", 46, radius, SpeakerBlockEntity.MIN_RADIUS, SpeakerBlockEntity.MAX_RADIUS,
                radius + " m", x, y, w);
        y += 16;

        float heard = heardVolume();
        double distance = distance();
        String where = heard <= 0
                ? String.format("You are %d m away: outside its reach", Math.round(distance))
                : String.format("You are %d m away: you would hear it at %d%%", Math.round(distance), Math.round(heard * 100));
        c.drawText(textRenderer, trim(where, w), x, y, heard <= 0 ? 0xFFE0B341 : FlatUi.TEXT_DIM, false);
        y += 10;
        c.drawText(textRenderer, trim("The cyan ring shows its reach at your height", w), x, y, FlatUi.TEXT_FAINT, false);

        int by = y0 + H - 22;
        int bx = x;
        int testW = textWidth("▶ Test here") + 12;
        button(c, mx, my, "▶ Test here", bx, by, testW, 16, FlatUi.ButtonStyle.FLAT, true, H_TEST, 0);
        bx += testW + 4;
        button(c, mx, my, "Copy", bx, by, 34, 16, FlatUi.ButtonStyle.GHOST, true, H_COPY, 0);
        bx += 38;
        button(c, mx, my, "Paste", bx, by, 38, 16, FlatUi.ButtonStyle.GHOST, clipboard != null, H_PASTE, 0);
        button(c, mx, my, "Save", x0 + W - 8 - 48, by, 48, 16, FlatUi.ButtonStyle.PRIMARY, true, H_SAVE, 0);
        button(c, mx, my, "Cancel", x0 + W - 8 - 48 - 4 - 48, by, 48, 16, FlatUi.ButtonStyle.GHOST, true, H_CANCEL, 0);
    }

    private void drawLinkStatus(DrawContext c, int x, int y, int w) {
        BlockPos boxPos = speaker.getControlBoxPos();
        String pos = boxPos == null ? "" : boxPos.toShortString();
        String text;
        int color;
        switch (speaker.getClientLinkState()) {
            case SpeakerBlockEntity.LINK_OK -> {
                text = "Linked to the Control Box at " + pos;
                color = FlatUi.OK;
            }
            case SpeakerBlockEntity.LINK_BROKEN -> {
                text = "Link broken: no Control Box at " + pos;
                color = FlatUi.DANGER;
            }
            case SpeakerBlockEntity.LINK_NOT_LOADED -> {
                text = "Linked to " + pos + " (not loaded)";
                color = 0xFFE0B341;
            }
            default -> {
                text = "Not linked yet (use the Speaker Link)";
                color = FlatUi.TEXT_DIM;
            }
        }
        FlatUi.rect(c, x, y + 1, 6, 6, color);
        c.drawText(textRenderer, trim(text, w - 10), x + 10, y, color, false);
    }

    private double distance() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        return Math.sqrt(client.player.squaredDistanceTo(Vec3d.ofCenter(speaker.getPos())));
    }

    /** The server's falloff (AbstractPaBlockEntity.fire): 100% at the speaker, 25% at the edge, 0 beyond. */
    private float heardVolume() {
        double d = distance();
        if (d > radius) {
            return 0;
        }
        float falloff = 1.0f - 0.75f * (float) (d / radius);
        return MathHelper.clamp(volume / 100.0f * falloff, 0.0f, 1.0f);
    }

    @Override
    protected void onHit(int id, int arg, double mx, double my) {
        switch (id) {
            case H_TEST -> test();
            case H_COPY -> {
                clipboard = new int[]{volume, radius};
                toast("Copied " + volume + "% · " + radius + " m", false);
            }
            case H_PASTE -> {
                if (clipboard != null) {
                    volume = clipboard[0];
                    radius = clipboard[1];
                }
            }
            case H_SAVE -> save();
            case H_CANCEL -> close();
            default -> {
            }
        }
    }

    @Override
    protected void onSlider(int key, int value) {
        if (key == S_VOLUME) {
            volume = value;
        } else if (key == S_RADIUS) {
            radius = value;
        }
    }

    @Override
    protected boolean onEnter() {
        save();
        return true;
    }

    private void test() {
        float heard = heardVolume();
        if (heard <= 0) {
            toast("You are outside this speaker's reach", true);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (testSound != null) {
            client.getSoundManager().stop(testSound);
        }
        testSound = new PositionedSoundInstance(com.stationannouncer.ModContent.CHIME.getId(),
                ClientConfig.get().chimeSoundCategory(), heard, 1.0f, SoundInstance.createRandom(), false, 0,
                SoundInstance.AttenuationType.NONE, 0.0, 0.0, 0.0, true);
        client.getSoundManager().play(testSound);
    }

    private void save() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(speaker.getPos());
        buf.writeVarInt(volume);
        buf.writeVarInt(radius);
        ClientPlayNetworking.send(AnnouncerNetworking.UPDATE_SPEAKER_C2S, buf);
        close();
    }
}
