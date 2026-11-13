package core.amazake.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.threading.PluginLock;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The synthetic plugin of a system: its description, resources, enabled flag and the plugin lock's view of it. */
class SystemPluginTest {

    @AfterEach
    void reset() {
        PluginLock.configure(PluginLock.Mode.AUTO, false, true, Set.of(), Set.of());
    }

    /** Loaded from a .azs in the class loader test below. */
    public static final class Marker {
    }

    @Test
    void definesClassesWithoutPinningTheLoader(@TempDir final Path dir) throws Exception {
        final Path azs = dir.resolve("Marker.azs");
        final String entry = Marker.class.getName().replace('.', '/') + ".class";
        try (InputStream in = Marker.class.getClassLoader().getResourceAsStream(entry);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(azs))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            zip.write(in.readAllBytes());
            zip.closeEntry();
        }
        try (SystemClassLoader loader = new SystemClassLoader("Marker", azs, ClassLoader.getPlatformClassLoader())) {
            final Class<?> loaded = Class.forName(Marker.class.getName(), false, loader);
            assertTrue(loaded != Marker.class);
            assertEquals(loader, loaded.getClassLoader());
            // Threads snapshot protection domains: the domain must not lead back to the loader.
            assertNull(loaded.getProtectionDomain().getClassLoader());
            assertEquals("core.amazake.systems", loaded.getPackageName());
        }
    }

    @Test
    void describesAndServesOwnResources(@TempDir final Path dir) throws Exception {
        final SystemDescriptor descriptor = SystemDescriptor.parse(SystemDescriptorTest.VALID);
        final Path azs = SystemDescriptorTest.azs(dir.resolve("AmazakeTab.azs"), SystemDescriptorTest.VALID);
        try (SystemClassLoader loader = new SystemClassLoader("AmazakeTab", azs, SystemPluginTest.class.getClassLoader())) {
            final SystemPlugin plugin = new SystemPlugin(descriptor.info(), dir.resolve("AmazakeTab"), loader);
            assertEquals("AmazakeTab", plugin.getName());
            assertEquals("1.0.0", plugin.getPluginMeta().getVersion());
            assertEquals(List.of("Takuyamo", "Someone"), plugin.getPluginMeta().getAuthors());
            assertEquals("Таб и скорборд", plugin.getPluginMeta().getDescription());
            assertFalse(plugin.isEnabled());
            plugin.setEnabled(true);
            assertTrue(plugin.isEnabled());
            try (InputStream in = plugin.getResource("config.yml")) {
                assertNotNull(in);
                assertEquals("a: 1\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            // Only the .azs itself, never the server's resources.
            assertNull(plugin.getResource("amazake-not-there.yml"));
            plugin.saveDefaultConfig();
            assertEquals("a: 1\n", Files.readString(dir.resolve("AmazakeTab/config.yml")));
            assertEquals(1, plugin.getConfig().getInt("a"));

            // Systems are thread-safe by contract: never under the plugin lock, even when everything is forced.
            PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, true, false, Set.of(), Set.of("amazaketab"));
            assertFalse(PluginLock.needsLock(plugin));
        }
    }
}
