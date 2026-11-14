package core.amazake.core.modules.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.core.modules.chat.ChatRouting.Channel;
import core.amazake.core.modules.chat.ChatRouting.Decision;
import core.amazake.core.modules.chat.ChatRouting.Mode;
import org.junit.jupiter.api.Test;

class ChatRoutingTest {

    @Test
    void modeParseDefaultsToGlobal() {
        assertEquals(Mode.GLOBAL, Mode.parse(null));
        assertEquals(Mode.GLOBAL, Mode.parse(""));
        assertEquals(Mode.GLOBAL, Mode.parse("global"));
        assertEquals(Mode.GLOBAL, Mode.parse("something"));
        assertEquals(Mode.LOCAL, Mode.parse("local"));
        assertEquals(Mode.LOCAL, Mode.parse(" LOCAL "));
    }

    @Test
    void globalModeKeepsPlainMessageGlobal() {
        final Decision d = ChatRouting.decide("привет всем", Mode.GLOBAL, '!', (char) 0);
        assertEquals(Channel.GLOBAL, d.channel());
        assertEquals("привет всем", d.message());
        assertFalse(d.prefixStripped());
    }

    @Test
    void globalModeLocalPrefixForcesLocalAndStrips() {
        final Decision d = ChatRouting.decide("=рядом", Mode.GLOBAL, '!', '=');
        assertEquals(Channel.LOCAL, d.channel());
        assertEquals("рядом", d.message());
        assertTrue(d.prefixStripped());
    }

    @Test
    void globalModeNoLocalPrefixConfiguredStaysGlobal() {
        // local-prefix unset ('\0') -> the '=' is just text, nothing forced.
        final Decision d = ChatRouting.decide("=рядом", Mode.GLOBAL, '!', (char) 0);
        assertEquals(Channel.GLOBAL, d.channel());
        assertEquals("=рядом", d.message());
        assertFalse(d.prefixStripped());
    }

    @Test
    void localModeKeepsPlainMessageLocal() {
        final Decision d = ChatRouting.decide("тихо рядом", Mode.LOCAL, '!', (char) 0);
        assertEquals(Channel.LOCAL, d.channel());
        assertEquals("тихо рядом", d.message());
        assertFalse(d.prefixStripped());
    }

    @Test
    void localModeGlobalPrefixForcesGlobalAndStripsLeadingSpace() {
        final Decision d = ChatRouting.decide("!  громко", Mode.LOCAL, '!', (char) 0);
        assertEquals(Channel.GLOBAL, d.channel());
        assertEquals("громко", d.message());
        assertTrue(d.prefixStripped());
    }

    @Test
    void emptyMessageIsRoutedByModeWithoutStripping() {
        assertEquals(Channel.GLOBAL, ChatRouting.decide("", Mode.GLOBAL, '!', '=').channel());
        assertEquals(Channel.LOCAL, ChatRouting.decide("", Mode.LOCAL, '!', '=').channel());
        assertFalse(ChatRouting.decide("", Mode.LOCAL, '!', '=').prefixStripped());
    }

    @Test
    void nullMessageTreatedAsEmpty() {
        final Decision d = ChatRouting.decide(null, Mode.LOCAL, '!', (char) 0);
        assertEquals(Channel.LOCAL, d.channel());
        assertEquals("", d.message());
    }

    @Test
    void firstCharHelperHandlesEmptyAndNull() {
        assertEquals((char) 0, ChatSettings.firstChar(null));
        assertEquals((char) 0, ChatSettings.firstChar(""));
        assertEquals('!', ChatSettings.firstChar("!"));
        assertEquals('=', ChatSettings.firstChar("=local"));
    }
}
