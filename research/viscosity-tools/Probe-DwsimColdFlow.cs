using System;
using System.IO;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Web.Script.Serialization;

// Offline research only: never changes or saves the source flowsheet or the material catalog.
class ProbeDwsimColdFlow {
 static object Finite(double value) { return double.IsNaN(value)||double.IsInfinity(value)?(object)value.ToString(System.Globalization.CultureInfo.InvariantCulture):value; }
 static object Crossings(Func<double,double> f,double target,double low,double high) {
  var roots=new List<double>();double a=low,fa=f(a)-target;
  while(a<high) {
   double b=Math.Min(a+1,high),fb=f(b)-target;
   if(double.IsNaN(fa)||double.IsInfinity(fa)||double.IsNaN(fb)||double.IsInfinity(fb))throw new Exception("Invalid viscosity scan");
   if(fa*fb<0) {
    double l=a,h=b,fl=fa;
    for(int i=0;i<50;i++){double m=(l+h)/2,fm=f(m)-target;if(fl*fm<=0)h=m;else{l=m;fl=fm;}}
    roots.Add((l+h)/2);
   }
   if(fb==0)roots.Add(b);a=b;fa=fb;
  }
  return new {threshold_pascal_seconds=target,temperatures_kelvin=roots,minimum_temperature_kelvin=low,maximum_temperature_kelvin=high};
 }
 [STAThread] static int Main(string[] args) {
  try{return Run(args);}catch(Exception e){Console.Error.WriteLine(e);return 1;}
 }
 static int Run(string[] args) {
  string install=Path.GetFullPath(args[0]),source=Path.GetFullPath(args[1]),output=Path.GetFullPath(args[2]);
  AppDomain.CurrentDomain.AssemblyResolve+=(sender,e)=>{var p=Path.Combine(install,new AssemblyName(e.Name).Name+".dll");return File.Exists(p)?Assembly.LoadFrom(p):null;};
  Directory.SetCurrentDirectory(install);
  dynamic automation=Activator.CreateInstance(Assembly.LoadFrom(Path.Combine(install,"DWSIM.Automation.dll")).GetType("DWSIM.Automation.Automation3",true));
  dynamic fs=automation.LoadFlowsheet2(source);
  var thermo=Assembly.LoadFrom(Path.Combine(install,"DWSIM.Thermodynamics.dll"));
  dynamic pp=Activator.CreateInstance(thermo.GetType("DWSIM.Thermodynamics.PropertyPackages.PengRobinson1978PropertyPackage",true));
  fs.AddPropertyPackage(pp);
  dynamic kind=Enum.Parse(Assembly.LoadFrom(Path.Combine(install,"DWSIM.Interfaces.dll")).GetType("DWSIM.Interfaces.Enums.GraphicObjects.ObjectType"),"MaterialStream");
  dynamic stream=fs.AddObject(kind,10,10,"Cold-flow research");stream.PropertyPackage=pp;pp.CurrentMaterialStream=stream;
  var props=thermo.GetType("DWSIM.Thermodynamics.PropertyPackages.Auxiliary.PROPS",true);
  var twu=props.GetMethod("oilvisc_twu");var letsou=props.GetMethod("viscl_letsti");
  var rows=new List<object>();
  foreach(DictionaryEntry entry in (IDictionary)fs.SelectedCompounds) {
   dynamic cp=entry.Value;string name=(string)cp.Name;if(!name.StartsWith("TJL_PC"))continue;
   var metadata=new Dictionary<string,object>();
   foreach(string key in new[]{"Molar_Weight","Normal_Boiling_Point","Critical_Temperature","Critical_Pressure","Acentric_Factor","PF_SG","PF_Tv1","PF_Tv2","PF_v1","PF_v2","PF_vA","PF_vB","TemperatureOfFusion","EnthalpyOfFusionAtTf"})
    metadata[key]=Finite(Convert.ToDouble(entry.Value.GetType().GetProperty(key).GetValue(entry.Value,null)));
   Func<double,double> viscosity=t=>(double)pp.AUX_LIQVISCi(name,t,100000.0);
   var samples=new List<object>();
   foreach(double t in new[]{293.15,298.0,298.15,310.95,323.15,348.15,372.05,423.15}) {
    double raw=(double)twu.Invoke(null,new object[]{t,(double)cp.PF_Tv1,(double)cp.PF_Tv2,(double)cp.PF_v1,(double)cp.PF_v2});
    double fallback=(double)letsou.Invoke(null,new object[]{t,(double)cp.Critical_Temperature,(double)cp.Critical_Pressure,(double)cp.Acentric_Factor,(double)cp.Molar_Weight});
    double density=(double)pp.AUX_LIQDENSi(cp,t);
    samples.Add(new{temperature_kelvin=t,viscosity_pascal_seconds=Finite(viscosity(t)),density_kg_per_cubic_metre=Finite(density),twu_kinematic_raw=Finite(raw),letsou_raw=Finite(fallback),fallback_times_density=Finite(fallback*density)});
   }
   double high=Math.Min(900,0.95*(double)cp.Critical_Temperature);
   var crossings=new List<object>();foreach(double target in new[]{1.0,10.0,100.0})crossings.Add(Crossings(viscosity,target,293.15,high));
   rows.Add(new{name=name,metadata=metadata,samples=samples,crossings=crossings});
   Console.WriteLine(name+": fusion="+cp.TemperatureOfFusion+" K; mu(298 K)="+viscosity(298)+" Pa s");
  }
  var json=new JavaScriptSerializer{MaxJsonLength=16777216};
  File.WriteAllText(output,json.Serialize(new{engine_version=thermo.GetName().Version.ToString(),source_characterization=Path.GetFileName(source),pressure_pascal=100000,records=rows}));
  return 0;
 }
}
