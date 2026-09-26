"""Repair local research paths after consolidation; does not regenerate scientific data."""
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
manifest = json.loads((HERE / 'relocation-manifest.json').read_text(encoding='utf-8-sig'))
moves = {r['old']: r['new'] for r in manifest['files']}
replacements = {
    'examples/crude-assays': 'research/crude-assays',
    'examples/cold-flow': 'research/cold-flow',
    'build/crude-assay-sources': 'research/crude-assay-sources',
}
replacements.update({a: b for a, b in moves.items() if a.startswith('examples/') and a.count('/') == 1})
replacements.update({a: b for a, b in moves.items() if a.startswith('build/') and a.count('/') == 1})
paths = [ROOT / r['new'] for r in manifest['files']]
paths += list((ROOT / 'research/viscosity-temperature-plots').rglob('*'))
changed = []
for path in sorted(set(paths)):
    if not path.is_file() or path.suffix not in {'.py', '.ps1', '.cs', '.md', '.html', '.java'}:
        continue
    text = path.read_text(encoding='utf-8-sig')
    original = text
    # Resolve Markdown links against their original location before rewriting prose paths.
    old = next((a for a, b in moves.items() if ROOT / b == path), None)
    if old and path.suffix == '.md':
        def relocate_link(match):
            target = match[1]
            if '://' in target or target.startswith(('#', 'mailto:', '/')):
                return match[0]
            resolved = ((ROOT / old).parent / target).resolve()
            try:
                relative = resolved.relative_to(ROOT).as_posix()
            except ValueError:
                return match[0]
            dest = ROOT / moves.get(relative, relative)
            if not dest.exists():
                return match[0]
            return '](' + os.path.relpath(dest, path.parent).replace('\\', '/') + ')'
        text = re.sub(r'\]\(([^)]+)\)', relocate_link, text)
    for a, b in replacements.items():
        text = text.replace(a, b)
    if path.parent == ROOT / 'research/viscosity-tools':
        text = text.replace('Path(__file__).resolve().parents[1]', 'Path(__file__).resolve().parents[2]')
        text = text.replace('Path(__file__).resolve().parent / "cold-flow"', 'Path(__file__).resolve().parent.parent / "cold-flow"')
        text = text.replace("Join-Path $PSScriptRoot '..'", "Join-Path $PSScriptRoot '../..'")
    if path.name == 'convert.py':
        text = text.replace('ROOT / "CRUDE_ASSAY_CONVERSION.md"', 'ROOT / "research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md"')
        text = text.replace('](research/crude-assays/', '](../../crude-assays/')
    if path.name == 'build_quality.py':
        text = text.replace('ROOT/"CUT_ELEMENTAL_PROFILES.md"', 'ROOT/"research/crude-regrouping/notes/CUT_ELEMENTAL_PROFILES.md"')
        text = text.replace('](research/crude-assays/', '](../../crude-assays/')
    if path.name == 'calculate_wax.py':
        text = text.replace("(ROOT/path).read_bytes()", "(ROOT/path.replace('examples/cold-flow/', 'research/cold-flow/')).read_bytes()")
    if text != original:
        path.write_text(text, encoding='utf-8', newline='\n')
        changed.append(path.relative_to(ROOT).as_posix())

# Keep the active Java regression independent of ignored papers, tools and reports.
report = json.loads((ROOT / 'research/crude-assays/converted-compositions.json').read_text())
fixture = {'crudes': [{k: r[k] for k in ('package_id', 'assay_id', 'mole_fractions', 'mass_fractions')} for r in report['crudes']]}
(ROOT / 'src/test/resources/materials/crude-assay-conversion-fixture.json').write_text(json.dumps(fixture, indent=2) + '\n', encoding='utf-8')

material_doc = ROOT / 'MATERIALS.md'
text = material_doc.read_text(encoding='utf-8')
for a, b in replacements.items():
    text = text.replace(a, b)
text = text.replace('[CRUDE_ASSAY_CONVERSION.md](CRUDE_ASSAY_CONVERSION.md)', 'the local research report `research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md`')
material_doc.write_text(text, encoding='utf-8', newline='\n')

# Preserve old result provenance rather than rewriting stored result hashes.
manifest['path_repairs'] = changed
manifest['post_repair_sha256'] = {r['new']: hashlib.sha256((ROOT / r['new']).read_bytes()).hexdigest() for r in manifest['files']}
(HERE / 'relocation-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(f'Repaired {len(changed)} research text files; preserved numerical reports and source PDF bytes.')
