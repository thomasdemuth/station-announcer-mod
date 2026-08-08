package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client mirror of the server's depot groups, replaced wholesale by the
 * {@code addon_depot_groups} sync on join and after every edit, and cleared on
 * disconnect. Read only on the client thread (the depot-group screens).
 *
 * <p>Each member carries the phase offset the simulators last computed for it, in
 * milliseconds, or {@code -1} for "not computed yet" — the client cannot derive it
 * itself because the game-day length lives on the simulator.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientDepotGroups {

    /** One member depot of a group, in offset order. */
    public record Member(long depotId, int offsetMillis) {
        public boolean hasOffset() {
            return offsetMillis >= 0;
        }
    }

    /** One depot group as the client sees it. */
    public record Group(long id, String name, List<Member> members) {
        public int indexOf(long depotId) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).depotId() == depotId) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static List<Group> groups = List.of();
    private static Map<Long, Long> groupIdByDepot = Map.of();

    private ClientDepotGroups() {
    }

    public static void replace(List<Group> newGroups) {
        groups = List.copyOf(newGroups);
        Map<Long, Long> byDepot = new HashMap<>();
        for (Group group : groups) {
            for (Member member : group.members()) {
                byDepot.putIfAbsent(member.depotId(), group.id());
            }
        }
        groupIdByDepot = Map.copyOf(byDepot);
    }

    public static void clear() {
        groups = List.of();
        groupIdByDepot = Map.of();
    }

    public static List<Group> all() {
        return groups;
    }

    public static int count() {
        return groups.size();
    }

    /** The group a depot belongs to, or null. */
    public static Group groupOf(long depotId) {
        Long groupId = groupIdByDepot.get(depotId);
        if (groupId == null) {
            return null;
        }
        for (Group group : groups) {
            if (group.id() == groupId) {
                return group;
            }
        }
        return null;
    }

    /** True when this depot is already staggered by SOME OTHER group than {@code groupId}. */
    public static boolean claimedByAnother(long depotId, long groupId) {
        Long owner = groupIdByDepot.get(depotId);
        return owner != null && owner != groupId;
    }
}
