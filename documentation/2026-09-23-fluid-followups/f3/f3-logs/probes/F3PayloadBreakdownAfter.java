package com.wormzjl.createcheme.runtime.fluid;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * F3 T1 probe, AFTER (format 4; not committed): the same islands as F3PayloadBreakdownProbe (the paced benchmark's
 * first six rest100 ladders certified, its first six stress100 ladders awake, the in-game rest line, a lone tank),
 * solved the same way, their format-4 island units broken down by section; plus each island's index row in the core
 * record and the world-level string and package tables. Output: build/reports/fluid/f3-payload-after.txt.
 */
class F3PayloadBreakdownAfter {
    final F3Fixtures f=new F3Fixtures();
    final StringBuilder out=new StringBuilder();
    void line(String s){System.out.println(s);out.append(s).append('\n');}
    static int gzip(byte[] bytes)throws IOException{var b=new ByteArrayOutputStream();try(var z=new GZIPOutputStream(b)){z.write(bytes);}return b.size();}
    record Row(String what,Map<String,Integer> sections,int total,int gzip,int nodes,int pipes) {}

    @Test void breakdown() throws Exception {
        var policy=CertificatePolicy.defaults();
        var rig=f.new Rig(policy,0);var random=new Random(2026091603L);
        var rest=new ArrayList<Long>();long nextDevice=1;
        for(int n=0;n<6;n++){var g=f.ladder(n,F3Fixtures.Ladder.CLOSED,random,nextDevice);nextDevice+=1000;long id=1000+n;rig.register(id,g);rest.add(id);}
        var random2=new Random(2026091603L);
        for(int n=0;n<6;n++){var g=f.ladder(n,F3Fixtures.Ladder.THROUGH,random2,nextDevice);nextDevice+=1000;rig.register(2000+n,g);}
        rig.register(3000,f.restLine(nextDevice,0));nextDevice+=10;rig.register(3001,f.loneTank(nextDevice));
        rig.runUntilCertified(rest,6_000);
        line(String.format(Locale.ROOT,"solved to tick %d, %d solves; rest certified %d/6",rig.epoch[0],rig.solves,rest.stream().filter(rig::certified).count()));
        var checkpoint=rig.checkpoint();
        var strings=new FluidCheckpointCodec.Strings();var rows=new ArrayList<Row>();
        for(var entry:checkpoint.islands()) {
            var sections=new LinkedHashMap<String,Integer>();var unit=FluidCheckpointCodec.islandUnit(entry,1,strings,sections);
            int sum=sections.values().stream().mapToInt(Integer::intValue).sum();if(sum!=unit.length)throw new AssertionError("sections "+sum+" != unit "+unit.length);
            long id=entry.snapshot().id();var s=entry.snapshot();
            String what=id>=3001?"lone tank":id==3000?"in-game rest line (N2)":id>=2000?"stress100 THROUGH ladder "+(id-2000):"rest100 CLOSED ladder "+(id-1000);
            what+=" ["+s.certificate().map(c->c.kind().name()).orElse("AWAKE")+"]";
            rows.add(new Row(what,sections,unit.length,gzip(unit),s.graph().reservoirs().size(),s.graph().pipes().size()));
            line(String.format(Locale.ROOT,"island %d %s: nodes %d pipes %d unit %d B (gzip %d)",id,what,s.graph().reservoirs().size(),s.graph().pipes().size(),unit.length,gzip(unit)));
        }
        // The index row: the core record of all islands less the core record of none, per island.
        var image=FluidCheckpointCodec.encode(checkpoint,rig.epoch[0],key->f.model);
        var empty=FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(),checkpoint.transfers()),rig.epoch[0],key->f.model);
        int n=checkpoint.islands().size();
        line(String.format(Locale.ROOT,"core record: %d bytes for %d islands, %d with none: %.1f bytes per index row; string table %d entries, %d bytes; package table %d bytes",
                image.core().sizeInBytes(),n,empty.core().sizeInBytes(),(image.core().sizeInBytes()-empty.core().sizeInBytes()-image.core().get("Strings").sizeInBytes()-image.core().get("Packages").sizeInBytes())/(double)n,
                ((net.minecraft.nbt.ListTag)image.core().get("Strings")).size(),image.core().get("Strings").sizeInBytes(),image.core().get("Packages").sizeInBytes()));
        line("");
        for(var group:List.of("rest100 CLOSED","stress100 THROUGH","in-game rest line","lone tank")) {
            var members=rows.stream().filter(r->r.what.startsWith(group)).toList();if(members.isEmpty())continue;
            line("## "+group+" ("+members.size()+" islands, mean per island)");
            line("| section | bytes | share |");line("|---|---|---|");
            var keys=new LinkedHashSet<String>();members.forEach(r->keys.addAll(r.sections.keySet()));
            double total=members.stream().mapToInt(Row::total).average().orElse(0);
            for(var k:keys){double v=members.stream().mapToInt(r->r.sections.getOrDefault(k,0)).average().orElse(0);line(String.format(Locale.ROOT,"| %s | %.0f | %.1f %% |",k,v,100*v/total));}
            line(String.format(Locale.ROOT,"| unit | %.0f | 100 %% |",total));
            line(String.format(Locale.ROOT,"| gzip of the unit | %.0f | |",members.stream().mapToInt(Row::gzip).average().orElse(0)));
            line(String.format(Locale.ROOT,"| nodes / pipes | %.1f / %.1f | |",members.stream().mapToInt(Row::nodes).average().orElse(0),members.stream().mapToInt(Row::pipes).average().orElse(0)));
            line("");
        }
        var file=Path.of("build/reports/fluid/f3-payload-after.txt");Files.createDirectories(file.getParent());Files.writeString(file,out.toString());
    }
}
