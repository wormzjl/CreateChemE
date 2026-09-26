package com.wormzjl.createcheme.runtime.fluid;

import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F3 T4 harness, AFTER (format 4; not committed; run with f3-harness.init.gradle: 4 GiB heap, scheduler verification
 * off). The same cases as F3SaveTimingBefore - 100 and 1,000 islands, certified (clones of the paced benchmark's first
 * 20 rest100 ladders, certified STEADY) and awake (clones of its first 20 stress100 ladders), each with the world
 * topology of its devices; a cold save (every unit written afresh), a warm save (the next save, same tick) and the
 * next cadence (120 ticks later; certified islands only materialise, awake ones solve) - through the world's real
 * layout in a temporary folder: FluidSavedData with a directory store, committed on the calling thread so the write is
 * timed. Server thread: capture and preparation (the changed units encoded, the core record built); IO thread: the new
 * pack(s) and the core record written atomically and flushed, unreferenced packs deleted; then a load of the files by
 * a fresh store. Output: build/reports/fluid/f3-save-after.txt.
 */
class F3SaveTimingAfter {
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
        line("| case | save | islands | capture ms | prepare ms | server thread ms | units encoded / reused / moved | topology encoded | island units MB | topology MB | IO write ms | pack MB written | core KB | files on disk MB | load ms | note |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for(int n:new int[]{100,1000}) {
            measure("certified",rest,restBuilt,n,rig.epoch[0],policy);
            measure("awake",through,throughBuilt,n,rig.epoch[0],policy);
        }
        var file=Path.of("build/reports/fluid/f3-save-after.txt");Files.createDirectories(file.getParent());Files.writeString(file,out.toString());
    }

    void measure(String kind,List<IslandCoordinator.Snapshot> templates,List<F3Fixtures.Built> built,int n,long epoch,CertificatePolicy policy) throws Exception {
        var rig=f.new Rig(policy,epoch);
        for(int i=0;i<n;i++)rig.coordinator.register(f.clone(templates.get(i%templates.size()),i+1,(i+1)*STRIDE,0,policy),f.model);
        long certified=rig.coordinator.observe().stream().filter(s->s.certificate().isPresent()).count();
        var world=F3Fixtures.topology(built,n,STRIDE,epoch);
        java.util.function.Supplier<WorldTopologyLedger.Snapshot> at=()->new WorldTopologyLedger.Snapshot(rig.epoch[0],world.nextIdentity(),world.active(),world.events(),world.constructed(),world.destroyed(),world.basis(),world.recoveries());
        var folder=Files.createTempDirectory("f3-after");
        var data=new FluidSavedData(rig.checkpoint(),at.get(),key->f.model,FluidCheckpointStore.directory(folder,Runnable::run,false));
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),at.get()));
        String name=n+" "+kind+" ("+certified+" certified, "+world.active().size()+" devices)";
        save(name,"cold",data,folder,true);
        save(name,"warm",data,folder,false);
        // 120 ticks: every awake island reaches its next interval (the rig dispatches at most 64 a tick).
        rig.run(120);
        save(name,"next cadence",data,folder,false);
        try(var walk=Files.walk(folder)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.gc();
    }
    void save(String name,String which,FluidSavedData data,Path folder,boolean cold) throws Exception {
        if(cold)data.clearPayloadCache();
        data.save(new CompoundTag(),null);var t=data.lastSave();var s=t.stats();
        long onDisk=0;try(var walk=Files.walk(folder)){for(var p:walk.filter(Files::isRegularFile).toList())onDisk+=Files.size(p);}
        long l0=System.nanoTime();var loaded=FluidSavedData.read(FluidCheckpointStore.directory(folder,Runnable::run,false),key->f.model).orElseThrow();long l1=System.nanoTime();
        assertEquals(t.islands(),loaded.checkpoint().islands().size());
        line(String.format(Locale.ROOT,"| %s | %s | %d | %.1f | %.1f | %.1f | %d / %d / %d | %s | %.2f | %.2f | %.1f | %.2f | %.1f | %.2f | %.1f | |",name,which,t.islands(),t.captureNanos()/1e6,t.encodeNanos()/1e6,t.totalNanos()/1e6,
                s.encoded(),s.reused(),s.copied(),s.topologyEncoded()?"yes":"no",s.islandBytes()/1e6,s.topologyBytes()/1e6,t.writeNanos()/1e6,s.packBytes()/1e6,s.coreBytes()/1e3,onDisk/1e6,(l1-l0)/1e6));
    }
}
