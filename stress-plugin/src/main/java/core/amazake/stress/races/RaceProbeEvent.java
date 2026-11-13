package core.amazake.stress.races;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;

/**
 * A synchronous custom event the "races" module fires itself, like plugins that talk to each other through their
 * own events. Its NORMAL listener sometimes fires a nested probe ({@code depth} 1) to exercise re-entrant dispatch.
 */
@NullMarked
public final class RaceProbeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int depth;
    private final int seq;

    public RaceProbeEvent(int depth, int seq) {
        super(false);
        this.depth = depth;
        this.seq = seq;
    }

    public int depth() {
        return this.depth;
    }

    public int seq() {
        return this.seq;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
