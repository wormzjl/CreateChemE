package com.wormzjl.createcheme.science.thermo.phase;

import java.lang.reflect.Method;

/** A minimal reflective runner of JUnit 5 @Test methods (no Gradle): prints PASS/FAIL per method. */
public final class RunTests {
    public static void main(String[] args) throws Exception {
        int failed = 0, passed = 0;
        for (String name : args) {
            Class<?> type = Class.forName(name);
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(org.junit.jupiter.api.Test.class)) continue;
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                method.setAccessible(true);
                long start = System.nanoTime();
                try {
                    method.invoke(instance);
                    passed++;
                    System.out.printf("PASS %s.%s (%.2f s)%n", type.getSimpleName(), method.getName(), (System.nanoTime() - start) / 1e9);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    failed++;
                    System.out.printf("FAIL %s.%s: %s%n", type.getSimpleName(), method.getName(), e.getCause());
                    StackTraceElement[] trace = e.getCause().getStackTrace();
                    for (int i = 0; i < Math.min(14, trace.length); i++) System.out.println("      at " + trace[i]);
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
    }
}
