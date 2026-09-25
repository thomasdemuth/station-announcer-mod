package com.stationannouncer.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Dev-rig only (registered in the development environment, never in a shipped
 * jar's normal play): runs a REAL right-click through the vanilla interaction
 * path as a named player, so item placement logic (getPlacementState, the
 * course items' "far third" rules, sneak variants) can be exercised from RCON
 * without a mouse.
 *
 * <pre>/rigplace &lt;player&gt; &lt;item&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;face&gt; &lt;hx&gt; &lt;hy&gt; &lt;hz&gt; &lt;yaw&gt; &lt;pitch&gt; &lt;sneak&gt;</pre>
 * (x y z) = the clicked block, (hx hy hz) = hit point inside it (0..1),
 * yaw/pitch = where the player looks (placement "look" fallbacks read it).
 */
public final class RigPlaceCommand {
    private RigPlaceCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("rigplace")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                        .then(CommandManager.argument("item", StringArgumentType.string())
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("face", StringArgumentType.word())
                        .then(CommandManager.argument("hx", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("hy", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("hz", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("yaw", FloatArgumentType.floatArg())
                        .then(CommandManager.argument("pitch", FloatArgumentType.floatArg())
                        .then(CommandManager.argument("sneak", BoolArgumentType.bool())
                                .executes(RigPlaceCommand::run)))))))))))))));
    }

    /** /rigstate x y z : prints the block state (dev rig probe). */
    public static void registerProbe() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("rigstate").requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("pos", net.minecraft.command.argument.BlockPosArgumentType.blockPos())
                                .executes(c -> {
                                    BlockPos p = net.minecraft.command.argument.BlockPosArgumentType.getLoadedBlockPos(c, "pos");
                                    String st = c.getSource().getWorld().getBlockState(p).toString();
                                    c.getSource().sendFeedback(() -> Text.literal(st), false);
                                    return 1;
                                }))));
    }

    private static int run(CommandContext<ServerCommandSource> c) {
        ServerCommandSource src = c.getSource();
        ServerPlayerEntity player = src.getServer().getPlayerManager()
                .getPlayer(StringArgumentType.getString(c, "player"));
        if (player == null) {
            src.sendError(Text.literal("no such player"));
            return 0;
        }
        Identifier id = Identifier.tryParse(StringArgumentType.getString(c, "item"));
        Item item = id == null ? null : Registries.ITEM.get(id);
        Direction face = Direction.byName(StringArgumentType.getString(c, "face"));
        if (item == null || face == null) {
            src.sendError(Text.literal("bad item or face"));
            return 0;
        }
        BlockPos pos = BlockPos.ofFloored(DoubleArgumentType.getDouble(c, "x"),
                DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"));
        Vec3d hit = new Vec3d(pos.getX() + DoubleArgumentType.getDouble(c, "hx"),
                pos.getY() + DoubleArgumentType.getDouble(c, "hy"),
                pos.getZ() + DoubleArgumentType.getDouble(c, "hz"));
        float yaw = FloatArgumentType.getFloat(c, "yaw");
        float pitch = FloatArgumentType.getFloat(c, "pitch");
        boolean sneak = BoolArgumentType.getBool(c, "sneak");

        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(pitch);
        player.setSneaking(sneak);
        ItemStack stack = new ItemStack(item);
        player.setStackInHand(Hand.MAIN_HAND, stack);
        ActionResult r = player.interactionManager.interactBlock(player, player.getServerWorld(), stack,
                Hand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        player.setSneaking(false);
        src.sendFeedback(() -> Text.literal("rigplace " + id + " -> " + r), false);
        return 1;
    }
}
