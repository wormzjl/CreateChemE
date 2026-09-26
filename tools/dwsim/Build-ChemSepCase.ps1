param([string]$Template='build/dwsim-research/chemsep/sample.sep', [string]$Output='build/dwsim-research/chemsep/petroleum.sep')
$ErrorActionPreference='Stop'
$templateText = Get-Content -LiteralPath $Template -Raw
$native = Get-Content build/dwsim-research/petroleum-native-profile.json -Raw | ConvertFrom-Json
$feed = $native.output_streams | Where-Object tag -eq Oil
$sum = ($feed.composition | Measure-Object -Sum).Sum
$outputPath = [IO.Path]::GetFullPath($Output)
$directory = [IO.Path]::GetDirectoryName($outputPath)
$sections = [ordered]@{}
foreach($match in [regex]::Matches($templateText,'(?ms)^\[([^\]\r\n]+)\]\r?\n(.*?)(?=^\[|\z)')) { if($match.Groups[1].Value -eq 'End of Input'){break}; $sections[$match.Groups[1].Value]=$match.Groups[2].Value.TrimEnd() }
$sections['ChemSep'] = "Version=8.50`nName=$outputPath`nTitle=Native DWSIM pseudocomponents - direct ChemSep duty test"
$sections['Paths'] = @"
Device drivers path=C:\Program Files\ChemSepL8v50\bin\
Help and Info path=C:\Program Files\ChemSepL8v50\help\
Component data path=C:\Program Files\ChemSepL8v50\pcd\
Property data path=C:\Program Files\ChemSepL8v50\ipd\
Executables path=C:\Program Files\ChemSepL8v50\bin\
Temporary path=$directory\
Scripts path=C:\Program Files\ChemSepL8v50\bin\
"@
$co = [Collections.Generic.List[string]]::new()
$co.Add('unitname=ChemSepPetroleum'); $co.Add('CO_ID=ChemSepPetroleum'); $co.Add('30 components')
$components = [Collections.Generic.List[string]]::new(); $components.Add('30 Number of Components')
for($i=0;$i -lt 30;$i++) {
  $c=$native.compounds[$i]
  # DWSIM's CAPE-OPEN adapter returns the component ID as the CAS-field fallback
  # for these fractions. Match that actual interface value; this is not a real CAS number.
  $co.Add($c.Name); $co.Add($c.Name); $co.Add("$($c.Molar_Weight.ToString('G17',[cultureinfo]::InvariantCulture)) Mw")
  $components.Add("0 0 Library Offset, Index CAS=$($c.Name) CID=$($c.Name)")
  $components.Add("Name=$($c.Name)"); $components.Add('Lib=COSE')
}
$co.AddRange([string[]]@('1 thermo','exe=co-col2.exe','mode=Unknown','NoWarnings=TRUE','WilsonEstimate=TRUE','0 only K-values and ethalpies','0 use only perturbed derivative','0 use only perturbed mole derivative','0.001 relative T perturbation','0.001 relative P perturbation','0.001 X perturbation','0 write all values to log','0 use initial guess','0 inlet reflash','1 outlet flash','1 expose energy ports','0 clear log report','0 update icon','CO Thermo Version=Auto','1 number of run levels','normal','1 current run level'))
$sections['Cape-Open']=$co -join "`n"
$sections['Cape-Open-Variables']='0'
$sections.Remove('Cape-Open-Reports')
foreach($uiSection in @('FlowSheet','CoCo','Settings','End Settings')) { $sections.Remove($uiSection) }
$sections['Components']=$components -join "`n"
$sections['Operation']="2 Operation Column`n1 Operation kind Simple Distillation`n1 Condenser Total (Liquid product)`n1 Reboiler Partial (Liquid product)`n12 Stages`n1 Feed stages`n2 Sidestream stages`nF=8`nS=4,8`n0 Pumparound stages`nP=`n0 Interconnections`nI=`n0 Extra condensers`nC=`n0 Extra reboilers`nR="
$sections['Heaters/Coolers']="0 Number`n0 Column duty Qcolumn`n2 First stage`n11 Last stage`n0 Qcolumn lost to surroundings"
$sections['Efficiencies']="1 Default efficiency`n0 Number"
$sections['Pressures']="1 Column pressure Constant pressure`n101325 Condenser pressure`n101325 Top pressure`n* Pressure Drop`n101325 Bottom pressure"
$flows=[Collections.Generic.List[string]]::new()
$flows.AddRange([string[]]@('1 Number','1 Feed state T & p','8 Stage Feed1','350 Temperature','101325 Pressure','* Vapour fraction','30 componentflows'))
for($i=0;$i -lt 30;$i++){ $value=$feed.molar_flow_mol_s*$feed.composition[$i]/$sum/1000; $flows.Add("$($value.ToString('G17',[cultureinfo]::InvariantCulture)) Component $($i+1) flow") }
$sections['Feeds']=$flows -join "`n"
$sections['SideStreams']="2 Number`n4 Stage SideLight`n2 Phase Liquid`n2 Specification Molar flow`n0.04 Value`n8 Stage SideHeavy`n2 Phase Liquid`n2 Specification Molar flow`n0.07 Value"
$sections['Condenser']="4 Type Top product flow rate`n0.29001 Value Qcondenser`n* Type`n* Initialization guess"
$sections['Reboiler']="2 Type Heat duty of reboiler`n27282470.703293696 Value Qreboiler`n* Superheating`n* Type`n* Initialization guess"
$sections['Solve options']=($sections['Solve options'] -replace '3 Initialization Old results','1 Initialization Automatic' -replace '5 Method 2-pass ideal K \+ constant H first',"1 Method Newton's method" -replace '300 Maximum iterations','100 Maximum iterations' -replace '1 Feeds type Stage below','1 Feeds type Stage below')
$sections['Programs']="Temporary file=$directory\scratch.tmp`nUser program=`n1 Compiler Gfortran`n1 Show windows Hidden"
$result = [Text.StringBuilder]::new()
foreach($section in $sections.GetEnumerator()){ [void]$result.AppendLine("[$($section.Key)]"); [void]$result.AppendLine($section.Value); [void]$result.AppendLine() }
[void]$result.AppendLine('[End of Input]')
[IO.File]::WriteAllText($outputPath,$result.ToString(),[Text.Encoding]::ASCII)
Write-Output $outputPath
