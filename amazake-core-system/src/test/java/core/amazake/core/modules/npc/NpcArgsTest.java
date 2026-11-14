package core.amazake.core.modules.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests the pure argument parsing of {@code /npccore}. */
class NpcArgsTest {

    @Test
    void emptyArgsAreUsageError() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {});
        assertFalse(r.isOk());
        assertTrue(r.error().contains("Использование"));
    }

    @Test
    void nullArgsAreUsageError() {
        assertFalse(NpcArgs.parse(null).isOk());
    }

    @Test
    void skinSelected() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"skin", "Notch"});
        assertTrue(r.isOk());
        assertEquals(NpcArgs.Action.SKIN_SELECTED, r.args().action());
        assertEquals("Notch", r.args().value());
        assertNull(r.args().npcId());
    }

    @Test
    void skinSubcommandIsCaseInsensitive() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"SKIN", "Steve"});
        assertTrue(r.isOk());
        assertEquals(NpcArgs.Action.SKIN_SELECTED, r.args().action());
        assertEquals("Steve", r.args().value());
    }

    @Test
    void skinWithoutPlayerIsError() {
        assertFalse(NpcArgs.parse(new String[] {"skin"}).isOk());
    }

    @Test
    void skinById() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"skin", "id", "42", "Notch"});
        assertTrue(r.isOk());
        assertEquals(NpcArgs.Action.SKIN_BY_ID, r.args().action());
        assertEquals(42, r.args().npcId());
        assertEquals("Notch", r.args().value());
    }

    @Test
    void skinByIdCaseInsensitiveKeyword() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"skin", "ID", "7", "Herobrine"});
        assertTrue(r.isOk());
        assertEquals(7, r.args().npcId());
    }

    @Test
    void skinByIdWithNonNumericIsError() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"skin", "id", "abc", "Notch"});
        assertFalse(r.isOk());
        assertTrue(r.error().contains("целым числом"));
    }

    @Test
    void skinByIdMissingPlayerIsError() {
        assertFalse(NpcArgs.parse(new String[] {"skin", "id", "42"}).isOk());
    }

    @Test
    void skinUrl() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"skinurl", "some-value"});
        assertTrue(r.isOk());
        assertEquals(NpcArgs.Action.SKIN_URL, r.args().action());
        assertEquals("some-value", r.args().value());
        assertNull(r.args().npcId());
    }

    @Test
    void skinUrlWithoutValueIsError() {
        assertFalse(NpcArgs.parse(new String[] {"skinurl"}).isOk());
    }

    @Test
    void unknownSubcommandIsError() {
        final NpcArgs.Result r = NpcArgs.parse(new String[] {"wat"});
        assertFalse(r.isOk());
        assertTrue(r.error().contains("Неизвестная подкоманда"));
    }
}
