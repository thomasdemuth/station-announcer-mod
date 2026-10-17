package com.stationannouncer.atmosphere;

/**
 * What state a light is in. Applies to ANY block that emits light (MTR's
 * ceiling lights, our lamps, other mods' blocks) without touching the block:
 * the condition lives in {@link LightConditionState} keyed by position.
 *
 * <p>Two separate effects:
 * <ul>
 *   <li>{@link #luminance} — the light level the block really gives off (the
 *       light engine hook). Only DIM, WARM and DEAD change it, and only when
 *       the condition is set, never per flicker frame.</li>
 *   <li>{@link #lens} — how bright the lamp itself LOOKS right now, 0..1
 *       (1 = untouched). A pure function of (seed, time) so every client sees
 *       the same blink with no packets. Drawn client-side by re-rendering the
 *       block's model at a lower light level.</li>
 * </ul>
 *
 * <p>Ordinals are stored (state file + packets): only ever APPEND.
 */
public enum LightCondition {
    HEALTHY("healthy", 0xFF9A9AA6),
    STUTTER("stutter", 0xFFE8C547),
    BALLAST("ballast", 0xFFE88A2E),
    PULSE("pulse", 0xFF6FB7E8),
    RESTRIKE("restrike", 0xFFB58CE8),
    DIM("dim", 0xFF8A8A70),
    WARM("warm", 0xFFE8B070),
    DEAD("dead", 0xFFE5484D);

    private static final LightCondition[] VALUES = values();

    /** Lang key part and command name. */
    public final String id;
    /** Marker colour while holding the maintenance tool (ARGB). */
    public final int color;

    LightCondition(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public static LightCondition byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : HEALTHY;
    }

    public static LightCondition byId(String id) {
        for (LightCondition c : VALUES) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public boolean flickers() {
        return this == STUTTER || this == BALLAST || this == PULSE || this == RESTRIKE;
    }

    /** The light level actually emitted, given the block's own. */
    public int luminance(int base) {
        if (base <= 0) {
            return base;
        }
        return switch (this) {
            case DIM -> Math.max(1, (base + 1) / 2);
            case WARM -> Math.max(1, base - 2);
            case DEAD -> 0;
            default -> base;
        };
    }

    /** Tint of the lamp (r, g, b multipliers); white unless WARM. */
    public float[] tint() {
        return this == WARM ? WARM_TINT : WHITE;
    }

    private static final float[] WHITE = {1f, 1f, 1f};
    private static final float[] WARM_TINT = {1f, 0.90f, 0.68f};

    /**
     * How lit the lamp looks at wall-clock {@code ms}, 0 (dark) .. 1 (normal).
     * {@code seed} is per light, so a row of failing tubes never blinks in step.
     */
    public float lens(int seed, long ms) {
        switch (this) {
            case HEALTHY:
                return 1f;
            case DIM:
                return 0.55f;
            case WARM:
                return 0.92f;
            case DEAD:
                return 0.08f;
            case STUTTER: {
                // Every 6-25 s a burst of 1-4 short drop-outs.
                long period = 6_000 + (hash(seed, 1) % 19_000);
                long cycle = Math.floorDiv(ms + hash(seed, 2) % period, period);
                long t = Math.floorMod(ms + hash(seed, 2) % period, period);
                int blinks = 1 + (int) (hash(seed ^ (int) cycle, 3) % 4);
                long at = 0;
                for (int i = 0; i < blinks; i++) {
                    long off = 50 + hash(seed ^ (int) cycle, 10 + i) % 90;
                    long gap = 40 + hash(seed ^ (int) cycle, 20 + i) % 160;
                    if (t >= at && t < at + off) {
                        return 0.12f;
                    }
                    at += off + gap;
                }
                return 1f;
            }
            case BALLAST: {
                // Long steady stretches, then 1.5-4 s of rapid irregular chatter.
                long period = 7_000 + (hash(seed, 1) % 9_000);
                long shift = hash(seed, 2) % period;
                long cycle = Math.floorDiv(ms + shift, period);
                long t = Math.floorMod(ms + shift, period);
                long burst = 1_500 + hash(seed ^ (int) cycle, 3) % 2_500;
                if (t >= burst) {
                    return 0.9f; // a tired tube even when steady
                }
                long slot = t / 55; // ~18 Hz chatter
                long r = hash(seed ^ (int) cycle ^ (int) (slot * 31), 4) % 100;
                return r < 45 ? 0.1f : r < 60 ? 0.5f : 0.95f;
            }
            case PULSE: {
                // Breathing 0.55..1 over 2-4 s, a little uneven.
                double period = 2_000 + (hash(seed, 1) % 2_000);
                double phase = (ms + hash(seed, 2) % 10_000) / period * Math.PI * 2;
                double v = 0.5 + 0.5 * Math.sin(phase) * (0.85 + 0.15 * Math.sin(phase * 0.37));
                return (float) (0.55 + 0.45 * v);
            }
            case RESTRIKE: {
                // Dark, a few strikes, then lit; repeats every 20-40 s.
                long period = 20_000 + (hash(seed, 1) % 20_000);
                long shift = hash(seed, 2) % period;
                long cycle = Math.floorDiv(ms + shift, period);
                long t = Math.floorMod(ms + shift, period);
                long dark = 2_000 + hash(seed ^ (int) cycle, 3) % 3_000;
                if (t < dark) {
                    return 0.08f;
                }
                long strikeEnd = dark + 1_800;
                if (t < strikeEnd) {
                    long s = (t - dark) / 120;
                    // flashes get longer as the tube catches
                    long r = hash(seed ^ (int) cycle ^ (int) (s * 17), 5) % 100;
                    return r < 55 - (t - dark) / 40 ? 0.1f : 0.85f;
                }
                return 1f;
            }
            default:
                return 1f;
        }
    }

    /** Stable non-negative hash of (seed, salt). */
    static long hash(int seed, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }

    /** The per-light seed: a hash of the position, rerollable by a stored salt. */
    public static int seed(long pos, int salt) {
        return (int) hash((int) (pos ^ (pos >>> 32)), 97 + salt);
    }
}
