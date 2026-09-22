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

/** One committed core checkpoint in overworld SavedData, independent of chunk residency. */
public final class FluidSavedData extends SavedData {
    public static final int TOPOLOGY_VERSION=3;
    public static final String DATA_NAME="createcheme_fluid_core";
    private final Thread owner=Thread.currentThread();
    private final Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models;
    private FluidCheckpointCodec.Checkpoint checkpoint;
    private Optional<WorldTopologyLedger.Snapshot> world=Optional.empty();
    private byte[] encoded;
    private byte[] encodedWorld;
    public record Capture(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world) {}
    private java.util.function.Supplier<Capture> capture;

    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        this.checkpoint=Objects.requireNonNull(checkpoint);this.models=Objects.requireNonNull(models);
    }
    public FluidSavedData(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        this(checkpoint,models);this.world=Optional.of(Objects.requireNonNull(world));
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Fluid SavedData must be captured on its server thread");}
    public FluidCheckpointCodec.Checkpoint checkpoint(){owned();return checkpoint;}
    /** Empty identifies an older core-only checkpoint, whose inventories remain fully preserved. */
    public Optional<WorldTopologyLedger.Snapshot> world(){owned();return world;}
    /** Snapshot live authority only at the server's save boundary, before its asynchronous file write. */
    public void bindCapture(java.util.function.Supplier<Capture> capture){owned();this.capture=Objects.requireNonNull(capture);}
    public void replace(FluidCheckpointCodec.Checkpoint next){owned();checkpoint=Objects.requireNonNull(next);encoded=null;setDirty();}
    public void replace(FluidCheckpointCodec.Checkpoint next,WorldTopologyLedger.Snapshot nextWorld) {
        owned();Objects.requireNonNull(next);Objects.requireNonNull(nextWorld);checkpoint=next;world=Optional.of(nextWorld);encoded=null;encodedWorld=null;setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        owned();if(capture!=null){var current=capture.get();replace(current.checkpoint,current.world);}
        owned();if(encoded==null)encoded=FluidCheckpointCodec.encode(checkpoint,models).getBytes(StandardCharsets.UTF_8);
        tag.putInt("FluidFormat",FluidCheckpointCodec.VERSION);tag.putByteArray("Checkpoint",encoded.clone());tag.putByteArray("SHA256",digest(encoded));
        // Additive extension: retain the existing core format and its load path unchanged.
        if(world.isPresent()) {
            if(encodedWorld==null)encodedWorld=FluidCheckpointCodec.encodeWorld(world.orElseThrow()).getBytes(StandardCharsets.UTF_8);
            tag.putInt("TopologyFormat",TOPOLOGY_VERSION);tag.putByteArray("Topology",encodedWorld.clone());tag.putByteArray("TopologySHA256",digest(encodedWorld));
        } else {tag.remove("TopologyFormat");tag.remove("Topology");tag.remove("TopologySHA256");}
        return tag;
    }
    public static FluidSavedData load(CompoundTag tag,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        if(!tag.contains("FluidFormat",Tag.TAG_INT)||(tag.getInt("FluidFormat")!=1&&tag.getInt("FluidFormat")!=FluidCheckpointCodec.VERSION)
                ||!tag.contains("Checkpoint",Tag.TAG_BYTE_ARRAY)||!tag.contains("SHA256",Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Unsupported/incomplete fluid save");
        byte[] bytes=tag.getByteArray("Checkpoint");
        if(!MessageDigest.isEqual(digest(bytes),tag.getByteArray("SHA256")))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch");
        var data=new FluidSavedData(FluidCheckpointCodec.decode(new String(bytes,StandardCharsets.UTF_8),models),models);data.encoded=null;
        if(tag.contains("Topology")||tag.contains("TopologyFormat")||tag.contains("TopologySHA256")) {
            if(!tag.contains("TopologyFormat",Tag.TAG_INT)||(tag.getInt("TopologyFormat")!=2&&tag.getInt("TopologyFormat")!=TOPOLOGY_VERSION)||!tag.contains("Topology",Tag.TAG_BYTE_ARRAY)||!tag.contains("TopologySHA256",Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Unsupported/incomplete fluid topology basis; use a fresh development world or explicitly reset its fluid data");
            byte[] worldBytes=tag.getByteArray("Topology");if(!MessageDigest.isEqual(digest(worldBytes),tag.getByteArray("TopologySHA256")))throw new IllegalArgumentException("Topology checkpoint checksum mismatch");
            data.world=Optional.of(FluidCheckpointCodec.decodeWorld(new String(worldBytes,StandardCharsets.UTF_8),tag.getInt("TopologyFormat")==2));data.encodedWorld=tag.getInt("TopologyFormat")==TOPOLOGY_VERSION?worldBytes.clone():null;
        }
        return data;
    }
    /** Prepare an explicit reference migration without mutating the input tag or writing a file.
     * The caller can review the result and publish it through the normal atomic SavedData writer. */
    public static FluidSavedData migrateToSensibleReference(CompoundTag tag,com.wormzjl.createcheme.science.fluid.state.EnergyReference previous,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        if(!tag.contains("FluidFormat",Tag.TAG_INT)||(tag.getInt("FluidFormat")!=1&&tag.getInt("FluidFormat")!=FluidCheckpointCodec.VERSION)||!tag.contains("Checkpoint",Tag.TAG_BYTE_ARRAY)||!tag.contains("SHA256",Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Unsupported/incomplete fluid save");
        var bytes=tag.getByteArray("Checkpoint");if(!MessageDigest.isEqual(digest(bytes),tag.getByteArray("SHA256")))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch");
        var checkpoint=FluidCheckpointCodec.migrateToSensibleReference(new String(bytes,StandardCharsets.UTF_8),previous,models);
        var copy=tag.copy();copy.putInt("FluidFormat",FluidCheckpointCodec.VERSION);var next=FluidCheckpointCodec.encode(checkpoint,models).getBytes(StandardCharsets.UTF_8);copy.putByteArray("Checkpoint",next);copy.putByteArray("SHA256",digest(next));
        // Validate every existing extension before changing its historical accounting datum.
        var result=load(copy,models);
        if(result.world.isPresent()) {
            var old=result.world.orElseThrow();var target=com.wormzjl.createcheme.science.fluid.state.EnergyReference.sensible(previous.components());
            var constructed=new WorldTopologyLedger.MaterialTotal(old.constructed().moles(),previous.rebase(old.constructed().totalEnergy(),old.constructed().moles(),target),old.constructed().solidMasses());
            var destroyed=new WorldTopologyLedger.MaterialTotal(old.destroyed().moles(),previous.rebase(old.destroyed().totalEnergy(),old.destroyed().moles(),target),old.destroyed().solidMasses());
            result.replace(checkpoint,new WorldTopologyLedger.Snapshot(old.onlineTick(),old.nextIdentity(),old.active(),old.events(),constructed,destroyed,old.basis(),old.recoveries()));
        }
        return result;
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
            throw new IllegalStateException("Existing fluid authority could not be read; refusing to replace its inventories: "+path);
        }catch(NoSuchFileException missing) {
            var created=factory.constructor().get();storage.set(DATA_NAME,created);created.setDirty();return created;
        }catch(IOException failure){throw new IllegalStateException("Cannot establish whether fluid authority exists",failure);}
    }
    private static byte[] digest(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
