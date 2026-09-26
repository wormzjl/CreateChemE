"""Local research figures. No production catalog writes. See README.md for scope."""
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'build/viscosity-plot-deps'))
import json, re, hashlib, html, zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
import pdfplumber

OUT = Path(__file__).resolve().parent
COLORS = ['#0072B2', '#009E73', '#AD659E', '#D55E00', '#57449A']
GRID = np.linspace(0, 500, 2001)
plt.rcParams.update({'font.family':'DejaVu Sans', 'font.size':11, 'axes.titlesize':15,
 'axes.labelsize':12, 'axes.spines.top':False, 'axes.spines.right':False,
 'axes.edgecolor':'#BBC4CD', 'xtick.color':'#46515B', 'ytick.color':'#46515B',
 'figure.facecolor':'white', 'savefig.facecolor':'white', 'svg.fonttype':'none'})

def extract():
    spec=json.loads((ROOT/'research/crude-assays/source-data.json').read_text(encoding='utf-8'))
    data=[]
    for source in spec['sources']:
        path=ROOT/'research/crude-assay-sources'/f"{source['id']}.pdf"
        assert hashlib.sha256(path.read_bytes()).hexdigest()==source['pdf_sha256']
        with pdfplumber.open(path) as pdf:
            words=pdf.pages[0].extract_words(x_tolerance=1)
            start=next(w for w in words if w['text']=='Start' and w['x0']<120)
            header=sorted([w for w in words if abs(w['top']-start['top'])<2 and w['x0']>140],key=lambda w:w['x0'])
            labels=[w['text'] for w in header]; centers=[(w['x0']+w['x1'])/2 for w in header]
            residue=labels.index('C5')+8
            assert labels[residue]=='370' and labels[residue+1]=='370'
            record={k:source[k] for k in ['id','name','reference','url','pdf_sha256']}
            record.update(crude={'points':[]},residue={'points':[]},residue_cut_celsius=370)
            for word in words:
                if word['top']<=start['top'] or word['x0']>120 or word['text'] not in ['Viscosity','Pour','Density']:continue
                row=sorted([w for w in words if abs(w['top']-word['top'])<2],key=lambda w:w['x0'])
                text=' '.join(w['text'] for w in row)
                temperature=re.search(r'Viscosity\s+@\s+(\d+)',text)
                for kind,idx in [('crude',0),('residue',residue)]:
                    nums=[float(w['text']) for w in row if re.fullmatch(r'-?\d+(?:\.\d+)?',w['text']) and abs((w['x0']+w['x1'])/2-centers[idx])<10]
                    assert len(nums)<=1
                    if not nums:continue
                    if temperature:record[kind]['points'].append([int(temperature[1]),nums[0]])
                    elif text.startswith('Pour Point'):record[kind]['pour_point_celsius']=nums[0]
                    elif text.startswith('Density @'):record[kind]['density_15c_kg_m3']=nums[0]*1000
            # Read-only rendering for verification of viscosity and pour-point columns.
            tops=[w['top'] for w in words if w['text']=='Viscosity' and w['x0']<120 and w['top']>start['top']]
            bottom=next(w['bottom'] for w in words if w['text']=='Pour' and w['x0']<120 and w['top']>start['top'])
            pdf.pages[0].crop((35,min(tops)-5,pdf.pages[0].width-35,bottom+5)).to_image(resolution=140).save(OUT/f"source_check_{source['id']}.png")
        for kind in ['crude','residue']:
            record[kind]['points'].sort()
            assert len(record[kind]['points'])==3
        data.append(record)
    return data

def walther_z(nu):
    # Extended Walther transform used in the public DWSIM two-anchor routine.
    # nu in cSt; numerical inverse avoids substituting a clipped negative viscosity.
    return nu+0.7+np.exp(-1.47-1.84*nu-0.51*nu**2)

def evaluate(tc, coefficients):
    a,b=coefficients
    target=10**(10**(a+b*np.log10(np.asarray(tc)+273.15)))
    low=np.zeros_like(target);high=target.copy()
    for _ in range(75):
        mid=(low+high)/2
        smaller=walther_z(mid)<target
        low=np.where(smaller,mid,low);high=np.where(smaller,high,mid)
    return (low+high)/2

def fit(series):
    points=np.array(series['points']); t,nu=points.T
    a,b=np.linalg.lstsq(np.column_stack([np.ones(len(t)),np.log10(t+273.15)]),np.log10(np.log10(walther_z(nu))),rcond=None)[0]
    assert b<0
    pred=evaluate(t,[a,b]); errors=100*(pred/nu-1)
    curve=evaluate(GRID,[a,b]);assert np.isfinite(curve).all() and (curve>0).all() and (np.diff(curve)<0).all()
    series.update(coefficients=[float(a),float(b)],fit_min_celsius=float(t.min()),fit_max_celsius=float(t.max()),
      residual_percent=errors.tolist(),max_absolute_residual_percent=float(abs(errors).max()))
    return curve

def line(ax, series, color, small=False):
    y=evaluate(GRID,series['coefficients']);lo,hi=series['fit_min_celsius'],series['fit_max_celsius'];pour=series['pour_point_celsius']
    masks=[((GRID>=lo)&(GRID<=hi),'-',2.5,1),
           (((GRID<lo)|(GRID>hi))&(GRID>=pour),'--',1.7,.8),
           (GRID<pour,':',1.7,.55)]
    for mask,style,width,alpha in masks:ax.plot(GRID,np.where(mask,y,np.nan),style,color=color,lw=width,alpha=alpha)
    p=np.array(series['points']);ax.scatter(p[:,0],p[:,1],color=color,edgecolors='white',s=27 if small else 48,linewidth=.9,zorder=5)

def decorate(ax, kind, scale):
    ax.set_xlim(0,500);ax.set_xticks(np.arange(0,501,50));ax.set_xlabel('Temperature (°C)')
    ax.set_ylabel('Kinematic liquid viscosity (cSt = mm²/s)')
    ax.set_yscale(scale);ax.grid(True,which='major',color='#E3E8ED',linewidth=.7);ax.set_axisbelow(True)
    if scale=='linear':ax.set_ylim(bottom=0);ax.ticklabel_format(axis='y',style='sci',scilimits=(-3,4),useMathText=True)
    ax.axvspan(200,500,color='#F0F3F6',alpha=.65,zorder=-2)
    ax.text(.98,.95,'200–500°C: long extrapolation\nPhase change / reaction not modeled',transform=ax.transAxes,ha='right',va='top',fontsize=9,color='#687682')
    ax.set_title(('Whole crude' if kind=='crude' else 'Atmospheric residue · 370°C+')+' / '+('linear Y' if scale=='linear' else 'log Y'),loc='left',pad=16,fontweight='bold')

def footer(fig):
    fig.text(.08,.04,'● Assay values    ━ Fit within measured span    – – Extrapolation    ··· Below reported pour point (formal extension only)',fontsize=10,color='#384652')
    fig.text(.08,.015,'Fixed-composition liquid correlation, not an atmospheric-pressure heating/flash path. No phase or cracking calculation. Source: ExxonMobil assays.',fontsize=9,color='#66737D')

def save(fig, name):
    fig.savefig(OUT/f'{name}.png',dpi=160)
    fig.savefig(OUT/f'{name}.svg')
    plt.close(fig)

def comparison(data,kind):
    fig,axes=plt.subplots(1,2,figsize=(15.8,7.4));fig.subplots_adjust(left=.08,right=.975,bottom=.16,top=.77,wspace=.26)
    fig.suptitle('Crude assays: temperature–viscosity curves',x=.08,y=.975,ha='left',fontsize=22,fontweight='bold',color='#172B3A')
    fig.text(.08,.921,'Five imported crude presets • '+('bulk crude assays' if kind=='crude' else 'assay-reported 370°C+ residues')+' • 0–500°C',fontsize=12,color='#536572')
    fig.legend([Line2D([0],[0],color=c,lw=2.4) for c in COLORS],[r['name'] for r in data],loc='upper left',bbox_to_anchor=(.073,.892),ncol=3,frameon=False,fontsize=10)
    for ax,scale in zip(axes,['linear','log']):
        for r,c in zip(data,COLORS):line(ax,r[kind],c)
        decorate(ax,kind,scale)
    inset=axes[0].inset_axes([.46,.34,.50,.38])
    for r,c in zip(data,COLORS):line(inset,r[kind],c,True)
    inset.set_xlim(15 if kind=='crude' else 45,115)
    inset.set_ylim(0,1.08*max(max(np.array(r[kind]['points'])[:,1]) for r in data))
    inset.tick_params(labelsize=8);inset.set_title('Zoom near assay measurements',fontsize=9);inset.grid(color='#E3E8ED')
    if kind=='residue':inset.ticklabel_format(axis='y',style='sci',scilimits=(0,0),useMathText=True)
    footer(fig);save(fig,f'{kind}_linear_and_log')
    for scale in ['linear','log']:
        fig,ax=plt.subplots(figsize=(10.5,6.8));fig.subplots_adjust(left=.12,right=.96,bottom=.2,top=.75)
        for r,c in zip(data,COLORS):line(ax,r[kind],c)
        decorate(ax,kind,scale)
        fig.legend([Line2D([0],[0],color=c,lw=2.4) for c in COLORS],[r['name'] for r in data],loc='upper left',bbox_to_anchor=(.11,.97),ncol=3,frameon=False,fontsize=10)
        footer(fig);save(fig,f'{kind}_{scale}')

def individual(record,color):
    fig,axes=plt.subplots(2,2,figsize=(14,10));fig.subplots_adjust(left=.085,right=.97,bottom=.105,top=.86,hspace=.43,wspace=.28)
    fig.suptitle(record['name'],x=.085,y=.98,ha='left',fontsize=23,fontweight='bold')
    fig.text(.085,.936,f"Assay {record['reference']} • Measured-anchor fits and explicitly marked extrapolation • 0–500°C",fontsize=12,color='#536572')
    for row,kind in zip(axes,['crude','residue']):
        for ax,scale in zip(row,['linear','log']):
            line(ax,record[kind],color);decorate(ax,kind,scale)
            pp=record[kind]['pour_point_celsius']
            if pp>0:
                ax.axvline(pp,color=color,lw=1,alpha=.5)
                ax.text(.03,.05,f'Assay pour point: {pp:g}°C',transform=ax.transAxes,fontsize=9,color=color)
    footer(fig);save(fig,record['id']+'_four_panels')

def main():
    data=extract()
    for record in data:
        for kind in ['crude','residue']:fit(record[kind])
    (OUT/'assay_fits.json').write_text(json.dumps({'model':'extended_Walther_least_squares_in_transformed_coordinates',
      'equation':'log10(log10(Z(nu_cSt))) = a + b*log10(T_kelvin); Z=nu+0.7+exp(-1.47-1.84*nu-0.51*nu^2)',
      'note':'Not a claim of full ASTM D341 compliance; extrapolated fixed-composition liquid only.', 'records':data},indent=2)+'\n',encoding='utf-8')
    values={r['id']:{k:evaluate(GRID,r[k]['coefficients']).tolist() for k in ['crude','residue']} for r in data}
    (OUT/'curves.json').write_text(json.dumps({'temperature_celsius':GRID.tolist(),'viscosity_cst':values},separators=(',',':'))+'\n')
    for kind in ['crude','residue']:comparison(data,kind)
    for r,c in zip(data,COLORS):individual(r,c)
    summary=['| Crude | Whole-crude anchors (°C: cSt) | 370°C+ anchors (°C: cSt) | Residue pour point | Max fit error |','|---|---|---|---|---|']
    for r in data:
        anchors=lambda k:', '.join(f'{t:g}: {v:g}' for t,v in r[k]['points'])
        error=max(r[k]['max_absolute_residual_percent'] for k in ['crude','residue'])
        summary.append(f"| {r['name']} | {anchors('crude')} | {anchors('residue')} | {r['residue']['pour_point_celsius']:g}°C | {error:.2f}% |")
        print(r['id'],'fit max error %',round(error,3))
    (OUT/'fit_summary.md').write_text('\n'.join(summary)+'\n',encoding='utf-8')
    print('Generated assay plots in',OUT)

if __name__=='__main__':main()
