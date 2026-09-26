package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.Locale;

/**
 * F4 probe: the network's nitrogen model at cryogenic states, read through the translated PR78 directly so the
 * domain checks do not stand in the way, against the NIST points in ../nist/. Prints saturation pressure,
 * saturated liquid density, vapour Cp and ideal-gas Cp. The network liquid is PR78 at the 2 MPa reference with
 * the global compressibility response (HydrocarbonModel.phase); the "pure PR" line evaluates the liquid at P.
 */
public final class NitrogenCryogenicProbe {
    public static void main(String[] args) {
        var model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        var hc=model.hydrocarbon;int n=hc.componentCount(),nitrogen=hc.components().indexOf("Nitrogen");
        double[] x=new double[n];x[nitrogen]=1;double mw=hc.molecularWeight(nitrogen);
        var eos=hc.translated();var response=new GlobalLiquidResponse(1e-9);
        double t=77.355;
        double pNetwork=saturation(eos,response,t,x,nitrogen,true),pPure=saturation(eos,response,t,x,nitrogen,false);
        System.out.printf(Locale.ROOT,"Psat(77.355 K) network=%.3f Pa pure-PR=%.3f Pa NIST=101325.059 Pa  err network=%.3f%% pure=%.3f%%%n",
                pNetwork,pPure,100*(pNetwork/101325.059-1),100*(pPure/101325.059-1));
        for(double p:new double[]{101325.059,pNetwork}) {
            double vNet=liquid(eos,response,t,p,x).molarVolume();
            var ws=eos.prepare(t);double vPure=eos.evaluate(t,p,x,PhaseRoot.LIQUID,ws).molarVolume();
            System.out.printf(Locale.ROOT,"rho_L(77.355 K, %.3f Pa) network=%.4f pure-PR=%.4f NIST=806.0844 kg/m3  err network=%.3f%% pure=%.3f%%%n",
                    p,mw/vNet,mw/vPure,100*(mw/vNet/806.084401826-1),100*(mw/vPure/806.084401826-1));
        }
        double[][] nist={{100,30.0249292065},{200,29.2321313603}};
        for(double[] row:nist) {
            var ws=eos.prepare(row[0]);var v=eos.evaluate(row[0],101325,x,PhaseRoot.VAPOR,ws);
            double h=1e-3;var up=eos.evaluate(row[0]+h,101325,x,PhaseRoot.VAPOR,eos.prepare(row[0]+h));var dn=eos.evaluate(row[0]-h,101325,x,PhaseRoot.VAPOR,eos.prepare(row[0]-h));
            double numeric=(up.molarEnthalpy()-dn.molarEnthalpy())/(2*h);
            System.out.printf(Locale.ROOT,"Cp_V(%.0f K, 101325 Pa) analytic=%.5f numeric=%.5f NIST=%.5f J/mol/K err=%.3f%%%n",row[0],v.heatCapacity(),numeric,row[1],100*(v.heatCapacity()/row[1]-1));
        }
        // Saturation sweep against the NIST table (../nist/sat-triple-to-critical.tsv), every fifth kelvin or so.
        try{var rows=java.nio.file.Files.readAllLines(java.nio.file.Path.of(args.length>0?args[0]:"../nist/sat-triple-to-critical.tsv"));double last=-10;
            System.out.println("T_K  Psat_NIST_Pa  Psat_model_Pa  err_%  rhoL_NIST  rhoL_model(at NIST Psat)  err_%");
            for(String row:rows.subList(1,rows.size())){var c=row.split("\t");double ts=Double.parseDouble(c[0]);if(ts-last<5&&ts<126)continue;if(ts>122)break;last=ts;
                double pn=Double.parseDouble(c[1])*1000,rn=Double.parseDouble(c[2]);double pm=saturation(eos,response,ts,x,nitrogen,true);double rm=mw/liquid(eos,response,ts,pn,x).molarVolume();
                System.out.printf(Locale.ROOT,"%.3f %.1f %.1f %+.3f %.3f %.3f %+.3f%n",ts,pn,pm,100*(pm/pn-1),rn,rm,100*(rm/rn-1));}
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        for(double tt:new double[]{63.16,78.16,98.16,148.16,198.16,248.16,273.16,298.16}) {
            var ws=eos.prepare(tt);var v=eos.evaluate(tt,1,x,PhaseRoot.VAPOR,ws);
            System.out.printf(Locale.ROOT,"Cp0(%.2f K) model=%.6f%n",tt,v.heatCapacity());
        }
    }
    static TranslatedPengRobinson.Phase liquidReference(TranslatedPengRobinson eos,double t,double[] x){return eos.evaluate(t,HydrocarbonModel.REFERENCE_PRESSURE,x,PhaseRoot.LIQUID,eos.prepare(t));}
    record Liquid(double molarVolume,double logPhi){}
    static Liquid liquid(TranslatedPengRobinson eos,GlobalLiquidResponse response,double t,double p,double[] x) {
        var ref=liquidReference(eos,t,x);
        var s=response.evaluate(t,p,HydrocarbonModel.REFERENCE_PRESSURE,ref.molarVolume(),ref.volumeTemperatureDerivative(),ref.volumeSecondTemperatureDerivative(),ref.molarEnthalpy(),ref.heatCapacity());
        double[] phi=response.logFugacity(t,p,HydrocarbonModel.REFERENCE_PRESSURE,s.pressureIntegral(),ref.logFugacityCoefficientsView(),ref.partialMolarVolumesView());
        int i=0;for(int c=0;c<x.length;c++)if(x[c]>0)i=c;
        return new Liquid(s.molarVolume(),phi[i]);
    }
    static double saturation(TranslatedPengRobinson eos,GlobalLiquidResponse response,double t,double[] x,int i,boolean network) {
        double lo=1e3,hi=2e6;
        for(int k=0;k<200;k++) {
            double p=Math.sqrt(lo*hi);
            double fl=network?liquid(eos,response,t,p,x).logPhi():eos.evaluate(t,p,x,PhaseRoot.LIQUID,eos.prepare(t)).logFugacityCoefficientsView()[i];
            double fv=eos.evaluate(t,p,x,PhaseRoot.VAPOR,eos.prepare(t)).logFugacityCoefficientsView()[i];
            if(fl>fv)lo=p;else hi=p;
        }
        return Math.sqrt(lo*hi);
    }
}
