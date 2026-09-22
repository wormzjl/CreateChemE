package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidDeviceSpecTest {
    @Test void allRequiredCrudePresetsShareTheExactQualifiedPropertyBasis() {
        var presets=FluidPresetCatalog.resolve(MaterialCatalog.bundled());assertEquals(6,presets.size());assertTrue(presets.stream().anyMatch(p->p.id().equals("createcheme:demo_slurry")&&p.solids().volumeFraction()==.1));
        for(var preset:presets){assertEquals(com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1,preset.moleFractions().length);assertEquals(1,java.util.Arrays.stream(preset.moleFractions()).sum(),1e-12);}
        assertTrue(presets.stream().anyMatch(p->p.name().equals("Cold Lake Blend")));assertTrue(presets.stream().anyMatch(p->p.name().equals("WTI Light Export")));
    }
}
