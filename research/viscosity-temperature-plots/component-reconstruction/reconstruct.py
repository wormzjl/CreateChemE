"""Research-only constituent reconstruction; never writes production properties.

No measured whole-crude or bulk-residue viscosity is read or fitted.
See README.md for the important distinction between a unit repair and a
physically qualified heavy-residue model.
"""
from pathlib import Path
import sys, json, hashlib, math, html, zipfile
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'build/viscosity-plot-deps'))
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

OUT = Path(__file__).resolve().parent
MAT = ROOT/'src/main/resources/data/createcheme/materials'
T = np.linspace(273.15,773.15,2001)
COLORS = ['#485B75','#0072B2','#009E73','#AD659E','#D55E00','#6C45A2']
SPECS = [('tjl20','Tia Juana Light + methane'),('wti_light_export_tjl20','WTI Light Export'),
         ('upper_zakum_tjl20','Upper Zakum'),('bonga_tjl20','Bonga'),
         ('dalia_tjl20','Dalia'),('cold_lake_blend_tjl20','Cold Lake Blend')]
INPUTS = {}
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.titlesize':14,
 'axes.labelsize':11,'axes.spines.top':False,'axes.spines.right':False,
 'axes.edgecolor':'#BCC6CE','xtick.color':'#46515B','ytick.color':'#46515B',
 'figure.facecolor':'white','savefig.facecolor':'white','svg.fonttype':'none'})

def read(path):
    INPUTS[str(path.relative_to(ROOT)).replace('\\','/')] = hashlib.sha256(path.read_bytes()).hexdigest()
    return json.loads(path.read_text(encoding='utf-8-sig'))

def table(nodes,values,ts):
    """Exact catalog log-linear interpolation, endpoint Andrade continuation.

    Continuation uses the two adjacent endpoint nodes to set d(ln mu)/d(1/T).
    No clamping, arbitrary viscosity ceiling, or table modification is applied.
    """
    x=np.array(nodes); y=np.log(values); ts=np.asarray(ts)
    result=np.interp(ts,x,y)
    for a,b,mask in [(0,1,ts<x[0]),(-1,-2,ts>x[-1])]:
        slope=(y[b]-y[a])/(1/x[b]-1/x[a])
        result=np.where(mask,y[a]+slope*(1/ts-1/x[a]),result)
    return np.exp(result)

def raw_component(prop,conditional,ts):
    c=prop['viscosity']['liquid']; assert c['type']=='log_table'
    base=table(c['temperatures_kelvin'],c['coefficients'],ts)
    valid=(ts>=c['temperature_min_kelvin'])&(ts<=c['temperature_max_kelvin'])
    other=conditional.get(prop['component'])
    if other:
        use=ts>c['temperature_max_kelvin']
        y=table(other['temperatures_kelvin'],other['viscosities_pascal_seconds'],ts)
        base=np.where(use,y,base)
        valid|=use & (ts>=other['temperatures_kelvin'][0]) & (ts<=other['temperatures_kelvin'][-1])
    return base,valid

def letsou_dynamic(prop,ts):
    # Same public DWSIM formula independently verified against the installed probe.
    # Already Pa s: DO NOT multiply the result by liquid density.
    pr=prop['models']['pr78']; tc=pr['critical_temperature_kelvin']
    tr=np.asarray(ts)/tc; pc_bar=pr['critical_pressure_pascal']/1e5
    mw_g_mol=prop['molecular_weight_kg_per_mol']*1000
    e0=(2.648-3.725*tr+1.309*tr**2)*.001
    e1=(7.425-13.39*tr+5.933*tr**2)*.001
    xi=.176*(tc/(mw_g_mol**3*pc_bar**4))**(1/6)
    return (e0+pr['acentric_factor']*e1)/xi/1000

def residue_split(components):
    factors=[]
    for cid in components:
        if not cid.startswith('tjl19_pc'):
            factors.append(0.);continue
        cut=read(MAT/'components'/f'{cid}.json')['cut']
        lower=cut.get('lower_kelvin',-math.inf); upper=cut.get('upper_kelvin',math.inf)
        if lower>=643.15: retained=1.
        elif upper<=643.15: retained=0.
        else:
            assert math.isfinite(lower) and math.isfinite(upper)
            retained=(upper-643.15)/(upper-lower)
        factors.append(retained)
    return np.array(factors)

def load():
    packages={}
    for path in (MAT/'packages').glob('*.json'):
        data=json.loads(path.read_text());packages[data['id']]=(path,data)
    conditional_doc=read(ROOT/'src/main/resources/data/createcheme/fluid/dissolved_viscosity.json')
    conditional={r['component']:r for r in conditional_doc['curves']}
    records=[]
    for filename,name in SPECS:
        assay=read(MAT/'assays'/f'{filename}.json')
        package_path,package=packages[assay['package']];read(package_path)
        assert assay['components']==package['components']
        props=[read(MAT/'properties'/f"{pid.split(':')[1]}.json") for pid in package['properties']]
        assert [p['component'] for p in props]==assay['components']
        mw=np.array([p['molecular_weight_kg_per_mol'] for p in props])
        density=np.array([p['standard_liquid_density_kg_per_m3'] for p in props])
        amounts=np.array(assay['amounts'])
        if assay['basis']=='mole':moles=amounts
        elif assay['basis']=='mass':moles=amounts/mw
        elif assay['basis']=='standard_liquid_volume':moles=amounts*density/mw
        else:raise ValueError(assay['basis'])
        moles=moles/moles.sum();split=residue_split(assay['components'])
        residue=moles*split;yield_mole=residue.sum();yield_mass=(residue*mw).sum()/(moles*mw).sum()
        residue=residue/yield_mole
        raw=[]; corrected=[];valid=[]
        for p in props:
            y,ok=raw_component(p,conditional,T); raw.append(y);valid.append(ok)
            corrected.append(letsou_dynamic(p,T) if p['component'] in ['tjl19_pc12','tjl19_pc13'] else y)
        raw=np.array(raw); corrected=np.array(corrected);valid=np.array(valid)
        assert np.isfinite(raw).all() and np.isfinite(corrected).all() and (raw>0).all() and (corrected>0).all()
        record=dict(id=assay['id'].split(':')[1],name=name,package_id=assay['package'],components=assay['components'],
                    original_basis=assay['basis'],mole_fractions=moles.tolist(),residue_mole_fractions=residue.tolist(),
                    residue_retention_factors=split.tolist(),residue_mole_yield=float(yield_mole),
                    residue_mass_yield=float(yield_mass),series={},properties=props)
        for kind,x in [('crude',moles),('residue',residue)]:
            yr=np.exp(x@np.log(raw));yc=np.exp(x@np.log(corrected));supported=np.all(valid[x>0],axis=0)
            assert np.isclose(x.sum(),1) and (np.diff(yc)<1e-12).all()
            assert ((yc>=corrected[x>0].min(axis=0))&(yc<=corrected[x>0].max(axis=0))).all()
            record['series'][kind]={'catalog_raw':yr.tolist(),'unit_corrected':yc.tolist(),'within_input_table_domain':supported.tolist()}
        records.append(record)
    # Every implemented imported preset deliberately shares the same pseudo properties.
    baseline=records[0]['properties']
    for r in records[1:]:assert r['properties']==baseline
    return records,baseline,raw,corrected,conditional

def save(fig,stem):
    fig.savefig(OUT/f'{stem}.png',dpi=160)
    fig.savefig(OUT/f'{stem}.svg')
    plt.close(fig)

def configure(ax,kind,scale):
    ax.set_yscale(scale);ax.set_xlim(0,500);ax.set_xticks(np.arange(0,501,50))
    ax.set_xlabel('Temperature (°C)');ax.set_ylabel('Dynamic liquid viscosity (Pa·s)')
    ax.grid(which='major',color='#E1E7ED',lw=.7);ax.set_axisbelow(True)
    if scale=='linear':ax.set_ylim(bottom=0);ax.ticklabel_format(axis='y',scilimits=(-3,4),style='sci',useMathText=True)
    ax.set_title(('Whole crude' if kind=='crude' else 'Ideal 370°C+ residue')+f' · {scale} Y',loc='left',pad=13,fontweight='bold')
    ax.axvspan(0,20,color='#DDE4E9',alpha=.6,zorder=-5)
    if kind=='crude':ax.axvspan(210.39,500,color='#DDE4E9',alpha=.6,zorder=-5)

def draw(ax,record,kind,color,raw=False):
    s=record['series'][kind];y=np.array(s['unit_corrected']);ok=np.array(s['within_input_table_domain'])
    for mask,style in [(ok,'-'),(~ok,'--')]:ax.plot(T-273.15,np.where(mask,y,np.nan),style,color=color,lw=2)
    if raw:ax.plot(T-273.15,s['catalog_raw'],':',color='#89939B',lw=1.4,zorder=-1)

def foot(fig,raw=False):
    fig.text(.085,.066,('Colored: PC12–13 units repaired    Gray dotted: original tables    ' if raw else '')+
             'Solid: within source tables    Dashed / shaded: temperature extension',fontsize=9,color='#44525D')
    fig.text(.085,.040,'DIAGNOSTIC ONLY · PC11–13 remain unqualified. Fixed composition; no flash, wax/gel model, pressure correction or cracking.',fontsize=9,color='#993F2B')
    fig.text(.085,.016,'Component reconstruction: ln μ = Σ xᵢ ln μᵢ. No measured whole-crude or residue viscosity used to fit these curves.',fontsize=9,color='#596875')

def comparison(records,kind):
    for scales,suffix in [(['linear','log'],'linear_and_log'),(['linear'],'linear'),(['log'],'log')]:
        fig,axes=plt.subplots(1,len(scales),figsize=(16 if len(scales)==2 else 11,8),squeeze=False)
        fig.subplots_adjust(left=.085,right=.975,bottom=.17,top=.72,wspace=.26)
        fig.suptitle('Viscosity reconstructed from the 20 components',x=.085,y=.965,ha='left',fontsize=21,fontweight='bold')
        fig.text(.085,.916,'Six implemented crude compositions · PC12–13 unit repair only · 0–500°C',fontsize=12,color='#516472')
        fig.legend([Line2D([0],[0],color=c,lw=2) for c in COLORS],[r['name'] for r in records],
                   loc='upper left',bbox_to_anchor=(.079,.877),ncol=3,frameon=False,fontsize=10)
        for ax,scale in zip(axes[0],scales):
            for r,c in zip(records,COLORS):draw(ax,r,kind,c)
            configure(ax,kind,scale)
        if len(scales)==2:
            inset=axes[0,0].inset_axes([.46,.37,.50,.49])
            for r,c in zip(records,COLORS):draw(inset,r,kind,c)
            mask=(T>=313.15)&(T<=423.15)
            inset.set_xlim(40,150);inset.set_ylim(0,1.08*max(np.array(r['series'][kind]['unit_corrected'])[mask].max() for r in records))
            inset.set_title('Linear zoom: 40–150°C',fontsize=9);inset.tick_params(labelsize=8);inset.grid(color='#E1E7ED')
        foot(fig);save(fig,f'{kind}_{suffix}')

def individual(record,color):
    fig,axes=plt.subplots(2,2,figsize=(14,11))
    fig.subplots_adjust(left=.085,right=.97,bottom=.15,top=.86,hspace=.4,wspace=.27)
    fig.suptitle(record['name'],x=.085,y=.969,ha='left',fontsize=22,fontweight='bold')
    fig.text(.085,.923,f"Component-based reconstruction · Ideal 370°C+ mass yield: {100*record['residue_mass_yield']:.2f}% · 0–500°C",fontsize=12,color='#516472')
    for row,kind in zip(axes,['crude','residue']):
        for ax,scale in zip(row,['linear','log']):draw(ax,record,kind,color,raw=True);configure(ax,kind,scale)
    foot(fig,raw=True);save(fig,record['id']+'_four_panels')

def component_plot(props,raw,corrected):
    fig,axes=plt.subplots(1,2,figsize=(15,7.5));fig.subplots_adjust(left=.08,right=.97,bottom=.18,top=.76,wspace=.26)
    fig.suptitle('The heavy-component problem remains after a unit repair',x=.08,y=.96,ha='left',fontsize=20,fontweight='bold')
    fig.text(.08,.902,'Solid: values after PC12–13 unit repair · Dotted: original PC12–13 values · Dashed: table extrapolation',fontsize=11)
    palette=['#0072B2','#009E73','#D55E00','#AD659E','#6C45A2']
    for ax in axes:
        for idx,col in zip(range(15,20),palette):
            mask=T>=293.15
            ax.plot(T[mask]-273.15,corrected[idx,mask],color=col,label=props[idx]['component'].replace('tjl19_','').upper())
            ax.plot(T[~mask]-273.15,corrected[idx,~mask],'--',color=col)
            if idx>=18:ax.plot(T-273.15,raw[idx],':',color=col)
        ax.set_yscale('log');ax.set_xlabel('Temperature (°C)');ax.set_ylabel('Dynamic viscosity (Pa·s)');ax.grid(color='#E1E7ED');ax.legend(frameon=False)
    axes[0].set_xlim(0,500);axes[0].set_title('PC09–13: full requested temperature range',loc='left',pad=15)
    axes[1].set_xlim(20,150);axes[1].set_ylim(1e-3,1e9);axes[1].set_title('Cold-to-warm region',loc='left',pad=15)
    fig.text(.08,.09,'PC11 rises sharply in the Twu estimate; PC12–13 use an unsuitable low-reduced-temperature fallback even after units are fixed.',fontsize=10,color='#993F2B')
    fig.text(.08,.05,'The literature fractions have no verified mapping to these component IDs. No measured heavy-fraction anchors were transplanted.',fontsize=10,color='#516472')
    save(fig,'heavy_components_audit')

def validate(props,raw,corrected,conditional):
    probe=read(ROOT/'research/cold-flow/dwsim-probe.json'); count=0;maximum=0
    for record in probe['records'][-2:]:
        prop=next(p for p in props if p['component']=='tjl19_pc'+record['name'][-2:])
        for s in record['samples']:
            result=float(letsou_dynamic(prop,s['temperature_kelvin']))
            error=abs(result/s['letsou_raw']-1); maximum=max(maximum,error)
            assert error<1e-12
            assert math.isclose(s['viscosity_pascal_seconds'],result*s['density_kg_per_cubic_metre'],rel_tol=1e-12)
            count+=1
    # Interpolation returns every catalog node unchanged, and endpoint continuations are continuous.
    for p in props:
        c=p['viscosity']['liquid'];nodes=np.array(c['temperatures_kelvin']);values=np.array(c['coefficients'])
        assert np.allclose(table(nodes,values,nodes),values,rtol=1e-13,atol=0)
        for t in [nodes[0],nodes[-1]]:
            v=table(nodes,values,np.array([t-1e-7,t,t+1e-7]));assert np.max(abs(v/v[1]-1))<1e-5
    return {'fallback_unit_checks':count,'maximum_probe_relative_error':maximum,
            'all_catalog_nodes_verified':True,'continuations_continuous':True,'finite_positive_curves':True,
            'normalized_compositions_and_convex_log_mixtures':True,
            'note':'Consistency checks, not experimental validation of heavy-fraction viscosities.'}

def main():
    records,props,raw,corrected,conditional=load()
    checks=validate(props,raw,corrected,conditional)
    for kind in ['crude','residue']:comparison(records,kind)
    for r,c in zip(records,COLORS):individual(r,c)
    component_plot(props,raw,corrected)
    out={'temperature_celsius':(T-273.15).tolist(),'units':'Pa s','mixing_rule':'ln(mu_mix)=sum(mole_fraction_i*ln(mu_i))',
         'correction':'PC12/13: direct Letsou-Stiel dynamic output, no extra density multiplier; still unqualified for residue',
         'records':[{k:v for k,v in r.items() if k!='properties'} for r in records],
         'components':records[0]['components'],
         'component_raw_viscosity':raw.tolist(),'component_unit_corrected_viscosity':corrected.tolist(),
         'input_sha256':INPUTS,'verification':checks}
    (OUT/'reconstruction.json').write_text(json.dumps(out,separators=(',',':'),allow_nan=False)+'\n')
    summary=['| Crude | 370°C+ mass yield | μ whole at 20 / 50 / 100°C (Pa·s) | μ residue at 20 / 50 / 100°C (Pa·s) |',
             '|---|---:|---|---|']
    for r in records:
        cells=[' / '.join(f"{np.interp(t,T-273.15,r['series'][kind]['unit_corrected']):.5g}" for t in [20,50,100]) for kind in ['crude','residue']]
        summary.append(f"| {r['name']} | {100*r['residue_mass_yield']:.2f}% | {cells[0]} | {cells[1]} |")
    (OUT/'values.md').write_text('\n'.join(summary)+'\n',encoding='utf-8')
    print('\n'.join(summary));print('Verification:',checks)

if __name__=='__main__':main()
