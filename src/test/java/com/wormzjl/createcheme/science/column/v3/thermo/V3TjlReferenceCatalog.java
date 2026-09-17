package com.wormzjl.createcheme.science.column.v3.thermo;

import com.google.gson.Gson;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;

/** Rebuilds only the independent old numerical oracle for frozen stalled/wet-state regressions. */
public final class V3TjlReferenceCatalog {
    private V3TjlReferenceCatalog() {}
    public static MaterialCatalog catalog() {
        var resources=new LinkedHashMap<>(MaterialRuntime.current().resources());
        var json=new Gson();String root="data/createcheme/materials/";
        for(var old:List.of(V3Tjl19PropertyPackage.INSTANCE,V3Tjl20MethanePropertyPackage.INSTANCE)) {
            var ids=old.componentBasis().componentIds();var refs=new ArrayList<String>();
            for(int i=0;i<ids.size();i++) {
                var p=old.component(i);String slug=p.id().toLowerCase(Locale.ROOT).replace('-','_');
                String id="test:tjl_"+slug;refs.add(id);
                resources.put(root+"components/"+slug+".json",json.toJson(Map.of("schema_version",1,"id",p.id(),"kind","lump","translation_key","test."+slug,"fallback",p.id())));
                var property=new LinkedHashMap<String,Object>();
                property.put("schema_version",1);property.put("id",id);property.put("component",p.id());property.put("revision",old.datasetRevision());property.put("source","Independent frozen Java numerical oracle");
                property.put("molecular_weight_kg_per_mol",p.molecularWeightKgPerMol());property.put("normal_boiling_point_kelvin",p.normalBoilingPointKelvin());property.put("standard_liquid_density_kg_per_m3",p.standardLiquidDensityKgPerCubicMetre());
                property.put("standard_temperature_kelvin",288.7055555556);property.put("standard_pressure_pascal",101325);
                property.put("temperature_min_kelvin",old.minimumTemperatureKelvin());property.put("temperature_max_kelvin",old.maximumTemperatureKelvin());property.put("estimated_heavy_residue",p.estimatedHeavyResidue());
                property.put("ideal_gas_cp",Map.of("type","shifted_polynomial_5","reference_kelvin",298.15,"coefficients",List.of(p.cpA(),p.cpB(),p.cpC(),p.cpD(),p.cpE(),p.cpF())));
                property.put("models",Map.of("pr78",Map.of("critical_temperature_kelvin",p.criticalTemperatureKelvin(),"critical_pressure_pascal",p.criticalPressurePascal(),"acentric_factor",p.acentricFactor())));
                resources.put(root+"properties/test_tjl_"+slug+".json",json.toJson(property));
            }
            String suffix=old instanceof V3Tjl20MethanePropertyPackage?"tjl20":"tjl19";
            var packageRecord=new LinkedHashMap<String,Object>();
            packageRecord.put("schema_version",1);packageRecord.put("id",old.packageId());packageRecord.put("revision",old.datasetRevision());packageRecord.put("model","pr78");packageRecord.put("components",ids);packageRecord.put("properties",refs);
            packageRecord.put("interactions","createcheme:"+suffix);packageRecord.put("missing_interactions","zero");packageRecord.put("water_model","createcheme:water");packageRecord.put("aliases",Map.of());packageRecord.put("advisory_evidence",old.advisoryEvidence());
            packageRecord.put("temperature_min_kelvin",old.minimumTemperatureKelvin());packageRecord.put("temperature_max_kelvin",old.maximumTemperatureKelvin());packageRecord.put("pressure_min_pascal",old.minimumPressurePascal());packageRecord.put("pressure_max_pascal",old.maximumPressurePascal());
            packageRecord.put("column_feed_standard_volume_m3_per_second",suffix.equals("tjl19")?.18401666666666672:.1832627693034672);
            resources.put(root+"packages/"+suffix+".json",json.toJson(packageRecord));
            String assay=suffix.equals("tjl19")?"createcheme:tia_juana_light":"createcheme:tia_juana_light_methane";
            resources.put(root+"assays/"+suffix+".json",json.toJson(Map.of("schema_version",1,"id",assay,"package",old.packageId(),"components",ids,"basis","mole","amounts",old.crudeFeed(assay).moleFractions())));
        }
        return MaterialCatalog.parse(resources);
    }
}
