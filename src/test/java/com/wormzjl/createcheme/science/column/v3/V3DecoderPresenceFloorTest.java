package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The opt-in decoder presence floor. The production decode is the {@code DecodeOptions.NONE} path and
 * must stay bit identical; every assertion on it here is an exact comparison for that reason.
 */
class V3DecoderPresenceFloorTest {
    private static final int STAGES = 17, NODE = 5, COMPONENT = 3, LIQUID_COMPOSITION = 3;
    private static final double PRESENCE_THRESHOLD = .02;

    @Test void defaultDecodeStillPrunesAKeptComponentThatFallsBelowItsFloor() {
        var input = input(); double[][] raw = belowFloor(input, 8);
        var explicit = decode(input, raw, V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        var implicit = V3FactorizedNeuralFeatures.decode(input, "test", V3CondenserPhaseBranch.TWO_PHASE, raw, PRESENCE_THRESHOLD);
        assertEquals(0, explicit.liquid()[NODE][COMPONENT]);
        for (int n = 0; n < STAGES + 2; n++) {
            assertArrayEquals(implicit.liquid()[n], explicit.liquid()[n]);
            assertArrayEquals(implicit.vapor()[n], explicit.vapor()[n]);
        }
        assertEquals(0, V3FactorizedNeuralFeatures.DecodeOptions.NONE.liftFactor());
        assertFalse(V3FactorizedNeuralFeatures.DecodeOptions.NONE.liftsPresentTraces());
        assertEquals(Double.POSITIVE_INFINITY, V3FactorizedNeuralFeatures.DecodeOptions.NONE.liftLogit());
    }

    @Test void aKeptButBelowFloorComponentIsLiftedToTheSupportReinsertionMultiple() {
        var input = input(); double[][] raw = belowFloor(input, 8);
        var pruned = decode(input, raw, V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        var lifted = decode(input, raw, V3FactorizedNeuralFeatures.DecodeOptions.lift(10, PRESENCE_THRESHOLD));
        double floor = floor(input, COMPONENT);
        assertEquals(0, pruned.liquid()[NODE][COMPONENT]);
        assertEquals(10 * floor, lifted.liquid()[NODE][COMPONENT]);
        assertEquals(V3TruncationSupport.FLOOR_REINSERTION_FACTOR * floor, lifted.liquid()[NODE][COMPONENT]);
        // Only the lifted trace differs: the lift adds mass instead of renormalising the phase for it.
        for (int n = 0; n < STAGES + 2; n++) for (int c = 0; c < input.componentBasis().componentCount(); c++) {
            assertEquals(pruned.vapor()[n][c], lifted.vapor()[n][c]);
            if (n != NODE || c != COMPONENT) assertEquals(pruned.liquid()[n][c], lifted.liquid()[n][c]);
        }
        double prunedTotal = Arrays.stream(pruned.liquid()[NODE]).sum();
        double liftedTotal = Arrays.stream(lifted.liquid()[NODE]).sum();
        assertEquals(10 * floor, liftedTotal - prunedTotal, floor);
        assertTrue(10 * floor < 1e-6 * prunedTotal, "the lifted mass must stay a trace of the phase total");
        assertEquals(floor, decode(input, raw, V3FactorizedNeuralFeatures.DecodeOptions.lift(1, PRESENCE_THRESHOLD))
                .liquid()[NODE][COMPONENT]);
    }

    @Test void componentsTheHeadDroppedOrTheFeedLacksStayAtZero() {
        var input = input();
        double[][] absent = belowFloor(input, -8);
        var lifted = decode(input, absent, V3FactorizedNeuralFeatures.DecodeOptions.lift(10, PRESENCE_THRESHOLD));
        var pruned = decode(input, absent, V3FactorizedNeuralFeatures.DecodeOptions.NONE);
        assertEquals(0, pruned.liquid()[NODE][COMPONENT]);
        assertEquals(0, lifted.liquid()[NODE][COMPONENT]);
        assertEquals(0, input.feedComponentMolarFlowsMolPerSecond()[0]);
        for (int n = 0; n < STAGES + 2; n++) {
            assertEquals(0, lifted.liquid()[n][0]); assertEquals(0, lifted.vapor()[n][0]);
            assertArrayEquals(pruned.liquid()[n], lifted.liquid()[n]);
        }
    }

    @Test void theGateComparesThePresenceProbabilityNotOnlyTheDecodeThreshold() {
        var input = input();
        double[][] weak = belowFloor(input, Math.log(.1 / .9));
        assertEquals(10 * floor(input, COMPONENT),
                decode(input, weak, V3FactorizedNeuralFeatures.DecodeOptions.lift(10, PRESENCE_THRESHOLD)).liquid()[NODE][COMPONENT]);
        assertEquals(0, decode(input, weak, V3FactorizedNeuralFeatures.DecodeOptions.lift(10, .5)).liquid()[NODE][COMPONENT]);
        double[][] confident = belowFloor(input, 8);
        assertEquals(10 * floor(input, COMPONENT),
                decode(input, confident, V3FactorizedNeuralFeatures.DecodeOptions.lift(10, .5)).liquid()[NODE][COMPONENT]);
        assertEquals(Math.log(.5 / .5), V3FactorizedNeuralFeatures.DecodeOptions.lift(10, .5).liftLogit());
    }

    @Test void malformedDecodeOptionsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(.5, .02));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(-1, .02));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(Double.NaN, .02));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(1e9, .02));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(10, 0));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.DecodeOptions.lift(10, 1));
        var input = input(); double[][] raw = belowFloor(input, 8);
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.decode(
                input, "test", V3CondenserPhaseBranch.TWO_PHASE, raw, PRESENCE_THRESHOLD, null));
    }

    private static V3NeuralSeed decode(V3ColumnInput input, double[][] raw, V3FactorizedNeuralFeatures.DecodeOptions options) {
        return V3FactorizedNeuralFeatures.decode(input, "test", V3CondenserPhaseBranch.TWO_PHASE, raw, PRESENCE_THRESHOLD, options);
    }

    private static double floor(V3ColumnInput input, int component) {
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        return Math.max(feed[component], Arrays.stream(feed).sum() * 1e-12) * V3FactorizedNeuralFeatures.TRACE_FLOOR_FRACTION;
    }

    /** One liquid trace of {@link #NODE} driven far under its floor, with a chosen presence logit. */
    private static double[][] belowFloor(V3ColumnInput input, double presenceLogit) {
        double[][] raw = V3FactorizedNeuralFeatures.targets(teacher(input));
        int c = input.componentBasis().componentCount();
        raw[NODE][LIQUID_COMPOSITION + COMPONENT] = -100;
        raw[NODE][5 + 2*c + COMPONENT] = presenceLogit;
        return raw;
    }

    private static V3ColumnInput input() {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        double[] feed = base.feedComponentMolarFlowsMolPerSecond(); feed[0] = 0;
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), feed,
                base.feedTemperatureKelvin(), STAGES, Math.max(1, STAGES/2), 200_000, 100,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(313.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2), new V3ColumnSpecification.ReboilerDuty(1e6)));
    }

    private static V3NeuralSeed teacher(V3ColumnInput input) {
        int nodes = input.stageCount()+2; double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double[][] l = new double[nodes][feed.length], v = new double[nodes][feed.length];
        double[] t = new double[nodes]; Arrays.fill(t, 400); t[0] = 313.15;
        for (int n = 0; n < nodes; n++) for (int c = 0; c < feed.length; c++) { l[n][c] = 2*feed[c]; v[n][c] = .5*feed[c]; }
        return new V3NeuralSeed(input, "test-property", V3CondenserPhaseBranch.TWO_PHASE, l, v, t, new double[nodes], new boolean[nodes]);
    }
}
