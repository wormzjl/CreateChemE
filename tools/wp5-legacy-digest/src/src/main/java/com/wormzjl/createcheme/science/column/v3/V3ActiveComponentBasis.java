package com.wormzjl.createcheme.science.column.v3;

import java.util.Arrays;
import java.util.Objects;

/** Immutable exact-zero elimination map from the public hydrocarbon axis to active numerical coordinates. */
final class V3ActiveComponentBasis {
    private final V3ComponentBasis publicBasis;
    private final int[] publicIndices;
    private final double[] feedFlowsMolPerSecond;
    private final double[] flowScales;

    private V3ActiveComponentBasis(
            V3ComponentBasis publicBasis, int[] publicIndices, double[] feedFlowsMolPerSecond, double[] flowScales) {
        this.publicBasis = Objects.requireNonNull(publicBasis, "publicBasis");
        this.publicIndices = publicIndices.clone();
        this.feedFlowsMolPerSecond = feedFlowsMolPerSecond.clone();
        this.flowScales = flowScales.clone();
        if (this.publicIndices.length == 0 || this.feedFlowsMolPerSecond.length != this.publicIndices.length
                || this.flowScales.length != this.publicIndices.length) {
            throw new IllegalArgumentException("V3 active component basis is empty or mismatched");
        }
        for (int active = 0; active < this.publicIndices.length; active++) {
            int publicIndex = this.publicIndices[active];
            if (publicIndex < 0 || publicIndex >= publicBasis.componentCount() || (active > 0 && publicIndex <= this.publicIndices[active - 1])
                    || !Double.isFinite(this.feedFlowsMolPerSecond[active]) || this.feedFlowsMolPerSecond[active] <= 0.0
                    || !Double.isFinite(this.flowScales[active]) || this.flowScales[active] <= 0.0) {
                throw new IllegalArgumentException("V3 active component basis violates exact-zero elimination invariants");
            }
        }
    }

    static V3ActiveComponentBasis from(V3ColumnInput input) {
        input = Objects.requireNonNull(input, "input");
        double[] publicFlows = input.feedComponentMolarFlowsMolPerSecond();
        double total = 0.0;
        int count = 0;
        for (double flow : publicFlows) {
            total += flow;
            if (flow > 0.0) count++;
        }
        if (!Double.isFinite(total) || total <= 0.0 || count == 0) {
            throw new IllegalArgumentException("V3 input has no active hydrocarbon feed components");
        }
        int[] indices = new int[count];
        double[] flows = new double[count];
        double[] scales = new double[count];
        int active = 0;
        for (int publicIndex = 0; publicIndex < publicFlows.length; publicIndex++) {
            if (publicFlows[publicIndex] == 0.0) continue;
            indices[active] = publicIndex;
            flows[active] = publicFlows[publicIndex];
            scales[active] = Math.max(publicFlows[publicIndex], total * 1.0e-12);
            active++;
        }
        return new V3ActiveComponentBasis(input.componentBasis(), indices, flows, scales);
    }

    int componentCount() { return publicIndices.length; }
    V3ComponentBasis publicBasis() { return publicBasis; }
    int publicIndex(int activeComponent) { return publicIndices[activeComponent]; }
    double feedFlowMolPerSecond(int activeComponent) { return feedFlowsMolPerSecond[activeComponent]; }
    double flowScale(int activeComponent) { return flowScales[activeComponent]; }
    double totalFeedFlowMolPerSecond() { return Arrays.stream(feedFlowsMolPerSecond).sum(); }
}
