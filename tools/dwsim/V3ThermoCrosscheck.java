package com.wormzjl.createcheme.science.column.v3.thermo;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public final class V3ThermoCrosscheck {
    public static void main(String[] args) throws Exception {
        var json=new GsonBuilder().setPrettyPrinting().create();
        var points=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        var thermo=V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        var rows=new ArrayList<Object>();
        for(var e:points){var p=e.getAsJsonObject();if(!p.has("flash_mode")||!p.get("flash_mode").getAsString().equals("Default")||!p.get("label").getAsString().startsWith("V3"))continue;
            double t=p.get("temperature_K").getAsDouble();
            for(var phase:List.of(V3Phase.LIQUID,V3Phase.VAPOR)){
                double[] full=json.fromJson(p.get(phase==V3Phase.LIQUID?"x":"y"),double[].class);
                double[] z=Arrays.copyOf(full,19);double sum=Arrays.stream(z).sum();for(int c=0;c<z.length;c++)z[c]/=sum;
                var v=thermo.fugacity(t,250000,z,phase,thermo.newWorkspace());
                double[] nativePhi=json.fromJson(p.get(phase==V3Phase.LIQUID?"liquid_fugacity_coefficients":"vapor_fugacity_coefficients"),double[].class);
                double[] diff=new double[19];for(int c=0;c<19;c++)diff[c]=v.logFugacityCoefficient(c)-Math.log(nativePhi[c]);
                var row=new LinkedHashMap<String,Object>();row.put("label",p.get("label").getAsString());row.put("phase",phase);row.put("T",t);row.put("max_log_phi_difference",Arrays.stream(diff).map(Math::abs).max().orElseThrow());row.put("log_phi_differences",diff);rows.add(row);
            }
        }
        Files.writeString(Path.of(args[1]),json.toJson(rows));
    }
}
