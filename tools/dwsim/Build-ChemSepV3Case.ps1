param([string]$Directory='build/dwsim-research/chemsep-v3',[switch]$NoWaterDraw,[switch]$SaturatedCondenser,[switch]$PartialCondenser)
$ErrorActionPreference='Stop'
$data=Get-Content "$Directory/input.json" -Raw|ConvertFrom-Json
$native=Get-Content "$Directory/native-properties.json" -Raw|ConvertFrom-Json
$inputCase=$data.input; $n=$native.components.Count; $stages=$inputCase.stageCount+2
$hydrocarbonCount=$inputCase.feedComponentMolarFlowsMolPerSecond.Count
if($n -ne $hydrocarbonCount -and $n -ne $hydrocarbonCount+1){throw 'Native component count does not match the feed axis.'}
if($n -eq $hydrocarbonCount -and ($inputCase.steamFeeds.Count -gt 0 -or -not $NoWaterDraw)){throw 'Water feeds/draws require native Water.'}
$path=[IO.Path]::GetFullPath("$Directory/input.sep")
$template=Get-Content build/dwsim-research/chemsep-direct-duty/petroleum.sep -Raw
$sections=[ordered]@{}
foreach($m in [regex]::Matches($template,'(?ms)^\[([^\]\r\n]+)\]\r?\n(.*?)(?=^\[|\z)')){if($m.Groups[1].Value -eq 'End of Input'){break};$sections[$m.Groups[1].Value]=$m.Groups[2].Value.TrimEnd()}
function F($number){([double]$number).ToString('G17',[cultureinfo]::InvariantCulture)}
$sections['ChemSep']="Version=8.50`nName=$path`nTitle=Current V3 TJL19 operating case with DWSIM PR78"
$co=[Collections.Generic.List[string]]::new();$co.AddRange([string[]]@('unitname=ChemSepTJL19','CO_ID=ChemSepTJL19',"$n components"))
$comps=[Collections.Generic.List[string]]::new();$comps.Add("$n Number of Components")
foreach($c in $native.components){$cas=if($c.cas){$c.cas}else{$c.name};$co.AddRange([string[]]@($c.name,$cas,"$(F $c.mw) Mw"));$comps.AddRange([string[]]@("0 0 Library Offset, Index CAS=$cas CID=$($c.name)","Name=$($c.name)",'Lib=COSE'))}
$co.AddRange([string[]]@('1 thermo','exe=co-col2.exe','mode=Unknown','NoWarnings=TRUE','WilsonEstimate=TRUE','0 only K-values and ethalpies','0 use only perturbed derivative','0 use only perturbed mole derivative','0.001 relative T perturbation','0.001 relative P perturbation','0.001 X perturbation','0 write all values to log','0 use initial guess','0 inlet reflash','1 outlet flash','1 expose energy ports','0 clear log report','0 update icon','CO Thermo Version=Auto','1 number of run levels','normal','1 current run level'))
$sections['Cape-Open']=$co -join "`n";$sections['Components']=$comps -join "`n"
$feedStages=@($inputCase.feedStageNumber+1)+@($inputCase.steamFeeds|ForEach-Object {$_.stageNumber+1})
$drawStages=@($inputCase.sideDraws|ForEach-Object {$_.trayNumber+1})
if(-not $NoWaterDraw){$drawStages=@(1)+$drawStages}
if($PartialCondenser){$drawStages=@(1)+$drawStages}
$condenser=if($SaturatedCondenser){'1 Condenser Total (Liquid product)'}else{'2 Condenser Total (Subcooled product)'}
if($PartialCondenser){$condenser='3 Condenser Partial (Vapour product)'}
$sections['Operation']="2 Operation Column`n1 Operation kind Simple Distillation`n$condenser`n1 Reboiler Partial (Liquid product)`n$stages Stages`n$($feedStages.Count) Feed stages`n$($drawStages.Count) Sidestream stages`nF=$($feedStages -join ',')`nS=$($drawStages -join ',')`n0 Pumparound stages`nP=`n0 Interconnections`nI=`n0 Extra condensers`nC=`n0 Extra reboilers`nR="
$heat=[Collections.Generic.List[string]]::new();$heat.Add("$($data.tray_heat_W.PSObject.Properties.Count | Measure-Object -Sum | Select-Object -ExpandProperty Sum) Number")
foreach($p in $data.tray_heat_W.PSObject.Properties){$heat.Add("$([int]$p.Name+1) Stage");$heat.Add("$(F $p.Value) Duty Q$($p.Name)")}
$heat.AddRange([string[]]@('0 Column duty Qcolumn','2 First stage',"$($stages-1) Last stage",'0 Qcolumn lost to surroundings'));$sections['Heaters/Coolers']=$heat -join "`n"
$pTop=$inputCase.topPressurePascal;$pBottom=$pTop+($stages-1)*$inputCase.stagePressureDropPascal
$sections['Pressures']="2 Column pressure Bottom & top pressures`n$(F $pTop) Condenser pressure`n$(F ($pTop+$inputCase.stagePressureDropPascal)) Top pressure`n* Pressure Drop`n$(F $pBottom) Bottom pressure"
$feeds=[Collections.Generic.List[string]]::new();$feeds.Add("$($feedStages.Count) Number")
for($f=0;$f -lt $feedStages.Count;$f++){
  $temp=if($f -eq 0){$inputCase.feedTemperatureKelvin}else{$inputCase.steamFeeds[$f-1].temperatureKelvin}
  $feeds.AddRange([string[]]@('1 Feed state T & p',"$($feedStages[$f]) Stage Feed$($f+1)","$(F $temp) Temperature","$(F $pTop) Pressure",'* Vapour fraction',"$n componentflows"))
  for($c=0;$c -lt $n;$c++){$flow=if($f -eq 0 -and $c -lt $hydrocarbonCount){$inputCase.feedComponentMolarFlowsMolPerSecond[$c]}elseif($f -gt 0 -and $c -eq $n-1){$inputCase.steamFeeds[$f-1].molarFlowMolPerSecond}else{0};$feeds.Add("$(F ($flow/1000)) Component $($c+1) flow")}
}
$sections['Feeds']=$feeds -join "`n"
$draws=[Collections.Generic.List[string]]::new();$draws.Add("$($drawStages.Count) Number")
if(-not $NoWaterDraw){$draws.AddRange([string[]]@('1 Stage WaterDraw','3 Phase Water','1 Specification Flow ratio','1 Value'))}
if($PartialCondenser){$ratio=($inputCase.specifications|Where-Object {$null -ne $_.ratio}).ratio;$draws.AddRange([string[]]@('1 Stage OrganicDistillate','2 Phase Liquid','1 Specification Flow ratio',"$(F (1/(1+$ratio))) Value"))}
foreach($draw in $inputCase.sideDraws){$draws.AddRange([string[]]@("$($draw.trayNumber+1) Stage Side$($draw.trayNumber)",'2 Phase Liquid','2 Specification Molar flow',"$(F ($draw.molarFlowMolPerSecond/1000)) Value"))}
$sections['SideStreams']=$draws -join "`n"
$temperature=($inputCase.specifications|Where-Object {$null -ne $_.kelvin}).kelvin
$reflux=($inputCase.specifications|Where-Object {$null -ne $_.ratio}).ratio
$duty=($inputCase.specifications|Where-Object {$null -ne $_.watts}).watts
$subcool=if($SaturatedCondenser){''}else{"`n$(F (-$temperature)) Subcooling"}
$sections['Condenser']="1 Type Reflux ratio`n$(F $reflux) Value Qcondenser$subcool`n* Type`n* Initialization guess"
if($PartialCondenser){$sections['Condenser']="3 Type Temperature of condenser`n$(F $temperature) Value Qcondenser`n* Type`n* Initialization guess"}
$sections['Reboiler']="2 Type Heat duty of reboiler`n$(F $duty) Value Qreboiler`n* Superheating`n* Type`n* Initialization guess"
$directoryPath=[IO.Path]::GetDirectoryName($path)
$sections['Solve options']=$sections['Solve options'] -replace '1 History Screen','3 History Both' -replace '(?m)^History file=.*$',"History file=$directoryPath\history.txt" -replace '100 Maximum iterations','30 Maximum iterations'
$sections['Programs']="Temporary file=$directoryPath\scratch.tmp`nUser program=`n1 Compiler Gfortran`n1 Show windows Hidden"
$sections['Paths']=$sections['Paths'] -replace '(?m)^Temporary path=.*$',"Temporary path=$directoryPath\"
$out=[Text.StringBuilder]::new();foreach($s in $sections.GetEnumerator()){[void]$out.AppendLine("[$($s.Key)]");[void]$out.AppendLine($s.Value);[void]$out.AppendLine()};[void]$out.AppendLine('[End of Input]')
[IO.File]::WriteAllText($path,$out.ToString(),[Text.Encoding]::ASCII)
$path
