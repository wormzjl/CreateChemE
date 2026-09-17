package com.wormzjl.createcheme.science.material;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/** Test-scoped fixture context; does not modify the server/global catalog. */
public final class Cdu17FixtureExtension implements InvocationInterceptor {
    @Override public void interceptTestMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> context, ExtensionContext extension) throws Throwable {
        invoke(invocation);
    }

    @Override public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> context, ExtensionContext extension) throws Throwable {
        invoke(invocation);
    }

    private static void invoke(Invocation<Void> invocation) throws Throwable {
        Throwable[] failure = new Throwable[1];
        Cdu17TestCatalog.with(() -> {
            try { invocation.proceed(); }
            catch (Throwable thrown) { failure[0] = thrown; }
            return null;
        });
        if (failure[0] != null) throw failure[0];
    }
}
