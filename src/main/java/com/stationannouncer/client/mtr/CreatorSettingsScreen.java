package com.stationannouncer.client.mtr;

import com.stationannouncer.client.gui.IntSlider;
import com.stationannouncer.mtr.CreatorSettings;
import com.stationannouncer.mtr.ItemElStructureCreator;
import com.stationannouncer.mtr.ItemPillarCreator;
import com.stationannouncer.mtr.MtrPillars;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.List;

/**
 * Settings for the rail-following creators (right-click in the air): width
 * and bent spacing for both, column style and cross-girder toggle for the
 * El Structure Creator. The right half is a live 3D preview of one span,
 * rebuilt whenever a control changes; drag it to turn it. Done sends the
 * values to the server, which writes them into the held item's NBT.
 */
@Environment(EnvType.CLIENT)
public class CreatorSettingsScreen extends Screen {
    private static final int LEFT_WIDTH = 170;
    private static final int PREVIEW = 190;
    private static final int GAP = 6;
    private static final int ROW = 24;

    private final Hand hand;
    private final boolean structure;
    private final BlockState material;
    private int deckWidth;
    private int spacing;
    private boolean lattice;
    private boolean girder;

    private List<CreatorPreview.Cell> scene = List.of();
    private float yaw = 35;
    private boolean dragging;
    private int previewX;
    private int previewY;

    public CreatorSettingsScreen(Hand hand, ItemStack stack) {
        super(Text.translatable("gui.station_announcer.creator.title"));
        this.hand = hand;
        this.structure = stack.getItem() instanceof ItemElStructureCreator;
        int defaultWidth = ItemElStructureCreator.DEFAULT_WIDTH;
        int defaultSpacing = ItemElStructureCreator.DEFAULT_SPACING;
        BlockState saved = Blocks.STONE_BRICKS.getDefaultState();
        if (stack.getItem() instanceof ItemPillarCreator pillar) {
            defaultWidth = pillar.width;
            defaultSpacing = pillar.spacing;
            BlockState picked = pillar.savedMaterial(stack);
            if (picked != null && !picked.isAir()) {
                saved = picked;
            }
        }
        this.material = saved;
        this.deckWidth = CreatorSettings.width(stack, defaultWidth);
        this.spacing = CreatorSettings.spacing(stack, defaultSpacing);
        this.lattice = CreatorSettings.lattice(stack);
        this.girder = CreatorSettings.girder(stack);
        rebuild();
    }

    private void rebuild() {
        scene = structure
                ? CreatorPreview.structure(deckWidth, spacing, lattice, girder)
                : CreatorPreview.pillars(deckWidth, spacing, material);
    }

    @Override
    protected void init() {
        int total = LEFT_WIDTH + GAP + PREVIEW;
        int left = (this.width - total) / 2;
        int top = 40;
        previewX = left + LEFT_WIDTH + GAP;
        previewY = top;
        int y = top;
        addDrawableChild(new IntSlider(left, y, LEFT_WIDTH, 20, 0, (CreatorSettings.MAX_WIDTH - 1) / 2, (deckWidth - 1) / 2,
                v -> Text.translatable("gui.station_announcer.creator.width", 2 * v + 1),
                v -> {
                    deckWidth = 2 * v + 1;
                    rebuild();
                }));
        y += ROW;
        addDrawableChild(new IntSlider(left, y, LEFT_WIDTH, 20, CreatorSettings.MIN_SPACING, CreatorSettings.MAX_SPACING, spacing,
                v -> Text.translatable("gui.station_announcer.creator.spacing", v),
                v -> {
                    spacing = v;
                    rebuild();
                }));
        y += ROW;
        if (structure) {
            addDrawableChild(CyclingButtonWidget.onOffBuilder(
                            Text.translatable("gui.station_announcer.creator.lattice"),
                            Text.translatable("gui.station_announcer.creator.box"))
                    .initially(lattice)
                    .build(left, y, LEFT_WIDTH, 20, Text.translatable("gui.station_announcer.creator.columns"),
                            (button, value) -> {
                                lattice = value;
                                rebuild();
                            }));
            y += ROW;
            addDrawableChild(CyclingButtonWidget.onOffBuilder(ScreenTexts.ON, ScreenTexts.OFF)
                    .initially(girder)
                    .build(left, y, LEFT_WIDTH, 20, Text.translatable("gui.station_announcer.creator.girder"),
                            (button, value) -> {
                                girder = value;
                                rebuild();
                            }));
            y += ROW;
        }
        int buttonsY = Math.max(y + ROW, top + PREVIEW + GAP);
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> {
            send();
            close();
        }).dimensions(left, buttonsY, LEFT_WIDTH / 2 - 2, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
                .dimensions(left + LEFT_WIDTH / 2 + 2, buttonsY, LEFT_WIDTH / 2 - 2, 20).build());
    }

    private void send() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(hand == Hand.OFF_HAND);
        buf.writeInt(deckWidth);
        buf.writeInt(spacing);
        buf.writeBoolean(lattice);
        buf.writeBoolean(girder);
        ClientPlayNetworking.send(MtrPillars.UPDATE_CREATOR_C2S, buf);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 16, 0xFFFFFF);
        context.fill(previewX, previewY, previewX + PREVIEW, previewY + PREVIEW, 0xFF1C1C22);
        context.drawBorder(previewX, previewY, PREVIEW, PREVIEW, 0xFF5A5A66);
        if (!dragging) {
            yaw += delta * 0.4f;
        }
        CreatorPreview.render(context, previewX + 4, previewY + 4, PREVIEW - 8, PREVIEW - 8, scene, yaw);
        context.drawTextWithShadow(textRenderer, Text.translatable("gui.station_announcer.creator.preview_hint"),
                previewX, previewY + PREVIEW + 4, 0xFF9A9AA5);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= previewX && mouseX < previewX + PREVIEW
                && mouseY >= previewY && mouseY < previewY + PREVIEW) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging) {
            yaw += (float) deltaX * 1.5f;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
