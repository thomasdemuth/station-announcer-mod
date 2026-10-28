package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.EarthworksBuilder;
import com.stationannouncer.mtr.SweepGeometry;
import com.stationannouncer.mtr.SweepMapper;
import com.stationannouncer.mtr.SweepSpec;
import com.stationannouncer.mtr.SweepTemplate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The client's picture of a sweep before it is built: the track picked with
 * the sweeper (the exact points the server will build along), the settings
 * being edited, and the planned blocks — computed with the SAME
 * {@link SweepMapper} the server uses, against the client's world, so the
 * ghost is what Build places.
 */
@Environment(EnvType.CLIENT)
public final class SweepPreview {
    public record Cell(BlockPos pos, BlockState state) {
    }

    private static SweepGeometry.Polyline track;
    private static SweepSpec spec;
    private static List<Cell> ghost = List.of();
    private static int placed, cleared, kept, copies;
    private static double stretch = 1;
    private static boolean tooBig;
    private static String key = "";
    private static int version;
    private static int trackId;

    private SweepPreview() {
    }

    public static SweepGeometry.Polyline track() {
        return track;
    }

    public static void setTrack(SweepGeometry.Polyline line) {
        track = line;
        trackId++;
        key = "";
    }

    public static void clearTrack() {
        track = null;
        ghost = List.of();
        trackId++;
        key = "";
        version++;
    }

    public static SweepSpec spec() {
        return spec;
    }

    /** The settings the ghost follows (the screen's working copy, kept after the screen closes). */
    public static void setSpec(SweepSpec s) {
        spec = s;
    }

    public static List<Cell> ghost() {
        return ghost;
    }

    public static int version() {
        return version;
    }

    /** One line summing up the planned sweep, or "" while there is nothing to show. */
    public static String summary() {
        if (track == null || spec == null) {
            return "";
        }
        SweepTemplate t = SweepClient.template(spec.template);
        if (spec.template.isEmpty()) {
            return "Pick a template";
        }
        if (t == null) {
            return SweepClient.unavailable(spec.template) ? "Template too big to preview" : "Loading template…";
        }
        if (tooBig) {
            return "Too big — over " + SweepMapper.MAX_CHANGES + " blocks";
        }
        StringBuilder b = new StringBuilder();
        b.append(copies).append(copies == 1 ? " copy" : " copies");
        if (spec.mode != SweepGeometry.Mode.REPEAT) {
            b.append(" · ").append(Math.round(stretch * 100)).append("% long");
        }
        b.append(" · ").append(placed).append(" blocks");
        if (cleared > 0) {
            b.append(" · dig ").append(cleared);
        }
        if (kept > 0) {
            b.append(" · keeps ").append(kept);
        }
        return b.toString();
    }

    /** Recomputes the plan when the track, settings or template changed. Render thread. */
    public static void update() {
        if (track == null || spec == null) {
            return;
        }
        SweepTemplate t = SweepClient.template(spec.template);
        String k = trackId + "|" + spec.toNbt() + "|" + SweepClient.templateVersion() + "|" + (t != null);
        if (k.equals(key)) {
            return;
        }
        key = k;
        version++;
        ghost = List.of();
        placed = cleared = kept = copies = 0;
        tooBig = false;
        ClientWorld world = MinecraftClient.getInstance().world;
        if (t == null || world == null) {
            return;
        }
        SweepMapper.Result r;
        try {
            r = SweepMapper.plan(new WorldView(world), track, t, spec.copy());
        } catch (RuntimeException e) {
            return;
        }
        List<Cell> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : r.changes().entrySet()) {
            if (!e.getValue().isAir()) {
                cells.add(new Cell(e.getKey(), e.getValue()));
            }
        }
        ghost = Collections.unmodifiableList(cells);
        placed = r.placed() + r.legs();
        cleared = r.cleared();
        kept = r.kept();
        copies = r.copies();
        stretch = r.stretch();
        tooBig = r.tooBig();
    }

    /** The client's world as the mapper's view (unloaded chunks read as air). */
    private record WorldView(ClientWorld world) implements EarthworksBuilder.View {
        @Override
        public BlockState get(BlockPos pos) {
            return world.getBlockState(pos);
        }

        @Override
        public int bottomY() {
            return world.getBottomY();
        }

        @Override
        public int topY() {
            return world.getTopY();
        }

        @Override
        public int surface(int x, int z) {
            return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        }
    }
}
