package core.amazake.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Sorts threads into the groups of docs/profiler-format.md by name, first matching rule wins. */
final class ThreadGroups {

    static final String MAIN = "main";
    static final String CHUNK_WORKERS = "chunk-workers";
    static final String CHUNK_IO = "chunk-io";
    static final String NETTY = "netty";
    static final String GC = "gc";
    static final String JIT = "jit";
    static final String PLUGINS_OWN = "plugins-own";
    static final String OTHER = "other";

    private record Rule(String group, List<Pattern> patterns) {
        boolean matches(final String name) {
            for (final Pattern pattern : this.patterns) {
                if (pattern.matcher(name).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Rule> RULES = List.of(
        rule(MAIN, "Server thread"),
        rule(CHUNK_WORKERS, "* Common Worker #*"),
        rule(CHUNK_IO, "* I/O Worker #*"),
        rule(NETTY, "Netty *"),
        rule(GC, "GC Thread*", "G1 *", "GC *", "VM Thread", "ZGC*", "Shenandoah*"),
        rule(JIT, "C1 CompilerThre*", "C2 CompilerThre*", "C1 Compiler*", "C2 Compiler*"),
        rule("plugins-async", "Craft Scheduler Thread*", "Folia Async Scheduler*", "Craft Async Scheduler*"),
        rule("chat", "Async Chat Thread*", "Async Tab Complete*"),
        rule("plugins-login", "User Authenticator*", "Configuration Thread*", "Paper Async Command*"),
        rule("paper-async", "Paper Async *", "Paper Plugin Remapper*",
            // Server threads that run no plugin code.
            "Server console *", "Paper Watchdog *", "Server Watchdog*", "Timer hack thread*", "Chat-Filter-Worker*", "Download-*"),
        rule("spark", "spark-*"),
        rule("amazake", "Amazake *")
    );

    private ThreadGroups() {
    }

    static String classify(final String name) {
        for (final Rule rule : RULES) {
            if (rule.matches(name)) {
                return rule.group();
            }
        }
        return OTHER;
    }

    /**
     * {@link #classify(String)}, and {@value #PLUGINS_OWN} instead of {@value #OTHER} for a Java thread the
     * core layout found to be created by a plugin (cpu.PluginThreads, by class loader and stack).
     */
    static String classify(final String name, final long javaId) {
        final String group = classify(name);
        if (OTHER.equals(group) && javaId >= 0L && core.amazake.cpu.Cores.pluginThreadIds().contains(javaId)) {
            return PLUGINS_OWN;
        }
        return group;
    }

    /** GC and JIT threads are not Java threads: their CPU is only visible in /proc. */
    static boolean isJvmInternal(final String group) {
        return GC.equals(group) || JIT.equals(group);
    }

    private static Rule rule(final String group, final String... globs) {
        final List<Pattern> patterns = new ArrayList<>(globs.length);
        for (final String glob : globs) {
            patterns.add(glob(glob));
        }
        return new Rule(group, List.copyOf(patterns));
    }

    /** "*" matches any run of characters (also none); everything else is literal. */
    private static Pattern glob(final String glob) {
        final StringBuilder regex = new StringBuilder();
        final String[] parts = glob.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
