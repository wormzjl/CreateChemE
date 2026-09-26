package com.wormzjl.createcheme.runtime.fluid;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.*;

/**
 * Opt-in in-game measurement log for the fluid scheduling benchmarks (plan section 6, WP5 in-game rig).
 *
 * <p>Off unless the system property {@value #PROPERTY} is a positive number of server ticks; the client and server
 * runs take it from {@code -PfluidRigDiagnosticTicks}. When off, nothing is registered and nothing runs. When on, it enables
 * {@link FluidRuntimeDiagnostics} and {@link FluidRuntimeMeter} and, from its own server-tick listeners, logs one
 * {@code fluid_diag} line per period with the island count by certificate kind and status, the scheduling counters
 * accumulated over the period, the shared worker allocation, and the per-tick engine and whole-tick time. It also logs
 * every gap of more than a quarter second between two server ticks, which is where commands run (a datapack function
 * that places a scenario's blocks). It reads stored island state only ({@link FluidWorldAuthority#diagnosticSnapshots()}),
 * never materialises or wakes anything, pauses the counters around its own reads, and decides nothing. Every tick's
 * wall time, whole-tick time and engine time also go to {@code fluid_diag_ticks.csv} in the game directory, flushed
 * once per period, so a harness can cut its measurement window out of them.
 */
public final class FluidInGameDiagnostics {
    public static final String PROPERTY="createcheme.fluid.diagnostics.logTicks";
    private static final int PERIOD=Math.max(0,Integer.getInteger(PROPERTY,0));
    private static final long GAP_NANOS=250_000_000L;
    private static final Logger LOGGER=LogUtils.getLogger();
    private static long tickStarted,lastTickEnded,lastEngineNanos,startedNanos;
    private static boolean metering;
    private static final List<Double> engineMillis=new ArrayList<>(),tickMillis=new ArrayList<>();
    private static Map<String,Long> lastCounters=Map.of();
    private static java.io.BufferedWriter ticks;

    private FluidInGameDiagnostics() {}

    public static boolean enabled(){return PERIOD>0;}

    /** Registers the listeners only when {@link #enabled()}. */
    public static void register(IEventBus bus) {
        if(!enabled())return;
        FluidRuntimeDiagnostics.ENABLED=true;
        bus.addListener(FluidInGameDiagnostics::started);
        bus.addListener(FluidInGameDiagnostics::stopping);
        bus.addListener(EventPriority.HIGHEST,FluidInGameDiagnostics::pre);
        bus.addListener(EventPriority.LOWEST,FluidInGameDiagnostics::post);
        LOGGER.info("fluid_diag enabled period_ticks={}",PERIOD);
    }

    private static void started(ServerStartedEvent event) {
        var server=event.getServer();
        FluidRuntimeMeter.enable(server);metering=true;lastEngineNanos=0;startedNanos=System.nanoTime();lastTickEnded=0;
        engineMillis.clear();tickMillis.clear();lastCounters=FluidRuntimeDiagnostics.sample();
        try {
            if(ticks!=null)ticks.close();
            ticks=java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of("fluid_diag_ticks.csv"));ticks.write("epoch_ms,online_tick,tick_ms,engine_ms\n");
        } catch(java.io.IOException exception){LOGGER.warn("fluid_diag cannot write fluid_diag_ticks.csv",exception);ticks=null;}
    }

    private static void stopping(ServerStoppingEvent event) {
        metering=false;
        if(ticks!=null)try{ticks.close();}catch(java.io.IOException exception){LOGGER.warn("fluid_diag cannot close fluid_diag_ticks.csv",exception);}
        ticks=null;
    }

    private static void pre(ServerTickEvent.Pre event) {
        long now=System.nanoTime();
        if(metering&&lastTickEnded!=0&&now-lastTickEnded>GAP_NANOS)
            FluidWorldAuthority.find(event.getServer()).ifPresent(world->LOGGER.info("fluid_diag gap_ms={} before_online_tick={} devices={}",
                    String.format(Locale.ROOT,"%.1f",(now-lastTickEnded)/1e6),world.onlineTick(),world.registrations().size()));
        tickStarted=now;
    }

    private static void post(ServerTickEvent.Post event) {
        if(!metering)return;
        MinecraftServer server=event.getServer();
        long engine=FluidRuntimeMeter.totalNanos(server);
        engineMillis.add((engine-lastEngineNanos)/1e6);lastEngineNanos=engine;
        long now=System.nanoTime();double tick=(now-tickStarted)/1e6;tickMillis.add(tick);lastTickEnded=now;
        var found=FluidWorldAuthority.find(server);long online=found.map(FluidWorldAuthority::onlineTick).orElse(-1L);
        if(ticks!=null)try{ticks.write(System.currentTimeMillis()+","+online+","+String.format(Locale.ROOT,"%.4f,%.4f",tick,engineMillis.getLast())+"\n");}catch(java.io.IOException exception){ticks=null;}
        if(found.isEmpty()||online%PERIOD!=0)return;var world=found.get();
        if(ticks!=null)try{ticks.flush();}catch(java.io.IOException exception){ticks=null;}
        FluidRuntimeDiagnostics.pause();
        try {
            var kinds=new TreeMap<String,Integer>();var statuses=new TreeMap<String,Integer>();
            var snapshots=world.diagnosticSnapshots();
            for(var s:snapshots) {
                kinds.merge(s.certificate().map(c->c.kind().name()).orElse("AWAKE"),1,Integer::sum);
                String status=s.status()==null?"":s.status();int colon=status.indexOf(':');
                statuses.merge((colon<0?status:status.substring(0,colon)).trim(),1,Integer::sum);
            }
            var counters=FluidRuntimeDiagnostics.sample();var deltas=new LinkedHashMap<String,Long>();
            counters.forEach((name,value)->{long delta=value-lastCounters.getOrDefault(name,0L);if(delta!=0)deltas.put(name,delta);});
            lastCounters=counters;
            var workers=com.wormzjl.createcheme.runtime.ProcessSolveServices.diagnostics(server);
            LOGGER.info("fluid_diag online_tick={} wall_s={} devices={} islands={} kinds={} statuses={} workers_active={} workers_limit={} ticks={} engine_ms_p50={} engine_ms_p95={} engine_ms_max={} tick_ms_p50={} tick_ms_p95={} tick_ms_max={} counters={}",
                    online,String.format(Locale.ROOT,"%.1f",(now-startedNanos)/1e9),world.registrations().size(),snapshots.size(),kinds,statuses,
                    workers.activeWorkers(),workers.workerCount(),tickMillis.size(),
                    quantile(engineMillis,.5),quantile(engineMillis,.95),quantile(engineMillis,1),quantile(tickMillis,.5),quantile(tickMillis,.95),quantile(tickMillis,1),deltas);
        } finally {
            FluidRuntimeDiagnostics.resume();
            engineMillis.clear();tickMillis.clear();
        }
    }

    private static String quantile(List<Double> values,double q) {
        if(values.isEmpty())return "NaN";
        var sorted=new ArrayList<>(values);Collections.sort(sorted);
        int index=(int)Math.min(sorted.size()-1,Math.max(0,Math.ceil(q*sorted.size())-1));
        return String.format(Locale.ROOT,"%.4f",sorted.get(index));
    }
}
