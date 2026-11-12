package ink.hoka.amazake.stress.profiler;

import java.lang.reflect.Method;

/**
 * The number of CPUs the server may use. On Amazake with its core layout, Runtime.availableProcessors()
 * answers from the calling thread's CPU set (the main thread is pinned to one CPU), so the server's own
 * count is asked for first: ink.hoka.amazake.cpu.CpuTopology.availableProcessors(), found by reflection
 * because it is not part of the plugin API. Elsewhere it is Runtime.availableProcessors().
 */
public final class Cpus {

    private static final Method AMAZAKE = find();

    private Cpus() {
    }

    private static Method find() {
        try {
            return Class.forName("ink.hoka.amazake.cpu.CpuTopology").getMethod("availableProcessors");
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    public static int logical() {
        if (AMAZAKE != null) {
            try {
                final Object n = AMAZAKE.invoke(null);
                if (n instanceof Integer count && count > 0) {
                    return count;
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                // fall through
            }
        }
        return Runtime.getRuntime().availableProcessors();
    }
}
