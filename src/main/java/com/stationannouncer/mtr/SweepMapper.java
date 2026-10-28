package com.stationannouncer.mtr;

import com.stationannouncer.block.ElRun;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans a sweep: walks every world column {@link SweepGeometry} finds along
 * the track, looks the matching template column up, and decides per cell
 * what changes. The world is only READ (through {@link EarthworksBuilder.View}),
 * so the screen's preview runs exactly the same code over generated ground.
 *
 * <p>Rules (Thomas's defaults, STRUCTURE_SWEEP_PLAN.md §5):</p>
 * <ul>
 *   <li>everything follows the rail: heights are counted from the rail at that point;</li>
 *   <li>a solid template block goes in only where the world holds air, fluid,
 *       plants, snow or natural ground — never over someone's build, never
 *       over block entities or MTR / station blocks;</li>
 *   <li>template air digs out natural ground and plants according to
 *       {@link SweepSpec.AirMode}; structure void leaves the world alone;</li>
 *   <li>legs: a column whose template floor cell is solid carries that block
 *       down to real ground (skipped over a void, like the earthworks).</li>
 * </ul>
 */
public final class SweepMapper {
    public static final int MAX_CHANGES = 1_000_000;

    public record Result(LinkedHashMap<BlockPos, BlockState> changes, int placed, int cleared, int kept, int legs,
                         int copies, double stretch, boolean tooBig) {
    }

    private SweepMapper() {
    }

    /** A bridge-creator path (rail or line) as the geometry's path. */
    public static SweepGeometry.PathFn pathOf(BridgeBuilder.Path path) {
        return new SweepGeometry.PathFn() {
            @Override
            public double length() {
                return path.length();
            }

            @Override
            public double[] at(double distance) {
                Vector v = path.at(distance);
                return new double[]{v.x, v.y, v.z};
            }
        };
    }

    /** The template as this spec uses it (turned), plus the rail column / level it is pinned at. */
    public record Fit(SweepTemplate template, int railW, int railH) {
    }

    public static Fit fit(SweepTemplate t, SweepSpec spec) {
        SweepTemplate turned = t.rotated(spec.turn);
        int w = spec.railW >= 0 ? Math.min(spec.railW, turned.width - 1) : turned.pinW;
        int h = spec.railH >= 0 ? Math.min(spec.railH, turned.height) : turned.pinH;
        return new Fit(turned, w, h);
    }

    public static Result plan(EarthworksBuilder.View view, SweepGeometry.PathFn path, SweepTemplate template, SweepSpec spec) {
        spec.clamp();
        Fit fit = fit(template, spec);
        SweepTemplate t = fit.template();
        int railW = fit.railW(), railH = fit.railH();
        LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
        double length = path.length();
        int copies = spec.mode == SweepGeometry.Mode.STRETCH ? Math.max(1, (int) Math.round(length / t.length))
                : spec.mode == SweepGeometry.Mode.ONCE ? 1 : (int) Math.ceil(length / t.length);
        double stretch = spec.mode == SweepGeometry.Mode.STRETCH ? SweepGeometry.stretchFactor(length, t.length)
                : spec.mode == SweepGeometry.Mode.ONCE ? length / t.length : 1;
        // template column w sits at sideways offset (w − railW) (mirrored when flipped), shifted by sideOffset
        int uLo = -railW, uHi = t.width - 1 - railW;
        double lo, hi;
        if (spec.flip) {
            lo = spec.sideOffset - uHi - 0.5;
            hi = spec.sideOffset - uLo + 0.5;
        } else {
            lo = spec.sideOffset + uLo - 0.5;
            hi = spec.sideOffset + uHi + 0.5;
        }
        Map<Long, SweepGeometry.Column> columns = SweepGeometry.columns(path, lo, hi);
        List<SweepGeometry.Column> ordered = new ArrayList<>(columns.values());
        ordered.sort((a, b) -> a.s != b.s ? Double.compare(a.s, b.s) : Double.compare(a.u, b.u));

        int placed = 0, cleared = 0, kept = 0, legs = 0;
        int bottom = view.bottomY(), top = view.topY() - 1;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        List<BlockPos> leg = new ArrayList<>();
        for (SweepGeometry.Column c : ordered) {
            int ts = SweepGeometry.templateS(c.s, length, t.length, spec.mode, spec.phase);
            double rel = c.u - spec.sideOffset;
            int iu = SweepGeometry.templateU(spec.flip ? -rel : rel) + railW;
            if (iu < 0 || iu >= t.width) {
                continue;
            }
            int railY = (int) Math.floor(path.at(c.s)[1] + 1e-6);
            BlockRotation rotation = SweepTemplate.fromCanonical(SweepGeometry.quarter(c.tx, c.tz));
            for (int iv = 0; iv < t.height; iv++) {
                int v = iv - railH;
                int y = railY + v + spec.heightOffset;
                if (y < bottom || y > top) {
                    continue;
                }
                BlockState st = t.get(ts, iu, iv);
                if (st == null || st.isOf(Blocks.STRUCTURE_VOID)) {
                    continue;
                }
                cursor.set(c.x, y, c.z);
                BlockState w = view.get(cursor);
                if (st.isAir()) {
                    if (spec.air == SweepSpec.AirMode.NEVER || (spec.air == SweepSpec.AirMode.ABOVE_RAIL && v < 0)) {
                        continue;
                    }
                    if (!w.isAir() && clearable(w)) {
                        changes.put(cursor.toImmutable(), Blocks.AIR.getDefaultState());
                        cleared++;
                    }
                    continue;
                }
                BlockState oriented = orient(st, rotation, spec.flip, c);
                if (w.equals(oriented)) {
                    continue;
                }
                if (!EarthworksBuilder.untouchable(w) && buildable(w)) {
                    changes.put(cursor.toImmutable(), oriented);
                    placed++;
                } else {
                    kept++;
                }
            }
            // legs down to real ground under a solid template floor
            if (spec.legs) {
                BlockState floor = t.get(ts, iu, 0);
                if (floor != null && !floor.isAir() && !floor.isOf(Blocks.STRUCTURE_VOID)) {
                    BlockState oriented = orient(floor, rotation, spec.flip, c);
                    leg.clear();
                    boolean ground = false;
                    for (int y = railY - railH + spec.heightOffset - 1, d = 0; y >= bottom && d < spec.legDepth; y--, d++) {
                        cursor.set(c.x, y, c.z);
                        BlockState w = changes.containsKey(cursor) ? changes.get(cursor) : view.get(cursor);
                        if (EarthworksBuilder.fillable(w) || EarthworksBuilder.plant(w) || EarthworksBuilder.naturalLeaves(w)
                                || EarthworksBuilder.snowOrIce(w)) {
                            leg.add(cursor.toImmutable());
                        } else {
                            ground = true;
                            break;
                        }
                    }
                    if (ground) {
                        for (BlockPos p : leg) {
                            changes.put(p, oriented);
                        }
                        legs += leg.size();
                    }
                }
            }
            if (changes.size() > MAX_CHANGES) {
                return new Result(changes, placed, cleared, kept, legs, copies, stretch, true);
            }
        }
        return new Result(changes, placed, cleared, kept, legs, copies, stretch, false);
    }

    /** Free for a template block: air, fluids, plants, snow, natural ground and natural leaves. */
    static boolean buildable(BlockState w) {
        return EarthworksBuilder.fillable(w) || EarthworksBuilder.naturalTerrain(w) || EarthworksBuilder.plant(w)
                || EarthworksBuilder.naturalLeaves(w) || EarthworksBuilder.snowOrIce(w) || EarthworksBuilder.treeDecor(w);
    }

    /** What template air digs out: natural ground, fluids, plants, snow and natural leaves — never builds. */
    static boolean clearable(BlockState w) {
        if (EarthworksBuilder.untouchable(w)) {
            return false;
        }
        return EarthworksBuilder.naturalTerrain(w) || EarthworksBuilder.plant(w) || EarthworksBuilder.naturalLeaves(w)
                || EarthworksBuilder.snowOrIce(w) || EarthworksBuilder.treeDecor(w) || (w.isReplaceable() && !w.isAir());
    }

    // ================================================================ turning blocks

    /**
     * Turns a canonical (travel = east) template state to this column's
     * travel direction: our el runs follow the exact tangent (eight
     * directions), everything else the nearest quarter turn. Blocks that do
     * not implement {@code rotate} (most of ours) are turned property by
     * property.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState orient(BlockState st, BlockRotation rotation, boolean flip, SweepGeometry.Column c) {
        if (flip) {
            st = mirrorAny(st, SweepTemplate.FLIP);
        }
        boolean hasRun = false;
        for (Property<?> property : st.getProperties()) {
            if (property.getType() == ElRun.class) {
                hasRun = true;
                ElRun run = (ElRun) st.get(property);
                st = st.with((Property) property, runFor(run, c));
            }
        }
        if (hasRun) {
            return st;
        }
        return rotateAny(st, rotation);
    }

    /** A canonical el run (x = along travel, z = right of travel) in world terms at this column. */
    static ElRun runFor(ElRun run, SweepGeometry.Column c) {
        double sx = run.step().getX(), sz = run.step().getZ();
        double wx = c.tx * sx + c.px() * sz;
        double wz = c.tz * sx + c.pz() * sz;
        return ElRun.fromDirection(wx, wz);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState rotateAny(BlockState st, BlockRotation rotation) {
        if (rotation == BlockRotation.NONE) {
            return st;
        }
        BlockState turned = st.rotate(rotation);
        if (turned != st) {
            return turned;
        }
        for (Property<?> property : st.getProperties()) {
            Object value = st.get(property);
            if (property instanceof DirectionProperty dp && value instanceof Direction d && d.getAxis().isHorizontal()) {
                Direction nd = rotation.rotate(d);
                if (dp.getValues().contains(nd)) {
                    st = st.with(dp, nd);
                }
            } else if (property instanceof EnumProperty ep && value instanceof Direction.Axis axis
                    && axis != Direction.Axis.Y
                    && (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90)) {
                Direction.Axis other = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
                if (ep.getValues().contains(other)) {
                    st = st.with(ep, other);
                }
            }
        }
        return st;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState mirrorAny(BlockState st, BlockMirror mirror) {
        BlockState m = st.mirror(mirror);
        if (m != st) {
            return m;
        }
        for (Property<?> property : st.getProperties()) {
            Object value = st.get(property);
            if (property instanceof DirectionProperty dp && value instanceof Direction d && d.getAxis().isHorizontal()) {
                Direction nd = mirror.apply(d);
                if (dp.getValues().contains(nd)) {
                    st = st.with(dp, nd);
                }
            } else if (property.getType() == ElRun.class) {
                ElRun run = (ElRun) value;
                ElRun nr = run == ElRun.XZ ? ElRun.ZX : run == ElRun.ZX ? ElRun.XZ : run;
                st = st.with((Property) property, nr);
            }
        }
        return st;
    }
}
