using System;
using System.IO;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Web.Script.Serialization;

// Offline adapter only. DWSIM is never loaded by Minecraft, and no source flowsheet is saved.
class ExportDwsimViscosity {
 static readonly JavaScriptSerializer Json=new JavaScriptSerializer {MaxJsonLength=67108864};
 static string install;
 static Type TypeOf(string dll,string name) {return Assembly.LoadFrom(Path.Combine(install,dll+".dll")).GetType(name,true);}
 static dynamic New(string dll,string name) {return Activator.CreateInstance(TypeOf(dll,name));}
 static double Num(object obj,string key) {
  var p=obj.GetType().GetProperty(key);return p==null?0:Convert.ToDouble(p.GetValue(obj,null));
 }
 static double Positive(Func<double,double> f,double t) {
  double v=f(t); if(double.IsNaN(v)||double.IsInfinity(v)||v<=0)throw new Exception("Nonpositive/nonfinite API viscosity at "+t);return v;
 }
 static void Refine(Func<double,double> f,double a,double b,SortedDictionary<double,double> knots,List<object> checks,int depth) {
  double va=knots[a],vb=knots[b];bool split=false;
  foreach(double fraction in new[]{.25,.5,.75}) {
   double t=a+(b-a)*fraction,v=Positive(f,t),predicted=Math.Exp(Math.Log(va)+(Math.Log(vb)-Math.Log(va))*fraction);
   if(Math.Abs(predicted/v-1)>0.0005)split=true;
  }
  if(split) {
   if(depth>=18||knots.Count>=2048)throw new Exception("Could not achieve 0.05% interpolation agreement");
   double m=(a+b)/2;knots[m]=Positive(f,m);Refine(f,a,m,knots,checks,depth+1);Refine(f,m,b,knots,checks,depth+1);
  } else foreach(double fraction in new[]{.25,.5,.75}) {
   double t=a+(b-a)*fraction;checks.Add(new{temperature_kelvin=t,viscosity_pascal_seconds=Positive(f,t)});
  }
 }
 static object Table(Func<double,double> f,double low,double high,bool estimated,string source,List<object> checks) {
  if(!(high>low))throw new Exception("Empty sampling interval");
  var knots=new SortedDictionary<double,double>();knots[low]=Positive(f,low);knots[high]=Positive(f,high);
  Refine(f,low,high,knots,checks,0);
  return new {type="log_table",temperature_min_kelvin=low,temperature_max_kelvin=high,
   pressure_min_pascal=100000.0,pressure_max_pascal=100000.0,reference_temperature_kelvin=300.0,
   coefficients=knots.Values.ToArray(),temperatures_kelvin=knots.Keys.ToArray(),revision="dwsim-viscosity-api-r1",source=source,estimated=estimated};
 }
 [STAThread] static int Main(string[] args) {
  try{return Run(args);}catch(Exception e){while(e!=null){Console.WriteLine(e.GetType().FullName+": "+e.Message);e=e.InnerException;}return 1;}
 }
 static int Run(string[] args) {
  install=Path.GetFullPath(args[0]);string sourceFile=Path.GetFullPath(args[1]),propertyDir=Path.GetFullPath(args[2]),output=Path.GetFullPath(args[3]);
  AppDomain.CurrentDomain.AssemblyResolve+=(sender,e)=>{var path=Path.Combine(install,new AssemblyName(e.Name).Name+".dll");return File.Exists(path)?Assembly.LoadFrom(path):null;};
  Directory.SetCurrentDirectory(install);
  dynamic automation=New("DWSIM.Automation","DWSIM.Automation.Automation3");
  dynamic original=automation.LoadFlowsheet2(sourceFile);original.AddCompound("Methane");
  var rows=new List<object>();
  foreach(string file in Directory.GetFiles(propertyDir,"*.json").OrderBy(p=>p)) {
   var property=Json.Deserialize<Dictionary<string,object>>(File.ReadAllText(file));string id=(string)property["id"],component=(string)property["component"];
   string native=component.StartsWith("tjl19_pc")?"TJL_PC"+component.Substring(8):component;
   if(!((IDictionary)original.SelectedCompounds).Contains(native)) {rows.Add(new {property_id=id,unavailable="No matching component in the supplied DWSIM characterization"});continue;}
   dynamic cp=((dynamic)((IDictionary)original.SelectedCompounds)[native]).Clone();
   bool pseudo=Convert.ToBoolean(cp.IsPF);
   // The native TJL characterization must match the selected material, not merely share a label.
   double mw=Convert.ToDouble(property["molecular_weight_kg_per_mol"])*1000,nbp=Convert.ToDouble(property["normal_boiling_point_kelvin"]);
   if(pseudo && (Math.Abs((double)cp.Molar_Weight/mw-1)>1e-10||Math.Abs((double)cp.Normal_Boiling_Point/nbp-1)>1e-10))
    throw new Exception("Characterization mismatch for "+id);
   dynamic fs=automation.CreateFlowsheet();((IDictionary)fs.SelectedCompounds).Add(native,cp);
   dynamic pp=New("DWSIM.Thermodynamics","DWSIM.Thermodynamics.PropertyPackages.PengRobinson1978PropertyPackage");fs.AddPropertyPackage(pp);
   dynamic kind=Enum.Parse(TypeOf("DWSIM.Interfaces","DWSIM.Interfaces.Enums.GraphicObjects.ObjectType"),"MaterialStream");
   dynamic stream=fs.AddObject(kind,10,10,"Viscosity export");stream.PropertyPackage=pp;pp.CurrentMaterialStream=stream;
   string version=pp.GetType().Assembly.GetName().Version.ToString();
   var metadata=new Dictionary<string,object>();
   foreach(var field in ((object)cp).GetType().GetProperties())
    if(field.Name.Contains("Viscos")||field.Name.StartsWith("PF_")||field.Name=="OriginalDB") {
     object value=field.GetValue(cp,null);
     if(value==null||value is double||value is string||value is bool)metadata[field.Name]=value;
    }
   var fits=new Dictionary<string,object>();var checks=new Dictionary<string,object>();var errors=new Dictionary<string,string>();
   foreach(string phase in new[]{"liquid","vapor"}) {
    try {
     double low,high;
     if(phase=="liquid") {low=pseudo?298.15:Math.Max(Num(cp,"TemperatureOfFusion")+1,0.5*(double)cp.Critical_Temperature);high=Math.Min(900,0.95*(double)cp.Critical_Temperature);}
     else {low=Math.Max(298.15,Num(cp,"Vapor_Viscosity_Tmin"));double max=Num(cp,"Vapor_Viscosity_Tmax");high=max>0?Math.Min(900,max):900;}
     Func<double,double> f=phase=="liquid"?(Func<double,double>)(t=>(double)pp.AUX_LIQVISCi(native,t,100000.0)):(t=>(double)pp.AUX_VAPVISCi(cp,t));
     string method=phase=="liquid"?"AUX_LIQVISCi(name,T,100000 Pa)":"AUX_VAPVISCi(component,T), dilute-gas auxiliary";
     string source="DWSIM "+version+" API "+method+"; "+(string)cp.OriginalDB+"; "+native+". Adaptive log interpolation checked to 0.05% against API quarter/midpoints. Sampled interval, not independent physical qualification; phase may be metastable. https://dwsim.org/api_help/html/T_DWSIM_Thermodynamics_PropertyPackages_PropertyPackage.htm";
     var phaseChecks=new List<object>();fits[phase]=Table(f,low,high,pseudo,source,phaseChecks);checks[phase]=phaseChecks;
    }catch(Exception e){errors[phase]=e.Message;}
   }
   rows.Add(new{property_id=id,api_component=native,engine_version=version,metadata=metadata,viscosity=fits,checks=checks,errors=errors});
   Console.WriteLine(id+": "+fits.Count+" phase curves");
  }
  File.WriteAllText(output,Json.Serialize(new{source_characterization=Path.GetFileName(sourceFile),records=rows}));return 0;
 }
}
