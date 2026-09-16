package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;

/** Causal, nonreactive test equipment. It consumes only committed feed withdrawals into its own
 * bounded holdup. A host installs the returned delivery horizon before advancing connected islands
 * and commits each receipt in the same transaction as its hydraulic withdrawal. No V3 solve is used. */
public final class FixedSplitModule {
    public static final String TYPE="createcheme:fixed_split_test";
    public static final String SCIENTIFIC_REVISION="fixed-split-reference-covariant-v1";
    public record Feed(UUID buffer,double kilogramsPerSecond) {
        public Feed {Objects.requireNonNull(buffer);if(!Double.isFinite(kilogramsPerSecond)||kilogramsPerSecond<=0)throw new IllegalArgumentException("Invalid feed rate");}
    }
    public record Definition(UUID id,List<Feed> feeds,UUID firstProduct,UUID secondProduct,int cadenceTicks,double[] firstFractions) {
        public Definition {
            Objects.requireNonNull(id);Objects.requireNonNull(firstProduct);Objects.requireNonNull(secondProduct);feeds=List.copyOf(feeds);firstFractions=firstFractions.clone();
            if(feeds.isEmpty()||feeds.size()>16||feeds.stream().map(Feed::buffer).distinct().count()!=feeds.size()||cadenceTicks!=100&&cadenceTicks!=300&&cadenceTicks!=600||firstFractions.length==0)throw new IllegalArgumentException("Invalid fixed-split definition");
            for(double fraction:firstFractions)if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid component split");
        }
        @Override public double[] firstFractions(){return firstFractions.clone();}
    }
    /** An observation is borrowed committed stock, never module ownership. */
    public record Observation(long committedTick,MaterialParcel inventory) {
        public Observation {Objects.requireNonNull(inventory);if(committedTick<0)throw new IllegalArgumentException("Invalid feed timestamp");}
    }
    public record Input(UUID withdrawalId,long throughTick,double targetKg,MaterialParcel owned) {
        public Input {Objects.requireNonNull(withdrawalId);Objects.requireNonNull(owned);if(throughTick<0||!Double.isFinite(targetKg)||targetKg<0||owned.massKg()>targetKg+1e-9*Math.max(1,targetKg))throw new IllegalArgumentException("Invalid module input ownership");}
    }
    public record Promise(UUID id,UUID receiver,int outlet,double maximumKg) {
        public Promise {Objects.requireNonNull(id);Objects.requireNonNull(receiver);if(outlet<0||outlet>1||!Double.isFinite(maximumKg)||maximumKg<=0)throw new IllegalArgumentException("Invalid product promise");}
    }
    public record Cycle(long startTick,long endTick,Map<UUID,Input> inputs,List<Promise> promises,String status) {
        public Cycle {
            inputs=Map.copyOf(inputs);promises=List.copyOf(promises);Objects.requireNonNull(status);
            if(startTick<0||endTick<=startTick||inputs.isEmpty()||promises.size()>2||inputs.values().stream().anyMatch(i->i.throughTick()<startTick||i.throughTick()>endTick))throw new IllegalArgumentException("Invalid module cycle");
            if(inputs.values().stream().map(Input::withdrawalId).distinct().count()!=inputs.size()||promises.stream().map(Promise::outlet).distinct().count()!=promises.size()||promises.stream().map(Promise::id).distinct().count()!=promises.size())throw new IllegalArgumentException("Duplicate cycle identity");
        }
        public boolean knownZero(){return inputs.values().stream().allMatch(i->i.targetKg()==0);}
        public boolean inputsComplete(){return inputs.values().stream().allMatch(i->i.throughTick()==endTick);}
    }
    public record Snapshot(Definition definition,long revision,long committedTick,boolean running,Cycle cycle) {
        public Snapshot {
            Objects.requireNonNull(definition);if(revision<0||committedTick<0)throw new IllegalArgumentException("Invalid module clock");
            if(cycle!=null&&(cycle.startTick()!=committedTick||cycle.endTick()-cycle.startTick()!=definition.cadenceTicks()||!cycle.inputs().keySet().equals(new HashSet<>(definition.feeds().stream().map(Feed::buffer).toList()))))throw new IllegalArgumentException("Module cycle/definition mismatch");
            if(cycle!=null) {
                double target=0;MaterialParcel basis=null;
                for(var feed:definition.feeds()) {
                    var input=cycle.inputs().get(feed.buffer());target+=input.targetKg();
                    if(input.owned().moles().length!=definition.firstFractions().length||input.owned().massKg()>input.targetKg()*(input.throughTick()-cycle.startTick())/definition.cadenceTicks()+1e-9*Math.max(1,input.targetKg()))throw new IllegalArgumentException("Module holdup precedes its withdrawal");
                    var zero=input.owned().takeMass(0).delivered();basis=basis==null?zero:basis.plus(zero);
                }
                if(!Double.isFinite(target))throw new IllegalArgumentException("Module target overflow");
                double[] bounds={target*Arrays.stream(definition.firstFractions()).max().orElseThrow(),target*(1-Arrays.stream(definition.firstFractions()).min().orElseThrow())};
                for(int outlet=0;outlet<2;outlet++) {
                    int index=outlet;var promise=cycle.promises().stream().filter(p->p.outlet()==index).findFirst();
                    UUID receiver=outlet==0?definition.firstProduct():definition.secondProduct();
                    if(bounds[outlet]>0&&(promise.isEmpty()||!promise.orElseThrow().receiver().equals(receiver)||promise.orElseThrow().maximumKg()+1e-9*Math.max(1,bounds[outlet])<bounds[outlet])||bounds[outlet]==0&&promise.isPresent())throw new IllegalArgumentException("Module interval lacks complete product capacity");
                }
            }
        }
    }
    public record Withdrawal(UUID id,UUID buffer,double maximumKg) {}
    public record Receipt(UUID id,long fromTick,long toTick,MaterialParcel actual) {
        public Receipt {Objects.requireNonNull(id);Objects.requireNonNull(actual);}
    }
    public static final class Prepared {
        private final Snapshot before,after;
        private final BufferedTransfers.Prepared capacity;
        private Prepared(Snapshot before,Snapshot after,BufferedTransfers.Prepared capacity){this.before=before;this.after=after;this.capacity=capacity;}
        public Snapshot after(){return after;}
    }
    private final Thread owner=Thread.currentThread();
    private final BufferedTransfers transfers;
    private final BufferBackpressure policy;
    private Snapshot state;
    public FixedSplitModule(Snapshot restored,BufferedTransfers transfers,BufferBackpressure policy) {
        this.state=Objects.requireNonNull(restored);this.transfers=Objects.requireNonNull(transfers);this.policy=Objects.requireNonNull(policy);
        if(!transfers.snapshot().buffers().containsKey(restored.definition().firstProduct())||!transfers.snapshot().buffers().containsKey(restored.definition().secondProduct()))throw new IllegalArgumentException("Missing module product buffer");
        if(restored.cycle()!=null)for(var promise:restored.cycle().promises()) {
            var saved=transfers.snapshot().planned().get(promise.id());
            if(saved==null||!saved.producer().equals(restored.definition().id())||!saved.receiver().equals(promise.receiver())||saved.dueTick()!=restored.cycle().endTick()||saved.maximumKg()!=promise.maximumKg())throw new IllegalArgumentException("Restored module lost its production reservation");
        }
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Module belongs to its coordinator thread");}
    public Snapshot snapshot(){owned();return state;}

    /** Prepare capacity and a future delivery/zero-delivery horizon from exactly t0 feed states. */
    public Prepared begin(Map<UUID,Observation> observations) {
        owned();if(state.cycle()!=null)throw new IllegalStateException("Module interval already active");
        var d=state.definition();long start=state.committedTick(),end=Math.addExact(start,d.cadenceTicks());
        var available=new ArrayList<Double>();double total=0;var targets=new LinkedHashMap<UUID,Double>();MaterialParcel empty=null;
        for(var feed:d.feeds()) {
            var observation=Objects.requireNonNull(observations.get(feed.buffer()),"Missing feed observation");
            if(observation.committedTick()!=start)throw new IllegalStateException("WAITING: feed "+feed.buffer()+" at "+observation.committedTick()+", required "+start);
            var parcel=observation.inventory();if(parcel.moles().length!=d.firstFractions().length)throw new IllegalArgumentException("Split basis mismatch");
            var zero=parcel.takeMass(0).delivered();empty=empty==null?zero:empty.plus(zero);
            double kg=Math.min(parcel.massKg(),feed.kilogramsPerSecond()*d.cadenceTicks()/20.0);targets.put(feed.buffer(),kg);available.add(parcel.massKg());total+=kg;
        }
        var ledger=transfers.snapshot();var free=new ArrayList<Double>();
        double maxFirst=Arrays.stream(d.firstFractions()).max().orElseThrow(),maxSecond=1-Arrays.stream(d.firstFractions()).min().orElseThrow();
        if(maxFirst>0)free.add(ledger.freeKg(d.firstProduct()));if(maxSecond>0)free.add(ledger.freeKg(d.secondProduct()));
        var needs=new HashMap<UUID,Double>();needs.merge(d.firstProduct(),total*maxFirst,Double::sum);needs.merge(d.secondProduct(),total*maxSecond,Double::sum);
        boolean capacity=needs.entrySet().stream().allMatch(e->e.getValue()<=ledger.freeKg(e.getKey()));
        boolean run=total>0&&capacity&&policy.mayRun(state.running(),available,free);
        var promises=new ArrayList<Promise>();
        if(run){if(maxFirst>0)promises.add(new Promise(UUID.randomUUID(),d.firstProduct(),0,total*maxFirst));if(maxSecond>0)promises.add(new Promise(UUID.randomUUID(),d.secondProduct(),1,total*maxSecond));}
        var inputs=new LinkedHashMap<UUID,Input>();for(var entry:targets.entrySet())inputs.put(entry.getKey(),new Input(UUID.randomUUID(),run?start:end,run?entry.getValue():0,empty));
        String status=run?"WAITING: committed feed withdrawals":capacity?"ZERO PRODUCTION: feed/backpressure policy":"ZERO PRODUCTION: product capacity";
        var cycle=new Cycle(start,end,inputs,promises,status);
        var reservation=promises.isEmpty()?null:transfers.reserveCapacity(promises.stream().map(p->new BufferedTransfers.CapacityReservation(p.id(),d.id(),p.receiver(),end,p.maximumKg())).toList());
        return new Prepared(state,new Snapshot(d,Math.addExact(state.revision(),1),start,run,cycle),reservation);
    }
    public List<Withdrawal> withdrawals(long fromTick,long toTick,Set<UUID> feedBuffers) {
        owned();var cycle=Objects.requireNonNull(state.cycle(),"Module has no announced interval");
        if(toTick<=fromTick||fromTick<cycle.startTick()||toTick>cycle.endTick())throw new IllegalArgumentException("Withdrawal crosses a module horizon");
        var requests=new ArrayList<Withdrawal>();
        for(var buffer:feedBuffers) {
            var input=cycle.inputs().get(buffer);if(input==null||input.targetKg()==0)continue;
            if(input.throughTick()!=fromTick)throw new IllegalStateException("Future or replayed feed read");
            requests.add(new Withdrawal(input.withdrawalId(),buffer,input.targetKg()*(toTick-fromTick)/(cycle.endTick()-cycle.startTick())));
        }
        return List.copyOf(requests);
    }
    /** Prepare, never independently apply, a receipt for the hydraulic interval being committed. */
    public Prepared receive(UUID withdrawal,long fromTick,long toTick,MaterialParcel actual) {
        return receive(List.of(new Receipt(withdrawal,fromTick,toTick,actual)));
    }
    public Prepared receive(List<Receipt> receipts) {
        owned();var cycle=Objects.requireNonNull(state.cycle());var inputs=new HashMap<>(cycle.inputs());var seen=new HashSet<UUID>();
        for(var receipt:receipts) {
            if(!seen.add(receipt.id()))throw new IllegalStateException("Duplicate staged module receipt");
            var entry=inputs.entrySet().stream().filter(e->e.getValue().withdrawalId().equals(receipt.id())).findFirst().orElseThrow(()->new IllegalStateException("Unknown module withdrawal"));
            var old=entry.getValue();double allowed=old.targetKg()*(receipt.toTick()-receipt.fromTick())/(cycle.endTick()-cycle.startTick());
            if(old.throughTick()!=receipt.fromTick()||receipt.toTick()<=receipt.fromTick()||receipt.toTick()>cycle.endTick()||receipt.actual().massKg()>allowed+1e-9*Math.max(1,allowed))throw new IllegalStateException("Future, duplicate or excessive withdrawal receipt");
            inputs.put(entry.getKey(),new Input(old.withdrawalId(),receipt.toTick(),old.targetKg(),old.owned().plus(receipt.actual())));
        }
        var next=new Cycle(cycle.startTick(),cycle.endTick(),inputs,cycle.promises(),"WAITING: remaining feed withdrawals");
        return new Prepared(state,new Snapshot(state.definition(),Math.addExact(state.revision(),1),state.committedTick(),state.running(),next),null);
    }
    /** Consume actual holdup exactly once and turn its reservations into due pending products. */
    public Prepared finish(long onlineTick) {
        owned();var cycle=Objects.requireNonNull(state.cycle());
        if(onlineTick<cycle.endTick()||!cycle.inputsComplete())throw new IllegalStateException("WAITING: module feed horizon "+cycle.endTick());
        MaterialParcel total=null;for(var input:cycle.inputs().values())total=total==null?input.owned():total.plus(input.owned());
        var products=Objects.requireNonNull(total).split(state.definition().firstFractions());var pending=new ArrayList<PendingTransfers.Pending>();var resolved=new HashSet<UUID>();
        for(var promise:cycle.promises()){resolved.add(promise.id());var product=products.get(promise.outlet());if(!product.empty())pending.add(new PendingTransfers.Pending(promise.id(),state.definition().id(),promise.receiver(),cycle.endTick(),0,product));}
        var capacity=resolved.isEmpty()?null:transfers.produce(resolved,pending);
        return new Prepared(state,new Snapshot(state.definition(),Math.addExact(state.revision(),1),cycle.endTick(),!total.empty(),null),capacity);
    }
    public void validate(Prepared proposal){owned();if(proposal.before!=state)throw new IllegalStateException("Stale module proposal");if(proposal.capacity!=null)transfers.validate(proposal.capacity);}
    /** Call inside the host's atomic ownership publication after all participating proposals validate. */
    public void commit(Prepared proposal){validate(proposal);if(proposal.capacity!=null)transfers.commit(proposal.capacity);state=proposal.after;}
}
