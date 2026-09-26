package com.wormzjl.createcheme.study;

import com.wormzjl.createcheme.science.fluid.network.PipeHeatExchange;
import com.wormzjl.createcheme.science.fluid.network.PipeResistance;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;

/** Offline, bounded steady-line surrogate. Never imported by the product or a gate test. */
public final class PipeThermalStudy {
    static final double D = .05, ROUGHNESS = .000045;
    record Spec(String name, int component, double in, double ambient, double pressure) {}
    record Point(double t, double h, double volume, double muVolume, double vapor) {}
    record Sol(double mass, double heat, double outT, int evaluations) {}
    record Group(List<String> rows, String profile, List<String> checks) {}
    static final List<Spec> SPECS = List.of(
        new Spec("water-cooling", -1, 360, 300, 300000),
        new Spec("water-heating", -1, 300, 360, 300000),
        new Spec("steam-condensing", -1, 450, 300, 100000),
        new Spec("steam-sensible", -1, 500, 400, 100000),
        new Spec("methane-cooling", 0, 500, 300, 300000),
        new Spec("methane-heating", 0, 300, 500, 300000),
        new Spec("pentane-liquid", 6, 340, 300, 1000000),
        new Spec("pentane-condensing", 6, 420, 300, 200000),
        new Spec("crude-cooling", -2, 500, 300, 200000),
        new Spec("heavy-liquid", 12, 600, 350, 300000));
    static final String HEADER = "fluid,L_m,v0_m_s,U_W_m2K,status,dp_Pa,dp_fraction,q0,qref,q1,q2,heat_ref,cp0_error,cp1_error,table_error,frozen_error,one_error,two_error,gated_error,flow_one_error,outlet_ref_K,vapor_in,vapor_out,one_self_mismatch,refinement_error,energy_residual,one_cpu_us,reference_cpu_us,reference_evals,detail";
    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    static long cpu() { return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(); }
    static double relative(double value, double expected) { return Math.abs(value - expected) / Math.max(1e-6, Math.abs(expected)); }
    static String line(Object... values) {
        var result = new StringJoiner(",");
        for (var value : values) result.add(String.valueOf(value).replace(',', ';').replace('\n', ' '));
        return result.toString();
    }

    static final class Sampler {
        final Spec spec;
        final FluidThermodynamics model;
        final double[] composition;
        final Map<Double,Point> cache = new HashMap<>();
        Sampler(Spec spec) {
            this.spec = spec;
            model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:tjl20_methane", 1e-9);
            composition = new double[model.componentCount()];
            if (spec.component == -1) composition[composition.length - 1] = 1;
            else if (spec.component == -2) {
                var crude = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane")
                        .crudeFeed("createcheme:tia_juana_light_methane").moleFractions();
                System.arraycopy(crude, 0, composition, 0, crude.length);
            } else composition[spec.component] = 1;
        }
        Point at(double t) {
            return cache.computeIfAbsent(t, ignored -> {
                checkpoint();
                var s = model.flashTP(t, spec.pressure, composition, PipeThermalStudy::checkpoint);
                double viscosity = 0;
                if (s.liquidVolume() > 0) viscosity += s.liquidVolume() * model.viscosity.liquid(t, s.liquid()).pascalSeconds();
                if (s.waterVolume() > 0) viscosity += s.waterVolume() * model.viscosity.waterLiquid(t);
                if (s.vaporVolume() > 0) viscosity += s.vaporVolume() * model.viscosity.vapor(t, s.vapor(), s.waterVapor());
                double vapor = s.waterVapor() * model.waterMolecularWeight;
                var n = s.vapor();
                for (int i = 0; i < n.length; i++) vapor += n[i] * model.molecularWeight(i);
                return new Point(t, s.enthalpy() / s.mass(), s.volume() / s.mass(), viscosity / s.mass(), vapor / s.mass());
            });
        }
        Table build(int divisions, boolean adaptive) {
            var points = new TreeMap<Double,Point>();
            double lo = Math.min(spec.in, spec.ambient), hi = Math.max(spec.in, spec.ambient);
            for (int i = 0; i <= divisions; i++) { double t = lo + (hi - lo) * i / divisions; points.put(t, at(t)); }
            var initial = new ArrayList<>(points.values());
            for (int i = 0; i < initial.size() - 1; i++) refine(initial.get(i), initial.get(i + 1), points, adaptive, 0);
            return new Table(new ArrayList<>(points.values()));
        }
        void refine(Point a, Point b, TreeMap<Double,Point> points, boolean adaptive, int depth) {
            if (depth >= 24 || b.t - a.t < 1e-5) return;
            boolean phase = Math.abs(a.vapor - b.vapor) > .1;
            if (!adaptive && !phase) return;
            Point mid = at((a.t + b.t) / 2);
            double interpolationT = a.t + (b.t - a.t) * (mid.h - a.h) / (b.h - a.h);
            if (!phase && Math.abs(interpolationT - mid.t) <= .20) return;
            if (points.size() >= 4096) throw new IllegalStateException("Profile refinement cap");
            points.put(mid.t, mid);
            refine(a, mid, points, adaptive, depth + 1);
            refine(mid, b, points, adaptive, depth + 1);
        }
    }
    static final class Table {
        final Point[] points;
        final double[] h;
        final PipeHeatExchange curve;
        Table(List<Point> source) {
            points = source.toArray(Point[]::new);
            h = new double[points.length]; double[] t = new double[h.length];
            for (int i = 0; i < h.length; i++) { h[i] = points[i].h; t[i] = points[i].t; }
            curve = new PipeHeatExchange(h, t);
        }
        Point at(double value) {
            value = Math.clamp(value, h[0], h[h.length - 1]);
            int found = Arrays.binarySearch(h, value), i = Math.min(h.length - 2, found >= 0 ? found : -found - 2);
            double f = (value - h[i]) / (h[i + 1] - h[i]);
            var a = points[i]; var b = points[i + 1];
            return new Point(a.t + f * (b.t - a.t), value, a.volume + f * (b.volume - a.volume),
                    a.muVolume + f * (b.muVolume - a.muVolume), a.vapor + f * (b.vapor - a.vapor));
        }
    }
    static Point[] profile(Table table, double inlet, double q, double ua, double ambient, int sections) {
        var result = new Point[sections];
        for (int i = 0; i < sections; i++) result[i] = table.at(table.curve.exchange(inlet, q, ua * (i + .5) / sections, ambient).outletEnthalpy());
        return result;
    }
    static double drop(Point[] profile, double q, double length) {
        var section = new PipeResistance.Geometry(length / profile.length, D, ROUGHNESS, 0);
        double dp = 0;
        for (var point : profile) dp += PipeResistance.pressureDrop(section, q, 1 / point.volume, point.muVolume / point.volume);
        return dp;
    }
    static double root(java.util.function.DoubleUnaryOperator residual, double initial) {
        double low = initial * 1e-5, high = initial * 4;
        for (int i = 0; i < 20 && residual.applyAsDouble(high) < 0; i++) high *= 2;
        if (residual.applyAsDouble(low) > 0 || residual.applyAsDouble(high) < 0) throw new IllegalStateException("Flow root not bracketed");
        for (int i = 0; i < 40; i++) {
            checkpoint(); double mid = (low + high) / 2;
            if (residual.applyAsDouble(mid) > 0) high = mid; else low = mid;
        }
        return (low + high) / 2;
    }
    static Sol reference(Table table, double inlet, double initial, double ua, double ambient, double length, double dp, int sections) {
        int[] evaluations = {0};
        double q = root(x -> { evaluations[0]++; return drop(profile(table, inlet, x, ua, ambient, sections), x, length) - dp; }, initial);
        var heat = table.curve.exchange(inlet, q, ua, ambient);
        return new Sol(q, heat.heatLossWatts(), heat.outletTemperature(), evaluations[0]);
    }
    static double cpHeat(double q, double ua, Spec spec, double cp) {
        return q * cp * (spec.in - spec.ambient) * -Math.expm1(-ua / (q * cp));
    }
    static Group group(Spec spec, boolean pilot) {
        var rows = new ArrayList<String>(); var checks = new ArrayList<String>();
        try {
            long build = cpu(); var sampler = new Sampler(spec);
            var sparse = sampler.build(8, true); long sparseTime = cpu() - build;
            var dense = sampler.build(512, false); var refined = sampler.build(1024, false);
            var inlet = sampler.at(spec.in);
            double cp = (sampler.at(spec.in + (spec.ambient > spec.in ? .01 : -.01)).h - inlet.h)
                    / (spec.ambient > spec.in ? .01 : -.01);
            int id = 0;
            for (double length : pilot ? new double[]{10, 100} : new double[]{1, 10, 50, 150})
            for (double velocity : pilot ? new double[]{.02, 1} : new double[]{.002, .02, .2, 1, 3})
            for (double u : pilot ? new double[]{15} : new double[]{2, 15, 60}) {
                id++; double ua = u * Math.PI * D * length, q0 = velocity * Math.PI * D * D / 4 / inlet.volume;
                double dp = drop(new Point[]{inlet}, q0, length);
                if (dp / spec.pressure > .05) {
                    rows.add(line(spec.name,length,velocity,u,"SCREENED_DP",dp,dp/spec.pressure)); continue;
                }
                try {
                    long start = cpu();
                    var frozen = profile(sparse, inlet.h, q0, ua, spec.ambient, 8);
                    double q1 = root(x -> drop(frozen, x, length) - dp, q0);
                    var first = sparse.curve.exchange(inlet.h, q1, ua, spec.ambient);
                    long oneTime = cpu() - start;
                    var secondProfile = profile(sparse, inlet.h, q1, ua, spec.ambient, 8);
                    double q2 = root(x -> drop(secondProfile, x, length) - dp, q1);
                    double heat2 = sparse.curve.exchange(inlet.h, q2, ua, spec.ambient).heatLossWatts();
                    start = cpu(); var ref = reference(dense, inlet.h, q0, ua, spec.ambient, length, dp, 64); long refTime = cpu() - start;
                    double refError = Double.NaN;
                    if (id % 9 == 1 || pilot) {
                        var fine = reference(refined, inlet.h, q0, ua, spec.ambient, length, dp, 128);
                        refError = relative(ref.heat, fine.heat);
                        checks.add(line(spec.name,length,velocity,u,"refinement",refError,relative(ref.mass,fine.mass)));
                    }
                    double cp0 = cpHeat(ref.mass, ua, spec, cp);
                    double estimatedT = spec.in - cp0 / (ref.mass * cp);
                    double cp1 = Math.abs(spec.in - estimatedT) < 1e-8 ? cp :
                            (inlet.h - sampler.at(estimatedT).h) / (spec.in - estimatedT);
                    double cpCorrected = cpHeat(ref.mass, ua, spec, cp1);
                    double tableHeat = sparse.curve.exchange(inlet.h, ref.mass, ua, spec.ambient).heatLossWatts();
                    double heat0 = sparse.curve.exchange(inlet.h, q0, ua, spec.ambient).heatLossWatts();
                    double self = relative(heat0,first.heatLossWatts());
                    double gated = self > .1 ? first.heatLossWatts() : heat0;
                    double energyResidual = Math.abs(q1 * inlet.h - q1 * first.outletEnthalpy() - first.heatLossWatts()) / Math.max(1,Math.abs(first.heatLossWatts()));
                    var outlet = dense.at(dense.curve.exchange(inlet.h,ref.mass,ua,spec.ambient).outletEnthalpy());
                    rows.add(line(spec.name,length,velocity,u,"OK",dp,dp/spec.pressure,q0,ref.mass,q1,q2,ref.heat,
                            relative(cp0,ref.heat),relative(cpCorrected,ref.heat),relative(tableHeat,ref.heat),relative(heat0,ref.heat),
                            relative(first.heatLossWatts(),ref.heat),relative(heat2,ref.heat),relative(gated,ref.heat),relative(q1,ref.mass),
                            ref.outT,inlet.vapor,outlet.vapor,self,refError,energyResidual,oneTime/1000.0,refTime/1000.0,ref.evaluations,""));
                } catch (RuntimeException failure) {
                    rows.add(line(spec.name,length,velocity,u,"FAILED",dp,dp/spec.pressure,"","","","","","","","","","","","","","","","","","","","","","",failure));
                }
            }
            // Independent RK4 oracle at prescribed flow; phase-aware and bounded, with step refinement.
            double q = .1, ua = 30, h0 = inlet.h;
            double expected = dense.curve.exchange(h0,q,ua,spec.ambient).heatLossWatts();
            double rk1 = rk(dense,h0,q,ua,spec.ambient,8192), rk2 = rk(dense,h0,q,ua,spec.ambient,16384);
            checks.add(line(spec.name,"","","","rk4",relative(q*(h0-rk2),expected),relative(q*(h0-rk1),q*(h0-rk2))));
            return new Group(rows,line(spec.name,sparse.h.length,dense.h.length,refined.h.length,sparseTime/1e6,sampler.cache.size(),"OK"),checks);
        } catch (RuntimeException failure) {
            rows.add(line(spec.name,"","","","PROFILE_FAILED","","","","","","","","","","","","","","","","","","","","","","","","",failure));
            return new Group(rows,line(spec.name,0,0,0,0,0,failure),checks);
        }
    }
    static double rk(Table table, double h, double q, double ua, double ambient, int steps) {
        double dx = ua/steps;
        for (int i=0; i<steps; i++) {
            double k1 = -(table.at(h).t-ambient)/q;
            double k2 = -(table.at(h+dx*k1/2).t-ambient)/q;
            double k3 = -(table.at(h+dx*k2/2).t-ambient)/q;
            double k4 = -(table.at(h+dx*k3).t-ambient)/q;
            h += dx*(k1+2*k2+2*k3+k4)/6;
        }
        return h;
    }
    static volatile double timingSink;
    static List<String> timings() {
        var result = new ArrayList<String>(); result.add("fluid,replicate,curve_build_ms,exchange_us,one_correction_us,reference_us");
        for (int index : new int[]{2,4,9}) {
            Spec spec = SPECS.get(index); var sampler = new Sampler(spec); var table = sampler.build(8,true);
            var dense = sampler.build(512,false); var inlet = sampler.at(spec.in);
            double q0 = .02 * Math.PI*D*D/4 / inlet.volume, ua = 15*Math.PI*D*100;
            double dp = drop(new Point[]{inlet},q0,100);
            Runnable thermal = () -> timingSink = table.curve.exchange(inlet.h,q0,ua,spec.ambient).heatLossWatts();
            Runnable one = () -> {
                var p = profile(table,inlet.h,q0,ua,spec.ambient,8);
                double q = root(x -> drop(p,x,100)-dp,q0);
                timingSink = table.curve.exchange(inlet.h,q,ua,spec.ambient).heatLossWatts();
            };
            Runnable ref = () -> timingSink = reference(dense,inlet.h,q0,ua,spec.ambient,100,dp,64).heat;
            for(int i=0;i<1000;i++) { thermal.run(); one.run(); }
            for(int i=0;i<20;i++) ref.run();
            for(int repeat=0;repeat<5;repeat++) {
                long start=System.nanoTime(); new Sampler(spec).build(8,true); double build=(System.nanoTime()-start)/1e6;
                result.add(line(spec.name,repeat,build,time(thermal,10000),time(one,1000),time(ref,100)));
            }
        }
        return result;
    }
    static double time(Runnable action,int repetitions) {
        long start=System.nanoTime(); for(int i=0;i<repetitions;i++) action.run();
        return (System.nanoTime()-start)/1000.0/repetitions;
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out); boolean pilot = args.length > 1 && args[1].equals("pilot");
        var tasks = new ArrayList<Callable<Group>>();
        for (var spec : SPECS) tasks.add(() -> group(spec,pilot));
        var rows = new ArrayList<String>(); rows.add(HEADER);
        var profiles = new ArrayList<String>(); profiles.add("fluid,sparse_knots,dense_knots,refined_knots,sparse_build_cpu_ms,total_unique_flashes,status");
        var checks = new ArrayList<String>(); checks.add("fluid,L_m,v0_m_s,U_W_m2K,check,error,refinement");
        long start = System.nanoTime();
        try (var pool = new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(SPECS.size()),
                Thread.ofPlatform().name("pipe-thermal-",0).factory(),new ThreadPoolExecutor.AbortPolicy())) {
            for (var future : pool.invokeAll(tasks,10,TimeUnit.MINUTES)) {
                if (future.isCancelled()) throw new IllegalStateException("Study deadline exceeded");
                var result = future.get(); rows.addAll(result.rows); profiles.add(result.profile); checks.addAll(result.checks);
                System.out.println(result.profile);
            }
        }
        Files.write(out.resolve("cases.csv"),rows); Files.write(out.resolve("profiles.csv"),profiles); Files.write(out.resolve("checks.csv"),checks);
        if (!pilot) Files.write(out.resolve("timings.csv"),timings());
        Files.writeString(out.resolve("run.txt"),"java="+System.getProperty("java.version")+"\nworkers=8\npilot="+pilot+"\nwallSeconds="+(System.nanoTime()-start)/1e9+"\nbase=f9d6be1\nfixed thermo pressure; homogeneous Darcy model; no simulation runtime changes\n");
        System.out.println("Wrote "+(rows.size()-1)+" rows to "+out);
    }
}

