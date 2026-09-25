package com.stationannouncer.item;

import com.stationannouncer.block.ElStairUpperBlock;
import com.stationannouncer.block.SubwayStairBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Item for {@link ElStairUpperBlock}. Right-click in the air switches between
 * cream boards and wired glass. Click a stair tread (or an upper cell already
 * standing over it): the left / right third picks that edge, the middle both,
 * and the wall goes into the first cell above the stair that does not carry it
 * yet - so clicking the same tread again stacks the wall toward the ceiling.
 */
public class ElStairUpperItem extends BlockItem {
    private static final String KIND_KEY = "UpperKind";
    private static final int CLIMB = 8;

    public ElStairUpperItem(Block block, Settings settings) {
        super(block, settings);
    }

    public static ElStairUpperBlock.Kind selectedKind(ItemStack stack) {
        boolean glass = stack.hasNbt() && stack.getNbt().getBoolean(KIND_KEY);
        return glass ? ElStairUpperBlock.Kind.GLASS : ElStairUpperBlock.Kind.WALL;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        boolean glass = selectedKind(stack) != ElStairUpperBlock.Kind.GLASS;
        stack.getOrCreateNbt().putBoolean(KIND_KEY, glass);
        if (!world.isClient) {
            player.sendMessage(Text.translatable("gui.station_announcer.stair_upper_kind",
                    Text.translatable("gui.station_announcer.stair_upper_kind." + (glass ? "glass" : "wall"))), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        World world = context.getWorld();
        BlockPos clicked = context.getBlockPos().offset(context.getSide().getOpposite());
        BlockState target = world.getBlockState(clicked);
        boolean onStair = target.getBlock() instanceof SubwayStairBlock && context.getSide() == Direction.UP;
        boolean onUpper = target.getBlock() instanceof ElStairUpperBlock;
        if (!onStair && !onUpper) {
            return super.place(context);
        }
        Direction facing = onStair ? target.get(SubwayStairBlock.FACING) : target.get(ElStairUpperBlock.FACING);
        Direction right = facing.rotateYClockwise();
        Vec3d hit = context.getHitPos();
        double lateral = (hit.x - (clicked.getX() + 0.5)) * right.getOffsetX()
                + (hit.z - (clicked.getZ() + 0.5)) * right.getOffsetZ();
        boolean wantLeft = lateral < 0.17;
        boolean wantRight = lateral > -0.17;
        ElStairUpperBlock.Kind kind = selectedKind(context.getStack());
        BlockPos cell = onStair ? clicked.up() : clicked;
        for (int i = 0; i < CLIMB; i++, cell = cell.up()) {
            BlockState state = world.getBlockState(cell);
            BlockState next;
            if (state.getBlock() instanceof ElStairUpperBlock upper) {
                boolean addLeft = wantLeft && state.get(ElStairUpperBlock.LEFT) == ElStairUpperBlock.Kind.NONE;
                boolean addRight = wantRight && state.get(ElStairUpperBlock.RIGHT) == ElStairUpperBlock.Kind.NONE;
                if (!addLeft && !addRight) {
                    continue;                      // this cell already has it: climb
                }
                next = upper.compute(state.with(ElStairUpperBlock.LEFT, addLeft ? kind : state.get(ElStairUpperBlock.LEFT))
                        .with(ElStairUpperBlock.RIGHT, addRight ? kind : state.get(ElStairUpperBlock.RIGHT)), world, cell);
            } else if (state.isReplaceable()) {
                ElStairUpperBlock upper = (ElStairUpperBlock) getBlock();
                next = upper.compute(upper.getDefaultState().with(ElStairUpperBlock.FACING, facing)
                        .with(ElStairUpperBlock.LEFT, wantLeft ? kind : ElStairUpperBlock.Kind.NONE)
                        .with(ElStairUpperBlock.RIGHT, wantRight ? kind : ElStairUpperBlock.Kind.NONE), world, cell);
            } else {
                return ActionResult.FAIL;          // reached the ceiling
            }
            if (!world.isClient) {
                world.setBlockState(cell, next, Block.NOTIFY_ALL);
                BlockSoundGroup sound = next.getSoundGroup();
                world.playSound(null, cell, sound.getPlaceSound(), SoundCategory.BLOCKS,
                        (sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
                PlayerEntity player = context.getPlayer();
                if (player == null || !player.getAbilities().creativeMode) {
                    context.getStack().decrement(1);
                }
            }
            return ActionResult.success(world.isClient);
        }
        return ActionResult.FAIL;
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        boolean glass = selectedKind(stack) == ElStairUpperBlock.Kind.GLASS;
        tooltip.add(Text.translatable("gui.station_announcer.stair_upper_kind",
                Text.translatable("gui.station_announcer.stair_upper_kind." + (glass ? "glass" : "wall")))
                .formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("gui.station_announcer.stair_upper_hint").formatted(Formatting.DARK_GRAY));
    }
}
