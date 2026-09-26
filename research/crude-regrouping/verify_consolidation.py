"""Check relocation integrity, Python syntax, local links and source/fixture retention."""
import ast
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
manifest = json.loads((HERE/'relocation-manifest.json').read_text())
checked = 0
for entry in manifest['files']:
    path = ROOT / entry['new']
    assert path.is_file(), path
    assert not (ROOT / entry['old']).exists(), entry['old']
    if '__pycache__' not in path.parts:
        assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest['post_repair_sha256'][entry['new']], path
    checked += 1

python_files = []
for folder in ['crude-assays', 'viscosity-tools', 'viscosity-temperature-plots', 'crude-regrouping']:
    for path in (ROOT/'research'/folder).rglob('*.py'):
        ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
        python_files.append(path)

links = []
for path in [HERE/'README.md', HERE/'PLAN.md']:
    for target in re.findall(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
        if '://' in target or target.startswith('#'):
            continue
        assert (path.parent / target).exists(), (path, target)
        links.append(target)

source = json.loads((ROOT/'research/crude-assays/source-data.json').read_text())
transport = json.loads((ROOT/'research/viscosity-temperature-plots/calibrated-rheology/source_transport.json').read_text())
for row in transport['records']:
    path = ROOT/'research/crude-assay-sources'/f"{row['id']}.pdf"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == row['pdf_sha256'], path
probe = json.loads((ROOT/'research/cold-flow/dwsim-probe.json').read_text())
assert hashlib.sha256((ROOT/'research/viscosity-tools/source-feed.dwxml').read_bytes()).hexdigest() == probe['source_sha256']
report = json.loads((ROOT/'research/crude-assays/converted-compositions.json').read_text())
fixture = json.loads((ROOT/'src/test/resources/materials/crude-assay-conversion-fixture.json').read_text())
assert fixture['crudes'] == [{k:r[k] for k in ('package_id','assay_id','mole_fractions','mass_fractions')} for r in report['crudes']]
selection = json.loads((HERE/'viscosity-selection.json').read_text())
assert selection['selection'] == 'dalia'
assert hashlib.sha256((ROOT/selection['source']).read_bytes()).hexdigest() == selection['source_sha256']
tracked_research = subprocess.check_output(['git', 'ls-files', '--', 'research'], cwd=ROOT, text=True).strip()
assert not tracked_research, tracked_research
result = {'relocated_files_verified': checked, 'python_files_syntax_checked': len(python_files),
          'index_links_checked': len(links), 'source_pdf_hashes_verified': len(transport['records']),
          'dwsim_source_hash_verified': True, 'independent_fixture_matches': True,
          'cache_policy': 'Relocated Python bytecode may regenerate; source/data hashes remain strict',
          'viscosity_selection': selection['selection'], 'tracked_research_files': 0,
          'scope': 'Research consolidation only; no regrouping, training or end-to-end qualification executed'}
(HERE/'consolidation-verification.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
