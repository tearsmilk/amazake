package core.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SortKeysTest {

    @Test
    void format() {
        assertEquals("az000takuyamo", SortKeys.teamName(0, "Takuyamo"));
        assertEquals("az007steve", SortKeys.teamName(7, "Steve"));
        assertEquals("az042alex", SortKeys.teamName(42, "Alex"));
        assertEquals("az999notch", SortKeys.teamName(999, "Notch"));
    }

    @Test
    void priorityIsClamped() {
        assertEquals("az000a", SortKeys.teamName(-5, "a"));
        assertEquals("az999a", SortKeys.teamName(5000, "a"));
    }

    @Test
    void stringOrderIsPriorityThenName() {
        // the client sorts by team name with String.compareTo
        final List<String> keys = new ArrayList<>(List.of(
            SortKeys.teamName(999, "aaron"), SortKeys.teamName(10, "Zed"), SortKeys.teamName(10, "bob"),
            SortKeys.teamName(0, "Zack"), SortKeys.teamName(100, "a"), SortKeys.teamName(9, "x")));
        keys.sort(String::compareTo);
        assertEquals(List.of("az000zack", "az009x", "az010bob", "az010zed", "az100a", "az999aaron"), keys);
    }

    @Test
    void recognisesOwnTeams() {
        assertTrue(SortKeys.isOurs(SortKeys.teamName(3, "Steve")));
        assertFalse(SortKeys.isOurs("azure"));
        assertFalse(SortKeys.isOurs("red"));
        assertFalse(SortKeys.isOurs("az1"));
    }
}
