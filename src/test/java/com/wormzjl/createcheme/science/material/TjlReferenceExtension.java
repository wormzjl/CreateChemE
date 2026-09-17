package com.wormzjl.createcheme.science.material;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.*;

/** Uses unchanged Java thermodynamic reference tables only for the old numerical state fixtures. */
public final class TjlReferenceExtension implements InvocationInterceptor {
    @Override public void interceptTestMethod(Invocation<Void> invocation,ReflectiveInvocationContext<Method> method,ExtensionContext context) throws Throwable {
        Throwable[] failure=new Throwable[1];
        MaterialRuntime.with(com.wormzjl.createcheme.science.column.v3.thermo.V3TjlReferenceCatalog.catalog(),"createcheme:tjl19_dwsim",()->{
            try { invocation.proceed(); } catch(Throwable e) { failure[0]=e; } return null;
        });
        if(failure[0]!=null)throw failure[0];
    }
}
