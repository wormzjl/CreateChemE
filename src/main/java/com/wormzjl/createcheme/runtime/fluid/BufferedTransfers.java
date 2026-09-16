package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;

/**
 * Coordinator-owned immutable transaction snapshots for buffer capacity and pending material.
 * Occupancy is a capacity counter derived from the hydraulic inventory, not another material owner.
 * A Prepared value must be committed together with its validated hydraulic/module proposal.
 */
public final class BufferedTransfers {
    public static final int MAXIMUM_PENDING_RECORDS=4096;
    public static final int MAXIMUM_BUFFERS=8192;
    public record Buffer(UUID id,double capacityKg,double occupiedKg,Map<UUID,Double> reservations) {
        public Buffer {
            Objects.requireNonNull(id);reservations=Map.copyOf(reservations);
            if(!Double.isFinite(capacityKg)||capacityKg<=0||!Double.isFinite(occupiedKg)||occupiedKg<0)throw new IllegalArgumentException("Invalid buffer capacity");
            for(double mass:reservations.values())if(!Double.isFinite(mass)||mass<=0)throw new IllegalArgumentException("Invalid reservation");
            double reserved=reservations.values().stream().mapToDouble(Double::doubleValue).sum();
            if(!Double.isFinite(reserved)||occupiedKg+reserved>capacityKg+1e-10*Math.max(1,capacityKg))throw new IllegalArgumentException("Buffer working capacity exceeded");
        }
        public double reservedKg(){return reservations.values().stream().mapToDouble(Double::doubleValue).sum();}
        public double freeKg(){return Math.max(0,capacityKg-occupiedKg-reservedKg());}
    }
    /** Capacity promised before a feed withdrawal. This reservation owns no material. */
    public record CapacityReservation(UUID id,UUID producer,UUID receiver,long dueTick,double maximumKg) {
        public CapacityReservation {
            Objects.requireNonNull(id);Objects.requireNonNull(producer);Objects.requireNonNull(receiver);
            if(dueTick<0||!Double.isFinite(maximumKg)||maximumKg<=0)throw new IllegalArgumentException("Invalid production reservation");
        }
    }
    public record Snapshot(long revision,Map<UUID,Buffer> buffers,Map<UUID,PendingTransfers.Pending> pending,Map<UUID,CapacityReservation> planned) {
        public Snapshot(long revision,Map<UUID,Buffer> buffers,Map<UUID,PendingTransfers.Pending> pending){this(revision,buffers,pending,Map.of());}
        public Snapshot {
            if(revision<0)throw new IllegalArgumentException("Invalid ledger revision");
            if(buffers.size()>MAXIMUM_BUFFERS||pending.size()+planned.size()>MAXIMUM_PENDING_RECORDS)throw new IllegalArgumentException("Buffer ledger record capacity exhausted");
            buffers=Map.copyOf(buffers);pending=Map.copyOf(pending);planned=Map.copyOf(planned);
            for(var entry:buffers.entrySet())if(!entry.getKey().equals(entry.getValue().id()))throw new IllegalArgumentException("Buffer key mismatch");
            for(var entry:pending.entrySet()) {
                var record=entry.getValue();var buffer=buffers.get(record.receiver());
                if(!entry.getKey().equals(record.id())||buffer==null)throw new IllegalArgumentException("Pending receiver missing");
                Double reserved=buffer.reservations.get(record.id());
                if(reserved==null||Math.abs(reserved-record.remaining().massKg())>1e-10*Math.max(1,reserved))throw new IllegalArgumentException("Pending material lacks matching capacity reservation");
            }
            for(var buffer:buffers.values())for(var id:buffer.reservations.keySet()) {
                var record=pending.get(id);if(record==null||!record.receiver().equals(buffer.id))throw new IllegalArgumentException("Orphan capacity reservation");
            }
            var promised=new HashMap<UUID,Double>();
            for(var entry:planned.entrySet()) {
                var reservation=entry.getValue();
                if(!entry.getKey().equals(reservation.id())||pending.containsKey(entry.getKey())||!buffers.containsKey(reservation.receiver()))throw new IllegalArgumentException("Invalid planned capacity identity");
                promised.merge(reservation.receiver(),reservation.maximumKg(),Double::sum);
            }
            for(var entry:promised.entrySet()) {
                var buffer=buffers.get(entry.getKey());
                if(!Double.isFinite(entry.getValue())||entry.getValue()>buffer.freeKg()+1e-10*Math.max(1,buffer.capacityKg()))throw new IllegalArgumentException("Planned production exceeds buffer working capacity");
            }
        }
        public double freeKg(UUID receiver){var buffer=Objects.requireNonNull(buffers.get(receiver),"Unknown buffer");return Math.max(0,buffer.freeKg()-planned.values().stream().filter(r->r.receiver().equals(receiver)).mapToDouble(CapacityReservation::maximumKg).sum());}
    }
    public record Feasible(UUID id,long expectedRevision,double massKg) {
        public Feasible {Objects.requireNonNull(id);if(expectedRevision<0||!Double.isFinite(massKg)||massKg<0)throw new IllegalArgumentException("Invalid feasible delivery");}
    }
    public record Delivered(UUID transferId,UUID receiver,MaterialParcel material) {}
    public static final class Prepared {
        private final Snapshot before,after;
        private final List<Delivered> delivered;
        private Prepared(Snapshot before,Snapshot after,List<Delivered> delivered){this.before=before;this.after=after;this.delivered=List.copyOf(delivered);}
        public Snapshot after(){return after;}
        public List<Delivered> delivered(){return delivered;}
    }
    private final Thread owner=Thread.currentThread();
    private Snapshot state;
    public BufferedTransfers(Snapshot restored){state=Objects.requireNonNull(restored);}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Buffer ledger belongs to its coordinator thread");}
    public Snapshot snapshot(){owned();return state;}
    public Prepared reserveCapacity(List<CapacityReservation> reservations) {
        owned();var planned=new HashMap<>(state.planned);
        for(var reservation:reservations)if(planned.putIfAbsent(reservation.id(),reservation)!=null||state.pending.containsKey(reservation.id()))throw new IllegalStateException("Duplicate production reservation");
        return prepare(state.buffers,state.pending,planned,List.of());
    }
    /** Resolve promised capacity into actual products, releasing unused capacity, in the same
     * transaction that removes those products from module-owned holdup. Empty production is valid. */
    public Prepared produce(Set<UUID> resolved,List<PendingTransfers.Pending> products) {
        owned();var planned=new HashMap<>(state.planned);var buffers=new HashMap<>(state.buffers);var pending=new HashMap<>(state.pending);
        for(var id:resolved)if(!planned.containsKey(id))throw new IllegalStateException("Unknown production reservation");
        for(var product:products) {
            var promise=planned.get(product.id());
            if(promise==null||!resolved.contains(product.id())||!promise.producer().equals(product.producer())||!promise.receiver().equals(product.receiver())||promise.dueTick()!=product.dueTick()||product.revision()!=0||product.remaining().massKg()>promise.maximumKg()+1e-10*Math.max(1,promise.maximumKg()))throw new IllegalStateException("Product does not match its reserved production");
            if(pending.putIfAbsent(product.id(),product)!=null)throw new IllegalStateException("Duplicate product identity");
            var buffer=buffers.get(product.receiver());var held=new HashMap<>(buffer.reservations);held.put(product.id(),product.remaining().massKg());
            buffers.put(buffer.id,new Buffer(buffer.id,buffer.capacityKg,buffer.occupiedKg,held));
        }
        resolved.forEach(planned::remove);return prepare(buffers,pending,planned,List.of());
    }
    /** Reserves complete product ownership before source material may be withdrawn. */
    public Prepared reserve(List<PendingTransfers.Pending> products) {
        owned();var buffers=new HashMap<>(state.buffers);var pending=new HashMap<>(state.pending);
        for(var product:products) {
            if(pending.putIfAbsent(product.id(),product)!=null)throw new IllegalStateException("Duplicate pending identity");
            var buffer=Objects.requireNonNull(buffers.get(product.receiver()),"Unknown product buffer");
            var reservations=new HashMap<>(buffer.reservations);reservations.put(product.id(),product.remaining().massKg());
            buffers.put(buffer.id,new Buffer(buffer.id,buffer.capacityKg,buffer.occupiedKg,reservations));
        }
        return prepare(buffers,pending,List.of());
    }
    /** Zero acceptance defers that record without consuming a revision or blocking other feasible inputs. */
    public Prepared deliver(long receiverTick,List<Feasible> feasible) {
        owned();if(receiverTick<0)throw new IllegalArgumentException("Negative delivery time");
        var buffers=new HashMap<>(state.buffers);var pending=new HashMap<>(state.pending);var delivered=new ArrayList<Delivered>();var seen=new HashSet<UUID>();
        for(var request:feasible) {
            var record=pending.get(request.id);
            if(!seen.add(request.id)||record==null||record.revision()!=request.expectedRevision||record.dueTick()>receiverTick)throw new IllegalStateException("Stale, duplicate or future delivery");
            var portion=record.remaining().takeMass(request.massKg);if(portion.delivered().empty())continue;
            var buffer=buffers.get(record.receiver());var reservations=new HashMap<>(buffer.reservations);
            if(portion.remainder().empty()){pending.remove(record.id());reservations.remove(record.id());}
            else {
                pending.put(record.id(),new PendingTransfers.Pending(record.id(),record.producer(),record.receiver(),record.dueTick(),Math.addExact(record.revision(),1),portion.remainder()));
                reservations.put(record.id(),portion.remainder().massKg());
            }
            buffers.put(buffer.id,new Buffer(buffer.id,buffer.capacityKg,buffer.occupiedKg+portion.delivered().massKg(),reservations));
            delivered.add(new Delivered(record.id(),record.receiver(),portion.delivered()));
        }
        return delivered.isEmpty()?new Prepared(state,state,List.of()):prepare(buffers,pending,delivered);
    }
    /** Updates capacity counters from proposed hydraulic inventories while retaining pending reservations. */
    public Prepared occupancy(Map<UUID,Double> proposedMassKg) {
        owned();var buffers=new HashMap<>(state.buffers);
        for(var entry:proposedMassKg.entrySet()) {
            var before=Objects.requireNonNull(buffers.get(entry.getKey()),"Unknown buffer");
            buffers.put(before.id,new Buffer(before.id,before.capacityKg,entry.getValue(),before.reservations));
        }
        return prepare(buffers,state.pending,List.of());
    }
    private Prepared prepare(Map<UUID,Buffer> buffers,Map<UUID,PendingTransfers.Pending> pending,List<Delivered> delivered) {
        return prepare(buffers,pending,state.planned,delivered);
    }
    private Prepared prepare(Map<UUID,Buffer> buffers,Map<UUID,PendingTransfers.Pending> pending,Map<UUID,CapacityReservation> planned,List<Delivered> delivered) {
        return new Prepared(state,new Snapshot(Math.addExact(state.revision,1),buffers,pending,planned),delivered);
    }
    public void validate(Prepared proposal){owned();if(proposal.before!=state)throw new IllegalStateException("Stale buffer transaction");}
    /** Compose validated delivery and hydraulic-occupancy proposals on a private staging ledger. */
    Prepared combine(Snapshot expected,Snapshot staged) {
        owned();if(state!=expected)throw new IllegalStateException("Stale combined buffer transaction");
        return new Prepared(state,new Snapshot(Math.addExact(state.revision,1),staged.buffers,staged.pending,staged.planned),List.of());
    }
    /** Single pointer publication: failed preparation or a discarded physical solve changes nothing. */
    public void commit(Prepared proposal){validate(proposal);state=proposal.after;}
}
