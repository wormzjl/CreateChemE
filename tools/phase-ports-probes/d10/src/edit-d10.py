import json
E=[["""    /**
     * A trial point for one pass, per node.
     *
     * <p>A vessel is seeded as it always was: its own inventory, plus a quarter of its mass at
     * most of what is about to arrive, which is a correction to a stock it already owns. A
     * junction is seeded with its own state: its owned holdup is a small stock whose mixture the
     * backward-Euler rows move from where it is.
     */""","""    /**
     * A trial point for one pass, per node.
     *
     * <p>A vessel is seeded as it always was: its own inventory, plus a quarter of its mass at
     * most of what is about to arrive, which is a correction to a stock it already owns. A
     * junction is seeded with its own state: its owned holdup is a small stock whose mixture the
     * backward-Euler rows move from where it is - with one addition, the water trace below.
     *
     * <p><b>Water trace in a junction (decision D10 of the phase-ports batch).</b> A junction whose reachable set holds
     * water ({@link #reachableComponents}) while its own state holds none is seeded with its state flashed at its own
     * temperature and pressure with a water entry trace of {@code 1e-12} of its total amount - the entry trace a vessel
     * gets for every reachable component it lacks. A node's {@link PhaseLayout} carries water (its water unknowns, its
     * water balance row and the junction's water mixing row) only when its seed holds water, and the hydrocarbon
     * components a junction can receive already enter through the reachable mask; water has no mask entry, so without the
     * trace a junction seeded dry that receives unsaturated water vapour - which does not change its phase code, so the
     * converged-point phase correction never reseeds it - is solved without the water the reconstruction then books into
     * it, and the equation gate refuses every step (1.3e-8 at 0.5 % water; PHASE_PORTS_REVIEW.md WP1 section 8). The
     * junction's owned inventory is untouched: the seed is a starting point and a basis, the rows start from the stock.
     * A junction whose flash with the trace leaves the property domain keeps its state (the trace is a basis, never a
     * reason to refuse a step).
     */"""],
["""            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();
            if(node.fixed()||node.junction()){seeds.add(state);continue;}""","""            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();
            if(node.junction()&&available[count-1]&&state.waterLiquid()+state.waterVapor()==0){seeds.add(waterTraceSeed(state,checkpoint));continue;}
            if(node.fixed()||node.junction()){seeds.add(state);continue;}"""],
["""    /** Value identity of two node-state lists (T, P, amounts, mass, volume, enthalpy). */""","""    /** A dry junction state flashed at its own temperature and pressure with a water entry trace of 1e-12 of its total
     * amount; the state itself if that flash leaves the property domain. See {@link #initialPhaseSeeds}. */
    private FluidThermodynamics.State waterTraceSeed(FluidThermodynamics.State state,Runnable checkpoint) {
        var n=PhaseLayout.totalAmounts(state);n[n.length-1]=Arrays.stream(n).sum()*1e-12;
        try{return model.flashTP(state.temperature(),state.pressure(),n,checkpoint).withSolidState(state.solids(),state.solidMoments());}
        catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation outside){return state;}
    }
    /** Value identity of two node-state lists (T, P, amounts, mass, volume, enthalpy). */"""]]
json.dump(E,open("/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/d10/e1.json","w"))
