package core.amazake.core.modules.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Разбор движка из конфига/команд. */
class HologramEngineTest {

    @Test
    void parsesKnownIdsCaseInsensitive() {
        assertEquals(HologramEngine.ARMOR_STAND, HologramEngine.parseOrNull("armor_stand"));
        assertEquals(HologramEngine.TEXT_DISPLAY, HologramEngine.parseOrNull("TEXT_DISPLAY"));
        assertEquals(HologramEngine.ITEM_DISPLAY, HologramEngine.parseOrNull(" Item_Display "));
    }

    @Test
    void unknownIsNull() {
        assertNull(HologramEngine.parseOrNull("floating"));
        assertNull(HologramEngine.parseOrNull(null));
        assertNull(HologramEngine.parseOrNull(""));
    }

    @Test
    void fallbackUsedForUnknown() {
        assertEquals(HologramEngine.TEXT_DISPLAY, HologramEngine.parse("nope", HologramEngine.TEXT_DISPLAY));
        assertEquals(HologramEngine.ARMOR_STAND, HologramEngine.parse(null, HologramEngine.ARMOR_STAND));
        assertEquals(HologramEngine.ITEM_DISPLAY, HologramEngine.parse("item_display", HologramEngine.TEXT_DISPLAY));
    }

    @Test
    void idRoundTrips() {
        for (final HologramEngine e : HologramEngine.values()) {
            assertEquals(e, HologramEngine.parseOrNull(e.id()));
        }
    }
}
