package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Frozen literature fixture, parsed independently of the production material index. */
public final class Cdu17TestCatalog {
    public static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final String PREFIX = "/materials/cdu17-reference/";
    private static final MaterialCatalog CATALOG = load();

    private Cdu17TestCatalog() {}

    public static MaterialCatalog catalog() { return CATALOG; }

    /** Scope the fixture to the calling test without publishing a process-wide catalog. */
    public static <T> T with(Supplier<T> action) {
        return MaterialRuntime.with(CATALOG, PACKAGE_ID, action);
    }

    private static MaterialCatalog load() {
        var resources = new LinkedHashMap<String, String>();
        for (var entry : JsonParser.parseString(read("index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            if (resources.putIfAbsent(path, read(path)) != null)
                throw new IllegalArgumentException("Duplicate CDU17 fixture resource: " + path);
        }
        return MaterialCatalog.parse(resources);
    }

    private static String read(String path) {
        try (var stream = Cdu17TestCatalog.class.getResourceAsStream(PREFIX + path)) {
            if (stream == null) throw new IllegalArgumentException("Missing CDU17 fixture: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
