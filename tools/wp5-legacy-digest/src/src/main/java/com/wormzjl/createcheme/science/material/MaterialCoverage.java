package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * The declared coverage of a reference record: a grade from the vocabulary of the unified multiphase thermodynamics
 * plan (section 3; decision D7 made it a P2 record field) and the evidence it rests on. Read from the {@code coverage}
 * object of a {@code spine} or {@code crystals} record; a record without one is refused.
 */
public record MaterialCoverage(Grade grade, String evidence) {
    public enum Grade {
        /** Held to the pilot's acceptance targets by tests named in the evidence. */
        QUALIFIED("qualified"),
        /** Usable, with a declared error the evidence states. */
        ESTIMATED_DECLARED_ERROR("estimated_declared_error"),
        /** Carried for research; a consumer must not rely on it. */
        RESEARCH_ONLY("research_only"),
        /** Known to be missing; the record only documents the gap. */
        UNAVAILABLE("unavailable");

        private final String key;
        Grade(String key) { this.key = key; }
        public String key() { return key; }
        static Grade parse(String key) {
            for (Grade grade : values()) if (grade.key.equals(key)) return grade;
            throw new IllegalArgumentException("coverage.grade: expected qualified, estimated_declared_error, research_only or unavailable, got " + key);
        }
    }

    public MaterialCoverage {
        Objects.requireNonNull(grade);
        Objects.requireNonNull(evidence);
    }

    static MaterialCoverage read(JsonObject record) {
        if (!record.has("coverage")) throw new IllegalArgumentException("coverage: required field (grade and evidence)");
        JsonObject o = MaterialCatalog.object(record, "coverage");
        return new MaterialCoverage(Grade.parse(MaterialCatalog.string(o, "grade")), MaterialCatalog.string(o, "evidence"));
    }
}
