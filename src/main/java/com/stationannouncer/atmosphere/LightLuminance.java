package com.stationannouncer.atmosphere;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light engine's view of the light conditions. Read from the lighting
 * thread(s) of the server AND the client, so the maps are immutable
 * snapshots swapped in whole (copy-on-write); a lookup is one hash probe and
 * only happens for blocks that emit light at all.
 *
 * <p>Server and client keep separate snapshots (an integrated server shares
 * this JVM with its client): the server's per dimension, the client's for
 * the one world it shows.
 */
public final class LightLuminance {
    private static final Map<RegistryKey<World>, Long2IntMap> SERVER = new ConcurrentHashMap<>();
    private static volatile Long2IntMap client = empty();

    private LightLuminance() {
    }

    private static Long2IntMap empty() {
        Long2IntOpenHashMap m = new Long2IntOpenHashMap(0);
        m.defaultReturnValue(0);
        return m;
    }

    /** Called by the light-engine mixin: the luminance to use at {@code pos}. */
    public static int adjust(BlockView view, long pos, int luminance) {
        if (luminance <= 0 || !(view instanceof World world)) {
            return luminance;
        }
        Long2IntMap map = world.isClient() ? client : SERVER.get(world.getRegistryKey());
        if (map == null || map.isEmpty()) {
            return luminance;
        }
        int packed = map.get(pos);
        if (packed == 0) {
            return luminance;
        }
        return LightConditionState.condition(packed).luminance(luminance);
    }

    public static void publishServer(RegistryKey<World> dimension, Long2IntMap entries) {
        Long2IntOpenHashMap copy = new Long2IntOpenHashMap(entries);
        copy.defaultReturnValue(0);
        SERVER.put(dimension, copy);
    }

    public static void clearServer() {
        SERVER.clear();
    }

    public static void publishClient(Long2IntMap entries) {
        Long2IntOpenHashMap copy = new Long2IntOpenHashMap(entries);
        copy.defaultReturnValue(0);
        client = copy;
    }
}
