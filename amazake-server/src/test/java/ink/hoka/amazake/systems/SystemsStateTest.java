package ink.hoka.amazake.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** systems.yml and .installed. */
class SystemsStateTest {

    @Test
    void missingFilesAreEmpty(@TempDir final Path dir) throws IOException {
        assertTrue(SystemsState.readEnabled(dir).isEmpty());
        assertTrue(SystemsState.readInstalled(dir).isEmpty());
    }

    @Test
    void enabledRoundTrip(@TempDir final Path dir) throws IOException {
        SystemsState.writeEnabled(dir, List.of("Zeta", "AmazakeTab", "alpha", "AmazakeTab"));
        assertEquals(List.of("alpha", "AmazakeTab", "Zeta"), List.copyOf(SystemsState.readEnabled(dir)));
        final String text = Files.readString(dir.resolve(SystemsState.STATE_FILE));
        assertTrue(text.startsWith("#"), "a comment for owners");
        assertFalse(Files.exists(dir.resolve(SystemsState.STATE_FILE + ".tmp")));

        SystemsState.writeEnabled(dir, List.of());
        assertTrue(SystemsState.readEnabled(dir).isEmpty());
        assertTrue(Files.readString(dir.resolve(SystemsState.STATE_FILE)).contains("enabled: []"));
    }

    @Test
    void enabledHandWritten(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve(SystemsState.STATE_FILE), "enabled: [A, ' B ', null]\nother: 1\n");
        assertEquals(Set.of("A", "B"), SystemsState.readEnabled(dir));
        Files.writeString(dir.resolve(SystemsState.STATE_FILE), "enabled: A\n");
        assertTrue(SystemsState.readEnabled(dir).isEmpty(), "not a list: nothing enabled");
        Files.writeString(dir.resolve(SystemsState.STATE_FILE), "enabled: [a\n");
        assertThrows(IOException.class, () -> SystemsState.readEnabled(dir));
    }

    @Test
    void installedRoundTrip(@TempDir final Path dir) throws IOException {
        SystemsState.writeInstalled(dir, List.of("AmazakeTab", "Other"));
        assertEquals(List.of("AmazakeTab", "Other"), List.copyOf(SystemsState.readInstalled(dir)));
        Files.writeString(dir.resolve(SystemsState.INSTALLED_FILE), "# comment\n\n  AmazakeTab  # tail\nX\n");
        assertEquals(List.of("AmazakeTab", "X"), List.copyOf(SystemsState.readInstalled(dir)));
    }
}
