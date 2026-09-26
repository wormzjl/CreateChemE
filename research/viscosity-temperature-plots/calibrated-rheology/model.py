"""Research transport calibration on the unchanged 20-component inventory."""
from pathlib import Path
import sys,json,math,hashlib
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'build/viscosity-plot-deps'))
sys.path.insert(0,str(HERE.parent/'component-reconstruction'))
import numpy as np
import reconstruct as old
import rheology

ALPHA=.0007 # assumed liquid volumetric expansion /K, sensitivity parameter
TC=np.linspace(-60,500,2241);TK=TC+273.15
SOURCES=json.loads((HERE/'source_transport.json').read_text())['records']
CONVERTED=json.loads((ROOT/'research/crude-assays/converted-compositions.json').read_text())
BASE=json.loads((HERE.parent/'component-reconstruction/reconstruction.json').read_text())
PKG=json.loads((old.MAT/'packages/tjl20.json').read_text())
PROPS=[json.loads((old.MAT/'properties'/f"{pid.split(':')[1]}.json").read_text()) for pid in PKG['properties']]
IDS=PKG['components'];MW=np.array([p['molecular_weight_kg_per_mol'] for p in PROPS])
RHO=np.array([p['standard_liquid_density_kg_per_m3'] for p in PROPS])
COND={r['component']:r for r in json.loads((ROOT/'src/main/resources/data/createcheme/fluid/dissolved_viscosity.json').read_text())['curves']}
H=np.array(BASE['records'][0]['residue_retention_factors'])
LIGHT=np.r_[np.zeros(7),1-H[7:]];HEAVY=H

def rho_at(rho15,t):return rho15/(1+ALPHA*(np.asarray(t)-15))

def walther_z(v):return v+.7+np.exp(-1.47-1.84*v-.51*v*v)

def fit(points):
    points=np.array(points);x=np.log10(points[:,0]+273.15)
    y=np.log10(np.log10(walther_z(points[:,1])))
    c=np.linalg.lstsq(np.column_stack([np.ones(len(x)),x]),y,rcond=None)[0]
    assert c[1]<0
    return c

def lognu(t,c):
    # Stable extended-Walther inverse in log space; no viscosity ceiling.
    lnz=np.log(10)*10**(c[0]+c[1]*np.log10(np.asarray(t)+273.15))
    safe=np.minimum(lnz,20);target=np.exp(safe)
    lo=np.zeros_like(target);hi=target.copy()
    for _ in range(60):
        m=(lo+hi)/2;small=walther_z(m)<target
        lo=np.where(small,m,lo);hi=np.where(small,hi,m)
    exact=np.log((lo+hi)/2)
    return np.where(lnz>20,lnz,exact)

def original_logmu(t):
    return np.array([np.log(old.letsou_dynamic(p,np.asarray(t)+273.15) if p['component'] in ['tjl19_pc12','tjl19_pc13'] else old.raw_component(p,COND,np.asarray(t)+273.15)[0]) for p in PROPS])

def source_curve(c,t):
    return lognu(t,fit(c['viscosity_points']))+np.log(rho_at(c['density_15c_g_ml']*1000,t))-np.log(1e6)

def component_prior(source,row,t):
    original=original_logmu(t);a=np.array(row['source_cut_to_pseudocomponent_mass_percent'])
    curves=[]
    nbp=np.array([p['normal_boiling_point_kelvin']-273.15 for p in PROPS[7:]])
    for i,c in enumerate(source['cuts']):
        if len(c['viscosity_points'])>=2:y=source_curve(c,t)
        else:
            # Only the three unmeasured naphtha intervals use the existing light-cut prior.
            weights=a[i]/a[i].sum();y=weights@original[7:]
        curves.append(y)
    result=original.copy()
    result[7:]=(a.T@np.array(curves))/a.sum(axis=0)[:,None]
    return result

def coefficients(source,row,basis='mole'):
    if basis!='mole':raise ValueError('This calibrated model retains the simulator molar mixing rule')
    z=np.array(row['mole_fractions'] if basis=='mole' else row['mass_fractions'])
    zr=z*H;zr/=zr.sum()
    # Two observations per aggregate calibrate four transport coefficients.
    # The interior 40C whole and 60C residue measurements remain held out.
    training=[];design=[];target=[]
    e=energy_shape(source,row)
    for kind,x,temps in [('whole',z,[20.,50.]),('residue',zr,[50.,100.])]:
        s=source[kind]
        for t in temps:
            nu=dict(s['viscosity_points'])[t]
            target_mu=nu*rho_at(s['density_15c_g_ml']*1000,t)/1e6
            xl,cold=phase_state(source,row,x,kind,np.array([t]),100.)
            ln_target=np.log((target_mu-cold['yield_stress_pascal'][0]/100)/cold['crowding_multiplier'][0])
            liquid_x=xl[:,0]
            prior=liquid_x@component_prior(source,row,np.array([t]))[:,0]
            design.append(interaction_basis(liquid_x[:,None],e,np.array([t]))[:,0]);target.append(ln_target-prior)
            training.append({'kind':kind,'temperature_celsius':t,'viscosity_cst':nu})
    c=np.linalg.solve(design,target)
    checks=[]
    for kind,x,t in [('whole',z,40.),('residue',zr,60.)]:
        observed=dict(source[kind]['viscosity_points'])[t]
        pred=aggregate_curve(source,row,c,x,kind,np.array([t]),100.)['apparent_pas'][0]*1e6/rho_at(source[kind]['density_15c_g_ml']*1000,t)
        checks.append({'kind':kind,'temperature_celsius':t,'observed_cst':observed,'predicted_cst':float(pred),'error_percent':float(100*(pred/observed-1))})
    return c,training,checks,z,zr

def calibrated_components(source,row,t,c):
    # Aggregate calibration belongs to mixture interactions, not pure-cut curves.
    return component_prior(source,row,t)

def interaction_basis(x,e,t):
    """Grunberg-Nissan-style symmetric pair contributions; exactly zero for pure i.
    Heavy/heavy pairs and all other pairs have separate a+b*q temperature terms.
    The activation weighting distributes slope correction without reversing
    source-component viscosity-temperature slopes.
    """
    x=np.asarray(x);e=np.asarray(e)[:,None]
    xh=x*H[:,None]
    fh=xh.sum(axis=0)**2-(xh*xh).sum(axis=0)
    fa=x.sum(axis=0)**2-(x*x).sum(axis=0)
    eh=xh.sum(axis=0)*(xh*e).sum(axis=0)-(xh*xh*e).sum(axis=0)
    ea=x.sum(axis=0)*(x*e).sum(axis=0)-(x*x*e).sum(axis=0)
    q=1000*(1/(np.minimum(np.asarray(t),100)+273.15)-1/323.15)
    return np.array([fa-fh,fh,(ea-eh)*q,eh*q])

def mixture_logmu(x,components,c,e,t):
    x=np.asarray(x)
    if x.ndim==1:x=np.repeat(x[:,None],len(t),axis=1)
    return (x*components).sum(axis=0)+np.asarray(c)@interaction_basis(x,e,t)

def energy_shape(source,row):
    prior=component_prior(source,row,np.array([40.,50.]))
    return np.maximum(0,(prior[:,0]-prior[:,1])/(1000*(1/313.15-1/323.15)))

def wax_profile(source,row):
    a=np.array(row['source_cut_to_pseudocomponent_mass_percent'])
    measured=np.array([c.get('wax_mass_percent',0)/100 for c in source['cuts']])
    # Missing distillate wax distribution is an explicit smooth prior. Two
    # nonnegative scale factors reconcile the reported whole/residue totals.
    unknown=np.r_[np.zeros(3),[.1,.3,.6,1.,1.],np.zeros(4)]
    h=np.r_[np.zeros(7),a.T@measured/a.sum(axis=0)]
    l=np.r_[np.zeros(7),a.T@unknown/a.sum(axis=0)]
    w=np.array(row['mass_fractions']);wr=w*H;wr/=wr.sum()
    scale=np.linalg.solve([[w@h,w@l],[wr@h,wr@l]],
      [source['whole']['wax_mass_percent']/100,source['residue']['wax_mass_percent']/100])
    f=scale[0]*h+scale[1]*l
    assert (scale>=0).all() and (f>=0).all() and (f<=1).all()
    assert np.allclose([w@f,wr@f],[source['whole']['wax_mass_percent']/100,source['residue']['wax_mass_percent']/100],atol=1e-14)
    return f,scale

def phase_state(source,row,z,kind,t,rate,**kwargs):
    f,_=wax_profile(source,row);s=source[kind];t=np.asarray(t)
    wax=float((z*MW)@f/(z@MW))
    cold=rheology.evaluate(t,np.ones(len(t)),rho_at(s['density_15c_g_ml']*1000,t),wax,s['pour_point_celsius'],rate,**kwargs)
    r=cold['precipitated_mass_fraction_proxy']/wax if wax else np.zeros(len(t))
    solid=z[:,None]*f[:,None]*r
    liquid=z[:,None]-solid
    assert (liquid>=0).all() and np.allclose(liquid+solid,z[:,None])
    assert np.allclose((solid*MW[:,None]).sum(axis=0)/(z@MW),cold['precipitated_mass_fraction_proxy'],atol=1e-14)
    return liquid/liquid.sum(axis=0),cold

def aggregate_curve(source,row,c,z,kind,t,rate,component_curves=None,**kwargs):
    xl,cold=phase_state(source,row,z,kind,t,rate,**kwargs)
    components=component_curves if component_curves is not None else calibrated_components(source,row,t,c)
    liquid=np.exp(mixture_logmu(xl,components,c,energy_shape(source,row),t))
    cold['remaining_liquid_pas']=liquid
    cold['apparent_pas']=liquid*cold['crowding_multiplier']+cold['yield_stress_pascal']/rate
    cold['all_liquid_pas']=np.exp(mixture_logmu(z,components,c,energy_shape(source,row),t))
    return cold

def diagnostic():
    for source,row in zip(SOURCES,CONVERTED['crudes']):
        assert source['reference']==row['source_reference']
        for basis in ['mole']:
            c,tr,checks,z,zr=coefficients(source,row,basis)
            print(source['id'],basis,'group correction coefficients',np.round(c,3),'holdout %',[(x['kind'],round(x['error_percent'],2)) for x in checks])
            curves=calibrated_components(source,row,np.arange(20,201),c)
            print('minimum/max derivative',np.diff(curves[7:],axis=1).min(),np.diff(curves[7:],axis=1).max())
if __name__=='__main__':diagnostic()
