package com.sporty.jackpot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tasks on a fixed number of platform threads that all start at the same moment: every worker thread first
 * waits on a shared {@link CountDownLatch}, which is released only once all workers are parked on it. Worker
 * {@code w} then runs tasks {@code w, w + threads, w + 2 × threads, ...} one after the other.
 */
final class ConcurrentRunner {

    private static final long START_TIMEOUT_SECONDS = 30;
    private static final long COMPLETION_TIMEOUT_SECONDS = 120;

    private ConcurrentRunner() {
    }

    /**
     * Outcome of one task: its result, or the exception it threw.
     *
     * @param index   position of the task in the submitted list
     * @param result  the task's result ({@code null} when it failed)
     * @param failure the exception thrown by the task ({@code null} when it succeeded)
     */
    record Attempt<T>(int index, T result, Exception failure) {

        boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * Runs {@code tasks} on {@code threads} threads released together.
     *
     * @return one attempt per task, in task order
     */
    static <T> List<Attempt<T>> run(int threads, List<? extends Callable<T>> tasks) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<Attempt<T>>>> workers = new ArrayList<>();
            for (int worker = 0; worker < threads; worker++) {
                int first = worker;
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    List<Attempt<T>> attempts = new ArrayList<>();
                    for (int index = first; index < tasks.size(); index += threads) {
                        attempts.add(attempt(index, tasks.get(index)));
                    }
                    return attempts;
                }));
            }
            assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("all %d worker threads are waiting at the start line", threads).isTrue();
            start.countDown();
            List<Attempt<T>> attempts = new ArrayList<>();
            for (Future<List<Attempt<T>>> worker : workers) {
                attempts.addAll(worker.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            attempts.sort(Comparator.comparingInt(Attempt::index));
            return attempts;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running concurrent tasks", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Concurrent tasks did not complete", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Attempt<T> attempt(int index, Callable<T> task) {
        try {
            return new Attempt<>(index, task.call(), null);
        } catch (Exception e) {
            return new Attempt<>(index, null, e);
        }
    }
}
