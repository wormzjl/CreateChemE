package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;

/** Coordinator-owned delivery ledger. Proposals are immutable and consume nothing until their revisions commit. */
public final class PendingTransfers {
    private final Thread owner=Thread.currentThread();
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    public record Pending(UUID id,UUID producer,UUID receiver,long dueTick,long revision,MaterialParcel remaining) {
        public Pending {Objects.requireNonNull(id);Objects.requireNonNull(producer);Objects.requireNonNull(receiver);Objects.requireNonNull(remaining);if(dueTick<0||revision<0||remaining.empty())throw new IllegalArgumentException("Invalid pending transfer");}
    }
    public static final class Proposed {
        private final Pending before;
        private final MaterialParcel delivered,remainder;
        private Proposed(Pending before,MaterialParcel delivered,MaterialParcel remainder){this.before=before;this.delivered=delivered;this.remainder=remainder;}
        public Pending before(){return before;}
        public MaterialParcel delivered(){return delivered;}
        public MaterialParcel remainder(){return remainder;}
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Pending transfers belong to their coordinator thread");}
    public void add(Pending transfer){owned();if(pending.putIfAbsent(transfer.id,transfer)!=null)throw new IllegalStateException("Duplicate transfer identity");}
    public List<Pending> snapshot(){owned();return List.copyOf(pending.values());}
    public List<Pending> due(UUID receiver,long tick){owned();return pending.values().stream().filter(p->p.receiver.equals(receiver)&&p.dueTick<=tick).toList();}
    public Proposed propose(UUID id,long expectedRevision,double feasibleMassKg) {
        owned();var record=require(id,expectedRevision);var portion=record.remaining.takeMass(feasibleMassKg);
        return new Proposed(record,portion.delivered(),portion.remainder());
    }
    /** Validate every staged identity first; failure leaves the entire ledger unchanged. */
    public void commit(List<Proposed> proposals) {
        owned();var seen=new HashSet<UUID>();
        for(var proposal:proposals) {
            if(!seen.add(proposal.before.id)||require(proposal.before.id,proposal.before.revision)!=proposal.before)throw new IllegalStateException("Stale/duplicate delivery proposal");
            if(!proposal.delivered.empty()&&!proposal.remainder.empty())Math.addExact(proposal.before.revision,1);
        }
        for(var proposal:proposals) {
            if(proposal.delivered.empty())continue;
            var before=proposal.before;
            if(proposal.remainder.empty())pending.remove(before.id);
            else pending.put(before.id,new Pending(before.id,before.producer,before.receiver,before.dueTick,Math.addExact(before.revision,1),proposal.remainder));
        }
    }
    public Pending retarget(UUID id,long expectedRevision,UUID receiver) {
        owned();var old=require(id,expectedRevision);var updated=new Pending(old.id,old.producer,receiver,old.dueTick,Math.addExact(old.revision,1),old.remaining);pending.put(id,updated);return updated;
    }
    private Pending require(UUID id,long revision){var value=pending.get(id);if(value==null||value.revision!=revision)throw new IllegalStateException("Stale or missing transfer "+id);return value;}
}
