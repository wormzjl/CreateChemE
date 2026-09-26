"""Local wax-precipitation sensitivity study, not a production property model.

Virtual wax/nonwax splits preserve each parent cut's MW and liquid baseline.
Ideal liquid + independent pure solids; dissolved inventory and total liquid
amount are solved together. Finite dispersed-suspension estimates are not gels.
"""
from pathlib import Path
import sys,json,hashlib,math
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'build/viscosity-plot-deps'))
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

R=8.31446261815324
SHARES=[.05,.15,.30]
BASE=ROOT/'research/viscosity-temperature-plots/component-reconstruction/reconstruction.json'
data=json.loads(BASE.read_text())
TC=np.array(data['temperature_celsius']);TK=TC+273.15
MU=np.array(data['component_unit_corrected_viscosity'])
IDS=data['components']
MAT=ROOT/'src/main/resources/data/createcheme/materials'
package=json.loads((MAT/'packages/tjl20.json').read_text())
PROPS=[json.loads((MAT/'properties'/f"{p.split(':')[1]}.json").read_text()) for p in package['properties']]
MW=np.array([p['molecular_weight_kg_per_mol'] for p in PROPS])
RHO=np.array([p['standard_liquid_density_kg_per_m3'] for p in PROPS])
ELIGIBLE=np.array([c.startswith('tjl19_pc') for c in IDS])
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.titlesize':13,
 'axes.spines.top':False,'axes.spines.right':False,'axes.edgecolor':'#BBC6CD',
 'figure.facecolor':'white','savefig.facecolor':'white','svg.fonttype':'none'})
COLORS=['#485B75','#0072B2','#009E73','#AD659E','#D55E00','#6C45A2']

def saturation(mwg,temperature,model='paraffin',cp=False):
    """K is saturation mole fraction under the ideal-liquid/pure-solid hypothesis.
    All formulas here use K, g/mol, J/mol; original cal/mol constants use 4.184.
    The LG branch is a different mixed-fraction hypothesis, not a paraffin fit.
    """
    mwg=np.asarray(mwg)
    if model in ['won','paraffin']:
        tm=374.5+.02617*mwg-20172/mwg
        if model=='paraffin':
            carbon=(mwg-2.016)/14.027
            # NIST Broadhurst 1962: long n-alkanes above C44, finite asymptote.
            tm=np.where(carbon>44,414.3*(carbon-1.5)/(carbon+5),tm)
        h=.1426*mwg*tm*4.184
    elif model=='lg':tm=333.46-419.01*np.exp(-.008546*mwg);h=.05276*mwg*tm*4.184
    else:raise ValueError(model)
    # Light explicit components are noncrystallizing in this experiment.
    tm=np.maximum(tm,1.)
    t=np.asarray(temperature)[None,:];fm=tm[:,None]
    ln_k=-h[:,None]/R*(1/t-1/fm)
    if cp:
        a=.3033*mwg[:,None]*4.184;b=-4.635e-4*mwg[:,None]*4.184
        ln_k+=a/R*(np.log(t/fm)+fm/t-1)+b/R*(.5*t-fm+.5*fm**2/t)
    # Pure solids are unstable at/above their model melting point.
    k=np.exp(np.minimum(0,ln_k));k=np.where(t>=fm,1.,k)
    return k,tm,h

def phase_split(z,f,k):
    """Solve L=N_nonwax+sum min(N_wax_i,K_i*L), independent of amount scale."""
    z=np.asarray(z);f=np.asarray(f)
    nw=z*f;nn=z*(1-f);n0=nn.sum()
    if n0<=0:raise ValueError('This screening model requires a nonwax carrier.')
    lo=np.full(k.shape[1],n0);hi=np.full(k.shape[1],z.sum())
    for _ in range(65):
        mid=(lo+hi)/2
        residual=n0+np.minimum(nw[:,None],k*mid).sum(axis=0)-mid
        lo=np.where(residual>0,mid,lo);hi=np.where(residual>0,hi,mid)
    liquid_total=(lo+hi)/2
    dissolved=np.minimum(nw[:,None],k*liquid_total)
    solid=nw[:,None]-dissolved
    liquid_parent=nn[:,None]+dissolved
    assert np.all(solid>=-1e-14) and np.allclose(liquid_parent+solid,z[:,None],rtol=1e-12,atol=1e-14)
    assert np.allclose(liquid_parent.sum(axis=0),liquid_total,rtol=1e-12,atol=1e-14)
    assert np.max(dissolved/liquid_total-k)<1e-12
    assert np.max(np.abs(solid*(dissolved/liquid_total-k)))<1e-12
    return liquid_parent,solid

def kd(phi,intrinsic=2.5,packing=.64):
    phi=np.asarray(phi)
    if (phi<0).any():raise ValueError('Negative solid volume fraction')
    result=np.full_like(phi,np.nan,dtype=float);valid=phi<packing
    result[valid]=(1-phi[valid]/packing)**(-intrinsic*packing)
    return result

def case(z,share,model='paraffin',cp=False):
    z=np.array(z);z=z/z.sum()
    f=share*ELIGIBLE;k,tm,h=saturation(MW*1000,TK,model,cp)
    liq,solid=phase_split(z,f,k)
    mass_s=solid*MW[:,None];mass_l=liq*MW[:,None]
    # Constant density is an explicit low-temperature sensitivity approximation.
    vs=(mass_s/900.).sum(axis=0);vl=(mass_l/RHO[:,None]).sum(axis=0)
    phi=vs/(vs+vl)
    x=liq/liq.sum(axis=0)
    mu_l=np.exp((x*np.log(MU)).sum(axis=0))
    multiplier=kd(phi);effective=mu_l*multiplier
    before=np.exp(z@np.log(MU))
    no_solid=solid.sum(axis=0)<1e-13
    assert np.allclose(effective[no_solid],before[no_solid],rtol=1e-12,atol=0)
    assert np.max(solid[:,TK>=tm.max()])<1e-13
    assert np.all(multiplier>=1) and np.isfinite(effective).all()
    # Density-only sensitivity, without changing the equilibrium mass balance.
    density_phi=[]
    for rs,rl in [(850.,RHO*1.05),(950.,RHO*.95)]:
        vss=mass_s.sum(axis=0)/rs;vll=(mass_l/rl[:,None]).sum(axis=0)
        density_phi.append(vss/(vss+vll))
    return {'effective_pas':effective,'liquid_matrix_pas':mu_l,'relative_to_before':effective/before,
      'hydrodynamic_multiplier':multiplier,'solid_volume_fraction':phi,
      'solid_mass_fraction':mass_s.sum(axis=0)/(z@MW),
      'solid_parent_moles_per_feed_mole':solid,'liquid_parent_moles_per_feed_mole':liq,
      'density_sensitivity_effective_min':mu_l*np.minimum(kd(density_phi[0]),kd(density_phi[1])),
      'density_sensitivity_effective_max':mu_l*np.maximum(kd(density_phi[0]),kd(density_phi[1]))}

def serial(obj):
    if isinstance(obj,np.ndarray):return obj.tolist()
    if isinstance(obj,np.generic):return obj.item()
    if isinstance(obj,dict):return {k:serial(v) for k,v in obj.items()}
    if isinstance(obj,list):return [serial(v) for v in obj]
    return obj

def save(fig,stem):
    fig.savefig(OUT/f'{stem}.png',dpi=150);fig.savefig(OUT/f'{stem}.svg');plt.close(fig)

def footer(fig):
    fig.text(.07,.040,'Scenario estimates only: wax-forming subfraction = 5 / 15 / 30% of each pseudo cut; 15% is the reference case.',fontsize=9,color='#465B6B')
    fig.text(.07,.019,'Dispersed-crystal viscosity, not gel/yield behavior. Heavy liquid baselines and high-MW fusion extrapolations remain unreliable.',fontsize=9,color='#A3472B')

def lines(ax,record,xmax=150):
    central=record['scenarios']['15'];low=record['scenarios']['5'];high=record['scenarios']['30']
    family=np.array([low['effective_pas'],central['effective_pas'],high['effective_pas']])
    ax.fill_between(TC,family.min(axis=0),family.max(axis=0),color='#17A798',alpha=.16)
    ax.plot(TC,record['before_pas'],'--',color='#555C67',lw=1.8,label='Before: liquid baseline')
    ax.plot(TC,central['effective_pas'],color='#007F78',lw=2,label='After: 15% scenario')
    ax.set_xlim(0,xmax);ax.grid(color='#E4E9ED');ax.set_axisbelow(True)
    ax.set_xlabel('Temperature (°C)');ax.set_ylabel('Dynamic viscosity (Pa·s)')

def overview(cuts,scale):
    fig,axes=plt.subplots(4,4,figsize=(17,14.5));fig.subplots_adjust(left=.07,right=.97,bottom=.10,top=.86,wspace=.34,hspace=.60)
    fig.suptitle('Isolated cuts: before and after paraffin precipitation',x=.07,y=.972,ha='left',fontsize=22,fontweight='bold')
    fig.text(.07,.931,f'{scale.title()} viscosity scale · 0–150°C detail · Same 13 cuts shared by the six crude presets',fontsize=12)
    fig.legend([Line2D([0],[0],color='#555C67',ls='--'),Line2D([0],[0],color='#007F78')],['Before: liquid-only','After: dispersed wax, 15% scenario'],loc='upper left',bbox_to_anchor=(.067,.913),ncol=2,frameon=False)
    for ax,c in zip(axes.flat,cuts):
        lines(ax,c);ax.set_yscale(scale)
        ax.set_title(c['name']+(' *' if c['mw_g_mol']>422.82 else ''),loc='left',fontsize=12,fontweight='bold')
        ax.tick_params(labelsize=8);ax.xaxis.label.set_size(9);ax.yaxis.label.set_size(9)
        if scale=='linear':ax.set_ylim(0,max(max(c['before_pas'][:601]),max(c['scenarios']['30']['effective_pas'][:601]))*1.05);ax.ticklabel_format(axis='y',scilimits=(-2,3),style='sci',useMathText=True)
    for ax in list(axes.flat)[13:]:ax.axis('off')
    axes.flat[13].text(0,1,'* MW beyond C30\nFusion extrapolation is especially uncertain.\n\nShade: 5–30% inventory scenarios,\nnot a confidence interval.\n\nPC11 liquid baseline is already extreme;\nprecipitation does not repair it.',va='top',fontsize=11,color='#6D5144')
    footer(fig);save(fig,'cuts_before_after_'+scale)

def individual(record):
    fig,axes=plt.subplots(2,2,figsize=(14,10.5));fig.subplots_adjust(left=.08,right=.97,bottom=.12,top=.85,wspace=.25,hspace=.39)
    fig.suptitle(record['name'],x=.08,y=.972,ha='left',fontsize=22,fontweight='bold')
    fig.text(.08,.928,'Liquid baseline vs dispersed wax · Gray dashed: before · Green: 15% after · Shading: 5–30% share sensitivity',fontsize=11)
    for row,xmax in zip(axes,[150,500]):
        for ax,scale in zip(row,['linear','log']):
            lines(ax,record,xmax);ax.set_yscale(scale);ax.set_title(f'0–{xmax}°C · {scale} Y',loc='left',pad=12)
            if scale=='linear':ax.set_ylim(bottom=0);ax.ticklabel_format(axis='y',scilimits=(-2,4),style='sci',useMathText=True)
    footer(fig);save(fig,record['id']+'_before_after')

def relative_plot(cuts):
    fig,axes=plt.subplots(1,2,figsize=(15,7.3));fig.subplots_adjust(left=.08,right=.97,bottom=.16,top=.76,wspace=.24)
    fig.suptitle('Isolated heavy cuts: the added effect of dispersed crystals',x=.08,y=.97,ha='left',fontsize=20,fontweight='bold')
    fig.text(.08,.913,'15% crystallizable share per cut · Won / bounded long-chain melting estimate · Ideal solid–liquid equilibrium',fontsize=11)
    for c,col in zip(cuts[-6:],COLORS):
        s=c['scenarios']['15'];axes[0].plot(TC,s['relative_to_before'],color=col,lw=2,label=c['name'])
        axes[1].plot(TC,100*s['solid_volume_fraction'],color=col,lw=2)
    for ax in axes:ax.set_xlim(0,150);ax.set_xlabel('Temperature (°C)');ax.grid(color='#E4E9ED')
    axes[0].set_ylabel('After / before viscosity');axes[0].set_title('Hydrodynamic change only',loc='left',pad=14);axes[0].legend(frameon=False,ncol=2)
    axes[1].set_ylabel('Solid wax volume (%)');axes[1].set_title('Predicted solids under the stated assumptions',loc='left',pad=14)
    axes[1].text(.98,.15,'No universal solid-fraction gel threshold.\nGel behavior is not calculated.',transform=axes[1].transAxes,ha='right',fontsize=9,color='#8C552B')
    footer(fig);save(fig,'heavy_cuts_relative_change')

def blend_plot(records,kind):
    fig,axes=plt.subplots(2,3,figsize=(16,10));fig.subplots_adjust(left=.07,right=.97,bottom=.13,top=.83,wspace=.29,hspace=.42)
    fig.suptitle(('Whole crudes' if kind=='crude' else 'Ideal 370°C+ residues')+': recomputed wax equilibrium',x=.07,y=.974,ha='left',fontsize=22,fontweight='bold')
    fig.text(.07,.929,'Log Y, 0–150°C · Before = liquid-only · After = new liquid composition + dispersed solids · Not a blend of isolated slurry viscosities',fontsize=11)
    fig.legend([Line2D([0],[0],color='#555C67',ls='--'),Line2D([0],[0],color='#007F78'),Line2D([0],[0],color='#C88627',ls=':')],['Before','After: 15% scenario','Remaining liquid only'],loc='upper left',bbox_to_anchor=(.065,.909),ncol=3,frameon=False)
    for ax,c in zip(axes.flat,records):
        lines(ax,c);ax.plot(TC,c['scenarios']['15']['liquid_matrix_pas'],':',color='#C88627',lw=1.4)
        ax.set_yscale('log');ax.set_title(c['name'],loc='left',pad=13,fontsize=12)
    footer(fig);save(fig,kind+'_before_after')

def sensitivity_plot(cuts):
    fig,axes=plt.subplots(2,2,figsize=(14,10));fig.subplots_adjust(left=.08,right=.97,bottom=.14,top=.79,wspace=.24,hspace=.37)
    fig.suptitle('Fusion assumptions change the precipitation window',x=.08,y=.975,ha='left',fontsize=21,fontweight='bold')
    fig.text(.08,.929,'15% share in every case · Four thermal descriptions · This is model sensitivity, not an uncertainty interval',fontsize=11)
    handles=[Line2D([0],[0],color='#007F78'),Line2D([0],[0],color='#8C62B0',ls='--'),Line2D([0],[0],color='#CE8031',ls=':'),Line2D([0],[0],color='#5A77A4',ls='-.')]
    fig.legend(handles,['Reference: Won / Broadhurst, ΔCp = 0','Lira-Galeana mixed fraction, ΔCp = 0','Reference + ΔCp estimate','Unbounded Won at all MW'],loc='upper left',bbox_to_anchor=(.074,.908),ncol=2,frameon=False,fontsize=10)
    for ax,index in zip(axes.flat,[7,9,10,12]):
        c=cuts[index]
        for s,col,style in [(c['scenarios']['15'],'#007F78','-'),(c['lg_sensitivity_15'],'#8C62B0','--'),(c['heat_capacity_sensitivity_15'],'#CE8031',':'),(c['unbounded_won_sensitivity_15'],'#5A77A4','-.')]:
            ax.plot(TC,100*s['solid_volume_fraction'],style,color=col,lw=2)
        ax.set_xlim(0,150);ax.set_ylim(bottom=0);ax.set_xlabel('Temperature (°C)');ax.set_ylabel('Solid volume (%)');ax.grid(color='#E4E9ED')
        ax.set_title(c['name']+f" · parent MW {c['mw_g_mol']:.0f} g/mol",loc='left',pad=12)
    footer(fig);save(fig,'fusion_model_sensitivity')

def tests():
    # Analytical binary ideal SLE: n_w=0.2, n_nonwax=0.8, x_sat=0.1.
    k=np.array([[.1]])
    l,s=phase_split(np.array([1.]),np.array([.2]),k)
    assert math.isclose(s[0,0],.2-.8*.1/.9,rel_tol=1e-12)
    assert math.isclose(l[0,0],.8/.9,rel_tol=1e-12)
    l2,s2=phase_split(np.array([1.,1.]),np.array([.2,0.]),np.array([[.1],[1.]]))
    assert s2[0,0]<s[0,0] # adding solvent suppresses crystallization
    assert math.isclose(float(kd(np.array([0]))[0]),1.)
    assert abs((kd(np.array([1e-7]))[0]-1)/1e-7-2.5)<1e-5
    assert np.isnan(kd(np.array([.64,.7]))).all() # no finite artificial cap
    for model in ['paraffin','won','lg']:
        k,tm,h=saturation(MW*1000,TK,model)
        assert (k>0).all() and (k<=1).all()
        assert (np.diff(k,axis=1)>=-1e-12).all()
    _,tm_bound,_=saturation(np.array([14.027*100000+2.016]),TK)
    assert 414<tm_bound[0]<414.3
    zero=case(np.ones(20),0.)
    assert np.allclose(zero['relative_to_before'],1.,rtol=1e-12)
    assert np.max(zero['solid_volume_fraction'])==0
    return {'analytical_binary_equilibrium':True,'solvent_dilution':True,'zero_wax_recovery':True,
            'einstein_dilute_limit':True,'reject_packing_limit':True,'equilibrium_complementarity_and_balance_all_cases':True,
            'note':'Numerical verification only; no experimental calibration is claimed.'}

def main():
    # Fail rather than silently change the before/after reference data.
    for path,digest in data['input_sha256'].items():assert hashlib.sha256((ROOT/path.replace('examples/cold-flow/', 'research/cold-flow/')).read_bytes()).hexdigest()==digest,path
    records=[]
    for j,cid in enumerate(IDS):
        if not ELIGIBLE[j]:continue
        z=np.eye(20)[j]
        records.append({'id':cid,'name':cid.replace('tjl19_','').upper(),'kind':'cut','mw_g_mol':MW[j]*1000,
                        'mole_fractions':z,'before_pas':MU[j]})
    for feed in data['records']:
        for kind,key in [('crude','mole_fractions'),('residue','residue_mole_fractions')]:
            records.append({'id':feed['id']+'_'+kind,'name':feed['name']+(' · 370°C+' if kind=='residue' else ''),
              'kind':kind,'mole_fractions':np.array(feed[key]),'before_pas':np.array(feed['series'][kind]['unit_corrected'])})
    for record in records:
        record['scenarios']={str(round(f*100)):case(record['mole_fractions'],f) for f in SHARES}
        record['lg_sensitivity_15']=case(record['mole_fractions'],.15,'lg')
        record['heat_capacity_sensitivity_15']=case(record['mole_fractions'],.15,'paraffin',True)
        record['unbounded_won_sensitivity_15']=case(record['mole_fractions'],.15,'won')
        individual(record)
    cuts=[r for r in records if r['kind']=='cut']
    for scale in ['linear','log']:overview(cuts,scale)
    relative_plot(cuts)
    sensitivity_plot(cuts)
    for kind in ['crude','residue']:blend_plot([r for r in records if r['kind']==kind],kind)
    k,tm,h=saturation(MW*1000,TK)
    doc={'temperature_celsius':TC,'baseline_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),
      'scenarios':{'crystallizable_share_per_pseudocomponent':SHARES,'reference_share':.15,'estimated':True,
        'fusion_temperature_model':'Won for equivalent carbon number <=44; Broadhurst 1962 for >44; virtual MW=parent MW',
        'fusion_enthalpy_model':'Won entropy approximation using the selected melting temperature; uncalibrated',
        'molecular_weight_rule':'virtual paraffin MW equals unchanged parent-cut MW; speculative, especially > C30',
        'liquid_activity_coefficients':1.,'solid_activity':1.,'heat_capacity_difference_main':0.,
        'wax_density_kg_m3':900.,'liquid_density_rule':'constant catalog standard-liquid density for volume conversion',
        'intrinsic_viscosity':2.5,'maximum_packing_fraction':.64,'rheological_state':'nonaggregating dispersed spheres, no yield stress'},
      'component_ids':IDS,'virtual_wax_fusion_temperature_kelvin':tm,'virtual_wax_fusion_enthalpy_j_mol':h,
      'records':records,'verification':tests()}
    # Keep constituent phase allocations for the reference scenario; totals and
    # curves are sufficient for the four alternative sensitivity scenarios.
    for record in records:
        for scenario in [record['scenarios']['5'],record['scenarios']['30'],record['lg_sensitivity_15'],record['heat_capacity_sensitivity_15'],record['unbounded_won_sensitivity_15']]:
            scenario.pop('solid_parent_moles_per_feed_mole');scenario.pop('liquid_parent_moles_per_feed_mole')
    (OUT/'wax_results.json').write_text(json.dumps(serial(doc),separators=(',',':'),allow_nan=False)+'\n')
    summary=['| Case | Solid vol% at 20°C | Before at 20°C (Pa·s) | After, 15% case (Pa·s) | After/before |', '|---|---:|---:|---:|---:|']
    for r in records:
        s=r['scenarios']['15'];idx=80
        summary.append(f"| {r['name']} | {100*s['solid_volume_fraction'][idx]:.3f} | {r['before_pas'][idx]:.6g} | {s['effective_pas'][idx]:.6g} | {s['relative_to_before'][idx]:.4g} |")
    (OUT/'summary.md').write_text('\n'.join(summary)+'\n',encoding='utf-8')
    print('\n'.join(summary));print('25 cases, 6 model/inventory scenarios each; numerical checks passed.')

if __name__=='__main__':main()
