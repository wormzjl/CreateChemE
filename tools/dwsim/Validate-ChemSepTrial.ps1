param([string]$Directory='build/dwsim-research/chemsep-direct-duty', [string]$DistillateTag='Distillate')
$ErrorActionPreference='Stop'
$data=Get-Content "$Directory/result.json" -Raw | ConvertFrom-Json
$sep=Get-Content "$Directory/chemsep-calculated.sep" -Raw
if(-not $data.solved -or -not $data.calculated -or $data.errors.Count -ne 0){throw 'ChemSep solve failed.'}
function Section([string]$name){return [regex]::Match($sep,'(?ms)^\['+[regex]::Escape($name)+'\]\s*\r?\n(.*?)(?=^\[|\z)').Groups[1].Value}
$nativeResults=Section 'Results'
$iterationMatch=[regex]::Match($nativeResults,'(?m)^\s*(\d+)\s+Iterations\s*$')
if($nativeResults -notmatch '(?m)^\s*1\s+Converged\s*$' -or -not $iterationMatch.Success){throw 'Native ChemSep convergence record is missing.'}
$iterations=[int]$iterationMatch.Groups[1].Value
$numeric=[cultureinfo]::InvariantCulture
$profiles=@(foreach($line in (Section 'Profiles') -split '\r?\n'){
  if($line -match '^\s*(\d+)\s+([-+0-9.].*)$'){
    $v=@($Matches[2] -split '\s+' | Where-Object {$_} | ForEach-Object {[double]::Parse($_,$numeric)})
    if($v.Count -ne 5){throw 'Unexpected profile row.'}
    [ordered]@{stage=[int]$Matches[1];temperature_K=$v[0];pressure_Pa=$v[1];raw_vapour_column_kmol_s=$v[2];raw_liquid_column_kmol_s=$v[3];heat_added_W=$v[4]}
  }
})
if($profiles.Count -ne 12){throw 'Expected 12 stages.'}
$phaseMatrices=@{}; $maxSumError=0.0
foreach($phase in @('Vapour','Liquid')){
  $matrix=[Collections.Generic.List[double[]]]::new()
  for($n=0;$n -lt 12;$n++){$matrix.Add([double[]]::new(30))}
  $first=0; $last=0; $values=0
  foreach($line in (Section "$phase phase compositions") -split '\r?\n'){
    if($line -match 'stages:\s*(\d+)\s+to\s+(\d+)'){$first=[int]$Matches[1];$last=[int]$Matches[2];continue}
    if($line -match '^\s*(\d+)\s+([-+0-9.].*)$'){
      $component=[int]$Matches[1]-1
      $parts=@($Matches[2] -split '\s+' | Where-Object {$_})
      if($component -lt 0 -or $component -ge 30 -or $first -lt 1 -or $last -gt 12 -or $parts.Count -ne $last-$first+1){throw 'Composition table layout mismatch.'}
      for($n=$first;$n -le $last;$n++){
        $value=[double]::Parse($parts[$n-$first],$numeric)
        if(-not [double]::IsFinite($value) -or $value -lt 0){throw 'Invalid composition.'}
        $matrix[$n-1][$component]=$value; $values++
      }
    }
  }
  if($values -ne 360){throw "Incomplete $phase composition export: $values values"}
  foreach($row in $matrix){$maxSumError=[Math]::Max($maxSumError,[Math]::Abs(($row|Measure-Object -Sum).Sum-1))}
  $phaseMatrices[$phase]=$matrix
}
$qc=[double]::Parse([regex]::Match((Section 'Condenser Heat Duty'),'([-+0-9.Ee]+)\s+Duty').Groups[1].Value,$numeric)
$qr=[double]::Parse([regex]::Match((Section 'Reboiler Heat Duty'),'([-+0-9.Ee]+)\s+Duty').Groups[1].Value,$numeric)
$feed=$data.streams|Where-Object tag -eq Oil
$products=@($data.streams|Where-Object tag -ne Oil)
$mass=$feed.mass_flow_kg_s-($products|Measure-Object mass_flow_kg_s -Sum).Sum
$moles=$feed.molar_flow_mol_s-($products|Measure-Object molar_flow_mol_s -Sum).Sum
$energy=1000*($feed.mass_flow_kg_s*$feed.enthalpy_kJ_kg-(($products|ForEach-Object {$_.mass_flow_kg_s*$_.enthalpy_kJ_kg})|Measure-Object -Sum).Sum)+$qc+$qr
$componentError=0.0
for($c=0;$c -lt 30;$c++){
  $closure=$feed.molar_flow_mol_s*$feed.composition[$c]
  foreach($product in $products){$closure-=$product.molar_flow_mol_s*$product.composition[$c]}
  $componentError=[Math]::Max($componentError,[Math]::Abs($closure))
}
$summary=[ordered]@{
  solved=$data.solved;dwsim_version=$data.dwsim_version;chemsep_version='8.50';solve_ms=$data.solve_ms
  iterations=$iterations;components=30;stages=12;direct_duty_specification=$true
  requested_duty_W=27282470.703293696;applied_duty_W=$qr
  input_rounding_difference_W=$qr-27282470.703293696
  condenser_heat_added_W=$qc;distillate_mol_s=($products|Where-Object tag -eq $DistillateTag).molar_flow_mol_s
  mass_closure_kg_s=$mass;molar_closure_mol_s=$moles;energy_closure_W=$energy
  maximum_component_closure_mol_s=$componentError;maximum_profile_composition_sum_error=$maxSumError
  qualified_for_v3_training=$false
  notes=@('ChemSep preprocessing rounds duty to 27,282,500 W (about 1.07 ppm above requested).','Raw profile temperatures are printed to 0.01 K. Port temperatures retain more precision.','At the total condenser, the raw vapour column carries the liquid distillate; do not map it as a physical vapor product.','Feed-stage and boundary flow conventions require qualification before V3 transfer.')
}
if([Math]::Abs($mass)/$feed.mass_flow_kg_s -gt 1e-6 -or [Math]::Abs($energy) -gt 10 -or $maxSumError -gt 1e-6){throw 'Balance or normalization check failed.'}
$summary|ConvertTo-Json -Depth 5|Set-Content "$Directory/validation.json"
@{profiles=$profiles;phase_compositions=$phaseMatrices;semantics=$summary.notes}|ConvertTo-Json -Depth 8|Set-Content "$Directory/stage-profiles.json"
$summary|ConvertTo-Json -Depth 5

