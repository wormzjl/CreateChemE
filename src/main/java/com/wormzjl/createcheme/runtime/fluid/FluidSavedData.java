package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/**
 * The world's fluid checkpoint as overworld saved data, independent of chunk residency: the core record of checkpoint
 * format 6 ({@link FluidCheckpointCodec}) is this saved data's file, and its island and topology units live in pack
 * files beside it ({@link FluidCheckpointStore}). A save is prepared on the server thread - the live authority is
 * captured (certified islands are materialised) and only the units whose island changed since they were written are
 * encoded - and committed on Minecraft's IO worker: the new pack, then the core record, each written atomically. The
 * dirty mark stays per tick, so online time is never lost at a save; an unchanged island costs nothing but its index
 * row.
 */
public final class FluidSavedData extends SavedData {
    public static final String DATA_NAME="createcheme_fluid_core";
    private final Thread owner=Thread.currentThread();
    private final Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models;
    private final FluidCheckpointStore store;
    private FluidCheckpointCodec.Checkpoint checkpoint;
    private Optional<WorldTopologyLedger.Snapshot> world=Optional.empty();
    public record Capture(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world) {}
    private java.util.function.Supplier<Capture> capture;
    /**
     * What one save cost: on the server thread, capturing the live authority (which materialises certified islands)
     * and preparing the save (encoding the changed units and the core record); and, for a save committed on the
     * calling thread, writing the new pack and the core record ({@code writeNanos}, 0 for a save committed on the IO
     * worker). {@code stats} says what was encoded, reused and moved, and how many bytes.
     */
    public record SaveTiming(long captureNanos,long encodeNanos,long writeNanos,FluidCheckpointStore.Stats stats) {
        public long totalNanos(){return captureNanos+encodeNanos;}
        public int islands(){return stats.islands();}
        public int payloadsEncoded(){return stats.encoded();}
        public int payloadsReused(){return stats.reused();}
        public int unitsCopied(){return stats.copied();}
        /** Bytes of every island unit the core record references, written now or before. */
        public long payloadBytes(){return stats.islandBytes();}
        public long ledgerBytes(){return stats.ledgerBytes();}
        public long topologyBytes(){return stats.topologyBytes();}
        public boolean topologyEncoded(){return stats.topologyEncoded();}
        /** Bytes this save wrote: the new pack (0 when none) and the core record. */
        public long bytes(){return stats.packBytes()+stats.coreBytes();}
    }
    private SaveTiming lastSave;

    /** Saved data kept in memory ({@link FluidCheckpointStore#memory}): tests and tools. */
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models){this(checkpoint,models,FluidCheckpointStore.memory());}
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models){this(checkpoint,world,models,FluidCheckpointStore.memory());}
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,FluidCheckpointStore store) {
        this.checkpoint=Objects.requireNonNull(checkpoint);this.models=Objects.requireNonNull(models);this.store=Objects.requireNonNull(store);
    }
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,FluidCheckpointStore store) {
        this(checkpoint,models,store);this.world=Optional.of(Objects.requireNonNull(world));
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Fluid SavedData must be captured on its server thread");}
    public FluidCheckpointCodec.Checkpoint checkpoint(){owned();return checkpoint;}
    /** Empty for a checkpoint saved without its world topology (a core-only fixture). */
    public Optional<WorldTopologyLedger.Snapshot> world(){owned();return world;}
    public FluidCheckpointStore store(){return store;}
    /** Snapshot live authority only at the server's save boundary, before its asynchronous file write. */
    public void bindCapture(java.util.function.Supplier<Capture> capture){owned();this.capture=Objects.requireNonNull(capture);}
    public void replace(FluidCheckpointCodec.Checkpoint next){owned();checkpoint=Objects.requireNonNull(next);setDirty();}
    public void replace(FluidCheckpointCodec.Checkpoint next,WorldTopologyLedger.Snapshot nextWorld) {
        owned();Objects.requireNonNull(next);Objects.requireNonNull(nextWorld);checkpoint=next;world=Optional.of(nextWorld);setDirty();
    }
    /** The cost of the last save, or null before the first. */
    public SaveTiming lastSave(){owned();return lastSave;}
    /** Forgets where every unit is stored, so the next save encodes and writes them all: a cold save. */
    public void clearPayloadCache(){owned();store.forget();}

    private FluidCheckpointStore.Plan prepare() {
        owned();long started=System.nanoTime();
        if(capture!=null){var current=capture.get();replace(current.checkpoint,current.world);}
        long captured=System.nanoTime();
        // The world epoch: the topology's online tick, or for a core-only checkpoint its most advanced island.
        long epoch=world.map(WorldTopologyLedger.Snapshot::onlineTick).orElseGet(()->checkpoint.islands().stream().mapToLong(i->i.snapshot().clock().onlineTick()).max().orElse(0));
        var plan=store.prepare(checkpoint,world,epoch,models);
        lastSave=new SaveTiming(captured-started,System.nanoTime()-captured,0,plan.stats());
        return plan;
    }
    /** Minecraft's save: prepared on the server thread, committed on the IO worker (a flushing save waits for it). */
    @Override public void save(File file,HolderLookup.Provider registries) {
        if(!isDirty())return;
        // The core record must be the file Minecraft reads this saved data from at the next load.
        if(store.backend() instanceof FluidCheckpointStore.Directory directory&&!sameFile(directory.core,file.toPath()))
            throw new IllegalStateException("Fluid saved data asked to save to "+file+" but its store writes "+directory.core);
        store.submit(prepare());
        setDirty(false);
    }
    /** A save committed on the calling thread, after any submitted one: returns the core record it wrote. */
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        var plan=prepare();long started=System.nanoTime();store.commit(plan);
        lastSave=new SaveTiming(lastSave.captureNanos(),lastSave.encodeNanos(),System.nanoTime()-started,lastSave.stats());
        if(!store.committed())throw new IllegalStateException("Fluid checkpoint save was not written; see the log");
        return tag.merge(plan.core().copy());
    }
    /**
     * Reads format 6: the core record and the units of {@code store}. Any other format is refused with the instruction
     * to create a fresh world: there is no upgrade from format 4 or older.
     */
    public static FluidSavedData load(CompoundTag core,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,FluidCheckpointStore store) {
        var loaded=store.load(core,models);var data=new FluidSavedData(loaded.checkpoint(),models,store);data.world=loaded.world();return data;
    }
    /** Reads the checkpoint {@code store} committed last, if any. */
    public static Optional<FluidSavedData> read(FluidCheckpointStore store,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) throws IOException {
        return store.read(models).map(loaded->{var data=new FluidSavedData(loaded.checkpoint(),models,store);data.world=loaded.world();return data;});
    }
    private static boolean sameFile(Path a,Path b) {
        if(a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize()))return true;
        try{return a.getFileName().equals(b.getFileName())&&Files.isSameFile(a.toAbsolutePath().getParent(),b.toAbsolutePath().getParent());}catch(IOException unknown){return false;}
    }
    /** A failed read must never be treated as an empty new world and reinitialize its reservoirs. */
    public static FluidSavedData open(MinecraftServer server,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        if(!server.isSameThread())throw new IllegalStateException("Fluid world data requires the server thread");
        var dataFolder=server.getWorldPath(LevelResource.ROOT).resolve("data");
        var store=FluidCheckpointStore.directory(dataFolder,net.neoforged.neoforge.common.IOUtilities::withIOWorker,true);
        var storage=server.overworld().getDataStorage();
        var factory=new SavedData.Factory<>(()->new FluidSavedData(new FluidCheckpointCodec.Checkpoint(List.of(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of())),models,store),(tag,registries)->load(tag,models,store));
        var existing=storage.get(factory,DATA_NAME);
        if(existing!=null){existing.store.cleanOrphans();return existing;}
        var path=dataFolder.resolve(FluidCheckpointStore.CORE_NAME);
        try {
            Files.readAttributes(path,BasicFileAttributes.class);
            // The storage logs the failure and returns nothing; read the checkpoint again so the refusal names its
            // reason (an older format, a checksum, a missing or damaged pack, a changed property basis). Nothing is
            // written or deleted: the core record and every pack are left exactly as they are.
            String reason;
            try{load(net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data"),models,FluidCheckpointStore.directory(dataFolder,Runnable::run,false));reason="unknown reason";}
            catch(IOException|RuntimeException failure){reason=failure.getMessage();}
            throw new IllegalStateException("Existing fluid authority could not be read: "+reason+" Refusing to replace its inventories: "+path);
        }catch(NoSuchFileException missing) {
            var created=factory.constructor().get();storage.set(DATA_NAME,created);created.setDirty();return created;
        }catch(IOException failure){throw new IllegalStateException("Cannot establish whether fluid authority exists",failure);}
    }
}
