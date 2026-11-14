package core.amazake.core.modules.chat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player chat cooldown, thread-safe. The clock (a millisecond {@link LongSupplier}) is injected so the logic
 * can be unit-tested with a fake clock; in production it is {@link System#currentTimeMillis}.
 *
 * <p>Because chat events run on an async thread and several may arrive at once, the check-and-record is done
 * atomically through {@link ConcurrentHashMap#compute}.</p>
 */
public final class Cooldown {

    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public Cooldown(final LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /**
     * Tries to send now. If the player is still cooling down, returns the milliseconds left and does NOT reset the
     * timer; otherwise records "now" as the last message time and returns {@code 0} (allowed). A non-positive
     * {@code cooldownMillis} disables the cooldown and always returns {@code 0}.
     */
    public long tryConsume(final UUID player, final long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return 0L;
        }
        final long now = this.clockMillis.getAsLong();
        final long[] remaining = {0L};
        this.lastMessage.compute(player, (key, previous) -> {
            if (previous != null) {
                final long elapsed = now - previous;
                if (elapsed >= 0L && elapsed < cooldownMillis) {
                    remaining[0] = cooldownMillis - elapsed;
                    return previous;
                }
            }
            return now;
        });
        return remaining[0];
    }

    /** Forgets a player's last-message time (e.g. on quit). */
    public void clear(final UUID player) {
        this.lastMessage.remove(player);
    }

    /** Forgets all cached times (on disable). */
    public void clearAll() {
        this.lastMessage.clear();
    }
}
