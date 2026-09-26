package com.wormzjl.createcheme.study;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.wormzjl.createcheme.study.PipeThermalStudy.*;

/** Follow-up: fixed 9/17-knot curves, independent phase-crossing RK, and worst-case reference refinement. */
public final class PipeThermalFollowup {
    record Output(List<String> cases,List<String> checks) {}
    static Table plain(Sampler sampler,int divisions) {
        var points=new ArrayList<Point>(); var spec=sampler.spec;
        double low=Math.min(spec.in(),spec.ambient()), high=Math.max(spec.in(),spec.ambient());
        for(int i=0;i<=divisions;i++) points.add(sampler.at(low+(high-low)*i/divisions));
        return new Table(points);
    }
    static Output run(Spec spec,List<String[]> rows) {
        var cases=new ArrayList<String>(); var checks=new ArrayList<String>();
        var sampler=new Sampler(spec); var nine=plain(sampler,8); var seventeen=plain(sampler,16);
        var dense=sampler.build(1024,false); var inlet=sampler.at(spec.in());
        for(var row:rows) {
            double length=Double.parseDouble(row[1]),velocity=Double.parseDouble(row[2]),u=Double.parseDouble(row[3]);
            double flow=Double.parseDouble(row[8]),refHeat=Double.parseDouble(row[11]),ua=u*Math.PI*D*length;
            double q9=nine.curve.exchange(inlet.h(),flow,ua,spec.ambient()).heatLossWatts();
            double q17=seventeen.curve.exchange(inlet.h(),flow,ua,spec.ambient()).heatLossWatts();
            cases.add(line(spec.name(),length,velocity,u,relative(q9,refHeat),relative(q17,refHeat)));
        }
        var worst=new ArrayList<>(rows);
        worst.sort(Comparator.comparingDouble((String[] row)->Double.parseDouble(row[16])).reversed());
        for(var row:worst.subList(0,Math.min(2,worst.size()))) {
            double length=Double.parseDouble(row[1]),velocity=Double.parseDouble(row[2]),u=Double.parseDouble(row[3]);
            double q0=Double.parseDouble(row[7]),dp=Double.parseDouble(row[5]),ua=u*Math.PI*D*length;
            double originalHeat=Double.parseDouble(row[11]);
            var ref512=reference(dense,inlet.h(),q0,ua,spec.ambient(),length,dp,512);
            var ref1024=reference(dense,inlet.h(),q0,ua,spec.ambient(),length,dp,1024);
            checks.add(line(spec.name(),length,velocity,u,"worst-reference",relative(originalHeat,ref1024.heat()),
                    relative(ref512.heat(),ref1024.heat()),""));
        }
        for(double ua:new double[]{30,300}) {
            double flow=.1;
            var exact=dense.curve.exchange(inlet.h(),flow,ua,spec.ambient());
            double r1=rk(dense,inlet.h(),flow,ua,spec.ambient(),16384);
            double r2=rk(dense,inlet.h(),flow,ua,spec.ambient(),32768);
            checks.add(line(spec.name(),"","",ua,"independent-rk",relative(flow*(inlet.h()-r2),exact.heatLossWatts()),
                    relative(flow*(inlet.h()-r1),flow*(inlet.h()-r2)),dense.at(exact.outletEnthalpy()).vapor()));
        }
        return new Output(cases,checks);
    }
    public static void main(String[] args)throws Exception {
        var all=Files.readAllLines(Path.of(args[0])).stream().skip(1).map(s->s.split(",",-1)).filter(r->r[4].equals("OK")).toList();
        var tasks=new ArrayList<Callable<Output>>();
        for(var spec:SPECS) {var rows=all.stream().filter(r->r[0].equals(spec.name())).toList();tasks.add(()->run(spec,rows));}
        var cases=new ArrayList<String>();cases.add("fluid,L_m,v0_m_s,U_W_m2K,nine_error,seventeen_error");
        var checks=new ArrayList<String>();checks.add("fluid,L_m,v0_m_s,U_or_UA,check,error,refinement,outlet_vapor_mass_fraction");
        try(var pool=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(SPECS.size()),
                Thread.ofPlatform().name("pipe-thermal-check-",0).factory(),new ThreadPoolExecutor.AbortPolicy())) {
            for(var future:pool.invokeAll(tasks,10,TimeUnit.MINUTES)) {
                if(future.isCancelled())throw new IllegalStateException("Follow-up deadline exceeded");
                var value=future.get();cases.addAll(value.cases);checks.addAll(value.checks);
            }
        }
        Path out=Path.of(args[1]);Files.createDirectories(out);
        Files.write(out.resolve("table-sizes.csv"),cases);Files.write(out.resolve("reference-checks.csv"),checks);
        System.out.println("Follow-up: "+(cases.size()-1)+" thermal cases, "+(checks.size()-1)+" reference checks");
    }
}
