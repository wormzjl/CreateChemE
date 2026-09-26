param([string]$Output='build/dwsim-research/chemsep-v3/input.json')
$ErrorActionPreference='Stop'
$blockPath='src/main/java/com/wormzjl/createcheme/world/level/block/entity/ColumnCalculatorV3BlockEntity.java'
$block=Get-Content $blockPath -Raw
$method=[regex]::Match($block,'(?s)public static V3ColumnInput literatureCduInput\(\) \{.*?\n    \}').Value
$constants=@(foreach($name in @('LITERATURE_PACKAGE','LITERATURE_FEED_MOL_PER_SECOND')){
  $match=[regex]::Match($block,'(?:public|private) static final (?:String|double) '+$name+' = [^;]+;')
  if(-not $match.Success){throw "Missing source constant: $name"}; $match.Value
}) -join "`n"
if(-not $method){throw 'Could not extract current shipped fixture.'}
$null=New-Item -ItemType Directory -Force -Path (Split-Path $Output),build/dwsim-research/v3-export-classes
$source=@'
package com.wormzjl.createcheme.science.column.v3.thermo;
import com.wormzjl.createcheme.science.column.v3.*;
import java.util.*;
import java.nio.file.*;
import com.google.gson.GsonBuilder;
public final class CurrentChemSepInput {
__CONSTANTS__
__METHOD__
public static void main(String[] args) throws Exception {
  var input=literatureCduInput(); var pkg=V3PropertyPackageRegistry.require(input.packageId());
  var data=new LinkedHashMap<String,Object>(); data.put("input",input);
  data.put("dataset_revision",pkg.datasetRevision()); data.put("binary_interactions",pkg.binaryInteractions());
  var components=new ArrayList<V3PropertyComponent>();
  for(int i=0;i<input.componentBasis().componentIds().size();i++) components.add(pkg.component(i));
  data.put("components",components);
  var heats=new TreeMap<Integer,Double>();
  for(var pa:input.pumparounds()) for(int tray=1;tray<=input.stageCount();tray++)
    if(pa.trayDutyWatts(tray)!=0) heats.merge(tray,pa.trayDutyWatts(tray),Double::sum);
  data.put("tray_heat_W",heats);
  Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(data));
}
}
'@
$source=$source.Replace('__CONSTANTS__',$constants).Replace('__METHOD__',$method)
$java='build/dwsim-research/CurrentChemSepInput.java'
[IO.File]::WriteAllText([IO.Path]::GetFullPath($java),$source)
$files=@(rg --files src/main/java/com/wormzjl/createcheme/science -g '*.java')
$gson='build/solver-audit/lib/gson-2.10.1.jar'
& javac --release 21 -cp $gson -d build/dwsim-research/v3-export-classes @files $java
if($LASTEXITCODE -ne 0){throw 'Could not compile source fixture export.'}
& java -cp "build/dwsim-research/v3-export-classes;$gson;src/main/resources" com.wormzjl.createcheme.science.column.v3.thermo.CurrentChemSepInput $Output
if($LASTEXITCODE -ne 0){throw 'Could not export source fixture.'}
$data=Get-Content $Output -Raw|ConvertFrom-Json
$data|Add-Member source_block_sha256 (Get-FileHash $blockPath -Algorithm SHA256).Hash
$data|Add-Member source_method $method
$data|ConvertTo-Json -Depth 16|Set-Content $Output
Write-Output $Output
