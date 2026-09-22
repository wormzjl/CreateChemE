package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.*;
import java.util.*;

/** Server-authored operating choices. Identity, feed data and editable operating settings stay separate. */
public final class MaterialPresets {
    public static final int MAX_PRESETS=32;
    public record Descriptor(String id,String label,String translationKey,String packageId,String assayId) {
        public Descriptor {
            if(id==null||!id.matches("[a-z][a-z0-9_.:-]{0,127}")||label==null||label.isBlank()||label.length()>128
                    ||translationKey==null||translationKey.length()>128||packageId==null||packageId.length()>128||assayId==null||assayId.length()>128)
                throw new IllegalArgumentException("Invalid preset naming descriptor");
        }
        public boolean matches(V3ColumnInput input){return packageId.equals(input.packageId())&&assayId.equals(input.assayId());}
    }
    /** {@code diameter} is the sieve-tray hydraulics input; zero authors the legacy prescribed uniform drop. */
    public record Column(Descriptor descriptor,int order,boolean visible,double feedVolume,double feedTemperature,
            int stages,int feedStage,double pressure,double pressureDrop,List<V3ColumnSpecification> specifications,
            List<V3SideDrawSpec> draws,List<V3SteamFeedSpec> steam,List<V3PumparoundSpec> pumparounds,double diameter) {
        public Column {specifications=List.copyOf(specifications);draws=List.copyOf(draws);steam=List.copyOf(steam);pumparounds=List.copyOf(pumparounds);}
        public V3ColumnInput input(MaterialCatalog catalog) {
            var p=catalog.requirePackage(descriptor.packageId());double[] fractions=moleFractions(p,descriptor.assayId());
            double volumePerMole=0;
            for(int i=0;i<fractions.length;i++)volumePerMole+=fractions[i]*p.properties().get(i).molecularWeight()/p.properties().get(i).density();
            double rate=feedVolume/volumePerMole;
            for(int i=0;i<fractions.length;i++)fractions[i]*=rate;
            return new V3ColumnInput(1,p.id(),descriptor.assayId(),new V3ComponentBasis(p.components()),fractions,
                    feedTemperature,stages,feedStage,pressure,pressureDrop,specifications,draws,steam,pumparounds,diameter);
        }
    }
    public record Fluid(String id,String label,String component,String packageId,String assayId) {}
    public record Network(String packageId,String defaultColumnPreset,List<String> fluidPresets) {
        public Network {fluidPresets=List.copyOf(fluidPresets);}
    }
    private final Map<String,Column> columns;
    private final Map<String,Fluid> fluids;
    private final Network network;
    private MaterialPresets(Map<String,Column> columns,Map<String,Fluid> fluids,Network network) {
        this.columns=Map.copyOf(columns);this.fluids=Map.copyOf(fluids);this.network=network;
    }
    public Map<String,Column> columns(){return columns;}
    public Column column(String id){var value=columns.get(id);if(value==null)throw new IllegalArgumentException("Unknown column preset: "+id);return value;}
    public Fluid fluid(String id){var value=fluids.get(id);if(value==null)throw new IllegalArgumentException("Unknown fluid preset: "+id);return value;}
    public Network network(){if(network==null)throw new IllegalArgumentException("No fluid network configuration in material catalog");return network;}
    public List<Descriptor> visibleColumns(){return columns.values().stream().filter(Column::visible)
            .sorted(Comparator.comparingInt(Column::order).thenComparing(c->c.descriptor().id())).map(Column::descriptor).toList();}
    public static double[] moleFractions(MaterialCatalog.Package p,String assayId) {
        var a=p.assays().get(assayId);if(a==null)throw new IllegalArgumentException("Unknown assay "+assayId+" for "+p.id());
        double[] n=new double[p.components().size()];
        for(int i=0;i<n.length;i++) {
            var property=p.properties().get(i);double amount=a.amounts().get(i);
            n[i]=switch(a.basis()) {
                case "mole"->amount;
                case "mass"->amount/property.molecularWeight();
                case "standard_liquid_volume"->(a.volumeScale()*amount/a.amountTotal())*property.density()/property.molecularWeight();
                default->throw new IllegalArgumentException("Unsupported assay basis");
            };
        }
        double total=0;for(double value:n){if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid assay amount");total+=value;}
        if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("Empty assay");
        for(int i=0;i<n.length;i++)n[i]/=total;
        return n;
    }
    static MaterialPresets read(Map<String,JsonObject> records,Map<String,JsonObject> networks,Map<JsonObject,String> origins,
            Map<String,MaterialCatalog.Package> packages,Set<String> components) {
        var columns=new HashMap<String,Column>();var fluids=new HashMap<String,Fluid>();Network network=null;
        if(records.size()>MAX_PRESETS*2)throw new IllegalArgumentException("Too many material presets");
        for(var row:records.values())try {
            String id=text(row,"id"),label=text(row,"label"),kind=text(row,"kind");
            if(label.length()>128)throw new IllegalArgumentException("label: exceeds 128 characters");
            if(kind.equals("column")) {
                String pkg=text(row,"package"),assay=text(row,"assay");requireAssay(packages,pkg,assay);
                if(id.equals("holland_3_2"))throw new IllegalArgumentException("id: reserved independent example");
                var d=new Descriptor(id,label,text(row,"translation_key"),pkg,assay);var o=row.getAsJsonObject("operating");
                var specs=List.<V3ColumnSpecification>of(new V3ColumnSpecification.CondenserOutletTemperature(number(o,"condenserTemperatureKelvin")),
                        new V3ColumnSpecification.OrganicRefluxRatio(number(o,"organicRefluxRatio")),new V3ColumnSpecification.ReboilerDuty(number(o,"reboilerDutyWatts")));
                var draws=new ArrayList<V3SideDrawSpec>();for(var v:o.getAsJsonArray("sideDraws")){var x=v.getAsJsonObject();draws.add(new V3SideDrawSpec(integer(x,"trayNumber"),number(x,"molarFlowMolPerSecond")));}
                var steam=new ArrayList<V3SteamFeedSpec>();for(var v:o.getAsJsonArray("steamFeeds")){var x=v.getAsJsonObject();steam.add(new V3SteamFeedSpec(integer(x,"stageNumber"),number(x,"molarFlowMolPerSecond"),number(x,"temperatureKelvin")));}
                var coolers=new ArrayList<V3PumparoundSpec>();for(var v:o.getAsJsonArray("pumparounds")){var x=v.getAsJsonObject();coolers.add(new V3PumparoundSpec(integer(x,"returnTray"),integer(x,"drawTray"),number(x,"dutyWatts"),V3PumparoundSpec.Split.valueOf(text(x,"split"))));}
                double volume=number(o,"feedStandardVolumeCubicMetresPerSecond");if(volume<=0)throw new IllegalArgumentException("operating.feedStandardVolumeCubicMetresPerSecond: positive required");
                var c=new Column(d,integer(row,"order"),bool(row,"visible"),volume,number(o,"feedTemperatureKelvin"),integer(o,"stageCount"),integer(o,"feedStageNumber"),number(o,"topPressurePascal"),number(o,"stagePressureDropPascal"),specs,draws,steam,coolers,
                        optionalNumber(o,"columnDiameterMetres",V3ColumnInput.PRESCRIBED_DROP_DIAMETER));
                var p=packages.get(pkg);double t=c.feedTemperature(),bottom=c.pressure()+(c.stages()-1)*c.pressureDrop();
                if(c.stages()<2||c.stages()>64||c.feedStage()<1||c.feedStage()>c.stages()||t<p.minimumTemperature()||t>p.maximumTemperature()||c.pressure()<p.minimumPressure()||bottom>p.maximumPressure()||c.pressureDrop()<0)
                    throw new IllegalArgumentException("operating: outside package or column domain");
                if(c.diameter()<0||c.diameter()>V3ColumnInput.MAX_COLUMN_DIAMETER_METRES)
                    throw new IllegalArgumentException("operating.columnDiameterMetres: outside 0.."+V3ColumnInput.MAX_COLUMN_DIAMETER_METRES+" m");
                columns.put(id,c);
            } else if(kind.equals("fluid")) {
                if(row.has("component")) {
                    String idComponent=text(row,"component");if(row.has("package")||row.has("assay")||!components.contains(idComponent))throw new IllegalArgumentException("component: unknown or conflicting fluid source");
                    fluids.put(id,new Fluid(id,label,idComponent,"",""));
                } else {String pkg=text(row,"package"),assay=text(row,"assay");requireAssay(packages,pkg,assay);fluids.put(id,new Fluid(id,label,"",pkg,assay));}
            } else throw new IllegalArgumentException("kind: expected column or fluid");
        } catch(RuntimeException invalid){throw new IllegalArgumentException(origins.get(row)+": "+invalid.getMessage(),invalid);}
        if(columns.size()>=MAX_PRESETS||fluids.size()>MAX_PRESETS)throw new IllegalArgumentException("Too many presets");
        if(networks.size()>1)throw new IllegalArgumentException("Only one global network configuration is supported");
        for(var row:networks.values())try {
            String pkg=text(row,"package"),defaultId=text(row,"default_column_preset");var p=packages.get(pkg);
            if(p==null||!p.components().contains("Nitrogen")||p.components().contains("Water")||!p.model().equals("pr78"))throw new IllegalArgumentException("package: PR78 network requires Nitrogen and separate water");
            if(!columns.containsKey(defaultId)||!columns.get(defaultId).visible())throw new IllegalArgumentException("default_column_preset: unknown or hidden preset");
            var ids=new ArrayList<String>();for(var value:row.getAsJsonArray("fluid_presets")){String id=value.getAsString();if(!fluids.containsKey(id)||ids.contains(id))throw new IllegalArgumentException("fluid_presets: unknown or duplicate "+id);ids.add(id);}
            network=new Network(pkg,defaultId,ids);
        } catch(RuntimeException invalid){throw new IllegalArgumentException(origins.get(row)+": "+invalid.getMessage(),invalid);}
        return new MaterialPresets(columns,fluids,network);
    }
    private static void requireAssay(Map<String,MaterialCatalog.Package> packages,String pkg,String assay){if(!packages.containsKey(pkg)||!packages.get(pkg).assays().containsKey(assay))throw new IllegalArgumentException("package/assay: unknown "+pkg+"/"+assay);}
    private static String text(JsonObject row,String field){var e=row.get(field);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()||e.getAsString().isBlank())throw new IllegalArgumentException(field+": string required");return e.getAsString();}
    /** A preset written before flow-dependent tray hydraulics keeps the prescribed uniform drop it authored. */
    private static double optionalNumber(JsonObject row,String field,double fallback){return row.has(field)?number(row,field):fallback;}
    private static double number(JsonObject row,String field){var e=row.get(field);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!Double.isFinite(e.getAsDouble()))throw new IllegalArgumentException(field+": finite number required");return e.getAsDouble();}
    private static int integer(JsonObject row,String field){number(row,field);try{return row.get(field).getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException(field+": integer required",e);}}
    private static boolean bool(JsonObject row,String field){var e=row.get(field);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(field+": boolean required");return e.getAsBoolean();}
}
