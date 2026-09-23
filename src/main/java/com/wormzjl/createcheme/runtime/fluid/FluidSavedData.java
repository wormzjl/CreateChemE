package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/**
 * One committed core checkpoint in overworld SavedData, independent of chunk residency: checkpoint format 3
 * ({@link FluidCheckpointCodec}) and, beside it, the world topology ledger. Island payloads are cached across saves
 * (plan section 3.5), so a save copies the payload of every island that did not change since the last save and
 * encodes only the others. The dirty mark stays per tick, so online time is never lost at a save.
 */
public final class FluidSavedData extends SavedData {
    /** The world topology ledger without its online tick, which is the checkpoint's epoch. */
    public static final int TOPOLOGY_VERSION=4;
    public static final String DATA_NAME="createcheme_fluid_core";
    private final Thread owner=Thread.currentThread();
    private final Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models;
    private FluidCheckpointCodec.Checkpoint checkpoint;
    private Optional<WorldTopologyLedger.Snapshot> world=Optional.empty();
    private final FluidCheckpointCodec.PayloadCache payloads=new FluidCheckpointCodec.PayloadCache();
    /** The encoded topology and the snapshot it encodes: kept while the ledger is unchanged, whatever the online tick. */
    private byte[] encodedWorld;private byte[] encodedWorldDigest;private WorldTopologyLedger.Snapshot encodedWorldSource;
    public record Capture(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world) {}
    private java.util.function.Supplier<Capture> capture;
    /**
     * What one save cost on the server thread: capturing the live authority (which materialises certified islands),
     * then encoding - island payloads encoded afresh or copied from the cache, the ledger and the topology - with the
     * bytes each part wrote. For the benchmark's save-time report.
     */
    public record SaveTiming(long captureNanos,long encodeNanos,int islands,int payloadsEncoded,int payloadsReused,long payloadBytes,long ledgerBytes,long topologyBytes,boolean topologyEncoded) {
        public long totalNanos(){return captureNanos+encodeNanos;}
        public long bytes(){return payloadBytes+ledgerBytes+topologyBytes;}
    }
    private SaveTiming lastSave;

    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        this.checkpoint=Objects.requireNonNull(checkpoint);this.models=Objects.requireNonNull(models);
    }
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        this(checkpoint,models);this.world=Optional.of(Objects.requireNonNull(world));
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Fluid SavedData must be captured on its server thread");}
    public FluidCheckpointCodec.Checkpoint checkpoint(){owned();return checkpoint;}
    /** Empty for a checkpoint saved without its world topology (a core-only fixture). */
    public Optional<WorldTopologyLedger.Snapshot> world(){owned();return world;}
    /** Snapshot live authority only at the server's save boundary, before its asynchronous file write. */
    public void bindCapture(java.util.function.Supplier<Capture> capture){owned();this.capture=Objects.requireNonNull(capture);}
    public void replace(FluidCheckpointCodec.Checkpoint next){owned();checkpoint=Objects.requireNonNull(next);setDirty();}
    public void replace(FluidCheckpointCodec.Checkpoint next,WorldTopologyLedger.Snapshot nextWorld) {
        owned();Objects.requireNonNull(next);Objects.requireNonNull(nextWorld);checkpoint=next;world=Optional.of(nextWorld);setDirty();
    }
    /** The cost of the last save, or null before the first. */
    public SaveTiming lastSave(){owned();return lastSave;}
    /** Forgets every cached island payload, so the next save encodes them all: a cold save. */
    public void clearPayloadCache(){owned();payloads.clear();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        owned();long started=System.nanoTime();
        if(capture!=null){var current=capture.get();replace(current.checkpoint,current.world);}
        long captured=System.nanoTime();
        // The world epoch: the topology's online tick, or for a core-only checkpoint its most advanced island.
        long epoch=world.map(WorldTopologyLedger.Snapshot::onlineTick).orElseGet(()->checkpoint.islands().stream().mapToLong(i->i.snapshot().clock().onlineTick()).max().orElse(0));
        var written=FluidCheckpointCodec.write(tag,checkpoint,epoch,models,payloads);
        long topologyBytes=0;boolean topologyEncoded=false;
        if(world.isPresent()) {
            // The topology is saved without its online tick (that is the epoch), so an unchanged ledger keeps its bytes.
            var current=world.orElseThrow();
            if(encodedWorld==null||!FluidCheckpointCodec.sameWorldBody(encodedWorldSource,current)) {
                encodedWorld=FluidCheckpointCodec.encodeWorldBody(current).getBytes(StandardCharsets.UTF_8);encodedWorldDigest=digest(encodedWorld);topologyEncoded=true;
            } else if(FluidCheckpointCodec.verifying()&&!Arrays.equals(encodedWorld,FluidCheckpointCodec.encodeWorldBody(current).getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Cached topology encoding no longer matches the world ledger");
            encodedWorldSource=current;
            tag.putInt("TopologyFormat",TOPOLOGY_VERSION);tag.putByteArray("Topology",encodedWorld.clone());tag.putByteArray("TopologySHA256",encodedWorldDigest.clone());topologyBytes=encodedWorld.length;
        } else {tag.remove("TopologyFormat");tag.remove("Topology");tag.remove("TopologySHA256");}
        long finished=System.nanoTime();
        lastSave=new SaveTiming(captured-started,finished-captured,written.islands(),written.encoded(),written.reused(),written.payloadBytes(),written.ledgerBytes(),topologyBytes,topologyEncoded);
        return tag;
    }
    /**
     * Reads format 3 and its topology. Any other format is refused with the instruction to create a fresh world:
     * there is no upgrade from format 2 or older. The topology is read at the checkpoint's world epoch.
     */
    public static FluidSavedData load(CompoundTag tag,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        var data=new FluidSavedData(FluidCheckpointCodec.decode(tag,models),models);
        if(tag.contains("Topology")||tag.contains("TopologyFormat")||tag.contains("TopologySHA256")) {
            if(!tag.contains("TopologyFormat",Tag.TAG_INT)||tag.getInt("TopologyFormat")!=TOPOLOGY_VERSION||!tag.contains("Topology",Tag.TAG_BYTE_ARRAY)||!tag.contains("TopologySHA256",Tag.TAG_BYTE_ARRAY))
                throw new IllegalArgumentException("Unsupported or incomplete fluid topology (this build reads topology format "+TOPOLOGY_VERSION+" only); create a fresh world for this development build");
            byte[] worldBytes=tag.getByteArray("Topology");if(!MessageDigest.isEqual(digest(worldBytes),tag.getByteArray("TopologySHA256")))throw new IllegalArgumentException("Topology checkpoint checksum mismatch");
            var world=FluidCheckpointCodec.decodeWorldBody(new String(worldBytes,StandardCharsets.UTF_8),FluidCheckpointCodec.epoch(tag));
            data.world=Optional.of(world);data.encodedWorld=worldBytes.clone();data.encodedWorldDigest=digest(worldBytes);data.encodedWorldSource=world;
        }
        return data;
    }
    /** A failed read must never be treated as an empty new world and reinitialize its reservoirs. */
    public static FluidSavedData open(MinecraftServer server,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        if(!server.isSameThread())throw new IllegalStateException("Fluid world data requires the server thread");
        var storage=server.overworld().getDataStorage();
        var factory=new SavedData.Factory<>(()->new FluidSavedData(new FluidCheckpointCodec.Checkpoint(List.of(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of())),models),(tag,registries)->load(tag,models));
        var existing=storage.get(factory,DATA_NAME);if(existing!=null)return existing;
        var path=server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME+".dat");
        try {
            Files.readAttributes(path,BasicFileAttributes.class);
            // The storage logs the failure and returns nothing; read the file again so the refusal names its reason
            // (an older format, a checksum, a changed property basis). The file itself is left exactly as it is.
            String reason;
            try{load(net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data"),models);reason="unknown reason";}
            catch(IOException|RuntimeException failure){reason=failure.getMessage();}
            throw new IllegalStateException("Existing fluid authority could not be read: "+reason+" Refusing to replace its inventories: "+path);
        }catch(NoSuchFileException missing) {
            var created=factory.constructor().get();storage.set(DATA_NAME,created);created.setDirty();return created;
        }catch(IOException failure){throw new IllegalStateException("Cannot establish whether fluid authority exists",failure);}
    }
    private static byte[] digest(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
