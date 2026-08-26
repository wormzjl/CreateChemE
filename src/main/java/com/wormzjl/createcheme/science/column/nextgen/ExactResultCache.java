package com.wormzjl.createcheme.science.column.nextgen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/** Server-thread-confined deterministic access-order LRU; callers install only matching committed successes. */
public final class ExactResultCache<K, V> {
    public static final int MAX_ENTRIES = 128;
    public static final long MAX_BYTES = 32L * 1024L * 1024L;
    private final ToLongFunction<? super V> weigh;
    private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75F, true);
    private long bytes;

    public ExactResultCache(ToLongFunction<? super V> weigh) {
        this.weigh = Objects.requireNonNull(weigh, "weigh");
    }

    public V get(K key) { return values.get(key); }
    public int size() { return values.size(); }
    public long bytes() { return bytes; }

    public void putCommittedSuccess(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long weight = weigh.applyAsLong(value);
        if (weight < 0L || weight > MAX_BYTES) throw new IllegalArgumentException("Invalid compact result weight");
        V previous = values.put(key, value);
        if (previous != null) bytes -= weigh.applyAsLong(previous);
        bytes += weight;
        while (values.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            Map.Entry<K, V> eldest = values.entrySet().iterator().next();
            values.remove(eldest.getKey());
            bytes -= weigh.applyAsLong(eldest.getValue());
        }
    }
}
