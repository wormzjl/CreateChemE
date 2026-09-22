package com.wormzjl.createcheme.science.fluid.transport;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;

/** Outlet-specific transport eligibility. Existing physical outlets request MIXED. */
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
    public static Check check(FluidThermodynamics model, FluidThermodynamics.State donor,
                              PassiveNetwork.Pipe pipe, double massFlow, Outlet outlet) {
        if(outlet==Outlet.GAS)return new Check(Reason.MOBILE,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.ORGANIC_LIQUID)&&donor.liquidVolume()>0
                &&SlurryTransport.immobile(model.viscosity.liquid(donor.temperature(),donor.liquidView()).pascalSeconds(),model.solidSettings.immobileViscosity()))
            return new Check(Reason.IMMOBILE_LIQUID,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.WATER)&&donor.waterVolume()>0
                &&SlurryTransport.immobile(model.viscosity.waterLiquid(donor.temperature()),model.solidSettings.immobileViscosity()))
            return new Check(Reason.IMMOBILE_LIQUID,0,0);
        double liquidVolume=donor.liquidVolume()+donor.waterVolume();
        double velocity=Math.abs(massFlow)/donor.mass()*liquidVolume/pipe.maximumArea();
        double required=0;
        boolean active=false;
        for(var population:donor.solids().populations()) {
            if(!SlurryTransport.activeForBlockage(population.volume(),donor.volume(),model.solidSettings.traceVolumeFraction()))continue;
            active=true;
            if(liquidVolume==0)return new Check(Reason.NO_LIQUID_CARRIER,velocity,0);
            if(population.size().diameterMetres()>=pipe.minimumDiameter())return new Check(Reason.PARTICLE_TOO_LARGE,velocity,0);
            double liquidMass=donor.waterLiquid()*model.waterMolecularWeight;
            var n=donor.liquidView();for(int c=0;c<n.length;c++)liquidMass+=n[c]*model.molecularWeight(c);
            double mu=0;
            if(donor.liquidVolume()>0)mu+=donor.liquidVolume()*model.viscosity.liquid(donor.temperature(),n).pascalSeconds();
            if(donor.waterVolume()>0)mu+=donor.waterVolume()*model.viscosity.waterLiquid(donor.temperature());
            required=Math.max(required,SlurryTransport.depositionVelocity(population.size().diameterMetres(),population.material().density(),
                    liquidMass/liquidVolume,mu/liquidVolume,model.solidSettings.suspensionMultiplier()));
        }
        if(active&&donor.solidMoments().volume()/(liquidVolume+donor.solidMoments().volume())>=SlurryTransport.MAXIMUM_PACKING_FRACTION)
            return new Check(Reason.PACKED_SLURRY,velocity,required);
        return new Check(pipe.filter()==null&&active&&velocity<=required?Reason.DEPOSITION:Reason.MOBILE,velocity,required);
    }
}