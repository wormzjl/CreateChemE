package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import java.util.*;

/** Persistent physical identities and timestamped edits, separate from loaded block-entity bindings. */
public final class WorldTopologyLedger {
    public static final int MAXIMUM_EVENTS=4096;
    public record Registration(PhysicalFluidTopology.Device device,FluidDeviceSpec spec,long revision) {
        public Registration {Objects.requireNonNull(device);Objects.requireNonNull(spec);if(revision<0)throw new IllegalArgumentException("Negative device revision");}
    }
    /** A null replacement removes that identity. Movement uses removal plus a new identity. */
    public record Edit(long id,Registration replacement) {
        public Edit {if(id<=0||replacement!=null&&replacement.device.id()!=id)throw new IllegalArgumentException("Invalid physical edit");}
    }
    public record Event(UUID id,long tick,List<Edit> edits,Set<Long> touched) {
        public Event {Objects.requireNonNull(id);edits=List.copyOf(edits);touched=Set.copyOf(touched);if(tick<0||edits.isEmpty())throw new IllegalArgumentException("Invalid topology event");}
    }
    /** Cumulative explicit construction/destruction, in the common network basis; these are not live stock. */
    public record MaterialTotal(double[] moles,double totalEnergy) {
        public MaterialTotal {
            moles=moles.clone();if((moles.length<1||moles.length>com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS)||!Double.isFinite(totalEnergy))throw new IllegalArgumentException("Invalid external material total");
            for(double n:moles)if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid external component total");
        }
        @Override public double[] moles(){return moles.clone();}
        public static MaterialTotal empty(){return empty(com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount());}
        public static MaterialTotal empty(int count){if(count<1||count>com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS)throw new IllegalArgumentException("Invalid external material axis size");return new MaterialTotal(new double[count],0);}
        public MaterialTotal plus(Collection<PassiveNetwork.Reservoir> reservoirs,double[] weights) {
            var total=moles.clone();double energy=totalEnergy;
            for(var node:reservoirs) {
                var n=node.inventory().moles();if(n.length!=total.length||weights.length!=total.length)throw new IllegalArgumentException("External ledger basis mismatch");double mass=0;
                for(int c=0;c<n.length;c++){total[c]+=n[c];mass+=n[c]*weights[c];}
                energy+=node.inventory().internalEnergy()+mass*com.wormzjl.createcheme.science.fluid.network.PassiveStepSolver.GRAVITY*node.elevation();
            }
            return new MaterialTotal(total,energy);
        }
    }
    public record Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,
                           MaterialTotal constructed,MaterialTotal destroyed,FluidBasis basis) {
        public Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,MaterialTotal constructed,MaterialTotal destroyed) {
            this(onlineTick,nextIdentity,active,events,constructed,destroyed,FluidBasis.capture(com.wormzjl.createcheme.science.material.MaterialRuntime.current()));
        }
        public Snapshot {
            if(onlineTick<0||nextIdentity<1||events.size()>MAXIMUM_EVENTS)throw new IllegalArgumentException("Invalid world topology snapshot");
            active=Map.copyOf(active);events=List.copyOf(events);Objects.requireNonNull(constructed);Objects.requireNonNull(destroyed);
            Objects.requireNonNull(basis);int count=basis.components().size();
            if(constructed.moles().length!=count||destroyed.moles().length!=count)throw new IllegalArgumentException("Topology accounting axis mismatch");
            for(var registration:active.values())if(registration.spec().composition().length!=count)throw new IllegalArgumentException("Topology device axis mismatch");
            for(var event:events)for(var edit:event.edits())if(edit.replacement()!=null&&edit.replacement().spec().composition().length!=count)throw new IllegalArgumentException("Topology event axis mismatch");
            var positions=new HashSet<PhysicalFluidTopology.Position>();
            for(var entry:active.entrySet())if(entry.getKey()!=entry.getValue().device.id()||entry.getKey()>=nextIdentity||!positions.add(entry.getValue().device.position()))throw new IllegalArgumentException("Invalid active physical identity/position");
            long last=-1;var ids=new HashSet<UUID>();
            for(var event:events){if(event.tick<last||event.tick>onlineTick||!ids.add(event.id))throw new IllegalArgumentException("Invalid topology event ordering");last=event.tick;}
        }
        public static Snapshot empty(){return empty(com.wormzjl.createcheme.science.material.MaterialRuntime.current());}
        public static Snapshot empty(com.wormzjl.createcheme.science.material.MaterialCatalog catalog) {
            var basis=FluidBasis.capture(catalog);int count=basis.components().size();
            return new Snapshot(0,1,Map.of(),List.of(),MaterialTotal.empty(count),MaterialTotal.empty(count),basis);
        }
    }
    public static final class Prepared {
        private final Snapshot before,after;
        private final Event event;
        private final long preparedTick;
        private Prepared(Snapshot before,Snapshot after,Event event){this.before=before;this.after=after;this.event=event;preparedTick=after.onlineTick;}
        public Snapshot after(){return after;}
        public Event event(){return event;}
    }
    private final Thread owner=Thread.currentThread();
    private Snapshot state;
    private long onlineTick;
    public WorldTopologyLedger(Snapshot restored){state=Objects.requireNonNull(restored);onlineTick=restored.onlineTick;latest();}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("World topology belongs to the server thread");}
    public Snapshot snapshot(){owned();return onlineTick==state.onlineTick?state:new Snapshot(onlineTick,state.nextIdentity,state.active,state.events,state.constructed,state.destroyed,state.basis);}
    public long onlineTick(){owned();return onlineTick;}
    public long nextIdentity(){owned();return state.nextIdentity;}
    public boolean hasPendingEvents(){owned();return !state.events.isEmpty();}
    public Map<Long,Registration> active(){owned();return state.active;}
    public Map<Long,Registration> latest() {
        owned();var records=new LinkedHashMap<>(state.active);
        for(var event:state.events)apply(records,event.edits);
        return Map.copyOf(records);
    }
    public void tick(){owned();onlineTick=Math.addExact(onlineTick,1);}
    /** The caller allocates from nextIdentity, then installs event fences before publishing this preparation. */
    public Prepared queue(List<Edit> edits,Set<Long> touched,long nextIdentity) {
        owned();if(state.events.size()>=MAXIMUM_EVENTS)throw new IllegalStateException("Topology history capacity exhausted");
        if(nextIdentity<state.nextIdentity)throw new IllegalArgumentException("Identity sequence moved backwards");
        var checked=new LinkedHashMap<>(latest());
        for(var edit:edits)if(edit.replacement!=null&&!checked.containsKey(edit.id)&&edit.id<state.nextIdentity)throw new IllegalStateException("Retired physical identity cannot be reused");
        apply(checked,edits);
        for(long id:checked.keySet())if(id>=nextIdentity)throw new IllegalArgumentException("New identity was not reserved");
        var event=new Event(UUID.randomUUID(),onlineTick,edits,touched);var events=new ArrayList<>(state.events);events.add(event);
        return new Prepared(state,new Snapshot(onlineTick,nextIdentity,state.active,events,state.constructed,state.destroyed,state.basis),event);
    }
    /** Returns an application with accounting staged; nothing is removed from the history until commit. */
    public Prepared applyFirst(Collection<PassiveNetwork.Reservoir> additions,Collection<PassiveNetwork.Reservoir> removals,double[] weights,long nextIdentity) {
        owned();if(state.events.isEmpty())throw new IllegalStateException("No applicable topology event");
        return applyReady(state.events.getFirst().id,additions,removals,weights,nextIdentity);
    }
    /** Events may pass an earlier event only when physical identities AND positions are disjoint.
     * Position dependencies keep removal/replacement ordered even though identities differ. */
    public List<Event> readyEvents() {
        owned();var positions=new HashMap<Long,PhysicalFluidTopology.Position>();
        state.active.forEach((id,r)->positions.put(id,r.device.position()));
        for(var event:state.events)for(var edit:event.edits)if(edit.replacement!=null)positions.put(edit.id,edit.replacement.device.position());
        var earlierIds=new HashSet<Long>();var earlierPositions=new HashSet<PhysicalFluidTopology.Position>();var ready=new ArrayList<Event>();
        for(var event:state.events) {
            var ids=new HashSet<>(event.touched);for(var edit:event.edits)ids.add(edit.id);
            var locations=new HashSet<PhysicalFluidTopology.Position>();for(long id:ids){var position=positions.get(id);if(position!=null)locations.add(position);}
            if(Collections.disjoint(ids,earlierIds)&&Collections.disjoint(locations,earlierPositions))ready.add(event);
            earlierIds.addAll(ids);earlierPositions.addAll(locations);
        }
        return List.copyOf(ready);
    }
    /** Stage one independent event at its original timestamp; the caller still aligns its owners. */
    public Prepared applyReady(UUID eventId,Collection<PassiveNetwork.Reservoir> additions,Collection<PassiveNetwork.Reservoir> removals,double[] weights,long nextIdentity) {
        owned();if(nextIdentity<state.nextIdentity)throw new IllegalStateException("Identity sequence moved backwards");
        var event=readyEvents().stream().filter(e->e.id.equals(eventId)).findFirst().orElseThrow(()->new IllegalStateException("Topology event has unresolved earlier dependencies"));
        var records=new LinkedHashMap<>(state.active);apply(records,event.edits);
        var remaining=state.events.stream().filter(e->!e.id.equals(eventId)).toList();
        var next=new Snapshot(onlineTick,nextIdentity,records,remaining,state.constructed.plus(additions,weights),state.destroyed.plus(removals,weights),state.basis);
        return new Prepared(state,next,event);
    }
    public void validate(Prepared prepared){owned();if(prepared.before!=state||prepared.preparedTick!=onlineTick)throw new IllegalStateException("Stale topology preparation");}
    public void commit(Prepared prepared){validate(prepared);state=prepared.after;}
    private static void apply(Map<Long,Registration> records,List<Edit> edits) {
        var seen=new HashSet<Long>();
        for(var edit:edits) {
            if(!seen.add(edit.id))throw new IllegalArgumentException("Duplicate identity in topology event");
            var previous=records.get(edit.id);var replacement=edit.replacement;
            if(replacement==null) {if(records.remove(edit.id)==null)throw new IllegalStateException("Missing removed device");continue;}
            if(previous!=null&&(!previous.device.position().equals(replacement.device.position())||previous.device.kind()!=replacement.device.kind()||replacement.revision!=Math.addExact(previous.revision,1)))throw new IllegalStateException("Moved or stale device edit");
            if(previous==null&&replacement.revision!=0)throw new IllegalStateException("New device has a stale revision");
            if(previous!=null&&previous.device.kind()==com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.RESERVOIR&&!previous.spec.equals(replacement.spec))throw new IllegalStateException("Finite-reservoir initialization cannot be reapplied");
            records.put(edit.id,replacement);
        }
        var positions=new HashSet<PhysicalFluidTopology.Position>();for(var record:records.values())if(!positions.add(record.device.position()))throw new IllegalStateException("Two devices occupy one position");
    }
}
