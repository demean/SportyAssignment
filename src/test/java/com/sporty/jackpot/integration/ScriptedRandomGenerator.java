package com.sporty.jackpot.integration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Thread-safe, deterministic random source for the service-level integration tests (replaces the application's
 * {@code SecureRandom} via {@link ScriptedRandomTestConfiguration}).
 *
 * <p>{@code RewardDraw} wins when {@code nextLong(1_000_000) < chance × 10_000}. This generator answers
 * {@code 0} (wins every chance above 0 %) for every {@code n}-th draw after {@link #winEvery(long)} and
 * {@code bound - 1} (wins only at 100 %) for all other draws. By default no draw is scripted to win, so a 100 %
 * jackpot always wins and every other jackpot always loses.
 */
public final class ScriptedRandomGenerator implements RandomGenerator {

    private final AtomicLong draws = new AtomicLong();
    private volatile long winEvery = Long.MAX_VALUE;

    /**
     * Makes every {@code n}-th draw from now on a winning one (the draw counter restarts at 0).
     *
     * @param n draw interval, {@code >= 1}
     */
    public void winEvery(long n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        draws.set(0);
        winEvery = n;
    }

    /** Back to the default: only 100 % chances win. */
    public void reset() {
        draws.set(0);
        winEvery = Long.MAX_VALUE;
    }

    /** @return the number of draws since the last {@link #winEvery(long)} / {@link #reset()} */
    public long draws() {
        return draws.get();
    }

    @Override
    public long nextLong(long bound) {
        long draw = draws.incrementAndGet();
        return draw % winEvery == 0 ? 0 : bound - 1;
    }

    @Override
    public long nextLong() {
        throw new UnsupportedOperationException("RewardDraw only draws with a bound");
    }
}
