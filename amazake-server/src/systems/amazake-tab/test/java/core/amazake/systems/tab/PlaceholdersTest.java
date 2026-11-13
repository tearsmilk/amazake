package core.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private static final Function<String, String> VALUES = Map.of("player", "Takuyamo", "online", "7", "ping", "42")::get;

    @Test
    void replacesKnownPlaceholders() {
        assertEquals("Takuyamo: 7 online", Placeholders.apply("%player%: %online% online", VALUES));
        assertEquals("42мс", Placeholders.apply("%ping%мс", VALUES));
    }

    @Test
    void namesAreCaseInsensitive() {
        assertEquals("Takuyamo", Placeholders.apply("%PLAYER%", VALUES));
    }

    @Test
    void unknownPlaceholdersStayForLaterPasses() {
        assertEquals("%vault_rank% Takuyamo", Placeholders.apply("%vault_rank% %player%", VALUES));
    }

    @Test
    void lonePercentSignsAreLiteral() {
        assertEquals("50% of 7", Placeholders.apply("50% of %online%", VALUES));
        assertEquals("100%", Placeholders.apply("100%", VALUES));
        assertEquals("% %", Placeholders.apply("% %", VALUES));
        assertEquals("%%7", Placeholders.apply("%%%online%", VALUES));
    }

    @Test
    void valuesAreNotExpandedAgain() {
        final Function<String, String> nasty = Map.of("a", "%b%", "b", "x")::get;
        assertEquals("%b%x", Placeholders.apply("%a%%b%", nasty));
    }

    @Test
    void emptyAndNullTemplates() {
        assertEquals("", Placeholders.apply("", VALUES));
        assertEquals("", Placeholders.apply(null, VALUES));
        assertEquals("plain", Placeholders.apply("plain", VALUES));
    }

    @Test
    void detectsPlaceholders() {
        assertTrue(Placeholders.hasPlaceholder("a %b% c"));
        assertFalse(Placeholders.hasPlaceholder("50%"));
        assertFalse(Placeholders.hasPlaceholder("%%"));
    }

    @Test
    void decimalsUseADot() {
        assertEquals("19.97", Placeholders.decimal(19.971, 2));
        assertEquals("20.0", Placeholders.decimal(20, 1));
        assertEquals("0", Placeholders.decimal(Double.NaN, 1));
    }
}
