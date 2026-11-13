package ink.hoka.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GroupResolverTest {

    private static GroupDef group(final String name, final int priority) {
        return new GroupDef(name, priority, "[" + name + "] ", "", "", "", null);
    }

    private static final GroupDef ADMIN = group("Admin", 0);
    private static final GroupDef VIP = group("vip", 20);
    private static final GroupDef DEFAULT = group("default", 999);
    private static final List<GroupDef> GROUPS = List.of(ADMIN, VIP, DEFAULT);

    @Test
    void permissionNamesAreLowerCase() {
        assertEquals("amazake.tab.group.admin", ADMIN.permission());
        assertNull(DEFAULT.permission());
    }

    @Test
    void firstMatchingGroupInConfigOrderWins() {
        final Set<String> perms = Set.of("amazake.tab.group.admin", "amazake.tab.group.vip");
        assertSame(ADMIN, GroupResolver.resolve(GROUPS, perms::contains));
        assertSame(VIP, GroupResolver.resolve(GROUPS, Set.of("amazake.tab.group.vip")::contains));
    }

    @Test
    void defaultMatchesEveryone() {
        assertSame(DEFAULT, GroupResolver.resolve(GROUPS, p -> false));
    }

    @Test
    void defaultBeforeOthersShadowsThem() {
        assertSame(DEFAULT, GroupResolver.resolve(List.of(DEFAULT, ADMIN), p -> true));
    }

    @Test
    void fallbackWithoutDefault() {
        assertSame(GroupDef.FALLBACK, GroupResolver.resolve(List.of(ADMIN), p -> false));
        assertSame(GroupDef.FALLBACK, GroupResolver.resolve(List.of(), p -> true));
    }
}
