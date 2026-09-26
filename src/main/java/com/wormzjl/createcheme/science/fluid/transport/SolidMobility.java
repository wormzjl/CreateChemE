package com.wormzjl.createcheme.science.fluid.transport;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;

/** Outlet-specific transport eligibility. A bulk end requests MIXED; a phase port requests the outlet of the phase it
 * draws first ({@code SolidEventIntegrator}; decision D11 of the phase-ports batch, the bottom port a decant): GAS,
 * ORGANIC_LIQUID or WATER. */
public final class SolidMobility {
    public enum Outlet { MIXED, ORGANIC_LIQUID, WATER, GAS }
    /**
     * {@code POPULATION_LIMIT} is the one reason that is not a property of the donor: a conserved
     * stock holds at most {@link SolidInventory#MAXIMUM_POPULATIONS} distinct material/size
     * populations, and a connection whose delivery would push what receives it past that has to
     * stop carrying rather than let the reconstruction refuse a step no smaller step can make work.
     */
    public enum Reason { MOBILE, IMMOBILE_LIQUID, NO_LIQUID_CARRIER, PACKED_SLURRY, PARTICLE_TOO_LARGE, DEPOSITION, FILTER_CLOGGED, POPULATION_LIMIT }
    public record Check(Reason reason, double velocity, double minimumVelocity) {
        public boolean allowed(){return reason==Reason.MOBILE;}
    }
    private SolidMobility() {}
    public static boolean immobileLiquid(FluidThermodynamics model,FluidThermodynamics.State state) {
        return state.liquidVolume()>0&&SlurryTransport.immobile(model.viscosity.liquid(state.temperature(),state.liquidView()).pascalSeconds(),model.solidSettings.immobileViscosity())
                ||state.waterVolume()>0&&SlurryTransport.immobile(model.viscosity.waterLiquid(state.temperature()),model.solidSettings.immobileViscosity());
    }
    public static boolean requiresFull(FluidThermodynamics model,PassiveNetwork graph) {
        return graph.pipes().stream().anyMatch(p->p.filter()!=null)
                ||graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||immobileLiquid(model,n.state()))
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof com.wormzjl.createcheme.science.fluid.network.ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    public static boolean monitored(PassiveNetwork graph) {
        return graph.pipes().stream().anyMatch(p->p.filter()!=null)||graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||n.state().liquidVolume()>0)
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof com.wormzjl.createcheme.science.fluid.network.ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    /**
     * Everything {@link #check} decides from the donor alone: the two carrier-viscosity tests,
     * whether any of its populations is large enough to matter for blockage, the largest of those
     * and the suspension velocity the largest of them asks for.
     *
     * <p>A stage guard asks the same donor about every connection leaving it, in both directions,
     * several times per step. Every one of those asked the carrier viscosities again - two
     * correlation evaluations for a donor that may hold no particles at all - and evaluated the
     * carrier density and mixture viscosity once per active population although neither depends on
     * the population. Preparing the donor once per node per stage removes both without moving a
     * single decision: what stays per connection is the liquid velocity, the bore, and whether the
     * connection carries a filter.
     */
    public record Donor(boolean immobileOrganic, boolean immobileWater, boolean active,
                        double liquidVolume, double solidVolume, double largestDiameter, double requiredVelocity) {}
    /** {@link Donor} for one state. A donor with no population above the trace volume fraction
     * costs the two carrier tests and nothing else: no deposition velocity, no mixture viscosity. */
    public static Donor donor(FluidThermodynamics model, FluidThermodynamics.State state) {
        boolean immobileOrganic=state.liquidVolume()>0
                &&SlurryTransport.immobile(model.viscosity.liquid(state.temperature(),state.liquidView()).pascalSeconds(),model.solidSettings.immobileViscosity());
        boolean immobileWater=state.waterVolume()>0
                &&SlurryTransport.immobile(model.viscosity.waterLiquid(state.temperature()),model.solidSettings.immobileViscosity());
        double liquidVolume=state.liquidVolume()+state.waterVolume();
        boolean active=false;double largest=0,required=0,carrierDensity=0,carrierViscosity=0;
        for(var population:state.solids().populations()) {
            if(!SlurryTransport.activeForBlockage(population.volume(),state.volume(),model.solidSettings.traceVolumeFraction()))continue;
            if(!active&&liquidVolume>0) {
                double liquidMass=state.waterLiquid()*model.waterMolecularWeight;
                var n=state.liquidView();for(int c=0;c<n.length;c++)liquidMass+=n[c]*model.molecularWeight(c);
                double mu=0;
                if(state.liquidVolume()>0)mu+=state.liquidVolume()*model.viscosity.liquid(state.temperature(),n).pascalSeconds();
                if(state.waterVolume()>0)mu+=state.waterVolume()*model.viscosity.waterLiquid(state.temperature());
                carrierDensity=liquidMass/liquidVolume;carrierViscosity=mu/liquidVolume;
            }
            active=true;
            largest=Math.max(largest,population.size().diameterMetres());
            if(liquidVolume>0)required=Math.max(required,SlurryTransport.depositionVelocity(population.size().diameterMetres(),
                    population.material().density(),carrierDensity,carrierViscosity,model.solidSettings.suspensionMultiplier()));
        }
        return new Donor(immobileOrganic,immobileWater,active,liquidVolume,state.solidMoments().volume(),largest,required);
    }
    public static Check check(FluidThermodynamics model, FluidThermodynamics.State donor,
                              PassiveNetwork.Pipe pipe, double massFlow, Outlet outlet) {
        if(outlet==Outlet.GAS)return new Check(Reason.MOBILE,0,0);
        return check(donor(model,donor),donor,pipe,massFlow,outlet);
    }
    /**
     * {@code prepared} must be {@link #donor} of this same state. The largest active population
     * decides the bore refusal and the suspension velocity alike: the original walked the
     * populations in order and refused on the first one too wide for the bore, which reports no
     * suspension velocity at all, so taking the largest refuses on exactly the same donors and
     * reports the same velocity everywhere it is read.
     */
    public static Check check(Donor prepared, FluidThermodynamics.State donor,
                              PassiveNetwork.Pipe pipe, double massFlow, Outlet outlet) {
        return check(prepared,donor,pipe,massFlow,outlet,donor.mass());
    }
    /**
     * {@link #check} of a connection that draws {@code drawnMass} kilograms of stream per the donor's whole liquid and
     * solid content: the donor's own mass for a bulk withdrawal, and for a phase port drawing one liquid that liquid's
     * stream mass over its share of the liquid (it carries its volume share of the donor's liquid and solids). The liquid
     * velocity is
     * {@code |q| / drawnMass * liquidVolume / area}.
     */
    public static Check check(Donor prepared, FluidThermodynamics.State donor,
                              PassiveNetwork.Pipe pipe, double massFlow, Outlet outlet, double drawnMass) {
        if(outlet==Outlet.GAS)return new Check(Reason.MOBILE,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.ORGANIC_LIQUID)&&prepared.immobileOrganic)return new Check(Reason.IMMOBILE_LIQUID,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.WATER)&&prepared.immobileWater)return new Check(Reason.IMMOBILE_LIQUID,0,0);
        double liquidVolume=prepared.liquidVolume;
        double velocity=Math.abs(massFlow)/drawnMass*liquidVolume/pipe.maximumArea();
        if(prepared.active) {
            if(liquidVolume==0)return new Check(Reason.NO_LIQUID_CARRIER,velocity,0);
            if(prepared.largestDiameter>=pipe.minimumDiameter())return new Check(Reason.PARTICLE_TOO_LARGE,velocity,0);
        }
        double required=prepared.requiredVelocity;
        if(prepared.active&&prepared.solidVolume/(liquidVolume+prepared.solidVolume)>=SlurryTransport.MAXIMUM_PACKING_FRACTION)
            return new Check(Reason.PACKED_SLURRY,velocity,required);
        return new Check(pipe.filter()==null&&prepared.active&&velocity<=required?Reason.DEPOSITION:Reason.MOBILE,velocity,required);
    }
}