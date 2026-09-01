package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Scan-verified transcription of Holland (1981), Example 3-2 and Tables B-1/B-2. */
final class HollandExample32Data {
    static final double LB_MOL_PER_HOUR_TO_MOL_PER_SECOND = 453.59237 / 3_600.0;
    static final double BTU_PER_HOUR_TO_WATT = 1_055.05585262 / 3_600.0;
    static final double PSI_TO_PASCAL = 6_894.757293168;
    static final double[] K_SCALES = {1.0e-2, 1.0e-5, 1.0e-8, 1.0e-12};
    static final double[] LIQUID_ENTHALPY_SCALES = {1.0, 1.0e-1, 1.0e-5};
    static final double[] VAPOR_ENTHALPY_SCALES = {1.0, 1.0e-4, 1.0e-6};
    static final HollandExample32Data INSTANCE = load();

    private final Fixture fixture;
    private final V3ComponentBasis basis;

    private HollandExample32Data(Fixture fixture) {
        this.fixture = Objects.requireNonNull(fixture, "fixture");
        require(fixture.schemaVersion() == 1, "unsupported fixture schema");
        require("holland-1981-example-3-2".equals(fixture.fixtureId()), "unexpected fixture id");
        require("HOLLAND_1981_PAGE_SCAN".equals(fixture.authority()), "fixture authority is not the page scan");
        require(fixture.components() != null && fixture.components().size() == 11, "Example 3-2 needs 11 components");
        require(fixture.v3FeedTrayNumber() == 4 && fixture.v3SideDrawTrayNumber() == 9,
                "scan-reconciled V3 stage mapping changed");
        for (Component component : fixture.components()) {
            require(component != null && component.feed() > 0.0, "invalid Holland component feed");
            requireLength(component.kScaled(), 4, component.id() + " K coefficients");
            requireLength(component.liquidHScaled(), 3, component.id() + " liquid enthalpy coefficients");
            requireLength(component.vaporHScaled(), 3, component.id() + " vapor enthalpy coefficients");
            requireLength(component.products(), 3, component.id() + " product targets");
        }
        PublishedSolution solution = fixture.publishedSolution();
        require(solution != null, "missing Holland solution");
        requireLength(solution.temperatureFahrenheit(), 13, "temperature profile");
        requireLength(solution.reportedTable3TemperatureFahrenheit(), 13, "raw temperature profile");
        requireLength(solution.v3LiquidStateLbMolPerHour(), 13, "V3 liquid profile");
        requireLength(solution.reportedTable3LiquidLbMolPerHour(), 13, "raw liquid profile");
        requireLength(solution.vaporLbMolPerHour(), 13, "vapor profile");
        basis = new V3ComponentBasis(fixture.components().stream().map(Component::id).toList());
    }

    V3ComponentBasis basis() {
        return basis;
    }

    int componentCount() {
        return fixture.components().size();
    }

    Component component(int index) {
        return fixture.components().get(index);
    }

    double[] feedLbMolPerHour() {
        return fixture.components().stream().mapToDouble(Component::feed).toArray();
    }

    double[] feedMolPerSecond() {
        double[] feed = feedLbMolPerHour();
        for (int component = 0; component < feed.length; component++) {
            feed[component] *= LB_MOL_PER_HOUR_TO_MOL_PER_SECOND;
        }
        return feed;
    }

    double[][] kCoefficients() {
        return coefficients(Component::kScaled, K_SCALES);
    }

    double[][] liquidEnthalpyCoefficients() {
        return coefficients(Component::liquidHScaled, LIQUID_ENTHALPY_SCALES);
    }

    double[][] vaporEnthalpyCoefficients() {
        return coefficients(Component::vaporHScaled, VAPOR_ENTHALPY_SCALES);
    }

    double[][] productTargetsLbMolPerHour() {
        double[][] targets = new double[3][componentCount()];
        for (int component = 0; component < componentCount(); component++) {
            for (int product = 0; product < targets.length; product++) {
                targets[product][component] = component(component).products()[product];
            }
        }
        return targets;
    }

    double pressurePascal() {
        return fixture.basePressurePsia() * PSI_TO_PASCAL;
    }

    int feedTray() {
        return fixture.v3FeedTrayNumber();
    }

    int sideDrawTray() {
        return fixture.v3SideDrawTrayNumber();
    }

    double sideDrawMolPerSecond() {
        return fixture.sideDrawLbMolPerHour() * LB_MOL_PER_HOUR_TO_MOL_PER_SECOND;
    }

    double publishedDistillateLbMolPerHour() {
        return fixture.publishedDistillateLbMolPerHour();
    }

    double publishedRefluxRatio() {
        return fixture.publishedRefluxRatioToVaporDistillate();
    }

    PublishedSolution solution() {
        return fixture.publishedSolution();
    }

    Source source() {
        return fixture.source();
    }

    static double kelvinFromFahrenheit(double fahrenheit) {
        return (fahrenheit - 32.0) / 1.8 + 273.15;
    }

    static double fahrenheitFromKelvin(double kelvin) {
        return (kelvin - 273.15) * 1.8 + 32.0;
    }

    private double[][] coefficients(java.util.function.Function<Component, double[]> values, double[] scales) {
        double[][] coefficients = new double[componentCount()][scales.length];
        for (int component = 0; component < componentCount(); component++) {
            double[] scaled = values.apply(component(component));
            for (int term = 0; term < scales.length; term++) coefficients[component][term] = scaled[term] * scales[term];
        }
        return coefficients;
    }

    private static HollandExample32Data load() {
        try (var stream = HollandExample32Data.class.getResourceAsStream("/column/v3/holland-example-3-2.json")) {
            if (stream == null) throw new IllegalStateException("Holland Example 3-2 fixture is missing");
            Fixture fixture = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Fixture.class);
            return new HollandExample32Data(fixture);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Holland Example 3-2 fixture", exception);
        }
    }

    private static void requireLength(double[] values, int expected, String name) {
        require(values != null && values.length == expected && Arrays.stream(values).allMatch(Double::isFinite),
                name + " is invalid");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    record Fixture(
            int schemaVersion, String fixtureId, String authority, String description, Source source,
            double basePressurePsia, int feedPlateNumber, int v3FeedTrayNumber,
            int sideDrawPlateNumber, int v3SideDrawTrayNumber, double sideDrawLbMolPerHour,
            double publishedDistillateLbMolPerHour, double publishedRefluxRatioToVaporDistillate,
            List<Component> components, PublishedSolution publishedSolution) {}

    record Source(
            String title, String author, String publisher, int year, String archiveItem,
            int[] verifiedPrintedPages, List<String> notes) {}

    record Component(
            String id, double feed, double[] kScaled, double[] liquidHScaled,
            double[] vaporHScaled, double[] products) {}

    record PublishedSolution(
            double[] temperatureFahrenheit, double[] reportedTable3TemperatureFahrenheit,
            double[] v3LiquidStateLbMolPerHour, double[] reportedTable3LiquidLbMolPerHour,
            double[] vaporLbMolPerHour, double condenserDutyBtuPerHour, double reboilerDutyBtuPerHour) {}
}
