package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/** Typed thermodynamic failure with optional phase context; never a numerical success sentinel. */
public final class V3ThermoException extends RuntimeException {
    private final Code code;
    private final V3Phase phase;

    public V3ThermoException(Code code, V3Phase phase, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.phase = phase;
    }

    public V3ThermoException(Code code, V3Phase phase, String message) {
        this(code, phase, message, null);
    }

    public Code code() {
        return code;
    }

    public V3Phase phase() {
        return phase;
    }

    public enum Code {
        DOMAIN,
        EOS_ROOT_FAILURE,
        FLASH_NONCONVERGENCE
    }
}
