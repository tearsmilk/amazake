package core.amazake.core.modules.essentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void acceptsLettersDigitsUnderscoreDash() {
        assertTrue(Names.valid("home"));
        assertTrue(Names.valid("base_1"));
        assertTrue(Names.valid("spawn-area"));
        assertTrue(Names.valid("A"));
        assertTrue(Names.valid("123"));
    }

    @Test
    void rejectsEmptyNullAndTooLong() {
        assertFalse(Names.valid(null));
        assertFalse(Names.valid(""));
        assertFalse(Names.valid("x".repeat(Names.MAX_LENGTH + 1)));
        assertTrue(Names.valid("x".repeat(Names.MAX_LENGTH)));
    }

    @Test
    void rejectsSpacesAndSpecialChars() {
        assertFalse(Names.valid("my home"));
        assertFalse(Names.valid("дом"));
        assertFalse(Names.valid("home!"));
        assertFalse(Names.valid("a.b"));
        assertFalse(Names.valid("drop'table"));
    }

    @Test
    void normalizeLowercases() {
        assertEquals("home", Names.normalize("HOME"));
        assertEquals("base_1", Names.normalize("Base_1"));
        assertEquals(null, Names.normalize(null));
    }
}
