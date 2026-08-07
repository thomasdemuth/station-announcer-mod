package com.stationannouncer.mtr;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.TooltipContext;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;
import java.util.List;

/**
 * Viaduct Creator — a full elevated structure in one pass: bridge deck under
 * the rail, girders hanging beneath the deck edges, and pillar columns down
 * to the ground every {@value #PILLAR_SPACING} blocks. One material for
 * everything (sneak-right-click to choose), widths 3/5/7 matching MTR's
 * bridge creators. The pillar columns reuse the pillar creator's logic and
 * therefore pass straight through the deck and girders they just built.
 */
public class ItemViaductCreator extends ItemNodeModifierSelectableBlockBase {
    /** Blocks of rail between pillar sets. */
    public static final int PILLAR_SPACING = 8;

    /** Deck width (3/5/7); deck spans ±width/2, pillars sit one block in from the edges. */
    public final int width;

    public ItemViaductCreator(int width) {
        super(true, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
        this.width = width;
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        BlockState savedState = getSavedState(stack);
        if (savedState == null || savedState.isAir()) {
            player.data.sendMessage(Text.translatable("msg.station_announcer.pillar.no_material"), true);
            return;
        }
        net.minecraft.block.BlockState state = savedState.data;
        ServerWorld world = player.getServerWorld().data;
        int edge = width / 2;

        // Pass 1: deck (one row below the rail) and edge girders (one below that).
        int[] deckBlocks = {0};
        RailBuildHelper.walk(rail.railMath, 0.15, 0.3, (center, perpendicular) -> {
            if (perpendicular == null) {
                return;
            }
            int deckY = (int) Math.floor(center.y) - 1;
            for (int offset = -edge; offset <= edge; offset++) {
                Vector cell = center.add(perpendicular.multiply(offset, 0, offset));
                if (RailBuildHelper.placeIfReplaceable(world, cell, deckY, state)) {
                    deckBlocks[0]++;
                }
                if (Math.abs(offset) == edge && RailBuildHelper.placeIfReplaceable(world, cell, deckY - 1, state)) {
                    deckBlocks[0]++; // girder under the deck edge
                }
            }
        });

        // Pass 2: pillars every PILLAR_SPACING blocks, one block in from the edges
        // (single centered pillar for the 3-wide viaduct). buildColumn's deck-skip
        // passes through the deck/girder placed above.
        int pillarOffset = Math.max(0, edge - 1);
        int[] pillars = {0};
        RailBuildHelper.walk(rail.railMath, PILLAR_SPACING / 2.0, PILLAR_SPACING, (center, perpendicular) -> {
            if (pillarOffset == 0 || perpendicular == null) {
                pillars[0] += RailBuildHelper.buildColumn(world, center, state);
                return;
            }
            pillars[0] += RailBuildHelper.buildColumn(world,
                    center.add(perpendicular.multiply(pillarOffset, 0, pillarOffset)), state);
            pillars[0] += RailBuildHelper.buildColumn(world,
                    center.add(perpendicular.multiply(-pillarOffset, 0, -pillarOffset)), state);
        });

        player.data.sendMessage(Text.translatable("msg.station_announcer.viaduct.built",
                deckBlocks[0], pillars[0]), true);
    }


    /** "Flashing" while armed: enchant glint between the first and second node click. */
    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.viaduct_creator", width, PILLAR_SPACING)
                .formatted(TextFormatting.GRAY));
        super.addTooltips(stack, world, tooltip, options); // saved material + usage hints
    }
}
