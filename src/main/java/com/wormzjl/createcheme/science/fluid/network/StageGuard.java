package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Map;

/**
 * A check the interval solver runs on every step it takes: {@link #checkRate} on the state a step starts from,
 * {@link #checkFilters} on the step's converged end. It throws to refuse the step: a
 * {@link SolidEventIntegrator.Transition} to declare a solid transport event (the interval solver then searches the
 * step onto it), an {@link ApproximationRejected} to send an approximate interval back to a full solve.
 */
@FunctionalInterface public interface StageGuard {
    StageGuard NONE=(states,modes)->{};
    void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes);
    default void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){check(states,modes);}
    default void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){checkFlow(states,modes,flows);}
    /**
     * The state at the start of a step - the previous accepted step's end, the end carried from the last interval, or
     * one rate solve of the port graph when nothing is carried - together with the live cake of the graph being stepped.
     * A transition seen here is at the step's own t0 and is therefore already exactly located: the interval solver
     * declares it at the current elapsed time instead of refining the step towards it.
     */
    default void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){checkFlow(states,modes,flows);}
}
