package core.amazake.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** system.yml: parsing, validation, version comparison. */
class SystemDescriptorTest {

    static final String VALID = """
        name: AmazakeTab
        version: 1.0.0
        main: core.amazake.systems.tab.TabSystem
        description: Таб и скорборд
        authors: [Takuyamo, Someone]
        api-version: 2
        """;

    @Test
    void parsesValid() throws Exception {
        final SystemDescriptor d = SystemDescriptor.parse(VALID);
        assertEquals("AmazakeTab", d.name());
        assertEquals("1.0.0", d.version());
        assertEquals("core.amazake.systems.tab.TabSystem", d.info().main());
        assertEquals("Таб и скорборд", d.info().description());
        assertEquals(List.of("Takuyamo", "Someone"), d.info().authors());
        assertEquals("2", d.info().apiVersion());
        // AmazakeTab fights with the TAB plugin even when its system.yml does not say so.
        assertEquals(List.of("TAB"), d.conflicts());
    }

    @Test
    void optionalKeys() throws Exception {
        final SystemDescriptor d = SystemDescriptor.parse("""
            name: my-system_2
            version: 0.1
            main: a.b.Main
            author: Solo
            api-version: "2"
            conflicts: Other
            """);
        assertEquals("", d.info().description());
        assertEquals(List.of("Solo"), d.info().authors());
        assertEquals(List.of("Other"), d.conflicts());
        assertEquals("0.1", d.version());
    }

    @Test
    void marksSystemsForAnOlderApi() {
        final SystemDescriptor.InvalidException old = org.junit.jupiter.api.Assertions.assertThrows(SystemDescriptor.InvalidException.class,
            () -> SystemDescriptor.parse("name: ok\nversion: 1\nmain: a.b.C\napi-version: 1\n"));
        assertTrue(old.outdatedApi());
        final SystemDescriptor.InvalidException newer = org.junit.jupiter.api.Assertions.assertThrows(SystemDescriptor.InvalidException.class,
            () -> SystemDescriptor.parse("name: ok\nversion: 1\nmain: a.b.C\napi-version: 3\n"));
        assertFalse(newer.outdatedApi());
    }

    @Test
    void rejectsInvalid() {
        final String base = "version: 1\nmain: a.b.C\napi-version: 2\n";
        assertInvalid(base); // no name
        assertInvalid("name: bad name\n" + base);
        assertInvalid("name: " + "x".repeat(33) + "\n" + base);
        assertInvalid("name: ok\nmain: a.b.C\napi-version: 2\n"); // no version
        assertInvalid("name: ok\nversion: 1\napi-version: 2\n"); // no main
        assertInvalid("name: ok\nversion: 1\nmain: NoPackage\napi-version: 2\n");
        assertInvalid("name: ok\nversion: 1\nmain: org.bukkit.Evil\napi-version: 2\n");
        assertInvalid("name: ok\nversion: 1\nmain: a.b.C\n"); // no api-version
        assertInvalid("name: ok\nversion: 1\nmain: a.b.C\napi-version: 3\n"); // newer than this core
        assertInvalid("name: [a, b]\n" + base);
        assertInvalid("just text");
        assertInvalid("");
        assertInvalid("name: ok\nversion: 1\nmain: a.b.C\napi-version: 2\nauthors: {a: b}\n");
        assertInvalid("name: ok\n  bad: : yaml\n");
    }

    private static void assertInvalid(final String yaml) {
        final SystemDescriptor.InvalidException e = assertThrows(SystemDescriptor.InvalidException.class, () -> SystemDescriptor.parse(yaml), yaml);
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
    }

    @Test
    void readsFromAzs(@TempDir final Path dir) throws Exception {
        final Path azs = azs(dir.resolve("x.azs"), VALID);
        assertEquals("AmazakeTab", SystemDescriptor.read(azs).name());
        final Path empty = azs(dir.resolve("empty.azs"), null);
        assertThrows(SystemDescriptor.InvalidException.class, () -> SystemDescriptor.read(empty));
        final Path notZip = Files.writeString(dir.resolve("text.azs"), "not a zip");
        assertThrows(SystemDescriptor.InvalidException.class, () -> SystemDescriptor.read(notZip));
    }

    /** A .azs with the given system.yml (none when null) and one class-like entry. */
    static Path azs(final Path file, final String systemYml) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (systemYml != null) {
                zip.putNextEntry(new ZipEntry(SystemDescriptor.FILE));
                zip.write(systemYml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("config.yml"));
            zip.write("a: 1\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void comparesVersions() {
        assertEquals(0, SystemDescriptor.compareVersions("1.0", "1.0.0"));
        assertEquals(-1, SystemDescriptor.compareVersions("1.0.0", "1.0.1"));
        assertEquals(-1, SystemDescriptor.compareVersions("1.9", "1.10"));
        assertEquals(1, SystemDescriptor.compareVersions("2.0", "1.99.99"));
        assertEquals(-1, SystemDescriptor.compareVersions("1.0.0-beta", "1.0.0"));
        assertEquals(-1, SystemDescriptor.compareVersions("1.0.0-alpha", "1.0.0-beta"));
        assertEquals(1, SystemDescriptor.compareVersions("1.0.1", "1.0.rc"));
        assertEquals(0, SystemDescriptor.compareVersions(" 1.2 ", "1.2"));
    }
}
