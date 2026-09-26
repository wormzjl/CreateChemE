package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;

/** Serial input-only feature preparation, never a corrected native benchmark. */
public final class V3HybridBaselineExport {
    private V3HybridBaselineExport() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input-only-jsonl output-jsonl");
        var gson = new Gson(); int count = 0;
        try (var lines = Files.lines(Path.of(args[0]));
             var writer = Files.newBufferedWriter(Path.of(args[1]), StandardOpenOption.CREATE_NEW)) {
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                var row = JsonParser.parseString(iterator.next()).getAsJsonObject();
                var input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
                var anchors = new LinkedHashMap<String, Object>();
                for (var branch : V3CondenserPhaseBranch.values())
                    anchors.put(branch.name(), V3HybridBaseline.build(input, branch, V3SolveControl.UNBOUNDED));
                writer.write(gson.toJson(Map.of("id", row.get("id").getAsString(),
                        "revision", V3HybridBaseline.REVISION, "anchors", anchors)));
                writer.newLine(); writer.flush();
                if (++count % 200 == 0) System.out.println("anchors=" + count);
            }
        }
        System.out.println("completed anchors=" + count);
    }
}
