package ink.hoka.amazake.cpu;

import java.util.List;

/**
 * Sorts Linux threads into the profiler's thread groups by their kernel name ("comm" in
 * /proc/self/task/TID/comm), and groups into {@link Role}s. Pure logic, no Bukkit: unit-tested on its own.
 *
 * <p>The kernel keeps only the first 15 bytes of a thread's name, so the rules are those of
 * {@code profiler.ThreadGroups} cut to that length: "Paper Common Worker #3" is "Paper Common Wo",
 * "Netty Epoll Server IO #0" is "Netty Epoll Ser", "C2 CompilerThread0" is "C2 CompilerThre".
 * Full names match as well. One extra group, {@value #JVM}, holds the JVM's own housekeeping threads
 * (reference handler, finalizer, signal dispatcher...); it goes with GC and JIT.</p>
 */
public final class ThreadClassifier {

    public static final String MAIN = "main";
    public static final String CHUNK_WORKERS = "chunk-workers";
    public static final String CHUNK_IO = "chunk-io";
    public static final String NETTY = "netty";
    public static final String GC = "gc";
    public static final String JIT = "jit";
    public static final String JVM = "jvm";
    public static final String PLUGINS_ASYNC = "plugins-async";
    public static final String CHAT = "chat";
    public static final String PAPER_ASYNC = "paper-async";
    public static final String SPARK = "spark";
    public static final String AMAZAKE = "amazake";
    public static final String OTHER = "other";

    /** Every group, in the order /amazake cores lists them. */
    public static final List<String> GROUPS = List.of(MAIN, CHUNK_WORKERS, CHUNK_IO, NETTY, GC, JIT, JVM,
        PLUGINS_ASYNC, CHAT, PAPER_ASYNC, SPARK, AMAZAKE, OTHER);

    /** Linux TASK_COMM_LEN - 1. */
    static final int COMM_LENGTH = 15;

    private record Rule(String group, Kind kind, String text) {
    }

    private enum Kind { EXACT, PREFIX, BRAND_POOL }

    // First match wins; the order follows profiler.ThreadGroups.
    private static final List<Rule> RULES = List.of(
        new Rule(MAIN, Kind.EXACT, "Server thread"),
        // "<brand> Common Worker #N" / "<brand> I/O Worker #N": the brand is "Paper" (comm "Paper Common Wo").
        new Rule(CHUNK_WORKERS, Kind.BRAND_POOL, " Common Worker #"),
        new Rule(CHUNK_IO, Kind.BRAND_POOL, " I/O Worker #"),
        new Rule(NETTY, Kind.PREFIX, "Netty "),
        new Rule(GC, Kind.PREFIX, "GC Thread"),
        new Rule(GC, Kind.PREFIX, "G1 "),
        new Rule(GC, Kind.PREFIX, "GC "),
        new Rule(GC, Kind.EXACT, "VM Thread"),
        // Generational ZGC (JDK 21 -XX:+ZGenerational) and single-generation ZGC ("X" prefix in JDK 21).
        new Rule(GC, Kind.PREFIX, "ZWorker"),
        new Rule(GC, Kind.PREFIX, "ZDriver"),
        new Rule(GC, Kind.PREFIX, "ZDirector"),
        new Rule(GC, Kind.PREFIX, "ZStat"),
        new Rule(GC, Kind.PREFIX, "ZUnmapper"),
        new Rule(GC, Kind.PREFIX, "ZUncommitter"),
        new Rule(GC, Kind.PREFIX, "XWorker"),
        new Rule(GC, Kind.PREFIX, "XDriver"),
        new Rule(GC, Kind.PREFIX, "XDirector"),
        new Rule(GC, Kind.PREFIX, "XStat"),
        new Rule(GC, Kind.PREFIX, "XUnmapper"),
        new Rule(GC, Kind.PREFIX, "XUncommitter"),
        new Rule(GC, Kind.PREFIX, "RuntimeWorker#"),
        new Rule(GC, Kind.PREFIX, "Shenandoah"),
        new Rule(GC, Kind.PREFIX, "Safepoint Clean"),
        new Rule(JIT, Kind.PREFIX, "C1 Compiler"),
        new Rule(JIT, Kind.PREFIX, "C2 Compiler"),
        new Rule(JVM, Kind.PREFIX, "VM Periodic Tas"),
        new Rule(JVM, Kind.PREFIX, "Reference Handl"),
        new Rule(JVM, Kind.EXACT, "Finalizer"),
        new Rule(JVM, Kind.PREFIX, "Signal Dispatch"),
        new Rule(JVM, Kind.EXACT, "Service Thread"),
        new Rule(JVM, Kind.PREFIX, "Monitor Deflati"),
        new Rule(JVM, Kind.PREFIX, "Sweeper thread"),
        new Rule(JVM, Kind.EXACT, "Common-Cleaner"),
        new Rule(JVM, Kind.PREFIX, "Notification Th"),
        new Rule(JVM, Kind.EXACT, "Attach Listener"),
        new Rule(PLUGINS_ASYNC, Kind.PREFIX, "Craft Scheduler"),
        new Rule(PLUGINS_ASYNC, Kind.PREFIX, "Folia Async Sch"),
        new Rule(CHAT, Kind.PREFIX, "Async Chat Thre"),
        new Rule(CHAT, Kind.PREFIX, "Async Tab Compl"),
        new Rule(PAPER_ASYNC, Kind.PREFIX, "Paper Async "),
        new Rule(PAPER_ASYNC, Kind.PREFIX, "Paper Plugin Re"),
        new Rule(PAPER_ASYNC, Kind.PREFIX, "User Authentica"),
        new Rule(PAPER_ASYNC, Kind.PREFIX, "Configuration T"),
        new Rule(SPARK, Kind.PREFIX, "spark-"),
        new Rule(AMAZAKE, Kind.PREFIX, "Amazake ")
    );

    private ThreadClassifier() {
    }

    /** The group of a thread by its comm (or full) name; {@value #OTHER} when no rule matches. */
    public static String classify(final String comm) {
        if (comm == null) {
            return OTHER;
        }
        for (final Rule rule : RULES) {
            final boolean match = switch (rule.kind()) {
                case EXACT -> comm.equals(rule.text());
                case PREFIX -> comm.startsWith(rule.text());
                case BRAND_POOL -> brandPool(comm, rule.text());
            };
            if (match) {
                return rule.group();
            }
        }
        return OTHER;
    }

    /**
     * Whether {@code name} is "&lt;brand&gt;" + {@code pool} + more, or that cut to 15 characters by the
     * kernel ("Paper Common Wo", "Amazake Common "). At least the pool's first word must survive the cut.
     */
    static boolean brandPool(final String name, final String pool) {
        final int full = name.indexOf(pool);
        if (full > 0) {
            return true;
        }
        if (name.length() != COMM_LENGTH) {
            return false;
        }
        final int firstWord = pool.indexOf(' ', 1);
        for (int start = 1; start < name.length(); start++) {
            final String tail = name.substring(start);
            if (tail.length() >= firstWord && pool.startsWith(tail)) {
                return true;
            }
        }
        return false;
    }

    /** Which CPUs a thread gets: its role's, or wider ones (parallel GC workers). */
    public enum Spread {
        /** The CPUs of the thread's role. */
        ROLE,
        /**
         * Every CPU the server may use: stop-the-world parallel GC workers ("GC Thread#N" of G1 and
         * Parallel GC). They only run while every Java thread, the main thread included, is stopped, so
         * squeezing them onto the gc CPUs would only make each pause longer.
         */
        ALL,
        /**
         * Every CPU except tick's: worker gangs of the concurrent collectors (ZGC, Shenandoah), which
         * mostly run next to the main thread and must not crowd a single gc CPU.
         */
        ALL_BUT_TICK
    }

    /** How widely a thread with this comm is spread; see {@link Spread}. */
    public static Spread spread(final String comm) {
        if (comm == null) {
            return Spread.ROLE;
        }
        if (comm.startsWith("GC Thread#")) {
            return Spread.ALL;
        }
        if (comm.startsWith("ZWorker") || comm.startsWith("XWorker") || comm.startsWith("Shenandoah GC T")) {
            return Spread.ALL_BUT_TICK;
        }
        return Spread.ROLE;
    }

    /** main -> tick; chunk-workers, chunk-io -> chunks; netty -> network; gc, jit, jvm -> gc; the rest -> background. */
    public static Role roleOf(final String group) {
        return switch (group) {
            case MAIN -> Role.TICK;
            case CHUNK_WORKERS, CHUNK_IO -> Role.CHUNKS;
            case NETTY -> Role.NETWORK;
            case GC, JIT, JVM -> Role.GC;
            default -> Role.BACKGROUND;
        };
    }
}
