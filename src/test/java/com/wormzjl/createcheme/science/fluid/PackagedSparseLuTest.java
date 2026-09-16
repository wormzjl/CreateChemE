package com.wormzjl.createcheme.science.fluid;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedSparseLuTest {
    @TempDir Path extractedLibraries;

    @Test void solvesUsingOnlyTheBuiltModAndItsEmbeddedLibraries() throws Exception {
        Path mod = Path.of(System.getProperty("fluid.modJar"));
        var urls = new ArrayList<URL>();
        urls.add(mod.toUri().toURL());
        try (var jar = new ZipFile(mod.toFile())) {
            assertNotNull(jar.getEntry("META-INF/licenses/ejml-Apache-2.0.txt"));
            assertNull(jar.getEntry("com/wormzjl/createcheme/fluid/gametest/FluidTestMod.class"),
                    "Disposable test mod must not ship in the production artifact");
            for (String module : new String[] {"ejml-core", "ejml-ddense", "ejml-dsparse"}) {
                String name = module + "-0.44.0.jar";
                var entry = jar.getEntry("META-INF/jarjar/" + name);
                assertNotNull(entry, "Missing packaged dependency " + name);
                Path library = extractedLibraries.resolve(name);
                try (var stream = jar.getInputStream(entry)) { Files.copy(stream, library); }
                urls.add(library.toUri().toURL());
            }
        }
        // Platform parent prevents the Gradle/JUnit dependency classpath from hiding packaging mistakes.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> matrixType = loader.loadClass("com.wormzjl.createcheme.science.fluid.linalg.SparseMatrix");
            Object matrix = matrixType.getConstructor(int.class, int[].class, int[].class, double[].class)
                    .newInstance(2, new int[] {0, 1, 3}, new int[] {1, 0, 1}, new double[] {1, 2, 3});
            Class<?> solver = loader.loadClass("com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver");
            double[] result = (double[]) solver.getMethod("solve", matrixType, double[].class)
                    .invoke(null, matrix, new double[] {4, 7});
            assertArrayEquals(new double[] {1, 2}, result, 1e-12);
        }
    }
}
