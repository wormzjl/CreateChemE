package com.wormzjl.createcheme.runtime.fluid;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Server-thread deadline heap of the fluid runtime, in ticks of the shared online epoch. Every recurring piece
 * of scheduling work is an entry here, so the per-tick hook only asks {@link #nextDue()} whether anything is
 * due, which is O(1), and pops exactly the due entries.
 *
 * <p>Invalidation is lazy: an entry carries the generation its owner had when it was scheduled, and the owner
 * drops an entry whose generation no longer matches when it is popped (a revision bump, repartition, removal
 * or stop gives the owner a new generation or removes it). A replaced deadline therefore costs one pop, never
 * a search. Entries at the same tick come out in scheduling order.
 */
public final class IslandScheduler {
    public enum Kind {
        /** An island's next slice becomes due: committed tick plus cadence, the retry span, or a fence. */
        SLICE_DUE,
        /** A held island's retry time. */
        RETRY,
        /** A dispatch round's hard wall budget may have expired. */
        ROUND_TIMEOUT,
        /** The shared worker allocator's lower-demand shrink becomes due. */
        ALLOCATOR_SHRINK,
        /** A module cycle end or pending input due tick, or a dependency change the module host must see. */
        MODULE_HORIZON,
        /** An undelivered solid recovery (offline player, full inventory, unloaded chunk) is retried. */
        RECOVERY_RETRY,
        /** Reserved for certified islands (rest and steady-flow certificates). */
        CERTIFICATE_HORIZON,
        /** Reserved for coalesced presentation buckets. */
        PRESENTATION_BUCKET
    }
    public record Deadline(long tick,long sequence,Kind kind,long id,long generation) {
        public Deadline {Objects.requireNonNull(kind);}
    }
    private final Thread owner=Thread.currentThread();
    private final PriorityQueue<Deadline> heap=new PriorityQueue<>(Comparator.comparingLong(Deadline::tick).thenComparingLong(Deadline::sequence));
    private long sequence;
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island scheduler belongs to its server thread");}
    /** Adds a deadline due once the epoch reaches {@code tick}. */
    public void schedule(long tick,Kind kind,long id,long generation) {
        owned();if(tick==Long.MAX_VALUE)throw new IllegalArgumentException("A deadline needs a finite tick");
        heap.add(new Deadline(tick,sequence=Math.addExact(sequence,1),kind,id,generation));
    }
    /** O(1). The earliest scheduled tick, possibly of a stale entry; {@link Long#MAX_VALUE} when empty. */
    public long nextDue(){owned();var head=heap.peek();return head==null?Long.MAX_VALUE:head.tick();}
    /** Removes and returns the earliest entry if it is due at {@code now}, otherwise null. */
    public Deadline poll(long now){owned();var head=heap.peek();return head==null||head.tick()>now?null:heap.poll();}
    public int size(){owned();return heap.size();}
    public void clear(){owned();heap.clear();}
    /** Drops the entries the owner no longer recognises. Only needed to bound memory, never for correctness. */
    public void compact(Predicate<Deadline> live){owned();heap.removeIf(entry->!live.test(entry));}
}
