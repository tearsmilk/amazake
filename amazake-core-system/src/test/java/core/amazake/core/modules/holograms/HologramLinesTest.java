package core.amazake.core.modules.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Индексация строк (с единицы для игрока) без сервера. */
class HologramLinesTest {

    @Test
    void addAppendsToEnd() {
        final List<String> out = HologramLines.added(List.of("a", "b"), "c");
        assertEquals(List.of("a", "b", "c"), out);
    }

    @Test
    void addToEmpty() {
        assertEquals(List.of("first"), HologramLines.added(List.of(), "first"));
    }

    @Test
    void setReplacesOneBased() {
        final List<String> out = HologramLines.set(List.of("a", "b", "c"), 2, "B");
        assertEquals(List.of("a", "B", "c"), out);
    }

    @Test
    void removeShiftsUp() {
        final List<String> out = HologramLines.removed(List.of("a", "b", "c"), 1);
        assertEquals(List.of("b", "c"), out);
    }

    @Test
    void validIndexBounds() {
        final List<String> lines = List.of("a", "b");
        assertFalse(HologramLines.validIndex(lines, 0));
        assertTrue(HologramLines.validIndex(lines, 1));
        assertTrue(HologramLines.validIndex(lines, 2));
        assertFalse(HologramLines.validIndex(lines, 3));
        assertFalse(HologramLines.validIndex(List.of(), 1));
    }

    @Test
    void setOutOfRangeThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> HologramLines.set(List.of("a"), 2, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> HologramLines.removed(List.of("a"), 0));
    }

    @Test
    void resultsAreImmutable() {
        final List<String> out = HologramLines.added(List.of("a"), "b");
        assertThrows(UnsupportedOperationException.class, () -> out.add("c"));
    }
}
