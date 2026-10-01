package com.stationannouncer.mtr;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

/**
 * DEV-ONLY (registered in the development environment only): runs the curved
 * platform builder along a synthetic MTR {@link RailMath} — the rail geometry
 * MTR itself would compute between two nodes — so the edge can be judged on
 * the headless rig without laying real track. {@code /rigcurve build <player>
 * x1 y z1 angle1 x2 z2 angle2} (angles are MTR's compass names: E, SE, S…),
 * {@code /rigcurve undo <player>}.
 */
final class CurvedPlatformDevCommand {
    private CurvedPlatformDevCommand() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("rigcurve").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("undo")
                                .then(CommandManager.argument("player", StringArgumentType.word()).executes(c -> {
                                    ServerPlayerEntity player = c.getSource().getServer().getPlayerManager()
                                            .getPlayer(StringArgumentType.getString(c, "player"));
                                    if (player != null) {
                                        CurvedPlatforms.undo(player.getServerWorld(), player);
                                    }
                                    return 1;
                                })))
                        .then(CommandManager.literal("build")
                                .then(CommandManager.argument("player", StringArgumentType.word())
                                .then(CommandManager.argument("x1", IntegerArgumentType.integer())
                                .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z1", IntegerArgumentType.integer())
                                .then(CommandManager.argument("a1", StringArgumentType.word())
                                .then(CommandManager.argument("x2", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z2", IntegerArgumentType.integer())
                                .then(CommandManager.argument("a2", StringArgumentType.word())
                                .then(CommandManager.argument("mode", IntegerArgumentType.integer(0, 2)).executes(c -> {
                                    ServerPlayerEntity player = c.getSource().getServer().getPlayerManager()
                                            .getPlayer(StringArgumentType.getString(c, "player"));
                                    if (player == null) {
                                        return 0;
                                    }
                                    int y = IntegerArgumentType.getInteger(c, "y");
                                    RailMath railMath = new RailMath(
                                            new Position(IntegerArgumentType.getInteger(c, "x1"), y, IntegerArgumentType.getInteger(c, "z1")),
                                            Angle.valueOf(StringArgumentType.getString(c, "a1")),
                                            new Position(IntegerArgumentType.getInteger(c, "x2"), y, IntegerArgumentType.getInteger(c, "z2")),
                                            Angle.valueOf(StringArgumentType.getString(c, "a2")),
                                            Rail.Shape.QUADRATIC, 0);
                                    Vector mid = railMath.getPosition(railMath.getLength() / 2, false);
                                    c.getSource().sendFeedback(() -> Text.literal(String.format("rigcurve length=%.2f mid=%.2f,%.2f,%.2f",
                                            railMath.getLength(), mid.x, mid.y, mid.z)), false);
                                    CurvedPlatforms.build(player.getServerWorld(), railMath, player,
                                            IntegerArgumentType.getInteger(c, "mode"));
                                    return 1;
                                })))))))))))));
    }
}
