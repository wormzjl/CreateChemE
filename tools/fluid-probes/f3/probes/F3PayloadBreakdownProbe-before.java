package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.*;
import net.minecraft.nbt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * F3 T1 probe, BEFORE (format 3 at b86b147; not committed): what one island's checkpoint payload holds, section by
 * section, in bytes of its JSON (UTF-8, compact as written), for the paced benchmark's rest100 ladders (certified)
 * and stress100 ladders (awake, through-flow), the in-game rest line and a lone tank. Also the island's NBT envelope
 * (the island compound without its payload) and gzip of the payload. Output: build/reports/fluid/f3-payload-before.txt.
 */
class F3PayloadBreakdownProbe {
    final F3Fixtures f=new F3Fixtures();
    final StringBuilder out=new StringBuilder();
    void line(String s){System.out.println(s);out.append(s).append('\n');}

    static int size(JsonElement e){return e==null?4:e.toString().getBytes(StandardCharsets.UTF_8).length;}
    /** Bytes a member takes in its object: "name":value plus one separator. */
    static int member(JsonObject o,String name){return name.length()+3+1+size(o.get(name));}
    static int gzip(byte[] bytes)throws IOException{var b=new ByteArrayOutputStream();try(var z=new GZIPOutputStream(b)){z.write(bytes);}return b.size();}

    record Row(String what,Map<String,Long> sections,long total,long gzip,long envelope,int nodes,int pipes) {}

    Map<String,Long> breakdown(JsonObject p) {
        var s=new LinkedHashMap<String,Long>();
        s.put("identity (dimension, package, compressibility)",(long)(member(p,"dimension")+member(p,"packageId")+member(p,"compressibility")));
        s.put("propertyRevision",(long)member(p,"propertyRevision"));
        s.put("energy reference",(long)member(p,"reference"));
        graph(s,"graph",p.getAsJsonObject("graph"));
        s.put("allowance",(long)member(p,"allowance"));
        if(p.get("anchor").isJsonNull())s.put("anchor (null)",(long)member(p,"anchor"));
        else {
            var a=p.getAsJsonObject("anchor");
            s.put("anchor.revision",(long)member(a,"revision"));
            graph(s,"anchor.graph",a.getAsJsonObject("graph"));
            s.put("anchor.modes",(long)member(a,"modes")+"\"anchor\":{}".length());
        }
        if(p.get("history").isJsonNull())s.put("history (null)",(long)member(p,"history"));
        else {
            var h=p.getAsJsonObject("history");
            s.put("history.scalars (seconds, accepted, rejected, pumpWork, acceptance)",(long)(member(h,"seconds")+member(h,"accepted")+member(h,"rejected")+member(h,"pumpWork")+member(h,"acceptance")+"\"history\":{}".length()));
            s.put("history.flows",(long)member(h,"flows"));s.put("history.heads",(long)member(h,"heads"));s.put("history.modes",(long)member(h,"modes"));
            s.put("history.boundaries",(long)member(h,"boundaries"));s.put("history.rejectionReasons",(long)member(h,"rejectionReasons"));
            s.put("history.pipes (pipe transfers)",(long)member(h,"pipes"));
        }
        s.put("status",(long)member(p,"status"));s.put("fences",(long)member(p,"fences"));
        if(p.get("certifiedFrom").isJsonNull())s.put("certifiedFrom (null)",(long)member(p,"certifiedFrom"));
        else graph(s,"certifiedFrom",p.getAsJsonObject("certifiedFrom"));
        return s;
    }
    void graph(Map<String,Long> s,String name,JsonObject g) {
        long identity=0,inventory=0,phase=0,pipeIdentity=0,pipeState=0;
        for(var n:g.getAsJsonArray("nodes")) {
            var o=n.getAsJsonObject();var inv=o.getAsJsonObject("inventory");
            identity+=member(o,"id")+member(o,"elevation")+member(o,"kind")+member(inv,"volume")+2;
            inventory+=member(inv,"moles")+member(inv,"internalEnergy")+member(inv,"solids")+"\"inventory\":{}".length();
            phase+=member(o,"phase");
        }
        for(var e:g.getAsJsonArray("pipes")) {
            var o=e.getAsJsonObject();
            pipeIdentity+=member(o,"id")+member(o,"first")+member(o,"second")+member(o,"sections")+member(o,"control")+2;
            pipeState+=member(o,"blockedDirections")+member(o,"filter");
        }
        s.put(name+".nodes identity (id, elevation, kind, volume)",identity);
        s.put(name+".nodes inventory (moles, energy, solids)",inventory);
        s.put(name+".nodes phase (T, P, liquid, vapor, water, hc pressure)",phase);
        s.put(name+".pipes identity (id, ends, sections, control)",pipeIdentity);
        s.put(name+".pipes state (blocked mask, filter)",pipeState);
    }

    @Test void breakdown() throws Exception {
        var policy=CertificatePolicy.defaults();
        var rig=f.new Rig(policy,0);var random=new Random(2026091603L);
        var rest=new ArrayList<Long>();var through=new ArrayList<Long>();
        long nextDevice=1;
        // rest100: the benchmark's first six CLOSED ladders (10, 23, 15, 28, 20, 13 reservoirs), one shared random sequence.
        for(int n=0;n<6;n++){var g=f.ladder(n,F3Fixtures.Ladder.CLOSED,random,nextDevice);nextDevice+=1000;long id=1000+n;rig.register(id,g);rest.add(id);}
        var random2=new Random(2026091603L);
        for(int n=0;n<6;n++){var g=f.ladder(n,F3Fixtures.Ladder.THROUGH,random2,nextDevice);nextDevice+=1000;long id=2000+n;rig.register(id,g);through.add(id);}
        rig.register(3000,f.restLine(nextDevice,0));nextDevice+=10;rig.register(3001,f.loneTank(nextDevice));nextDevice+=10;
        long started=System.nanoTime();
        rig.runUntilCertified(rest,6_000);
        var certifiedRest=rest.stream().filter(rig::certified).toList();
        line(String.format(Locale.ROOT,"solved to tick %d in %.1f s, %d solves; rest certified %d/%d; through certified %d/%d; rest line %s; lone tank %s",
                rig.epoch[0],(System.nanoTime()-started)/1e9,rig.solves,certifiedRest.size(),rest.size(),through.stream().filter(rig::certified).count(),through.size(),rig.certified(3000),rig.certified(3001)));
        var checkpoint=rig.checkpoint();
        var tag=FluidCheckpointCodec.encode(checkpoint,rig.epoch[0],key->f.model);
        var list=(ListTag)tag.get("Islands");
        var rows=new ArrayList<Row>();
        for(int i=0;i<list.size();i++) {
            var island=list.getCompound(i).copy();long id=island.getLong("Id");
            byte[] payload=island.getByteArray("Payload");island.remove("Payload");
            var envelope=new ByteArrayOutputStream();NbtIo.write(island,new DataOutputStream(envelope));
            var json=JsonParser.parseString(new String(payload,StandardCharsets.UTF_8)).getAsJsonObject();
            var s=breakdown(json);
            var snapshot=checkpoint.islands().stream().filter(e->e.snapshot().id()==id).findFirst().orElseThrow().snapshot();
            String what=id>=3001?"lone tank":id==3000?"in-game rest line (N2)":id>=2000?"stress100 THROUGH ladder "+(id-2000):"rest100 CLOSED ladder "+(id-1000);
            what+=" ["+snapshot.certificate().map(c->c.kind().name()).orElse("AWAKE")+"]";
            rows.add(new Row(what,s,payload.length,gzip(payload),envelope.size(),snapshot.graph().reservoirs().size(),snapshot.graph().pipes().size()));
            // identity sharing facts
            boolean anchorSame=!json.get("anchor").isJsonNull()&&json.getAsJsonObject("anchor").get("graph").equals(json.get("graph"));
            boolean fromSameTopology=false;
            if(!json.get("certifiedFrom").isJsonNull()) {
                var a=json.getAsJsonObject("graph");var b=json.getAsJsonObject("certifiedFrom");
                fromSameTopology=topology(a).equals(topology(b));
            }
            boolean anchorSameTopology=!json.get("anchor").isJsonNull()&&topology(json.getAsJsonObject("graph")).equals(topology(json.getAsJsonObject("anchor").getAsJsonObject("graph")));
            line(String.format(Locale.ROOT,"island %d %s: nodes %d pipes %d payload %d B (gzip %d) envelope %d B; anchor graph == graph: %s; anchor topology == graph topology: %s; certifiedFrom topology == graph topology: %s",
                    id,what,snapshot.graph().reservoirs().size(),snapshot.graph().pipes().size(),payload.length,gzip(payload),envelope.size(),anchorSame,anchorSameTopology,fromSameTopology));
        }
        line("");
        for(var group:List.of("rest100 CLOSED","stress100 THROUGH","in-game rest line","lone tank")) {
            var members=rows.stream().filter(r->r.what.startsWith(group)).toList();if(members.isEmpty())continue;
            line("## "+group+" ("+members.size()+" islands, mean per island)");
            var keys=new LinkedHashSet<String>();members.forEach(r->keys.addAll(r.sections.keySet()));
            double total=members.stream().mapToLong(Row::total).average().orElse(0);
            line(String.format(Locale.ROOT,"| section | bytes | share |"));line("|---|---|---|");
            long sum=0;
            for(var k:keys){double v=members.stream().mapToLong(r->r.sections.getOrDefault(k,0L)).average().orElse(0);sum+=Math.round(v);line(String.format(Locale.ROOT,"| %s | %.0f | %.1f %% |",k,v,100*v/total));}
            line(String.format(Locale.ROOT,"| sum of sections | %d | |",sum));
            line(String.format(Locale.ROOT,"| payload (JSON as written) | %.0f | 100 %% |",total));
            line(String.format(Locale.ROOT,"| gzip of the payload | %.0f | |",members.stream().mapToLong(Row::gzip).average().orElse(0)));
            line(String.format(Locale.ROOT,"| NBT island envelope (scalars, certificate strings, digest) | %.0f | |",members.stream().mapToLong(Row::envelope).average().orElse(0)));
            line(String.format(Locale.ROOT,"| nodes / pipes | %.1f / %.1f | |",members.stream().mapToInt(Row::nodes).average().orElse(0),members.stream().mapToInt(Row::pipes).average().orElse(0)));
            line("");
        }
        var file=Path.of("build/reports/fluid/f3-payload-before.txt");Files.createDirectories(file.getParent());Files.writeString(file,out.toString());
        // keep the solved fixture for the save-time harness (before and after read the same islands)
        var bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,bytes);Files.write(Path.of("build/reports/fluid/f3-fixture-format3.dat"),bytes.toByteArray());
    }
    static JsonArray topology(JsonObject graph) {
        var t=new JsonArray();
        for(var n:graph.getAsJsonArray("nodes")){var o=n.getAsJsonObject();var x=new JsonArray();x.add(o.get("id"));x.add(o.get("elevation"));x.add(o.get("kind"));x.add(o.getAsJsonObject("inventory").get("volume"));t.add(x);}
        for(var e:graph.getAsJsonArray("pipes")){var o=e.getAsJsonObject();var x=new JsonArray();for(var k:List.of("id","first","second","sections","control"))x.add(o.get(k));t.add(x);}
        return t;
    }
}
