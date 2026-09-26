package com.wormzjl.createcheme.science.column.v3.thermo;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.*;

/** Offline fixture importing DWSIM data into the unchanged V3 kernel. Not a runtime package loader. */
public final class V3NativeThermoFixture {
    private V3NativeThermoFixture() {}

    public static V3PengRobinsonThermo create(JsonObject external) throws ReflectiveOperationException {
        var descriptors = new ArrayList<V3PropertyComponent>();
        var nativeComponents = external.getAsJsonArray("native_compound_properties");
        for (int i = 0; i < nativeComponents.size(); i++) {
            JsonObject p = nativeComponents.get(i).getAsJsonObject();
            if (!p.get("IdealgasCpEquation").getAsString().equals("3") || !p.get("CurrentDB").getAsString().equals("CoolProp"))
                throw new IllegalArgumentException("Only the measured native CoolProp quadratic Cp convention is qualified");
            double mw = number(p, "Molar_Weight");
            double a = number(p, "Ideal_Gas_Heat_Capacity_Const_A");
            double b = number(p, "Ideal_Gas_Heat_Capacity_Const_B");
            double c = number(p, "Ideal_Gas_Heat_Capacity_Const_C");
            if (number(p, "Ideal_Gas_Heat_Capacity_Const_D") != 0 || number(p, "Ideal_Gas_Heat_Capacity_Const_E") != 0)
                throw new IllegalArgumentException("Unexpected higher Cp terms");
            String id = p.get("Name").getAsString();
            for (JsonElement check : external.getAsJsonArray("package_cp_checks")) {
                JsonObject row = check.getAsJsonObject();
                if (row.get("id").getAsString().equals(id)) {
                    double t = number(row, "temperature_K");
                    if (Math.abs(a + b*t + c*t*t - number(row, "cp_kJ_kg_K")) > 1e-10)
                        throw new IllegalArgumentException("Cp mapping does not reproduce DWSIM for " + id);
                }
            }
            descriptors.add(new V3PropertyComponent(id, id, mw / 1000, number(p, "Normal_Boiling_Point"),
                    number(p, "Critical_Temperature"), number(p, "Critical_Pressure"), number(p, "Acentric_Factor"),
                    external.getAsJsonArray("reference_liquid_density_kg_m3").get(i).getAsDouble(),
                    mw * (a + b*298.15 + c*298.15*298.15), mw * (b + 2*c*298.15), mw*c, 0, false));
        }
        var basis = new V3ComponentBasis(descriptors.stream().map(V3PropertyComponent::id).toList());
        double[][] interactions = new Gson().fromJson(external.get("binary_interactions"), double[][].class);
        V3PropertyPackage pkg = new V3PropertyPackage() {
            public String packageId() { return "test:dwsim-native-binary"; }
            public String datasetRevision() { return "dwsim-" + external.get("engine_version").getAsString(); }
            public V3ComponentBasis componentBasis() { return basis; }
            public V3PropertyComponent component(int i) { return descriptors.get(i); }
            public V3CrudeFeed crudeFeed(String id) { throw new UnsupportedOperationException("Not a crude assay"); }
            public double minimumTemperatureKelvin() { return 298.15; }
            public double maximumTemperatureKelvin() { return 600; }
            public double minimumPressurePascal() { return 50000; }
            public double maximumPressurePascal() { return 2000000; }
            public double[][] binaryInteractions() { return Arrays.stream(interactions).map(double[]::clone).toArray(double[][]::new); }
        };
        // Research-only construction avoids modifying the production registry or public API.
        var sessionConstructor = V3PengRobinsonSession.class.getDeclaredConstructor(V3PropertyPackage.class);
        sessionConstructor.setAccessible(true);
        var thermoConstructor = V3PengRobinsonThermo.class.getDeclaredConstructor(V3PengRobinsonSession.class);
        thermoConstructor.setAccessible(true);
        V3PengRobinsonThermo thermo = thermoConstructor.newInstance(sessionConstructor.newInstance(pkg));
        JsonObject input = external.getAsJsonObject("input");
        input.addProperty("package_id", pkg.packageId());
        input.addProperty("dataset_revision", pkg.datasetRevision());
        input.add("components", new Gson().toJsonTree(descriptors));
        if (external.has("profile")) {
            input.addProperty("condenser_temperature_K", external.getAsJsonObject("profile").getAsJsonArray("Tf").get(0).getAsDouble());
            input.addProperty("reboiler_duty_W", -1000 * number(external.getAsJsonObject("profile"), "reboiler_duty_kW"));
        }
        return thermo;
    }

    private static double number(JsonObject value, String key) { return value.get(key).getAsDouble(); }
}
