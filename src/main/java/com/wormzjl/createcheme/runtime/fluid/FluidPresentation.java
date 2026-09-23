package com.wormzjl.createcheme.runtime.fluid;

import java.util.*;
import java.util.function.Supplier;

/**
 * The engine-owned presentation schedule (plan R2 and section 3.4). Loaded devices and open menus receive
 * coalesced updates on staggered presentation buckets, about every {@value #BUCKET_TICKS} online ticks, and at
 * no other time: nothing is pushed from a packet handler, a block-entity load, a menu open or a tick hook.
 *
 * <p>A device or a menu belongs to bucket {@code key % 100}, where the key is the island that owns the device, or
 * the device itself when it has no hydraulic owner. An island's loaded devices and its menus are therefore
 * presented together, from one read of the island, at the online ticks {@code t} with
 * {@code t % 100 == key % 100}. A publication, a chunk load, a block-entity load or an identity binding only
 * marks a loaded device dirty; an open menu is presented at every bucket of its island, its static part only
 * when the device's registration revision changed since the menu last received it.
 *
 * <p>Player inputs are queued. An edit or a recovery is validated by its packet handler, queued as a ledger event
 * and recorded here without a reply. The reply is composed at the first bucket of the menu after the online tick
 * the input arrived in: {@value #REFUSED}{@code <reason>} for a refusal, {@value #APPLIED} once its event has been
 * applied, otherwise {@value #QUEUED}{@code N}, followed by {@value #APPLIED} at the first bucket after the event
 * applies. Until the reply the menu shows {@value #WAITING}.
 *
 * <p>Cost. The per-tick check is one comparison with the next due bucket tick. A flush visits only its own
 * bucket's dirty devices and menus, and the next due tick is found over a 100-bit mask of non-empty buckets. A
 * view is built at most once per device per flush, from at most one read of its island, and only for a device
 * that is loaded or has an open menu. Presentation never decides anything the simulation reads.
 *
 * @param <C> the consumer type: an open menu
 */
public final class FluidPresentation<C> {
    public static final int BUCKET_TICKS=100;
    public static final String QUEUED="Queued for simulation event at tick ",APPLIED="Applied",REFUSED="Not applied: ",WAITING="Waiting for the engine";
    /** Bounds on per-menu memory: queued inputs awaiting a reply, and the diagnostic delivery log. */
    private static final int MAXIMUM_INPUTS=64,MAXIMUM_LOG=256,MAXIMUM_REPLY=1024;

    /** What presentation needs from its world. Called on the owning thread, only from {@link #tick} and the marking calls. */
    public interface Host<C> {
        /** The bucket key of a device: the island that owns it, or the device itself when it has none. */
        long key(long device);
        /** The device's registration revision, or -1 once it is gone. */
        long revision(long device);
        /** Whether a queued ledger event is still waiting to be applied. */
        boolean eventPending(UUID event);
        /** Why an applied event did nothing for its input (a recovery that found no cake), consumed once; null when it took effect. */
        default String eventRefusal(UUID event){return null;}
        /** Whether a menu is still open and valid for its player; a closed one is forgotten. */
        boolean open(C consumer);
        /** Builds a device's view. {@code islands} caches the island reads of one flush, so an island is read once. */
        FluidView view(long device,Map<Long,IslandCoordinator.Snapshot> islands);
        /** Presents a dirty device if it is loaded and bound, handing it {@code view.get()}; false when it is not loaded. */
        boolean present(long device,Supplier<FluidView> view);
        /** Delivers one bucket to an open menu: the static payload when {@code withStatic}, then the live payload with the composed reply (empty for none). */
        void deliver(C consumer,long device,boolean withStatic,FluidView view,String reply);
        /** One device or menu could not be presented; the bucket goes on with the others. The default rethrows (tests). */
        default void failed(long device,RuntimeException failure){throw failure;}
    }
    /** An input's arrival, for its reply: a refusal, or the ledger event it queued (null with no refusal: nothing to change). */
    public record Input(long receivedTick,String refusal,UUID event,long eventTick) {
        public Input {if(receivedTick<0)throw new IllegalArgumentException("Negative input tick");}
        public static Input refused(long tick,String reason){return new Input(tick,String.valueOf(reason),null,-1);}
        public static Input queued(long tick,UUID event,long eventTick){return new Input(tick,null,event,eventTick);}
    }
    /** One bucket delivered to one menu, for diagnostics and tests: the tick and the bucket (key modulo 100) it was delivered for, whether the static part went with it, the reply, and the view it carried. */
    public record Delivery(long tick,int bucket,boolean withStatic,String reply,String status,long viewOnlineTick,long viewCommittedTick,long revision) {}
    /** Diagnostics: devices known to be loaded, open menus, dirty devices, and the next due bucket tick. */
    public record Stats(int loadedDevices,int menus,int dirtyDevices,long nextDue) {}

    private static final class Pending {
        private final Input input;
        private boolean queuedReported;
        private Pending(Input input){this.input=input;}
    }
    private static final class Subscription<C> {
        private final C consumer;
        private final long device;
        private int slot=-1;
        private long staticRevision=Long.MIN_VALUE;
        private final ArrayDeque<Pending> inputs=new ArrayDeque<>();
        private final ArrayDeque<Delivery> log=new ArrayDeque<>();
        private Subscription(C consumer,long device){this.consumer=consumer;this.device=device;}
    }

    private final Thread owner=Thread.currentThread();
    private final Host<C> host;
    private final Map<C,Subscription<C>> subscriptions=new IdentityHashMap<>();
    private final List<List<Subscription<C>>> menusBySlot=new ArrayList<>(BUCKET_TICKS);
    private final Map<Long,Integer> dirty=new HashMap<>();
    private final List<LinkedHashSet<Long>> dirtyBySlot=new ArrayList<>(BUCKET_TICKS);
    private final Set<Long> loaded=new HashSet<>();
    private final BitSet active=new BitSet(BUCKET_TICKS);
    /** The last tick the hook has presented (flushed if due); marks are due from the tick after it. */
    private long flushedThrough;
    private long nextDue=Long.MAX_VALUE;

    /** {@code startTick} is the current online tick: the first bucket that can be due is the next tick's. */
    public FluidPresentation(Host<C> host,long startTick) {
        this.host=Objects.requireNonNull(host);flushedThrough=startTick;
        for(int i=0;i<BUCKET_TICKS;i++){menusBySlot.add(new ArrayList<>());dirtyBySlot.add(new LinkedHashSet<>());}
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Presentation belongs to its server thread");}
    private static int slot(long key){return (int)Math.floorMod(key,(long)BUCKET_TICKS);}

    // ---- marking: O(1), never a push ----

    /** Marks a device for its next bucket, whether or not it is known to be loaded (a chunk load, a binding). */
    public void markDirty(long device) {
        owned();int slot=slot(host.key(device));Integer previous=dirty.put(device,slot);
        if(previous!=null&&previous!=slot){dirtyBySlot.get(previous).remove(device);settle(previous);}
        dirtyBySlot.get(slot).add(device);activate(slot);
    }
    /** A publication: marks the device only if it is loaded. Unloaded devices are never presented. */
    public void markIfLoaded(long device){owned();if(loaded.contains(device))markDirty(device);}
    /** Marks every loaded device (a property hold or resume changed every status). */
    public void markAllLoaded(){owned();for(long device:List.copyOf(loaded))markDirty(device);}
    /** A block entity loaded or bound: remembered as loaded and marked. The flush confirms it is loaded and bound. */
    public void deviceLoaded(long device){owned();loaded.add(device);markDirty(device);}
    /** A block entity unloaded or removed: no longer marked by publications, and a pending mark is dropped. */
    public void deviceUnloaded(long device) {
        owned();loaded.remove(device);Integer slot=dirty.remove(device);
        if(slot!=null){dirtyBySlot.get(slot).remove(device);settle(slot);}
    }

    // ---- menus and inputs ----

    /** Registers an open menu as a consumer of its device's bucket. Nothing is delivered before that bucket. */
    public void subscribe(C consumer,long device) {
        owned();Objects.requireNonNull(consumer);if(subscriptions.containsKey(consumer))return;
        var subscription=new Subscription<>(consumer,device);subscriptions.put(consumer,subscription);place(subscription);
    }
    public void unsubscribe(C consumer) {
        owned();var subscription=subscriptions.remove(consumer);if(subscription==null)return;
        menusBySlot.get(subscription.slot).remove(subscription);settle(subscription.slot);
    }
    /** Ownership changed (a topology commit replaced islands): every menu follows its device to its new island's bucket. */
    public void rekey() {
        owned();
        for(var subscription:subscriptions.values()) {
            int slot=slot(host.key(subscription.device));if(slot==subscription.slot)continue;
            int previous=subscription.slot;menusBySlot.get(previous).remove(subscription);settle(previous);place(subscription);
        }
    }
    /** Records an input's arrival; its reply is composed at the menu's first bucket after {@code input.receivedTick()}. */
    public void input(C consumer,long device,Input input) {
        owned();Objects.requireNonNull(input);subscribe(consumer,device);var subscription=subscriptions.get(consumer);
        if(subscription.inputs.size()>=MAXIMUM_INPUTS)subscription.inputs.removeFirst();
        subscription.inputs.addLast(new Pending(input));FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.queuedInputs);
    }
    private void place(Subscription<C> subscription){subscription.slot=slot(host.key(subscription.device));menusBySlot.get(subscription.slot).add(subscription);activate(subscription.slot);}

    // ---- the bucket ----

    /**
     * The tick hook, called once per online tick after the tick's engine work: O(1) unless a bucket is due at
     * {@code now}, and then only that bucket's devices and menus. Afterwards every tick up to {@code now} counts as
     * presented, so a device marked later is due at its next bucket after {@code now}.
     */
    public void tick(long now) {
        owned();
        while(nextDue<=now) {
            long due=nextDue;flush(slot(due),now);flushedThrough=Math.max(flushedThrough,due);nextDue=next();
        }
        flushedThrough=Math.max(flushedThrough,now);
    }
    /** The next tick at which a bucket is due, {@link Long#MAX_VALUE} when no device is dirty and no menu is open. */
    public long nextDue(){owned();return nextDue;}
    private void flush(int slot,long now) {
        var menus=menusBySlot.get(slot);var devices=dirtyBySlot.get(slot);
        if(menus.isEmpty()&&devices.isEmpty()){active.clear(slot);return;}
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.bucketFlushes);
        var views=new HashMap<Long,FluidView>();var islands=new HashMap<Long,IslandCoordinator.Snapshot>();
        for(var subscription:List.copyOf(menus)) {
            if(!host.open(subscription.consumer)){unsubscribe(subscription.consumer);continue;}
            try {
                long revision=host.revision(subscription.device);
                var view=views.computeIfAbsent(subscription.device,device->host.view(device,islands));
                String reply=reply(subscription,now);
                boolean withStatic=revision!=subscription.staticRevision;subscription.staticRevision=revision;
                host.deliver(subscription.consumer,subscription.device,withStatic,view,reply);
                if(subscription.log.size()>=MAXIMUM_LOG)subscription.log.removeFirst();
                subscription.log.addLast(new Delivery(now,slot,withStatic,reply,view.status(),view.onlineTick(),view.committedTick(),revision));
            }catch(RuntimeException failure){host.failed(subscription.device,failure);}
        }
        // Drain first: a device marked while it is presented (an identity binding) is due at its next bucket.
        var drained=List.copyOf(devices);devices.clear();for(long device:drained)dirty.remove(device);
        for(long device:drained) {
            boolean presented;
            try{presented=host.present(device,()->views.computeIfAbsent(device,d->host.view(d,islands)));}catch(RuntimeException failure){host.failed(device,failure);continue;}
            if(presented){loaded.add(device);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.devicePresentations);}else loaded.remove(device);
        }
        settle(slot);
    }
    /** The replies owed to a menu at this bucket, joined; inputs that arrived at {@code now} wait for the next one. */
    private String reply(Subscription<C> subscription,long now) {
        var replies=new ArrayList<String>();
        for(var iterator=subscription.inputs.iterator();iterator.hasNext();) {
            var pending=iterator.next();var input=pending.input;
            if(input.receivedTick()>=now)continue;
            if(input.refusal()!=null){replies.add(REFUSED+input.refusal());iterator.remove();}
            else if(input.event()==null||!host.eventPending(input.event())) {
                String refusal=input.event()==null?null:host.eventRefusal(input.event());
                replies.add(refusal==null?APPLIED:REFUSED+refusal);iterator.remove();
            }
            else if(!pending.queuedReported){replies.add(QUEUED+input.eventTick());pending.queuedReported=true;}
        }
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.inputReplies,replies.size());
        String reply=String.join(" / ",replies);
        return reply.length()>MAXIMUM_REPLY?reply.substring(0,MAXIMUM_REPLY-3)+"...":reply;
    }
    private void activate(int slot) {
        active.set(slot);long due=tickOf(slot);if(due<nextDue)nextDue=due;
    }
    /** Clears an empty bucket from the mask; a due tick that became empty costs one empty visit, never a scan. */
    private void settle(int slot){if(menusBySlot.get(slot).isEmpty()&&dirtyBySlot.get(slot).isEmpty())active.clear(slot);}
    /** The first tick after the last flushed one that falls in {@code slot}. */
    private long tickOf(int slot){long from=flushedThrough+1;return from+Math.floorMod(slot-from,(long)BUCKET_TICKS);}
    /** The earliest due tick over the non-empty buckets: the first set bit at or after the next tick's slot, cyclically. */
    private long next() {
        int from=slot(flushedThrough+1);int slot=active.nextSetBit(from);if(slot<0)slot=active.nextSetBit(0);
        return slot<0?Long.MAX_VALUE:tickOf(slot);
    }

    // ---- diagnostics ----

    /** The deliveries made to one menu, oldest first (bounded); empty when it is not subscribed. */
    public List<Delivery> deliveries(C consumer){owned();var subscription=subscriptions.get(consumer);return subscription==null?List.of():List.copyOf(subscription.log);}
    public boolean subscribed(C consumer){owned();return subscriptions.containsKey(consumer);}
    public boolean dirty(long device){owned();return dirty.containsKey(device);}
    public Stats stats(){owned();return new Stats(loaded.size(),subscriptions.size(),dirty.size(),nextDue);}
}
