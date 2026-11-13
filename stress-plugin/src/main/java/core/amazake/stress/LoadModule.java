package core.amazake.stress;

/**
 * One kind of load (mobs, physics, redstone, ...).
 *
 * <p>A fresh instance is created for every run by {@link ModuleRegistry}. All methods are called on the
 * server main thread by {@link StageRunner}. Modules must keep {@link #tick(long)} cheap: heavy work is
 * scheduled through {@link StressContext} (its tasks are cancelled automatically on stop).
 */
public interface LoadModule {

    /** Stable module id, equal to the id it is registered under (e.g. {@code "mobs"}). */
    String id();

    /**
     * Called once when the module's stage begins (or immediately in single-module mode). Build structures,
     * spawn entities and schedule tasks here. The context stays valid until {@link #stop()} returns.
     */
    void start(StressContext ctx);

    /**
     * Called every server tick while the run is active, after {@link #start(StressContext)}.
     *
     * @param tick ticks since the run started (0-based, identical on every server for the same timeline)
     */
    void tick(long tick);

    /**
     * Stop all load. The framework afterwards cancels tasks scheduled through the context, removes tracked
     * entities, releases chunk tickets and sweeps leftover entities in stress_world, but a module should
     * also neutralise self-sustaining builds here (e.g. break a redstone clock) and stop its own threads.
     */
    void stop();

    /** One short line for /stress status (Russian, user-facing), e.g. {@code "мобов 800/800"}. */
    String status();

    /**
     * Called when the timeline enters a new stage, for every module that is already started (including the
     * one that has just started on this stage). Default: nothing. Use it to react to {@link Stage#MAX}.
     */
    default void onStageChange(Stage stage) {
    }
}
