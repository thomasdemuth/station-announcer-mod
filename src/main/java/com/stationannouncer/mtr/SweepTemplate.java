package com.stationannouncer.mtr;

import com.stationannouncer.block.ElRun;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure captured for the Structure Sweeper: just a BOX of blocks, in a
 * canonical frame — {@code s} (length, 0 … L−1) runs EAST, {@code w}
 * (0 … W−1, + = right of travel) runs SOUTH, {@code h} (0 … H−1) runs up.
 * The longer horizontal side of the captured box becomes the length. Block
 * states are turned into that frame at capture so stairs, logs and doors keep
 * facing the right way.
 *
 * <p>Where the rail sits in the box is NOT part of the capture any more
 * (Thomas, 2026-10-22: "select any structure, then pin it to the rails"):
 * {@link #pinW}/{@link #pinH} are only the SUGGESTED rail column and rail
 * level — an MTR block inside the box (a rail node of the test track) wins,
 * else the centre column's deck top — and the sweeper can override both, and
 * turn the whole template ({@link #rotated}).</p>
 *
 * <p>{@link Blocks#STRUCTURE_VOID} means "leave the world alone here"; MTR
 * blocks are captured as void, never copied.</p>
 */
public final class SweepTemplate {
    public static final int MAX_LENGTH = 128;
    public static final int MAX_WIDTH = 64;
    public static final int MAX_HEIGHT = 96;
    public static final BlockState VOID = Blocks.STRUCTURE_VOID.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    public final String name;
    public final String author;
    public final long created;
    public final int length;
    public final int width;
    public final int height;
    /** Suggested rail column (0 … W−1) and rail level (0 … H, the rail runs in this layer). */
    public final int pinW;
    public final int pinH;
    final List<BlockState> palette;
    final int[] cells;

    SweepTemplate(String name, String author, long created, int length, int width, int height,
                  List<BlockState> palette, int[] cells, int pinW, int pinH) {
        this.name = name;
        this.author = author;
        this.created = created;
        this.length = length;
        this.width = width;
        this.height = height;
        this.palette = palette;
        this.cells = cells;
        if (pinW < 0 || pinH < 0) {
            int[] auto = autoPins();
            pinW = auto[0];
            pinH = auto[1];
        }
        this.pinW = Math.max(0, Math.min(width - 1, pinW));
        this.pinH = Math.max(0, Math.min(height, pinH));
    }

    private int index(int s, int w, int h) {
        return (s * width + w) * height + h;
    }

    /** The block at template cell (s, w, h); null outside the box. */
    public BlockState get(int s, int w, int h) {
        if (s < 0 || s >= length || w < 0 || w >= width || h < 0 || h >= height) {
            return null;
        }
        return palette.get(cells[index(s, w, h)]);
    }

    private static boolean solid(BlockState s) {
        return s != null && !s.isAir() && !s.isOf(Blocks.STRUCTURE_VOID);
    }

    /** Solid (non-air, non-void) cells — the "blocks" count shown in lists. */
    public int solidCount() {
        int n = 0;
        boolean[] isSolid = new boolean[palette.size()];
        for (int i = 0; i < isSolid.length; i++) {
            isSolid[i] = solid(palette.get(i));
        }
        for (int c : cells) {
            if (isSolid[c]) {
                n++;
            }
        }
        return n;
    }

    /**
     * Suggested rail position: the median MTR (void) cell if the box held a
     * rail node; otherwise the centre column, on top of its most common
     * "floor" (a solid cell with open air above it — a viaduct deck, a tunnel
     * floor), else on top of its most common top block.
     */
    private int[] autoPins() {
        List<Integer> vw = new ArrayList<>(), vh = new ArrayList<>();
        for (int s = 0; s < length; s++) {
            for (int w = 0; w < width; w++) {
                for (int h = 0; h < height; h++) {
                    if (palette.get(cells[index(s, w, h)]).isOf(Blocks.STRUCTURE_VOID)) {
                        vw.add(w);
                        vh.add(h);
                    }
                }
            }
        }
        if (!vw.isEmpty()) {
            vw.sort(null);
            vh.sort(null);
            return new int[]{vw.get(vw.size() / 2), vh.get(vh.size() / 2)};
        }
        int w = width / 2;
        int[] floors = new int[height + 1];
        int[] tops = new int[height + 1];
        boolean anyFloor = false, anyTop = false;
        for (int s = 0; s < length; s++) {
            int floor = -1, top = -1;
            for (int h = 0; h < height; h++) {
                if (solid(get(s, w, h))) {
                    top = h;
                    boolean open1 = h + 1 >= height || !solid(get(s, w, h + 1));
                    boolean open2 = h + 2 >= height || !solid(get(s, w, h + 2));
                    if (open1 && open2 && h + 1 < height) {
                        floor = h;
                    }
                }
            }
            if (floor >= 0) {
                floors[floor + 1]++;
                anyFloor = true;
            }
            if (top >= 0) {
                tops[top + 1]++;
                anyTop = true;
            }
        }
        int[] votes = anyFloor ? floors : tops;
        int best = 0;
        if (anyFloor || anyTop) {
            for (int h = 0; h <= height; h++) {
                if (votes[h] > votes[best]) {
                    best = h;
                }
            }
        }
        return new int[]{w, best};
    }

    // ================================================================ turning

    /**
     * This template turned {@code quarters} quarter turns clockwise (seen
     * from above): turn 1 or 3 makes the old width the length. Pins turn with
     * it when they came from a rail node; otherwise they are re-suggested.
     */
    public SweepTemplate rotated(int quarters) {
        quarters &= 3;
        SweepTemplate t = this;
        for (int i = 0; i < quarters; i++) {
            t = t.rotatedOnce();
        }
        return t;
    }

    private SweepTemplate rotatedOnce() {
        int nl = width, nw = length;
        List<BlockState> np = new ArrayList<>(palette.size());
        for (BlockState state : palette) {
            np.add(turnState(state, BlockRotation.CLOCKWISE_90));
        }
        int[] nc = new int[cells.length];
        for (int s = 0; s < length; s++) {
            for (int w = 0; w < width; w++) {
                int ns = width - 1 - w, nwi = s;
                for (int h = 0; h < height; h++) {
                    nc[(ns * nw + nwi) * height + h] = cells[index(s, w, h)];
                }
            }
        }
        return new SweepTemplate(name, author, created, nl, nw, height, np, nc, -1, -1);
    }

    /** A canonical state turned about the vertical axis (el runs by their vector). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState turnState(BlockState state, BlockRotation rotation) {
        if (state.isAir() || rotation == BlockRotation.NONE) {
            return state;
        }
        boolean hasRun = false;
        for (Property<?> property : state.getProperties()) {
            if (property.getType() == ElRun.class) {
                hasRun = true;
                ElRun run = (ElRun) state.get(property);
                double x = run.step().getX(), z = run.step().getZ();
                for (int i = 0; i < quarterTurns(rotation); i++) {
                    double nx = -z;
                    z = x;
                    x = nx;
                }
                state = state.with((Property) property, ElRun.fromDirection(x, z));
            }
        }
        return hasRun ? state : SweepMapper.rotateAny(state, rotation);
    }

    private static int quarterTurns(BlockRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
    }

    // ================================================================ capture

    public record Capture(SweepTemplate template, String errorKey, Object[] errorArgs) {
    }

    /** Turn that takes EAST onto the quarter {@link SweepGeometry#quarter} returned (0 E, 1 S, 2 W, 3 N). */
    static BlockRotation fromCanonical(int quarter) {
        return switch (quarter & 3) {
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    /** Mirror across the travel line (w → W−1−w) in the canonical frame (w runs along z). */
    static final BlockMirror FLIP = BlockMirror.LEFT_RIGHT;

    /** The smallest box inside [min, max] holding every non-air block, or null when it is all air. */
    public static BlockPos[] trimmed(World world, BlockPos min, BlockPos max) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    if (!world.getBlockState(p.set(x, y, z)).isAir()) {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        z0 = Math.min(z0, z);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                        z1 = Math.max(z1, z);
                    }
                }
            }
        }
        return x0 == Integer.MAX_VALUE ? null : new BlockPos[]{new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1)};
    }

    /**
     * Reads the box between two corners (optionally trimmed to its blocks).
     * The longer horizontal side becomes the length (travel east in the
     * canonical frame).
     */
    public static Capture capture(World world, BlockPos c1, BlockPos c2, boolean trim, String name, String author) {
        BlockPos min = new BlockPos(Math.min(c1.getX(), c2.getX()), Math.min(c1.getY(), c2.getY()), Math.min(c1.getZ(), c2.getZ()));
        BlockPos max = new BlockPos(Math.max(c1.getX(), c2.getX()), Math.max(c1.getY(), c2.getY()), Math.max(c1.getZ(), c2.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > 4_000_000L) {
            return new Capture(null, "msg.station_announcer.sweep.too_big", new Object[]{
                    max.getX() - min.getX() + 1, max.getZ() - min.getZ() + 1, max.getY() - min.getY() + 1,
                    MAX_LENGTH, MAX_WIDTH, MAX_HEIGHT});
        }
        if (trim) {
            BlockPos[] box = trimmed(world, min, max);
            if (box == null) {
                return new Capture(null, "msg.station_announcer.sweep.empty", new Object[0]);
            }
            min = box[0];
            max = box[1];
        }
        int sx = max.getX() - min.getX() + 1, sz = max.getZ() - min.getZ() + 1, height = max.getY() - min.getY() + 1;
        boolean alongX = sx >= sz;
        int length = alongX ? sx : sz, width = alongX ? sz : sx;
        if (length > MAX_LENGTH || width > MAX_WIDTH || height > MAX_HEIGHT) {
            return new Capture(null, "msg.station_announcer.sweep.too_big",
                    new Object[]{length, width, height, MAX_LENGTH, MAX_WIDTH, MAX_HEIGHT});
        }
        // travel east (x longer): right = south (+z); travel south (z longer): right = west (−x)
        BlockRotation rotation = alongX ? BlockRotation.NONE : BlockRotation.COUNTERCLOCKWISE_90;
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> lookup = new HashMap<>();
        int[] cells = new int[length * width * height];
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int s = 0; s < length; s++) {
            for (int w = 0; w < width; w++) {
                int x = alongX ? min.getX() + s : max.getX() - w;
                int z = alongX ? min.getZ() + w : min.getZ() + s;
                for (int h = 0; h < height; h++) {
                    BlockState state = world.getBlockState(p.set(x, min.getY() + h, z));
                    if (Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("mtr")) {
                        state = VOID;
                    } else if (state.isAir()) {
                        state = AIR;
                    } else {
                        state = turnState(state, rotation);
                    }
                    Integer idx = lookup.get(state);
                    if (idx == null) {
                        idx = palette.size();
                        palette.add(state);
                        lookup.put(state, idx);
                    }
                    cells[(s * width + w) * height + h] = idx;
                }
            }
        }
        return new Capture(new SweepTemplate(name, author, System.currentTimeMillis(), length, width, height,
                palette, cells, -1, -1), null, null);
    }

    // ================================================================ storage

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("Format", 2);
        nbt.putString("Name", name);
        nbt.putString("Author", author);
        nbt.putLong("Created", created);
        nbt.putInt("Length", length);
        nbt.putInt("Width", width);
        nbt.putInt("Height", height);
        nbt.putInt("PinW", pinW);
        nbt.putInt("PinH", pinH);
        NbtList list = new NbtList();
        for (BlockState state : palette) {
            list.add(NbtHelper.fromBlockState(state));
        }
        nbt.put("Palette", list);
        nbt.putIntArray("Cells", cells);
        return nbt;
    }

    public static SweepTemplate fromNbt(NbtCompound nbt) {
        int length = nbt.getInt("Length"), width = nbt.getInt("Width"), height = nbt.getInt("Height");
        if (length <= 0 || width <= 0 || height <= 0 || length > MAX_LENGTH || width > MAX_WIDTH || height > MAX_HEIGHT) {
            return null;
        }
        NbtList list = nbt.getList("Palette", NbtElement.COMPOUND_TYPE);
        List<BlockState> palette = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            palette.add(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), list.getCompound(i)));
        }
        int[] cells = nbt.getIntArray("Cells");
        if (palette.isEmpty() || cells.length != length * width * height) {
            return null;
        }
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] < 0 || cells[i] >= palette.size()) {
                cells[i] = 0;
            }
        }
        int pinW, pinH;
        if (nbt.getInt("Format") >= 2) {
            pinW = nbt.getInt("PinW");
            pinH = nbt.getInt("PinH");
        } else {
            // first build (2026-10-21): the rail cell was u 0 / v 0 at UMin / VMin
            pinW = -nbt.getInt("UMin");
            pinH = -nbt.getInt("VMin");
        }
        return new SweepTemplate(nbt.getString("Name"), nbt.getString("Author"), nbt.getLong("Created"),
                length, width, height, palette, cells, pinW, pinH);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(toNbt(), out);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static SweepTemplate fromBytes(byte[] bytes) {
        try {
            return fromNbt(NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtSizeTracker.of(64L * 1024 * 1024)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return name + " " + length + "x" + width + "x" + height + " pin " + pinW + "," + pinH + " " + Arrays.hashCode(cells);
    }
}
