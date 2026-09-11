package com.wormzjl.createcheme.science.column.v3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned CPU workers; only the caller consumes results and writes the journal. */
final class V3BoundedEvaluation {
    private V3BoundedEvaluation() {}

    @FunctionalInterface
    interface Sink<T> {
        void accept(T value, double queueWaitMillis) throws Exception;
    }

    record Summary(int submitted, int completed, int maximumInFlight,
                   int maximumActive, int distinctWorkerThreads, boolean terminated) {}
    private record Completed<T>(T value, double queueWaitMillis) {}

    /**
     * At most {@code workers} futures are outstanding. Submission publishes each
     * task; get() publishes its result to the caller. Failure/interrupt cancels
     * siblings, with bounded owned-executor shutdown and restored interruption.
     */
    static <T> Summary run(List<? extends Callable<T>> tasks, int workers, Sink<T> sink) throws Exception {
        if (workers < 1 || workers > 12) throw new IllegalArgumentException("workers must be 1..12");
        var factory = Thread.ofPlatform().name("v3-column-evaluation-", 0).daemon(false).factory();
        var executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), factory, new ThreadPoolExecutor.AbortPolicy());
        var completed = new ExecutorCompletionService<Completed<T>>(executor);
        Set<Future<Completed<T>>> pending = new HashSet<>();
        Set<Long> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger active = new AtomicInteger(), maximumActive = new AtomicInteger();
        int submitted = 0, consumed = 0, maximumInFlight = 0;
        Throwable primary = null;
        try {
            executor.prestartAllCoreThreads();
            while (consumed < tasks.size()) {
                while (submitted < tasks.size() && pending.size() < workers) {
                    Callable<T> task = tasks.get(submitted++);
                    long admitted = System.nanoTime();
                    pending.add(completed.submit(() -> {
                        double queueWait = (System.nanoTime() - admitted) / 1e6;
                        threads.add(Thread.currentThread().threadId());
                        maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try { return new Completed<>(task.call(), queueWait); }
                        finally { active.decrementAndGet(); }
                    }));
                    maximumInFlight = Math.max(maximumInFlight, pending.size());
                }
                Future<Completed<T>> future = completed.take();
                if (!pending.remove(future)) throw new IllegalStateException("Foreign completion");
                Completed<T> result = future.get();
                sink.accept(result.value(), result.queueWaitMillis());
                consumed++;
            }
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            // Cancel caller-visible ECS futures before removing their queued wrappers.
            for (Future<?> future : pending) future.cancel(true);
            try {
                close(executor, primary instanceof InterruptedException);
            } catch (RuntimeException shutdownFailure) {
                if (primary == null) throw shutdownFailure;
                primary.addSuppressed(shutdownFailure);
            }
        }
        return new Summary(submitted, consumed, maximumInFlight, maximumActive.get(), threads.size(), executor.isTerminated());
    }

    /** Repeated interruption cannot reset the single fifteen-second shutdown bound. */
    static void close(ThreadPoolExecutor executor, boolean alreadyInterrupted) {
        boolean interrupted = Thread.interrupted() | alreadyInterrupted;
        long start = System.nanoTime();
        long gracefulEnd = start + TimeUnit.SECONDS.toNanos(5);
        long end = start + TimeUnit.SECONDS.toNanos(15);
        boolean forced = interrupted;
        try {
            if (forced) executor.shutdownNow(); else executor.shutdown();
            while (!executor.isTerminated()) {
                long now = System.nanoTime();
                if (now >= end) throw new IllegalStateException("Owned evaluation workers exceeded the shutdown bound");
                if (!forced && now >= gracefulEnd) { executor.shutdownNow(); forced = true; }
                long allowance = (forced ? end : gracefulEnd) - now;
                try { executor.awaitTermination(allowance, TimeUnit.NANOSECONDS); }
                catch (InterruptedException again) {
                    interrupted = true; forced = true;
                    executor.shutdownNow();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
