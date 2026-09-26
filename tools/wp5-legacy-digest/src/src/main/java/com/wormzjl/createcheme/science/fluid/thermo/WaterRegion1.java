package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialRuntime;

/**
 * IF97 Region 1 in SI units, with its original datum. Hybrid-reference offsets are applied by the caller.
 *
 * <p>Two entries. {@link #evaluate(String, MaterialCatalog.Water, double, double)} is the stable liquid of the
 * region's own range of validity, {@code ps(T) <= p <= 100 MPa} (IAPWS R7-97(2012), section 5.1), and refuses a
 * pressure below saturation. {@link #evaluateLiquid} is the fluid network's liquid water at the state pressure: it
 * also admits the metastable superheated liquid below saturation, which the release covers qualitatively ("In
 * addition to the properties in the stable single-phase liquid region, Eq. (7) also yields reasonable values in the
 * metastable superheated-liquid region close to the saturated liquid line", section 5.1) with no quantitative limit,
 * so the admission carries the declared bound {@link #METASTABLE_MARGIN_PASCAL} and the result is flagged
 * {@link Workspace#metastable()}.
 */
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
    // Exponent span the 34 terms and their first two derivatives reach: x^0..x^32 and y^-43..y^17.
    private static final int HIGHEST_X = 32, LOWEST_Y = -43, HIGHEST_Y = 17;
    /**
     * The declared bound of the metastable admission: liquid water is evaluated down to {@code ps(T) - 2 MPa} (and the
     * package envelope's minimum, which the state's own domain check applies). 2 MPa is the span the retired
     * reference-pressure path covered: it evaluated Region 1 at 2 MPa for every state and so admitted every envelope
     * pressure wherever {@code ps(T) <= 2 MPa} (T up to 485.5 K); this bound admits exactly those states there and
     * keeps the same pressure distance from the saturated liquid line above it, where the release's "close to the
     * saturated liquid line" is the only statement of validity. Below the bound a trial is a
     * {@link ThermoDomainViolation} naming water, as a pressure below saturation was.
     */
    public static final double METASTABLE_MARGIN_PASCAL = 2e6;

    private WaterRegion1() {}

    public static State evaluate(double temperatureKelvin,double pressurePascal) {
        return evaluate(MaterialRuntime.water(),temperatureKelvin,pressurePascal);
    }

    /** The water record is only the saturation domain check's; a caller evaluating in a loop resolves it once. */
    public static State evaluate(MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal) {
        return evaluate("water model "+water.revision(),water,temperatureKelvin,pressurePascal);
    }
    /** IF97 Region 1's own upper temperature boundary: a boundary of the formulation, not a property-package range. */
    public static final double REGION_1_MAXIMUM_TEMPERATURE=623.15,REGION_1_MAXIMUM_PRESSURE=100e6;
    /**
     * The stable liquid of Region 1's range of validity. Liquid water outside it - below the water model's triple
     * point (ice is not modelled), above the region's 623.15 K boundary, or below its saturation pressure - is a
     * {@link ThermoDomainViolation} naming water, reported against {@code scope} (the fluid model's package). A
     * non-finite input stays a plain refusal.
     */
    public static State evaluate(String scope,MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal) {
        requireTemperature(scope,water,temperatureKelvin,pressurePascal);
        double saturation=V3WaterProperties.saturationPressurePascal(water,temperatureKelvin);
        if (pressurePascal < saturation || pressurePascal > REGION_1_MAXIMUM_PRESSURE)
            throw new ThermoDomainViolation(scope,"Water",ThermoDomainViolation.Property.PRESSURE,pressurePascal,saturation,REGION_1_MAXIMUM_PRESSURE);
        var workspace=new Workspace();
        fill(workspace,temperatureKelvin,pressurePascal);
        return workspace.state();
    }

    /**
     * The first half of {@link #evaluateLiquid}'s checks, so a caller can refuse a temperature outside Region 1 before
     * it evaluates the saturation pressure the second half needs: a finite positive state, and a temperature from the
     * water model's triple point to the region's 623.15 K boundary.
     */
    public static void requireTemperature(String scope,MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal) || pressurePascal <= 0)
            throw new IllegalArgumentException("Water state is not finite and positive");
        if (temperatureKelvin < water.triplePoint() || temperatureKelvin > REGION_1_MAXIMUM_TEMPERATURE)
            throw new ThermoDomainViolation(scope,"Water",ThermoDomainViolation.Property.TEMPERATURE,temperatureKelvin,water.triplePoint(),REGION_1_MAXIMUM_TEMPERATURE);
    }

    /**
     * Liquid water at the state pressure, into {@code workspace}, without allocating. {@code saturation} is the
     * network's saturation pressure at {@code temperatureKelvin} (the water model's, which the free-water rule also
     * uses). A pressure at or above it is the stable liquid; below it, down to {@code saturation -}
     * {@link #METASTABLE_MARGIN_PASCAL}, the metastable superheated liquid, flagged; below that, and above the
     * region's 100 MPa, a {@link ThermoDomainViolation} naming water. The temperature checks of
     * {@link #requireTemperature} apply too.
     */
    public static void evaluateLiquid(String scope,MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal,
                                      double saturation,Workspace workspace) {
        requireTemperature(scope,water,temperatureKelvin,pressurePascal);
        double minimum=saturation-METASTABLE_MARGIN_PASCAL;
        if (pressurePascal < minimum || pressurePascal > REGION_1_MAXIMUM_PRESSURE)
            throw new ThermoDomainViolation(scope,"Water",ThermoDomainViolation.Property.PRESSURE,pressurePascal,minimum,REGION_1_MAXIMUM_PRESSURE);
        fill(workspace,temperatureKelvin,pressurePascal);
        workspace.metastable=pressurePascal<saturation;
    }

    /**
     * The Gibbs-function sums at (T, p), with no range check. The powers of {@code tau - 1.222} depend on the
     * temperature alone and are kept for the workspace's last temperature; the powers of {@code pi - 7.1} are
     * rebuilt per call. The arithmetic and its order are fixed, so a reused workspace and a fresh one give the same
     * bits.
     */
    private static void fill(Workspace w,double temperatureKelvin,double pressurePascal) {
        double tau=T_STAR/temperatureKelvin;
        if (temperatureKelvin!=w.temperature) {
            double y=tau-1.222;double[] yp=w.yp;
            yp[-LOWEST_Y]=1;
            for (int k=-LOWEST_Y+1;k<yp.length;k++) yp[k]=yp[k-1]*y;
            double inverse=1/y;
            for (int k=-LOWEST_Y-1;k>=0;k--) yp[k]=yp[k+1]*inverse;
            w.temperature=temperatureKelvin;
        }
        double pi = pressurePascal/P_STAR, x=pi-7.1;
        // The integer powers, built once by multiplication instead of six Math.pow calls per term.
        double[] xp=w.xp,yp=w.yp;xp[0]=1;
        for (int k=1;k<xp.length;k++) xp[k]=xp[k-1]*x;
        double gp=0,gpp=0,gt=0,gtt=0,gpt=0,gptt=0;
        for (int k=0;k<N.length;k++) {
            int i=I[k],j=J[k]-LOWEST_Y; double n=N[k];
            if(i!=0) gp+=n*i*xp[i-1]*yp[j];
            if(i>1) gpp+=n*i*(i-1)*xp[i-2]*yp[j];
            if(J[k]!=0) gt+=n*J[k]*xp[i]*yp[j-1];
            if(J[k]!=0 && J[k]!=1) gtt+=n*J[k]*(J[k]-1)*xp[i]*yp[j-2];
            if(i!=0 && J[k]!=0) gpt+=n*i*J[k]*xp[i-1]*yp[j-1];
            if(i!=0 && J[k]!=0 && J[k]!=1) gptt+=n*i*J[k]*(J[k]-1)*xp[i-1]*yp[j-2];
        }
        double v=R*temperatureKelvin*gp/P_STAR;
        double h=R*temperatureKelvin*tau*gt;
        double cp=-R*tau*tau*gtt;
        double vt=R/P_STAR*(gp-tau*gpt);
        double vp=R*temperatureKelvin*gpp/(P_STAR*P_STAR);
        if (!(v>0) || !(cp>0) || !(vp<0) || !Double.isFinite(h)) throw new IllegalArgumentException("Invalid Region 1 result");
        w.pressure=pressurePascal;
        w.specificVolume=v;w.specificEnthalpy=h;w.specificInternalEnergy=h-pressurePascal*v;w.specificHeatCapacity=cp;
        w.volumeTemperatureDerivative=vt;w.volumePressureDerivative=vp;w.enthalpyPressureDerivative=v-temperatureKelvin*vt;
        w.volumeSecondTemperatureDerivative=R*tau*tau*gptt/(P_STAR*temperatureKelvin);
        w.metastable=false;
    }

    /**
     * Caller-owned Region 1 scratch and result: the two power tables and the last evaluation's properties. Mutable,
     * so - like the Peng-Robinson workspace a node's prepared temperature holds - it belongs to one solving thread.
     */
    public static final class Workspace {
        private final double[] xp=new double[HIGHEST_X+1],yp=new double[HIGHEST_Y-LOWEST_Y+1];
        private double temperature=Double.NaN,pressure=Double.NaN;
        private double specificVolume,specificEnthalpy,specificInternalEnergy,specificHeatCapacity,
                volumeTemperatureDerivative,volumePressureDerivative,enthalpyPressureDerivative,volumeSecondTemperatureDerivative;
        private boolean metastable;
        public Workspace() {}
        public double temperature(){return temperature;}
        public double pressure(){return pressure;}
        public double specificVolume(){return specificVolume;}
        public double specificEnthalpy(){return specificEnthalpy;}
        public double specificInternalEnergy(){return specificInternalEnergy;}
        public double specificHeatCapacity(){return specificHeatCapacity;}
        public double volumeTemperatureDerivative(){return volumeTemperatureDerivative;}
        public double volumePressureDerivative(){return volumePressureDerivative;}
        public double enthalpyPressureDerivative(){return enthalpyPressureDerivative;}
        public double volumeSecondTemperatureDerivative(){return volumeSecondTemperatureDerivative;}
        /** The last {@link #evaluateLiquid} was below the saturation pressure it was given (metastable liquid). */
        public boolean metastable(){return metastable;}
        /** A copy of the last evaluation as the immutable record. */
        public State state() {
            return new State(specificVolume,specificEnthalpy,specificInternalEnergy,specificHeatCapacity,volumeTemperatureDerivative,
                    volumePressureDerivative,enthalpyPressureDerivative,volumeSecondTemperatureDerivative);
        }
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
