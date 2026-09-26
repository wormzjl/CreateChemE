param([string]$Directory,[string]$Profile='build/dwsim-research/chemsep-v3/v3-accepted-profile.json',[switch]$UserProfiles)
$ErrorActionPreference='Stop'
$profileData=Get-Content $Profile -Raw|ConvertFrom-Json
if($profileData.branch -ne 'LIQUID_ONLY'){throw 'This seed adapter currently requires a liquid-only V3 condenser.'}
$path="$Directory/input.sep";$text=Get-Content $path -Raw
$text=$text.Replace('1 Initialization Automatic',$(if($UserProfiles){'2 Initialization User'}else{'3 Initialization Old Results'}))
$reflux=($profileData.input.specifications|Where-Object {$null -ne $_.ratio}).ratio
$rows=$profileData.nodes;$n=$rows[0].liquid_mol_s.Count
function F($v){([double]$v).ToString('E15',[cultureinfo]::InvariantCulture)}
$userLines=[Collections.Generic.List[string]]::new();$userLines.AddRange([string[]]@('[Column Initialization]',"$($rows.Count) Number",'Stage Temperature V-flow L-flow'))
$x=@();$y=@();$lines=[Collections.Generic.List[string]]::new()
$lines.AddRange([string[]]@('','[Results]','1 Converged','0 Iterations','','[Profiles]','','Stage Temperature Pressure Vapour Flow Liquid Flow Duties',''))
foreach($row in $rows){
  $liquid=@($row.liquid_mol_s);$vapor=@($row.vapor_mol_s)
  $l=($liquid|Measure-Object -Sum).Sum;$v=($vapor|Measure-Object -Sum).Sum
  if($l -le 0){throw 'Missing liquid seed phase.'}
  $x+=,@($liquid|ForEach-Object {$_/$l})
  if($row.node -eq 0){$v=$l/(1+$reflux);$l=$l*$reflux/(1+$reflux);$y+=,@($x[-1])}
  elseif($v -gt 0){$y+=,@($vapor|ForEach-Object {$_/$v})}
  else{$v=1e-12;$y+=,@($x[-1])}
  $p=$profileData.input.topPressurePascal+$row.node*$profileData.input.stagePressureDropPascal
  $lines.Add("$($row.node+1) $(F $row.temperature_K) $(F $p) $(F ($v/1000)) $(F ($l/1000)) 0")
  $userLines.Add("$($row.node+1) $(F $row.temperature_K) $(F ($v/1000)) $(F ($l/1000))")
}
foreach($phase in @('Vapour','Liquid')){
  $matrix=if($phase -eq 'Vapour'){$y}else{$x}
  $lines.AddRange([string[]]@('',"[$phase phase compositions]",''))
  for($first=0;$first -lt $rows.Count;$first+=5){
    $last=[Math]::Min($first+4,$rows.Count-1)
    $lines.Add("Component Mole fractions on stages: $($first+1) to $($last+1)");$lines.Add('')
    for($c=0;$c -lt $n;$c++){$values=@(for($j=$first;$j -le $last;$j++){F $matrix[$j][$c]});$lines.Add("$($c+1) $($values -join ' ')")}
    $lines.Add('')
  }
}
$lines.Add('[End of Results]')
if($UserProfiles){$text=$text.Replace('[End of Input]',($userLines -join "`r`n")+"`r`n`r`n[End of Input]")}
[IO.File]::WriteAllText([IO.Path]::GetFullPath($path),$text+($lines -join "`r`n"),[Text.Encoding]::ASCII)
@{source=$Profile;source_sha256=(Get-FileHash $Profile).Hash;meaning='Accepted V3 state used only as an initial guess; input Results section is not a ChemSep-converged label.';condenser='V3 total condensate converted to native reflux and liquid distillate columns'}|ConvertTo-Json|Set-Content "$Directory/seed-provenance.json"
