package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;

/** IF97 Region 1 in SI units, with its original datum. Hybrid-reference offsets are applied by the caller. */
public final class WaterRegion1 {
    private static final double R = 461.526, P_STAR = 16.53e6, T_STAR = 1386;
    // IAPWS Region 1 table, expressed in (pi - 7.1): odd-I coefficients have reversed signs.
    private static final int[] I = {0,0,0,0,0,0,0,0,1,1,1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,8,8,21,23,29,30,31,32};
    private static final int[] J = {-2,-1,0,1,2,3,4,5,-9,-7,-1,0,1,3,-3,0,1,3,17,-4,0,6,-5,-2,10,-8,-11,-6,-29,-31,-38,-39,-40,-41};
    private static final double[] N = {
            .14632971213167,-.84548187169114,-3.756360367204,3.3855169168385,-.95791963387872,
            .15772038513228,-.016616417199501,.00081214629983568,-.00028319080123804,
            .00060706301565874,.018990068218419,.032529748770505,.021841717175414,.00005283835796993,
            -.00047184321073267,-.00030001780793026,.000047661393906987,-4.4141845330846e-6,
            -7.2694996297594e-16,.000031679644845054,2.8270797985312e-6,8.5205128120103e-10,
            -.0000022425281908,-6.5171222895601e-7,-1.4341729937924e-13,4.0516996860117e-7,
            -1.2734301741641e-9,-1.7424871230634e-10,6.8762131295531e-19,-1.4478307828521e-20,
            -2.6335781662795e-23,-1.1947622640071e-23,-1.8228094581404e-24,-9.3537087292458e-26};

    private WaterRegion1() {}

    public static State evaluate(double temperatureKelvin,double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < 273.16 || temperatureKelvin > 623.15
                || !Double.isFinite(pressurePascal) || pressurePascal <= 0 || pressurePascal > 100e6
                || pressurePascal < V3WaterProperties.saturationPressurePascal(temperatureKelvin)) {
            throw new IllegalArgumentException("Water state outside stable IF97 Region 1");
        }
        double pi = pressurePascal/P_STAR, tau=T_STAR/temperatureKelvin, x=pi-7.1,y=tau-1.222;
        double gp=0,gpp=0,gt=0,gtt=0,gpt=0,gptt=0;
        for (int k=0;k<N.length;k++) {
            int i=I[k],j=J[k]; double n=N[k];
            if(i!=0) gp+=n*i*Math.pow(x,i-1)*Math.pow(y,j);
            if(i>1) gpp+=n*i*(i-1)*Math.pow(x,i-2)*Math.pow(y,j);
            if(j!=0) gt+=n*j*Math.pow(x,i)*Math.pow(y,j-1);
            if(j!=0 && j!=1) gtt+=n*j*(j-1)*Math.pow(x,i)*Math.pow(y,j-2);
            if(i!=0 && j!=0) gpt+=n*i*j*Math.pow(x,i-1)*Math.pow(y,j-1);
            if(i!=0 && j!=0 && j!=1) gptt+=n*i*j*(j-1)*Math.pow(x,i-1)*Math.pow(y,j-2);
        }
        double v=R*temperatureKelvin*gp/P_STAR;
        double h=R*temperatureKelvin*tau*gt;
        double cp=-R*tau*tau*gtt;
        double vt=R/P_STAR*(gp-tau*gpt);
        double vp=R*temperatureKelvin*gpp/(P_STAR*P_STAR);
        if (!(v>0) || !(cp>0) || !(vp<0) || !Double.isFinite(h)) throw new IllegalArgumentException("Invalid Region 1 result");
        return new State(v,h,h-pressurePascal*v,cp,vt,vp,v-temperatureKelvin*vt,R*tau*tau*gptt/(P_STAR*temperatureKelvin));
    }

    /** IAPWS 2008 dilute-gas viscosity term (Pa.s); not a liquid or dense-steam correlation. */
    public static double diluteVaporViscosity(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin<273.16 || temperatureKelvin>900) {
            throw new IllegalArgumentException("Vapor viscosity outside v1 temperature envelope");
        }
        double reduced=temperatureKelvin/647.096;
        return 1e-4*Math.sqrt(reduced)/(1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced));
    }

    public record State(double specificVolume,double specificEnthalpy,double specificInternalEnergy,
                        double specificHeatCapacity,double volumeTemperatureDerivative,
                        double volumePressureDerivative,double enthalpyPressureDerivative,
                        double volumeSecondTemperatureDerivative) {}
}
