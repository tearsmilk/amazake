package ink.hoka.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LegacyColorsTest {

    @Test
    void sectionCodes() {
        assertEquals("<red>[Admin] <reset>", LegacyColors.toMiniMessage("§c[Admin] §r", false));
        assertEquals("<gold><bold>VIP", LegacyColors.toMiniMessage("§6§LVIP", false));
    }

    @Test
    void ampersandOnlyWhenAsked() {
        assertEquals("&cA", LegacyColors.toMiniMessage("&cA", false));
        assertEquals("<red>A", LegacyColors.toMiniMessage("&cA", true));
        assertEquals("Tom & Jerry", LegacyColors.toMiniMessage("Tom & Jerry", true));
        assertEquals("a&", LegacyColors.toMiniMessage("a&", true));
    }

    @Test
    void hexColours() {
        assertEquals("<#ff8800>x", LegacyColors.toMiniMessage("&#ff8800x", true));
        assertEquals("<#ff8800>x", LegacyColors.toMiniMessage("§x§f§f§8§8§0§0x", false));
        // broken hex sequences are left alone apart from the codes that are valid on their own
        assertEquals("&#ff88", LegacyColors.toMiniMessage("&#ff88", true));
    }

    @Test
    void plainTextUntouched() {
        assertEquals("", LegacyColors.toMiniMessage(null, true));
        assertEquals("hello", LegacyColors.toMiniMessage("hello", true));
    }
}
