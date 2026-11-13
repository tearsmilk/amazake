package core.amazake.systems.api;

/** Server-wide numbers for placeholders; any thread. */
public interface ServerStats {

    /** TPS over the last 5 s, at most 20. */
    double tps();

    /** Mean tick duration over the last 5 s, ms. */
    double mspt();

    int onlinePlayers();

    int maxPlayers();
}
