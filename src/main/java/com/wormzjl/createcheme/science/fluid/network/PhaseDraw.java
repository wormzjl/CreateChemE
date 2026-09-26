package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;

import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.WATER;

/**
 * What a vessel's phase port draws (decision D11 of documentation/2026-09-26-phase-ports-and-compressor: ports carry
 * mixed phases by priority).
 *
 * <p><b>Priority.</b> A bottom ({@link PassiveNetwork.PhasePort#LIQUID}) port draws the heavier of the vessel's two
 * liquids (by their own densities, {@link FluidThermodynamics#heavierLiquid}), then the lighter, then the gas; a top
 * ({@link PassiveNetwork.PhasePort#VAPOR}) port the gas, then the lighter liquid, then the heavier. Each liquid carries
 * its volume share of the solids ({@link FluidThermodynamics#phaseSolidShare}). A BULK end draws the bulk and has no
 * order.
 *
 * <p><b>Capacity and the priority stream.</b> A phase can be drawn through a port over one step only up to what the
 * vessel held of it at the step start: {@code c = m_start / (N dt)} (kg/s), {@code N} the vessel's phase-port ends whose
 * connection may carry outflow by the static rule, {@code dt} the step; infinite in a rate solve, which has no step.
 * A port carrying the outflow {@code q} draws the first phase of its order up to its capacity, the second with what is
 * left up to its capacity, and so on; the last phase the vessel holds takes whatever the earlier ones do not (it is
 * never capped: a port's total flow is limited by the hydraulics and the velocity cap alone, as a bulk end's is). A
 * phase the vessel does not hold at the step start has no capacity and is skipped.
 *
 * <p><b>Pinned and marginal phases.</b> A phase drawn at its capacity (pinned) is drawn at the composition, enthalpy and
 * density of the step's start state: over the step it takes exactly its share of what the vessel held of that phase at
 * the start, which exists whatever the end state holds (a phase drawn at its capacity usually ends the step at zero, on
 * its own phase boundary, where its end-state composition is not defined and a Newton whose layout keeps the phase
 * cannot converge). The phase that carries the rest of the flow (marginal) is drawn at the step's end state, the
 * implicit form of every stream before (plan 3.2): with no phase pinned the port's stream is exactly its phase stream.
 * The marginal phase must be held by the pass's seed; a phase the seed no longer holds (it vanished within the step) is
 * not drawn as marginal, and the next phase of the order the seed holds is.
 *
 * <p><b>The segment.</b> Which phases are pinned and which one is marginal is a kink in the stream's composition as a
 * function of {@code q}, so it is frozen for one active-set pass (a {@link Segment}, decided from the pass's start flow
 * and seeds, like the junction donors and the head densities) and re-decided from the converged flow by the pass loop:
 * each Newton residual is smooth, and the loop settles on the segment its own flow confirms.
 */
final class PhaseDraw {
    private PhaseDraw() {}

    /** The priority order of {@code port} on {@code state} (phase ids of {@link FluidThermodynamics}), null for BULK. */
    static int[] order(FluidThermodynamics model,FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        if(port==PassiveNetwork.PhasePort.BULK)return null;
        int heavy=model.heavierLiquid(state),light=heavy==OIL?WATER:OIL;
        return port==PassiveNetwork.PhasePort.LIQUID?new int[]{heavy,light,GAS}:new int[]{GAS,light,heavy};
    }
    /** The phase a port draws at a vanishing flow: the first of its order {@code state} holds; -1 for a BULK end and for a
     * state holding none of the three (then the port draws the bulk). The stream every static reader of a port reads
     * (columns, sign tests, start flows, the mobility check): the priority stream's limit as the flow goes to zero. */
    static int leading(FluidThermodynamics model,FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        var order=order(model,state,port);if(order==null)return -1;
        for(int phase:order)if(FluidThermodynamics.holdsPhase(state,phase))return phase;
        return -1;
    }
    /** Which of the three phases {@code state} holds. */
    static boolean[] held(FluidThermodynamics.State state) {
        return new boolean[]{FluidThermodynamics.holdsPhase(state,GAS),FluidThermodynamics.holdsPhase(state,OIL),FluidThermodynamics.holdsPhase(state,WATER)};
    }

    /**
     * One port end's draw for one pass: its priority {@code order}, the per-phase {@code capacity} (kg/s, by phase id), the
     * phases {@code pinned} at their capacity (in order, drawn at the step's start state) and the {@code marginal} phase
     * (drawn at the end state) that carries the rest of the outflow; {@code marginal} is -1 when the seed holds no phase to
     * draw it from.
     */
    record Segment(int[] order,double[] capacity,int[] pinned,int marginal) {
        /**
         * The segment an outflow {@code outflow} (kg/s; zero or negative is no outflow) lands in. Walk the order over the
         * phases with a positive capacity: a phase whose capacity the remaining flow exceeds is pinned, provided a later
         * phase of the order the seed holds can take the rest; the first phase the seed holds that covers the remainder
         * (or that nothing after it could relieve) is marginal; a phase with capacity the seed no longer holds and the
         * remainder does not exceed is not drawn. With no such phase, the first phase after the pinned ones the seed holds
         * is marginal (a capacity of zero: a phase that appeared within the step), then any the seed holds; none, -1.
         */
        static Segment decide(int[] order,double[] capacity,boolean[] seedHeld,double outflow) {
            double remaining=Math.max(outflow,0);int[] pinned=new int[3];int count=0,marginal=-1,after=0;
            for(int i=0;i<order.length;i++) {
                int phase=order[i];if(!(capacity[phase]>0))continue;
                boolean relieved=false;for(int j=i+1;j<order.length;j++)relieved|=seedHeld[order[j]];
                if(remaining>capacity[phase]&&relieved){pinned[count++]=phase;remaining-=capacity[phase];after=i+1;continue;}
                if(seedHeld[phase]){marginal=phase;break;}
            }
            if(marginal<0)for(int i=after;i<order.length;i++)if(seedHeld[order[i]]){marginal=order[i];break;}
            if(marginal<0&&count==0)for(int phase:order)if(seedHeld[phase]){marginal=phase;break;}
            return new Segment(order,capacity,Arrays.copyOf(pinned,count),marginal);
        }
        /** A compact code of the pinned set and the marginal phase, for the pass loop's cycle key. */
        byte code() {
            int mask=0;for(int phase:pinned)mask|=1<<phase;
            return (byte)(marginal+1|mask<<2);
        }
        boolean samePhases(Segment other){return marginal==other.marginal&&Arrays.equals(pinned,other.pinned);}
        /** The pinned total, {@code C = sum c}. */
        double pinnedTotal() {
            double total=0;for(int phase:pinned)total+=capacity[phase];
            return total;
        }
        /**
         * The phase drawn at the end state from a state holding {@code held}: the marginal phase, or, if the state no
         * longer holds it (an iterate at which it vanished), the next phase of the order the state holds, else the
         * nearest earlier one that is not pinned; -1 when there is none (with pinned phases the pinned mixture then
         * carries the whole flow; without, the port draws the bulk). The same resolution serves the Newton's stream, the
         * reconstruction's booking and the transfer sample.
         */
        int marginalAt(boolean[] held) {
            if(marginal<0)return -1;
            int at=0;while(order[at]!=marginal)at++;
            for(int i=at;i<order.length;i++)if(held[order[i]])return order[i];
            for(int i=at-1;i>=0;i--){int phase=order[i];if(held[phase]&&Arrays.stream(pinned).noneMatch(p->p==phase))return phase;}
            return -1;
        }
        /**
         * The mass flow (kg/s) each phase supplies to an outflow {@code outflow}, as {@code [end-state rates by phase id,
         * start-state rates by phase id]} (six entries): the pinned phases their capacity at the start state and the
         * marginal phase {@code marginal} (from {@link #marginalAt}) the rest at the end state; below the pinned total (an
         * iterate or a point the segment does not describe), or with no marginal phase, the pinned phases in proportion to
         * their capacities and the marginal one nothing, which is continuous with the above at the pinned total and at zero
         * flow. They add up to {@code outflow}.
         */
        double[] rates(int marginal,double outflow) {
            double[] rates=new double[6];double total=pinnedTotal();
            if(pinned.length==0){if(marginal>=0)rates[marginal]=outflow;return rates;}
            if(marginal>=0&&outflow>=total){for(int phase:pinned)rates[3+phase]=capacity[phase];rates[marginal]=outflow-total;}
            else for(int phase:pinned)rates[3+phase]=capacity[phase]*(outflow/total);
            return rates;
        }
    }
}
