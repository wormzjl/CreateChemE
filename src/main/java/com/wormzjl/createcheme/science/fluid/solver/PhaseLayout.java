package com.wormzjl.createcheme.science.fluid.solver;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.thermo.PhaseSupport;
import com.wormzjl.createcheme.science.thermo.TraceTruncationPolicy;
import java.util.*;

/** Fixed phase active set for one Newton pass. Evaluates properties directly; never runs a nested flash. */
public final class PhaseLayout {
    private final FluidThermodynamics model;
    private final int[] components,liquidIndex,vaporIndex;
    /** Where each present component's <em>total</em> amount lives: {@link #liquidIndex} when this
     * layout carries a liquid amount for it, {@link #vaporIndex} otherwise. For a two-phase component
     * the second slot is the phase split {@code ln(v/l)}; for a one-phase component there is no
     * second slot and no equilibrium row. */
    private final int[] amountIndex;
    private final PhaseSupport[] support;
    /** The conserved components a junction writes mass fractions for, water last: the graph fixes
     * it, so it is built with the layout instead of boxed into a list on every evaluation. */
    private final int[] junctionBasis;
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
        this(model,seed,componentMask,conservedReference,support(seed,componentMask,TraceTruncationPolicy.OFF,null));
    }
    /**
     * {@code support} must be the array {@link #support} derives from this seed, this mask and the
     * caller's policy - the caller derives it first because the solver keys its workspaces and its
     * active-set cycle guard on it, and it must be the same array the layout was built from.
     */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference,
                       PhaseSupport[] support) {
        this.model=Objects.requireNonNull(model);
        double[] l=seed.liquidView(),v=seed.vaporView();var present=new ArrayList<Integer>();
        if(componentMask!=null&&componentMask.length!=l.length)throw new IllegalArgumentException("Component mask mismatch");
        if(support.length!=l.length)throw new IllegalArgumentException("Phase support basis mismatch");
        this.support=support.clone();
        for(int i=0;i<l.length;i++)if(l[i]+v[i]>0||(componentMask!=null&&componentMask[i]))present.add(i);
        components=present.stream().mapToInt(Integer::intValue).toArray();
        liquidActive=seed.liquidVolume()>0;vaporActive=sum(v)>0;
        if(components.length>0&&!liquidActive&&!vaporActive)throw new IllegalArgumentException("A hydrocarbon-phase appearance pass is required");
        for(int i:components)if(this.support[i]==PhaseSupport.ABSENT||this.support[i].liquid()&&!liquidActive||this.support[i].vapor()&&!vaporActive)
            throw new IllegalArgumentException("Phase support does not match the seed's active phases at component "+i);
        boolean water=seed.waterLiquid()>0||seed.waterVapor()>0;
        junctionBasis=new int[components.length+(water?1:0)];
        System.arraycopy(components,0,junctionBasis,0,components.length);
        if(water)junctionBasis[components.length]=l.length;
        liquidIndex=new int[l.length];vaporIndex=new int[l.length];amountIndex=new int[l.length];
        Arrays.fill(liquidIndex,-1);Arrays.fill(vaporIndex,-1);Arrays.fill(amountIndex,-1);
        int offset=0;
        for(int i:components) {
            if(this.support[i].liquid())liquidIndex[i]=offset++;
            if(this.support[i].vapor())vaporIndex[i]=offset++;
            amountIndex[i]=liquidIndex[i]>=0?liquidIndex[i]:vaporIndex[i];
        }
        waterLiquidIndex=seed.waterLiquid()>0?offset++:-1;waterVaporIndex=seed.waterVapor()>0?offset++:-1;
        temperatureIndex=offset++;pressureIndex=offset++;
        partialPressureIndex=vaporActive&&waterVaporIndex>=0?offset++:-1;size=offset;
        amountScale=Math.max(1e-12,sum(l)+sum(v)+seed.waterLiquid()+seed.waterVapor());
        energyScale=Math.max(1,Math.max(Math.abs(seed.internalEnergy()),amountScale*FluidThermodynamics.R*seed.temperature()));
        if(conservedReference!=null&&conservedReference.length!=l.length+1)throw new IllegalArgumentException("Balance reference basis mismatch");
        boolean resolveTraces=seed.vaporVolume()/seed.volume()<.01;
        componentScales=new double[l.length+1];for(int c=0;c<l.length;c++)componentScales[c]=!resolveTraces||conservedReference!=null&&conservedReference[c]==0?amountScale:Math.max(1e-30,l[c]+v[c]);
        componentScales[l.length]=!resolveTraces||conservedReference!=null&&conservedReference[l.length]==0?amountScale:Math.max(1e-30,seed.waterLiquid()+seed.waterVapor());
        differenceScales=encode(seed,l,v);
        if(!resolveTraces)for(int local=0;local<size;local++)if(totalAmountVariable(local))differenceScales[local]=Math.max(1e-4,differenceScales[local]);
        if(conservedReference!=null) {
            for(int c:components)if(conservedReference[c]==0){int at=amountIndex[c];differenceScales[at]=Math.max(1e-4,differenceScales[at]);}
            if(conservedReference[l.length]==0){if(waterLiquidIndex>=0)differenceScales[waterLiquidIndex]=Math.max(1e-4,differenceScales[waterLiquidIndex]);if(waterVaporIndex>=0)differenceScales[waterVaporIndex]=Math.max(1e-4,differenceScales[waterVaporIndex]);}
        }
    }
    public int size(){return size;}
    public int componentBalanceCount(){return components.length+(waterLiquidIndex>=0||waterVaporIndex>=0?1:0);}
    /** This layout's frozen per-component support, in the conserved hydrocarbon basis. */
    public PhaseSupport support(int component){return support[component];}
    /** Components whose omitted phase this layout holds at exactly zero, for the diagnostics counters. */
    public int singlePhaseComponentCount() {
        if(!liquidActive||!vaporActive)return 0;
        int count=0;for(int i:components)if(support[i].singlePhase())count++;
        return count;
    }
    /** Bulk component transport is independent of phase split, T and P at fixed total amounts and edge mass flow. */
    public boolean totalAmountVariable(int local) {
        if(local==waterLiquidIndex||local==waterVaporIndex)return true;
        for(int component:components)if(local==amountIndex[component])return true;
        return false;
    }
    public double amountScale(){return amountScale;}
    public double energyScale(){return energyScale;}
    /** Resolve trace amounts relative to their own scale rather than the bulk water inventory. */
    public double differenceScale(int local,double value){return Math.max(Math.abs(value),totalAmountVariable(local)?Math.max(1e-30,Math.abs(differenceScales[local])):1);}
    public double temperature(double[] variables,int offset){return 350*Math.exp(variables[offset+temperatureIndex]);}
    public boolean hasHydrocarbons(){return liquidActive||vaporActive;}
    public double[] encode(FluidThermodynamics.State state) {
        return encode(state,state.liquidView(),state.vaporView());
    }
    /**
     * {@link java.util.stream.DoubleStream#sum()} without the stream: the same Kahan compensation,
     * accumulated in the same order, so replacing a stream sum with this cannot move a result.
     * Every summation here used to be a stream, and the residual evaluates several per node.
     */
    public static double sum(double[] values) {
        double high=0,low=0,simple=0;
        for(double value:values) {
            double adjusted=value-low,next=high+adjusted;
            low=(next-high)-adjusted;high=next;simple+=value;
        }
        double total=high-low;
        return Double.isNaN(total)&&Double.isInfinite(simple)?simple:total;
    }
    private double[] encode(FluidThermodynamics.State state,double[] l,double[] v) {
        double[] x=new double[size],fl=null,fv=null;double phaseAmountRatio=0;
        for(int i:components){
            if(support[i]==PhaseSupport.BOTH) {
                x[liquidIndex[i]]=(l[i]+v[i])/amountScale;
                if(l[i]>0&&v[i]>0)x[vaporIndex[i]]=Math.log(v[i])-Math.log(l[i]);
                else {
                    if(fl==null) {
                        phaseAmountRatio=Math.log(sum(v)/sum(l));
                        fl=state.liquidProperties().logFugacityView();fv=state.vaporProperties().logFugacityView();
                    }
                    // The equilibrium split this component would reach at the seed's fugacity
                    // coefficients: ln(v_i/l_i) = ln(n_V/n_L) + ln phi_i^L - ln phi_i^V + ln(P/Pc).
                    // It is the seed for a phase that has just appeared, and for a trace component
                    // the active set has just reactivated, which is the same situation.
                    x[vaporIndex[i]]=phaseAmountRatio+fl[i]-fv[i]+Math.log(state.pressure()/state.hydrocarbonPartialPressure());
                }
            }
            // One retained phase, whether because the node carries only that phase or because the
            // frozen support omitted the other one. The unknown is the component total either way,
            // so an omitted trace is folded into the phase that is kept and the material closes.
            else x[amountIndex[i]]=(l[i]+v[i])/amountScale;
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
    /** {@code prepared} carries this node's temperature-only properties; it must be for the exact
     * temperature the variables decode to, and {@code null} evaluates them here as before. */
    public FluidThermodynamics.State decode(double[] variables,int offset,FluidThermodynamics.Prepared prepared) {
        double[] l=new double[liquidIndex.length],v=new double[l.length];
        for(int i:components){
            if(support[i]==PhaseSupport.BOTH) {
                double amount=amountScale*variables[offset+liquidIndex[i]],ratio=variables[offset+vaporIndex[i]];
                if(ratio>=0){l[i]=amount/(1+Math.exp(ratio));v[i]=amount-l[i];}
                else{v[i]=amount/(1+Math.exp(-ratio));l[i]=amount-v[i];}
            }
            // The omitted phase is written as exactly zero, never as a small number, so every
            // component total, every transport split and every conservation audit is exact.
            else if(liquidIndex[i]>=0)l[i]=amountScale*variables[offset+liquidIndex[i]];
            else v[i]=amountScale*variables[offset+vaporIndex[i]];
        }
        double wl=waterLiquidIndex<0?0:amountScale*variables[offset+waterLiquidIndex];
        double wv=waterVaporIndex<0?0:amountScale*variables[offset+waterVaporIndex];
        double t=350*Math.exp(variables[offset+temperatureIndex]),p=1e5*Math.exp(variables[offset+pressureIndex]);
        double pc=partialPressureIndex>=0?1e5*Math.exp(variables[offset+partialPressureIndex]):vaporActive?p:0;
        return model.adoptingState(t,p,l,v,wl,wv,pc,prepared);
    }
    /** Target amounts/U may include backward-Euler edge contributions computed in the same residual evaluation. */
    public void residual(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double targetVolume,double[] result,int offset,double[] variables) {
        residual(state,targetAmounts,targetEnergy,targetVolume,result,offset,variables,null);
    }
    public void residual(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double targetVolume,double[] result,int offset,
                         double[] variables,FluidThermodynamics.Prepared prepared) {
        double[] l=state.liquidView(),v=state.vaporView();
        int row=balanceRows(state,targetAmounts,targetEnergy,targetVolume,result,offset);
        equilibriumResidual(state,l,v,result,row,offset,variables,prepared);
    }
    /**
     * The rows a change of this node's accumulated targets can move, and no others: the component
     * balances, the water balance, the energy balance and the volume closure. The equilibrium and
     * closure rows below them read the decoded state alone, so a Jacobian sweep that perturbs a
     * <em>neighbour</em> leaves them at exactly the residual it already evaluated. Returns the
     * first row after this block.
     */
    public int balanceRows(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double targetVolume,
                           double[] result,int offset) {
        double[] l=state.liquidView(),v=state.vaporView();int row=offset;
        for(int i:components)result[row++]=(l[i]+v[i]-targetAmounts[i])/componentScales[i];
        if(waterLiquidIndex>=0||waterVaporIndex>=0)result[row++]=(state.waterLiquid()+state.waterVapor()-targetAmounts[l.length])/componentScales[l.length];
        result[row++]=(state.internalEnergy()-targetEnergy)/energyScale;
        result[row++]=(state.volume()-targetVolume)/targetVolume;
        return row;
    }
    /**
     * The change {@link #residual} would show if this node's target amounts and internal energy
     * moved by the given amounts, at a fixed state: the component rows carry it divided by their
     * own component scale and the energy row by the energy scale, in the same row order, while the
     * volume and equilibrium rows carry no target at all. A Newton step for that perturbation is
     * therefore {@code J*dx = +delta/scale}, because the residual subtracts the target.
     */
    public void targetRows(double[] deltaAmounts,double deltaEnergy,double[] rows,int offset) {
        if(deltaAmounts.length!=componentScales.length)throw new IllegalArgumentException("Balance basis mismatch");
        int row=offset,water=componentScales.length-1;
        for(int i:components)rows[row++]=deltaAmounts[i]/componentScales[i];
        if(waterLiquidIndex>=0||waterVaporIndex>=0)rows[row++]=deltaAmounts[water]/componentScales[water];
        rows[row]=deltaEnergy/energyScale;
    }
    /** Zero-holdup mixing: M-1 mass-fraction equations, continuity, enthalpy, and amount normalization. */
    public void junctionResidual(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                                 double netMassFlow,double[] result,int offset,double[] variables) {
        junctionResidual(state,incomingMassFractions,incomingSpecificEnthalpy,netMassFlow,result,offset,variables,null);
    }
    public void junctionResidual(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                                 double netMassFlow,double[] result,int offset,double[] variables,FluidThermodynamics.Prepared prepared) {
        double[] l=state.liquidView(),v=state.vaporView();
        int row=junctionRows(state,incomingMassFractions,incomingSpecificEnthalpy,netMassFlow,result,offset);
        equilibriumResidual(state,l,v,result,row,offset,variables,prepared);
    }
    /** The mixing block of {@link #junctionResidual}: everything the inflow and the net flow move,
     * and nothing the equilibrium rows below it read. Returns the first row after this block. */
    public int junctionRows(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                            double netMassFlow,double[] result,int offset) {
        var n=totalAmounts(state,state.liquidView(),state.vaporView());
        int row=offset;
        for(int index=0;index<junctionBasis.length-1;index++) {
            int i=junctionBasis[index];double mw=i==n.length-1?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(i);
            result[row++]=n[i]*mw/state.mass()-incomingMassFractions[i];
        }
        result[row++]=netMassFlow; // 1 kg/s reference scale
        result[row++]=(state.enthalpy()/state.mass()-incomingSpecificEnthalpy)/Math.max(1,energyScale/state.mass());
        result[row++]=sum(n)/amountScale-1;
        return row;
    }
    private void equilibriumResidual(FluidThermodynamics.State state,double[] l,double[] v,double[] result,int row,int offset,double[] variables,
                                     FluidThermodynamics.Prepared prepared) {
        if(liquidActive&&vaporActive) {
            if(state.liquidProperties()==null||state.vaporProperties()==null)throw new IllegalArgumentException("A phase vanished inside a fixed-regime Newton trial");
            double nl=sum(l),nv=sum(v);
            double[] fl=state.liquidProperties().logFugacityView(),fv=state.vaporProperties().logFugacityView();
            // The total amount cancels from the fugacity ratio, including at zero amount. A component
            // the frozen support reduced to one phase has no split unknown and therefore no row here:
            // its material balance is the whole of it, and this is the only equation it loses.
            for(int i:components)if(support[i]==PhaseSupport.BOTH)
                result[row++]=-variables[offset+vaporIndex[i]]+Math.log(nv/nl)+fl[i]-fv[i]+Math.log(state.pressure()/state.hydrocarbonPartialPressure());
        }
        if(waterLiquidIndex>=0&&waterVaporIndex>=0)result[row++]=Math.log(state.waterPartialPressure()
                /(prepared==null?model.saturationPressure(state.temperature()):model.saturationPressure(prepared)));
        if(partialPressureIndex>=0)result[row++]=(state.hydrocarbonPartialPressure()+state.waterPartialPressure()-state.pressure())/state.pressure();
        if(row!=offset+size)throw new IllegalStateException("Phase equation/unknown count mismatch");
    }
    /**
     * Components whose omitted phase this converged state no longer supports omitting, marked in
     * {@code promoted} for the next active-set pass; returns how many were newly marked.
     *
     * <p>The test is V3's reinsertion inequality, on the equilibrium relation this layout's own
     * equilibrium rows state. At a converged point the retained components satisfy
     * {@code ln y_i = ln x_i + ln phi_i^L - ln phi_i^V + ln(P/Pc)}, and the equation of state fills
     * {@code ln phi_i} for a component that is not in a phase as well - at infinite dilution, which is
     * exactly the regime an omitted trace is in - so the same expression says what mole fraction the
     * omitted phase <em>would</em> hold. When that reaches
     * {@link TraceTruncationPolicy#REINSERTION_FACTOR} times the cutoff, the reference the support was
     * derived from is no longer describing this state and the component gets both phases back; the
     * factor is the hysteresis that stops a component on the boundary from alternating.</p>
     *
     * <p>The seed for the restored phase needs no extra work: {@link #encode} already seeds a
     * component with an empty phase at exactly this ratio, because a phase that has just appeared and
     * a trace that has just been reactivated are the same situation.</p>
     */
    public int reactivate(FluidThermodynamics.State state,TraceTruncationPolicy policy,boolean[] promoted) {
        if(!policy.enabled()||!liquidActive||!vaporActive)return 0;
        if(promoted.length!=liquidIndex.length)throw new IllegalArgumentException("Reactivation basis mismatch");
        var lp=state.liquidProperties();var vp=state.vaporProperties();
        if(lp==null||vp==null)return 0;
        double[] l=state.liquidView(),v=state.vaporView(),fl=lp.logFugacityView(),fv=vp.logFugacityView();
        double nl=sum(l),nv=sum(v);
        if(!(nl>0&&nv>0))return 0;
        double pressureRatio=Math.log(state.pressure()/state.hydrocarbonPartialPressure());
        double threshold=Math.log(TraceTruncationPolicy.REINSERTION_FACTOR*policy.cutoffMoleFraction());
        int restored=0;
        for(int i:components) {
            if(!support[i].singlePhase()||promoted[i])continue;
            double implied=support[i]==PhaseSupport.LIQUID_ONLY
                    ?Math.log(l[i]/nl)+fl[i]-fv[i]+pressureRatio
                    :Math.log(v[i]/nv)-fl[i]+fv[i]-pressureRatio;
            if(implied>=threshold){promoted[i]=true;restored++;}
        }
        return restored;
    }
    /**
     * The per-component phase support of one node, derived from a full-basis reference state and
     * then frozen: V3's rule, on the seed's own phase compositions.
     *
     * <p>The reference is the seed the layout is built from and never an in-flight Newton iterate, so
     * the unknown set of a pass is fixed before the pass starts - which is what lets the sparsity
     * pattern, the colouring, the fill-reducing ordering and the retained factorization be keyed on
     * it. A component is reduced to one phase when its mole fraction in the other phase is below the
     * cutoff while this one's is not; when both are below it the reference cannot say which phase the
     * component belongs to and both are kept, as V3 does. {@code promoted} components are kept in
     * both phases regardless: the active set reactivated them during this solve, and a reactivation
     * may not be undone by a later pass of the same solve or the two would alternate.</p>
     *
     * <p>A node with only one active hydrocarbon phase gets that phase for every present component,
     * which is the layout it always had. At {@link TraceTruncationPolicy#OFF} this returns the
     * support of the unreduced layout for every component, so the whole path is the pre-truncation
     * one.</p>
     */
    public static PhaseSupport[] support(FluidThermodynamics.State seed,boolean[] componentMask,TraceTruncationPolicy policy,boolean[] promoted) {
        Objects.requireNonNull(seed);Objects.requireNonNull(policy);
        double[] l=seed.liquidView(),v=seed.vaporView();
        if(componentMask!=null&&componentMask.length!=l.length)throw new IllegalArgumentException("Component mask mismatch");
        if(promoted!=null&&promoted.length!=l.length)throw new IllegalArgumentException("Reactivation basis mismatch");
        var support=new PhaseSupport[l.length];
        boolean liquidActive=seed.liquidVolume()>0,vaporActive=sum(v)>0;
        double nl=liquidActive?sum(l):0,nv=vaporActive?sum(v):0;
        for(int i=0;i<l.length;i++) {
            if(!(l[i]+v[i]>0||componentMask!=null&&componentMask[i]))support[i]=PhaseSupport.ABSENT;
            else if(!vaporActive)support[i]=PhaseSupport.LIQUID_ONLY;
            else if(!liquidActive)support[i]=PhaseSupport.VAPOR_ONLY;
            else if(promoted!=null&&promoted[i])support[i]=PhaseSupport.BOTH;
            else support[i]=PhaseSupport.of(l[i]/nl,v[i]/nv,policy.cutoffMoleFraction());
        }
        return support;
    }
    public static double[] totalAmounts(FluidThermodynamics.State state) {
        return totalAmounts(state,state.liquidView(),state.vaporView());
    }
    private static double[] totalAmounts(FluidThermodynamics.State state,double[] l,double[] v) {
        double[] n=Arrays.copyOf(l,l.length+1);
        for(int i=0;i<l.length;i++)n[i]+=v[i];n[l.length]=state.waterLiquid()+state.waterVapor();return n;
    }
    public SparseNewton.Equations fixedInventory(double[] amounts,double energy,double volume) {
        double[] target=amounts.clone();
        return new SparseNewton.Equations(){
            private volatile FluidThermodynamics.Prepared prepared;
            public int size(){return PhaseLayout.this.size;}
            public double differenceScale(int column,double value){return PhaseLayout.this.differenceScale(column,value);}
            public double maximumStep(double[] x,double[] direction){double alpha=1;for(int c=0;c<x.length;c++)if(totalAmountVariable(c)&&x[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*x[c]/-direction[c]);return alpha;}
            public int[][] columnRows(){int[][] rows=new int[size()][size()];for(int[] column:rows)for(int i=0;i<column.length;i++)column[i]=i;return rows;}
            public double[] residual(double[] x){
                double temperature=PhaseLayout.this.temperature(x,0);
                var local=prepared;
                if(local==null||local.temperature()!=temperature)prepared=local=model.prepare(temperature);
                double[] f=new double[size()];PhaseLayout.this.residual(decode(x,0,local),target,energy,volume,f,0,x,local);return f;
            }
        };
    }
}
