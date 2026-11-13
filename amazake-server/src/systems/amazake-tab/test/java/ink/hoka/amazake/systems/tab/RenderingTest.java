package ink.hoka.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The MiniMessage side of the templates (no server needed). */
class RenderingTest {

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void tabNameFormat() {
        final Component name = TabRuntime.parse("<prefix><name><suffix>",
            Placeholder.parsed("prefix", "<red>[Админ] "), Placeholder.parsed("suffix", " <gray>*"),
            Placeholder.unparsed("name", "Takuyamo"));
        assertEquals("[Админ] Takuyamo *", plain(name));
    }

    @Test
    void prefixColourCarriesOverToTheName() {
        final Component name = TabRuntime.parse("<prefix><name>",
            Placeholder.parsed("prefix", "<red>[A] "), Placeholder.unparsed("name", "Steve"));
        // a colour left open in the prefix applies to the name, like legacy prefixes do
        assertEquals("<red>[A] Steve", MiniMessage.miniMessage().serialize(name).replace("</red>", ""));
    }

    @Test
    void namesAreNotParsedAsTags() {
        final Component name = TabRuntime.parse("<name>", Placeholder.unparsed("name", "<red>x"));
        assertEquals("<red>x", plain(name));
    }

    @Test
    void legacyPrefixesBecomeMiniMessage() {
        final Component c = TabRuntime.parse(LegacyColors.toMiniMessage("&c[VIP] &f", true) + "Bob");
        assertEquals("[VIP] Bob", plain(c));
    }

    @Test
    void multiLineHeader() {
        assertEquals("a\nb", plain(TabRuntime.parse("<red>a\n<gray>b")));
        assertEquals(Component.empty(), TabRuntime.parse(""));
    }

    @Test
    void sidebarLineEntriesAreUniqueAndOrdered() {
        assertEquals("#aztab-00", PlayerView.lineEntry(0));
        assertEquals("#aztab-14", PlayerView.lineEntry(14));
    }
}
