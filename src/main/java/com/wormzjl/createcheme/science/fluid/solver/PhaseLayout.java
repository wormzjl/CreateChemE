package com.wormzjl.createcheme.science.fluid.solver;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Fixed phase active set for one Newton pass. Evaluates properties directly; never runs a nested flash. */
public final class PhaseLayout {
    private final FluidThermodynamics model;
    private final int[] components,liquidIndex,vaporIndex;
    private final int waterLiquidIndex,waterVaporIndex,temperatureIndex,pressureIndex,partialPressureIndex,size;
    private final double amountScale,energyScale;
    private final double[] componentScales,differenceScales;
    private final boolean liquidActive,vaporActive;

    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed) {
        this(model,seed,null);
    }
    /** A shared component mask allows a component absent in this vessel to arrive through connected pipes. */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask) {
        this(model,seed,componentMask,null);
    }
    /** A trial seed may contain artificial entry traces; do not impose trace-relative accuracy
     * on a component that is actually absent before this step. Canonical traces remain resolved. */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference) {
        this.model=Objects.requireNonNull(model);
        double[] l=seed.liquid(),v=seed.vapor();var present=new ArrayList<Integer>();
        if(componentMask!=null&&componentMask.length!=l.length)throw new IllegalArgumentException("Component mask mismatch");
        for(int i=0;i<l.length;i++)if(l[i]+v[i]>0||(componentMask!=null&&componentMask[i]))present.add(i);
        components=present.stream().mapToInt(Integer::intValue).toArray();
        liquidActive=seed.liquidVolume()>0;vaporActive=Arrays.stream(v).sum()>0;
        if(components.length>0&&!liquidActive&&!vaporActive)throw new IllegalArgumentException("A hydrocarbon-phase appearance pass is required");
        liquidIndex=new int[l.length];vaporIndex=new int[l.length];Arrays.fill(liquidIndex,-1);Arrays.fill(vaporIndex,-1);
        int offset=0;for(int i:components){if(liquidActive)liquidIndex[i]=offset++;if(vaporActive)vaporIndex[i]=offset++;}
        waterLiquidIndex=seed.waterLiquid()>0?offset++:-1;waterVaporIndex=seed.waterVapor()>0?offset++:-1;
        temperatureIndex=offset++;pressureIndex=offset++;
        partialPressureIndex=vaporActive&&waterVaporIndex>=0?offset++:-1;size=offset;
        amountScale=Math.max(1e-12,Arrays.stream(l).sum()+Arrays.stream(v).sum()+seed.waterLiquid()+seed.waterVapor());
        energyScale=Math.max(1,Math.max(Math.abs(seed.internalEnergy()),amountScale*FluidThermodynamics.R*seed.temperature()));
        if(conservedReference!=null&&conservedReference.length!=l.length+1)throw new IllegalArgumentException("Balance reference basis mismatch");
        boolean resolveTraces=seed.vaporVolume()/seed.volume()<.01;
        componentScales=new double[l.length+1];for(int c=0;c<l.length;c++)componentScales[c]=!resolveTraces||conservedReference!=null&&conservedReference[c]==0?amountScale:Math.max(1e-30,l[c]+v[c]);
        componentScales[l.length]=!resolveTraces||conservedReference!=null&&conservedReference[l.length]==0?amountScale:Math.max(1e-30,seed.waterLiquid()+seed.waterVapor());
        differenceScales=encode(seed);
        if(!resolveTraces)for(int local=0;local<size;local++)if(totalAmountVariable(local))differenceScales[local]=Math.max(1e-4,differenceScales[local]);
        if(conservedReference!=null) {
            for(int c:components)if(conservedReference[c]==0){int at=liquidActive?liquidIndex[c]:vaporIndex[c];differenceScales[at]=Math.max(1e-4,differenceScales[at]);}
            if(conservedReference[l.length]==0){if(waterLiquidIndex>=0)differenceScales[waterLiquidIndex]=Math.max(1e-4,differenceScales[waterLiquidIndex]);if(waterVaporIndex>=0)differenceScales[waterVaporIndex]=Math.max(1e-4,differenceScales[waterVaporIndex]);}
        }
    }
    public int size(){return size;}
    public int componentBalanceCount(){return components.length+(waterLiquidIndex>=0||waterVaporIndex>=0?1:0);}
    /** Bulk component transport is independent of phase split, T and P at fixed total amounts and edge mass flow. */
    public boolean totalAmountVariable(int local) {
        if(local==waterLiquidIndex||local==waterVaporIndex)return true;
        for(int component:components)if(local==(liquidActive?liquidIndex[component]:vaporIndex[component]))return true;
        return false;
    }
    public double amountScale(){return amountScale;}
    public double energyScale(){return energyScale;}
    /** Resolve trace amounts relative to their own scale rather than the bulk water inventory. */
    public double differenceScale(int local,double value){return Math.max(Math.abs(value),totalAmountVariable(local)?Math.max(1e-30,Math.abs(differenceScales[local])):1);}
    public double temperature(double[] variables,int offset){return 350*Math.exp(variables[offset+temperatureIndex]);}
    public boolean hasHydrocarbons(){return liquidActive||vaporActive;}
    public double[] encode(FluidThermodynamics.State state) {
        double[] x=new double[size],l=state.liquid(),v=state.vapor();
        for(int i:components){
            if(liquidActive&&vaporActive) {
                x[liquidIndex[i]]=(l[i]+v[i])/amountScale;
                x[vaporIndex[i]]=l[i]>0&&v[i]>0?Math.log(v[i])-Math.log(l[i]):Math.log(Arrays.stream(v).sum()/Arrays.stream(l).sum())
                        +state.liquidProperties().logFugacity()[i]-state.vaporProperties().logFugacity()[i]+Math.log(state.pressure()/state.hydrocarbonPartialPressure());
            }
            else if(liquidIndex[i]>=0)x[liquidIndex[i]]=l[i]/amountScale;
            else if(vaporIndex[i]>=0)x[vaporIndex[i]]=v[i]/amountScale;
        }
        if(waterLiquidIndex>=0)x[waterLiquidIndex]=state.waterLiquid()/amountScale;
        if(waterVaporIndex>=0)x[waterVaporIndex]=state.waterVapor()/amountScale;
        x[temperatureIndex]=Math.log(state.temperature()/350);x[pressureIndex]=Math.log(state.pressure()/1e5);
        if(partialPressureIndex>=0)x[partialPressureIndex]=Math.log(state.hydrocarbonPartialPressure()/1e5);
        return x;
    }
    public FluidThermodynamics.State decode(double[] variables,int offset) {
        return decode(variables,offset,null);
    }
    public FluidThermodynamics.State decode(double[] variables,int offset,com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson.TemperatureTerms terms) {
        double[] l=new double[liquidIndex.length],v=new double[l.length];
        for(int i:components){
            if(liquidActive&&vaporActive) {
                double amount=amountScale*variables[offset+liquidIndex[i]],ratio=variables[offset+vaporIndex[i]];
                if(ratio>=0){l[i]=amount/(1+Math.exp(ratio));v[i]=amount-l[i];}
                else{v[i]=amount/(1+Math.exp(-ratio));l[i]=amount-v[i];}
            }else if(liquidIndex[i]>=0)l[i]=amountScale*variables[offset+liquidIndex[i]];
            else if(vaporIndex[i]>=0)v[i]=amountScale*variables[offset+vaporIndex[i]];
        }
        double wl=waterLiquidIndex<0?0:amountScale*variables[offset+waterLiquidIndex];
        double wv=waterVaporIndex<0?0:amountScale*variables[offset+waterVaporIndex];
        double t=350*Math.exp(variables[offset+temperatureIndex]),p=1e5*Math.exp(variables[offset+pressureIndex]);
        double pc=partialPressureIndex>=0?1e5*Math.exp(variables[offset+partialPressureIndex]):vaporActive?p:0;
        return model.state(t,p,l,v,wl,wv,pc,terms);
    }
    /** Target amounts/U may include backward-Euler edge contributions computed in the same residual evaluation. */
    public void residual(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double targetVolume,double[] result,int offset,double[] variables) {
        double[] l=state.liquid(),v=state.vapor();int row=offset;
        for(int i:components)result[row++]=(l[i]+v[i]-targetAmounts[i])/componentScales[i];
        if(waterLiquidIndex>=0||waterVaporIndex>=0)result[row++]=(state.waterLiquid()+state.waterVapor()-targetAmounts[l.length])/componentScales[l.length];
        result[row++]=(state.internalEnergy()-targetEnergy)/energyScale;
        result[row++]=(state.volume()-targetVolume)/targetVolume;
        equilibriumResidual(state,result,row,offset,variables);
    }
    /** Zero-holdup mixing: M-1 mass-fraction equations, continuity, enthalpy, and amount normalization. */
    public void junctionResidual(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                                 double netMassFlow,double[] result,int offset,double[] variables) {
        var n=totalAmounts(state);var present=new ArrayList<Integer>();for(int i:components)present.add(i);
        if(waterLiquidIndex>=0||waterVaporIndex>=0)present.add(n.length-1);
        int row=offset;
        for(int index=0;index<present.size()-1;index++) {
            int i=present.get(index);double mw=i==n.length-1?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(i);
            result[row++]=n[i]*mw/state.mass()-incomingMassFractions[i];
        }
        result[row++]=netMassFlow; // 1 kg/s reference scale
        result[row++]=(state.enthalpy()/state.mass()-incomingSpecificEnthalpy)/Math.max(1,energyScale/state.mass());
        result[row++]=Arrays.stream(n).sum()/amountScale-1;
        equilibriumResidual(state,result,row,offset,variables);
    }
    private void equilibriumResidual(FluidThermodynamics.State state,double[] result,int row,int offset,double[] variables) {
        double[] l=state.liquid(),v=state.vapor();
        if(liquidActive&&vaporActive) {
            if(state.liquidProperties()==null||state.vaporProperties()==null)throw new IllegalArgumentException("A phase vanished inside a fixed-regime Newton trial");
            double nl=Arrays.stream(l).sum(),nv=Arrays.stream(v).sum();
            double[] fl=state.liquidProperties().logFugacity(),fv=state.vaporProperties().logFugacity();
            // The total amount cancels from the fugacity ratio, including at zero amount.
            for(int i:components)result[row++]=-variables[offset+vaporIndex[i]]+Math.log(nv/nl)+fl[i]-fv[i]+Math.log(state.pressure()/state.hydrocarbonPartialPressure());
        }
        if(waterLiquidIndex>=0&&waterVaporIndex>=0)result[row++]=Math.log(state.waterPartialPressure()/model.saturationPressure(state.temperature()));
        if(partialPressureIndex>=0)result[row++]=(state.hydrocarbonPartialPressure()+state.waterPartialPressure()-state.pressure())/state.pressure();
        if(row!=offset+size)throw new IllegalStateException("Phase equation/unknown count mismatch");
    }
    public static double[] totalAmounts(FluidThermodynamics.State state) {
        double[] l=state.liquid(),v=state.vapor(),n=Arrays.copyOf(l,l.length+1);
        for(int i=0;i<l.length;i++)n[i]+=v[i];n[l.length]=state.waterLiquid()+state.waterVapor();return n;
    }
    public SparseNewton.Equations fixedInventory(double[] amounts,double energy,double volume) {
        double[] target=amounts.clone();
        return new SparseNewton.Equations(){
            public int size(){return PhaseLayout.this.size;}
            public double differenceScale(int column,double value){return PhaseLayout.this.differenceScale(column,value);}
            public double maximumStep(double[] x,double[] direction){double alpha=1;for(int c=0;c<x.length;c++)if(totalAmountVariable(c)&&x[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*x[c]/-direction[c]);return alpha;}
            public int[][] columnRows(){int[][] rows=new int[size()][size()];for(int[] column:rows)for(int i=0;i<column.length;i++)column[i]=i;return rows;}
            public double[] residual(double[] x){double[] f=new double[size()];PhaseLayout.this.residual(decode(x,0),target,energy,volume,f,0,x);return f;}
        };
    }
}
