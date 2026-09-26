using System;
using System.IO;
using System.Reflection;
using System.Collections.Generic;
using System.Linq;
using System.Web.Script.Serialization;

// Offline conditional-solute references for DWSIM-style logarithmic liquid-mixture viscosity.
// These are NOT claims that a pure liquid exists above its critical temperature.
class ExportDissolvedViscosity {
 static string install;
 static Type TypeOf(string dll,string name) { return Assembly.LoadFrom(Path.Combine(install,dll+".dll")).GetType(name,true); }
 static dynamic New(string dll,string name) { return Activator.CreateInstance(TypeOf(dll,name)); }
 static double Value(Func<double,double> f,double t) { double v=f(t); if(v<=0||double.IsNaN(v)||double.IsInfinity(v))throw new Exception("Invalid reference at "+t);return v; }
 static void Refine(Func<double,double> f,double a,double b,SortedDictionary<double,double> knots,List<object> checks,int depth) {
  bool split=false;
  foreach(double fraction in new[]{.25,.5,.75}) {
   double t=a+(b-a)*fraction,reference=Value(f,t);
   double predicted=Math.Exp(Math.Log(knots[a])+(Math.Log(knots[b])-Math.Log(knots[a]))*fraction);
   if(Math.Abs(predicted/reference-1)>.0005)split=true;
  }
  if(split) {
   if(depth>=18)throw new Exception("Interpolation failed");
   double m=(a+b)/2;knots[m]=Value(f,m);Refine(f,a,m,knots,checks,depth+1);Refine(f,m,b,knots,checks,depth+1);
  } else {
   foreach(double fraction in new[]{.25,.5,.75}) { double t=a+(b-a)*fraction;checks.Add(new{temperature_kelvin=t,viscosity_pascal_seconds=Value(f,t)}); }
  }
 }
 [STAThread] static int Main(string[] args) {
  try {
   install=Path.GetFullPath(args[0]);string output=Path.GetFullPath(args[1]);
   AppDomain.CurrentDomain.AssemblyResolve+=(sender,e)=>{string path=Path.Combine(install,new AssemblyName(e.Name).Name+".dll");return File.Exists(path)?Assembly.LoadFrom(path):null;};
   Directory.SetCurrentDirectory(install);
   dynamic automation=New("DWSIM.Automation","DWSIM.Automation.Automation3");
   dynamic flowsheet=automation.CreateFlowsheet();
   string[] names={"Methane","Ethane","Propane","Isobutane","N-butane","Isopentane","N-pentane","Nitrogen"};
   foreach(string name in names)flowsheet.AddCompound(name);
   dynamic package=New("DWSIM.Thermodynamics","DWSIM.Thermodynamics.PropertyPackages.PengRobinson1978PropertyPackage");flowsheet.AddPropertyPackage(package);
   dynamic kind=Enum.Parse(TypeOf("DWSIM.Interfaces","DWSIM.Interfaces.Enums.GraphicObjects.ObjectType"),"MaterialStream");
   dynamic stream=flowsheet.AddObject(kind,10,10,"Conditional viscosity reference");stream.PropertyPackage=package;package.CurrentMaterialStream=stream;
   var curves=new List<object>();
   foreach(string name in names) {
    Func<double,double> f=t=>(double)package.AUX_LIQVISCi(name,t,100000.0);
    double lower=name=="Nitrogen"?273.16:298.15;
    double upper=600;
    if(args.Length>2 && args[2]=="--ambient") {if(name=="Nitrogen")continue;lower=293.15;upper=298.15;}
    var knots=new SortedDictionary<double,double>();knots[lower]=Value(f,lower);knots[upper]=Value(f,upper);
    var checks=new List<object>();Refine(f,lower,upper,knots,checks,0);
    curves.Add(new{component=name,temperatures_kelvin=knots.Keys.ToArray(),viscosities_pascal_seconds=knots.Values.ToArray(),checks=checks});
    Console.WriteLine(name+": "+knots.Count+" knots");
   }
   var json=new JavaScriptSerializer{MaxJsonLength=67108864};
   File.WriteAllText(output,json.Serialize(new{revision="dwsim-conditional-solute-v1",engine_version=package.GetType().Assembly.GetName().Version.ToString(),
    source="DWSIM AUX_LIQVISCi reference factors for LogMoleAverage liquid mixtures, at 100000 Pa. Conditional dissolved-solute approximation, not pure-liquid phase validity. No source flowsheet loaded or saved.",
    source_url="https://dwsim.org/api_help/html/Methods_T_DWSIM_Thermodynamics_PropertyPackages_PropertyPackage.htm",curves=curves}));
   return 0;
  } catch(Exception e){Console.Error.WriteLine(e);return 1;}
 }
}
