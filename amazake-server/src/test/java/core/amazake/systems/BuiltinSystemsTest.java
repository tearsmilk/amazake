package core.amazake.systems;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Extraction of built-in systems into systems/ and the newer-version check. */
class BuiltinSystemsTest {

    private static BuiltinSystems.Source source(final Map<String, byte[]> files) {
        return name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name)) : null;
    }

    private static byte[] azs(final Path tmp, final String version) throws IOException {
        final Path file = SystemDescriptorTest.azs(tmp.resolve("src-" + version + ".azs"),
            SystemDescriptorTest.VALID.replace("version: 1.0.0", "version: " + version));
        return Files.readAllBytes(file);
    }

    @Test
    void extractsOnceAndRemembers(@TempDir final Path tmp) throws Exception {
        final Path dir = tmp.resolve("systems");
        final byte[] v1 = azs(tmp, "1.0.0");
        final BuiltinSystems.Source jar = source(Map.of("AmazakeTab", v1));

        BuiltinSystems.Outcome first = BuiltinSystems.install(dir, List.of("AmazakeTab", "Missing"), jar);
        assertEquals(List.of("AmazakeTab"), first.extracted());
        assertEquals(List.of("Missing"), first.missing());
        assertArrayEquals(v1, Files.readAllBytes(dir.resolve("AmazakeTab.azs")));
        assertEquals(List.of("AmazakeTab"), List.copyOf(SystemsState.readInstalled(dir)));

        // Second start: nothing to do.
        BuiltinSystems.Outcome second = BuiltinSystems.install(dir, List.of("AmazakeTab"), jar);
        assertTrue(second.extracted().isEmpty() && second.keptExisting().isEmpty());

        // The owner deleted it: it stays deleted.
        Files.delete(dir.resolve("AmazakeTab.azs"));
        BuiltinSystems.install(dir, List.of("AmazakeTab"), jar);
        assertFalse(Files.exists(dir.resolve("AmazakeTab.azs")));

        // Removed from .installed: extracted again.
        SystemsState.writeInstalled(dir, List.of());
        assertEquals(List.of("AmazakeTab"), BuiltinSystems.install(dir, List.of("AmazakeTab"), jar).extracted());
        assertTrue(Files.exists(dir.resolve("AmazakeTab.azs")));
    }

    @Test
    void neverOverwritesTheOwnersFile(@TempDir final Path tmp) throws Exception {
        final Path dir = Files.createDirectories(tmp.resolve("systems"));
        Files.writeString(dir.resolve("AmazakeTab.azs"), "mine");
        final BuiltinSystems.Outcome outcome = BuiltinSystems.install(dir, List.of("AmazakeTab"), source(Map.of("AmazakeTab", azs(tmp, "1.0.0"))));
        assertEquals(List.of("AmazakeTab"), outcome.keptExisting());
        assertEquals("mine", Files.readString(dir.resolve("AmazakeTab.azs")));
        assertEquals(List.of("AmazakeTab"), List.copyOf(SystemsState.readInstalled(dir)));
    }

    @Test
    void replacesABuiltinMadeForAnOlderApi(@TempDir final Path tmp) throws Exception {
        final Path dir = Files.createDirectories(tmp.resolve("systems"));
        final Path old = SystemDescriptorTest.azs(tmp.resolve("old.azs"),
            SystemDescriptorTest.VALID.replace("api-version: 2", "api-version: 1"));
        Files.copy(old, dir.resolve("AmazakeTab.azs"));
        final byte[] current = azs(tmp, "2.0.0");
        final BuiltinSystems.Source jar = source(Map.of("AmazakeTab", current));

        // First start of this core on a folder from an older core (not yet in .installed, file present).
        BuiltinSystems.Outcome outcome = BuiltinSystems.install(dir, List.of("AmazakeTab"), jar);
        assertEquals(List.of("AmazakeTab"), outcome.upgraded());
        assertTrue(outcome.keptExisting().isEmpty());
        assertArrayEquals(current, Files.readAllBytes(dir.resolve("AmazakeTab.azs")));
        assertArrayEquals(Files.readAllBytes(old), Files.readAllBytes(dir.resolve("AmazakeTab.azs.old")));

        // Already in .installed: still upgraded.
        Files.copy(old, dir.resolve("AmazakeTab.azs"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(List.of("AmazakeTab"), BuiltinSystems.install(dir, List.of("AmazakeTab"), jar).upgraded());
        assertArrayEquals(current, Files.readAllBytes(dir.resolve("AmazakeTab.azs")));

        // A current file is left alone.
        assertTrue(BuiltinSystems.install(dir, List.of("AmazakeTab"), jar).upgraded().isEmpty());
    }

    @Test
    void describesAndComparesTheJarCopy(@TempDir final Path tmp) throws Exception {
        final BuiltinSystems.Source jar = source(Map.of("AmazakeTab", azs(tmp, "1.2.0")));
        final SystemDescriptor inJar = BuiltinSystems.describe("AmazakeTab", jar);
        assertEquals("1.2.0", inJar.version());
        assertNull(BuiltinSystems.describe("Missing", jar));
        final SystemDescriptor old = SystemDescriptor.parse(SystemDescriptorTest.VALID);
        assertTrue(BuiltinSystems.newer(inJar, old));
        assertFalse(BuiltinSystems.newer(old, inJar));
        assertFalse(BuiltinSystems.newer(old, old));
        assertFalse(BuiltinSystems.newer(null, old));
    }
}
