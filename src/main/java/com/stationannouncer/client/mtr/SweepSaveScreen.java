package com.stationannouncer.client.mtr;

import com.stationannouncer.client.mtraddon.FlatUi;
import com.stationannouncer.client.mtraddon.FlatUi.ButtonStyle;
import com.stationannouncer.client.mtraddon.FlatUi.TextBox;
import com.stationannouncer.mtr.ItemSweepWand;
import com.stationannouncer.mtr.Sweep;
import com.stationannouncer.mtr.SweepTemplate;
import com.stationannouncer.mtr.SweepTemplateStore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * The Template Wand's save screen: what the box holds (trimmed to its blocks
 * by default, so a loose selection is fine) and the template's name. Where
 * the rail sits is picked later, on the real track.
 */
@Environment(EnvType.CLIENT)
public class SweepSaveScreen extends Screen {
    private static final String[] OFF_ON = {"Off", "On"};

    private final Hand hand;
    private final ItemSweepWand.Selection selection;
    private boolean trim = true;
    private TextBox nameBox;
    private String error = "";
    /** Sizes {length, width, height, blocks} untrimmed / trimmed; null while not computed or too big to count. */
    private int[] full, trimmed;

    private int px, py, pw, ph;
    private int saveX, saveY, cancelX, overX, btnW, trimX, trimY;

    public SweepSaveScreen(Hand hand, ItemStack stack) {
        super(Text.translatable("gui.station_announcer.sweep.save_title"));
        this.hand = hand;
        this.selection = ItemSweepWand.read(stack);
    }

    @Override
    protected void init() {
        if (nameBox == null) {
            nameBox = new TextBox(textRenderer, SweepTemplateStore.MAX_NAME, false);
            nameBox.setFocused(true);
        }
        pw = Math.min(260, width - 16);
        ph = 132;
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
        nameBox.setBounds(px + 10, py + 80, pw - 20, 16);
        if (full == null && client != null && client.world != null && selection.complete()) {
            BlockPos min = selection.min(), max = selection.max();
            full = sizes(min, max, true);
            long volume = (long) selection.sizeX() * selection.sizeY() * selection.sizeZ();
            if (volume <= 2_000_000L) {
                BlockPos[] box = SweepTemplate.trimmed(client.world, min, max);
                trimmed = box == null ? new int[]{0, 0, 0, 0} : sizes(box[0], box[1], true);
            }
        }
    }

    /** {length (longer side), width, height, blocks} of a box. */
    private int[] sizes(BlockPos min, BlockPos max, boolean count) {
        int sx = max.getX() - min.getX() + 1, sz = max.getZ() - min.getZ() + 1, sy = max.getY() - min.getY() + 1;
        int blocks = 0;
        if (count && client != null && client.world != null && (long) sx * sy * sz <= 2_000_000L) {
            for (BlockPos p : BlockPos.iterate(min, max)) {
                if (!client.world.getBlockState(p).isAir()) {
                    blocks++;
                }
            }
        }
        return new int[]{Math.max(sx, sz), Math.min(sx, sz), sy, blocks};
    }

    private int[] chosen() {
        return trim && trimmed != null ? trimmed : full;
    }

    private boolean tooBig() {
        int[] s = chosen();
        return s != null && (s[0] > SweepTemplate.MAX_LENGTH || s[1] > SweepTemplate.MAX_WIDTH || s[2] > SweepTemplate.MAX_HEIGHT);
    }

    private boolean empty() {
        int[] s = chosen();
        return s != null && s[3] == 0 && trim;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0x99000000);
        FlatUi.pane(c, px, py, pw, ph);
        c.drawText(textRenderer, getTitle(), px + 10, py + 8, FlatUi.TEXT, false);
        if (!selection.complete()) {
            FlatUi.label(c, textRenderer, "The wand has no box yet.", px + 10, py + 26);
            return;
        }
        int[] s = chosen();
        if (s != null) {
            String size = String.format(Locale.ROOT, "%d long × %d wide × %d tall", s[0], s[1], s[2]);
            c.drawText(textRenderer, size, px + 10, py + 24, tooBig() ? FlatUi.DANGER : FlatUi.TEXT_DIM, false);
            String blocks = s[3] + " blocks";
            c.drawText(textRenderer, blocks, px + pw - 10 - textRenderer.getWidth(blocks), py + 24, FlatUi.TEXT_FAINT, false);
        }
        if (tooBig()) {
            FlatUi.label(c, textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Too big — max %d × %d × %d",
                    SweepTemplate.MAX_LENGTH, SweepTemplate.MAX_WIDTH, SweepTemplate.MAX_HEIGHT), pw - 20), px + 10, py + 35);
        } else if (empty()) {
            FlatUi.label(c, textRenderer, "The box has no blocks in it.", px + 10, py + 35);
        }
        FlatUi.label(c, textRenderer, "Trim empty space", px + 10, py + 52);
        trimX = px + pw - 10 - 60;
        trimY = py + 48;
        FlatUi.segmented(c, textRenderer, OFF_ON, trim ? 1 : 0, trimX, trimY, 60, 15, mx, my);
        FlatUi.heading(c, textRenderer, "Name", px + 10, py + 70);
        nameBox.render(c, mx, my);
        if (!error.isEmpty()) {
            c.drawText(textRenderer, error, px + 10, py + 100, FlatUi.DANGER, false);
        }
        btnW = 58;
        saveY = py + ph - 24;
        saveX = px + pw - 10 - btnW;
        cancelX = saveX - 4 - btnW;
        overX = px + 10;
        boolean canSave = !tooBig() && !empty() && !nameBox.getText().isBlank();
        FlatUi.button(c, textRenderer, "Save", saveX, saveY, btnW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.PRIMARY, canSave);
        FlatUi.button(c, textRenderer, "Cancel", cancelX, saveY, btnW, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.FLAT);
        FlatUi.button(c, textRenderer, "Start over", overX, saveY, 64, FlatUi.BUTTON_HEIGHT, mx, my, ButtonStyle.GHOST);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!selection.complete()) {
            close();
            return true;
        }
        if (FlatUi.inside(mx, my, trimX, trimY, 60, 15)) {
            trim = mx >= trimX + 30;
            return true;
        }
        if (FlatUi.inside(mx, my, saveX, saveY, btnW, FlatUi.BUTTON_HEIGHT)) {
            save();
            return true;
        }
        if (FlatUi.inside(mx, my, cancelX, saveY, btnW, FlatUi.BUTTON_HEIGHT)) {
            close();
            return true;
        }
        if (FlatUi.inside(mx, my, overX, saveY, 64, FlatUi.BUTTON_HEIGHT)) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(Sweep.ACTION_CLEAR_WAND);
            buf.writeString("");
            ClientPlayNetworking.send(Sweep.ACTION_C2S, buf);
            close();
            return true;
        }
        if (nameBox.mouseClicked(mx, my, button)) {
            nameBox.setFocused(true);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void save() {
        String name = SweepTemplateStore.cleanName(nameBox.getText());
        if (name == null) {
            error = "Give it a name";
            return;
        }
        if (tooBig() || empty()) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        buf.writeString(name);
        buf.writeBoolean(trim);
        ClientPlayNetworking.send(Sweep.SAVE_TEMPLATE_C2S, buf);
        SweepClient.forget(name);
        close();
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        nameBox.mouseDragged(mx, my);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        nameBox.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            close();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            save();
            return true;
        }
        if (nameBox.keyPressed(keyCode, modifiers)) {
            error = "";
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameBox.charTyped(chr)) {
            error = "";
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Dev hook: fill the name and save (headless rig). */
    public void devSave(String name) {
        nameBox.load(name);
        save();
    }
}
