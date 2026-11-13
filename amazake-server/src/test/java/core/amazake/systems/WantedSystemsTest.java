package core.amazake.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wanted set behind systems.yml: a system that is listed but not running must survive other toggles. */
class WantedSystemsTest {

    @Test
    void failedSystemStaysListedWhenAnotherIsToggled(@TempDir final Path dir) throws IOException {
        SystemsState.writeEnabled(dir, List.of("AmazakeTab", "Foo"));
        final WantedSystems wanted = new WantedSystems();
        wanted.replace(SystemsState.readEnabled(dir));
        // AmazakeTab fails to enable at startup: nothing changes. The owner disables Foo (persisted).
        assertTrue(wanted.remove("Foo"));
        SystemsState.writeEnabled(dir, wanted.sorted());
        assertEquals(List.of("AmazakeTab"), List.copyOf(SystemsState.readEnabled(dir)));
        // And enables Bar (persisted).
        wanted.add("Bar");
        SystemsState.writeEnabled(dir, wanted.sorted());
        assertEquals(List.of("AmazakeTab", "Bar"), List.copyOf(SystemsState.readEnabled(dir)));
    }

    @Test
    void namesIgnoreCase() {
        final WantedSystems wanted = new WantedSystems();
        wanted.replace(List.of("amazaketab", "Zeta"));
        assertTrue(wanted.contains("AmazakeTab"));
        wanted.add("AmazakeTab");
        assertEquals(List.of("AmazakeTab", "Zeta"), wanted.sorted());
        assertTrue(wanted.remove("AMAZAKETAB"));
        assertFalse(wanted.remove("AmazakeTab"));
        assertEquals(List.of("Zeta"), wanted.sorted());
        wanted.replace(List.of());
        assertTrue(wanted.sorted().isEmpty());
    }
}
