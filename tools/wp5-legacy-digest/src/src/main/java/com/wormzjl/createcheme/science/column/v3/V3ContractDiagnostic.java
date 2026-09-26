package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** A stable, UI-safe contract diagnostic emitted before numerical work is admitted. */
public record V3ContractDiagnostic(String code, String detail) {
    public V3ContractDiagnostic {
        code = requireBounded(code, "code", 64);
        detail = requireBounded(detail, "detail", 512);
    }

    private static String requireBounded(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
        }
        return value;
    }
}
