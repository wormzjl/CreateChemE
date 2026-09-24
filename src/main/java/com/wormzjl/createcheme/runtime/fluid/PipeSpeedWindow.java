package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PipeTransfer;
import java.util.*;

/** Ephemeral, server-thread-owned transport history. Never advances process state or survives topology changes. */
final class PipeSpeedWindow {
    private static final long TICKS = 100;
    private static final double SECONDS = 5.0;
    private record Span(long start, long end, Map<Long, Double> volumePerTick) {}
    private final ArrayDeque<Span> spans = new ArrayDeque<>();
    private long cachedAt = Long.MIN_VALUE;
    private Map<Long, Double> cached = Map.of();

    void accepted(long start, long end, List<PipeTransfer> transfers) {
        if (end <= start) throw new IllegalArgumentException("Empty transport interval");
        if (!spans.isEmpty() && start < spans.getLast().end())
            throw new IllegalArgumentException("Overlapping transport intervals");
        var rates = new HashMap<Long, Double>();
        for (var transfer : transfers) {
            double volume = volume(transfer.forward()) + volume(transfer.reverse());
            rates.put(transfer.pipeId(), volume / (end - start));
        }
        spans.addLast(new Span(start, end, Map.copyOf(rates)));
        prune(end);
        cachedAt = Long.MIN_VALUE;
    }

    /** Accepted gross volume within the previous 100 online ticks, divided by five seconds.
     * Partial intervals use their recorded interval-average rate; missing/startup history contributes zero. */
    Map<Long, Double> volumeRates(long onlineTick) {
        if (cachedAt == onlineTick) return cached;
        prune(onlineTick);
        var result = new HashMap<Long, Double>();
        for (var span : spans) {
            long overlap = Math.min(onlineTick, span.end()) - Math.max(onlineTick - TICKS, span.start());
            if (overlap > 0) span.volumePerTick().forEach((pipe, rate) ->
                result.merge(pipe, rate * overlap / SECONDS, Double::sum));
        }
        cached = Map.copyOf(result);
        cachedAt = onlineTick;
        return cached;
    }

    private void prune(long end) {
        while (!spans.isEmpty() && spans.getFirst().end() <= end - TICKS) spans.removeFirst();
    }
    private static double volume(PipeTransfer.Stream stream) {
        return Arrays.stream(stream.phaseVolumes()).sum() + stream.solids().volume();
    }
}
