package com.wormzjl.createcheme.runtime.fluid;

import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F3 T4 harness, BEFORE (format 3 at b86b147; not committed; run with f3-harness.init.gradle: 4 GiB heap, scheduler
 * verification off). Save time and bytes of the world's saved data for 100 and 1,000 islands, certified (clones of the
 * paced benchmark's first 20 rest100 ladders, certified STEADY) and awake (clones of its first 20 stress100 ladders),
 * each with the world topology of its devices: a cold save (payload cache cleared), a warm save (the next save, same
 * tick) and the next cadence (120 ticks later; certified islands only materialise, awake ones solve). Server thread:
 * capture + encode as FluidSavedData.lastSave() reports it, plus the deep copy NeoForge's SavedData.save(File) makes of
 * the tag; IO thread: gzip and atomic write of that copy (IOUtilities.writeNbtCompressed), then the load of the file.
 * Output: build/reports/fluid/f3-save-before.txt.
 */
class F3SaveTimingBefore {
    static final long STRIDE=100_000;
    final F3Fixtures f=new F3Fixtures();
    final StringBuilder out=new StringBuilder();
    void line(String s){System.out.println(s);out.append(s).append('\n');}

    @Test void timings() throws Exception {
        assertFalse(FluidCheckpointCodec.verifying(),"run with f3-harness.init.gradle: verification off");
        var policy=CertificatePolicy.defaults();
        var rig=f.new Rig(policy,0);
        var restBuilt=new ArrayList<F3Fixtures.Built>();var throughBuilt=new ArrayList<F3Fixtures.Built>();
        var random=new Random(2026091603L);
        for(int n=0;n<20;n++){var b=f.build(n,F3Fixtures.Ladder.CLOSED,random,1+n*1000L);restBuilt.add(b);rig.register(1000+n,b.graph());}
        var random2=new Random(2026091603L);
        for(int n=0;n<20;n++){var b=f.build(n,F3Fixtures.Ladder.THROUGH,random2,50_001+n*1000L);throughBuilt.add(b);rig.register(2000+n,b.graph());}
        long started=System.nanoTime();
        var restIds=java.util.stream.LongStream.range(1000,1020).boxed().toList();
        rig.runUntilCertified(restIds,6_000);
        for(long id:restIds)assertTrue(rig.certified(id),"template "+id+" certified");
        line(String.format(Locale.ROOT,"templates solved to tick %d in %.1f s (%d solves); through certified %d/20",rig.epoch[0],(System.nanoTime()-started)/1e9,rig.solves,
                java.util.stream.LongStream.range(2000,2020).filter(rig::certified).count()));
        var snapshots=new HashMap<Long,IslandCoordinator.Snapshot>();for(var s:rig.coordinator.snapshots())snapshots.put(s.id(),s);
        var rest=restIds.stream().map(snapshots::get).toList();
        var through=java.util.stream.LongStream.range(2000,2020).mapToObj(snapshots::get).toList();
        line("| case | save | islands | capture ms | encode ms | tag copy ms | server thread ms | payloads encoded / copied | topology encoded | payload MB | topology MB | IO gzip+write ms | file MB | load ms | note |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for(int n:new int[]{100,1000}) {
            measure("certified",rest,restBuilt,n,rig.epoch[0],policy);
            measure("awake",through,throughBuilt,n,rig.epoch[0],policy);
        }
        var file=Path.of("build/reports/fluid/f3-save-before.txt");Files.createDirectories(file.getParent());Files.writeString(file,out.toString());
    }

    void measure(String kind,List<IslandCoordinator.Snapshot> templates,List<F3Fixtures.Built> built,int n,long epoch,CertificatePolicy policy) throws Exception {
        var rig=f.new Rig(policy,epoch);
        for(int i=0;i<n;i++)rig.coordinator.register(f.clone(templates.get(i%templates.size()),i+1,(i+1)*STRIDE,0,policy),f.model);
        long certified=rig.coordinator.observe().stream().filter(s->s.certificate().isPresent()).count();
        var world=F3Fixtures.topology(built,n,STRIDE,epoch);
        java.util.function.Supplier<WorldTopologyLedger.Snapshot> at=()->new WorldTopologyLedger.Snapshot(rig.epoch[0],world.nextIdentity(),world.active(),world.events(),world.constructed(),world.destroyed(),world.basis(),world.recoveries());
        var data=new FluidSavedData(rig.checkpoint(),at.get(),key->f.model);
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),at.get()));
        String name=n+" "+kind+" ("+certified+" certified, "+world.active().size()+" devices)";
        save(name,"cold",data,true);
        save(name,"warm",data,false);
        // 120 ticks: every awake island reaches its next interval (the rig dispatches at most 64 a tick).
        rig.run(120);
        save(name,"next cadence",data,false);
        System.gc();
    }
    void save(String name,String which,FluidSavedData data,boolean cold) throws Exception {
        if(cold)data.clearPayloadCache();
        CompoundTag tag;
        try{tag=data.save(new CompoundTag(),null);}
        catch(RuntimeException refused) {
            line(String.format(Locale.ROOT,"| %s | %s | - | - | - | - | - | - | - | - | - | - | - | - | refused: %s |",name,which,refused.getMessage()));return;
        }
        var t=data.lastSave();
        // NeoForge's SavedData.save(File): wrap, then a deep copy on the server thread; gzip and atomic write on the IO thread.
        long c0=System.nanoTime();var root=new CompoundTag();root.put("data",tag);var copy=root.copy();long c1=System.nanoTime();
        var dir=Files.createTempDirectory("f3-before");var path=dir.resolve("createcheme_fluid_core.dat");
        long w0=System.nanoTime();net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed(copy,path);long w1=System.nanoTime();
        long fileBytes=Files.size(path);
        long l0=System.nanoTime();FluidSavedData.load(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()).getCompound("data"),key->f.model);long l1=System.nanoTime();
        Files.delete(path);Files.delete(dir);
        line(String.format(Locale.ROOT,"| %s | %s | %d | %.1f | %.1f | %.1f | %.1f | %d / %d | %s | %.2f | %.2f | %.1f | %.2f | %.1f | |",name,which,t.islands(),t.captureNanos()/1e6,t.encodeNanos()/1e6,(c1-c0)/1e6,
                (t.totalNanos()+(c1-c0))/1e6,t.payloadsEncoded(),t.payloadsReused(),t.topologyEncoded()?"yes":"no",t.payloadBytes()/1e6,t.topologyBytes()/1e6,(w1-w0)/1e6,fileBytes/1e6,(l1-l0)/1e6));
    }
}
