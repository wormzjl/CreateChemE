package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import java.util.*;
import org.junit.jupiter.api.Test;

class PendingTransfersTest {
    @Test void revisionOverflowRejectsTheWholeBatchBeforeConsumingAnyParcel() {
        var ledger=new PendingTransfers();var a=UUID.randomUUID();var b=UUID.randomUUID();var receiver=UUID.randomUUID();
        ledger.add(new PendingTransfers.Pending(a,UUID.randomUUID(),receiver,0,0,parcel()));
        ledger.add(new PendingTransfers.Pending(b,UUID.randomUUID(),receiver,0,Long.MAX_VALUE,parcel()));
        var before=ledger.snapshot();var proposals=List.of(ledger.propose(a,0,20),ledger.propose(b,Long.MAX_VALUE,20));
        assertThrows(ArithmeticException.class,()->ledger.commit(proposals));assertEquals(before,ledger.snapshot());
    }
    private MaterialParcel parcel(){return new MaterialParcel(new double[]{2000,2000},new double[]{.018,.032},123456,EnergyReference.sensible(List.of("Water","Oxygen")));}
    @Test void oneHundredKgCanArriveAsTwentyAndEightyWithoutLosingItsIdentityOrEnergy() {
        var ledger=new PendingTransfers();var id=UUID.randomUUID();var receiver=UUID.randomUUID();var original=parcel();
        ledger.add(new PendingTransfers.Pending(id,UUID.randomUUID(),receiver,100,0,original));
        var first=ledger.propose(id,0,20);assertEquals(100,ledger.snapshot().getFirst().remaining().massKg());
        ledger.commit(List.of(first));var rest=ledger.snapshot().getFirst();assertEquals(id,rest.id());assertEquals(1,rest.revision());assertEquals(80,rest.remaining().massKg(),1e-12);
        var second=ledger.propose(id,1,80);ledger.commit(List.of(second));assertTrue(ledger.snapshot().isEmpty());
        assertEquals(original.internalEnergy(),first.delivered().internalEnergy()+second.delivered().internalEnergy(),1e-10);
        assertArrayEquals(original.moles(),sum(first.delivered().moles(),second.delivered().moles()),1e-12);
    }
    @Test void zeroAcceptanceDoesNotConsumeMaterialAndStaleBatchesCommitNothing() {
        var ledger=new PendingTransfers();var a=UUID.randomUUID();var b=UUID.randomUUID();var receiver=UUID.randomUUID();
        ledger.add(new PendingTransfers.Pending(a,UUID.randomUUID(),receiver,100,0,parcel()));ledger.add(new PendingTransfers.Pending(b,UUID.randomUUID(),receiver,100,0,parcel()));
        ledger.commit(List.of(ledger.propose(a,0,0)));assertEquals(0,ledger.snapshot().getFirst().revision());
        var first=ledger.propose(a,0,20);var second=ledger.propose(b,0,20);ledger.retarget(b,0,UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->ledger.commit(List.of(first,second)));assertEquals(100,ledger.snapshot().getFirst().remaining().massKg());
        assertEquals(1,ledger.due(receiver,100).size());
    }
    @Test void fixedSplitCommutesWithAnEnergyReferenceMigration() {
        var initial=parcel();var target=new EnergyReference("future-reference",initial.reference().components(),new double[]{1200,-400},false);
        var oldSplit=initial.split(new double[]{.2,.8});var newSplit=initial.rebase(target).split(new double[]{.2,.8});
        for(int i=0;i<2;i++)assertEquals(oldSplit.get(i).rebase(target).internalEnergy(),newSplit.get(i).internalEnergy(),1e-8);
        assertArrayEquals(initial.moles(),sum(oldSplit.get(0).moles(),oldSplit.get(1).moles()),1e-12);
    }
    private static double[] sum(double[] a,double[] b){for(int i=0;i<a.length;i++)a[i]+=b[i];return a;}
}
