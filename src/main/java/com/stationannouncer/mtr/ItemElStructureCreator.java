package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.ElDeckBlock;
import com.stationannouncer.block.ElGirderBlock;
import com.stationannouncer.block.ElRun;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.TooltipContext;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * El Structure Creator: click two connected rail nodes and the v2 el
 * structure is built under the rail between them - a ribbon of track deck
 * one block below the rail (cells turned to the nearest of the eight
 * directions, so curves are carried on the diagonal girders and decks), and
 * a bent every N blocks: street columns down to the ground, a cross girder
 * across the ribbon on the columns' caps, knee braces growing on their own.
 * Grades simply step the deck cell by cell. Deck width, bent spacing, column
 * style and whether to build the cross girders are set in the settings
 * screen (right-click in the air), kept in the item's NBT.
 */
public class ItemElStructureCreator extends ItemNodeModifierSelectableBlockBase {
    public static final int DEFAULT_WIDTH = 3;
    public static final int DEFAULT_SPACING = 8;

    public ItemElStructureCreator() {
        super(false, 0, 0, new ItemSettings(new net.minecraft.item.Item.Settings().maxCount(1)));
    }

    @Override
    protected void onConnect(Rail rail, ServerPlayerEntity player, ItemStack stack, int radius, int height) {
        int width = CreatorSettings.width(stack.data, DEFAULT_WIDTH);
        int spacing = CreatorSettings.spacing(stack.data, DEFAULT_SPACING);
        boolean lattice = CreatorSettings.lattice(stack.data);
        boolean girder = CreatorSettings.girder(stack.data);
        ServerWorld world = player.getServerWorld().data;
        int edge = width / 2;
        net.minecraft.block.Block column = lattice ? ModContent.EL_STREET_COLUMN_LATTICE : ModContent.EL_STREET_COLUMN;

        // Pass 1: the deck ribbon one block under the rail.
        Set<BlockPos> decked = new HashSet<>();
        int[] deckCells = {0};
        RailBuildHelper.walk(rail.railMath, 0.125, 0.25, (center, perpendicular) -> {
            if (perpendicular == null) {
                return;
            }
            ElRun across = ElRun.fromDirection(perpendicular.x, perpendicular.z);
            ElRun run = across.across();
            double cellStep = across.diagonal() ? Math.sqrt(2) : 1.0;
            int deckY = (int) Math.floor(center.y) - 1;
            for (int k = -edge; k <= edge; k++) {
                Vector cell = center.add(perpendicular.multiply(k * cellStep, 0, k * cellStep));
                BlockPos pos = new BlockPos((int) Math.floor(cell.x), deckY, (int) Math.floor(cell.z));
                if (decked.add(pos) && world.getBlockState(pos).isReplaceable()) {
                    world.setBlockState(pos, ModContent.EL_TRACK_DECK.getDefaultState().with(ElDeckBlock.AXIS, run), 3);
                    deckCells[0]++;
                }
            }
        });

        // Pass 2: bents. Columns first so the girder finds them and grows its braces.
        int[] bents = {0};
        RailBuildHelper.walk(rail.railMath, spacing / 2.0, spacing, (center, perpendicular) -> {
            if (perpendicular == null) {
                return;
            }
            ElRun across = ElRun.fromDirection(perpendicular.x, perpendicular.z);
            double cellStep = across.diagonal() ? Math.sqrt(2) : 1.0;
            int bentY = (int) Math.floor(center.y) - 2;
            int built = 0;
            if (edge == 0) {
                built += buildColumn(world, center, bentY - 1, column);
            } else {
                for (int sign : new int[]{-1, 1}) {
                    Vector top = center.add(perpendicular.multiply(sign * edge * cellStep, 0, sign * edge * cellStep));
                    built += buildColumn(world, top, bentY - 1, column);
                }
            }
            if (girder && edge > 0) {
                for (int k = -edge; k <= edge; k++) {
                    Vector cell = center.add(perpendicular.multiply(k * cellStep, 0, k * cellStep));
                    BlockPos pos = new BlockPos((int) Math.floor(cell.x), bentY, (int) Math.floor(cell.z));
                    if (world.getBlockState(pos).isReplaceable()) {
                        net.minecraft.block.BlockState state = ModContent.EL_GIRDER_PLATE.getDefaultState()
                                .with(ElGirderBlock.AXIS, across);
                        world.setBlockState(pos, ElGirderBlock.compute(state, world, pos), 3);
                    }
                }
            }
            if (built > 0) {
                bents[0]++;
            }
        });
        player.data.sendMessage(Text.translatable("msg.station_announcer.structure.built", deckCells[0], bents[0]), true);
    }

    /**
     * A street column from {@code topY} straight down to the first solid
     * block, top to bottom so each new block's UP is right at placement and
     * the neighbour update fixes DOWN as the next one lands.
     */
    static int buildColumn(ServerWorld world, Vector top, int topY, net.minecraft.block.Block column) {
        int x = (int) Math.floor(top.x);
        int z = (int) Math.floor(top.z);
        int placed = 0;
        for (int y = topY; y >= world.getBottomY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!world.getBlockState(pos).isReplaceable()) {
                break;
            }
            boolean above = world.getBlockState(pos.up()).getBlock() instanceof ColumnBlock;
            world.setBlockState(pos, column.getDefaultState().with(ColumnBlock.UP, above).with(ColumnBlock.DOWN, false), 3);
            placed++;
        }
        return placed > 0 ? 1 : 0;
    }

    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            MtrPillars.SETTINGS_OPENER.accept(hand.data);
        }
    }

    @Override
    public boolean hasGlint2(ItemStack stack) {
        net.minecraft.nbt.NbtCompound nbt = stack.data.getNbt();
        return (nbt != null && nbt.contains(TAG_POS)) || super.hasGlint2(stack);
    }

    @Override
    public void addTooltips(ItemStack stack, World world, List<MutableText> tooltip, TooltipContext options) {
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.structure_creator",
                CreatorSettings.width(stack.data, DEFAULT_WIDTH), CreatorSettings.spacing(stack.data, DEFAULT_SPACING))
                .formatted(TextFormatting.GRAY));
        tooltip.add(TextHelper.translatable("tooltip.station_announcer.creator.settings").formatted(TextFormatting.DARK_GRAY));
    }
}
