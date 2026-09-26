using System;
using System.Linq;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms;
using DWSIM.Interfaces.Enums;

// Research-only gamma/phi outer correction for the native VLLE bubble routine.
internal static class CorrectedWaterPvFlash {
    internal static object[] Solve(double[] z,double pressure,ChemSepLoggedPr78 pp) {
        int water=Array.IndexOf(pp.RET_VNAMES(),"Water");
        if(water<0||z[water]<=1e-8||z[water]>=1-1e-8)throw new InvalidOperationException("Correction requires a water/hydrocarbon mixture.");
        double[] a=new double[z.Length];a[water]=1;
        double[] b=z.Select((v,i)=>i==water?0:v/(1-z[water])).ToArray();
        double[] y=(double[])z.Clone();double t=350,l1=z[water],l2=1-z[water];
        object[] result=null;
        try {
            for(int outer=0;outer<30;outer++) {
                pp.FrozenVaporPhi=null;
                double[] vaporPhi=pp.DW_CalcFugCoeff(y,t,pressure,State.Vapor);
                pp.FrozenVaporPhi=vaporPhi;
                var solver=new NestedLoops3PV3();
                solver.FlashSettings=pp.FlashSettings.ToDictionary(k=>k.Key,k=>k.Value);
                solver.FlashSettings[FlashSetting.PTFlash_Internal_Loop_Tolerance]="1E-10";
                solver.FlashSettings[FlashSetting.PTFlash_External_Loop_Tolerance]="1E-10";
                result=(object[])solver.Flash_PV_3P(z,0,l1,l2,y,a,b,pressure,0,t,pp,false,null);
                l1=Convert.ToDouble(result[0]);l2=Convert.ToDouble(result[7]);t=Convert.ToDouble(result[4]);
                a=(double[])result[2];b=(double[])result[8];y=(double[])result[3];
                pp.FrozenVaporPhi=null;
                var updated=pp.DW_CalcFugCoeff(y,t,pressure,State.Vapor);
                double error=updated.Zip(vaporPhi,(v,w)=>Math.Abs(Math.Log(v/w))).Max();
                if(error<1e-9){Console.WriteLine("Corrected VLLE outer iterations="+(outer+1)+" T="+t);return result;}
            }
            throw new InvalidOperationException("Vapor-fugacity outer correction did not converge.");
        } finally {pp.FrozenVaporPhi=null;}
    }
}

internal sealed class CorrectedWaterPvDispatcher : UniversalFlash {
    public override object Flash_PV(double[] z,double p,double v,double t,PropertyPackage package,bool reuse=false,double[] previous=null) {
        int water=Array.IndexOf(package.RET_VNAMES(),"Water");
        if(v==0&&water>=0&&z[water]>1e-8&&z[water]<1-1e-8) {
            try {return CorrectedWaterPvFlash.Solve(z,p,(ChemSepLoggedPr78)package);}
            catch(Exception threePhaseError) {
                var solver=new NestedLoops();solver.FlashSettings=this.FlashSettings;
                var result=(object[])solver.Flash_PV(z,p,v,t,package,reuse,previous);
                var x=(double[])result[2];var y=(double[])result[3];double tt=Convert.ToDouble(result[4]);
                var pl=package.DW_CalcFugCoeff(x,tt,p,State.Liquid);var pv=package.DW_CalcFugCoeff(y,tt,p,State.Vapor);
                double defect=0,bubble=0;
                for(int c=0;c<x.Length;c++){bubble+=x[c]*pl[c]/pv[c];if(x[c]>1e-6&&y[c]>1e-6)defect=Math.Max(defect,Math.Abs(Math.Log(x[c]*pl[c]/(y[c]*pv[c]))));}
                if(defect>1e-3||Math.Abs(bubble-1)>1e-4)throw new InvalidOperationException("VLLE correction failed and VLE fallback failed independent equilibrium checks.",threePhaseError);
                Console.WriteLine("Validated VLE fallback T="+tt+" water="+z[water]);
                return result;
            }
        }
        return base.Flash_PV(z,p,v,t,package,reuse,previous);
    }
}
