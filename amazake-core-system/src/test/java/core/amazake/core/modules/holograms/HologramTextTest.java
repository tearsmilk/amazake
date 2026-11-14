package core.amazake.core.modules.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Сборка текста голограммы и проверка имени (Adventure доступен из paper-api в тестах). */
class HologramTextTest {

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void emptyLinesGiveEmptyComponent() {
        assertEquals("", plain(HologramText.joined(List.of())));
    }

    @Test
    void linesJoinedWithNewlines() {
        final Component c = HologramText.joined(List.of("<red>Привет", "<green>мир"));
        assertEquals("Привет\nмир", plain(c));
    }

    @Test
    void singleLineHasNoNewline() {
        assertEquals("one", plain(HologramText.joined(List.of("one"))));
    }

    @Test
    void miniMessageStylingParsed() {
        // Разметка должна исчезнуть из простого текста, а содержимое — остаться.
        final Component c = HologramText.joined(List.of("<bold><gold>Магазин</gold></bold>"));
        assertEquals("Магазин", plain(c));
    }

    @Test
    void nameNormalizationAndValidation() {
        assertEquals("shop", HologramText.normalizeName(" Shop "));
        assertTrue(HologramText.validName("shop_1"));
        assertTrue(HologramText.validName("a-b-c"));
        assertFalse(HologramText.validName(""));
        assertFalse(HologramText.validName("has space"));
        assertFalse(HologramText.validName("ПриветМир"));
        assertFalse(HologramText.validName("x".repeat(HologramText.MAX_NAME + 1)));
        assertTrue(HologramText.validName("x".repeat(HologramText.MAX_NAME)));
    }
}
