package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Constructs a finite dry-MESH seed and evidence only; it has no public success/result publication path. */
final class V3ColumnInitializer {
    private static final double TWO_PHASE_EXTERNAL_LIQUID_FRACTION = 0.20;
    private static final double TWO_PHASE_EXTERNAL_VAPOR_FRACTION = 0.10;
    private static final double VAPOR_ONLY_EXTERNAL_VAPOR_FRACTION = 0.30;
    private static final double ZERO_REFLUX_TRAFFIC_FRACTION = 0.10;

    private V3ColumnInitializer() {}

    static Seed initialize(V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (!problem.input().componentBasis().equals(thermo.componentBasis())) {
            throw new IllegalArgumentException("V3 initializer thermodynamic basis differs from the resolved problem basis");
        }
        int components = problem.input().componentBasis().componentCount();
        double[] feed = problem.input().feedComponentMolarFlowsMolPerSecond();
        double totalFeed = 0.0;
        for (double flow : feed) {
            if (flow <= 0.0) {
                throw new IllegalArgumentException("V3 initializer requires an active-basis map before it can seed an exact-zero feed component");
            }
            totalFeed += flow;
        }
        double[] feedComposition = new double[components];
        for (int component = 0; component < components; component++) feedComposition[component] = feed[component] / totalFeed;
        int feedNode = problem.topology().feedTrayNumber();
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(), problem.nodePressurePascal(feedNode),
                feedComposition, workspace);
        double refluxRatio = specification(problem, V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = temperatures(problem, nodes);

        for (int component = 0; component < components; component++) {
            double externalLiquid = topology.hasLiquidPhase(0) ? TWO_PHASE_EXTERNAL_LIQUID_FRACTION * feed[component] : 0.0;
            double externalVapor = (topology.hasLiquidPhase(0) ? TWO_PHASE_EXTERNAL_VAPOR_FRACTION
                    : VAPOR_ONLY_EXTERNAL_VAPOR_FRACTION) * feed[component];
            if (topology.hasLiquidPhase(0)) liquid[0][component] = (1.0 + refluxRatio) * externalLiquid;
            vapor[0][component] = externalVapor;
            vapor[1][component] = vapor[0][component] + liquid[0][component];

            double reflux = refluxRatio * externalLiquid;
            double traffic = refluxRatio > 0.0 ? reflux : ZERO_REFLUX_TRAFFIC_FRACTION * feed[component];
            double previousLiquid = traffic;
            for (int tray = 1; tray <= topology.trayCount(); tray++) {
                double feedAtTray = tray == topology.feedTrayNumber() ? feed[component] : 0.0;
                liquid[tray][component] = previousLiquid + feedAtTray;
                double liquidIn = tray == 1 ? reflux : previousLiquid;
                vapor[tray + 1][component] = liquid[tray][component] + vapor[tray][component] - liquidIn - feedAtTray;
                if (!Double.isFinite(vapor[tray + 1][component]) || vapor[tray + 1][component] <= 0.0) {
                    throw new IllegalArgumentException("V3 initializer generated a nonpositive stage vapor seed");
                }
                previousLiquid = liquid[tray][component];
            }
            liquid[topology.reboilerNode()][component] = liquid[topology.trayCount()][component]
                    - vapor[topology.reboilerNode()][component];
            if (!Double.isFinite(liquid[topology.reboilerNode()][component])
                    || liquid[topology.reboilerNode()][component] <= 0.0) {
                throw new IllegalArgumentException("V3 initializer generated a nonpositive bottoms seed");
            }
        }
        V3DryMeshState state = new V3DryMeshState(topology, components, liquid, vapor, temperatures);
        return new Seed(state, new Evidence(feedFlash.phase().name(), feedFlash.vaporFraction(),
                refluxRatio == 0.0 ? "zero-reflux finite traffic seed" : "organic-reflux material-closed traffic seed"));
    }

    private static double[] temperatures(V3ColumnProblem problem, int nodes) {
        double condenserTemperature = specification(problem, V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            temperatures[node] = condenserTemperature + (problem.input().feedTemperatureKelvin() - condenserTemperature)
                    * node / (nodes - 1.0);
        }
        return temperatures;
    }

    private static <T extends V3ColumnSpecification> T specification(V3ColumnProblem problem, Class<T> type) {
        return problem.input().specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 initializer problem is missing " + type.getSimpleName()));
    }

    record Seed(V3DryMeshState state, Evidence evidence) {
        Seed {
            state = Objects.requireNonNull(state, "state");
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Evidence(String feedPhase, double feedVaporFraction, String trafficPolicy) {
        Evidence {
            if (feedPhase == null || feedPhase.isBlank() || !Double.isFinite(feedVaporFraction)
                    || feedVaporFraction < 0.0 || feedVaporFraction > 1.0 || trafficPolicy == null || trafficPolicy.isBlank()) {
                throw new IllegalArgumentException("V3 initializer evidence is invalid");
            }
        }
    }
}
