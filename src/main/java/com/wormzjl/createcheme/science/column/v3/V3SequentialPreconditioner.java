package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;

/** Request-local sequential seed strategy; a prepared state still requires rigorous MESH correction and audit. */
interface V3SequentialPreconditioner {
    V3PreconditionerId id();

    V3PreconditionerResult prepare(
            V3PreconditionerRequest request, V3ThermoModel thermo, V3ThermoWorkspace workspace);
}
