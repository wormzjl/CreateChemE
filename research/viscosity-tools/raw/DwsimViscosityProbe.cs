using System;
using System.IO;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Web.Script.Serialization;

class DwsimViscosityProbe {
 [STAThread] static int Main(string[] args) {
  try{return Run(args);}catch(Exception e){while(e!=null){Console.WriteLine(e.GetType().FullName+": "+e.Message);e=e.InnerException;}return 1;}
 }
 static int Run(string[] args) {
  string install=Path.GetFullPath(args[0]), input=Path.GetFullPath(args[1]), output=Path.GetFullPath(args[2]);
  AppDomain.CurrentDomain.AssemblyResolve+=(sender,e)=>{var p=Path.Combine(install,new AssemblyName(e.Name).Name+".dll");return File.Exists(p)?Assembly.LoadFrom(p):null;};
  Directory.SetCurrentDirectory(install);
  Console.WriteLine("Loading DWSIM API");
  dynamic automation=Activator.CreateInstance(Assembly.LoadFrom(Path.Combine(install,"DWSIM.Automation.dll")).GetType("DWSIM.Automation.Automation3",true));
  Console.WriteLine("Loading characterization");
  dynamic fs=automation.LoadFlowsheet2(input); fs.AddCompound("Methane");
  dynamic pp=Activator.CreateInstance(Assembly.LoadFrom(Path.Combine(install,"DWSIM.Thermodynamics.dll")).GetType("DWSIM.Thermodynamics.PropertyPackages.PengRobinson1978PropertyPackage",true));
  fs.AddPropertyPackage(pp);
  var objectType=Assembly.LoadFrom(Path.Combine(install,"DWSIM.Interfaces.dll")).GetType("DWSIM.Interfaces.Enums.GraphicObjects.ObjectType");
  dynamic kind=Enum.Parse(objectType,"MaterialStream");
  dynamic stream=fs.AddObject(kind,10,10,"Viscosity probe");
  stream.PropertyPackage=pp; pp.CurrentMaterialStream=stream;
  var rows=new List<object>();
  foreach(DictionaryEntry entry in (IDictionary)fs.SelectedCompounds) {
   dynamic cp=entry.Value; var metadata=new Dictionary<string,object>();
   foreach(var prop in entry.Value.GetType().GetProperties()) if(prop.Name.Contains("Viscos")||prop.Name.StartsWith("PF_")||prop.Name=="OriginalDB")
    if(prop.PropertyType==typeof(string)||prop.PropertyType==typeof(double)||prop.PropertyType==typeof(bool))metadata[prop.Name]=prop.GetValue(entry.Value,null);
   var values=new List<object>();
   foreach(double t in new[]{298.15,310.95,350,372.05,450,600}) {
    var point=new Dictionary<string,object>(); point["T"]=t;
    try{point["liquid_Pa_s"]=(double)pp.AUX_LIQVISCi((string)cp.Name,t,100000.0);}catch(Exception e){point["liquid_error"]=e.Message;}
    try{point["vapor_Pa_s"]=(double)pp.AUX_VAPVISCi(cp,t);}catch(Exception e){point["vapor_error"]=e.Message;}
    values.Add(point);
   }
   rows.Add(new{name=(string)cp.Name, metadata=metadata, values=values});
  }
  var json=new JavaScriptSerializer{MaxJsonLength=16777216};
  File.WriteAllText(output,json.Serialize(new{version=pp.GetType().Assembly.GetName().Version.ToString(),data=rows}));
  Console.WriteLine("Exported "+rows.Count+" components to "+output); return 0;
 }
}
