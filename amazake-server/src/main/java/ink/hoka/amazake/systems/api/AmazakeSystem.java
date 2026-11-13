package ink.hoka.amazake.systems.api;

/**
 * A system of the Amazake core: a module in a {@code .azs} file in the server's {@code systems/} folder, enabled and
 * disabled at runtime (without a restart) with {@code /amazake system enable|disable|reload <name>}.
 *
 * <p>The main class named in the file's {@code system.yml} implements this interface and has a public no-argument
 * constructor. Every enable creates a new instance in a new class loader; after {@link #disable} the instance and its
 * class loader are dropped, so a system must not leave anything behind that the core does not clean up itself (see
 * {@link SystemContext}).</p>
 *
 * <p><b>Threads.</b> Systems are written for regionized ticking from day one: they are never run under the plugin
 * lock, their listeners may be called on several tick threads at once, and per-player or per-entity work must be
 * scheduled with {@link SystemContext#runForPlayer} / the entity scheduler, which runs it on the thread that owns that
 * entity. Shared state must be thread-safe (concurrent collections, immutable snapshots). See docs/systems.md.</p>
 */
public interface AmazakeSystem {

    /**
     * Starts the system. Called on the global tick thread. Register listeners, commands and tasks through
     * {@code context}: everything registered there is removed by the core when the system is disabled.
     * Throwing leaves the system disabled (the core cleans up what was registered and logs the error).
     */
    void enable(SystemContext context) throws Exception;

    /**
     * Stops the system: undo what the core cannot undo on its own (scoreboards and tab lists shown to players,
     * files to save). Called on the global tick thread, also at server shutdown. The core then unregisters the
     * listeners, cancels the tasks and unregisters the commands registered through the context.
     */
    void disable(SystemContext context) throws Exception;

    /**
     * Reloads the configuration without a full disable/enable. The default does a disable followed by an enable.
     */
    default void reload(final SystemContext context) throws Exception {
        this.disable(context);
        this.enable(context);
    }
}
