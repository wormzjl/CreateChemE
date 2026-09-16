package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent NIST checkpoints. Writes all measured errors before evaluating the qualification gate. */
class LiquidCompressionQualificationTest {
    @Test void recordsThePrCompressionAuditAndChecksCalibratedDensity() throws Exception {
        var components = List.of(
                TranslatedPengRobinsonTest.BUTANE,
                new ThermoComponent("n-pentane",469.7,3370000,.251,.07214878),
                new ThermoComponent("n-decane",617.7,2103000,.488,.14228168));
        var results = new ArrayList<Result>();
        for (var component : components) {
            var rows = reference(component.id());
            double referenceVolume = rows.get(1)[3] * .001;
            double referenceSlope = (rows.get(2)[3]-rows.get(0)[3])*.001 / ((rows.get(2)[1]-rows.get(0)[1])*1000);
            double referenceCompression = -referenceSlope/referenceVolume;
            var raw = TranslatedPengRobinsonTest.model(component,0);
            double shift = referenceVolume - raw.evaluate(300,1e6,new double[] {1},PhaseRoot.LIQUID).molarVolume();
            var translated = TranslatedPengRobinsonTest.model(component,shift);
            var prediction = translated.evaluate(300,1e6,new double[] {1},PhaseRoot.LIQUID);
            double worstDensityError = 0;
            for (double[] row : rows) {
                double v = translated.evaluate(row[0],row[1]*1000,new double[] {1},PhaseRoot.LIQUID).molarVolume();
                worstDensityError = Math.max(worstDensityError,Math.abs((row[3]*.001)/v-1));
            }
            results.add(new Result(component.id(),shift,referenceCompression,prediction.isothermalCompressibility(),
                    Math.abs(prediction.isothermalCompressibility()/referenceCompression-1),worstDensityError));
        }
        Path report = Path.of("build/reports/fluid/M1-liquid-compression.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(results));
        assertAll(results.stream().map(result -> () -> {
            // User superseded the material-specific 20% gate with a shared liquid compression approximation.
            // Keep the measured error in the report; do not claim that native PR compression is now qualified.
            assertTrue(result.predictedCompressibility > 0 && Double.isFinite(result.predictedCompressibility));
            assertTrue(result.worstDensityRelativeError <= .05, result.component + " density relative error=" + result.worstDensityRelativeError);
        }));
    }

    private static List<double[]> reference(String component) throws Exception {
        String resource = "/fluid/reference/" + component + "-300K.tsv";
        try (var input = LiquidCompressionQualificationTest.class.getResourceAsStream(resource)) {
            assertNotNull(input,resource);
            var lines = new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().skip(1).filter(s -> !s.isBlank()).toList();
            var result = new ArrayList<double[]>();
            for (String line : lines) {
                String[] fields = line.split("\t");
                assertEquals("liquid",fields[13]);
                result.add(new double[] {Double.parseDouble(fields[0]),Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]),Double.parseDouble(fields[3])});
            }
            assertEquals(3,result.size());
            return result;
        }
    }

    private record Result(String component,double translationCubicMetresPerMole,double referenceCompressibility,
                          double predictedCompressibility,double relativeCompressionError,double worstDensityRelativeError) {}
}
