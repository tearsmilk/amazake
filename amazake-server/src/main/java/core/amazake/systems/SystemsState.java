package core.amazake.systems;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The files in {@code systems/} that hold the core's own state: {@code systems.yml} (which systems are enabled) and
 * {@code .installed} (which built-in systems were already extracted). Pure file handling, unit-tested.
 */
public final class SystemsState {

    public static final String STATE_FILE = "systems.yml";
    public static final String INSTALLED_FILE = ".installed";

    private SystemsState() {
    }

    /** The enabled systems listed in systems.yml, in file order; empty when the file is missing. */
    public static Set<String> readEnabled(final Path dir) throws IOException {
        final Path file = dir.resolve(STATE_FILE);
        final Set<String> names = new LinkedHashSet<>();
        if (!Files.isRegularFile(file)) {
            return names;
        }
        final Object yaml;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (final RuntimeException e) {
            throw new IOException(STATE_FILE + ": " + e.getMessage(), e);
        }
        if (yaml instanceof Map<?, ?> map && map.get("enabled") instanceof Collection<?> list) {
            for (final Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    names.add(String.valueOf(item).trim());
                }
            }
        }
        return names;
    }

    /** Writes systems.yml (atomically: a temporary file moved over the old one). */
    public static void writeEnabled(final Path dir, final Collection<String> enabled) throws IOException {
        final List<String> sorted = new ArrayList<>(new LinkedHashSet<>(enabled));
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        final StringBuilder text = new StringBuilder()
            .append("# Включённые системы ядра Amazake. Файл переписывается командами\n")
            .append("# /amazake system enable|disable <имя>; система, которой нет в списке, выключена.\n")
            .append("# При запуске сервер включает все системы из списка.\n");
        if (sorted.isEmpty()) {
            text.append("enabled: []\n");
        } else {
            text.append("enabled:\n");
            for (final String name : sorted) {
                text.append("  - ").append(name).append('\n');
            }
        }
        writeAtomically(dir.resolve(STATE_FILE), text.toString());
    }

    /** The names in .installed (one per line, {@code #} starts a comment); empty when the file is missing. */
    public static Set<String> readInstalled(final Path dir) throws IOException {
        final Path file = dir.resolve(INSTALLED_FILE);
        final Set<String> names = new LinkedHashSet<>();
        if (!Files.isRegularFile(file)) {
            return names;
        }
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final int hash = line.indexOf('#');
            final String name = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    public static void writeInstalled(final Path dir, final Collection<String> names) throws IOException {
        final StringBuilder text = new StringBuilder()
            .append("# Встроенные системы, которые ядро уже распаковало в эту папку.\n")
            .append("# Удалённая встроенная система не распаковывается снова, пока её имя здесь.\n")
            .append("# Чтобы вернуть её, удалите строку с её именем и перезапустите сервер.\n");
        for (final String name : new LinkedHashSet<>(names)) {
            text.append(name).append('\n');
        }
        writeAtomically(dir.resolve(INSTALLED_FILE), text.toString());
    }

    static void writeAtomically(final Path file, final String text) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
