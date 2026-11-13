package core.amazake.stress;

import org.bukkit.command.CommandSender;

/**
 * The built-in profiler used by {@code /stress profile}. Implemented by
 * {@link core.amazake.stress.profiler.StressProfiler}; the framework only talks to this interface.
 * All methods are called on the main thread.
 */
public interface ProfilerBridge {

    /** False while the profiler is only a placeholder / cannot run on this server. */
    boolean isAvailable();

    boolean isRunning();

    /**
     * Starts profiling for {@code seconds} of wall time (it stops by itself and produces the report) or
     * until {@link #stop(CommandSender)}. Progress and the result (report file / link) go to {@code sender}.
     */
    void start(CommandSender sender, int seconds);

    /** Stops early and produces the report for what was collected. */
    void stop(CommandSender sender);

    /** One short Russian status line for /stress status. */
    String status();

    /** Plugin disable: stop without blocking, release threads. */
    void shutdown();
}
