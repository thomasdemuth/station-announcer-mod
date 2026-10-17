package com.stationannouncer.client.track;

import com.stationannouncer.track.TrackPath;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the PARALLEL COMPANIONS of a rail in MTR's client data: the other tracks of a double /
 * triple line that run beside it between parallel-offset nodes.
 *
 * A rail with canonical nodes A, B (travel headings hA leaving A, hB arriving at B) has a companion
 * at lateral offset d (+ = right of A→B) when some rail joins A′ ≈ A + right(hA)·d and
 * B′ ≈ B + right(hB)·d (both within {@link #TOLERANCE} blocks, the same d at both ends,
 * 0.5 ≤ |d| ≤ {@link #MAX_OFFSET}) and runs along the same headings (either way round).
 */
@Environment(EnvType.CLIENT)
final class TrackEditorParallels {
    static final double TOLERANCE = 0.3;
    static final double MAX_OFFSET = 12;
    private static final double MAX_DY = 4;

    /** A companion: its rail, the node of it that pairs with the source's node A, the offset, and the direction. */
    record Companion(Rail rail, Position nearA, double lateral, boolean reversed) {
    }

    private TrackEditorParallels() {
    }

    /**
     * @param nodes      the source's canonical nodes {ax, ay, az, bx, by, bz}
     * @param headingA   travel heading leaving A
     * @param headingB   travel heading arriving at B
     * @param skipKeys   rail hex ids to ignore (the tracks already open in the editor)
     */
    static List<Companion> find(long[] nodes, double headingA, double headingB, Set<String> skipKeys) {
        final List<Companion> result = new ArrayList<>();
        final MinecraftClientData data = MinecraftClientData.getInstance();
        if (data == null) {
            return result;
        }
        final double fax = Math.cos(headingA);
        final double faz = Math.sin(headingA);
        final double rax = -faz;
        final double raz = fax;
        final double fbx = Math.cos(headingB);
        final double fbz = Math.sin(headingB);
        final double rbx = -fbz;
        final double rbz = fbx;
        for (final Map.Entry<Position, Object2ObjectOpenHashMap<Position, Rail>> entry : data.positionsToRail.entrySet()) {
            final Position p = entry.getKey();
            final double dx = p.getX() - nodes[0];
            final double dz = p.getZ() - nodes[2];
            if (Math.abs(dx) > MAX_OFFSET + 1 || Math.abs(dz) > MAX_OFFSET + 1 || Math.abs(p.getY() - nodes[1]) > MAX_DY) {
                continue;
            }
            final double along = dx * fax + dz * faz;
            final double lateral = dx * rax + dz * raz;
            if (Math.abs(along) > TOLERANCE || Math.abs(lateral) < 0.5 || Math.abs(lateral) > MAX_OFFSET) {
                continue;
            }
            for (final Map.Entry<Position, Rail> other : entry.getValue().entrySet()) {
                final Position q = other.getKey();
                final Rail rail = other.getValue();
                if (rail == null || skipKeys.contains(rail.getHexId())) {
                    continue;
                }
                final double ex = q.getX() - nodes[3];
                final double ez = q.getZ() - nodes[5];
                if (Math.abs(q.getY() - nodes[4]) > MAX_DY) {
                    continue;
                }
                final double alongB = ex * fbx + ez * fbz;
                final double lateralB = ex * rbx + ez * rbz;
                if (Math.abs(alongB) > TOLERANCE || Math.abs(lateralB - lateral) > TOLERANCE) {
                    continue;
                }
                final long[] own = com.stationannouncer.mtr.track.TrackRails.access(rail).stationannouncer$nodes();
                final boolean reversed = !(own[0] == p.getX() && own[1] == p.getY() && own[2] == p.getZ());
                result.add(new Companion(rail, p, (lateral + lateralB) / 2, reversed));
            }
        }
        result.sort((a, b) -> Double.compare(Math.abs(a.lateral()), Math.abs(b.lateral())));
        return result;
    }

    /** True when a companion's own headings run along the source's (same way, or the other way when reversed). */
    static boolean headingsParallel(double srcA, double srcB, double compA, double compB, boolean reversed) {
        final double a = reversed ? TrackPath.wrap(compA - (srcB + Math.PI)) : TrackPath.wrap(compA - srcA);
        final double b = reversed ? TrackPath.wrap(compB - (srcA + Math.PI)) : TrackPath.wrap(compB - srcB);
        return Math.abs(a) < 0.02 && Math.abs(b) < 0.02;
    }
}
