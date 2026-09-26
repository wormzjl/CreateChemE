const fs=require('fs');const f='src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java';
let s=fs.readFileSync(f,'utf8');const crlf=s.includes('\r\n');s=s.replace(/\r\n/g,'\n');
function rep(a,b){if(!s.includes(a))throw new Error('missing: '+a.slice(0,100));s=s.replace(a,b);}
rep(`    private static Fixture fixture;
    private static Run run;`,`    private static Fixture fixture;
    /** The world's saved data as installed with the fixture: the benchmark times its saves (plan section 6, save time). */
    private static FluidSavedData savedData;
    private static Run run;`);
rep(`        long tickStarted,previousTickStarted,nextTick,lastMeter,measuredStarted;double externalEnergy,pumpWork;boolean finished;`,
`        long tickStarted,previousTickStarted,nextTick,lastMeter,measuredStarted;double externalEnergy,pumpWork;boolean finished;
        /** After the window: the report waiting for the save one cadence after the window's end, which the paced ticks
         * in between reach; the saves measured so far; the tick of that last save. */
        Map<String,Object> report;boolean pass,probing,probed;long probeTick;final Map<String,Object> saves=new LinkedHashMap<>();`);
rep(`        level.getDataStorage().set(FluidSavedData.DATA_NAME,new FluidSavedData(checkpoint,topology,key->model));`,
`        savedData=new FluidSavedData(checkpoint,topology,key->model);level.getDataStorage().set(FluidSavedData.DATA_NAME,savedData);`);
rep(`        helper.startSequence().thenWaitUntil(()->{
            helper.assertTrue(run.enoughSamples(),"Collecting paced intervals: "+run.samples.size()+" (target "+run.targetIntervals+" per island)");
        }).thenExecute(()->finish(run)).thenSucceed();`,
`        helper.startSequence().thenWaitUntil(()->{
            helper.assertTrue(run.enoughSamples(),"Collecting paced intervals: "+run.samples.size()+" (target "+run.targetIntervals+" per island)");
        }).thenExecute(()->finish(run)).thenWaitUntil(()->helper.assertTrue(run.probed,"Waiting one cadence for the next save"))
        .thenExecute(()->complete(run)).thenSucceed();`);
rep(`    public static void afterTick(ServerTickEvent.Post event) {
        if(run==null||run.finished)return;run.referenceTick();long now=System.nanoTime(),meter=FluidRuntimeMeter.totalNanos(event.getServer());`,
`    public static void afterTick(ServerTickEvent.Post event) {
        if(run==null)return;
        if(run.finished) {
            // After the window, still paced: one cadence of ordinary running, then the next save.
            if(!run.probing)return;
            if(run.world.onlineTick()>=run.probeTick){run.probing=false;run.saves.put("nextCadence",save(run,false));run.probed=true;return;}
            pace(run,event.getServer());return;
        }
        run.referenceTick();long now=System.nanoTime(),meter=FluidRuntimeMeter.totalNanos(event.getServer());`);
rep(`        run.lastMeter=meter;
        // GameTestServer normally ticks unpaced. Service its real server mailbox while waiting;
        // short solver completions can therefore refill workers between 20-TPS ticks.
        if(run.nextTick==0||now>run.nextTick+TICK_NANOS)run.nextTick=now+TICK_NANOS;else run.nextTick+=TICK_NANOS;
        long deadline=run.nextTick;event.getServer().managedBlock(()->System.nanoTime()>=deadline);
    }`,
`        run.lastMeter=meter;
        pace(run,event.getServer());
    }
    /** GameTestServer normally ticks unpaced. Service its real server mailbox while waiting;
     * short solver completions can therefore refill workers between 20-TPS ticks. */
    private static void pace(Run r,MinecraftServer server) {
        long now=System.nanoTime();
        if(r.nextTick==0||now>r.nextTick+TICK_NANOS)r.nextTick=now+TICK_NANOS;else r.nextTick+=TICK_NANOS;
        long deadline=r.nextTick;server.managedBlock(()->System.nanoTime()>=deadline);
    }
    /**
     * One save of the whole fixture through the world's saved data, as an autosave makes it: the capture (which
     * materialises certified islands) and the encoding, timed on the server thread, with the bytes each part wrote
     * and the size the tag compresses to on disk (the compression is not timed: it is the file writer's).
     * {@code cold} forgets the cached payloads first.
     */
    private static Map<String,Object> save(Run r,boolean cold) {
        if(cold)savedData.clearPayloadCache();
        var tag=savedData.save(new net.minecraft.nbt.CompoundTag(),r.server.registryAccess());var timing=savedData.lastSave();
        var result=new LinkedHashMap<String,Object>();
        result.put("onlineTick",r.world.onlineTick());result.put("cacheClearedFirst",cold);
        result.put("totalMilliseconds",timing.totalNanos()/1e6);result.put("captureMilliseconds",timing.captureNanos()/1e6);result.put("encodeMilliseconds",timing.encodeNanos()/1e6);
        result.put("islands",timing.islands());result.put("payloadsEncoded",timing.payloadsEncoded());result.put("payloadsReused",timing.payloadsReused());
        result.put("payloadBytes",timing.payloadBytes());result.put("ledgerBytes",timing.ledgerBytes());result.put("topologyBytes",timing.topologyBytes());result.put("bytes",timing.bytes());
        try{var out=new java.io.ByteArrayOutputStream();var root=new net.minecraft.nbt.CompoundTag();root.put("data",tag);net.minecraft.nbt.NbtIo.writeCompressed(root,out);result.put("compressedBytes",out.size());}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        return result;
    }`);
rep(`        try {var path=Path.of(System.getProperty("createcheme.fluid.benchmark.output"));Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if(recording!=null){recording.stop();recording.dump(path.resolveSibling("stress.jfr"));recording.close();recording=null;}}
        catch(java.io.IOException failure){throw new IllegalStateException("Could not write benchmark evidence",failure);}
        for(long chunk:fixture.chunks){var p=new ChunkPos(chunk);r.server.overworld().setChunkForced(p.x,p.z,false);}FluidRuntimeMeter.forget(r.server);
        r.helper.assertTrue(pass,"Paced benchmark failed; inspect the complete raw report");
    }`,
`        // Save time (plan section 6): the whole fixture at the end of the window, once with the payload cache cleared
        // and once right after (a second consecutive save), then once more one cadence later, after the paced ticks
        // in between (where awake islands have solved and certified ones have only been materialised).
        r.saves.put("cold",save(r,true));r.saves.put("warm",save(r,false));
        r.report=report;r.pass=pass;r.probeTick=r.world.onlineTick()+100;r.probing=true;
    }
    /** Writes the report once the post-window save was taken, and releases the fixture. */
    private static void complete(Run r) {
        var saveTime=new LinkedHashMap<String,Object>();
        saveTime.put("note","Checkpoint format 3 saves of the whole fixture through the world's saved data, timed on the server thread: capture (materialises certified islands) plus encoding. cold: payload cache cleared first, at the end of the window; warm: the second consecutive save, same tick; nextCadence: one save 100 paced ticks later, after awake islands solved again. compressedBytes is the gzip NBT size a file write would produce, not timed.");
        saveTime.putAll(r.saves);r.report.put("saveTime",saveTime);
        try {var path=Path.of(System.getProperty("createcheme.fluid.benchmark.output"));Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(r.report));
            if(recording!=null){recording.stop();recording.dump(path.resolveSibling("stress.jfr"));recording.close();recording=null;}}
        catch(java.io.IOException failure){throw new IllegalStateException("Could not write benchmark evidence",failure);}
        for(long chunk:fixture.chunks){var p=new ChunkPos(chunk);r.server.overworld().setChunkForced(p.x,p.z,false);}FluidRuntimeMeter.forget(r.server);
        r.helper.assertTrue(r.pass,"Paced benchmark failed; inspect the complete raw report");
    }`);
if(crlf)s=s.replace(/\n/g,'\r\n');fs.writeFileSync(f,s);console.log('edited');
