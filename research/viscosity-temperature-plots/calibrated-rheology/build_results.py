"""Build local, untracked calibration figures and a reproducible result bundle."""
from pathlib import Path
import json,math,hashlib,html,zipfile,re
import model as m
import rheology as rh
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

OUT=Path(__file__).resolve().parent
COLORS=['#0072B2','#009E73','#AD659E','#D55E00','#6C45A2']
RATE_COLORS=['#713BA1','#B75E25','#008F83','#2168AB']
RATES=[.1,1.,10.,100.]
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False,
 'axes.edgecolor':'#B8C7CE','svg.fonttype':'none','figure.facecolor':'white','savefig.facecolor':'white'})

def serial(x):
    if isinstance(x,np.ndarray):return x.tolist()
    if isinstance(x,np.generic):return x.item()
    if isinstance(x,dict):return {k:serial(v) for k,v in x.items()}
    if isinstance(x,list):return [serial(v) for v in x]
    return x

def save(fig,name):
    fig.savefig(OUT/(name+'.png'),dpi=160);fig.savefig(OUT/(name+'.svg'));plt.close(fig)

def foot(fig):
    fig.text(.075,.045,'Calibration: reported cut data + 4 aggregate anchors/crude; 2 additional temperatures/crude excluded from coefficient fitting.',fontsize=9,color='#425B6C')
    fig.text(.075,.022,'Cold rheology is a scenario, not measured yield stress. Pour point locates a prior transition window; it is not independently predicted.',fontsize=9,color='#9B4B2B')

def decorate(ax,scale,xlim,title):
    ax.set_yscale(scale);ax.set_xlim(*xlim);ax.set_xlabel('Temperature (°C)');ax.set_ylabel('Apparent dynamic viscosity (Pa·s)')
    ax.grid(color='#E1E8EB');ax.set_axisbelow(True);ax.set_title(title,loc='left',pad=13,fontweight='bold',fontsize=12)
    if scale=='linear':ax.set_ylim(bottom=0);ax.ticklabel_format(axis='y',style='sci',scilimits=(-3,4),useMathText=True)

def draw_points(ax,source,kind):
    s=source[kind];p=np.array(s['viscosity_points']);vals=p[:,1]*m.rho_at(s['density_15c_g_ml']*1000,p[:,0])/1e6
    hold=40 if kind=='whole' else 60
    for t,v in zip(p[:,0],vals):
        ax.scatter(t,v,s=38,marker='D' if t==hold else 'o',facecolors='white' if t==hold else '#1C3546',edgecolors='#1C3546',zorder=7)

def individual(record):
    source=record['source'];name=source['name'];stem=record['id']
    for full in [False,True]:
        fig,axes=plt.subplots(2,2,figsize=(14,10.5));fig.subplots_adjust(left=.085,right=.97,bottom=.13,top=.82,wspace=.28,hspace=.42)
        fig.suptitle(name,x=.085,y=.97,ha='left',fontsize=23,fontweight='bold')
        fig.text(.085,.928,('0–500°C extension · Linear and log Y' if full else 'Cold-region detail · Log Y · Left: before/after at 100 s⁻¹; right: rested response at several shear rates'),fontsize=12)
        if full:
            fig.legend([Line2D([0],[0],color='#667988',ls='--'),Line2D([0],[0],color='#008F83')],['Previous shared-component model','New calibrated model + network, 100 s⁻¹'],loc='upper left',bbox_to_anchor=(.078,.899),ncol=2,frameon=False)
        else:
            fig.legend([Line2D([0],[0],color=c) for c in RATE_COLORS],[f'{r:g} s⁻¹' for r in RATES],loc='upper left',bbox_to_anchor=(.52,.899),ncol=4,frameon=False)
        for row,kind in zip(axes,['whole','residue']):
            s=record[kind];pp=source[kind]['pour_point_celsius'];old=np.array(s['previous_pas'])
            if full:
                for ax,scale in zip(row,['linear','log']):
                    ax.plot(m.TC,old,'--',color='#667988',lw=1.3);ax.plot(m.TC,s['rates']['100.0']['apparent_pas'],color='#008F83',lw=2)
                    decorate(ax,scale,(0,500),('Whole crude' if kind=='whole' else '370°C+ residue')+' · '+scale+' Y')
                    # Autoscale only on the visible full-range interval.
                    idx=m.TC>=0;values=np.r_[old[idx],s['rates']['100.0']['apparent_pas'][idx]]
                    if scale=='linear':ax.set_ylim(0,values.max()*1.06)
                    else:ax.set_ylim(values.min()*.7,values.max()*1.4)
                    ax.axvspan(150,500,color='#E8EDF0',alpha=.55,zorder=-3)
                    draw_points(ax,source,kind)
            else:
                ax=row[0];ax.plot(m.TC,old,'--',color='#667988',lw=1.3,label='Previous')
                ax.plot(m.TC,s['rates']['100.0']['apparent_pas'],color='#008F83',lw=2,label='Calibrated + network')
                decorate(ax,'log',(-60,150),'Whole crude' if kind=='whole' else '370°C+ residue');draw_points(ax,source,kind)
                ax.legend(frameon=False,fontsize=9)
                ax=row[1]
                ax.fill_between(m.TC,s['sensitivity_min_10_pas'],s['sensitivity_max_10_pas'],color='#008F83',alpha=.13)
                for rate,color in zip(RATES,RATE_COLORS):ax.plot(m.TC,s['rates'][str(rate)]['apparent_pas'],color=color,lw=1.8)
                decorate(ax,'log',(-60,150),'Shear-rate dependence; shade = 10 s⁻¹ sensitivity')
                for ax in row:
                    ax.axvline(pp,color='#B26A33',lw=1,ls=':');ax.text(.97,.035,f'Reported pour point: {pp:g}°C',ha='right',transform=ax.transAxes,fontsize=9,color='#99532B')
                    ax.axvspan(-60,20 if kind=='whole' else 50,color='#F0E9E0',alpha=.35,zorder=-3)
        foot(fig);save(fig,stem+('_full_range' if full else '_cold_detail'))

def comparison(records,kind):
    fig,axes=plt.subplots(1,2,figsize=(16,7.5));fig.subplots_adjust(left=.075,right=.97,bottom=.16,top=.74,wspace=.24)
    title='Whole crudes' if kind=='whole' else 'Ideal 370°C+ residues'
    fig.suptitle(title+': calibrated component reconstruction',x=.075,y=.974,ha='left',fontsize=22,fontweight='bold')
    fig.text(.075,.923,'Rested response at 100 s⁻¹ · Reported cut viscosities and wax inventories · Low-temperature structure remains a prior',fontsize=12)
    fig.legend([Line2D([0],[0],color=c,lw=2) for c in COLORS],[r['source']['name'] for r in records],loc='upper left',bbox_to_anchor=(.068,.892),ncol=3,frameon=False)
    for ax,scale,xlim in zip(axes,['linear','log'],[(0,500),(-60,150)]):
        for r,c in zip(records,COLORS):
            ax.plot(m.TC,r[kind]['rates']['100.0']['apparent_pas'],color=c,lw=2)
        decorate(ax,scale,xlim,('0–500°C · linear Y' if scale=='linear' else '−60–150°C · log Y'))
        if scale=='linear':
            ax.set_ylim(0,1.05*max(np.array(r[kind]['rates']['100.0']['apparent_pas'])[m.TC>=0].max() for r in records))
            ax.axvspan(150,500,color='#E8EDF0',alpha=.5,zorder=-3)
        else:
            ax.axvspan(-60,20 if kind=='whole' else 50,color='#F0E9E0',alpha=.4,zorder=-3)
            ax.text(.97,.95,'Shaded cold range:\nextrapolation + rheology assumptions',ha='right',va='top',transform=ax.transAxes,fontsize=9,color='#8C6348')
    foot(fig);save(fig,kind+'_comparison')

def cut_plot(record):
    fig,axes=plt.subplots(4,4,figsize=(16.5,13));fig.subplots_adjust(left=.07,right=.97,bottom=.10,top=.86,wspace=.35,hspace=.56)
    fig.suptitle(record['source']['name']+' · source-based cut transport curves',x=.07,y=.97,ha='left',fontsize=20,fontweight='bold')
    fig.text(.07,.927,'Crude-specific liquid/capillary branch · The same IDs, MW, NBP, PR and Cp remain unchanged · 0–200°C',fontsize=11)
    fig.legend([Line2D([0],[0],color='#667988',ls='--'),Line2D([0],[0],color='#008F83')],['Previous shared curves','Reported source-cut mapping'],loc='upper left',bbox_to_anchor=(.065,.907),ncol=2,frameon=False)
    previous=m.original_logmu(m.TC)
    for j,ax in enumerate(axes.flat):
        if j>=13:ax.axis('off');continue
        ax.plot(m.TC,np.exp(previous[j+7]),'--',color='#667988',lw=1.2)
        ax.plot(m.TC,np.exp(record['component_logmu'][j+7]),color='#008F83',lw=1.8)
        ax.set_xlim(0,200);ax.set_yscale('log');ax.grid(color='#E5EBEF');ax.set_title(f'PC{j+1:02}',loc='left',fontsize=11)
        mask=(m.TC>=0)&(m.TC<=200);vals=np.r_[np.exp(previous[j+7,mask]),np.exp(record['component_logmu'][j+7,mask])]
        ax.set_ylim(vals.min()*.65,vals.max()*1.5);ax.set_xlabel('°C',fontsize=9);ax.set_ylabel('Pa·s',fontsize=9);ax.tick_params(labelsize=8)
    axes.flat[13].text(0,1,'550°C+ transport is unresolved within\nthe source assay: no invented heavy-tail\nviscosity gradient.\n\nThe aggregate-fit correction acts through\nmixture interactions and is zero for a\npure component.\n\nCurves are rebinned source-cut estimates,\nnot measurements of these exact IDs.',va='top',fontsize=10,color='#5C6C75')
    foot(fig);save(fig,record['id']+'_cut_transport')

def tia_plot(records):
    tia=m.BASE['records'][0]
    fig,axes=plt.subplots(1,2,figsize=(14,7));fig.subplots_adjust(left=.085,right=.97,bottom=.16,top=.72,wspace=.25)
    fig.suptitle('Tia Juana: transfer range only',x=.085,y=.97,ha='left',fontsize=23,fontweight='bold')
    fig.text(.085,.915,'Same Tia Juana composition, five donor transport descriptions. No matching assay or pour-point calibration is available.',fontsize=11)
    fig.legend([Line2D([0],[0],color=c) for c in COLORS],[r['source']['name'] for r in records],loc='upper left',bbox_to_anchor=(.079,.876),ncol=3,frameon=False)
    outputs={}
    for ax,kind,key in zip(axes,['whole','residue'],['mole_fractions','residue_mole_fractions']):
        z=np.array(tia[key]);ys=[]
        for r,color in zip(records,COLORS):
            y=np.exp(m.mixture_logmu(z,r['component_logmu'],r['calibration_coefficients'],r['energy_shape'],m.TC));ys.append(y);ax.plot(m.TC,y,color=color)
        decorate(ax,'log',(20,150),'Whole composition' if kind=='whole' else '370°C+ composition')
        vis=np.array(ys)[:,(m.TC>=20)&(m.TC<=150)];ax.set_ylim(vis.min()*.5,vis.max()*2)
        outputs[kind]={'donor_curves_pas':ys,'interpretation':'Transfer sensitivity only; not a calibrated Tia Juana viscosity or gel prediction.'}
    fig.text(.085,.05,'No donor pour point or gel parameter is assigned to Tia Juana. This spread is not a statistical confidence interval.',fontsize=10,color='#9B4B2B')
    save(fig,'tia_juana_transfer');return outputs

def main():
    records=[];checks=[];training_checks=[]
    for source,row in zip(m.SOURCES,m.CONVERTED['crudes']):
        c,training,held,z,zr=m.coefficients(source,row)
        curves=m.calibrated_components(source,row,m.TC,c)
        assert np.isfinite(curves).all() and (np.diff(curves[7:],axis=1)<1e-12).all()
        f,scale=m.wax_profile(source,row)
        record={'id':row['id'],'source':source,'calibration_coefficients':c,'training':training,'temperature_checks':held,
                'component_logmu':curves,'energy_shape':m.energy_shape(source,row),'component_wax_mass_fractions':f,'wax_reconciliation_scales':scale}
        for j in range(20):
            pure=np.eye(20)[j];test=m.mixture_logmu(pure,curves,c,record['energy_shape'],m.TC)
            assert np.allclose(test,curves[j],atol=1e-13)
        before=next(r for r in m.BASE['records'] if r['id']==row['id'])
        for kind,x in [('whole',z),('residue',zr)]:
            rates={str(g):m.aggregate_curve(source,row,c,x,kind,m.TC,g,component_curves=curves) for g in RATES}
            scenarios=[]
            for strength in [50.,500.,5000.]:
                for onset in [5.,10.,20.]:
                    scenarios.append(m.aggregate_curve(source,row,c,x,kind,m.TC,10.,component_curves=curves,strength=strength,onset_offset=onset)['apparent_pas'])
            conditioned=m.aggregate_curve(source,row,c,x,kind,m.TC,10.,component_curves=curves,structure=rh.structure_after_time(1.,100.,300.))
            scenarios.append(conditioned['apparent_pas'])
            original=np.exp(x@m.original_logmu(m.TC))
            record[kind]={'mole_fractions':x,'previous_pas':original,'rates':rates,
                          'sensitivity_min_10_pas':np.min(scenarios,axis=0),'sensitivity_max_10_pas':np.max(scenarios,axis=0),
                          'pre_sheared_10_pas':conditioned['apparent_pas']}
            for rate,s in rates.items():
                assert np.isfinite(s['apparent_pas']).all() and (s['apparent_pas']>0).all()
                clear=m.TC>=source[kind]['pour_point_celsius']+10
                assert np.allclose(s['apparent_pas'][clear],s['all_liquid_pas'][clear],rtol=1e-12)
        for tr in training:
            kind=tr['kind'];x=z if kind=='whole' else zr;t=tr['temperature_celsius']
            predicted=m.aggregate_curve(source,row,c,x,kind,np.array([t]),100.)['apparent_pas'][0]*1e6/m.rho_at(source[kind]['density_15c_g_ml']*1000,t)
            error=predicted/tr['viscosity_cst']-1
            assert abs(error)<1e-10;training_checks.append(error)
        for check in held:
            assert abs(check['error_percent'])<5
            checks.append({'id':row['id'],**check})
        records.append(record);individual(record);cut_plot(record)
    for kind in ['whole','residue']:comparison(records,kind)
    tia=tia_plot(records)
    verification={**rh.checks(),'training_count':len(training_checks),'maximum_training_relative_error':max(map(abs,training_checks)),
      'temperature_check_count':len(checks),'maximum_temperature_check_error_percent':max(abs(c['error_percent']) for c in checks),
      'mean_absolute_temperature_check_error_percent':np.mean([abs(c['error_percent']) for c in checks]),
      'pseudo_curve_monotonicity_checked':True,'wax_inventory_and_phase_mass_balance_checked':True,
      'scope':'Same-assay temperature checks; not external crude validation or validation of low-temperature rheology.'}
    output={'temperature_celsius':m.TC,'components':m.IDS,'records':records,'tia_juana_transfer':tia,'verification':verification,
      'parameters':{'mixing':'log dynamic viscosity with liquid parent mole fractions plus symmetric Grunberg-Nissan-style pair terms','reference_measurement_rate_assumed_s_inverse':100,
        'gel_strength_prior_pascal_at_5wtpct_wax':500,'strength_sensitivity':[50,500,5000],'onset_offset_above_pour_celsius':10,
        'onset_offset_sensitivity':[5,10,20],'solid_fraction_proxy_width_kelvin':10,'wax_content_exponent':2.3,
        'crystal_density_assumed_kg_m3':900,'thermal_expansion_assumed_per_kelvin':m.ALPHA,
        'yield_stress_data_available':False,'DSC_solid_fraction_data_available':False,'pour_point_is_input_not_prediction':True,
        'steady_cooling_and_glass_transition_models_available':False}}
    (OUT/'calibrated_results.json').write_text(json.dumps(serial(output),separators=(',',':'),allow_nan=False)+'\n')
    (OUT/'verification.json').write_text(json.dumps(serial(verification),indent=2)+'\n')
    lines=['| Crude | Whole at 40°C: reported / predicted cSt | Error | Residue at 60°C: reported / predicted cSt | Error |','|---|---|---:|---|---:|']
    for r in records:
        a,b=r['temperature_checks'];lines.append(f"| {r['source']['name']} | {a['observed_cst']:.4g} / {a['predicted_cst']:.4g} | {a['error_percent']:+.2f}% | {b['observed_cst']:.4g} / {b['predicted_cst']:.4g} | {b['error_percent']:+.2f}% |")
    (OUT/'validation.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('\n'.join(lines));print(verification)

if __name__=='__main__':main()
