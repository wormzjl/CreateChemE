package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidModuleGameTests {
    private FluidModuleGameTests() {}
    @GameTest(template="empty",timeoutTicks=200000,batch="fluid-modules")
    public static void sharedWorkersTransferThroughCausalModuleAndPersistAllMaterialOwners(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var nitrogen=model.initialNitrogenCharge(1,298.15,101325,()->{});var n=new double[22];System.arraycopy(nitrogen.vapor(),0,n,0,21);n[21]=100/model.waterMolecularWeight;
        var feed=model.flashTP(298.15,101325,n,()->{});var graphs=new ArrayList<PassiveNetwork>();var buffers=new HashMap<UUID,BufferedTransfers.Buffer>();var bindings=new ArrayList<CausalModuleCoordinator.Binding>();
        for(int i=1;i<=3;i++){var state=i==1?feed:nitrogen;graphs.add(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(i,0,state)),List.of()));buffers.put(id(i),new BufferedTransfers.Buffer(id(i),1000,state.mass(),Map.of()));bindings.add(new CausalModuleCoordinator.Binding(id(i),i,i));}
        double[] fractions=new double[22];Arrays.fill(fractions,.5);
        var definition=new FixedSplitModule.Definition(id(100),List.of(new FixedSplitModule.Feed(id(1),.2)),id(2),id(3),300,fractions);
        var host=new CausalModuleCoordinator(model,bindings,new BufferedTransfers.Snapshot(0,buffers,Map.of()),List.of(new FixedSplitModule.Snapshot(definition,0,0,false,null)));
        var runtime=new MinecraftFluidRuntime(server,changed->host.advance(),new IslandCoordinator.Settings(5_000_000_000L,4_000_000_000L,64,false),host::command,host::prepare);
        for(int i=1;i<=3;i++)runtime.register(helper.getLevel().dimension(),new IslandCoordinator.Snapshot(i,0,graphs.get(i-1),new IslandClock.Snapshot(900,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        host.attach(runtime.coordinator());double[] before=totals(runtime.coordinator().snapshots(),host.transfers(),host.snapshots());host.advance();runtime.coordinator().pump();
        helper.startSequence().thenWaitUntil(()->{
            for(var island:runtime.coordinator().snapshots())helper.assertTrue(island.clock().committedTick()==900,"Waiting for shared-worker module transfer: "+island.status());
            helper.assertTrue(host.snapshots().getFirst().committedTick()==900,"Module horizon did not reach 45 s");
        }).thenExecute(()->{try {
            helper.assertTrue(runtime.coordinator().snapshot(2).graph().reservoirs().getFirst().inventory().moles()[21]>0&&runtime.coordinator().snapshot(3).graph().reservoirs().getFirst().inventory().moles()[21]>0,"Products were not physically delivered");
            var entries=runtime.coordinator().snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld",FluidPresetCatalog.NETWORK_PACKAGE,1e-9,s)).toList();
            var checkpoint=new FluidCheckpointCodec.Checkpoint(entries,host.transfers(),host.snapshots(),host.bindings());
            var data=new FluidSavedData(checkpoint,key->model);var tag=data.save(new net.minecraft.nbt.CompoundTag(),helper.getLevel().registryAccess());
            var loaded=FluidSavedData.load(tag,key->model).checkpoint();var after=totals(loaded.islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList(),loaded.transfers(),loaded.modules());
            for(int c=0;c<22;c++)helper.assertTrue(Math.abs(before[c]-after[c])<=1e-9*Math.max(1,before[c]),"Module global component balance "+c);
            helper.assertTrue(Math.abs(before[22]-after[22])<=1e-6*Math.max(1,Math.abs(before[22])),"Module global energy balance");
            helper.assertTrue(new HashSet<>(loaded.moduleBindings()).equals(new HashSet<>(bindings))&&loaded.modules().getFirst().committedTick()==900,"Module bindings/clock were not saved");
            helper.assertTrue(loaded.transfers().pending().size()<=2&&loaded.transfers().planned().size()<=2,"Unbounded module ledgers");
        } finally {runtime.close();}}).thenSucceed();
    }
    private static UUID id(int n){return new UUID(19,n);}
    private static double[] totals(List<IslandCoordinator.Snapshot> islands,BufferedTransfers.Snapshot transfers,List<FixedSplitModule.Snapshot> modules) {
        double[] sum=new double[23];for(var island:islands)for(var node:island.graph().reservoirs()) {var n=node.inventory().moles();for(int c=0;c<22;c++)sum[c]+=n[c];sum[22]+=node.inventory().internalEnergy();}
        for(var pending:transfers.pending().values())add(sum,pending.remaining());for(var module:modules)if(module.cycle()!=null)for(var input:module.cycle().inputs().values())add(sum,input.owned());return sum;
    }
    private static void add(double[] sum,MaterialParcel parcel){var n=parcel.moles();for(int c=0;c<22;c++)sum[c]+=n[c];sum[22]+=parcel.energyJoule();}
}
