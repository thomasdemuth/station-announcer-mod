package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A weighted block mix for one earthworks layer, stored as text so it rides
 * NBT, presets and packets unchanged: {@code "60 minecraft:gravel, 30
 * minecraft:andesite, 10 minecraft:cobblestone"} (weight optional, default
 * 1; any block-state string {@link BridgeSpec#parseMaterial} accepts).
 * Blank = the layer is off.
 *
 * <p>The block for a cell is picked by hashing its position, so the same
 * cell always gets the same block: the settings preview shows exactly what
 * the build will place, and re-running a build is stable.</p>
 */
public final class EarthworksPalette {
    public static final int MAX_ENTRIES = 8;
    public static final int MAX_WEIGHT = 100;

    public record Entry(String material, int weight) {
    }

    private final List<BlockState> states = new ArrayList<>();
    private final int[] cumulative;
    private final int total;
    private final List<String> bad = new ArrayList<>();
    private BlockState solid;

    private EarthworksPalette(List<Entry> entries) {
        List<Integer> sums = new ArrayList<>();
        int sum = 0;
        for (Entry e : entries) {
            BlockState state = BridgeSpec.parseMaterial(e.material());
            if (state == null) {
                bad.add(e.material());
                continue;
            }
            states.add(state);
            sum += Math.max(1, e.weight());
            sums.add(sum);
            if (solid == null && !(state.getBlock() instanceof FallingBlock)) {
                solid = state;
            }
        }
        cumulative = sums.stream().mapToInt(Integer::intValue).toArray();
        total = sum;
    }

    public static EarthworksPalette of(String text) {
        return new EarthworksPalette(parse(text));
    }

    public boolean isEmpty() {
        return states.isEmpty();
    }

    /** Materials that did not parse (reported to the player). */
    public List<String> bad() {
        return bad;
    }

    /** The block for a cell (null when the palette is empty). */
    public BlockState pick(BlockPos pos, int salt) {
        if (states.isEmpty()) {
            return null;
        }
        if (states.size() == 1) {
            return states.get(0);
        }
        long h = pos.asLong() * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        int r = (int) Math.floorMod(h, (long) total);
        for (int i = 0; i < cumulative.length; i++) {
            if (r < cumulative[i]) {
                return states.get(i);
            }
        }
        return states.get(states.size() - 1);
    }

    /** The first entry that does not fall (sand/gravel over a hole), or null. */
    public BlockState solid() {
        return solid;
    }

    // ------------------------------------------------------------ text form

    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        // split on commas outside [...] (block states carry commas between their properties)
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int weight = 1;
            int space = p.indexOf(' ');
            if (space > 0) {
                String head = p.substring(0, space).replace("%", "").replace("x", "");
                try {
                    weight = Integer.parseInt(head);
                    p = p.substring(space + 1).trim();
                } catch (NumberFormatException ignored) {
                    // no weight: the whole part is the material
                }
            }
            if (!p.isEmpty() && out.size() < MAX_ENTRIES) {
                out.add(new Entry(p, Math.max(1, Math.min(MAX_WEIGHT, weight))));
            }
        }
        return out;
    }

    public static String format(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.weight()).append(' ').append(e.material());
        }
        return sb.toString();
    }

    /** {@code text} with {@code material} added (or its weight bumped if already there). */
    public static String add(String text, String material, int weight) {
        List<Entry> entries = parse(text);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).material().equals(material)) {
                entries.set(i, new Entry(material, Math.min(MAX_WEIGHT, entries.get(i).weight() + weight)));
                return format(entries);
            }
        }
        if (entries.size() < MAX_ENTRIES) {
            entries.add(new Entry(material, weight));
        }
        return format(entries);
    }
}
