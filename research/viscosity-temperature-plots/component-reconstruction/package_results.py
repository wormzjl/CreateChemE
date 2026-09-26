"""Build a local gallery and archive of the component reconstruction only."""
from pathlib import Path
import json, html, zipfile

HERE=Path(__file__).resolve().parent
d=json.loads((HERE/'reconstruction.json').read_text())
panels=[('crude_linear_and_log','Whole-crude comparison'),
        ('residue_linear_and_log','Ideal 370°C+ residue comparison')]
panels += [(r['id']+'_four_panels',r['name']) for r in d['records']]
panels += [('heavy_components_audit','Heavy-component audit')]
nav=''.join(f'<a href="#{stem}">{html.escape(title)}</a>' for stem,title in panels)
body=''.join(f'''<section id="{stem}"><h2>{html.escape(title)}</h2>
<p class="links"><a href="{stem}.png" download>PNG</a><a href="{stem}.svg" download>Editable SVG</a></p>
<a href="{stem}.png"><img src="{stem}.png" alt="{html.escape(title)}: dynamic viscosity versus temperature, linear and logarithmic scales" loading="lazy"></a></section>'''
    for stem,title in panels)
singles=''.join(f'<a href="{kind}_{scale}.png">{kind.title()} / {scale} Y</a>' for kind in ['crude','residue'] for scale in ['linear','log'])
rows=[]
for r in d['records']:
    vals=[r['series'][k]['unit_corrected'][200] for k in ['crude','residue']]
    rows.append(f"<tr><td>{html.escape(r['name'])}</td><td>{r['residue_mass_yield']*100:.2f}%</td><td>{vals[0]:.5g}</td><td>{vals[1]:.5g}</td></tr>")
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Component-based crude viscosity · 0–500°C</title><style>
*{box-sizing:border-box}body{margin:0;background:#f1f4f7;color:#1c3040;font:16px/1.6 system-ui,sans-serif}
main{max-width:1320px;margin:auto;padding:40px 24px}h1{font-size:clamp(28px,4vw,44px);line-height:1.2;margin:0 0 14px}h2{font-size:24px;line-height:1.3;margin:0}
.eyebrow{font-size:13px;text-transform:uppercase;letter-spacing:1.5px;color:#596c7b}p{max-width:1000px}
a{color:#126198;text-underline-offset:3px}.note{background:#fff3e9;border-left:4px solid #ba6331;padding:16px 22px;margin:24px 0}
nav,.links{display:flex;gap:10px 20px;flex-wrap:wrap}nav{margin:26px 0 36px}nav a{background:white;padding:8px 14px;border:1px solid #dae2e8;border-radius:5px;font-size:14px;text-decoration:none}
section{margin:28px 0;padding:26px 20px;background:white;border:1px solid #dce3e9;border-radius:8px;scroll-margin-top:20px}
img{display:block;width:100%;height:auto}table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:10px;text-align:left;border-bottom:1px solid #dde4e9}th{color:#455d6f}footer{font-size:14px;color:#627586}.tablewrap{overflow:auto}
</style><main><div class="eyebrow">Local research · 17 September 2026</div>
<h1>Viscosity from the implemented components</h1>
<p>Six crude compositions and their ideal 370°C+ residues, reconstructed from the 20 constituent components over 0–500°C. Dynamic viscosity is reported in Pa·s. No measured whole-crude or residue viscosity was fitted.</p>
<div class="note"><strong>These are model diagnostics, not validated physical predictions.</strong><br>
The colored curves repair the verified PC12–13 units error. PC11–13 still need a suitable, calibrated heavy-fraction model. Solid lines mean input-table coverage; dashed lines and shading mean a temperature extension. No phase equilibrium, wax/gel behavior or cracking is calculated.</div>
<p class="links"><a href="component-viscosity-plots.zip" download>Download plots + data + methods</a><a href="README.md">Full methodology</a><a href="reconstruction.json" download>Numerical data</a><a href="java-verification.json">Java verification</a></p>
<nav>'''+nav+'''</nav><section><h2>At 50°C, after the units repair</h2>
<p>Model outputs only. The residue yields come from the implemented component cuts; they are not exact producer-assay yields.</p><div class="tablewrap"><table><thead><tr><th>Crude</th><th>370°C+ mass yield</th><th>Whole crude (Pa·s)</th><th>Residue (Pa·s)</th></tr></thead><tbody>'''+''.join(rows)+'''</tbody></table></div></section>
<p class="links">Separate figures: '''+singles+'''</p>'''+body+'''
<footer>All outputs are local and Git-ignored. Production properties and thermodynamics are unchanged. The raw reconstruction matched the Java evaluator at 72 states; this checks implementation consistency, not agreement with experiments.</footer></main></html>'''
(HERE/'index.html').write_text(page,encoding='utf-8')
archive=HERE/'component-viscosity-plots.zip'
files=sorted(p for p in HERE.iterdir() if p.suffix in {'.png','.svg','.json','.md','.py','.java','.html'})
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in files:z.write(p,p.name)
    assert z.testzip() is None
# Check local href/src destinations, including individual plot downloads.
import re
for ref in re.findall(r'(?:href|src)="([^"]+)"',page):
    if not ref.startswith('#'):assert (HERE/ref).is_file(),ref
print(f'Gallery links verified; archived {len(files)} files ({archive.stat().st_size/1e6:.2f} MB).')
