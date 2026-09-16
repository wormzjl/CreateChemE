package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;
import java.util.function.LongPredicate;

/** Coordinator-owned round robin over registered owners, without queuing duplicate heavy snapshots. */
public final class FairIslandQueue {
    private final Thread owner=Thread.currentThread();
    private final ArrayDeque<Long> order=new ArrayDeque<>();
    private final ArrayDeque<Long> newcomers=new ArrayDeque<>();
    private final Set<Long> registered=new HashSet<>();
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Ready set belongs to its coordinator thread");}
    public void register(long island){owned();if(island<=0)throw new IllegalArgumentException("Invalid island identity");if(registered.add(island))newcomers.addLast(island);}
    public void remove(long island){owned();if(registered.remove(island)){order.remove(island);newcomers.remove(island);}}
    public int size(){owned();return registered.size();}
    public OptionalLong nextReady(LongPredicate ready) {
        owned();Objects.requireNonNull(ready);
        // A newly ready owner gets its first opportunity before already-served owners catch up again.
        for(var iterator=newcomers.iterator();iterator.hasNext();) {
            long island=iterator.next();if(ready.test(island)){iterator.remove();order.addLast(island);return OptionalLong.of(island);}
        }
        int candidates=order.size();
        for(int i=0;i<candidates;i++){long island=order.removeFirst();order.addLast(island);if(ready.test(island))return OptionalLong.of(island);}
        return OptionalLong.empty();
    }
}
