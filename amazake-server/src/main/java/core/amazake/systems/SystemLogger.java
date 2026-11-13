package core.amazake.systems;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A system's logger, named after the system (the server log shows it as {@code [AmazakeTab] message}) and forwarding
 * to the server log. Not registered with the LogManager, so it goes away with the system.
 */
final class SystemLogger extends Logger {

    SystemLogger(final String systemName, final Logger parent) {
        super(systemName, null);
        this.setParent(parent);
        this.setLevel(Level.ALL);
    }
}
