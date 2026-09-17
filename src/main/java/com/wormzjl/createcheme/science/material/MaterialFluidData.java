package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import java.util.*;

/** Validated, immutable reference data used by fluid EOS volume translation and dissolved-solute transport. */
public final class MaterialFluidData {
    public record VolumeReference(double temperatureKelvin,double pressurePascal,double molarVolumeCubicMetres) {}
    private final Map<String,VolumeReference> volumes;
    private final Map<String,ViscosityCorrelation> conditional;
    private MaterialFluidData(Map<String,VolumeReference> volumes,Map<String,ViscosityCorrelation> conditional) {
        this.volumes=Map.copyOf(volumes);this.conditional=Map.copyOf(conditional);
    }
    public Map<String,VolumeReference> volumeReferences(){return volumes;}
    public Map<String,ViscosityCorrelation> conditionalLiquidViscosities(){return conditional;}
    static MaterialFluidData read(Map<String,JsonObject> records,Map<JsonObject,String> origins,Set<String> components) {
        var volumes=new HashMap<String,VolumeReference>();var curves=new HashMap<String,ViscosityCorrelation>();
        for(var row:records.values())try {
            String type=text(row,"type"),revision=text(row,"revision"),source=text(row,"source");
            if(type.equals("liquid_volume_reference_points")) {
                for(var entry:array(row,"points")) {
                    var point=entry.getAsJsonObject();String id=component(point,components);
                    text(point,"source");
                    var value=new VolumeReference(positive(point,"temperatureKelvin"),positive(point,"pressurePascal"),positive(point,"molarVolumeCubicMetres"));
                    if(volumes.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("points: duplicate component "+id);
                }
            } else if(type.equals("conditional_solute_log_tables")) {
                double pressure=positive(row,"reference_pressure_pascal");
                for(var entry:array(row,"curves")) {
                    var curve=entry.getAsJsonObject();String id=component(curve,components);
                    var t=numbers(curve,"temperatures_kelvin");var mu=numbers(curve,"viscosities_pascal_seconds");
                    if(t.size()<2||t.size()!=mu.size())throw new IllegalArgumentException("curves: temperature/value count mismatch");
                    var value=new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,t.getFirst(),t.getLast(),pressure,pressure,t.getFirst(),mu,List.of(),revision,source,true,t);
                    if(curves.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("curves: duplicate component "+id);
                    if(curve.has("checks"))for(var check:array(curve,"checks")) {
                        var sample=check.getAsJsonObject();double temperature=positive(sample,"temperature_kelvin");positive(sample,"viscosity_pascal_seconds");
                        if(temperature<t.getFirst()||temperature>t.getLast())throw new IllegalArgumentException("checks: temperature outside sampled domain");
                    }
                }
            } else throw new IllegalArgumentException("type: unsupported fluid reference record "+type);
        } catch(RuntimeException invalid) {throw new IllegalArgumentException(origins.get(row)+": "+invalid.getMessage(),invalid);}
        return new MaterialFluidData(volumes,curves);
    }
    private static String component(JsonObject row,Set<String> components){String id=text(row,"component");if(!components.contains(id))throw new IllegalArgumentException("component: unknown "+id);return id;}
    private static String text(JsonObject row,String key){var e=row.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()||e.getAsString().isBlank())throw new IllegalArgumentException(key+": string required");return e.getAsString();}
    private static JsonArray array(JsonObject row,String key){var e=row.get(key);if(e==null||!e.isJsonArray())throw new IllegalArgumentException(key+": array required");return e.getAsJsonArray();}
    private static double positive(JsonObject row,String key){var e=row.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+": number required");double v=e.getAsDouble();if(!Double.isFinite(v)||v<=0)throw new IllegalArgumentException(key+": positive finite value required");return v;}
    private static List<Double> numbers(JsonObject row,String key){var values=new ArrayList<Double>();for(var e:array(row,key)){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+": number required");double v=e.getAsDouble();if(!Double.isFinite(v)||v<=0)throw new IllegalArgumentException(key+": positive finite values required");values.add(v);}return List.copyOf(values);}
}
