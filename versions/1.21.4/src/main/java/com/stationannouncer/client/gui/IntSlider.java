package com.stationannouncer.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Integer slider with a formatted caption and a change callback, shared by all PA screens. */
@Environment(EnvType.CLIENT)
class IntSlider extends SliderWidget {
    private final int min;
    private final int max;
    private final IntFunction<Text> caption;
    private final IntConsumer onChange;

    IntSlider(int x, int y, int width, int height, int min, int max, int initial,
              IntFunction<Text> caption, IntConsumer onChange) {
        super(x, y, width, height, caption.apply(initial),
                (MathHelper.clamp(initial, min, max) - min) / (double) (max - min));
        this.min = min;
        this.max = max;
        this.caption = caption;
        this.onChange = onChange;
        updateMessage();
    }

    private int intValue() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(caption.apply(intValue()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(intValue());
    }
}
