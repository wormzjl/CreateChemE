package com.wormzjl.createcheme.science.column.v3.thermo;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Research-only export compiled beside the science sources, outside the mod artifact. */
public final class V3PackageExport {
    private V3PackageExport() {}

    public static void main(String[] args) throws Exception {
        var pkg = V3PropertyPackageRegistry.require("createcheme:cdu17_tjl_acs2018");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", 1);
        result.put("package_id", pkg.packageId());
        result.put("dataset_revision", pkg.datasetRevision());
        result.put("public_component_ids", pkg.componentBasis().componentIds());
        var components = new ArrayList<V3PropertyComponent>();
        components.add(pkg.component(6));
        components.add(pkg.component(13));
        result.put("components", components);
        result.put("binary_interactions", new double[][] {{0, 0}, {0, 0}});
        result.put("feed_mol_s", new double[] {50, 50});
        result.put("feed_temperature_K", 550);
        result.put("trays", 2);
        result.put("feed_tray", 1);
        result.put("top_pressure_Pa", 250000);
        result.put("stage_pressure_drop_Pa", 750);
        result.put("condenser_temperature_K", 300);
        result.put("reflux_ratio", 2);
        result.put("reboiler_duty_W", 0);
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
