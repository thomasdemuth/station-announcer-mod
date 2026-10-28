package com.stationannouncer.mtr;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;

import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Structure Sweeper: turns a clicked rail (or a straight
 * line, for {@code /sweep line}) into a path running from the FIRST node
 * clicked to the second, plans the sweep with {@link SweepMapper} and hands
 * the changes to the shared earthworks applier (sliced per tick, one undo
 * per player, fences and stairs re-shaped at the end).
 */
public final class SweepService {
    private SweepService() {
    }

    /** A path walked backwards (so the template runs from the first node clicked). */
    record Reversed(SweepGeometry.PathFn path) implements SweepGeometry.PathFn {
        @Override
        public double length() {
            return path.length();
        }

        @Override
        public double[] at(double distance) {
            return path.at(path.length() - distance);
        }
    }

    /** Where a build's messages go: a player (chat / action bar) or a command source. */
    public interface Feedback {
        void send(Text text, boolean actionBar);

        static Feedback of(ServerPlayerEntity player) {
            return player::sendMessage;
        }
    }

    /** A track picked with the sweeper, waiting for Build (the same points the client previews). */
    record Pending(ServerWorld world, SweepGeometry.Polyline path) {
    }

    private static final java.util.Map<UUID, Pending> PENDING = new java.util.HashMap<>();
    /** Preview / build samples: every half block, at most this many points. */
    static final double SAMPLE_STEP = 0.5;
    static final int MAX_POINTS = 8001;

    /** The rail between two clicked nodes as a path running from the first node clicked. */
    static SweepGeometry.PathFn railPath(Rail rail, BlockPos firstNode) {
        SweepGeometry.PathFn path = SweepMapper.pathOf(new BridgeService.RailPath(rail.railMath));
        if (firstNode != null) {
            double[] a = path.at(0);
            double[] b = path.at(path.length());
            if (distance(b, firstNode) < distance(a, firstNode)) {
                path = new Reversed(path);
            }
        }
        return path;
    }

    /** Two nodes clicked: remember the track and send it to the client for the preview. */
    public static void pickRail(ServerPlayerEntity player, Rail rail, BlockPos firstNode) {
        if (!Sweep.allowed(player)) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.not_allowed"), true);
            return;
        }
        SweepGeometry.PathFn path = railPath(rail, firstNode);
        if (path.length() / SAMPLE_STEP + 1 > MAX_POINTS) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.track_too_long",
                    (int) ((MAX_POINTS - 1) * SAMPLE_STEP)), true);
            return;
        }
        SweepGeometry.Polyline line = SweepGeometry.Polyline.sample(path, SAMPLE_STEP);
        PENDING.put(player.getUuid(), new Pending(player.getServerWorld(), line));
        Sweep.sendTrack(player, line);
    }

    public static void cancelPending(UUID owner) {
        PENDING.remove(owner);
    }

    /** Build pressed in the screen: sweep along the picked track with these settings. */
    public static void buildPending(ServerPlayerEntity player, SweepSpec spec) {
        if (!Sweep.allowed(player)) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.not_allowed"), true);
            return;
        }
        Pending pending = PENDING.get(player.getUuid());
        if (pending == null || pending.world() != player.getServerWorld()) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.no_track"), true);
            return;
        }
        if (build(player.getUuid(), pending.world(), spec, pending.path(), Feedback.of(player), player.getName().getString())) {
            PENDING.remove(player.getUuid());
        }
    }

    public static void buildFromRail(ServerPlayerEntity player, ItemStack stack, Rail rail, BlockPos firstNode) {
        if (!Sweep.allowed(player)) {
            player.sendMessage(Text.translatable("msg.station_announcer.sweep.not_allowed"), true);
            return;
        }
        buildFromRail(player.getUuid(), player.getServerWorld(), SweepSpec.read(stack), rail, firstNode,
                Feedback.of(player), player.getName().getString());
    }

    public static void buildFromRail(UUID owner, ServerWorld world, SweepSpec spec, Rail rail, BlockPos firstNode,
                                     Feedback feedback, String who) {
        build(owner, world, spec, railPath(rail, firstNode), feedback, who);
    }

    public static void buildLine(UUID owner, ServerWorld world, SweepSpec spec, BlockPos from, BlockPos to,
                                 Feedback feedback, String who) {
        SweepGeometry.PathFn path = SweepMapper.pathOf(new BridgeBuilder.LinePath(
                new Vector(from.getX() + 0.5, from.getY(), from.getZ() + 0.5),
                new Vector(to.getX() + 0.5, to.getY(), to.getZ() + 0.5)));
        build(owner, world, spec, path, feedback, who);
    }

    private static double distance(double[] p, BlockPos pos) {
        double dx = p[0] - (pos.getX() + 0.5), dy = p[1] - pos.getY(), dz = p[2] - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Plans and queues a sweep; true when something was queued. */
    static boolean build(UUID owner, ServerWorld world, SweepSpec spec, SweepGeometry.PathFn path, Feedback feedback, String who) {
        if (EarthworksService.isBusy(owner)) {
            feedback.send(Text.translatable("msg.station_announcer.earthworks.busy"), true);
            return false;
        }
        SweepTemplate template = SweepTemplateStore.get(spec.template);
        if (template == null) {
            feedback.send(Text.translatable(spec.template.isEmpty()
                    ? "msg.station_announcer.sweep.no_template" : "msg.station_announcer.sweep.missing_template", spec.template), true);
            return false;
        }
        long started = System.nanoTime();
        SweepMapper.Result result;
        try {
            result = SweepMapper.plan(new EarthworksService.WorldView(world), path, template, spec);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.error("Sweep: planning failed", t);
            feedback.send(Text.translatable("msg.station_announcer.earthworks.failed"), true);
            return false;
        }
        if (result.tooBig()) {
            feedback.send(Text.translatable("msg.station_announcer.earthworks.too_big", SweepMapper.MAX_CHANGES), false);
            return false;
        }
        Map<BlockPos, BlockState> changes = result.changes();
        if (changes.isEmpty()) {
            feedback.send(Text.translatable("msg.station_announcer.sweep.nothing", template.name), true);
            return false;
        }
        StringBuilder summary = new StringBuilder(Text.translatable("msg.station_announcer.sweep.done", template.name,
                result.placed() + result.legs(), result.cleared(), result.copies()).getString());
        if (spec.mode == SweepGeometry.Mode.STRETCH && Math.abs(result.stretch() - 1) > 0.005) {
            summary.append(" · ").append(Text.translatable("msg.station_announcer.sweep.stretched",
                    Math.round(result.stretch() * 100)).getString());
        }
        if (result.kept() > 0) {
            summary.append(" · ").append(Text.translatable("msg.station_announcer.earthworks.kept", result.kept()).getString());
        }
        StationAnnouncer.LOGGER.info("Sweep: {} planned '{}' along {} blocks: {} placed, {} legs, {} cleared, {} kept, {} copies, stretch {} in {} ms",
                who, template.name, String.format(java.util.Locale.ROOT, "%.1f", path.length()),
                result.placed(), result.legs(), result.cleared(), result.kept(), result.copies(),
                String.format(java.util.Locale.ROOT, "%.3f", result.stretch()), (System.nanoTime() - started) / 1_000_000);
        EarthworksService.submit(owner, world, changes, summary.toString(), true);
        return true;
    }
}
