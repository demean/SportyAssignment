package com.sporty.jackpot.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Deterministic {@link RandomGenerator} for draw tests: returns queued values in order and records the bounds it was
 * asked for. Fails loudly when the queue is empty or a queued value is outside the requested bound.
 *
 * <pre>{@code
 * StubRandomGenerator random = new StubRandomGenerator(999_999L, 0L);
 * RewardDraw draw = new RewardDraw(random);
 * }</pre>
 */
public final class StubRandomGenerator implements RandomGenerator {

    private final Deque<Long> values = new ArrayDeque<>();
    private final List<Long> requestedBounds = new ArrayList<>();

    public StubRandomGenerator(long... values) {
        enqueue(values);
    }

    /**
     * Appends values to the queue.
     *
     * @param values values returned by the next calls, in order
     * @return this
     */
    public synchronized StubRandomGenerator enqueue(long... values) {
        for (long value : values) {
            this.values.addLast(value);
        }
        return this;
    }

    @Override
    public synchronized long nextLong() {
        if (values.isEmpty()) {
            throw new IllegalStateException("StubRandomGenerator: no queued value left");
        }
        return values.removeFirst();
    }

    @Override
    public synchronized long nextLong(long bound) {
        requestedBounds.add(bound);
        long value = nextLong();
        if (value < 0 || value >= bound) {
            throw new IllegalStateException("StubRandomGenerator: queued value " + value + " is outside [0, " + bound
                    + ")");
        }
        return value;
    }

    /**
     * @return the bounds passed to {@link #nextLong(long)}, in call order
     */
    public synchronized List<Long> requestedBounds() {
        return List.copyOf(requestedBounds);
    }

    /**
     * @return how many queued values are left
     */
    public synchronized int remaining() {
        return values.size();
    }
}
