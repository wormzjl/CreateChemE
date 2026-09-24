package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.*;
import java.util.*;

/** Widget-independent draft; one-decimal displays never round untouched physical inputs. */
final class V3EditorDraft {
    private final V3ColumnInput base;
    private final Map<String,String> original=new LinkedHashMap<>(),fields=new LinkedHashMap<>();
    private final Map<String,Double> exact=new HashMap<>();
    private V3CompositionDraft composition;
    private boolean columnChanged;
    private final boolean[] steamAtBottom=new boolean[2];

    V3EditorDraft(V3ColumnInput base){this(base,null);}
    V3EditorDraft(V3ColumnInput base,List<Double> weights){
        this.base=base;
        double[] scalars={Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum()*3.6,
            base.feedTemperatureKelvin()-273.15,base.stageCount()+2,base.feedStageNumber()+1,
            value(base,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)-273.15,
            value(base,V3ControlledQuantity.REBOILER_DUTY)/1e6,value(base,V3ControlledQuantity.ORGANIC_REFLUX_RATIO),
            base.topPressurePascal()/1e5,base.stagePressureDropPascal()/1000,base.columnDiameterMetres()};
        for(int i=0;i<scalars.length;i++)putNumber("s"+i,scalars[i],i==2||i==3);
        for(int i=0;i<3;i++){
            var d=i<base.sideDraws().size()?base.sideDraws().get(i):null;
            if(d==null){put("d"+i+"stage","");put("d"+i+"rate","");}
            else{putNumber("d"+i+"stage",d.trayNumber()+1,true);putNumber("d"+i+"rate",d.molarFlowMolPerSecond()*3.6,false);}
        }
        for(int i=0;i<V3ColumnInput.MAX_STEAM_FEEDS;i++){
            var steam=i<base.steamFeeds().size()?base.steamFeeds().get(i):null;
            if(steam==null){put("t"+i+"stage","");put("t"+i+"rate","");put("t"+i+"T","");}
            else{steamAtBottom[i]=steam.stageNumber()==base.stageCount()+1;putNumber("t"+i+"stage",steam.stageNumber()+1,true);putNumber("t"+i+"rate",steam.molarFlowMolPerSecond()*3.6,false);putNumber("t"+i+"T",steam.temperatureKelvin()-273.15,false);}
        }
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++){
            var c=i<base.pumparounds().size()?base.pumparounds().get(i):null;
            if(c==null){put("c"+i+"draw","");put("c"+i+"return","");put("c"+i+"duty","");}
            else{putNumber("c"+i+"draw",c.drawTray()+1,true);putNumber("c"+i+"return",c.returnTray()+1,true);putNumber("c"+i+"duty",-c.dutyWatts()/1e6,false);}
            put("c"+i+"split",c==null?"UNIFORM":c.split().name());
        }
        if(weights!=null)composition=new V3CompositionDraft(base,weights);
    }
    V3ColumnInput base(){return base;}
    V3CompositionDraft composition(){return composition;}
    void composition(V3ColumnInput preset,List<Double> weights){composition=new V3CompositionDraft(preset,weights);}
    String get(String key){if(key.matches("t[0-1]stage")&&steamAtBottom[Character.digit(key.charAt(1),10)])return fields.get("s2");return fields.get(key);}
    private static boolean temperature(String key){return key.equals("s1")||key.equals("s4")||key.matches("t[0-1]T");}
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
        String previous=fields.put(key,value);
        if(!Objects.equals(previous,value))exact.remove(key);
        if(key.matches("t[0-1]stage"))steamAtBottom[Character.digit(key.charAt(1),10)]=value.equals(fields.get("s2"));

    }
    boolean dirty(){return columnChanged||!fields.equals(original)||composition!=null&&(composition.dirty()||!composition.source().equals(base));}
    private void put(String key,String value){fields.put(key,value);original.put(key,value);}
    private void putNumber(String key,double value,boolean integer){
        put(key,integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.1f",value));exact.put(key,value);
    }
    private String precise(String key){
        if(key.matches("t[0-1]stage")&&steamAtBottom[Character.digit(key.charAt(1),10)])return get(key);
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
            set("c"+i+"draw",Integer.toString(Math.max(3,tray)));set("c"+i+"return",Integer.toString(Math.max(2,tray-1)));
            set("c"+i+"duty","0.1");return i;}
        return -1;
    }
    void removePumparound(int i){set("c"+i+"draw","");set("c"+i+"return","");set("c"+i+"duty","");}
    boolean canAddSteam(){return nextSteamTray(2)>=2&&java.util.stream.IntStream.range(0,2).anyMatch(i->get("t"+i+"stage").isBlank());}
    private int nextSteamTray(int requested){
        int count=preview("s2",base.stageCount()+2,2,66);
        if(requested>=2&&requested<=count&&!steamUses(requested))return requested;
        for(int tray=2;tray<=count;tray++)if(!steamUses(tray))return tray;
        return -1;
    }
    private boolean steamUses(int tray){return get("t0stage").equals(Integer.toString(tray))||get("t1stage").equals(Integer.toString(tray));}
    int addSteam(int tray){
        int selected=nextSteamTray(tray);if(selected<2)return -1;
        for(int i=0;i<2;i++)if(get("t"+i+"stage").isBlank()){
            set("t"+i+"stage",Integer.toString(selected));set("t"+i+"rate","1.0");set("t"+i+"T","260.0");return i;
        }
        return -1;
    }
    void removeSteam(int i){set("t"+i+"stage","");set("t"+i+"rate","");set("t"+i+"T","");}
    Map<String,String> columnFields(){
        Map<String,String> result=new LinkedHashMap<>();for(String key:fields.keySet())result.put(key,precise(key));return result;
    }
    void loadColumn(Map<String,String> data){
        if(!data.keySet().equals(fields.keySet()))throw new IllegalArgumentException("Invalid column preset fields");
        data.forEach((key,value)->{
            fields.put(key,value);exact.remove(key);
            if(!value.isBlank()&&!key.endsWith("split")){
                double n=Double.parseDouble(value);exact.put(key,n);
                fields.put(key,isInteger(key)?Integer.toString((int)n):String.format(Locale.ROOT,"%.1f",n));
            }
        });for(int i=0;i<2;i++)steamAtBottom[i]=fields.get("t"+i+"stage").equals(fields.get("s2"));columnChanged=true;
    }
    private static boolean isInteger(String key){return key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("draw")||key.endsWith("return");}
    private String index(String key,int delta){
        String value=precise(key);return value.isBlank()?value:Integer.toString(Integer.parseInt(value)+delta);
    }
    V3ColumnInput assemble(){return assemble(true);}
    V3ColumnInput assembleOperating(){return assemble(false);}
    private V3ColumnInput assemble(boolean withComposition){
        if(!dirty())return base;
        List<String> scalar=new ArrayList<>();for(int i=0;i<10;i++)scalar.add(i==2?index("s2",-2):i==3?index("s3",-1):precise("s"+i));
        List<V3SideDrawDraft.Row> draws=new ArrayList<>();
        for(int i=0;i<3;i++)draws.add(new V3SideDrawDraft.Row(index("d"+i+"stage",-1),precise("d"+i+"rate")));
        List<V3PumparoundDraft.Row> coolers=new ArrayList<>();
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)coolers.add(new V3PumparoundDraft.Row(
            index("c"+i+"draw",-1),index("c"+i+"return",-1),precise("c"+i+"duty"),V3PumparoundSpec.Split.valueOf(get("c"+i+"split"))));
        var parsed=V3ColumnInputDraft.assemble(base,scalar,draws,
            new V3SteamFeedDraft.Row("","",""),new V3SteamFeedDraft.Row("","",""),coolers);
        List<V3SteamFeedSpec> steam=new ArrayList<>();
        for(int i=0;i<2;i++)if(!get("t"+i+"stage").isBlank())steam.add(new V3SteamFeedSpec(Integer.parseInt(index("t"+i+"stage",-1)),Double.parseDouble(precise("t"+i+"rate"))/3.6,Double.parseDouble(precise("t"+i+"T"))+273.15));
        var source=!withComposition||composition==null?base:composition.source();
        double[] flows=parsed.feedComponentMolarFlowsMolPerSecond();
        if(withComposition&&composition!=null&&(composition.dirty()||!source.equals(base))){
            flows=composition.moleFractions();double total=Double.parseDouble(precise("s0"))/3.6;
            for(int i=0;i<flows.length;i++)flows[i]*=total;
        }else if(!columnChanged&&fields.get("s0").equals(original.get("s0")))flows=base.feedComponentMolarFlowsMolPerSecond();
        var precise=new V3ColumnInput(base.schemaVersion(),source.packageId(),source.assayId(),source.componentBasis(),flows,
            !columnChanged&&fields.get("s1").equals(original.get("s1"))?base.feedTemperatureKelvin():parsed.feedTemperatureKelvin(),
            parsed.stageCount(),parsed.feedStageNumber(),unchanged("s7")?base.topPressurePascal():parsed.topPressurePascal(),
            unchanged("s8")?base.stagePressureDropPascal():parsed.stagePressureDropPascal(),
            List.of(new V3ColumnSpecification.CondenserOutletTemperature(unchanged("s4")?value(base,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE):value(parsed,V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)),
                new V3ColumnSpecification.OrganicRefluxRatio(unchanged("s6")?value(base,V3ControlledQuantity.ORGANIC_REFLUX_RATIO):value(parsed,V3ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                new V3ColumnSpecification.ReboilerDuty(unchanged("s5")?value(base,V3ControlledQuantity.REBOILER_DUTY):value(parsed,V3ControlledQuantity.REBOILER_DUTY))),
            parsed.sideDraws(),steam,parsed.pumparounds(),unchanged("s9")?base.columnDiameterMetres():parsed.columnDiameterMetres());
        V3ColumnProblemResolver.validateInput(precise);
        return withComposition&&V3HollandExample32.isPackage(precise.packageId())?canonicalBenchmark(precise):precise;
    }
    /** Independent JSON unit/relative conversions must not turn roundoff into a changed fixed benchmark. */
    private static V3ColumnInput canonicalBenchmark(V3ColumnInput input){
        var fixed=V3HollandExample32.input();
        if(input.stageCount()!=fixed.stageCount()||input.feedStageNumber()!=fixed.feedStageNumber()
                ||!input.componentBasis().equals(fixed.componentBasis())||!input.steamFeeds().isEmpty()||!input.pumparounds().isEmpty()
                ||input.sideDraws().size()!=fixed.sideDraws().size())return input;
        if(!roundoff(input.feedTemperatureKelvin(),fixed.feedTemperatureKelvin())||!roundoff(input.topPressurePascal(),fixed.topPressurePascal())
                ||!roundoff(input.stagePressureDropPascal(),fixed.stagePressureDropPascal())||!roundoff(input.columnDiameterMetres(),fixed.columnDiameterMetres()))return input;
        for(var quantity:V3ControlledQuantity.values())if(!roundoff(value(input,quantity),value(fixed,quantity)))return input;
        double[] actual=input.feedComponentMolarFlowsMolPerSecond(),expected=fixed.feedComponentMolarFlowsMolPerSecond();
        for(int i=0;i<actual.length;i++)if(!roundoff(actual[i],expected[i]))return input;
        for(int i=0;i<input.sideDraws().size();i++){
            var a=input.sideDraws().get(i);var b=fixed.sideDraws().get(i);
            if(a.trayNumber()!=b.trayNumber()||!roundoff(a.molarFlowMolPerSecond(),b.molarFlowMolPerSecond()))return input;
        }
        return fixed;
    }
    private static boolean roundoff(double actual,double expected){return actual==expected||Math.abs(actual-expected)<=16*Math.ulp(expected);}
    private boolean unchanged(String key){return !columnChanged&&Objects.equals(fields.get(key),original.get(key));}
    static double value(V3ColumnInput input,V3ControlledQuantity quantity){
        return input.specifications().stream().filter(s->s.controlledQuantity()==quantity).mapToDouble(s->switch(s){
            case V3ColumnSpecification.CondenserOutletTemperature t->t.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio r->r.ratio();
            case V3ColumnSpecification.ReboilerDuty d->d.watts();
        }).findFirst().orElseThrow();
    }
}
