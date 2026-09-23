package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;
import java.util.function.LongPredicate;

/**
 * Coordinator-owned round robin over registered owners, without queuing duplicate heavy snapshots.
 *
 * <p>The service order is the one the queue always had: owners never served yet are offered first, in
 * registration order; a served owner joins the back of one fixed cycle, and each request serves the first
 * ready owner at or after the service position, then moves the position past it. Skipping an owner that is
 * not ready moves the position exactly as rotating it to the back of a deque did, so the order among ready
 * owners is independent of how many other owners exist.
 *
 * <p>What changed is the cost. The owner marks which owners are ready ({@link #setReady}); only those are
 * indexed, so a request never visits an owner that is waiting. Positions are ordinal keys on the cycle, and
 * the service position is the key of the owner most recently served. {@link #register(long)} keeps the old
 * contract that a new owner is offered to the predicate; {@link #register(long,boolean)} lets the owner
 * state readiness itself.
 */
public final class FairIslandQueue {
    private static final long GAP=1L<<20;
    private final Thread owner=Thread.currentThread();
    private final Set<Long> registered=new HashSet<>(),ready=new HashSet<>();
    private final Map<Long,Long> arrival=new HashMap<>();
    private final TreeMap<Long,Long> newcomers=new TreeMap<>(),readyNewcomers=new TreeMap<>();
    private final Map<Long,Long> keyOf=new HashMap<>();
    private final TreeMap<Long,Long> cycle=new TreeMap<>(),readyCycle=new TreeMap<>();
    private long arrivals,last;
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Ready set belongs to its coordinator thread");}
    public void register(long island){register(island,true);}
    public void register(long island,boolean isReady) {
        owned();if(island<=0)throw new IllegalArgumentException("Invalid island identity");
        if(!registered.add(island))return;
        long order=++arrivals;arrival.put(island,order);newcomers.put(order,island);
        if(isReady){ready.add(island);readyNewcomers.put(order,island);}
    }
    public void remove(long island) {
        owned();if(!registered.remove(island))return;ready.remove(island);
        var order=arrival.remove(island);if(order!=null){newcomers.remove(order);readyNewcomers.remove(order);}
        var key=keyOf.remove(island);if(key!=null){cycle.remove(key);readyCycle.remove(key);}
    }
    public int size(){owned();return registered.size();}
    public int readyCount(){owned();return ready.size();}
    public boolean isReady(long island){owned();return ready.contains(island);}
    /** Marks whether a registered owner may be served; unregistered owners are ignored. */
    public void setReady(long island,boolean isReady) {
        owned();if(!registered.contains(island)||ready.contains(island)==isReady)return;
        if(isReady)ready.add(island);else ready.remove(island);
        var order=arrival.get(island);
        if(order!=null){if(isReady)readyNewcomers.put(order,island);else readyNewcomers.remove(order);return;}
        long key=keyOf.get(island);if(isReady)readyCycle.put(key,island);else readyCycle.remove(key);
    }
    /** Serves the next ready owner that also passes {@code accept}; an owner failing it is passed over, not unmarked. */
    public OptionalLong nextReady(LongPredicate accept) {
        owned();Objects.requireNonNull(accept);
        // A newly ready owner gets its first opportunity before already-served owners catch up again.
        for(var entry:readyNewcomers.entrySet()) {
            long island=entry.getValue();if(!accept.test(island))continue;
            newcomers.remove(entry.getKey());readyNewcomers.remove(entry.getKey());arrival.remove(island);
            long key=positionAtBack();cycle.put(key,island);keyOf.put(island,key);readyCycle.put(key,island);last=key;
            return OptionalLong.of(island);
        }
        for(var entry:readyCycle.tailMap(last,false).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
        for(var entry:readyCycle.headMap(last,true).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
        return OptionalLong.empty();
    }
    /** A key after the service position and before the next served owner: the back of the cycle. */
    private long positionAtBack() {
        var after=cycle.higherKey(last);
        if(after==null)return Math.addExact(last,GAP);
        if(after-last<2){renumber();after=cycle.higherKey(last);if(after==null)return Math.addExact(last,GAP);}
        return last+(after-last)/2;
    }
    /** Restores key gaps without changing the service order: the cycle is re-keyed from the service position. */
    private void renumber() {
        var order=new ArrayList<Long>(cycle.tailMap(last,false).values());order.addAll(cycle.headMap(last,true).values());
        cycle.clear();readyCycle.clear();keyOf.clear();long key=0;
        for(long island:order){key+=GAP;cycle.put(key,island);keyOf.put(island,key);if(ready.contains(island))readyCycle.put(key,island);}
        last=key;
    }
}
