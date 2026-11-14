package core.amazake.core.modules.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CooldownTest {

    /** A fake clock the test advances by hand. */
    private static final class FakeClock {
        final AtomicLong now = new AtomicLong();

        long millis() {
            return this.now.get();
        }

        void advance(final long ms) {
            this.now.addAndGet(ms);
        }
    }

    @Test
    void zeroCooldownAlwaysAllows() {
        final FakeClock clock = new FakeClock();
        final Cooldown cooldown = new Cooldown(clock::millis);
        final UUID player = UUID.randomUUID();
        assertEquals(0L, cooldown.tryConsume(player, 0L));
        assertEquals(0L, cooldown.tryConsume(player, 0L));
    }

    @Test
    void firstMessageAllowedThenThrottled() {
        final FakeClock clock = new FakeClock();
        final Cooldown cooldown = new Cooldown(clock::millis);
        final UUID player = UUID.randomUUID();

        assertEquals(0L, cooldown.tryConsume(player, 3_000L), "first message is allowed");

        clock.advance(1_000L);
        assertEquals(2_000L, cooldown.tryConsume(player, 3_000L), "1s later: 2s remain");

        clock.advance(500L);
        assertEquals(1_500L, cooldown.tryConsume(player, 3_000L),
            "a throttled attempt must NOT reset the timer: remaining keeps shrinking (1.5s at t=1.5s), "
                + "not jump back to 2s");
    }

    @Test
    void allowedAgainAfterCooldownElapses() {
        final FakeClock clock = new FakeClock();
        final Cooldown cooldown = new Cooldown(clock::millis);
        final UUID player = UUID.randomUUID();

        assertEquals(0L, cooldown.tryConsume(player, 3_000L));
        clock.advance(3_000L);
        assertEquals(0L, cooldown.tryConsume(player, 3_000L), "exactly at the boundary is allowed");
        clock.advance(10L);
        assertEquals(2_990L, cooldown.tryConsume(player, 3_000L));
    }

    @Test
    void cooldownIsPerPlayer() {
        final FakeClock clock = new FakeClock();
        final Cooldown cooldown = new Cooldown(clock::millis);
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();

        assertEquals(0L, cooldown.tryConsume(a, 2_000L));
        assertEquals(0L, cooldown.tryConsume(b, 2_000L), "a different player has their own timer");
        clock.advance(500L);
        assertEquals(1_500L, cooldown.tryConsume(a, 2_000L));
        assertEquals(1_500L, cooldown.tryConsume(b, 2_000L));
    }

    @Test
    void clearForgetsPlayer() {
        final FakeClock clock = new FakeClock();
        final Cooldown cooldown = new Cooldown(clock::millis);
        final UUID player = UUID.randomUUID();

        assertEquals(0L, cooldown.tryConsume(player, 5_000L));
        cooldown.clear(player);
        assertEquals(0L, cooldown.tryConsume(player, 5_000L), "after clear the next message is allowed");
    }
}
