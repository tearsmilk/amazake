package ink.hoka.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FramesTest {

    @Test
    void framesAdvanceEveryInterval() {
        final Frames frames = Frames.of(10, "a", "b", "c");
        assertEquals("a", frames.at(0));
        assertEquals("a", frames.at(9));
        assertEquals("b", frames.at(10));
        assertEquals("c", frames.at(29));
        assertEquals("a", frames.at(30));
        assertEquals("b", frames.at(1_000_000_000L));
    }

    @Test
    void singleFrameIsStatic() {
        final Frames frames = Frames.of(1, "only");
        assertEquals("only", frames.at(0));
        assertEquals("only", frames.at(Long.MAX_VALUE));
    }

    @Test
    void emptyAndBadInput() {
        assertTrue(Frames.EMPTY.isEmpty());
        assertEquals("", Frames.EMPTY.at(5));
        assertEquals(-1, Frames.EMPTY.index(5));
        final Frames zeroInterval = new Frames(List.of("a", "b"), 0);
        assertEquals(1, zeroInterval.intervalTicks());
        assertEquals("b", zeroInterval.at(1));
        assertEquals("a", Frames.of(5, "a", "b").at(-7));
    }
}
