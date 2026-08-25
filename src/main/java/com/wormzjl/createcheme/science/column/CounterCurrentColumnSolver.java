package com.wormzjl.createcheme.science.column;

import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnInput;
import com.wormzjl.createcheme.science.thermo.FlashResult;
import com.wormzjl.createcheme.science.thermo.PressureVaporFractionFlashSolver;
import com.wormzjl.createcheme.science.thermo.TpFlashSolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Counter-current equilibrium cascade with constant-molar-overflow traffic. A TP flash converts the
 * feed temperature into feed quality, which changes the traffic above and below the feed stage. Each
 * stage then solves an isobaric Peng-Robinson flash at its specified vapor traffic. Final product
 * allocation preserves the equilibrium-derived ordering while enforcing exact overall material
 * balances. This model does not solve the column energy equations.
 */
public final class CounterCurrentColumnSolver {
    public static final String SOLVER_REVISION = "pr-isobaric-feed-quality-cascade-approx-v2";
    public static final int MAXIMUM_SWEEPS = 400;
    public static final double COMPOSITION_TOLERANCE = 2.0e-3;
    public static final double STAGE_COMPONENT_TOLERANCE = 5.0e-4;

    private static final double DAMPING = 1.0;
    private static final double MINIMUM_WEIGHT = 1.0e-14;

    private final PressureVaporFractionFlashSolver flashSolver =
            new PressureVaporFractionFlashSolver(
                    TiaJuanaLight12PropertyPackage.equationOfState());
    private final TpFlashSolver feedFlashSolver =
            new TpFlashSolver(TiaJuanaLight12PropertyPackage.equationOfState());

    public Result solve(ColumnInput input) {
        int stageCount = input.stageCount();
        int componentCount = TiaJuanaLight12PropertyPackage.componentCount();
        double[] feed = TiaJuanaLight12PropertyPackage.feedMoleFractions();
        double feedVaporFraction = feedFlashSolver.solve(
                        input.feedTemperatureKelvin(),
                        ColumnSimulation.MILESTONE_1.uniformPressurePascal(),
                        feed)
                .vaporFraction();
        double referenceFeedVaporFraction = feedFlashSolver.solve(
                        638.15,
                        ColumnSimulation.MILESTONE_1.uniformPressurePascal(),
                        feed)
                .vaporFraction();
        ColumnTraffic traffic =
                columnTraffic(input, feedVaporFraction, referenceFeedVaporFraction);
        double[] productFlows = traffic.productFlows();
        double[] temperatures = temperatureProfile(input);
        double[] vaporFlows = traffic.vaporFlows();
        double[] liquidFlows = traffic.liquidFlows();

        double[][] liquid = new double[stageCount][componentCount];
        double[][] vapor = new double[stageCount][componentCount];
        for (int stage = 0; stage < stageCount; stage++) {
            liquid[stage] = feed.clone();
            vapor[stage] = feed.clone();
        }

        double maximumChange = Double.POSITIVE_INFINITY;
        double maximumEquilibriumResidual = 0.0;
        double maximumVaporFractionResidual = Double.POSITIVE_INFINITY;
        double maximumStageComponentResidual = Double.POSITIVE_INFINITY;
        int propertyEvaluations = 0;
        int completedSweeps = 0;
        for (int sweep = 1; sweep <= MAXIMUM_SWEEPS; sweep++) {
            maximumChange = 0.0;
            maximumEquilibriumResidual = 0.0;
            maximumVaporFractionResidual = 0.0;
            for (int index = 0; index < stageCount; index++) {
                int stage = stageCount - 1 - index;
                StageFeed stageFeed = mixedStageFeed(
                        input,
                        stage,
                        feed,
                        productFlows[0],
                        vaporFlows,
                        liquidFlows,
                        liquid,
                        vapor);
                double targetVaporFraction = vaporFlows[stage] / stageFeed.totalMolarFlow();
                PressureVaporFractionFlashSolver.Result stageFlash = flashSolver.solve(
                        ColumnSimulation.MILESTONE_1.uniformPressurePascal(),
                        targetVaporFraction,
                        stageFeed.moleFractions(),
                        ColumnSimulation.MILESTONE_1.minTemperatureKelvin(),
                        ColumnSimulation.MILESTONE_1.maxTemperatureKelvin(),
                        temperatures[stage]);
                FlashResult flash = stageFlash.equilibrium();
                propertyEvaluations += stageFlash.propertyEvaluations();
                temperatures[stage] = stageFlash.temperatureKelvin();
                maximumVaporFractionResidual = Math.max(
                        maximumVaporFractionResidual,
                        Math.abs(stageFlash.vaporFractionResidual()));
                maximumEquilibriumResidual = Math.max(
                        maximumEquilibriumResidual, flash.maximumLogFugacityResidual());
                maximumChange = Math.max(
                        maximumChange,
                        damp(liquid[stage], flash.liquidMoleFractions()));
                maximumChange = Math.max(
                        maximumChange,
                        damp(vapor[stage], flash.vaporMoleFractions()));
            }
            completedSweeps = sweep;
            maximumStageComponentResidual = maximumRelativeStageComponentResidual(
                    input, feed, productFlows[0], vaporFlows, liquidFlows, liquid, vapor);
            if (maximumChange <= COMPOSITION_TOLERANCE
                    && maximumStageComponentResidual <= STAGE_COMPONENT_TOLERANCE
                    && maximumVaporFractionResidual
                            <= PressureVaporFractionFlashSolver.DEFAULT_VAPOR_FRACTION_TOLERANCE) {
                break;
            }
        }
        double[][] candidateProducts = candidateProductCompositions(input, liquid, vapor);
        double[][] componentFlows = scaleProducts(
                input.feedMolarFlowMolPerSecond(), feed, productFlows, candidateProducts);
        boolean converged = maximumChange <= COMPOSITION_TOLERANCE
                && maximumVaporFractionResidual
                        <= PressureVaporFractionFlashSolver.DEFAULT_VAPOR_FRACTION_TOLERANCE
                && maximumStageComponentResidual <= STAGE_COMPONENT_TOLERANCE;
        return new Result(
                converged,
                completedSweeps,
                propertyEvaluations,
                maximumChange,
                maximumEquilibriumResidual,
                maximumVaporFractionResidual,
                maximumStageComponentResidual,
                productFlows,
                componentFlows,
                temperatures,
                liquidFlows,
                vaporFlows,
                feedVaporFraction,
                liquid,
                vapor);
    }

    private static StageFeed mixedStageFeed(
            ColumnInput input,
            int stage,
            double[] feed,
            double distillateFlow,
            double[] vaporFlows,
            double[] liquidFlows,
            double[][] liquid,
            double[][] vapor) {
        int lastStage = input.stageCount() - 1;
        double refluxFlow = input.refluxRatio() * distillateFlow;
        double liquidInFlow = stage == 0 ? refluxFlow : liquidFlows[stage - 1];
        double vaporInFlow = stage == lastStage ? 0.0 : vaporFlows[stage + 1];
        double feedFlow = stage == input.feedStage() - 1
                ? input.feedMolarFlowMolPerSecond()
                : 0.0;
        double total = liquidInFlow + vaporInFlow + feedFlow;
        double[] mixed = new double[feed.length];
        double[] liquidIn = stage == 0 ? vapor[0] : liquid[stage - 1];
        double[] vaporIn = stage == lastStage ? feed : vapor[stage + 1];
        for (int component = 0; component < mixed.length; component++) {
            mixed[component] = (liquidInFlow * liquidIn[component]
                    + vaporInFlow * vaporIn[component]
                    + feedFlow * feed[component]) / total;
        }
        return new StageFeed(total, mixed);
    }

    private static double damp(double[] current, double[] target) {
        double maximumChange = 0.0;
        for (int component = 0; component < current.length; component++) {
            double updated = current[component]
                    + DAMPING * (target[component] - current[component]);
            maximumChange = Math.max(maximumChange, Math.abs(updated - current[component]));
            current[component] = updated;
        }
        normalize(current);
        return maximumChange;
    }

    private static ColumnTraffic columnTraffic(
            ColumnInput input,
            double feedVaporFraction,
            double referenceFeedVaporFraction) {
        double sideTotal = input.sideDraws().stream()
                .mapToDouble(ColumnSimulation.SideDrawSpec::molarFlowMolPerSecond)
                .sum();
        double remaining = input.feedMolarFlowMolPerSecond() - sideTotal;
        double specificDuty = input.reboilerDutyWatts() / input.feedMolarFlowMolPerSecond();
        double dutyResponse = Math.tanh((specificDuty - 40_000.0) / 80_000.0);
        double refluxResponse = Math.log1p(input.refluxRatio()) / Math.log(101.0);
        double topShare = clamp(0.34 + 0.12 * dutyResponse + 0.12 * refluxResponse, 0.08, 0.80);
        double baseDistillateFlow = remaining * topShare;
        double trafficFeedVaporFraction = Math.min(
                feedVaporFraction,
                0.90 * (1.0 + input.refluxRatio()) * remaining
                        / input.feedMolarFlowMolPerSecond());
        double upperSideFlow = input.sideDraws().stream()
                .filter(side -> side.stage() < input.feedStage())
                .mapToDouble(ColumnSimulation.SideDrawSpec::molarFlowMolPerSecond)
                .sum();
        double sideDrawDistillateFloor = upperSideFlow > 0.0 && input.refluxRatio() > 0.0
                ? (upperSideFlow + 0.001 * remaining) / input.refluxRatio()
                : 0.0;
        double minimumDistillateFlow = Math.max(
                0.08 * remaining,
                Math.max(
                        trafficFeedVaporFraction * input.feedMolarFlowMolPerSecond()
                                        / (1.0 + input.refluxRatio())
                                + 0.01 * remaining,
                        sideDrawDistillateFloor));
        double requestedDistillateFlow = baseDistillateFlow
                + (trafficFeedVaporFraction - referenceFeedVaporFraction)
                        * input.feedMolarFlowMolPerSecond()
                        / (1.0 + input.refluxRatio());

        double[] productFlows = new double[input.sideDraws().size() + 2];
        productFlows[0] = clamp(
                requestedDistillateFlow, minimumDistillateFlow, 0.92 * remaining);
        for (int side = 0; side < input.sideDraws().size(); side++) {
            productFlows[side + 1] = input.sideDraws().get(side).molarFlowMolPerSecond();
        }
        productFlows[productFlows.length - 1] = remaining - productFlows[0];

        double rectifyingVaporFlow = (1.0 + input.refluxRatio()) * productFlows[0];
        double strippingVaporFlow =
                rectifyingVaporFlow - trafficFeedVaporFraction * input.feedMolarFlowMolPerSecond();
        double[] vaporFlows = new double[input.stageCount()];
        for (int stage = 0; stage < vaporFlows.length; stage++) {
            vaporFlows[stage] = stage <= input.feedStage() - 1
                    ? rectifyingVaporFlow
                    : strippingVaporFlow;
        }
        double[] liquidFlows = liquidFlows(input, productFlows, trafficFeedVaporFraction);
        return new ColumnTraffic(productFlows, liquidFlows, vaporFlows);
    }

    private static double[] temperatureProfile(ColumnInput input) {
        double topTemperature = input.refluxCondition().temperatureKelvin()
                .orElse(clamp(input.feedTemperatureKelvin() - 150.0, 250.0, 520.0));
        double specificDuty = input.reboilerDutyWatts() / input.feedMolarFlowMolPerSecond();
        double bottomTemperature = clamp(
                input.feedTemperatureKelvin() + 50.0 + specificDuty / 200_000.0,
                topTemperature + 20.0,
                Math.max(topTemperature + 20.0, 1_000.0));
        double[] temperatures = new double[input.stageCount()];
        for (int stage = 0; stage < temperatures.length; stage++) {
            double position = stage / (double) (temperatures.length - 1);
            double smoothPosition = position * position * (3.0 - 2.0 * position);
            temperatures[stage] = topTemperature
                    + smoothPosition * (bottomTemperature - topTemperature);
        }
        return temperatures;
    }

    private static double[] liquidFlows(
            ColumnInput input, double[] productFlows, double feedVaporFraction) {
        double[] flows = new double[input.stageCount()];
        double refluxFlow = input.refluxRatio() * productFlows[0];
        double cumulativeSideFlow = 0.0;
        int nextSide = 0;
        for (int stage = 0; stage < flows.length - 1; stage++) {
            while (nextSide < input.sideDraws().size()
                    && input.sideDraws().get(nextSide).stage() - 1 <= stage) {
                cumulativeSideFlow += input.sideDraws().get(nextSide).molarFlowMolPerSecond();
                nextSide++;
            }
            flows[stage] = refluxFlow
                    + (stage >= input.feedStage() - 1
                            ? (1.0 - feedVaporFraction) * input.feedMolarFlowMolPerSecond()
                            : 0.0)
                    - cumulativeSideFlow;
        }
        flows[flows.length - 1] = productFlows[productFlows.length - 1];
        return flows;
    }

    private static double maximumRelativeStageComponentResidual(
            ColumnInput input,
            double[] feed,
            double distillateFlow,
            double[] vaporFlows,
            double[] liquidFlows,
            double[][] liquid,
            double[][] vapor) {
        double refluxFlow = input.refluxRatio() * distillateFlow;
        double maximumResidual = 0.0;
        for (int stage = 0; stage < input.stageCount(); stage++) {
            double liquidInFlow = stage == 0 ? refluxFlow : liquidFlows[stage - 1];
            double vaporInFlow =
                    stage == input.stageCount() - 1 ? 0.0 : vaporFlows[stage + 1];
            double vaporOutFlow = vaporFlows[stage];
            double feedFlow = stage == input.feedStage() - 1
                    ? input.feedMolarFlowMolPerSecond()
                    : 0.0;
            double liquidOutFlow = liquidInFlow + vaporInFlow + feedFlow - vaporOutFlow;
            double[] liquidIn = stage == 0 ? vapor[0] : liquid[stage - 1];
            double[] vaporIn = stage == input.stageCount() - 1 ? feed : vapor[stage + 1];
            for (int component = 0; component < feed.length; component++) {
                double inlet = liquidInFlow * liquidIn[component]
                        + vaporInFlow * vaporIn[component]
                        + feedFlow * feed[component];
                double outlet = liquidOutFlow * liquid[stage][component]
                        + vaporOutFlow * vapor[stage][component];
                maximumResidual = Math.max(
                        maximumResidual,
                        Math.abs(outlet - inlet) / (liquidOutFlow + vaporOutFlow));
            }
        }
        return maximumResidual;
    }
    private static double[][] candidateProductCompositions(
            ColumnInput input, double[][] liquid, double[][] vapor) {
        double[][] products = new double[input.sideDraws().size() + 2][];
        products[0] = vapor[0].clone();
        for (int side = 0; side < input.sideDraws().size(); side++) {
            products[side + 1] = liquid[input.sideDraws().get(side).stage() - 1].clone();
        }
        products[products.length - 1] = liquid[liquid.length - 1].clone();
        return products;
    }

    private static double[][] scaleProducts(
            double feedFlow,
            double[] feedFractions,
            double[] productFlows,
            double[][] candidateProducts) {
        double[][] allocation = new double[feedFractions.length][productFlows.length];
        for (int component = 0; component < allocation.length; component++) {
            for (int product = 0; product < productFlows.length; product++) {
                allocation[component][product] = candidateProducts[product][component]
                                * productFlows[product]
                        + MINIMUM_WEIGHT;
            }
        }
        for (int iteration = 0; iteration < 1_024; iteration++) {
            for (int component = 0; component < allocation.length; component++) {
                double rowSum = 0.0;
                for (double value : allocation[component]) {
                    rowSum += value;
                }
                double scale = feedFlow * feedFractions[component] / rowSum;
                for (int product = 0; product < productFlows.length; product++) {
                    allocation[component][product] *= scale;
                }
            }
            for (int product = 0; product < productFlows.length; product++) {
                double columnSum = 0.0;
                for (double[] componentAllocation : allocation) {
                    columnSum += componentAllocation[product];
                }
                double scale = productFlows[product] / columnSum;
                for (double[] componentAllocation : allocation) {
                    componentAllocation[product] *= scale;
                }
            }
        }
        return allocation;
    }

    private static void normalize(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        for (int i = 0; i < values.length; i++) {
            values[i] /= sum;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record StageFeed(double totalMolarFlow, double[] moleFractions) {}

    private record ColumnTraffic(
            double[] productFlows, double[] liquidFlows, double[] vaporFlows) {}

    public record Result(
            boolean converged,
            int sweeps,
            int propertyEvaluations,
            double maximumCompositionChange,
            double maximumEquilibriumResidual,
            double maximumVaporFractionResidual,
            double maximumRelativeStageComponentResidual,
            double[] productFlows,
            double[][] componentFlows,
            double[] temperatures,
            double[] liquidFlows,
            double[] vaporFlows,
            double feedVaporFraction,
            double[][] liquidCompositions,
            double[][] vaporCompositions) {
        public Result {
            productFlows = productFlows.clone();
            componentFlows = copy(componentFlows);
            temperatures = temperatures.clone();
            liquidFlows = liquidFlows.clone();
            vaporFlows = vaporFlows.clone();
            liquidCompositions = copy(liquidCompositions);
            vaporCompositions = copy(vaporCompositions);
        }

        @Override
        public double[] productFlows() {
            return productFlows.clone();
        }

        @Override
        public double[][] componentFlows() {
            return copy(componentFlows);
        }

        @Override
        public double[] temperatures() {
            return temperatures.clone();
        }

        @Override
        public double[] liquidFlows() {
            return liquidFlows.clone();
        }

        @Override
        public double[] vaporFlows() {
            return vaporFlows.clone();
        }

        public double vaporFlow() {
            return vaporFlows[0];
        }

        @Override
        public double[][] liquidCompositions() {
            return copy(liquidCompositions);
        }

        @Override
        public double[][] vaporCompositions() {
            return copy(vaporCompositions);
        }

        private static double[][] copy(double[][] values) {
            List<double[]> copy = new ArrayList<>(values.length);
            for (double[] value : values) {
                copy.add(value.clone());
            }
            return copy.toArray(double[][]::new);
        }
    }
}
