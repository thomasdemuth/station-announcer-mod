package com.stationannouncer;

import com.stationannouncer.block.AbstractPaBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Tracks all currently loaded PA source block entities (Station Announcers
 * and PA Control Boxes) on the server so the /announce command can trigger
 * them by tag and tab-complete known tags.
 *
 * Entries are weakly referenced and validated on every query, so a missed
 * unload cannot leak memory or trigger stale blocks.
 */
public final class AnnouncerRegistry {
    private static final Set<AbstractPaBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private AnnouncerRegistry() {
    }

    public static synchronized void add(AbstractPaBlockEntity be) {
        LOADED.add(be);
    }

    public static synchronized void remove(AbstractPaBlockEntity be) {
        LOADED.remove(be);
    }

    public static synchronized void clear() {
        LOADED.clear();
    }

    /** All non-empty tags of loaded announcers on this server, sorted. */
    public static synchronized Set<String> knownTags(MinecraftServer server) {
        Set<String> tags = new TreeSet<>();
        for (AbstractPaBlockEntity be : LOADED) {
            if (isValid(be, server) && !be.getAnnouncerTag().isBlank()) {
                tags.add(be.getAnnouncerTag());
            }
        }
        return tags;
    }

    /** Triggers every loaded announcer with the given tag; returns how many fired. */
    public static synchronized int trigger(MinecraftServer server, String tag) {
        int count = 0;
        for (AbstractPaBlockEntity be : LOADED) {
            if (isValid(be, server) && be.getAnnouncerTag().equals(tag)) {
                be.trigger();
                count++;
            }
        }
        return count;
    }

    /**
     * Visits every currently loaded, still-valid PA source of this server.
     * Additive (used by the MTR dispatch addon's disruption broadcaster); no
     * pre-existing code path calls it. The consumer runs while the registry lock
     * is held, so it must not add or remove PA blocks — reading their state and
     * firing announcements (which only touches the block entity itself) is fine.
     */
    public static synchronized void forEachLoaded(MinecraftServer server, java.util.function.Consumer<AbstractPaBlockEntity> consumer) {
        for (AbstractPaBlockEntity be : LOADED) {
            if (isValid(be, server)) {
                consumer.accept(be);
            }
        }
    }

    private static boolean isValid(AbstractPaBlockEntity be, MinecraftServer server) {
        return be != null
                && !be.isRemoved()
                && be.getWorld() instanceof ServerWorld world
                && world.getServer() == server;
    }
}
