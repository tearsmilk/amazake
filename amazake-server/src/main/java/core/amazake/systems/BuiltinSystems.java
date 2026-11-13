package core.amazake.systems;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The systems shipped inside the server jar ({@code systems/<name>.azs} resources) and their extraction into the
 * {@code systems/} folder. The file logic is independent of Bukkit (the jar is reached through a {@link Source}),
 * so it is unit-tested.
 */
public final class BuiltinSystems {

    /** Built-in systems, by name; each is the resource {@code systems/<name>.azs} of the server jar. */
    public static final List<String> NAMES = List.of("AmazakeTab");

    private BuiltinSystems() {
    }

    /** Opens a built-in's .azs, or returns null when this jar does not carry it. */
    @FunctionalInterface
    public interface Source {
        InputStream open(String name) throws IOException;

        /** The server jar's resources. */
        Source JAR = name -> BuiltinSystems.class.getClassLoader().getResourceAsStream(resource(name));
    }

    public static String resource(final String name) {
        return "systems/" + name + ".azs";
    }

    public static String fileName(final String name) {
        return name + ".azs";
    }

    /**
     * What {@link #install} did: built-ins written to the folder, built-ins whose file was already there (a file of
     * that name is never overwritten; the name is still recorded) and built-ins this jar does not carry.
     */
    public record Outcome(List<String> extracted, List<String> keptExisting, List<String> missing, List<String> upgraded) {
    }

    /**
     * Extracts every built-in whose name is not in {@code .installed} and records it there, so a built-in the owner
     * deleted is not extracted again. A built-in's file that was made for an older systems API (it cannot run on this
     * core at all) is replaced by the jar's copy; the old file is kept next to it as {@code <name>.azs.old}.
     */
    public static Outcome install(final Path dir, final Collection<String> builtins, final Source source) throws IOException {
        Files.createDirectories(dir);
        final Set<String> installed = SystemsState.readInstalled(dir);
        final List<String> extracted = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        final List<String> upgraded = new ArrayList<>();
        boolean changed = false;
        for (final String name : builtins) {
            if (installed.contains(name)) {
                if (replaceOutdated(dir.resolve(fileName(name)), name, source)) {
                    upgraded.add(name);
                }
                continue;
            }
            try (InputStream in = source.open(name)) {
                if (in == null) {
                    missing.add(name);
                    continue;
                }
                final Path target = dir.resolve(fileName(name));
                if (Files.exists(target)) {
                    if (replaceOutdated(target, name, source)) {
                        upgraded.add(name);
                    } else {
                        kept.add(name);
                    }
                } else {
                    write(target, in);
                    extracted.add(name);
                }
            }
            installed.add(name);
            changed = true;
        }
        if (changed) {
            SystemsState.writeInstalled(dir, installed);
        }
        return new Outcome(extracted, kept, missing, upgraded);
    }

    /**
     * Replaces {@code target} by the jar's copy when the file is a system made for an older systems API than this core
     * runs and the jar carries a copy this core can run; the old file is moved to {@code <file>.old}.
     */
    static boolean replaceOutdated(final Path target, final String name, final Source source) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try {
            SystemDescriptor.read(target);
            return false;
        } catch (final SystemDescriptor.InvalidException e) {
            if (!e.outdatedApi()) {
                return false;
            }
        }
        try {
            if (describe(name, source) == null) {
                return false;
            }
        } catch (final SystemDescriptor.InvalidException e) {
            return false;
        }
        try (InputStream in = source.open(name)) {
            if (in == null) {
                return false;
            }
            Files.move(target, target.resolveSibling(target.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            write(target, in);
        }
        return true;
    }

    /** Writes {@code in} to {@code target} through a temporary file, replacing it. */
    public static void write(final Path target, final InputStream in) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The system.yml of a .azs given as a stream (a jar resource), or null when the built-in is not in the jar. */
    public static SystemDescriptor describe(final String name, final Source source) throws IOException, SystemDescriptor.InvalidException {
        try (InputStream in = source.open(name)) {
            if (in == null) {
                return null;
            }
            final ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(SystemDescriptor.FILE)) {
                    return SystemDescriptor.parse(zip);
                }
            }
            throw new SystemDescriptor.InvalidException("во встроенной системе " + name + " нет " + SystemDescriptor.FILE);
        }
    }

    /** Whether the jar's built-in is newer than the file in the folder. */
    public static boolean newer(final SystemDescriptor inJar, final SystemDescriptor inFolder) {
        return inJar != null && inFolder != null && SystemDescriptor.compareVersions(inJar.version(), inFolder.version()) > 0;
    }
}
