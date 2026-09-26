package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/**
 * Defensive physical-state transfer object on the public component axis. This MVP accepts requested-problem
 * seeds only. Intermediate/dry-surrogate states must not be relabelled as requested solutions.
 */
public final class V3NeuralSeed {
    private final V3ColumnInput input;
    private final String propertyRevision;
    private final V3CondenserPhaseBranch branch;
    private final double[][] liquid, vapor;
    private final double[] temperatures, freeWater;
    private final boolean[] wetTrays;

    public V3NeuralSeed(V3ColumnInput input, String propertyRevision, V3CondenserPhaseBranch branch,
            double[][] liquid, double[][] vapor, double[] temperatures, double[] freeWater, boolean[] wetTrays) {
        this.input = Objects.requireNonNull(input, "input");
        this.propertyRevision = Objects.requireNonNull(propertyRevision, "propertyRevision");
        this.branch = Objects.requireNonNull(branch, "branch");
        if (propertyRevision.isBlank() || propertyRevision.length() > 128) throw new IllegalArgumentException("Invalid property revision");
        int nodes = input.stageCount() + 2, components = input.componentBasis().componentCount();
        this.liquid = copyFlows(liquid, nodes, components);
        this.vapor = copyFlows(vapor, nodes, components);
        this.temperatures = copyVector(temperatures, nodes, true);
        this.freeWater = copyVector(freeWater, nodes, false);
        if (wetTrays == null || wetTrays.length != nodes) throw new IllegalArgumentException("Invalid wet mask shape");
        this.wetTrays = wetTrays.clone();
        for (int n = 0; n < nodes; n++) {
            if ((n == 0 || n == nodes - 1) && wetTrays[n]) throw new IllegalArgumentException("Wet terminal node");
            if (!wetTrays[n] && freeWater[n] != 0.0) throw new IllegalArgumentException("Water on a dry node");
            if (input.steamFeeds().isEmpty() && (wetTrays[n] || freeWater[n] != 0.0))
                throw new IllegalArgumentException("Water seed without a water source");
        }
    }

    public V3ColumnInput input() { return input; }
    public String propertyRevision() { return propertyRevision; }
    public V3CondenserPhaseBranch branch() { return branch; }
    public double[][] liquid() { return copyFlows(liquid, liquid.length, liquid[0].length); }
    public double[][] vapor() { return copyFlows(vapor, vapor.length, vapor[0].length); }
    public double[] temperatures() { return temperatures.clone(); }
    public double[] freeWater() { return freeWater.clone(); }
    public boolean[] wetTrays() { return wetTrays.clone(); }

    V3WetTraySet wetSetFor(V3ColumnProblem problem, V3InitializationOptions.WetStart mode) {
        if (!input.equals(problem.input())) throw new IllegalArgumentException("Wet mask belongs to a different problem");
        return mode == V3InitializationOptions.WetStart.DRY_START ? V3WetTraySet.dry(problem.topology())
                : V3WetTraySet.of(problem.topology(), wetTrays);
    }

    V3DryMeshState stateFor(V3ColumnProblem problem) {
        if (!input.equals(problem.input()) || branch != problem.topology().condenserPhaseBranch())
            throw new IllegalArgumentException("Seed belongs to a different requested problem");
        int count = problem.activeComponentBasis().componentCount();
        double[][] l = new double[liquid.length][count], v = new double[liquid.length][count];
        boolean[] active = new boolean[input.componentBasis().componentCount()];
        for (int c = 0; c < count; c++) {
            int publicIndex = problem.activeComponentBasis().publicIndex(c);
            active[publicIndex] = true;
            for (int n = 0; n < l.length; n++) { l[n][c] = liquid[n][publicIndex]; v[n][c] = vapor[n][publicIndex]; }
        }
        for (int c = 0; c < active.length; c++) if (!active[c]) for (int n = 0; n < l.length; n++) {
            if (liquid[n][c] != 0 || vapor[n][c] != 0) throw new IllegalArgumentException("Flow on an inactive component");
        }
        double specifiedTemperature = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        if (Math.abs(temperatures[0] - specifiedTemperature) > 1e-9)
            throw new IllegalArgumentException("Seed changed prescribed condenser temperature");
        return new V3DryMeshState(problem.topology(), count, l, v, temperatures, freeWater);
    }

    static V3NeuralSeed capture(V3ColumnProblem problem, V3DryMeshState state, String revision) {
        int nodes = state.nodeCount(), count = problem.input().componentBasis().componentCount();
        double[][] l = new double[nodes][count], v = new double[nodes][count];
        double[] t = new double[nodes], w = new double[nodes];
        for (int n = 0; n < nodes; n++) {
            t[n] = state.temperatureKelvin(n); w[n] = state.freeWaterFlow(n);
            for (int c = 0; c < state.componentCount(); c++) {
                int publicIndex = problem.activeComponentBasis().publicIndex(c);
                l[n][publicIndex] = state.liquidFlow(n, c); v[n][publicIndex] = state.vaporFlow(n, c);
            }
        }
        boolean[] wet = new boolean[nodes];
        for (int n : problem.wetTraySet().wetTrays()) wet[n] = true;
        return new V3NeuralSeed(problem.input(), revision, problem.topology().condenserPhaseBranch(), l, v, t, w, wet);
    }

    private static double[][] copyFlows(double[][] values, int nodes, int count) {
        if (values == null || values.length != nodes) throw new IllegalArgumentException("Invalid flow node count");
        double[][] copy = new double[nodes][];
        for (int n = 0; n < nodes; n++) copy[n] = copyVector(values[n], count, false);
        return copy;
    }

    private static double[] copyVector(double[] values, int length, boolean positive) {
        if (values == null || values.length != length) throw new IllegalArgumentException("Invalid seed vector shape");
        double[] copy = values.clone();
        for (double x : copy) if (!Double.isFinite(x) || (positive ? x <= 0 : x < 0))
            throw new IllegalArgumentException("Nonfinite or inadmissible seed value");
        return copy;
    }
}
