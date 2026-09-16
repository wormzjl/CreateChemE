package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.CompletionWakeup;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CompletionWakeupTest {
    @Test void manyWorkerSignalsCreateOneOwnerTaskAndClosureSuppressesLateCallbacks() throws Exception {
        var mailbox=new ConcurrentLinkedQueue<Runnable>();var calls=new AtomicInteger();var owner=Thread.currentThread();
        var wakeup=new CompletionWakeup(mailbox::add,()->{assertSame(owner,Thread.currentThread());calls.incrementAndGet();});
        var worker=Thread.ofPlatform().start(()->{for(int i=0;i<1000;i++)wakeup.signal();});worker.join(2000);assertFalse(worker.isAlive());
        assertEquals(1,mailbox.size());assertEquals(0,calls.get());mailbox.remove().run();assertEquals(1,calls.get());
        wakeup.signal();wakeup.close();mailbox.remove().run();wakeup.signal();assertEquals(1,calls.get());assertTrue(mailbox.isEmpty());
    }
    @Test void completionArrivingDuringThePumpCannotLoseItsWakeup() {
        var mailbox=new ConcurrentLinkedQueue<Runnable>();var calls=new AtomicInteger();var holder=new CompletionWakeup[1];
        holder[0]=new CompletionWakeup(mailbox::add,()->{if(calls.incrementAndGet()==1)holder[0].signal();});
        holder[0].signal();mailbox.remove().run();assertEquals(1,mailbox.size());mailbox.remove().run();assertEquals(2,calls.get());assertTrue(mailbox.isEmpty());holder[0].close();
    }
}
