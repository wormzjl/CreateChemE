package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/** Validated persisted initialization/boundary settings. Finite reservoir contents live only in the world ledger. */
public record FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition) {
    public FluidDeviceSpec {
        composition=composition.clone();
        if(!Double.isFinite(volume)||volume<=0||volume>1000||!Double.isFinite(temperature)||temperature<273.16||temperature>600||!Double.isFinite(pressure)||pressure<100||pressure>2e6||composition.length!=22)throw new IllegalArgumentException("Device settings outside the fluid model's bounds");
        double sum=0;for(double amount:composition){if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid composition");sum+=amount;}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Empty composition");
        if(Math.abs(sum-1)>1e-12)for(int i=0;i<composition.length;i++)composition[i]/=sum;
    }
    @Override public double[] composition(){return composition.clone();}
    public static FluidDeviceSpec nitrogen(){double[] n=new double[22];n[20]=1;return new FluidDeviceSpec(1,298.15,101325,n);}
    public static FluidDeviceSpec water(){double[] n=new double[22];n[21]=1;return new FluidDeviceSpec(1,298.15,101325,n);}
    /** Called for first activation, never to reconcile an already owned finite reservoir. */
    public PassiveNetwork.Reservoir initialize(PhysicalFluidTopology.Device device,FluidThermodynamics model,Runnable checkpoint) {
        var kind=switch(device.kind()) {case RESERVOIR->PassiveNetwork.NodeKind.RESERVOIR;case GENERATOR->PassiveNetwork.NodeKind.GENERATOR;case VOID->PassiveNetwork.NodeKind.VOID;default->throw new IllegalArgumentException("Pipes and actuators have no owned reservoir");};
        FluidThermodynamics.State state;
        if(kind==PassiveNetwork.NodeKind.RESERVOIR||kind==PassiveNetwork.NodeKind.VOID)state=model.initialNitrogenCharge(volume,temperature,pressure,checkpoint);
        else {
            var n=composition.clone();var unit=model.flashTP(temperature,pressure,n,checkpoint);for(int c=0;c<n.length;c++)n[c]*=volume/unit.volume();state=model.flashTP(temperature,pressure,n,checkpoint);
        }
        return new PassiveNetwork.Reservoir(device.id(),device.position().y(),state,kind,new PassiveNetwork.Inventory(volume,com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy()));
    }
    @Override public boolean equals(Object value){return value instanceof FluidDeviceSpec other&&volume==other.volume&&temperature==other.temperature&&pressure==other.pressure&&Arrays.equals(composition,other.composition);}
    @Override public int hashCode(){return 31*Objects.hash(volume,temperature,pressure)+Arrays.hashCode(composition);}
}
