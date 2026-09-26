import com.google.gson.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity;
import java.nio.file.*;

/** Local research check against the real Java evaluator, not a second Python mixing formula. */
class VerifyReconstruction {
    public static void main(String[] args) throws Exception {
        var json=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var catalog=MaterialCatalog.bundled();int count=0;double worst=0;
        for (var element:json.getAsJsonArray("records")) {
            var record=element.getAsJsonObject();
            var model=new MixtureViscosity(catalog,record.get("package_id").getAsString());
            for (String kind:new String[]{"crude","residue"}) {
                var a=record.getAsJsonArray(kind.equals("crude")?"mole_fractions":"residue_mole_fractions");
                double[] x=new double[a.size()];for(int i=0;i<x.length;i++)x[i]=a.get(i).getAsDouble();
                var expected=record.getAsJsonObject("series").getAsJsonObject(kind).getAsJsonArray("catalog_raw");
                for(double c:new double[]{20,50,100,150,200,400,500}) {
                    if(kind.equals("crude")&&c>200)continue;
                    double actual=model.liquid(c+273.15,x).pascalSeconds();
                    double error=Math.abs(actual/expected.get((int)(c*4)).getAsDouble()-1);
                    worst=Math.max(worst,error);count++;
                    if(error>1e-11)throw new AssertionError(record.get("id")+" "+kind+" "+c+" error "+error);
                }
            }
        }
        String report="{\"checks\":"+count+",\"maximum_relative_error\":"+worst+",\"status\":\"passed\"}\n";
        Files.writeString(Path.of(args[1]),report);System.out.print(report);
    }
}
