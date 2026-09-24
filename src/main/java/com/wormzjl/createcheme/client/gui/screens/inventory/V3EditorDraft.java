package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.*;
import java.util.*;

/** Widget-independent draft; one-decimal displays never round untouched physical inputs. */
final class V3EditorDraft {
    private final V3ColumnInput base;
    private final Map<String,String> original=new LinkedHashMap<>(),fields=new LinkedHashMap<>();
    private final Map<String,Double> exact=new HashMap<>();
    private V3CompositionDraft composition;

    V3EditorDraft(V3ColumnInput base){this(base,null);}
    V3EditorDraft(V3ColumnInput base,List<Double> weights){
        this.base=base;
        double[] scalars={Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum()*3.6,
            base.feedTemperatureKelvin()-273.15,base.stageCount(),base.feedStageNumber(),
            value(base,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)-273.15,
            value(base,V3ControlledQuantity.REBOILER_DUTY)/1e6,value(base,V3ControlledQuantity.ORGANIC_REFLUX_RATIO),
            base.topPressurePascal()/1e5,base.stagePressureDropPascal()/1000,base.columnDiameterMetres()};
        for(int i=0;i<scalars.length;i++)putNumber("s"+i,scalars[i],i==2||i==3);
        for(int i=0;i<3;i++){
            var d=i<base.sideDraws().size()?base.sideDraws().get(i):null;
            if(d==null){put("d"+i+"stage","");put("d"+i+"rate","");}
            else{putNumber("d"+i+"stage",d.trayNumber(),true);putNumber("d"+i+"rate",d.molarFlowMolPerSecond()*3.6,false);}
        }
        var sump=base.steamFeeds().stream().filter(s->s.stageNumber()==base.stageCount()+1).findFirst();
        var tray=base.steamFeeds().stream().filter(s->s.stageNumber()<=base.stageCount()).findFirst();
        put("sumpRate","");put("sumpT","");put("steamStage","");put("steamRate","");put("steamT","");
        sump.ifPresent(s->{putNumber("sumpRate",s.molarFlowMolPerSecond()*3.6,false);putNumber("sumpT",s.temperatureKelvin()-273.15,false);});
        tray.ifPresent(s->{putNumber("steamStage",s.stageNumber(),true);putNumber("steamRate",s.molarFlowMolPerSecond()*3.6,false);putNumber("steamT",s.temperatureKelvin()-273.15,false);});
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++){
            var c=i<base.pumparounds().size()?base.pumparounds().get(i):null;
            if(c==null){put("c"+i+"draw","");put("c"+i+"return","");put("c"+i+"duty","");}
            else{putNumber("c"+i+"draw",c.drawTray(),true);putNumber("c"+i+"return",c.returnTray(),true);putNumber("c"+i+"duty",-c.dutyWatts()/1e6,false);}
            put("c"+i+"split",c==null?"UNIFORM":c.split().name());
        }
        if(weights!=null)composition=new V3CompositionDraft(base,weights);
    }
    V3ColumnInput base(){return base;}
    V3CompositionDraft composition(){return composition;}
    void composition(V3ColumnInput preset,List<Double> weights){composition=new V3CompositionDraft(preset,weights);}
    String get(String key){return fields.get(key);}
    private static boolean temperature(String key){return key.equals("s1")||key.equals("s4")||key.equals("sumpT")||key.equals("steamT");}
    String display(String key,boolean kelvin){
        if(!kelvin||!temperature(key)||get(key).isBlank())return get(key);
        try{return String.format(Locale.ROOT,"%.1f",Double.parseDouble(precise(key))+273.15);}
        catch(NumberFormatException invalid){return get(key);}
    }
    void setDisplay(String key,String value,boolean kelvin){
        if(kelvin&&temperature(key)&&!value.isBlank())try{value=Double.toString(Double.parseDouble(value)-273.15);}
        catch(NumberFormatException ignored){/* Preserve incomplete typing. */}
        set(key,value);
    }
    void set(String key,String value){
        if(!fields.containsKey(key))throw new IllegalArgumentException("Unknown input field");
        if(!Objects.equals(fields.put(key,value),value))exact.remove(key);
    }
    boolean dirty(){return !fields.equals(original)||composition!=null&&(composition.dirty()||!composition.source().equals(base));}
    private void put(String key,String value){fields.put(key,value);original.put(key,value);}
    private void putNumber(String key,double value,boolean integer){
        put(key,integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.1f",value));exact.put(key,value);
    }
    private String precise(String key){
        if(!exact.containsKey(key))return get(key);
        double v=exact.get(key);
        return key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("Stage")||key.endsWith("draw")||key.endsWith("return")
                ?Integer.toString((int)v):Double.toString(v);
    }
    int preview(String key,int fallback,int min,int max){
        try{return Math.clamp(Integer.parseInt(get(key)),min,max);}catch(NumberFormatException e){return fallback;}
    }
    int addDraw(int tray){
        for(int i=0;i<3;i++)if(get("d"+i+"stage").isBlank()){
            set("d"+i+"stage",Integer.toString(tray));set("d"+i+"rate","1.0");return i;}
        return -1;
    }
    void removeDraw(int i){set("d"+i+"stage","");set("d"+i+"rate","");}
    int addPumparound(int tray){
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)if(get("c"+i+"draw").isBlank()){
            set("c"+i+"draw",Integer.toString(Math.max(2,tray)));set("c"+i+"return",Integer.toString(Math.max(1,tray-1)));
            set("c"+i+"duty","0.1");return i;}
        return -1;
    }
    void removePumparound(int i){set("c"+i+"draw","");set("c"+i+"return","");set("c"+i+"duty","");}
    V3ColumnInput assemble(){
        if(!dirty()||V3HollandExample32.isPackage(base.packageId()))return base;
        List<String> scalar=new ArrayList<>();for(int i=0;i<10;i++)scalar.add(precise("s"+i));
        List<V3SideDrawDraft.Row> draws=new ArrayList<>();
        for(int i=0;i<3;i++)draws.add(new V3SideDrawDraft.Row(precise("d"+i+"stage"),precise("d"+i+"rate")));
        List<V3PumparoundDraft.Row> coolers=new ArrayList<>();
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)coolers.add(new V3PumparoundDraft.Row(
            precise("c"+i+"draw"),precise("c"+i+"return"),precise("c"+i+"duty"),V3PumparoundSpec.Split.valueOf(get("c"+i+"split"))));
        var parsed=V3ColumnInputDraft.assemble(base,scalar,draws,
            new V3SteamFeedDraft.Row("",precise("sumpRate"),precise("sumpT")),
            new V3SteamFeedDraft.Row(precise("steamStage"),precise("steamRate"),precise("steamT")),coolers);
        var source=composition==null?base:composition.source();
        double[] flows=parsed.feedComponentMolarFlowsMolPerSecond();
        if(composition!=null&&(composition.dirty()||!source.equals(base))){
            flows=composition.moleFractions();double total=Double.parseDouble(precise("s0"))/3.6;
            for(int i=0;i<flows.length;i++)flows[i]*=total;
        }else if(fields.get("s0").equals(original.get("s0")))flows=base.feedComponentMolarFlowsMolPerSecond();
        var precise=new V3ColumnInput(base.schemaVersion(),source.packageId(),source.assayId(),source.componentBasis(),flows,
            fields.get("s1").equals(original.get("s1"))?base.feedTemperatureKelvin():parsed.feedTemperatureKelvin(),
            parsed.stageCount(),parsed.feedStageNumber(),unchanged("s7")?base.topPressurePascal():parsed.topPressurePascal(),
            unchanged("s8")?base.stagePressureDropPascal():parsed.stagePressureDropPascal(),
            List.of(new V3ColumnSpecification.CondenserOutletTemperature(unchanged("s4")?value(base,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE):value(parsed,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)),
                new V3ColumnSpecification.OrganicRefluxRatio(unchanged("s6")?value(base,V3ControlledQuantity.ORGANIC_REFLUX_RATIO):value(parsed,V3ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                new V3ColumnSpecification.ReboilerDuty(unchanged("s5")?value(base,V3ControlledQuantity.REBOILER_DUTY):value(parsed,V3ControlledQuantity.REBOILER_DUTY))),
            parsed.sideDraws(),parsed.steamFeeds(),parsed.pumparounds(),unchanged("s9")?base.columnDiameterMetres():parsed.columnDiameterMetres());
        V3ColumnProblemResolver.validateInput(precise);return precise;
    }
    private boolean unchanged(String key){return Objects.equals(fields.get(key),original.get(key));}
    static double value(V3ColumnInput input,V3ControlledQuantity quantity){
        return input.specifications().stream().filter(s->s.controlledQuantity()==quantity).mapToDouble(s->switch(s){
            case V3ColumnSpecification.CondenserOutletTemperature t->t.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio r->r.ratio();
            case V3ColumnSpecification.ReboilerDuty d->d.watts();
        }).findFirst().orElseThrow();
    }
}
