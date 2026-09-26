using System;
using System.Threading;
using System.Linq;
using System.IO;
using System.Web.Script.Serialization;
using DWSIM.Thermodynamics.PropertyPackages;

// Research instrumentation. Defaults delegate to DWSIM; the explicitly enabled
// experimental PV correction temporarily supplies a frozen vapor-fugacity factor.
[Serializable]
public class ChemSepLoggedPr78 : PengRobinson1978PropertyPackage {
    public static int MaximumDiagnosticFlashCalls;
    public static string FlashValidationDirectory;
    public double[] FrozenVaporPhi;
    public static bool CorrectWaterPv;
    public override DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.FlashAlgorithm FlashBase {
        get {
            var selected=base.FlashBase;
            if(CorrectWaterPv&&selected is DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.UniversalFlash)
                return new CorrectedWaterPvDispatcher {FlashSettings=this.FlashSettings};
            return selected;
        }
    }
    private static long fugacityCalls;
    private static long enthalpyCalls;
    private static long propertyCalls;
    private static long flashes;
    private static long moleDerivativeCalls;
    public override double[] DW_CalcdEnthalpydmoles(double[] x,double t,double p,State phase) {
        long count=Interlocked.Increment(ref moleDerivativeCalls);
        double[] result=base.DW_CalcdEnthalpydmoles(x,t,p,phase);
        double sum=x.Sum();
        if(count<=4 || (Math.Abs(sum-1)>1e-8 && count%100==0)) Console.WriteLine("THERMO enthalpy Dmoles #"+count+" sum="+sum.ToString("G17")+" maxAbs="+result.Select(Math.Abs).Max().ToString("G17")+" T="+t);
        return result;
    }
    public override double[] DW_CalcFugCoeff(Array composition,double temperature,double pressure,State state) {
        long n=Interlocked.Increment(ref fugacityCalls);
        if(n<=4 || n%20000==0) Console.WriteLine("THERMO fugacity #"+n+" T="+temperature+" P="+pressure+" phase="+state);
        double[] result=base.DW_CalcFugCoeff(composition,temperature,pressure,state);
        if(state==State.Liquid && FrozenVaporPhi!=null)for(int c=0;c<result.Length;c++)result[c]/=FrozenVaporPhi[c];
        return result;
    }
    public override double DW_CalcEnthalpy(Array composition,double temperature,double pressure,State state) {
        long n=Interlocked.Increment(ref enthalpyCalls);
        if(n<=4 || n%20000==0) Console.WriteLine("THERMO enthalpy #"+n+" T="+temperature+" P="+pressure+" phase="+state);
        return base.DW_CalcEnthalpy(composition,temperature,pressure,state);
    }
    public override void DW_CalcProp(string property,Phase phase) {
        long n=Interlocked.Increment(ref propertyCalls);
        if(n<=30 || n%5000==0) Console.WriteLine("THERMO property #"+n+" "+property+" "+phase);
        base.DW_CalcProp(property,phase);
    }
    public override void DW_CalcEquilibrium(FlashSpec a,FlashSpec b) {
        long n=Interlocked.Increment(ref flashes);
        double[] before=this.CurrentMaterialStream.GetOverallComposition();
        if(MaximumDiagnosticFlashCalls>0 && n>MaximumDiagnosticFlashCalls) throw new InvalidOperationException("Research diagnostic flash-call limit reached (not a physical infeasibility result).");
        if(n<=10 || n%100==0) Console.WriteLine("THERMO equilibrium BEGIN #"+n+" "+a+" "+b+" z="+string.Join(",",this.CurrentMaterialStream.GetOverallComposition())+" flash="+(this.FlashAlgorithm==null ? "default" : this.FlashAlgorithm.GetType().Name));
        if(n==7) Console.WriteLine(Environment.StackTrace);
        base.DW_CalcEquilibrium(a,b);
        if(FlashValidationDirectory!=null && a==FlashSpec.P && b==FlashSpec.VAP) {
            double t=this.CurrentMaterialStream.GetTemperature(),p=this.CurrentMaterialStream.GetPressure();
            double[] y=this.CurrentMaterialStream.GetPhaseComposition(2),pv=base.DW_CalcFugCoeff(y,t,p,State.Vapor);
            foreach(int phaseIndex in new[]{3,4}) {
            if(this.CurrentMaterialStream.Phases[phaseIndex].Properties.molarfraction.GetValueOrDefault()<1e-8)continue;
            double[] x=this.CurrentMaterialStream.GetPhaseComposition(phaseIndex),pl=base.DW_CalcFugCoeff(x,t,p,State.Liquid);
            double max=0;int worst=-1;
            for(int c=0;c<x.Length;c++)if(x[c]>1e-6&&y[c]>1e-6){double e=Math.Abs(Math.Log(x[c]*pl[c]/(y[c]*pv[c])));if(e>max){max=e;worst=c;}}
            if(max>1e-2) {
                File.WriteAllText(Path.Combine(FlashValidationDirectory,"rejected-native-flash.json"),new JavaScriptSerializer().Serialize(new {flash_call=n,temperature_K=t,pressure_Pa=p,input_z=before,phase_index=phaseIndex,component_names=this.RET_VNAMES(),x=x,y=y,phi_liquid=pl,phi_vapor=pv,max_log_fugacity_defect=max,worst_component=worst}));
                throw new InvalidOperationException("Native PV flash returned inconsistent equilibrium: max |ln(fL/fV)|="+max.ToString("G17")+" at "+this.RET_VNAMES()[worst]+"; research validation rejected the result.");
            }
            }
        }
        if(n<=10 || n%100==0) Console.WriteLine("THERMO equilibrium END #"+n+" "+a+" "+b);
    }
    public override object DW_CalcBubT(Array composition,double pressure,double temperature,Array k,bool reuse) {
        Console.WriteLine("THERMO bubble T BEGIN P="+pressure+" T="+temperature);
        object value=base.DW_CalcBubT(composition,pressure,temperature,k,reuse);
        Console.WriteLine("THERMO bubble T END");return value;
    }
}
