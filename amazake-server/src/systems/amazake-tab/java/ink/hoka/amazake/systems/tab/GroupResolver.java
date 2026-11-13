package ink.hoka.amazake.systems.tab;

import java.util.List;
import java.util.function.Predicate;

/** Picks a player's group: the first group in config order whose permission the player has. Pure. */
public final class GroupResolver {

    private GroupResolver() {
    }

    /**
     * @param groups        groups in config order
     * @param hasPermission permission check of the player (called on the player's own thread by the caller)
     * @return the first matching group; the {@code default} group matches everyone; {@link GroupDef#FALLBACK} if
     *         nothing matches
     */
    public static GroupDef resolve(final List<GroupDef> groups, final Predicate<String> hasPermission) {
        for (final GroupDef group : groups) {
            final String permission = group.permission();
            if (permission == null || hasPermission.test(permission)) {
                return group;
            }
        }
        return GroupDef.FALLBACK;
    }
}
