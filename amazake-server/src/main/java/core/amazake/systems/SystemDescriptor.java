package core.amazake.systems;

import core.amazake.systems.api.SystemInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The parsed and validated {@code system.yml} of a .azs file: the API's {@link SystemInfo} plus what only the core
 * reads ({@code conflicts}: plugin names the system fights with). Pure: no Bukkit, unit-tested.
 */
public record SystemDescriptor(SystemInfo info, List<String> conflicts) {

    /** The descriptor file at the root of a .azs. */
    public static final String FILE = "system.yml";
    /** The {@code api-version} values this core runs. */
    public static final List<String> SUPPORTED_API_VERSIONS = List.of("2");
    /** System names: also folder names and command namespaces. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    /** Known plugins that do the same job as a system, for systems whose system.yml does not say. */
    private static final Map<String, List<String>> KNOWN_CONFLICTS = Map.of("AmazakeTab", List.of("TAB"));

    public SystemDescriptor {
        conflicts = List.copyOf(conflicts);
    }

    public String name() {
        return this.info.name();
    }

    public String version() {
        return this.info.version();
    }

    /** A system.yml that is missing, unreadable or invalid; the message is for server owners (Russian). */
    public static final class InvalidException extends Exception {
        public InvalidException(final String message) {
            super(message);
        }

        public InvalidException(final String message, final Throwable cause) {
            super(message, cause);
        }

        /** Whether the file is a system built for an older systems API than this core runs (api-version too low). */
        public boolean outdatedApi() {
            return this.outdatedApi;
        }

        private boolean outdatedApi;
    }

    /** Reads {@code system.yml} from a .azs file. */
    public static SystemDescriptor read(final Path azs) throws InvalidException {
        try (ZipFile zip = new ZipFile(azs.toFile(), StandardCharsets.UTF_8)) {
            final ZipEntry entry = zip.getEntry(FILE);
            if (entry == null) {
                throw new InvalidException("внутри нет файла " + FILE + " (это точно система Amazake?)");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(in);
            }
        } catch (final IOException e) {
            throw new InvalidException("не удалось прочитать файл как zip/jar: " + e.getMessage(), e);
        }
    }

    /** Parses system.yml text. */
    public static SystemDescriptor parse(final InputStream in) throws InvalidException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            final Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(yaml instanceof Map<?, ?> map)) {
                throw new InvalidException(FILE + " пустой или не является набором ключей");
            }
            return fromMap(map);
        } catch (final InvalidException e) {
            throw e;
        } catch (final Exception e) {
            throw new InvalidException("ошибка в " + FILE + ": " + e.getMessage(), e);
        }
    }

    public static SystemDescriptor parse(final String text) throws InvalidException {
        return parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether {@code apiVersion} is a number below every supported one (a system made for an older core). */
    static boolean olderThanSupported(final String apiVersion) {
        try {
            final int v = Integer.parseInt(apiVersion.trim());
            return SUPPORTED_API_VERSIONS.stream().mapToInt(Integer::parseInt).allMatch(supported -> v < supported);
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /** Validates the keys of a loaded system.yml. */
    static SystemDescriptor fromMap(final Map<?, ?> map) throws InvalidException {
        final String name = required(map, "name");
        if (!NAME.matcher(name).matches()) {
            throw new InvalidException("name «" + name + "»: допустимы латинские буквы, цифры, «_» и «-», от 1 до 32 символов");
        }
        final String version = required(map, "version");
        final String main = required(map, "main");
        if (!CLASS_NAME.matcher(main).matches()) {
            throw new InvalidException("main «" + main + "» не похож на полное имя класса (пакет.Класс)");
        }
        if (main.startsWith("core.amazake.systems.api.") || main.startsWith("org.bukkit.") || main.startsWith("net.minecraft.")) {
            throw new InvalidException("main «" + main + "»: класс системы не может лежать в пакете ядра или Bukkit");
        }
        final String apiVersion = required(map, "api-version");
        if (!SUPPORTED_API_VERSIONS.contains(apiVersion)) {
            final InvalidException e = new InvalidException("api-version " + apiVersion + " не поддерживается этим ядром (поддерживается: "
                + String.join(", ", SUPPORTED_API_VERSIONS) + ");
            e.outdatedApi = olderThanSupported(apiVersion);
            throw e; обновите Amazake или возьмите подходящую версию системы");
        }
        final Object rawDescription = map.get("description");
        final String description = rawDescription == null ? "" : String.valueOf(rawDescription).trim();
        final List<String> authors = stringList(map, "authors");
        if (authors.isEmpty() && map.get("author") != null) {
            authors.add(String.valueOf(map.get("author")).trim());
        }
        final List<String> conflicts = stringList(map, "conflicts");
        if (conflicts.isEmpty()) {
            conflicts.addAll(KNOWN_CONFLICTS.getOrDefault(name, List.of()));
        }
        return new SystemDescriptor(new SystemInfo(name, version, main, description, List.copyOf(authors), apiVersion), conflicts);
    }

    private static String required(final Map<?, ?> map, final String key) throws InvalidException {
        final Object value = map.get(key);
        if (value == null || value instanceof Map || value instanceof Collection) {
            throw new InvalidException("в " + FILE + " нет ключа «" + key + "» (или это не строка)");
        }
        final String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new InvalidException("в " + FILE + " ключ «" + key + "» пустой");
        }
        return text;
    }

    private static List<String> stringList(final Map<?, ?> map, final String key) throws InvalidException {
        final Object value = map.get(key);
        final List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof Collection<?> list) {
            for (final Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (value instanceof Map) {
            throw new InvalidException("в " + FILE + " ключ «" + key + "» должен быть списком");
        } else if (!String.valueOf(value).isBlank()) {
            out.add(String.valueOf(value).trim());
        }
        return out;
    }

    /**
     * Compares two versions: numeric parts as numbers, other parts as text, a missing part counts as 0
     * ({@code 1.0 == 1.0.0 < 1.0.1 < 1.1 < 1.10}); a release is newer than its pre-release ({@code 1.0.0-beta < 1.0.0}).
     */
    public static int compareVersions(final String a, final String b) {
        final String[] mainA = a.trim().split("-", 2);
        final String[] mainB = b.trim().split("-", 2);
        final int main = compareParts(mainA[0], mainB[0]);
        if (main != 0) {
            return main;
        }
        final boolean preA = mainA.length > 1;
        final boolean preB = mainB.length > 1;
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return preA ? compareParts(mainA[1], mainB[1]) : 0;
    }

    private static int compareParts(final String a, final String b) {
        final String[] pa = a.split("[.+]");
        final String[] pb = b.split("[.+]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            final String x = i < pa.length ? pa[i] : "0";
            final String y = i < pb.length ? pb[i] : "0";
            final boolean nx = x.matches("\\d{1,18}");
            final boolean ny = y.matches("\\d{1,18}");
            final int c;
            if (nx && ny) {
                c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (nx != ny) {
                c = nx ? 1 : -1; // "1.0.1" > "1.0.rc"
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return Integer.signum(c);
            }
        }
        return 0;
    }
}
