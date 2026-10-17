package com.stationannouncer.atmosphere;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.PersistentState;

/**
 * Per-dimension store of every light that is not healthy:
 * {@code data/station_announcer_lights.dat} in the dimension's folder.
 * Packed value = condition ordinal | salt << 8 (0 never stored = healthy).
 */
public final class LightConditionState extends PersistentState {
    public static final String ID = "station_announcer_lights";
    public static final Type<LightConditionState> TYPE =
            new Type<>(LightConditionState::new, LightConditionState::fromNbt, net.minecraft.datafixer.DataFixTypes.LEVEL);

    final Long2IntOpenHashMap entries = new Long2IntOpenHashMap();

    public LightConditionState() {
        entries.defaultReturnValue(0);
    }

    public static int pack(LightCondition condition, int salt) {
        return condition == LightCondition.HEALTHY ? 0 : (condition.ordinal() & 0xFF) | ((salt & 0xFF) << 8);
    }

    public static LightCondition condition(int packed) {
        return LightCondition.byOrdinal(packed & 0xFF);
    }

    public static int salt(int packed) {
        return (packed >>> 8) & 0xFF;
    }

    public Long2IntMap entries() {
        return entries;
    }

    public int get(long pos) {
        return entries.get(pos);
    }

    /** Returns true when something changed. */
    public boolean set(long pos, int packed) {
        int old = packed == 0 ? entries.remove(pos) : entries.put(pos, packed);
        if (old != packed) {
            markDirty();
            return true;
        }
        return false;
    }

    private static LightConditionState fromNbt(NbtCompound nbt) {
        LightConditionState state = new LightConditionState();
        long[] pos = nbt.getLongArray("Pos");
        int[] val = nbt.getIntArray("Val");
        for (int i = 0; i < Math.min(pos.length, val.length); i++) {
            if (val[i] != 0) {
                state.entries.put(pos[i], val[i]);
            }
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        long[] pos = new long[entries.size()];
        int[] val = new int[entries.size()];
        int i = 0;
        for (Long2IntMap.Entry e : entries.long2IntEntrySet()) {
            pos[i] = e.getLongKey();
            val[i] = e.getIntValue();
            i++;
        }
        nbt.putLongArray("Pos", pos);
        nbt.putIntArray("Val", val);
        return nbt;
    }
}
