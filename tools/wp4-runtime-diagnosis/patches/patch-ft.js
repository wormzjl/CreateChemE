const fs=require('fs');
const p='patch-src/com/wormzjl/createcheme/science/fluid/thermo/FluidThermodynamics.java';
let s=fs.readFileSync(p,'utf8');
const old1=`    public WaterLiquid waterLiquid(double t,double p,Prepared prepared) {
        var w=waterAt(t,p,match(prepared,t));
        return new WaterLiquid(w.specificVolume()*waterMolecularWeight,w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset,
                w.volumePressureDerivative()*waterMolecularWeight,w.metastable());
    }`;
const new1=`    public WaterLiquid waterLiquid(double t,double p,Prepared prepared) {
        if(DIAG_MODE!=0){double[] d=diagWater(t,p,match(prepared,t));return new WaterLiquid(d[0],d[1]+waterEnthalpyOffset,d[2],d[3]!=0);}
        var w=waterAt(t,p,match(prepared,t));
        return new WaterLiquid(w.specificVolume()*waterMolecularWeight,w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset,
                w.volumePressureDerivative()*waterMolecularWeight,w.metastable());
    }
    // ---- WP4 runtime diagnosis only (scratch copy under build/diagnosis, never tracked) ----
    // diag.water=region1 (default) | legacy (Region 1 at 2 MPa carried by exp(-1e-9 (P-2 MPa)), the retired path) |
    // kappa (Region 1 at 101,325 Pa carried by exp(-k (P-P0)), k = diag.waterKappa): isolates the compressibility.
    static final int DIAG_MODE=switch(System.getProperty("diag.water","region1")){case "legacy"->1;case "kappa"->2;default->0;};
    static final double DIAG_KAPPA=DIAG_MODE==1?1e-9:Double.parseDouble(System.getProperty("diag.waterKappa","1e-9"));
    static final double DIAG_P0=DIAG_MODE==1?2e6:101325;
    /** {molar volume, molar enthalpy without offset, dv/dP molar, metastable flag, compressibility}. */
    private double[] diagWater(double t,double p,Prepared prepared) {
        var w=waterAt(t,DIAG_P0,prepared);double mw=waterMolecularWeight;
        double v0=w.specificVolume()*mw,vt0=w.volumeTemperatureDerivative()*mw,h0=w.specificEnthalpy()*mw;
        double exponent=-DIAG_KAPPA*(p-DIAG_P0);double factor=Math.exp(exponent),integral=-Math.expm1(exponent)/DIAG_KAPPA;
        double v=v0*factor,h=h0+(v0-t*vt0)*integral;
        double saturation=prepared==null?saturationPressure(t):prepared.saturationPressure();
        return new double[]{v,h,-DIAG_KAPPA*v,p<saturation?1:0,DIAG_KAPPA};
    }`;
if(!s.includes(old1))throw 'old1';
s=s.replace(old1,new1);
const old2=`            var w=waterAt(t,p,prepared);double molarVolume=w.specificVolume()*waterMolecularWeight;
            vw=waterLiquid*molarVolume;volume+=vw;h+=waterLiquid*(w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset);
            kw=-w.volumePressureDerivative()/w.specificVolume();`;
const new2=`            if(DIAG_MODE!=0){WaterRegion1.requireTemperature(domain.packageId(),water,t,p);double[] d=diagWater(t,p,prepared);vw=waterLiquid*d[0];volume+=vw;h+=waterLiquid*(d[1]+waterEnthalpyOffset);kw=d[4];}
            else {
            var w=waterAt(t,p,prepared);double molarVolume=w.specificVolume()*waterMolecularWeight;
            vw=waterLiquid*molarVolume;volume+=vw;h+=waterLiquid*(w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset);
            kw=-w.volumePressureDerivative()/w.specificVolume();
            }`;
if(!s.includes(old2))throw 'old2';
s=s.replace(old2,new2);
fs.writeFileSync(p,s);console.log('patched');
