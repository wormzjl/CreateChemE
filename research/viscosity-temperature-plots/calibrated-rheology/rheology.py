"""Phenomenological cold-rheology extension; parameters are priors, not fitted rheometry.

Reported pour point locates an uncertain structure-onset window. It is never
returned as an independently predicted pour point. Wax crystallization below
that window is an empirical bounded proxy, NOT a new solid-equilibrium solver.
"""
import numpy as np

def structure_after_time(initial,pre_shear_rate,seconds,build_seconds=300.,break_rate=10.):
    if not 0<=initial<=1 or min(pre_shear_rate,seconds)<0:raise ValueError('Invalid history')
    ratio=pre_shear_rate/break_rate
    equilibrium=1/(1+ratio)
    return equilibrium+(initial-equilibrium)*np.exp(-(1+ratio)*seconds/build_seconds)

def evaluate(t,mu_reference,rho_liquid,wax_mass_fraction,pour_point,shear_rate,
             strength=500.,onset_offset=10.,width=10.,structure=1.):
    """Return rested/conditioned Bingham-style apparent viscosity in Pa s.

    strength: Pa at 5 wt% precipitated wax, order-of-magnitude laboratory prior.
    The exponent 2.3 is literature-informed; the prefactor is not universal.
    structure is a fixed preconditioning state, not a function of the current
    measuring shear rate: this preserves a monotone stress-rate flow curve.
    """
    if shear_rate<=0:raise ValueError('No finite apparent viscosity at zero imposed shear')
    if not 0<=wax_mass_fraction<=1 or not 0<=structure<=1:raise ValueError('Invalid fraction')
    t=np.asarray(t);mu_reference=np.asarray(mu_reference)
    undercool=np.maximum(pour_point+onset_offset-t,0.)
    precipitated=wax_mass_fraction*(-np.expm1(-undercool/width))
    vs=precipitated/900.;vl=(1-precipitated)/np.asarray(rho_liquid)
    phi=vs/(vs+vl)
    if np.any(phi>=.64):raise ValueError('Dispersion model outside packing domain')
    # Assay-calibrated reference data are treated as the unstructured branch.
    # Cold crystal crowding is small at the fitted temperature anchors.
    multiplier=(1-phi/.64)**(-1.6)
    yield_stress=strength*(precipitated/.05)**2.3*structure
    mu=mu_reference*multiplier+yield_stress/shear_rate
    assert np.all(mu>=mu_reference) and np.all(precipitated<=wax_mass_fraction+1e-14)
    return {'apparent_pas':mu,'yield_stress_pascal':yield_stress,'precipitated_mass_fraction_proxy':precipitated,
            'solid_volume_fraction_proxy':phi,'crowding_multiplier':multiplier,'structure':structure}

def checks():
    t=np.array([-30.,0.,50.,100.]);base=np.array([.1,.05,.01,.001]);rho=np.full(4,900.)
    zero=evaluate(t,base,rho,0.,-20.,1.)
    assert np.array_equal(zero['apparent_pas'],base)
    zero_history=evaluate(t,base,rho,.1,-20.,1.,structure=0.)
    assert np.all(zero_history['yield_stress_pascal']==0)
    stresses=[]
    for rate in [.1,1,10,100,1000]:stresses.append(evaluate(t,base,rho,.1,-20.,rate)['apparent_pas']*rate)
    assert np.all(np.diff(stresses,axis=0)>0)
    assert structure_after_time(1.,100.,0.)==1.
    assert abs(structure_after_time(1.,100.,1e6)-1/11)<1e-12
    assert abs(structure_after_time(.1,0.,1e6)-1)<1e-12
    try:evaluate(t,base,rho,.1,-20.,0.)
    except ValueError:pass
    else:raise AssertionError('Zero shear rate was accepted')
    return {'zero_wax_recovery':True,'monotone_stress_vs_rate_at_fixed_history':True,
            'history_recovery_and_breakdown_limits':True,'zero_shear_viscosity_not_fabricated':True}
