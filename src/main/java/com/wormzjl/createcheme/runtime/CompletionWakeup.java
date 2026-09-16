package com.wormzjl.createcheme.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces terminal notifications into at most one pending owner-thread mailbox task.
 * The executor must enqueue asynchronously onto the constructing thread; it must never run inline.
 * A signal carries no result and never releases service capacity. Only the owner pump may drain results.
 */
public final class CompletionWakeup implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Executor mailbox;
    private final Runnable pump;
    private final AtomicBoolean pending=new AtomicBoolean(),closed=new AtomicBoolean();
    public CompletionWakeup(Executor mailbox,Runnable pump){this.mailbox=Objects.requireNonNull(mailbox);this.pump=Objects.requireNonNull(pump);}
    public void signal() {
        if(closed.get()||!pending.compareAndSet(false,true))return;
        try{mailbox.execute(()->{owned();pending.set(false);if(!closed.get())pump.run();});}
        catch(RuntimeException failure){pending.set(false);throw failure;}
    }
    @Override public void close(){owned();closed.set(true);}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Completion pump must run on its owning thread");}
}
