"""Choose an existing middle donor family; reads research only, writes this report only."""
import hashlib
import json
import math
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / 'viscosity-temperature-plots/calibrated-rheology/calibrated_results.json'
data = json.loads(SOURCE.read_text())
temperatures = [40.0, 50.0, 60.0, 80.0, 100.0, 120.0, 150.0]
indices = [data['temperature_celsius'].index(t) for t in temperatures]
curves = {r['id']: [r['component_logmu'][j][k] for j in range(7, 20) for k in indices] for r in data['records']}
median = [statistics.median(values) for values in zip(*curves.values())]
scores = {name: sum(abs(a-b) for a, b in zip(curve, median))/len(median) for name, curve in curves.items()}
selected = min(scores, key=lambda name: (scores[name], name))
report = {
    'selection': selected,
    'method': 'Minimum mean absolute natural-log dynamic-viscosity distance from the pointwise five-donor median; equal cut/temperature weights',
    'temperature_celsius': temperatures,
    'components': data['components'][7:],
    'scores': scores,
    'heavy_cut_100c_pascal_second': {r['id']: math.exp(r['component_logmu'][19][data['temperature_celsius'].index(100.0)]) for r in data['records']},
    'source': SOURCE.relative_to(HERE.parents[1]).as_posix(),
    'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    'limits': 'Comparative donor choice includes extrapolation. No bulk fits, gel branch, per-crude mixture interactions, or claim of universal accuracy are selected. Rebin this one family onto the new cuts during implementation.'
}
(HERE / 'viscosity-selection.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'selection': selected, 'scores': scores}, indent=2))
