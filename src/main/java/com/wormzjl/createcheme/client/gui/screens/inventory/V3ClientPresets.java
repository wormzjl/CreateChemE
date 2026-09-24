package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Explicitly refreshed, client-owned JSON libraries. Neither custom paths nor files cross the wire. */
final class V3ClientPresets {
    static final int MAX_BYTES=65536,MAX_FILES=256;
    enum Kind {
        COLUMN("columnpreset","column"),MIXTURE("mixturepreset","mixture");
        final String folder,id;
        Kind(String folder,String id){this.folder=folder;this.id=id;}
    }
    sealed interface Preset permits Column,Mixture {String name();String translationKey();}
    record Column(String name,String translationKey,Map<String,String> fields) implements Preset {
        Column {fields=Map.copyOf(fields);}
    }
    record Mixture(String name,String translationKey,String packageId,boolean mass,Map<String,Double> amounts) implements Preset {
        Mixture {amounts=Collections.unmodifiableMap(new LinkedHashMap<>(amounts));}
    }
    record Entry(Preset preset,boolean bundled,String file){}
    record Problem(String file,String reason){}
    record Snapshot(List<Entry> entries,List<Problem> problems){
        Snapshot{entries=List.copyOf(entries);problems=List.copyOf(problems);}
    }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    private final Function<String,InputStream> resources;
    V3ClientPresets(Path gameDirectory,Function<String,InputStream> resources){
        root=gameDirectory.toAbsolutePath().normalize().resolve("CreatChemE");this.resources=resources;
    }
    Path directory(Kind kind){return root.resolve(kind.folder);}
    Snapshot refresh(Kind kind){
        List<Entry> entries=new ArrayList<>();List<Problem> problems=new ArrayList<>();
        String prefix="assets/createcheme/"+kind.folder+"/";
        try(InputStream stream=resources.apply(prefix+"index.json")){
            if(stream==null)throw new IOException("Missing bundled index");
            var index=JsonParser.parseString(read(stream)).getAsJsonArray();
            if(index.size()>MAX_FILES)throw new IOException("Too many bundled files");
            for(var item:index){
                String file=item.getAsString();
                if(!file.matches("[a-z0-9_-]+\\.json"))throw new IOException("Invalid bundled file");
                try(InputStream resource=resources.apply(prefix+file)){
                    if(resource==null)throw new IOException("Missing bundled file");
                    entries.add(new Entry(decode(read(resource),kind),true,file));
                }catch(RuntimeException|IOException e){problems.add(new Problem(file,e.getMessage()));}
            }
        }catch(RuntimeException|IOException e){problems.add(new Problem("index.json",e.getMessage()));}
        Path directory=directory(kind);
        try{Files.createDirectories(directory);}catch(IOException e){problems.add(new Problem(directory.toString(),e.getMessage()));}
        if(Files.exists(directory)){
            try(var stream=Files.list(directory)){
                var files=stream.filter(p->p.getFileName().toString().endsWith(".json")).sorted().limit(MAX_FILES+1L).toList();
                if(files.size()>MAX_FILES)problems.add(new Problem(directory.getFileName().toString(),"Too many custom files"));
                for(Path path:files.stream().limit(MAX_FILES).toList()){
                    String file=path.getFileName().toString();
                    try{
                        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_BYTES)throw new IOException("Invalid preset file");
                        try(InputStream input=Files.newInputStream(path)){entries.add(new Entry(decode(read(input),kind),false,file));}
                    }catch(RuntimeException|IOException e){problems.add(new Problem(file,e.getMessage()));}
                }
            }catch(IOException e){problems.add(new Problem(directory.toString(),e.getMessage()));}
        }
        return new Snapshot(entries,problems);
    }
    Path save(Kind kind,String name,String encoded)throws IOException{
        decode(encoded,kind);
        if(name==null||name.isBlank()||name.length()>64||!Character.isLetterOrDigit(name.codePointAt(0))||!name.codePoints().allMatch(c->Character.isLetterOrDigit(c)||c==32||c==95||c==45))throw new IllegalArgumentException("Invalid preset filename");
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Preset exceeds size limit");
        Path directory=directory(kind);Files.createDirectories(directory);
        String stem=name.strip().replace(' ','_');
        for(int i=0;i<10000;i++){
            Path path=directory.resolve(stem+(i==0?"":"_"+i)+".json").normalize();
            if(!path.getParent().equals(directory))throw new IOException("Invalid preset path");
            try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);return path;}
            catch(FileAlreadyExistsException exists){/* Preserve an existing user file. */}
        }
        throw new IOException("No available preset filename");
    }
    private static String read(InputStream stream)throws IOException{
        byte[] bytes=stream.readNBytes(MAX_BYTES+1);
        if(bytes.length>MAX_BYTES)throw new IOException("Preset exceeds size limit");
        return new String(bytes,StandardCharsets.UTF_8);
    }
    static Preset decode(String json,Kind kind){
        try{
            JsonObject o=JsonParser.parseString(json).getAsJsonObject();
            if(integer(o,"schema_version")!=1||!string(o,"kind").equals(kind.id))throw new IllegalArgumentException("Unsupported preset schema");
            String name=string(o,"name"),key=string(o,"translation_key");
            if(name.isBlank()||name.length()>128||key.length()>128)throw new IllegalArgumentException("Invalid preset name");
            if(kind==Kind.MIXTURE){
                String pkg=string(o,"package"),basis=string(o,"basis");
                if(!pkg.matches("[a-z][a-z0-9_.:-]{0,127}")||!Set.of("mole","mass").contains(basis))throw new IllegalArgumentException("Invalid mixture basis");
                Map<String,Double> amounts=new LinkedHashMap<>();double total=0;
                for(var element:array(o,"components",64)){
                    var c=element.getAsJsonObject();String id=string(c,"id");double value=number(c,"amount");
                    if(!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")||value<0||amounts.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("Invalid component amount");
                    total+=value;
                }
                if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("Empty mixture");
                return new Mixture(name,key,pkg,basis.equals("mass"),amounts);
            }
            Map<String,String> f=new LinkedHashMap<>();
            String[] names={"feed_flow_kmol_h","feed_temperature_kelvin","tray_count","feed_tray","top_temperature_kelvin",
                "reboiler_duty_mw","reflux_ratio","top_pressure_bar","nominal_dp","diameter_m"};
            for(int i=0;i<10;i++){
                double value=i==8?0:number(o,names[i]);if(i==1||i==4)value-=273.15;
                if(i==2||i==3)f.put("s"+i,Integer.toString(integer(o,names[i])));else f.put("s"+i,Double.toString(value));
            }
            int total=integer(o,"tray_count"),feed=integer(o,"feed_tray");
            if(total<2||total>66||feed<2||feed>total)throw new IllegalArgumentException("Invalid public tray numbering");
            for(int i=0;i<3;i++){f.put("d"+i+"stage","");f.put("d"+i+"rate","");}
            for(int i=0;i<2;i++){f.put("t"+i+"stage","");f.put("t"+i+"rate","");f.put("t"+i+"T","");}
            for(int i=0;i<4;i++){f.put("c"+i+"draw","");f.put("c"+i+"return","");f.put("c"+i+"duty","");f.put("c"+i+"split","UNIFORM");}
            int i=0;
            for(var element:array(o,"draws",3)){
                var d=element.getAsJsonObject();int at=integer(d,"tray");
                if(at<2||at>=total)throw new IllegalArgumentException("Draw must be on an interior tray");
                f.put("d"+i+"stage",Integer.toString(at));f.put("d"+i+"rate",Double.toString(number(d,"flow_kmol_h")));i++;
            }
            i=0;
            for(var element:array(o,"steam",2)){
                var t=element.getAsJsonObject();int at=integer(t,"tray");
                if(at<2||at>total)throw new IllegalArgumentException("Steam must enter below tray 1");
                f.put("t"+i+"stage",Integer.toString(at));f.put("t"+i+"rate",Double.toString(number(t,"flow_kmol_h")));
                f.put("t"+i+"T",Double.toString(number(t,"temperature_kelvin")-273.15));i++;
            }
            i=0;
            for(var element:array(o,"pumparounds",4)){
                var c=element.getAsJsonObject();int draw=integer(c,"draw_tray"),ret=integer(c,"return_tray");
                if(ret<2||draw<=ret||draw>=total)throw new IllegalArgumentException("Invalid PA trays");
                String split=string(c,"split");V3PumparoundSpec.Split.valueOf(split);
                f.put("c"+i+"draw",Integer.toString(draw));f.put("c"+i+"return",Integer.toString(ret));
                f.put("c"+i+"duty",Double.toString(number(c,"cooling_mw")));f.put("c"+i+"split",split);i++;
            }
            return new Column(name,key,f);
        }catch(IllegalStateException|NullPointerException|ClassCastException e){throw new IllegalArgumentException("Invalid preset JSON",e);}
    }
    static String mixtureJson(String name,V3CompositionDraft composition){
        JsonObject o=header(name,Kind.MIXTURE);o.addProperty("package",composition.source().packageId());
        o.addProperty("basis",composition.mass()?"mass":"mole");JsonArray rows=new JsonArray();
        composition.relativeAmounts().forEach((id,amount)->{var c=new JsonObject();c.addProperty("id",id);c.addProperty("amount",amount);rows.add(c);});
        o.add("components",rows);return JSON.toJson(o);
    }
    static String columnJson(String name,V3ColumnInput input){
        JsonObject o=header(name,Kind.COLUMN);
        o.addProperty("tray_count",input.stageCount()+2);o.addProperty("feed_tray",input.feedStageNumber()+1);
        o.addProperty("feed_flow_kmol_h",Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum()*3.6);
        o.addProperty("feed_temperature_kelvin",input.feedTemperatureKelvin());
        o.addProperty("top_temperature_kelvin",V3EditorDraft.value(input,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE));
        o.addProperty("reboiler_duty_mw",V3EditorDraft.value(input,V3ControlledQuantity.REBOILER_DUTY)/1e6);
        o.addProperty("reflux_ratio",V3EditorDraft.value(input,V3ControlledQuantity.ORGANIC_REFLUX_RATIO));
        o.addProperty("top_pressure_bar",input.topPressurePascal()/1e5);o.addProperty("diameter_m",input.columnDiameterMetres());
        JsonArray draws=new JsonArray(),steam=new JsonArray(),pas=new JsonArray();
        for(var d:input.sideDraws()){var v=new JsonObject();v.addProperty("tray",d.trayNumber()+1);v.addProperty("flow_kmol_h",d.molarFlowMolPerSecond()*3.6);draws.add(v);}
        for(var t:input.steamFeeds()){var v=new JsonObject();v.addProperty("tray",t.stageNumber()+1);v.addProperty("flow_kmol_h",t.molarFlowMolPerSecond()*3.6);v.addProperty("temperature_kelvin",t.temperatureKelvin());steam.add(v);}
        for(var c:input.pumparounds()){var v=new JsonObject();v.addProperty("draw_tray",c.drawTray()+1);v.addProperty("return_tray",c.returnTray()+1);v.addProperty("cooling_mw",-c.dutyWatts()/1e6);v.addProperty("split",c.split().name());pas.add(v);}
        o.add("draws",draws);o.add("steam",steam);o.add("pumparounds",pas);return JSON.toJson(o);
    }
    private static JsonObject header(String name,Kind kind){
        var o=new JsonObject();o.addProperty("schema_version",1);o.addProperty("kind",kind.id);o.addProperty("name",name);o.addProperty("translation_key","");return o;
    }
    private static String string(JsonObject o,String key){
        var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string: "+key);
        return v.getAsString();
    }
    private static double number(JsonObject o,String key){
        var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!Double.isFinite(v.getAsDouble()))throw new IllegalArgumentException("Invalid number: "+key);
        return v.getAsDouble();
    }
    private static int integer(JsonObject o,String key){double n=number(o,key);if(n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid integer: "+key);return (int)n;}
    private static JsonArray array(JsonObject o,String key,int max){var a=o.getAsJsonArray(key);if(a==null||a.size()>max)throw new IllegalArgumentException("Invalid list: "+key);return a;}
}
