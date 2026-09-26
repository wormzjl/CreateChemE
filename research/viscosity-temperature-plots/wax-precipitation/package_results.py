"""Create the local figure gallery; never include source articles in the ZIP."""
from pathlib import Path
import json,html,zipfile,re
HERE=Path(__file__).resolve().parent
d=json.loads((HERE/'wax_results.json').read_text())
panels=[('heavy_cuts_relative_change','Heavy cuts: relative change'),
        ('cuts_before_after_log','All cuts: logarithmic scale'),
        ('cuts_before_after_linear','All cuts: linear scale'),
        ('fusion_model_sensitivity','Fusion-model sensitivity'),
        ('crude_before_after','Whole-crude comparisons'),
        ('residue_before_after','370°C+ residue comparisons')]

def panel(stem,title):
    return f'''<section id="{stem}"><h2>{html.escape(title)}</h2><p class="links"><a href="{stem}.png" download>PNG</a><a href="{stem}.svg" download>Editable SVG</a></p><a href="{stem}.png"><img src="{stem}.png" alt="{html.escape(title)}" loading="lazy"></a></section>'''

rows=[];cards=[]
for r in d['records']:
    s=r['scenarios']['15'];i=80
    rows.append(f'''<tr><td><a href="{r['id']}_before_after.png">{html.escape(r['name'])}</a></td><td>{100*s['solid_volume_fraction'][i]:.3f}%</td><td>{r['before_pas'][i]:.5g}</td><td>{s['effective_pas'][i]:.5g}</td><td>{s['relative_to_before'][i]:.3f}×</td></tr>''')
    cards.append(f'''<details><summary>{html.escape(r['name'])} — linear/log, 0–150°C and 0–500°C</summary>{panel(r['id']+'_before_after',r['name'])}</details>''')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Wax precipitation · viscosity before and after</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f1f5f7;color:#193341;font:16px/1.6 system-ui,sans-serif}main{max-width:1350px;margin:auto;padding:38px 24px}h1{font-size:clamp(29px,4vw,44px);line-height:1.18;margin:0 0 15px}h2{font-size:24px;line-height:1.25;margin:0}a{color:#086b77;text-underline-offset:3px}p{max-width:1100px}.eyebrow{font-size:13px;text-transform:uppercase;letter-spacing:1.5px;color:#627582}.note{background:#fff0e1;border-left:4px solid #bd7247;padding:17px 22px;margin:22px 0}.links,nav{display:flex;gap:10px 20px;flex-wrap:wrap}nav{margin:25px 0}nav a{background:#fff;padding:8px 13px;border:1px solid #d6e0e6;border-radius:5px;text-decoration:none;font-size:14px}section{background:white;border:1px solid #dbe4ea;border-radius:7px;padding:24px 20px;margin:28px 0;scroll-margin-top:20px}img{width:100%;height:auto;display:block}.tablewrap{overflow:auto}table{border-collapse:collapse;width:100%;font-size:14px}td,th{border-bottom:1px solid #dce5ea;padding:9px;text-align:left}th{color:#52717e}details{margin:12px 0;border:1px solid #dbe4ea;border-radius:5px;background:#fff}summary{cursor:pointer;padding:14px 18px;font-weight:600}details section{margin:0;border:0}footer{font-size:14px;color:#657984}</style>
<main><div class="eyebrow">Local research · 17 September 2026</div><h1>Wax precipitation: before and after</h1>
<p>Calculated for all 13 pseudo cuts, six crudes and six ideal 370°C+ residues. Each case includes linear/log viscosity plots over 0–500°C, plus a 0–150°C detail. The liquid baseline is unchanged from the preceding component reconstruction.</p>
<div class="note"><strong>Explicit sensitivity scenarios, not measured wax properties.</strong><br>5%, 15% or 30% of each pseudo cut is assumed crystallizable; 15% is the reference case. The “after” curve is a dispersed-sphere suspension estimate. Actual wax gels require shear/history-dependent data and can be much more resistant to flow. PC11–13 liquid baselines and heavy fusion estimates remain unreliable.</div>
<p class="links"><a href="wax-viscosity-plots.zip" download>Download all figures, data and methods</a><a href="README.md">Methods and literature</a><a href="wax_results.json" download>Numerical results</a><a href="https://chatgpt.com/c/6aab5be3-bdc4-83ec-8a91-246901c46b82">ChatGPT literature review</a></p>
<nav>'''+''.join(f'<a href="#{stem}">{html.escape(title)}</a>' for stem,title in panels)+'''<a href="#individual">Individual cases</a><a href="#table">20°C values</a></nav>
<p>The relative plot isolates the modest hydrodynamic effect. The fusion comparison shows the much larger uncertainty in when crystals appear. In a mixture, the remaining-liquid composition changes too: a numerical decrease in viscosity is a model diagnostic, not evidence that actual wax gelation improves flow.</p>'''+''.join(panel(s,t) for s,t in panels)+'''
<section id="table"><h2>Reference scenario at 20°C</h2><p>Dynamic viscosity in Pa·s. Multiply by 1,000 for cP. The solid fractions and viscosity ratios are conditional on the illustrative 15% share and Won / bounded long-chain fusion assumptions.</p><div class="tablewrap"><table><thead><tr><th>Case</th><th>Solid vol%</th><th>Before (Pa·s)</th><th>After (Pa·s)</th><th>After / before</th></tr></thead><tbody>'''+''.join(rows)+'''</tbody></table></div></section>
<section id="individual"><h2>Individual before/after figures</h2><p>Open a case to see both temperature ranges and both Y-axis scales. The green shading spans the three selected inventory scenarios; it is not a statistical confidence interval.</p>'''+''.join(cards)+'''</section>
<footer>All results are local and Git-ignored. No production database or thermodynamic model changes. Source articles are excluded from the ZIP. Mathematical checks verify mass balance, saturation, solvent dilution, the zero-wax/high-temperature limits and the suspension dilute limit; there is no experimental calibration.</footer></main></html>'''
(HERE/'index.html').write_text(page,encoding='utf-8')
archive=HERE/'wax-viscosity-plots.zip'
files=sorted(p for p in HERE.iterdir() if p.suffix in {'.png','.svg','.json','.md','.py','.html'})
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in files:z.write(p,p.name)
    assert z.testzip() is None
for ref in re.findall(r'(?:href|src)="([^"]+)"',page):
    if not ref.startswith(('#','http')):assert (HERE/ref).is_file(),ref
print(f'{len(files)} files, {archive.stat().st_size/1e6:.2f} MB archive. All gallery links exist.')
