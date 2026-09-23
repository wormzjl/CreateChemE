package com.wormzjl.createcheme.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Sizing, demand policy and worker CPU observations for the one shared solve pool. */
public final class WorkerAllocation {
    public static final int MAXIMUM_WORKERS = 32;
    public static final int DEFAULT_AUTOMATIC_LIMIT = 12;
    private WorkerAllocation() {}

    /** Lazily initializes management access only when a worker requests a CPU sample. */
    static final class CpuTime {
        private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
        private CpuTime() {}

        static long sample() {
            return BEAN.isCurrentThreadCpuTimeSupported() && BEAN.isThreadCpuTimeEnabled()
                    ? BEAN.getCurrentThreadCpuTime() : -1;
        }

        static long elapsed(long start, long end) {
            return start < 0 || end < start ? -1 : end - start;
        }
    }

    /** Zero configured workers enables automatic sizing with the default ceiling. */
    public static int resolve(int configuredWorkers, int availableProcessors) {
        return resolve(configuredWorkers, availableProcessors, DEFAULT_AUTOMATIC_LIMIT);
    }

    /** Fixed overrides and automatic limits use the same bounded pool; automatic mode leaves two processors free. */
    public static int resolve(int configuredWorkers, int availableProcessors, int automaticLimit) {
        if (configuredWorkers < 0 || configuredWorkers > MAXIMUM_WORKERS || availableProcessors < 1
                || automaticLimit < 1 || automaticLimit > MAXIMUM_WORKERS) {
            throw new IllegalArgumentException("Invalid worker sizing inputs");
        }
        return configuredWorkers == 0 ? Math.max(1, Math.min(automaticLimit, availableProcessors - 2)) : configuredWorkers;
    }

    /** Confined to the service owner thread. Grow immediately; shrink after ten online seconds of lower demand. */
    public static final class Demand {
        private final int maximum;
        private int limit = 1;
        private long lowSince = -1, previousTick = -1;

        public Demand(int maximum) {
            if (maximum < 1) throw new IllegalArgumentException("Positive worker budget required");
            this.maximum = maximum;
        }

        /** Returns admitted parallelism, retaining the original 200-tick shrink hysteresis. */
        public int observe(int demand, long onlineTick) {
            if (demand < 0 || onlineTick < 0 || onlineTick < previousTick) {
                throw new IllegalArgumentException("Invalid demand clock");
            }
            previousTick = onlineTick;
            int target = Math.max(1, Math.min(maximum, demand));
            if (target >= limit) {
                limit = target;
                lowSince = -1;
            } else if (lowSince < 0) {
                lowSince = onlineTick;
            } else if (onlineTick - lowSince >= 200) {
                limit = target;
                lowSince = -1;
            }
            return limit;
        }

        /** The tick from which an observation applies the pending lower-demand shrink, or -1 when none is
         * pending. Lets an event-driven owner schedule that one observation instead of observing every tick. */
        public long shrinkTick() {
            return lowSince < 0 ? -1 : lowSince + 200;
        }
    }
}
