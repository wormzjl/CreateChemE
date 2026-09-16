package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Holds every field of every {@code Phase} the rewritten {@link TranslatedPengRobinson} produces against
 * {@link LegacyTranslatedPengRobinson}, the dense pre-kernel implementation kept verbatim as a test oracle.
 *
 * <p>The two compute the same function by different arithmetic. The old one built three n x n matrices per
 * temperature and read the quadratic mixture sums out of them; the new one forms {@code S_i} from the eleven
 * nonzero interaction pairs of the network's package. That part agrees at roundoff, and the tolerance for it
 * is argued rather than chosen: the mixture sums carry a relative error of order {@code n * eps}, about
 * 2.4e-15 over 21 components, and the volumetric block amplifies it through the {@code v - b} and
 * {@code v^2 + 2bv - b^2} cancellations by two or three orders. {@link #TOLERANCE} is that bound with room.</p>
 *
 * <h2>The states where they do not agree to it, and why that is the point</h2>
 *
 * <p>The two take the compressibility from different code: the legacy from
 * {@code PengRobinson78.compressibilityRoot}, which is the plain analytic Cardano formula, the kernel from
 * its own {@code selectRoot}, which repairs that formula's cancellation and then refines the root inside its
 * derivative-monotonic interval. On this grid the difference is invisible - the median deviation of every
 * field is zero or one ulp - except on the low-pressure liquid root, where {@code Z} sits within a few ulp of
 * {@code B} and the analytic root loses most of its digits. There the two differ by up to 4e-4 relative.</p>
 *
 * <p>So the test referees them. It recomputes the cubic independently from the classical mixing rule and the
 * PR78 critical constants, evaluates both compressibilities in it, and requires that wherever the two
 * implementations differ by more than {@link #TOLERANCE}, the legacy root is the backward-inaccurate one and
 * the kernel's leaves the strictly smaller residual. Agreement is the rule, and every exception to it is the
 * kernel being right. Nothing in production reaches those states in any case: {@code HydrocarbonModel}
 * evaluates the liquid root only at its 2 MPa reference pressure, and the sub-kilopascal liquid root on this
 * grid exists to exercise the repair.</p>
 */
class TranslatedPengRobinsonOracleTest {
    private static final String PACKAGE_ID="createcheme:tjl20_methane";
    /** Argued above from n*eps, with room for the volumetric block's cancellations. */
    private static final double TOLERANCE=1.0e-12;
    /** Fields whose error is absolute because they are exponents. */
    private static final java.util.Set<String> LOGARITHMIC=java.util.Set.of("ln phi_i");
    private static final double[] TEMPERATURES={273.16,290,310,350,400,450,520,600};
    private static final double[] PRESSURES={100,1e4,1e5,5e5,1e6,2e6};

    @Test void everyPhaseFieldReproducesTheDensePreKernelImplementation() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE_ID,1e-9);
        var inputs=model.hydrocarbon.translated().inputs();
        int count=inputs.components().size();
        assertEquals(21,count,"the network basis is the package plus nitrogen");
        assertTrue(model.hydrocarbon.components().contains("Nitrogen"));
        int pairs=0;
        for(int i=0;i<count;i++)for(int j=i+1;j<count;j++)if(inputs.interactions()[i][j]!=0)pairs++;
        assertEquals(11,pairs,"the sparse-pair plan is exercised on the package's real interaction set");
        var mixtures=compositions(count,model.hydrocarbon.components().indexOf("Nitrogen"));

        var interacting=compare("11 nonzero pairs",model.hydrocarbon.translated(),
                new LegacyTranslatedPengRobinson(inputs.components(),inputs.interactions(),
                        inputs.heatCapacityCoefficients(),inputs.translations()),
                inputs.components(),inputs.interactions(),inputs.translations(),mixtures);
        // The degenerate case of the same plan: with no pair at all the correction list is empty and the row
        // sums are the plain rank-one ones, which is the arithmetic every zero-interaction package gets.
        double[][] noInteractions=new double[count][count];
        var free=compare("no nonzero pair",
                new TranslatedPengRobinson(inputs.components(),noInteractions,
                        inputs.heatCapacityCoefficients(),inputs.translations()),
                new LegacyTranslatedPengRobinson(inputs.components(),noInteractions,
                        inputs.heatCapacityCoefficients(),inputs.translations()),
                inputs.components(),noInteractions,inputs.translations(),mixtures);

        for(var result:List.of(interacting,free)) {
            System.out.println(result);
            assertTrue(result.resolved+result.repaired>1000,
                    result.label+": the grid must actually evaluate ("+result.resolved+"+"+result.repaired+")");
            assertEquals(0,result.disagreements,result.label+": one implementation accepted a state the other refused");
            assertTrue(result.repaired>0,result.label+": the grid must reach the states the root repair exists for");
            assertEquals(0,result.rootRegressions,
                    result.label+": the kernel's root must be the better root wherever the two differ");
            result.fields.forEach((field,statistic)->assertTrue(statistic.resolvedMaximum<=TOLERANCE,
                    result.label+" "+field+": "+statistic));
        }
    }

    /** Physically plausible mixtures plus the awkward ones: trace, exact zeros, pure, nitrogen-heavy. */
    private static List<double[]> compositions(int count,int nitrogen) {
        var all=new ArrayList<double[]>();
        double[] heavy=new double[count];
        for(int i=0;i<count;i++)heavy[i]=Math.exp(-i/6.0);
        heavy[nitrogen]=1e-3;
        all.add(heavy);
        double[] light=new double[count];
        light[0]=.6;light[1]=.2;light[2]=.1;light[nitrogen]=.1;
        all.add(light);
        double[] trace=heavy.clone();
        for(int i=count-6;i<count;i++)if(i!=nitrogen)trace[i]=1e-12;
        all.add(trace);
        double[] zeros=new double[count];
        for(int i:new int[]{0,3,7,12,19})zeros[i]=1.0/(1+i);
        all.add(zeros);
        double[] pureNitrogen=new double[count];pureNitrogen[nitrogen]=1;all.add(pureNitrogen);
        double[] pureMethane=new double[count];pureMethane[0]=1;all.add(pureMethane);
        double[] equimolar=new double[count];java.util.Arrays.fill(equimolar,1);all.add(equimolar);
        double[] nitrogenAndResidue=new double[count];
        nitrogenAndResidue[nitrogen]=.5;nitrogenAndResidue[count-1==nitrogen?count-2:count-1]=.5;
        all.add(nitrogenAndResidue);
        var random=new Random(20260916L);
        for(int sample=0;sample<5;sample++) {
            double[] mixture=new double[count];
            for(int i=0;i<count;i++)mixture[i]=random.nextInt(4)==0?0:random.nextDouble();
            mixture[nitrogen]=random.nextDouble()*.05;
            boolean empty=true;for(double value:mixture)empty&=value==0;
            if(empty)mixture[0]=1;
            all.add(mixture);
        }
        return List.copyOf(all);
    }

    private static Result compare(String label,TranslatedPengRobinson current,LegacyTranslatedPengRobinson legacy,
                                  List<ThermoComponent> components,double[][] interactions,double[] translations,
                                  List<double[]> compositions) {
        var samples=new LinkedHashMap<String,List<Sample>>();
        int resolved=0,repaired=0,disagreements=0,rootRegressions=0;
        double worstRepaired=0;String worstRepairedAt="-";
        for(double temperature:TEMPERATURES) {
            var workspace=current.prepare(temperature);
            var terms=legacy.temperatureTerms(temperature);
            for(double pressure:PRESSURES)for(PhaseRoot root:PhaseRoot.values())for(int mixture=0;mixture<compositions.size();mixture++) {
                double[] amounts=compositions.get(mixture);
                String at="T="+temperature+" P="+pressure+" "+root+" mixture "+mixture;
                TranslatedPengRobinson.Phase expected=null,actual=null;
                try{expected=legacy.evaluate(temperature,pressure,amounts,root,terms);}catch(RuntimeException refused){}
                try{actual=current.evaluate(temperature,pressure,amounts,root,workspace);}catch(RuntimeException refused){}
                if(expected==null||actual==null) {
                    if(expected!=null||actual!=null)disagreements++;
                    continue;
                }
                assertEquals(expected.vaporBranch(),actual.vaporBranch(),label+" vapor branch at "+at);
                var referee=new Referee(components,interactions,translations,amounts,temperature,pressure);
                boolean repairedHere=!referee.backwardAccurate(referee.compressibility(expected))
                        && referee.backwardAccurate(referee.compressibility(actual));
                if(repairedHere) {
                    repaired++;
                    if(Math.abs(referee.residual(referee.compressibility(actual)))
                            >Math.abs(referee.residual(referee.compressibility(expected))))rootRegressions++;
                } else {
                    resolved++;
                }
                var oracle=expected;var kernel=actual;boolean counted=!repairedHere;
                record(samples,"molarVolume",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::molarVolume);
                record(samples,"molarEnthalpy",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::molarEnthalpy);
                record(samples,"molarInternalEnergy",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::molarInternalEnergy);
                record(samples,"dv/dT",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::volumeTemperatureDerivative);
                record(samples,"dv/dP",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::volumePressureDerivative);
                record(samples,"cp",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::heatCapacity);
                record(samples,"dh/dP",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::enthalpyPressureDerivative);
                record(samples,"isothermalCompressibility",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::isothermalCompressibility);
                record(samples,"d2v/dT2",at,oracle,kernel,counted,TranslatedPengRobinson.Phase::volumeSecondTemperatureDerivative);
                for(int i=0;i<oracle.logFugacityCoefficientsView().length;i++) {
                    int component=i;
                    record(samples,"ln phi_i",at,oracle,kernel,counted,phase->phase.logFugacityCoefficientsView()[component]);
                    record(samples,"partial molar volume",at,oracle,kernel,counted,phase->phase.partialMolarVolumesView()[component]);
                }
                if(repairedHere) {
                    double deviation=relative(oracle.molarVolume(),kernel.molarVolume());
                    if(deviation>worstRepaired){worstRepaired=deviation;worstRepairedAt=at;}
                }
            }
        }
        var statistics=new LinkedHashMap<String,Statistic>();
        samples.forEach((field,values)->{
            double scale=0;
            for(Sample sample:values)scale=Math.max(scale,Math.abs(sample.expected));
            // A quantity that passes through zero on this grid - the ideal-gas enthalpy datum sits at
            // 298.15 K, d2v/dT2 changes sign - has no relative error of its own there, so each field is
            // measured against its own range as well as against the value. A logarithm is measured
            // absolutely on top of that: ln phi is an exponent, and 1e-12 of it is 1e-12 of the fugacity
            // coefficient however small ln phi happens to be.
            double floor=Math.max(1e-8*scale,LOGARITHMIC.contains(field)?1.0:0.0);
            var deviations=new ArrayList<Double>(values.size());
            double resolvedMaximum=0,repairedMaximum=0;String worstAt="-";double worstExpected=0,worstActual=0;
            for(Sample sample:values) {
                double denominator=Math.max(Math.max(Math.abs(sample.expected),Math.abs(sample.actual)),floor);
                double deviation=denominator==0?0:Math.abs(sample.expected-sample.actual)/denominator;
                if(sample.resolved) {
                    deviations.add(deviation);
                    if(deviation>resolvedMaximum) {
                        resolvedMaximum=deviation;worstAt=sample.at;worstExpected=sample.expected;worstActual=sample.actual;
                    }
                } else repairedMaximum=Math.max(repairedMaximum,deviation);
            }
            deviations.sort(null);
            statistics.put(field,new Statistic(resolvedMaximum,percentile(deviations,.99),percentile(deviations,.5),
                    repairedMaximum,worstAt,worstExpected,worstActual,deviations.size()));
        });
        return new Result(label,resolved,repaired,disagreements,rootRegressions,worstRepaired,worstRepairedAt,statistics);
    }

    /**
     * An independent PR cubic for one state: classical mixing over the PR78 critical constants, built here
     * rather than read out of either implementation, so the residual it reports is a referee's and not a
     * restatement of one side's own arithmetic.
     */
    private static final class Referee {
        private final double c2,c1,c0,shift,reducedTemperature;
        Referee(List<ThermoComponent> components,double[][] interactions,double[] translations,double[] amounts,
                double temperature,double pressure) {
            int n=components.size();
            double total=0;for(double value:amounts)total+=value;
            double[] x=new double[n];for(int i=0;i<n;i++)x[i]=amounts[i]/total;
            double[] attraction=new double[n],coVolume=new double[n];
            double gasConstant=PengRobinson78.GAS_CONSTANT;
            for(int i=0;i<n;i++) {
                var component=components.get(i);double w=component.acentricFactor();
                double kappa=w<=.491?.37464+1.54226*w-.26992*w*w:.379642+1.48503*w-.164423*w*w+.016666*w*w*w;
                double criticalA=.45724*gasConstant*gasConstant*component.criticalTemperatureKelvin()
                        *component.criticalTemperatureKelvin()/component.criticalPressurePascal();
                double alpha=1+kappa*(1-Math.sqrt(temperature/component.criticalTemperatureKelvin()));
                attraction[i]=criticalA*alpha*alpha;
                coVolume[i]=.07780*gasConstant*component.criticalTemperatureKelvin()/component.criticalPressurePascal();
            }
            double a=0,b=0,translation=0;
            for(int i=0;i<n;i++) {
                b+=x[i]*coVolume[i];translation+=x[i]*translations[i];
                for(int j=0;j<n;j++)a+=x[i]*x[j]*Math.sqrt(attraction[i]*attraction[j])*(1-interactions[i][j]);
            }
            shift=translation;reducedTemperature=gasConstant*temperature/pressure;
            double reducedA=a*pressure/(gasConstant*temperature*gasConstant*temperature);
            double reducedB=b*pressure/(gasConstant*temperature);
            c2=-(1-reducedB);
            c1=reducedA-3*reducedB*reducedB-2*reducedB;
            c0=-(reducedA*reducedB-reducedB*reducedB-reducedB*reducedB*reducedB);
        }
        /** The compressibility a phase reports, recovered through the constant translation it carries. */
        double compressibility(TranslatedPengRobinson.Phase phase) {
            return (phase.molarVolume()-shift)/reducedTemperature;
        }
        double residual(double z){return Math.fma(Math.fma(z+c2,z,c1),z,c0);}
        boolean backwardAccurate(double z) {
            double scale=Math.abs(z*z*z)+Math.abs(c2*z*z)+Math.abs(c1*z)+Math.abs(c0);
            return Math.abs(residual(z))<=8*Math.ulp(scale);
        }
    }

    private static double relative(double expected,double actual) {
        double denominator=Math.max(Math.abs(expected),Math.abs(actual));
        return denominator==0?0:Math.abs(expected-actual)/denominator;
    }

    private static double percentile(List<Double> sorted,double fraction) {
        if(sorted.isEmpty())return 0;
        return sorted.get(Math.min(sorted.size()-1,(int)(fraction*sorted.size())));
    }

    private static void record(Map<String,List<Sample>> samples,String field,String at,
                               TranslatedPengRobinson.Phase oracle,TranslatedPengRobinson.Phase kernel,boolean resolved,
                               java.util.function.ToDoubleFunction<TranslatedPengRobinson.Phase> of) {
        double expected=of.applyAsDouble(oracle),actual=of.applyAsDouble(kernel);
        assertTrue(Double.isFinite(expected)&&Double.isFinite(actual),field+" must be finite at "+at);
        samples.computeIfAbsent(field,ignored->new ArrayList<>()).add(new Sample(at,expected,actual,resolved));
    }

    private record Sample(String at,double expected,double actual,boolean resolved) {}
    private record Statistic(double resolvedMaximum,double ninetyNinth,double median,double repairedMaximum,
                             String at,double expected,double actual,int count) {
        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "resolved max %.3e p99 %.3e p50 %.3e over %d | repaired-root max %.3e | worst resolved at %s (legacy %.17g, kernel %.17g)",
                    resolvedMaximum,ninetyNinth,median,count,repairedMaximum,at,expected,actual);
        }
    }
    private record Result(String label,int resolved,int repaired,int disagreements,int rootRegressions,
                          double worstRepaired,String worstRepairedAt,Map<String,Statistic> fields) {
        @Override public String toString() {
            var text=new StringBuilder("TranslatedPengRobinson vs legacy oracle, ").append(label)
                    .append(": ").append(resolved).append(" evaluations where the legacy root is backward accurate, ")
                    .append(repaired).append(" where the kernel repaired it (").append(rootRegressions)
                    .append(" regressions), ").append(disagreements).append(" domain disagreements")
                    .append(String.format(Locale.ROOT,"%n    worst repaired-root volume deviation %.3e at %s",
                            worstRepaired,worstRepairedAt));
            fields.forEach((field,value)->text.append("\n    ").append(String.format("%-26s",field)).append(value));
            return text.toString();
        }
    }
}
