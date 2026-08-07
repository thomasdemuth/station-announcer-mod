package com.stationannouncer.item;

import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.PaDisplay;
import com.stationannouncer.block.PaDisplayBlock;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.config.ServerConfig;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Handheld linking tool for the PA network. Linking works in both directions —
 * select either end first:
 * <ul>
 *   <li>Right-click a PA Control Box or a Speaker — select it (remembered in
 *       item NBT). Clicking another block of the same kind reselects.</li>
 *   <li>With a Control Box selected, right-click Speakers to link each of them
 *       to it (the box stays selected for linking many speakers).</li>
 *   <li>With a Speaker selected, right-click a Control Box to link that
 *       speaker to it (the selection is then cleared).</li>
 *   <li>Sneak-right-click a Speaker — unlink it.</li>
 *   <li>Sneak-right-click air — clear the selection.</li>
 * </ul>
 * Linking a speaker that already belongs to another box moves it (both boxes
 * update). All logic runs on the server; distance limit comes from the server
 * config ({@code maxLinkDistance}, default 128 blocks, same dimension only).
 */
public class SpeakerLinkItem extends Item {
    private static final String NBT_SELECTED_POS = "SelectedPos";
    private static final String NBT_SELECTED_DIM = "SelectedDim";
    private static final String NBT_SELECTED_TYPE = "SelectedType";
    private static final String TYPE_BOX = "box";
    private static final String TYPE_SPEAKER = "speaker";
    private static final String TYPE_DISPLAY = "display";
    /** Pre-1.2.1 selections stored the box position under this key. */
    private static final String NBT_LEGACY_BOX = "SelectedBox";

    private record Selection(BlockPos pos, String dimension, String type) {
    }

    public SpeakerLinkItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return ActionResult.PASS;
        }
        // Multi-part displays (PIDS): route the click to the data-holding part.
        if (world.getBlockState(pos).getBlock() instanceof PaDisplayBlock displayBlock) {
            pos = displayBlock.paDataPos(world, pos);
        }
        // Peek at the target type on both sides so the client's result matches.
        boolean isBox = world.getBlockEntity(pos) instanceof ControlBoxBlockEntity;
        boolean isSpeaker = world.getBlockEntity(pos) instanceof SpeakerBlockEntity;
        boolean isDisplay = world.getBlockEntity(pos) instanceof PaDisplay;
        if (!isBox && !isSpeaker && !isDisplay) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            ItemStack stack = context.getStack();
            Selection selection = getSelection(stack);
            boolean selectionHere = selection != null && sameDimension(selection, world);
            if (isSpeaker && player.isSneaking()) {
                unlinkSpeaker(world, pos, player);
            } else if (isDisplay && player.isSneaking()) {
                unlinkDisplay(world, pos, player);
            } else if (isBox && selectionHere && TYPE_SPEAKER.equals(selection.type())) {
                // Speaker-first flow: link the remembered speaker to this box.
                if (linkPair(world, player, selection.pos(), pos)) {
                    clearSelection(stack);
                }
            } else if (isBox && selectionHere && TYPE_DISPLAY.equals(selection.type())) {
                // Display-first flow: link the remembered display to this box.
                if (linkDisplayPair(world, player, selection.pos(), pos)) {
                    clearSelection(stack);
                }
            } else if (isSpeaker && selectionHere && TYPE_BOX.equals(selection.type())) {
                // Box-first flow: link this speaker; the box stays selected.
                linkPair(world, player, pos, selection.pos());
            } else if (isDisplay && selectionHere && TYPE_BOX.equals(selection.type())) {
                // Box-first flow: link this display; the box stays selected.
                linkDisplayPair(world, player, pos, selection.pos());
            } else {
                select(stack, world, pos, player, isBox ? TYPE_BOX : isSpeaker ? TYPE_SPEAKER : TYPE_DISPLAY);
            }
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!player.isSneaking()) {
            return TypedActionResult.pass(stack);
        }
        if (!world.isClient) {
            if (getSelection(stack) != null) {
                clearSelection(stack);
                feedback(player, Text.translatable("msg.station_announcer.link.cleared"));
            } else {
                feedback(player, Text.translatable("msg.station_announcer.link.no_selection"));
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    // -------------------------------------------------------------- actions

    private static void select(ItemStack stack, World world, BlockPos pos, PlayerEntity player, String type) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.remove(NBT_LEGACY_BOX);
        nbt.putLong(NBT_SELECTED_POS, pos.asLong());
        nbt.putString(NBT_SELECTED_DIM, world.getRegistryKey().getValue().toString());
        nbt.putString(NBT_SELECTED_TYPE, type);
        String key = switch (type) {
            case TYPE_BOX -> "msg.station_announcer.link.selected";
            case TYPE_DISPLAY -> "msg.station_announcer.link.selected_display";
            default -> "msg.station_announcer.link.selected_speaker";
        };
        feedback(player, Text.translatable(key, pos.toShortString()));
    }

    /**
     * Links the speaker at {@code speakerPos} to the control box at
     * {@code boxPos}, whichever end was clicked. @return true on success.
     */
    private static boolean linkPair(World world, PlayerEntity player, BlockPos speakerPos, BlockPos boxPos) {
        int maxDistance = ServerConfig.get().maxLinkDistance;
        if (speakerPos.getSquaredDistance(Vec3d.ofCenter(boxPos)) > (double) maxDistance * maxDistance) {
            feedback(player, Text.translatable("msg.station_announcer.link.too_far", maxDistance));
            return false;
        }
        if (!world.isChunkLoaded(boxPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.box_not_loaded", boxPos.toShortString()));
            return false;
        }
        if (!(world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box)) {
            feedback(player, Text.translatable("msg.station_announcer.link.box_missing"));
            return false;
        }
        if (!world.isChunkLoaded(speakerPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.speaker_not_loaded", speakerPos.toShortString()));
            return false;
        }
        if (!(world.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
            feedback(player, Text.translatable("msg.station_announcer.link.speaker_missing"));
            return false;
        }

        BlockPos oldBoxPos = speaker.getControlBoxPos();
        if (boxPos.equals(oldBoxPos) && box.hasSpeaker(speakerPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.already_linked", boxPos.toShortString()));
            return false;
        }
        box.addSpeaker(speakerPos);
        if (!box.hasSpeaker(speakerPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.limit", ControlBoxBlockEntity.MAX_SPEAKERS));
            return false;
        }
        boolean replaced = oldBoxPos != null && !oldBoxPos.equals(boxPos);
        if (replaced && world.isChunkLoaded(oldBoxPos)
                && world.getBlockEntity(oldBoxPos) instanceof ControlBoxBlockEntity oldBox
                && oldBox.removeSpeaker(speakerPos)) {
            oldBox.sync();
        }
        box.sync();
        speaker.setControlBoxPos(boxPos);
        feedback(player, Text.translatable(
                replaced ? "msg.station_announcer.link.relinked" : "msg.station_announcer.link.linked",
                boxPos.toShortString(), box.getSpeakerCount()));
        return true;
    }

    /**
     * Links the display at {@code displayPos} to the control box at
     * {@code boxPos}, whichever end was clicked. @return true on success.
     */
    private static boolean linkDisplayPair(World world, PlayerEntity player, BlockPos displayPos, BlockPos boxPos) {
        int maxDistance = ServerConfig.get().maxLinkDistance;
        if (displayPos.getSquaredDistance(Vec3d.ofCenter(boxPos)) > (double) maxDistance * maxDistance) {
            feedback(player, Text.translatable("msg.station_announcer.link.too_far", maxDistance));
            return false;
        }
        if (!world.isChunkLoaded(boxPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.box_not_loaded", boxPos.toShortString()));
            return false;
        }
        if (!(world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box)) {
            feedback(player, Text.translatable("msg.station_announcer.link.box_missing"));
            return false;
        }
        if (!world.isChunkLoaded(displayPos)
                || !(world.getBlockEntity(displayPos) instanceof PaDisplay display)) {
            feedback(player, Text.translatable("msg.station_announcer.link.display_missing"));
            return false;
        }

        BlockPos oldBoxPos = display.getPaControlBoxPos();
        if (boxPos.equals(oldBoxPos) && box.hasDisplay(displayPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.display_already_linked", boxPos.toShortString()));
            return false;
        }
        box.addDisplay(displayPos);
        if (!box.hasDisplay(displayPos)) {
            feedback(player, Text.translatable("msg.station_announcer.link.limit", ControlBoxBlockEntity.MAX_SPEAKERS));
            return false;
        }
        boolean replaced = oldBoxPos != null && !oldBoxPos.equals(boxPos);
        if (replaced && world.isChunkLoaded(oldBoxPos)
                && world.getBlockEntity(oldBoxPos) instanceof ControlBoxBlockEntity oldBox
                && oldBox.removeDisplay(displayPos)) {
            oldBox.sync();
        }
        box.sync();
        display.setPaControlBoxPos(boxPos);
        syncDisplay(world, displayPos);
        feedback(player, Text.translatable(
                replaced ? "msg.station_announcer.link.display_relinked" : "msg.station_announcer.link.display_linked",
                boxPos.toShortString(), box.getDisplayCount()));
        return true;
    }

    private static void unlinkDisplay(World world, BlockPos displayPos, PlayerEntity player) {
        if (!(world.getBlockEntity(displayPos) instanceof PaDisplay display)) {
            return;
        }
        BlockPos boxPos = display.getPaControlBoxPos();
        if (boxPos == null) {
            feedback(player, Text.translatable("msg.station_announcer.link.display_not_linked"));
            return;
        }
        if (world.isChunkLoaded(boxPos)
                && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box
                && box.removeDisplay(displayPos)) {
            box.sync();
        }
        display.setPaControlBoxPos(null);
        syncDisplay(world, displayPos);
        feedback(player, Text.translatable("msg.station_announcer.link.display_unlinked"));
    }

    /** Pushes a display block entity's changed link state to tracking clients. */
    private static void syncDisplay(World world, BlockPos displayPos) {
        if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            serverWorld.getChunkManager().markForUpdate(displayPos);
        }
    }

    private static void unlinkSpeaker(World world, BlockPos speakerPos, PlayerEntity player) {
        if (!(world.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
            return;
        }
        BlockPos boxPos = speaker.getControlBoxPos();
        if (boxPos == null) {
            feedback(player, Text.translatable("msg.station_announcer.link.not_linked"));
            return;
        }
        if (world.isChunkLoaded(boxPos)
                && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box
                && box.removeSpeaker(speakerPos)) {
            box.sync();
        }
        speaker.clearControlBox();
        feedback(player, Text.translatable("msg.station_announcer.link.unlinked"));
    }

    // -------------------------------------------------------------- helpers

    @Nullable
    private static Selection getSelection(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) {
            return null;
        }
        if (nbt.contains(NBT_SELECTED_POS)) {
            return new Selection(BlockPos.fromLong(nbt.getLong(NBT_SELECTED_POS)),
                    nbt.getString(NBT_SELECTED_DIM), nbt.getString(NBT_SELECTED_TYPE));
        }
        if (nbt.contains(NBT_LEGACY_BOX)) { // selection made by an older mod version
            return new Selection(BlockPos.fromLong(nbt.getLong(NBT_LEGACY_BOX)),
                    nbt.getString(NBT_SELECTED_DIM), TYPE_BOX);
        }
        return null;
    }

    private static boolean sameDimension(Selection selection, World world) {
        return world.getRegistryKey().getValue().toString().equals(selection.dimension());
    }

    private static void clearSelection(ItemStack stack) {
        stack.removeSubNbt(NBT_SELECTED_POS);
        stack.removeSubNbt(NBT_SELECTED_DIM);
        stack.removeSubNbt(NBT_SELECTED_TYPE);
        stack.removeSubNbt(NBT_LEGACY_BOX);
    }

    private static void feedback(PlayerEntity player, Text message) {
        player.sendMessage(message, true);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        Selection selection = getSelection(stack);
        if (selection != null) {
            String key = switch (selection.type()) {
                case TYPE_SPEAKER -> "tooltip.station_announcer.speaker_link.selected_speaker";
                case TYPE_DISPLAY -> "tooltip.station_announcer.speaker_link.selected_display";
                default -> "tooltip.station_announcer.speaker_link.selected";
            };
            tooltip.add(Text.translatable(key, selection.pos().toShortString()).formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.station_announcer.speaker_link.hint")
                    .formatted(Formatting.GRAY));
        }
    }
}
