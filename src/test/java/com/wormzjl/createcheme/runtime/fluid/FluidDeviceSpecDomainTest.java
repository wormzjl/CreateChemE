package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.science.fluid.network.FlowControl;
import com.wormzjl.createcheme.science.fluid.network.PipeResistance;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F4, T1.1: the device input bounds follow the property package, not constants. A spec is structurally checked when it
 * is built and checked against the model's domain where it meets the model (validate, initialize; the edit handler and
 * the world's placement defaults call validate), with the dedicated thermo-domain error.
 */
class FluidDeviceSpecDomainTest {
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,"createcheme:tjl20_methane_nitrogen",1e-9);
    private PhysicalFluidTopology.Device device(TopologyCompiler.Kind kind) {
        return new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,64,0),kind,PhysicalFluidTopology.Direction.EAST,
                new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
    }

    @Test void aNitrogenReservoirAt200KelvinIsValidAndOneAt50KelvinIsRefusedWithTheDedicatedError() {
        var cold=new FluidDeviceSpec(1,200,101325,FluidDeviceSpec.nitrogen(catalog).composition());
        assertDoesNotThrow(()->cold.validate(model,TopologyCompiler.Kind.RESERVOIR));
        var tank=cold.initialize(device(TopologyCompiler.Kind.RESERVOIR),model,()->{});
        assertEquals(200,tank.state().temperature());
        var colder=new FluidDeviceSpec(1,50,101325,FluidDeviceSpec.nitrogen(catalog).composition());
        var refused=assertThrows(ThermoDomainViolation.class,()->colder.validate(model,TopologyCompiler.Kind.RESERVOIR));
        assertEquals("Thermo domain: Nitrogen at 50.00 K is below its valid range 63.151..900 K (package createcheme:tjl20_methane_nitrogen); the state cannot be evaluated",refused.getMessage());
        assertThrows(ThermoDomainViolation.class,()->colder.initialize(device(TopologyCompiler.Kind.RESERVOIR),model,()->{}));
    }

    @Test void aGeneratorIsCheckedAgainstWhatItHoldsAndControlsAreOnlyStructural() {
        int water=model.componentCount()-1;double[] n=new double[model.componentCount()];n[water]=1;
        var ice=new FluidDeviceSpec(1,260,101325,n);
        assertEquals("Water",assertThrows(ThermoDomainViolation.class,()->ice.validate(model,TopologyCompiler.Kind.GENERATOR)).component());
        double[] crude=new double[model.componentCount()];crude[model.components().indexOf("crude_pc02")]=1;
        assertEquals("crude_pc02",assertThrows(ThermoDomainViolation.class,()->new FluidDeviceSpec(1,280,101325,crude).validate(model,TopologyCompiler.Kind.GENERATOR)).component());
        assertDoesNotThrow(()->new FluidDeviceSpec(1,700,101325,crude).validate(model,TopologyCompiler.Kind.GENERATOR),"the old 600 K ceiling is gone; the components declare 900 K");
        // The menu's controls carry no temperature or pressure range of their own: the server refuses with the domain's.
        assertDoesNotThrow(()->new FluidNetwork.Controls(50,101325,.05,0,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(-1,101325,.05,0,.01,500000,n),"but must be physical");
        assertThrows(IllegalArgumentException.class,()->new FluidDeviceSpec(1,0,101325,n));
        assertEquals(ThermoDomainViolation.PACKAGE,assertThrows(ThermoDomainViolation.class,()->model.domain().checkPressure(50)).component(),"a valve target outside the envelope");
    }
}
