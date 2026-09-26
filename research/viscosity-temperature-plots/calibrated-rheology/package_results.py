"""Package the generated research result; no production writes or Git operations."""
from pathlib import Path
import json,html,zipfile,re
HERE=Path(__file__).resolve().parent
d=json.loads((HERE/'calibrated_results.json').read_text())
records=d['records']

def panel(stem,title):
    return f'<section id="{stem}"><h2>{html.escape(title)}</h2><p class="links"><a href="{stem}.png" download>PNG</a><a href="{stem}.svg" download>Editable SVG</a></p><a href="{stem}.png"><img src="{stem}.png" alt="{html.escape(title)}" loading="lazy"></a></section>'

rows=[];waxrows=[];detail=[]
for r in records:
    a,b=r['temperature_checks'];s=r['source']
    rows.append(f"<tr><td>{html.escape(s['name'])}</td><td>{a['observed_cst']:.4g} / {a['predicted_cst']:.4g}</td><td>{a['error_percent']:+.2f}%</td><td>{b['observed_cst']:.4g} / {b['predicted_cst']:.4g}</td><td>{b['error_percent']:+.2f}%</td></tr>")
    waxrows.append(f"<tr><td><a href=\"{s['url']}\">{html.escape(s['name'])}</a></td><td>{s['whole']['wax_mass_percent']:g}%</td><td>{s['residue']['wax_mass_percent']:g}%</td><td>{s['whole']['pour_point_celsius']:g}°C</td><td>{s['residue']['pour_point_celsius']:g}°C</td></tr>")
    detail.append(f'<details><summary>{html.escape(s["name"])}</summary>'+panel(r['id']+'_cold_detail','Cold detail and shear dependence')+panel(r['id']+'_full_range','Linear/log comparison over 0–500°C')+panel(r['id']+'_cut_transport','Source-based cut transport curves')+'</details>')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Calibrated crude viscosity and cold rheology</title><style>
*{box-sizing:border-box}body{margin:0;background:#eff4f6;color:#193341;font:16px/1.6 system-ui,sans-serif}main{max-width:1340px;margin:auto;padding:38px 24px}h1{font-size:clamp(29px,4vw,45px);line-height:1.2;margin:0 0 16px}h2{font-size:24px;line-height:1.25;margin:0}a{color:#086c77;text-underline-offset:3px}.eyebrow{font-size:13px;letter-spacing:1.4px;text-transform:uppercase;color:#607785}.note{background:#fff1e4;border-left:4px solid #b66a39;padding:17px 22px;margin:24px 0}.result{background:#e1f1ec;border-left:4px solid #25876f;padding:17px 22px;margin:24px 0}nav,.links{display:flex;flex-wrap:wrap;gap:9px 20px}nav{margin:24px 0}nav a{background:#fff;border:1px solid #d6e1e6;border-radius:5px;padding:8px 13px;text-decoration:none}section{background:#fff;border:1px solid #dbe4e9;border-radius:8px;padding:24px 20px;margin:27px 0;scroll-margin-top:20px}img{display:block;width:100%;height:auto}table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:10px;text-align:left;border-bottom:1px solid #dce5eb}.tablewrap{overflow:auto}details{margin:13px 0;background:#fff;border:1px solid #dbe4e9;border-radius:6px}summary{cursor:pointer;padding:15px 18px;font-weight:600}details section{border:0;margin:0}footer{font-size:14px;color:#617785}
</style><main><div class="eyebrow">Local research · 17 September 2026</div><h1>Crude-specific viscosity, with cold rheology</h1>
<p>Source-cut transport curves are mapped onto the unchanged 20-component compositions. Mixture interactions are calibrated against reported crude and residue measurements. A separate cold branch adds wax inventory, yield stress and a shear-history state.</p>
<div class="result"><strong>Ten temperature checks excluded from coefficient fitting: 0.874% mean absolute error; 3.87% maximum.</strong><br>The five imported crude assays are individually calibrated. These checks cover local viscosity interpolation, not unknown compositions or cold gel behavior.</div>
<div class="note"><strong>Measured-data constraints and cold-model assumptions remain separate.</strong><br>Reported wax contents replace the old uniform 15% assumption. Pour point sets an uncertain transition window; it does not determine yield stress. The cold bands sweep selected literature-based strength and history priors. The 100 s⁻¹ comparison rate is a modeling convention, not a reported assay test rate.</div>
<p class="links"><a href="calibrated-viscosity-results.zip" download>Download plots, data and methods</a><a href="README.md">Full methodology and limitations</a><a href="calibrated_results.json" download>Numerical model and outputs</a><a href="verification.json">Verification results</a></p>
<nav><a href="#whole_comparison">Whole crudes</a><a href="#residue_comparison">Residues</a><a href="#checks">Temperature checks</a><a href="#wax">Reported wax</a><a href="#individual">Individual cases</a><a href="#tia_juana_transfer">Tia Juana limitations</a></nav>
'''+panel('whole_comparison','Whole-crude comparison')+panel('residue_comparison','370°C+ residue comparison')+'''
<section id="checks"><h2>Temperatures excluded from the coefficient fit</h2><p>Values are in the original reported cSt units. The fitted temperatures were 20/50°C for each whole crude and 50/100°C for each residue. Filled markers in the detail figures are fit anchors; open diamonds are these additional checks. Source-point dynamic viscosities use an estimated density conversion.</p><div class="tablewrap"><table><tr><th>Crude</th><th>Whole at 40°C<br>Reported / predicted</th><th>Error</th><th>Residue at 60°C<br>Reported / predicted</th><th>Error</th></tr>'''+''.join(rows)+'''</table></div></section>
<section id="wax"><h2>Previously overlooked assay wax data</h2><p>The earlier blanket statement that wax-content data were unavailable was incorrect. These are analytical total-wax contents; they are not measured solid fractions versus temperature. DSC curves and crude-specific rheometry remain missing.</p><div class="tablewrap"><table><tr><th>Assay source</th><th>Whole wax wt%</th><th>Residue wax wt%</th><th>Whole pour point</th><th>Residue pour point</th></tr>'''+''.join(waxrows)+'''</table></div><p>Cold Lake’s 550°C+ cut reports nominally 0.0 wt% wax but 1,040,038 cSt at 100°C. The reconstruction now retains that high intrinsic viscosity instead of inventing a large wax inventory to explain it.</p></section>
<section id="individual"><h2>Each crude and its cuts</h2><p>Open a crude for before/after curves, shear rates of 0.1–100 s⁻¹, full-range linear/log plots, and the 13 source-based pseudo-cut curves. The aggregate fit acts through mixture interactions; its correction vanishes in the pure-component limit.</p>'''+''.join(detail)+'''</section>'''+panel('tia_juana_transfer','Tia Juana: donor transfer range only')+'''
<footer>Research outputs are local and Git-ignored. Production properties and thermodynamics are unchanged. Source articles and QA images are excluded from the ZIP. Neither high-temperature phase change/cracking nor a validated glass-transition model is included.</footer></main></html>'''
(HERE/'index.html').write_text(page,encoding='utf-8')
files=sorted(p for p in HERE.iterdir() if p.suffix in {'.png','.svg','.json','.md','.py','.html'} and not p.name.startswith('qa_'))
archive=HERE/'calibrated-viscosity-results.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in files:z.write(p,p.name)
    assert z.testzip() is None
for ref in re.findall(r'(?:href|src)="([^"]+)"',page):
    if not ref.startswith(('#','http')):assert (HERE/ref).is_file(),ref
print(f'{len(files)} files packaged; {archive.stat().st_size/1e6:.2f} MB; all gallery links exist.')
