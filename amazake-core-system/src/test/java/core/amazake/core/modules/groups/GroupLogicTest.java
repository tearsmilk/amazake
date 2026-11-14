package core.amazake.core.modules.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Юнит-тесты чистой логики групп на обычных мапах — без сервера. */
class GroupLogicTest {

    @Test
    void inheritanceUnionsParents() {
        final Map<String, Set<String>> perms = Map.of(
            "admin", Set.of("a.admin"),
            "mod", Set.of("a.kick"),
            "user", Set.of("a.chat"));
        final Map<String, Set<String>> parents = Map.of(
            "admin", Set.of("mod"),
            "mod", Set.of("user"));
        final Set<String> eff = GroupLogic.effectivePerms("admin", perms, parents);
        assertEquals(Set.of("a.admin", "a.kick", "a.chat"), eff);
    }

    @Test
    void inheritanceIsCycleSafe() {
        final Map<String, Set<String>> perms = Map.of(
            "a", Set.of("p.a"),
            "b", Set.of("p.b"),
            "c", Set.of("p.c"));
        // цикл a -> b -> c -> a
        final Map<String, Set<String>> parents = Map.of(
            "a", Set.of("b"),
            "b", Set.of("c"),
            "c", Set.of("a"));
        final Set<String> eff = GroupLogic.effectivePerms("a", perms, parents);
        assertEquals(Set.of("p.a", "p.b", "p.c"), eff);
    }

    @Test
    void primaryGroupIsHighestWeight() {
        final Map<String, Integer> weights = Map.of("default", 0, "vip", 10, "admin", 100);
        assertEquals("admin", GroupLogic.primaryGroup(List.of("vip", "admin"), weights, "default"));
        assertEquals("vip", GroupLogic.primaryGroup(List.of("vip"), weights, "default"));
        // нет групп -> по умолчанию
        assertEquals("default", GroupLogic.primaryGroup(List.of(), weights, "default"));
        // неизвестная группа игнорируется
        assertEquals("default", GroupLogic.primaryGroup(List.of("ghost"), weights, "default"));
    }

    @Test
    void primaryGroupTieBreaksByName() {
        final Map<String, Integer> weights = Map.of("default", 0, "builder", 5, "artist", 5);
        assertEquals("artist", GroupLogic.primaryGroup(List.of("builder", "artist"), weights, "default"));
    }

    @Test
    void wildcardMatching() {
        final Set<String> perms = Set.of("essentials.home", "worldedit.*");
        assertTrue(GroupLogic.matches(perms, "essentials.home"));
        assertTrue(GroupLogic.matches(perms, "worldedit.cut"));
        assertTrue(GroupLogic.matches(perms, "worldedit.region.set"));
        assertFalse(GroupLogic.matches(perms, "essentials.fly"));
        assertFalse(GroupLogic.matches(perms, "worldeditx.cut"));
    }

    @Test
    void globalStarMatchesEverything() {
        final Set<String> perms = Set.of("*");
        assertTrue(GroupLogic.matches(perms, "anything.at.all"));
        assertTrue(GroupLogic.matches(perms, "x"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        final Set<String> perms = Set.of("essentials.home");
        assertTrue(GroupLogic.matches(perms, "Essentials.Home"));
    }

    @Test
    void playerEffectiveCombinesGroupsDefaultAndPersonal() {
        final Map<String, Set<String>> perms = Map.of(
            "default", Set.of("base.chat"),
            "vip", Set.of("vip.fly"));
        final Map<String, Set<String>> parents = Map.of();
        final Set<String> eff = GroupLogic.playerEffective(
            List.of("vip"), Set.of("extra.node"), "default", perms, parents);
        assertEquals(Set.of("base.chat", "vip.fly", "extra.node"), eff);
    }

    @Test
    void playerEffectiveFallsBackToDefaultOnly() {
        final Map<String, Set<String>> perms = Map.of("default", Set.of("base.chat"));
        final Set<String> eff = GroupLogic.playerEffective(null, null, "default", perms, Map.of());
        assertEquals(Set.of("base.chat"), eff);
    }

    @Test
    void groupNameRegexAcceptsAndRejects() {
        assertTrue(GroupCommand.NAME.matcher("vip_plus-1").matches());
        assertFalse(GroupCommand.NAME.matcher("ВИП").matches());
        assertFalse(GroupCommand.NAME.matcher("with space").matches());
        assertFalse(GroupCommand.NAME.matcher("").matches());
    }
}
