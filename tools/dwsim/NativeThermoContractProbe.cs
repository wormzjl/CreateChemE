using System;
using System.Linq;
using System.IO;
using System.Collections;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Interfaces.Enums;

internal static class NativeThermoContractProbe {
    private static double H(PropertyPackage pp,double[] z,double t,double p,State phase) {
        return (pp.DW_CalcEnthalpy(z,t,p,phase)+pp.AUX_HFm25(z))*pp.AUX_MMM(z);
    }
    internal static void Run(object package,object stream,Dictionary<string,object> data,string output) {
        var pp=(PropertyPackage)package;dynamic ms=stream;
        var rows=new List<object>();
        foreach(Dictionary<string,object> point in (IEnumerable)data["probe_states"]) {
            double[] z=((IEnumerable)point["z"]).Cast<object>().Select(Convert.ToDouble).ToArray();
            if(data.ContainsKey("water_first")&&Convert.ToBoolean(data["water_first"]))z=new[]{z.Last()}.Concat(z.Take(z.Length-1)).ToArray();
            double sum=z.Sum();z=z.Select(v=>v/sum).ToArray();
            double t=Convert.ToDouble(point["temperature_K"]),p=250000;
            foreach(State phase in new[]{State.Liquid,State.Vapor}) {
                var row=new Dictionary<string,object>();row["label"]=point["label"];row["phase"]=phase.ToString();row["temperature_K"]=t;
                try {
                    ms.SetTemperature(t);ms.SetPressure(p);ms.SetOverallComposition(z);
                    ms.SetPhaseComposition(z,phase==State.Liquid ? Phase.Liquid1 : Phase.Vapor);
                    pp.CurrentMaterialStream=(DWSIM.Interfaces.IMaterialStream)ms;
                    double cp=pp.DW_CalcCp_ISOL(phase==State.Liquid?Phase.Liquid1:Phase.Vapor,t,p)*pp.AUX_MMM(z);
                    double dh=(H(pp,z,t+0.001,p,phase)-H(pp,z,t-0.001,p,phase))/0.002;
                    row["cp_J_mol_K"]=cp;row["finite_dH_dT_J_mol_K"]=dh;row["relative_cp_error"]=(cp-dh)/Math.Max(1,Math.Abs(dh));
                    var moleTests=new List<object>();
                    foreach(double scale in new[]{1.0,1.0001,0.9999,1.01}) {
                        double[] n=z.Select(v=>v*scale).ToArray();double[] reported=pp.DW_CalcdEnthalpydmoles(n,t,p,phase);
                        var finite=new double[n.Length];double step=1e-6;
                        for(int i=0;i<n.Length;i++) {
                            double[] plus=(double[])n.Clone();plus[i]+=step;
                            double ns=plus.Sum();double hp=H(pp,plus.Select(v=>v/ns).ToArray(),t,p,phase)*ns;
                            double h0=H(pp,z,t,p,phase)*scale;
                            finite[i]=(hp-h0)/step;
                        }
                        moleTests.Add(new {input_sum=scale,reported_partial_molar_J_mol=reported,finite_partial_molar_J_mol=finite,
                            max_abs_error=reported.Zip(finite,(a,b)=>Math.Abs(a-b)).Max(),euler_error=reported.Zip(z,(a,b)=>a*b).Sum()-H(pp,z,t,p,phase)});
                    }
                    row["mole_derivative_tests"]=moleTests;
                }catch(Exception e){row["error"]=e.ToString();}
                rows.Add(row);
            }
            foreach(string mode in new[]{"Default","VLE","VLLE","VLE-strict","VLLE-strict","VLE-tight","VLLE-tight","pv1-zero","pv-wrapper","pv1-no-azeotrope-shortcut","saturated-newton","legacy-pv4","seeded-3phase","corrected-3phase"}) {
                if((mode=="seeded-3phase"||mode=="corrected-3phase")&&!point["label"].ToString().Contains("initializer"))continue;
                var row=new Dictionary<string,object>();row["label"]=point["label"];row["flash_mode"]=mode;
                try {
                    pp.FlashSettings[FlashSetting.ForceEquilibriumCalculationType]=mode.Contains("newton")||mode.Contains("pv")?"VLE":mode.Replace("-strict","").Replace("-tight","");
                    pp.FlashSettings[FlashSetting.PTFlash_Internal_Loop_Tolerance]=mode.Contains("tight")||mode=="seeded-3phase"?"1E-10":"0.0001";
                    pp.FlashSettings[FlashSetting.PTFlash_External_Loop_Tolerance]=mode.Contains("tight")||mode=="seeded-3phase"?"1E-10":"0.0001";
                    pp.FlashSettings[FlashSetting.FailSafeCalculationMode]=mode.Contains("strict")?"3":"1";
                    pp.FlashSettings[FlashSetting.PVFlash_TryIdealCalcOnFailure]=mode.Contains("strict")?"False":"True";
                    ms.SetOverallComposition(z);ms.SetTemperature(t);ms.SetPressure(p);ms.Phases[2].Properties.molarfraction=0.0;
                    var timer=System.Diagnostics.Stopwatch.StartNew();
                    double tt;double[] x,y;object[] raw=null;
                    if(mode=="corrected-3phase") {
                        raw=CorrectedWaterPvFlash.Solve(z,p,(ChemSepLoggedPr78)pp);tt=Convert.ToDouble(raw[4]);x=(double[])raw[2];y=(double[])raw[3];
                    } else if(mode=="seeded-3phase") {
                        int wi=Array.IndexOf(pp.RET_VNAMES(),"Water");double water=z[wi];
                        var aqueous=new double[z.Length];aqueous[wi]=1;double[] organic=z.Select((v,i)=>i==wi?0:v/(1-water)).ToArray();
                        var three=new DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.NestedLoops3PV3();three.FlashSettings=pp.FlashSettings;
                        raw=(object[])three.Flash_PV_3P(z,0,water,1-water,z,aqueous,organic,p,0,350,pp,false,null);
                        tt=Convert.ToDouble(raw[4]);x=(double[])raw[2];y=(double[])raw[3];
                    } else if(mode=="saturated-newton"||mode=="legacy-pv4"||mode.StartsWith("pv")) {
                        var algorithm=new DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.NestedLoops();algorithm.FlashSettings=pp.FlashSettings;
                        if(mode=="pv1-no-azeotrope-shortcut") algorithm.GetType().GetField("CalculatingAzeotrope",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.Public|System.Reflection.BindingFlags.NonPublic).SetValue(algorithm,true);
                        raw=(object[])(mode=="saturated-newton"?algorithm.Flash_PV_Saturated_Newton(z,p,0,t,pp,false,null):mode=="legacy-pv4"?algorithm.Flash_PV_4(z,p,0,t,pp,false,null):mode.StartsWith("pv1")?algorithm.Flash_PV_1(z,p,0,0,pp,false,null,false):algorithm.Flash_PV(z,p,0,0,pp,false,null));
                        if(raw.Length<5)throw new InvalidOperationException("Native routine returned a failure sentinel.");
                        tt=Convert.ToDouble(raw[4]);x=(double[])raw[2];y=(double[])raw[3];
                    } else {pp.DW_CalcEquilibrium(FlashSpec.P,FlashSpec.VAP);tt=(double)ms.GetTemperature();x=ms.GetPhaseComposition(3);y=ms.GetPhaseComposition(2);}
                    timer.Stop();
                    double[] phiL=pp.DW_CalcFugCoeff(x,tt,p,State.Liquid),phiV=pp.DW_CalcFugCoeff(y,tt,p,State.Vapor);
                    double defect=0;for(int i=0;i<z.Length;i++)if(x[i]>1e-12&&y[i]>1e-12)defect=Math.Max(defect,Math.Abs(Math.Log(x[i]*phiL[i]/(y[i]*phiV[i]))));
                    row["ms"]=timer.ElapsedMilliseconds;row["temperature_K"]=tt;row["liquid1_fraction"]=ms.Phases[3].Properties.molarfraction;
                    row["liquid2_fraction"]=ms.Phases[4].Properties.molarfraction;row["vapor_fraction"]=ms.Phases[2].Properties.molarfraction;
                    row["x"]=x;row["y"]=y;row["max_log_fugacity_defect"]=defect;
                    row["liquid_fugacity_coefficients"]=phiL;row["vapor_fugacity_coefficients"]=phiV;
                    double l1=raw!=null?Convert.ToDouble(raw[0]):Convert.ToDouble(ms.Phases[3].Properties.molarfraction);
                    double l2=raw!=null?Convert.ToDouble(raw[7]):Convert.ToDouble(ms.Phases[4].Properties.molarfraction);
                    double vv=raw!=null?Convert.ToDouble(raw[1]):Convert.ToDouble(ms.Phases[2].Properties.molarfraction);
                    double[] x2=raw!=null?(double[])raw[8]:(double[])ms.GetPhaseComposition(4);
                    double activeDefect=0,closure=0;
                    for(int c=0;c<z.Length;c++)closure=Math.Max(closure,Math.Abs(z[c]-l1*x[c]-l2*x2[c]-vv*y[c]));
                    foreach(var liquidPhase in new[]{Tuple.Create(l1,x),Tuple.Create(l2,x2)})if(liquidPhase.Item1>1e-8){
                        double[] ph=pp.DW_CalcFugCoeff(liquidPhase.Item2,tt,p,State.Liquid);
                        for(int c=0;c<z.Length;c++)if(liquidPhase.Item2[c]>1e-6&&y[c]>1e-6)activeDefect=Math.Max(activeDefect,Math.Abs(Math.Log(liquidPhase.Item2[c]*ph[c]/(y[c]*phiV[c]))));
                    }
                    row["active_phase_equilibrium_defect"]=activeDefect;row["component_balance_defect"]=closure;
                    row["returned_phase_fractions"]=new[]{l1,l2,vv};row["second_liquid_composition"]=x2;
                    row["liquid1_fraction"]=l1;row["liquid2_fraction"]=l2;row["vapor_fraction"]=vv;
                    row["component_names"]=pp.RET_VNAMES();
                    row["liquid_Z"]=pp.AUX_Z(x,tt,p,PhaseName.Liquid);
                    row["vapor_Z"]=pp.AUX_Z(y,tt,p,PhaseName.Vapor);
                    if(raw!=null){row["raw_iterations"]=raw[5];row["raw_K"]=raw[6];row["raw_bubble_equation_error"]=((double[])raw[6]).Zip(x,(k,xx)=>k*xx).Sum()-1;if(raw.Length>11)row["raw_delta_T"]=raw[11];}
                }catch(Exception e){row["error"]=e.ToString();}
                rows.Add(row);
            }
        }
        File.WriteAllText(Path.Combine(output,"thermo-contract.json"),new JavaScriptSerializer{MaxJsonLength=16777216}.Serialize(rows));
    }
}
